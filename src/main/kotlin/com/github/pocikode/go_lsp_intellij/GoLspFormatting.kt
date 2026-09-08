package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Runs Go source text through the local toolchain: `gofmt`, then `goimports` when it is enabled and
 * installed, which is also what adds the imports a newly generated method signature needs.
 *
 * Both tools are external processes, so every entry point here blocks and must be called off the
 * UI thread. Shared by format-on-save ([GoFormatOnSave]) and "Implement interface"
 * ([GoLspImplementInterfaceService]).
 */
internal object GoLspFormatting {
    /** [text] formatted, or null when the toolchain is missing or refused it - leave the text alone then. */
    fun format(project: Project, file: VirtualFile, text: String): String? {
        val gofmt = GoToolchain.executable(project)?.let { java.io.File(it).parentFile.resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "gofmt.exe" else "gofmt").absolutePath }
            ?.takeIf { java.io.File(it).canExecute() } ?: GoLspDiscovery.findGoTool("gofmt") ?: run {
            LOG.warn("gofmt was not found; leaving ${file.name} unformatted")
            return null
        }

        val gofmtText = runFormatter(gofmt, text, project.basePath, file.name) ?: return null
        if (!GoLspSettingsState.getInstance().useGoimports) return gofmtText

        val goimports = GoToolchain.executable(project)?.let { java.io.File(it).parentFile.resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "goimports.exe" else "goimports").absolutePath }
            ?.takeIf { java.io.File(it).canExecute() } ?: GoLspDiscovery.findGoTool("goimports") ?: run {
            LOG.debug("goimports was not found; using gofmt only for ${file.name}")
            return gofmtText
        }
        val directory = file.parent?.path?.let(::File) ?: return gofmtText
        return runGoimports(goimports, gofmtText, directory, file.name) ?: gofmtText
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

    private const val FORMAT_TIMEOUT_MS = 10_000
    private val LOG: Logger = Logger.getInstance(GoLspFormatting::class.java)
}
