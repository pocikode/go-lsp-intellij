package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/** Project-scoped Go toolchain and build configuration, matching GoLand's project model boundary. */
internal class GoProjectConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private var sdkPath: TextFieldWithBrowseButton? = null
    private var version: JComboBox<String>? = null
    private var downloadVersion: JComboBox<String>? = null
    private var tags: JTextField? = null
    private var goos: JTextField? = null
    private var goarch: JTextField? = null
    private var gopath: JTextField? = null
    private var cgo: JCheckBox? = null
    private var vendoring: JCheckBox? = null
    private var discovered = emptyList<GoSdk>()

    override fun getDisplayName(): String = "Go"

    override fun createComponent(): JComponent {
        val state = GoProjectModelState.getInstance(project)
        discovered = GoSdkDiscovery.installed()
        sdkPath = TextFieldWithBrowseButton().apply {
            text = state.sdkPath
            addBrowseFolderListener("Select Go SDK (GOROOT)", null, project, FileChooserDescriptor(true, false, false, false, false, false))
        }
        version = JComboBox((discovered.map { it.version } + "Custom GOROOT...").distinct().toTypedArray()).apply {
            selectedItem = discovered.firstOrNull { it.home.toString() == state.sdkPath }?.version ?: "Custom GOROOT..."
            addActionListener {
                discovered.firstOrNull { it.version == selectedItem }?.let { sdkPath?.text = it.home.toString() }
            }
        }
        tags = JTextField(state.buildTags)
        goos = JTextField(state.goos)
        goarch = JTextField(state.goarch)
        gopath = JTextField(state.gopath)
        cgo = JCheckBox("Enable CGO", state.cgoEnabled)
        vendoring = JCheckBox("Use vendoring when available", state.useVendoring)

        downloadVersion = JComboBox(GoSdkDiscovery.downloadableVersions().toTypedArray())
        val download = JButton("Download selected Go version").apply {
            addActionListener {
                val requested = downloadVersion?.selectedItem?.toString().orEmpty()
                isEnabled = false
                object : Task.Backgroundable(project, "Downloading Go $requested", true) {
                    private var root: java.nio.file.Path? = null
                    private var failure: String? = null

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        root = runCatching {
                            GoSdkDiscovery.download(requested) { stage -> indicator.text = stage }
                        }.getOrElse {
                            failure = it.message ?: "The download failed."
                            null
                        }
                    }

                    override fun onFinished() {
                        isEnabled = true
                        if (root == null) {
                            javax.swing.JOptionPane.showMessageDialog(
                                panel,
                                failure ?: "Could not download $requested",
                                "Go SDK",
                                javax.swing.JOptionPane.ERROR_MESSAGE,
                            )
                        } else {
                            sdkPath?.text = root.toString()
                            version?.addItem(requested)
                            version?.selectedItem = requested
                        }
                    }
                }.queue()
            }
        }
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Go version:"), version!!, 1, false)
            .addLabeledComponent(JBLabel("Download version:"), downloadVersion!!, 1, false)
            .addComponent(download)
            .addLabeledComponent(JBLabel("Go SDK (GOROOT):"), sdkPath!!, 1, false)
            .addLabeledComponent(JBLabel("Build tags:"), tags!!, 1, false)
            .addLabeledComponent(JBLabel("GOOS:"), goos!!, 1, false)
            .addLabeledComponent(JBLabel("GOARCH:"), goarch!!, 1, false)
            .addLabeledComponent(JBLabel("GOPATH:"), gopath!!, 1, false)
            .addComponent(cgo!!)
            .addComponent(vendoring!!)
            .addComponentFillVertically(JPanel(BorderLayout()), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = GoProjectModelState.getInstance(project)
        return sdkPath?.text != state.sdkPath || tags?.text != state.buildTags || goos?.text != state.goos ||
            goarch?.text != state.goarch || gopath?.text != state.gopath || cgo?.isSelected != state.cgoEnabled ||
            vendoring?.isSelected != state.useVendoring
    }

    override fun apply() {
        GoProjectModelState.getInstance(project).apply {
            sdkPath = this@GoProjectConfigurable.sdkPath?.text?.trim().orEmpty()
            buildTags = this@GoProjectConfigurable.tags?.text?.trim().orEmpty()
            goos = this@GoProjectConfigurable.goos?.text?.trim().orEmpty()
            goarch = this@GoProjectConfigurable.goarch?.text?.trim().orEmpty()
            gopath = this@GoProjectConfigurable.gopath?.text?.trim().orEmpty()
            cgoEnabled = this@GoProjectConfigurable.cgo?.isSelected == true
            useVendoring = this@GoProjectConfigurable.vendoring?.isSelected == true
        }
        GoProjectModelService.getInstance(project).refresh()
    }

    override fun reset() {
        val state = GoProjectModelState.getInstance(project)
        sdkPath?.text = state.sdkPath
        tags?.text = state.buildTags
        goos?.text = state.goos
        goarch?.text = state.goarch
        gopath?.text = state.gopath
        cgo?.isSelected = state.cgoEnabled
        vendoring?.isSelected = state.useVendoring
    }

    override fun disposeUIResources() {
        panel = null
        sdkPath = null
        version = null
        downloadVersion = null
        tags = null
        goos = null
        goarch = null
        gopath = null
        cgo = null
        vendoring = null
    }
}
