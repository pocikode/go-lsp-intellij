package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * The Run/Debug Configurations form for [GoTestRunConfiguration].
 *
 * The fields are the `go test` command line rather than a friendlier abstraction over it, so what
 * the gutter generated can be read, understood and adjusted in place.
 */
class GoTestSettingsEditor : SettingsEditor<GoTestRunConfiguration>() {

    private val directory = TextFieldWithBrowseButton()
    private val packagePattern = JBTextField()
    private val runPattern = JBTextField()
    private val goToolArguments = JBTextField()
    private val environment = EnvironmentVariablesComponent()

    override fun createEditor(): JComponent {
        directory.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Working Directory")
                .withDescription("The directory go test runs in"),
        )
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Working directory:", directory)
            .addLabeledComponent("Package pattern:", packagePattern)
            .addLabeledComponent("Pattern (-run):", runPattern)
            .addLabeledComponent("Go tool arguments:", goToolArguments)
            .addComponent(environment)
            .panel
    }

    override fun resetEditorFrom(configuration: GoTestRunConfiguration) {
        directory.text = configuration.directoryPath
        packagePattern.text = configuration.packagePattern
        runPattern.text = configuration.runPattern
        goToolArguments.text = configuration.goToolArguments
        environment.envData = configuration.envData
    }

    override fun applyEditorTo(configuration: GoTestRunConfiguration) {
        configuration.directoryPath = directory.text.trim()
        configuration.packagePattern = packagePattern.text.trim()
        configuration.runPattern = runPattern.text.trim()
        configuration.goToolArguments = goToolArguments.text.trim()
        configuration.envData = environment.envData
    }
}
