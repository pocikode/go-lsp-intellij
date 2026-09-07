package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.util.SystemInfo
import java.io.File

object GoLspDiscovery {
    fun findExecutable(): String? {
        val settingsPath = GoLspSettingsState.getInstance().goplsPath.trim()
        if (settingsPath.isNotEmpty()) {
            return settingsPath.takeIf { File(it).isFile && File(it).canExecute() }
        }

        val pathCandidates = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it, executableName()) }
            ?.firstOrNull { it.isFile && it.canExecute() }
        if (pathCandidates != null) return pathCandidates.absolutePath

        val goRoot = System.getenv("GOROOT")
        val goPath = System.getenv("GOPATH")
        val candidates = sequenceOf(
            goRoot?.let { File(it, "bin/${executableName()}") },
            goPath?.let { File(it, "bin/${executableName()}") },
            File(System.getProperty("user.home"), "go/bin/${executableName()}"),
            if (SystemInfo.isMac) File("/opt/homebrew/bin/${executableName()}") else null,
            if (SystemInfo.isLinux) File("/usr/local/bin/${executableName()}") else null,
        ).filterNotNull()

        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun executableName(): String = if (SystemInfo.isWindows) "gopls.exe" else "gopls"
}
