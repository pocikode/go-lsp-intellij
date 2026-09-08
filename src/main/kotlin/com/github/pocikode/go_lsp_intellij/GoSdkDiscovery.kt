package com.github.pocikode.go_lsp_intellij

import java.nio.file.Files
import java.nio.file.Path
import com.intellij.openapi.util.SystemInfo

internal object GoSdkDiscovery {
    // Latest stable patch for each supported minor, sourced from go.dev/dl.
    private val downloadableVersions = listOf(
        "go1.27.1", "go1.26.8", "go1.25.14", "go1.24.13", "go1.23.12", "go1.22.12",
    )

    fun downloadableVersions(): List<String> = downloadableVersions

    fun installed(): List<GoSdk> = candidates().mapNotNull { root ->
        val go = root.resolve("bin/go")
        if (!Files.isExecutable(go)) return@mapNotNull null
        val version = runCatching {
            Regex("go\\d+(?:\\.\\d+){1,2}(?:[a-z]\\d+)?")
                .find(ProcessBuilder(go.toString(), "version").start().inputStream.bufferedReader().readText())?.value
        }.getOrNull()
            ?: "Go SDK"
        GoSdk(root, version, go, listOf(root.resolve("src")))
    }.distinctBy { it.home.normalize() }.toList()

    /** Downloads a selected toolchain through the official Go version wrapper. */
    fun download(version: String): Path? {
        return download(version) { }
    }

    fun download(version: String, progress: (String) -> Unit): Path? {
        if (!Regex("go\\d+(?:\\.\\d+){1,2}(?:[a-z]\\d+)?").matches(version)) return null
        val go = GoLspDiscovery.findGoTool("go") ?: return null
        val wrapper = "golang.org/dl/$version@latest"
        progress("Installing $version download tool")
        val install = ProcessBuilder(go, "install", wrapper).inheritIO().start()
        if (install.waitFor() != 0) return null
        val wrapperPath = GoLspDiscovery.findGoTool(version) ?: return null
        val command = listOf(wrapperPath, "download")
        progress("Downloading $version toolchain")
        val process = ProcessBuilder(command).inheritIO().start()
        progress("Installing $version toolchain")
        return process.takeIf { it.waitFor() == 0 }?.let {
            candidates().firstOrNull { root -> root.fileName?.toString() == version && Files.isExecutable(root.resolve("bin/go")) }
                ?: Path.of(System.getProperty("user.home"), "sdk", version)
        }
    }

    private fun candidates(): Sequence<Path> {
        val home = Path.of(System.getProperty("user.home"))
        val pathRoot = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).asSequence()
            .map { Path.of(it).resolve(if (SystemInfo.isWindows) "go.exe" else "go") }
            .filter { Files.isExecutable(it) }
            .mapNotNull { it.parent?.parent }
        return sequenceOf(
            System.getenv("GOROOT")?.let(Path::of),
            Path.of("/usr/local/go"),
            Path.of("/opt/go"),
            if (SystemInfo.isMac) Path.of("/opt/homebrew/opt/go/libexec") else null,
            if (SystemInfo.isWindows) System.getenv("ProgramFiles")?.let { Path.of(it, "Go") } else null,
            if (SystemInfo.isWindows) System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "Go") } else null,
            home.resolve("sdk"),
        ).filterNotNull().asSequence().plus(pathRoot).flatMap { root ->
            if (Files.isDirectory(root.resolve("bin"))) sequenceOf(root)
            else runCatching { Files.list(root).use { it.toList().asSequence() } }.getOrDefault(emptySequence())
        }
    }
}
