package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

/** Adds the project Go SDK to the shell environment before the terminal process starts. */
internal class GoTerminalCustomizer : ShellExecOptionsCustomizer {
    override fun customizeExecOptions(project: Project, options: MutableShellExecOptions) {
        val sdk = GoProjectModelState.getInstance(project).sdkPath.trim()
        if (sdk.isEmpty()) return
        val root = Path.of(sdk)
        options.setEnvironmentVariable("GOROOT", root.toString())
        options.setEnvironmentVariable("GOTOOLCHAIN", "local")
        options.prependEntryToPATH(root.resolve("bin"))
    }
}
