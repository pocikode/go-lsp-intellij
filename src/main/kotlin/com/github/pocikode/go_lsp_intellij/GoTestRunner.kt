package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/**
 * Builds and starts a [GoTestRunConfiguration] for something the user pointed at - a test, a file,
 * a package - without going through the Run/Debug Configurations dialog.
 *
 * The platform's usual route from a click to a run is a `RunConfigurationProducer`, which is handed
 * a PSI location to recognise. There is nothing to recognise here: the bundled TextMate grammar
 * parses a `.go` file into a single PSI leaf, so a producer would only ever see "the whole file".
 * Callers work out the `go test` arguments themselves ([GoTestFunctions]) and come here with them.
 *
 * An equivalent configuration is reused when one already exists, so running the same test twice
 * does not leave a trail of near-identical entries in the run widget.
 */
internal object GoTestRunner {

    fun run(project: Project, name: String, directory: String, packagePattern: String, runPattern: String) {
        val runManager = RunManager.getInstance(project)
        val settings = existing(runManager, directory, packagePattern, runPattern)
        if (settings != null) {
            // A configuration the user saved must stay saved: setTemporaryConfiguration would
            // demote it to one of the handful the platform keeps and then discards.
            runManager.selectedConfiguration = settings
        }
        val target = settings ?: create(runManager, name, directory, packagePattern, runPattern)
            .also(runManager::setTemporaryConfiguration)
        ProgramRunnerUtil.executeConfiguration(target, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun existing(
        runManager: RunManager,
        directory: String,
        packagePattern: String,
        runPattern: String,
    ): RunnerAndConfigurationSettings? {
        val type = GoTestRunConfigurationType.getInstance()
        return runManager.getConfigurationSettingsList(type).firstOrNull { settings ->
            val configuration = settings.configuration as? GoTestRunConfiguration ?: return@firstOrNull false
            configuration.directoryPath == directory &&
                configuration.packagePattern == packagePattern &&
                configuration.runPattern == runPattern
        }
    }

    private fun create(
        runManager: RunManager,
        name: String,
        directory: String,
        packagePattern: String,
        runPattern: String,
    ): RunnerAndConfigurationSettings {
        val factory = GoTestRunConfigurationType.getInstance().configurationFactories.first()
        val settings = runManager.createConfiguration(name, factory)
        (settings.configuration as GoTestRunConfiguration).apply {
            directoryPath = directory
            this.packagePattern = packagePattern
            this.runPattern = runPattern
        }
        settings.isTemporary = true
        return settings
    }
}
