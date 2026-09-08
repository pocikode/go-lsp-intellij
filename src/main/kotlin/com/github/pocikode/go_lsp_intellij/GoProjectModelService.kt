package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
internal class GoProjectModelService(private val project: Project) {
    private val generation = AtomicLong()

    @Volatile
    var model: GoProjectModel = GoProjectModel()
        private set

    init {
        project.messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<out VFileEvent>) {
                if (events.any { it.file?.name in MANIFESTS }) refresh()
            }
        })
    }

    fun refresh(): GoProjectModel {
        val currentGeneration = generation.incrementAndGet()
        val base = project.basePath?.let(Path::of) ?: return publish(GoProjectModel(errors = listOf("Project has no base path.")))
        val projectSettings = GoProjectModelState.getInstance(project)
        val go = projectSettings.sdkPath.takeIf(String::isNotBlank)?.let(Path::of)?.resolve("bin")
            ?.resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "go.exe" else "go")
            ?.takeIf { Files.isExecutable(it) }
            ?: GoLspDiscovery.findGoTool("go")?.let(Path::of)
        if (go == null) return publish(GoProjectModel(errors = listOf("The Go executable was not found.")))

        val env = goEnv(go, base) ?: return publish(GoProjectModel(errors = listOf("Could not query the Go environment.")))
        val sdk = env["GOROOT"]?.let(Path::of)?.let { root ->
            GoSdk(root, env["GOVERSION"].orEmpty(), root.resolve("bin/go"), listOf(root.resolve("src")))
        }
        val context = GoBuildContext(
            tags = projectSettings.buildTags.split(Regex("[ ,]+" )).filter(String::isNotBlank).distinct(),
            goos = projectSettings.goos.ifBlank { env["GOOS"] },
            goarch = projectSettings.goarch.ifBlank { env["GOARCH"] },
            cgoEnabled = projectSettings.cgoEnabled,
            vendoring = projectSettings.useVendoring || Files.isDirectory(base.resolve("vendor")),
            gopath = projectSettings.gopath.ifBlank { env["GOPATH"].orEmpty() }.split(java.io.File.pathSeparator)
                .filter(String::isNotBlank).map(Path::of),
        )
        val directories = moduleDirectories(go, base)
        val goArguments = buildList {
            addAll(listOf("list", "-json", "-e"))
            if (context.tags.isNotEmpty()) addAll(listOf("-tags", context.tags.joinToString(",")))
            add("./...")
        }
        val packages = directories.flatMap { directory ->
            run(go, directory, *goArguments.toTypedArray()).stdout.let(GoProjectModelParsers::packages)
        }.distinctBy { it.importPath to it.directory }
        val modules = directories.mapNotNull { directory ->
            val report = run(go, directory, "list", "-m", "-json", "all")
            val dependencies = GoDependencyParsers.modules(report.stdout)
            val main = dependencies.firstOrNull { it.main }
            GoModule(main?.path ?: directory.fileName.toString(), directory, env["GOVERSION"], dependencies,
                packages.filter { it.directory.startsWith(directory) })
        }
        val libraries = buildList {
            sdk?.let { add(GoExternalLibrary("Go SDK ${it.version}", it.sourceRoots, it.version, GoExternalLibrary.Kind.SDK)) }
            val moduleCache = env["GOMODCACHE"]?.let(Path::of)
            if (moduleCache != null) add(GoExternalLibrary("Go module cache", listOf(moduleCache), kind = GoExternalLibrary.Kind.MODULE_CACHE))
            context.gopath.forEach { add(GoExternalLibrary("GOPATH", listOf(it), kind = GoExternalLibrary.Kind.GOPATH)) }
        }
        val result = GoProjectModel(sdk, modules, packages, libraries, context)
        return if (generation.get() == currentGeneration) publish(result) else model
    }

    private fun moduleDirectories(go: Path, base: Path): List<Path> {
        val work = base.resolve("go.work")
        if (Files.exists(work)) return run(go, base, "work", "edit", "-json")?.stdout
            ?.let { GoDependencyParsers.workspaceDirectories(it, base) }.orEmpty().ifEmpty { listOf(base) }
        return Files.walk(base, 6).use { paths ->
            paths.filter { it.fileName.toString() == "go.mod" }.map { it.parent }.toList().ifEmpty { listOf(base) }
        }
    }

    private fun goEnv(go: Path, directory: Path): Map<String, String>? = run(go, directory, "env", "-json", "GOROOT", "GOVERSION", "GOOS", "GOARCH", "CGO_ENABLED", "GOPATH", "GOMODCACHE")
        ?.takeIf { it.exitCode == 0 }?.let { com.google.gson.Gson().fromJson(it.stdout, Map::class.java) }
        ?.mapNotNull { (key, value) -> key?.toString()?.let { it to value.toString() } }?.toMap()

    private fun run(go: Path, directory: Path, vararg args: String): Result = try {
        val output = CapturingProcessHandler(GeneralCommandLine(go.toString()).withParameters(*args)
            .withWorkingDirectory(directory).withCharset(StandardCharsets.UTF_8)).runProcess(120_000)
        Result(output.stdout, output.stderr, output.exitCode)
    } catch (exception: Exception) { Result("", exception.message.orEmpty(), -1) }

    private fun publish(value: GoProjectModel): GoProjectModel {
        model = value
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) project.messageBus.syncPublisher(GoProjectModelListener.TOPIC).modelUpdated(value)
        }
        return value
    }

    private data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    companion object {
        private val MANIFESTS = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
        fun getInstance(project: Project): GoProjectModelService = project.getService(GoProjectModelService::class.java)
    }
}

internal fun interface GoProjectModelListener {
    fun modelUpdated(model: GoProjectModel)
    companion object { val TOPIC = com.intellij.util.messages.Topic.create("Go project model updated", GoProjectModelListener::class.java) }
}
