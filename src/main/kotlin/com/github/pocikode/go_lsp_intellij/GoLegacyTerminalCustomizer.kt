package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.File
import java.nio.file.Path

/** Compatibility hook for terminal sessions created through the legacy local-terminal path. */
@Suppress("DEPRECATION")
internal class GoLegacyTerminalCustomizer : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: MutableList<String>,
        env: MutableMap<String, String>,
        eelDescriptor: com.intellij.platform.eel.EelDescriptor,
    ): MutableList<String> {
        val sdk = GoProjectModelState.getInstance(project).sdkPath.trim()
        if (sdk.isEmpty()) return command
        val root = Path.of(sdk)
        env["GOROOT"] = root.toString()
        env["GOTOOLCHAIN"] = "local"
        env["PATH"] = root.resolve("bin").toString() + File.pathSeparator + env["PATH"].orEmpty()
        return command
    }
}
