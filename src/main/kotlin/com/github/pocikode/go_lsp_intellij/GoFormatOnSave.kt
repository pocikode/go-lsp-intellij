package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Formats the current in-memory Go document with the local Go toolchain before saving. */
class GoFormatOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
    override val presentableName: String = "Go formatting"

    override fun isEnabledForProject(project: Project): Boolean =
        GoFormatOnSaveState.getInstance(project).enabled

    override suspend fun updateDocument(project: Project, document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.extension != "go") return

        val settings = GoLspSettingsState.getInstance()
        val gofmt = GoLspDiscovery.findGoTool("gofmt") ?: run {
            LOG.warn("gofmt was not found; skipping format-on-save for ${file.name}")
            return
        }

        val gofmtText = runFormatter(gofmt, document.text, project.basePath, file.name) ?: return
        val formattedText = if (settings.useGoimports) {
            val goimports = GoLspDiscovery.findGoTool("goimports")
            if (goimports == null) {
                LOG.debug("goimports was not found; using gofmt only for ${file.name}")
                gofmtText
            } else {
                runGoimports(goimports, gofmtText, File(file.parent.path), file.name) ?: gofmtText
            }
        } else {
            gofmtText
        }

        if (formattedText != document.text) {
            withContext(Dispatchers.EDT) {
                WriteAction.run<RuntimeException> {
                    document.setText(formattedText)
                }
            }
        }
    }

    private fun runGoimports(executable: String, input: String, directory: File, fileName: String): String? {
        val temporaryFile = try {
            Files.createTempFile(directory.toPath(), ".idea-go-format-", ".go").toFile()
        } catch (exception: Exception) {
            LOG.warn("Unable to create a temporary Go file for $fileName", exception)
            return null
        }

        return try {
            temporaryFile.writeText(input, StandardCharsets.UTF_8)
            val commandLine = GeneralCommandLine(executable, "-w", temporaryFile.absolutePath)
                .withWorkDirectory(directory)
                .withCharset(StandardCharsets.UTF_8)
            val output = CapturingProcessHandler(commandLine).runProcess(FORMAT_TIMEOUT_MS)
            if (output.isTimeout || output.isCancelled || output.exitCode != 0) {
                LOG.warn(
                    "${commandLine.commandLineString} failed for $fileName " +
                        "(exit code ${output.exitCode}): ${output.stderr.trim()}",
                )
                null
            } else {
                temporaryFile.readText(StandardCharsets.UTF_8)
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Unable to run $executable for $fileName", exception)
            null
        } finally {
            Files.deleteIfExists(temporaryFile.toPath())
        }
    }

    private fun runFormatter(executable: String, input: String, workDirectory: String?, fileName: String): String? {
        return try {
            val commandLine = GeneralCommandLine(executable)
                .withWorkDirectory(workDirectory)
                .withCharset(StandardCharsets.UTF_8)
            val process = commandLine.createProcess()
            process.outputStream.use { output ->
                output.write(input.toByteArray(StandardCharsets.UTF_8))
            }

            val output = CapturingProcessHandler(
                process,
                StandardCharsets.UTF_8,
                commandLine.commandLineString,
            ).runProcess(FORMAT_TIMEOUT_MS)
            if (output.isTimeout || output.isCancelled || output.exitCode != 0) {
                LOG.warn(
                    "${commandLine.commandLineString} failed for $fileName " +
                        "(exit code ${output.exitCode}): ${output.stderr.trim()}",
                )
                null
            } else {
                output.stdout
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Unable to run $executable for $fileName", exception)
            null
        }
    }

    private companion object {
        const val FORMAT_TIMEOUT_MS = 10_000
        val LOG: Logger = Logger.getInstance(GoFormatOnSave::class.java)
    }
}

class GoFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<GoFormatOnSaveInfo> =
        listOf(GoFormatOnSaveInfo(context))

    override fun getSearchableOptions(): Collection<String> =
        listOf("Reformat Go files with gofmt")
}

class GoFormatOnSaveInfo(context: ActionOnSaveContext) : ActionOnSaveInfo(context) {
    private var enabled = GoFormatOnSaveState.getInstance(context.project).enabled

    override fun apply() {
        GoFormatOnSaveState.getInstance(project).enabled = enabled
    }

    override fun isModified(): Boolean =
        enabled != GoFormatOnSaveState.getInstance(project).enabled

    override fun getActionOnSaveName(): String = "Reformat Go files with gofmt"

    override fun isActionOnSaveEnabled(): Boolean = enabled

    override fun setActionOnSaveEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
