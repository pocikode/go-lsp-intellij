package dev.go_lsp.intellij

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class GoLspConfigurable : Configurable {
    private var panel: JPanel? = null
    private var pathField: TextFieldWithBrowseButton? = null
    private var argumentsField: JTextField? = null

    override fun getDisplayName() = "Go LSP"

    override fun createComponent(): JComponent {
        val settings = GoLspSettingsState.getInstance()
        pathField = TextFieldWithBrowseButton().apply {
            text = settings.goplsPath
            addBrowseFolderListener("Select gopls", null, null, FileChooserDescriptor(true, false, false, false, false, false))
        }
        argumentsField = JTextField(settings.goplsArguments)
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("gopls executable:"), pathField!!, 1, false)
            .addLabeledComponent(JBLabel("Arguments:"), argumentsField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = GoLspSettingsState.getInstance()
        return pathField?.text != settings.goplsPath || argumentsField?.text != settings.goplsArguments
    }

    override fun apply() {
        val settings = GoLspSettingsState.getInstance()
        settings.goplsPath = pathField?.text?.trim().orEmpty()
        settings.goplsArguments = argumentsField?.text?.trim().takeUnless { it.isNullOrEmpty() } ?: "serve"
    }

    override fun reset() {
        val settings = GoLspSettingsState.getInstance()
        pathField?.text = settings.goplsPath
        argumentsField?.text = settings.goplsArguments
    }

    override fun disposeUIResources() {
        panel = null
        pathField = null
        argumentsField = null
    }
}
