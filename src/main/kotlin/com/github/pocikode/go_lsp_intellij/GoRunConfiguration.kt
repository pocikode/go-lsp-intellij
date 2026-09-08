package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import icons.GoLspIcons
import java.io.File
import org.jdom.Element

/** An editable `go run` invocation, used by the gutter action on `func main()`. */
class GoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var directoryPath: String = project.basePath.orEmpty()
    var target: String = CURRENT_PACKAGE
    var goToolArguments: String = ""
    var programArguments: String = ""
    var envData: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoRunSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        GoRunRunningState(environment, this)

    override fun checkConfiguration() {
        if (directoryPath.isBlank()) throw RuntimeConfigurationError("Specify the directory to run go in")
        if (!File(directoryPath).isDirectory) throw RuntimeConfigurationError("$directoryPath is not a directory")
        if (target.isBlank()) throw RuntimeConfigurationError("Specify a package or file to run")
        GoLspDiscovery.findGoTool("go")
            ?: throw RuntimeConfigurationError("The go executable was not found; add it to PATH or set GOROOT")
    }

    override fun suggestedName(): String = target

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, DIRECTORY, directoryPath)
        JDOMExternalizerUtil.writeField(element, TARGET, target)
        JDOMExternalizerUtil.writeField(element, GO_ARGUMENTS, goToolArguments)
        JDOMExternalizerUtil.writeField(element, PROGRAM_ARGUMENTS, programArguments)
        envData.writeExternal(element)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        directoryPath = JDOMExternalizerUtil.readField(element, DIRECTORY, directoryPath)
        target = JDOMExternalizerUtil.readField(element, TARGET, target)
        goToolArguments = JDOMExternalizerUtil.readField(element, GO_ARGUMENTS, goToolArguments)
        programArguments = JDOMExternalizerUtil.readField(element, PROGRAM_ARGUMENTS, programArguments)
        envData = EnvironmentVariablesData.readExternal(element)
    }

    companion object {
        const val CURRENT_PACKAGE: String = "."

        private const val DIRECTORY = "goRunDirectory"
        private const val TARGET = "goRunTarget"
        private const val GO_ARGUMENTS = "goRunArguments"
        private const val PROGRAM_ARGUMENTS = "goRunProgramArguments"
    }
}

/** Registers "Go Run" in the Run/Debug Configurations dialog. */
class GoRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Run",
    "Run a Go main package",
    GoLspIcons.GO,
) {
    init {
        addFactory(GoRunConfigurationFactory(this))
    }

    companion object {
        const val ID: String = "GoLspRunConfiguration"

        fun getInstance(): GoRunConfigurationType =
            ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(GoRunConfigurationType::class.java)
    }
}

class GoRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Go Run"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoRunConfiguration(project, this, "Go Run")
}
