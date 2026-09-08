package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal data class GoDependencyReport(
    val modules: List<GoModuleDependency> = emptyList(),
    val graph: List<GoModuleGraphEdge> = emptyList(),
    val vulnerabilities: List<GoVulnerability> = emptyList(),
    val errors: List<String> = emptyList(),
)

internal data class GoToolResult(val stdout: String, val stderr: String, val exitCode: Int, val timedOut: Boolean)

@Service(Service.Level.PROJECT)
internal class GoDependencyService(private val project: Project) {
    private val refreshGeneration = AtomicLong()

    @Volatile
    var report: GoDependencyReport = GoDependencyReport()
        private set

    init {
        project.messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<out VFileEvent>) {
                if (events.any { it.file?.name in setOf("go.mod", "go.sum", "go.work", "go.work.sum") }) {
                    GoDependencyActions.refresh(project, activate = false)
                }
            }
        })
    }

    fun refresh(indicator: ProgressIndicator? = null): GoDependencyReport {
        val generation = refreshGeneration.incrementAndGet()
        if (GoLspDiscovery.findGoTool("go") == null) {
            return publish(GoDependencyReport(errors = listOf("The Go executable was not found.")))
        }
        val directories = moduleDirectories()
        if (directories.isEmpty()) return publish(GoDependencyReport(errors = listOf("No Go module was found.")))
        val errors = mutableListOf<String>()

        val modules = directories.flatMap { directory ->
            runGo(directory, MODULE_TIMEOUT_MS, indicator, "list", "-m", "-json", "-u", "-retracted", "all")
                ?.let { result ->
                    if (!result.success) errors += result.failure("go list -m in $directory")
                    runCatching { GoDependencyParsers.modules(result.stdout) }
                        .getOrElse { errors += "Could not parse go list output: ${it.message}"; emptyList() }
                }.orEmpty()
        }.distinctBy { it.path to it.version }

        val graph = directories.flatMap { directory ->
            runGo(directory, MODULE_TIMEOUT_MS, indicator, "mod", "graph")
                ?.let { result ->
                    if (!result.success) errors += result.failure("go mod graph in $directory")
                    GoDependencyParsers.graph(result.stdout)
                }.orEmpty()
        }.distinct()

        val govulncheck = GoLspDiscovery.findGoTool("govulncheck")
        if (govulncheck == null) {
            errors += "govulncheck is not installed; gopls still reports imported vulnerabilities in go.mod."
        }
        val vulnerabilities = if (govulncheck == null) emptyList() else directories.flatMap { directory ->
            val result = run(govulncheck, directory, VULNCHECK_TIMEOUT_MS, indicator, "-json", "./...")
            if (!result.success) errors += result.failure("govulncheck in $directory")
            runCatching { GoDependencyParsers.vulnerabilities(result.stdout) }
                .getOrElse { errors += "Could not parse govulncheck output: ${it.message}"; emptyList() }
        }.distinctBy { it.id to it.modulePath }

        val newReport = GoDependencyReport(modules, graph, vulnerabilities, errors.distinct())
        return if (refreshGeneration.get() == generation) publish(newReport) else report
    }

    fun runModuleCommand(
        arguments: List<String>,
        workspaceArguments: List<String>? = null,
        manifest: VirtualFile? = null,
        indicator: ProgressIndicator? = null,
    ): List<GoToolResult> {
        val selectedManifest = manifest ?: GoDependencyActions.selectedModuleFile(project)
        val selectedDirectory = selectedManifest?.parent?.toNioPath()
        val workFile = selectedManifest?.takeIf { it.name == "go.work" }?.toNioPath()
            ?: selectedDirectory?.let(::enclosingWorkFile)
            ?: if (selectedManifest == null) project.basePath?.let(Path::of)?.resolve("go.work")?.takeIf(Files::exists) else null
        val directories = when {
            selectedManifest?.name == "go.mod" -> listOfNotNull(selectedDirectory)
            workFile != null && workspaceArguments == null -> workspaceDirectories(workFile)
            else -> selectedDirectory?.let(::listOf) ?: moduleDirectories()
        }
        val command = if (workFile != null && workspaceArguments != null) workspaceArguments else arguments
        val targets = if (workFile != null && workspaceArguments != null) listOf(workFile.parent) else directories
        if (targets.isEmpty()) return emptyList()

        val results = targets.mapNotNull { runGo(it, COMMAND_TIMEOUT_MS, indicator, *command.toTypedArray()) }
        targets.forEach(::refreshFiles)
        workFile?.parent?.let(::refreshFiles)
        refresh(indicator)
        return results
    }

    private fun runGo(
        directory: Path,
        timeout: Int,
        indicator: ProgressIndicator? = null,
        vararg arguments: String,
    ): GoToolResult? {
        val executable = GoToolchain.executable(project) ?: return null
        return run(executable, directory, timeout, indicator, *arguments)
    }

    private fun run(
        executable: String,
        directory: Path,
        timeout: Int,
        indicator: ProgressIndicator? = null,
        vararg arguments: String,
    ): GoToolResult = try {
        val commandLine = GeneralCommandLine(executable)
            .withParameters(*arguments)
            .withWorkingDirectory(directory)
            .withCharset(StandardCharsets.UTF_8)
        GoToolchain.configure(commandLine, project)
        val handler = CapturingProcessHandler(commandLine)
        val output = if (indicator == null) handler.runProcess(timeout) else {
            handler.runProcessWithProgressIndicator(indicator, timeout)
        }
        GoToolResult(output.stdout, output.stderr, output.exitCode, output.isTimeout)
    } catch (exception: ExecutionException) {
        GoToolResult("", exception.message.orEmpty(), -1, false)
    }

    private fun workingDirectory(): Path? {
        val selected = GoDependencyActions.selectedModuleFile(project)
        val selectedDirectory = selected?.parent?.toNioPath()
        if (selectedDirectory != null) return selectedDirectory
        val base = project.basePath?.let(Path::of) ?: return null
        if (Files.exists(base.resolve("go.work")) || Files.exists(base.resolve("go.mod"))) return base
        return Files.walk(base, 6).use { paths ->
            paths.filter { it.fileName.toString() == "go.mod" }.findFirst().orElse(null)?.parent
        }
    }

    private fun moduleDirectories(): List<Path> {
        val directory = workingDirectory() ?: return emptyList()
        val workFile = directory.resolve("go.work")
        return if (Files.exists(workFile)) workspaceDirectories(workFile) else listOf(directory)
    }

    private fun workspaceDirectories(workFile: Path): List<Path> {
        val result = runGo(workFile.parent, MODULE_TIMEOUT_MS, null, "work", "edit", "-json")
            ?: return emptyList()
        return if (result.success) GoDependencyParsers.workspaceDirectories(result.stdout, workFile.parent) else emptyList()
    }

    private fun enclosingWorkFile(directory: Path): Path? {
        val projectRoot = project.basePath?.let(Path::of)?.normalize()
        var current: Path? = directory.normalize()
        while (current != null) {
            val candidate = current.resolve("go.work")
            if (Files.exists(candidate)) return candidate
            if (current == projectRoot) return null
            current = current.parent
        }
        return null
    }

    private fun refreshFiles(directory: Path) {
        listOf("go.mod", "go.sum", "go.work", "go.work.sum", "vendor").forEach { name ->
            VfsUtil.markDirtyAndRefresh(false, true, true, directory.resolve(name))
        }
    }

    private fun publish(newReport: GoDependencyReport): GoDependencyReport {
        report = newReport
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) project.messageBus.syncPublisher(GoDependencyReportListener.TOPIC).reportUpdated(newReport)
        }
        return newReport
    }

    private val GoToolResult.success: Boolean get() = !timedOut && exitCode == 0

    private fun GoToolResult.failure(command: String): String = when {
        timedOut -> "$command timed out."
        stderr.isNotBlank() -> stderr.trim()
        else -> "$command exited with code $exitCode."
    }

    companion object {
        fun getInstance(project: Project): GoDependencyService = project.service()

        private const val MODULE_TIMEOUT_MS = 60_000
        private const val COMMAND_TIMEOUT_MS = 300_000
        private const val VULNCHECK_TIMEOUT_MS = 300_000
    }
}

internal fun interface GoDependencyReportListener {
    fun reportUpdated(report: GoDependencyReport)

    companion object {
        val TOPIC = com.intellij.util.messages.Topic.create(
            "Go dependency report updated",
            GoDependencyReportListener::class.java,
        )
    }
}
