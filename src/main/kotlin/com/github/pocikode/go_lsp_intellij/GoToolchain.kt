package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object GoToolchain {
    fun executable(project: Project): String? {
        val configured = GoProjectModelState.getInstance(project).sdkPath.trim()
        if (configured.isNotEmpty()) {
            val candidate = Path.of(configured).resolve("bin").resolve(if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go")
            if (Files.isExecutable(candidate)) return candidate.toString()
        }
        return GoLspDiscovery.findGoTool("go")
    }

    fun configure(commandLine: GeneralCommandLine, project: Project) {
        val sdk = GoProjectModelState.getInstance(project).sdkPath.trim()
        if (sdk.isEmpty()) return
        val root = Path.of(sdk)
        commandLine.environment["GOROOT"] = root.toString()
        // Prevent Go 1.21+ from silently replacing the project SDK with the toolchain requested
        // by go.mod. The project selector must control the exact compiler used by the IDE.
        commandLine.environment["GOTOOLCHAIN"] = "local"
        val bin = root.resolve("bin").toString()
        val currentPath = commandLine.environment["PATH"] ?: System.getenv("PATH").orEmpty()
        commandLine.environment["PATH"] = bin + File.pathSeparator + currentPath
    }
}
