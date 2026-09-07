package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled

/** Describes one project-wide `gopls` process for the IntelliJ LSP API. */
class GoLspServerDescriptor(project: Project, private val executable: String) :
    ProjectWideLspServerDescriptor(project, "Go") {

    override fun isSupportedFile(file: VirtualFile): Boolean = GoLspSupport.isGoFile(file)

    /** `gopls` expects the LSP language identifier `go`; the default derives it from the file type name. */
    override fun getLanguageId(file: VirtualFile): String = "go"

    /**
     * Go-to-definition is handled by [GoLspReferenceProvider] instead of the platform client, whose
     * reference provider stays silent on Cmd/Ctrl+hover. Keeping both would yield duplicate targets.
     */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer = LspGoToDefinitionDisabled
    }

    override fun createCommandLine(): GeneralCommandLine {
        val settings = GoLspSettingsState.getInstance()
        val arguments = settings.goplsArguments.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return GeneralCommandLine(executable)
            .withParameters(arguments)
            .withWorkDirectory(project.basePath)
    }
}
