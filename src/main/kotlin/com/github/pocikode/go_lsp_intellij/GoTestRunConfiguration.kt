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
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.ConsolePropertiesProvider
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import icons.GoLspIcons
import java.io.File
import org.jdom.Element

/**
 * The "Go Test" run configuration: one `go test` invocation, described the way `go test` describes
 * it - a directory to run in, a package pattern, and an optional `-run` pattern.
 *
 * There is deliberately no notion of "test kind" here. A single test, a file's worth of tests and a
 * whole package differ only in the `-run` pattern the caller builds ([GoTestFunctions.runPattern]),
 * so the configuration stores what is actually passed to `go test` and stays editable by hand.
 */
class GoTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name), ConsolePropertiesProvider {

    /** Where `go test` runs, which is also what resolves a relative package pattern. */
    var directoryPath: String = project.basePath.orEmpty()

    /** The package pattern, as `go test` takes it: `./...`, `.`, or an import path. */
    var packagePattern: String = ALL_PACKAGES

    /** The value of `-run`; empty runs every test in the matched packages. */
    var runPattern: String = ""

    /** Extra flags for `go test`, for example `-race -count=1`. */
    var goToolArguments: String = ""

    var envData: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoTestSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        GoTestRunningState(environment, this)

    /**
     * Lets the platform reach the test console's settings without a running process - which is what
     * rerun-failed needs to build its filter, among other things.
     */
    override fun createTestConsoleProperties(executor: Executor): TestConsoleProperties =
        GoTestConsoleProperties(this, executor)

    override fun checkConfiguration() {
        if (directoryPath.isBlank()) throw RuntimeConfigurationError("Specify the directory to run go test in")
        if (!File(directoryPath).isDirectory) throw RuntimeConfigurationError("$directoryPath is not a directory")
        if (packagePattern.isBlank()) throw RuntimeConfigurationError("Specify a package pattern, for example ./...")
        GoLspDiscovery.findGoTool("go")
            ?: throw RuntimeConfigurationError("The go executable was not found; add it to PATH or set GOROOT")
    }

    /** What the run widget calls this configuration when the name was not typed by the user. */
    override fun suggestedName(): String = when {
        runPattern.isNotEmpty() -> runPattern.replace(ANCHORS, "").replace('/', '.')
        else -> packagePattern
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, DIRECTORY, directoryPath)
        JDOMExternalizerUtil.writeField(element, PACKAGE, packagePattern)
        JDOMExternalizerUtil.writeField(element, RUN, runPattern)
        JDOMExternalizerUtil.writeField(element, ARGUMENTS, goToolArguments)
        envData.writeExternal(element)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        directoryPath = JDOMExternalizerUtil.readField(element, DIRECTORY, directoryPath)
        packagePattern = JDOMExternalizerUtil.readField(element, PACKAGE, packagePattern)
        runPattern = JDOMExternalizerUtil.readField(element, RUN, runPattern)
        goToolArguments = JDOMExternalizerUtil.readField(element, ARGUMENTS, goToolArguments)
        envData = EnvironmentVariablesData.readExternal(element)
    }

    companion object {
        const val ALL_PACKAGES: String = "./..."

        // Prefixed because these share the <option> namespace with whatever RunConfigurationBase
        // writes for its own settings.
        private const val DIRECTORY = "goDirectory"
        private const val PACKAGE = "goPackage"
        private const val RUN = "goRunPattern"
        private const val ARGUMENTS = "goArguments"

        private val ANCHORS = Regex("""[\^$]""")
    }
}

/** Registers "Go Test" in the Run/Debug Configurations dialog. */
class GoTestRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Test",
    "Run Go tests with go test",
    GoLspIcons.GO,
) {
    init {
        addFactory(GoTestConfigurationFactory(this))
    }

    companion object {
        const val ID: String = "GoLspTestRunConfiguration"

        fun getInstance(): GoTestRunConfigurationType =
            ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(GoTestRunConfigurationType::class.java)
    }
}

class GoTestConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Go Test"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoTestRunConfiguration(project, this, "Go Test")
}
