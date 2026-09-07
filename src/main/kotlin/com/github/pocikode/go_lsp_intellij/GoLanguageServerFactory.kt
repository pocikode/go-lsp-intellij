package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

class GoLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        val executable = GoLspDiscovery.findExecutable()
            ?: error("gopls was not found. Configure its path in Settings | Tools | Go LSP.")
        val settings = GoLspSettingsState.getInstance()
        val arguments = settings.goplsArguments.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val commandLine = GeneralCommandLine(executable)
            .withParameters(arguments)
            .withWorkDirectory(project.basePath)

        return OSProcessStreamConnectionProvider().also { it.setCommandLine(commandLine) }
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl = LanguageClientImpl(project)

    override fun getServerInterface(): Class<out LanguageServer> = LanguageServer::class.java
}
