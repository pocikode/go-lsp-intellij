package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentLinkCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.WorkspaceClientCapabilities

/** Describes one project-wide `gopls` process for the IntelliJ LSP API. */
class GoLspServerDescriptor(project: Project, private val executable: String) :
    ProjectWideLspServerDescriptor(project, "Go") {

    override fun isSupportedFile(file: VirtualFile): Boolean = GoLspSupport.isGoFile(file)

    /** `gopls` expects the LSP language identifier `go`; the default derives it from the file type name. */
    override fun getLanguageId(file: VirtualFile): String = "go"

    /**
     * Go-to-definition is handled by [GoLspReferenceProvider] instead of the platform client, whose
     * reference provider stays silent on Cmd/Ctrl+hover. Keeping both would yield duplicate targets.
     *
     * Semantic tokens are mapped to GoLand's colour keys by [GoLspSemanticTokens].
     *
     * Document links are off because `gopls` returns one pkg.go.dev link per import path, which the
     * platform client paints with the scheme's hyperlink attributes. That underlines and recolours
     * every import, and GoLand leaves import paths looking like the plain strings they are.
     */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer = LspGoToDefinitionDisabled
        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = GoLspSemanticTokens
        override val documentLinkCustomizer: LspDocumentLinkCustomizer = LspDocumentLinkDisabled
    }

    /**
     * The platform client asks for neither document symbols nor workspace symbols, and `gopls`
     * tailors both answers to what the client claims to support: without
     * `hierarchicalDocumentSymbolSupport` it replies with a flat list that carries no signatures
     * and no struct or interface members, which is exactly what the code vision above a
     * declaration and "Implement interface" are built from. See [GoLspSymbolRequests].
     */
    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.also { capabilities ->
            val textDocument = capabilities.textDocument
                ?: TextDocumentClientCapabilities().also { capabilities.textDocument = it }
            textDocument.documentSymbol = DocumentSymbolCapabilities().apply {
                hierarchicalDocumentSymbolSupport = true
            }
            val workspace = capabilities.workspace
                ?: WorkspaceClientCapabilities().also { capabilities.workspace = it }
            workspace.symbol = SymbolCapabilities()
        }

    /**
     * `gopls` reads its settings at startup from the initialization options and afterwards through
     * `workspace/configuration`; both are answered with the same map.
     */
    override fun createInitializationOptions(): Any = GOPLS_SETTINGS

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? =
        if (item.section.isNullOrEmpty() || item.section == "gopls") GOPLS_SETTINGS else null

    override fun createCommandLine(): GeneralCommandLine {
        val settings = GoLspSettingsState.getInstance()
        val arguments = settings.goplsArguments.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return GeneralCommandLine(executable)
            .withParameters(arguments)
            .withWorkDirectory(project.basePath)
    }

    private companion object {
        /**
         * `gopls` defaults `semanticTokens` to false, so without this the editor only ever sees the
         * TextMate grammar and Go looks flatter than it does in GoLand: no type, constant, field or
         * call-versus-declaration colouring. The string and number token streams are deliberately
         * left on - [GoLspSemanticTokens] ignores all but the format verbs inside a string.
         */
        val GOPLS_SETTINGS: Map<String, Any> = mapOf("semanticTokens" to true)
    }
}
