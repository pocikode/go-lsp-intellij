package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** The Run/Debug Configurations form for [GoRunConfiguration]. */
class GoRunSettingsEditor : SettingsEditor<GoRunConfiguration>() {

    private val directory = TextFieldWithBrowseButton()
    private val target = JBTextField()
    private val goToolArguments = JBTextField()
    private val programArguments = JBTextField()
    private val environment = EnvironmentVariablesComponent()

    override fun createEditor(): JComponent {
        directory.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Working Directory")
                .withDescription("The directory go run starts in"),
        )
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Working directory:", directory)
            .addLabeledComponent("Package or file:", target)
            .addLabeledComponent("Go tool arguments:", goToolArguments)
            .addLabeledComponent("Program arguments:", programArguments)
            .addComponent(environment)
            .panel
    }

    override fun resetEditorFrom(configuration: GoRunConfiguration) {
        directory.text = configuration.directoryPath
        target.text = configuration.target
        goToolArguments.text = configuration.goToolArguments
        programArguments.text = configuration.programArguments
        environment.envData = configuration.envData
    }

    override fun applyEditorTo(configuration: GoRunConfiguration) {
        configuration.directoryPath = directory.text.trim()
        configuration.target = target.text.trim()
        configuration.goToolArguments = goToolArguments.text.trim()
        configuration.programArguments = programArguments.text.trim()
        configuration.envData = environment.envData
    }
}
