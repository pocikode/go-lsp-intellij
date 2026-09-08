package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/** Creates or reuses the `go run .` configuration behind a main-function gutter action. */
internal object GoRunRunner {

    fun run(project: Project, name: String, directory: String) {
        val runManager = RunManager.getInstance(project)
        val settings = existing(runManager, directory)
        if (settings != null) runManager.selectedConfiguration = settings
        val target = settings ?: create(runManager, name, directory).also(runManager::setTemporaryConfiguration)
        ProgramRunnerUtil.executeConfiguration(target, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun existing(runManager: RunManager, directory: String): RunnerAndConfigurationSettings? {
        val type = GoRunConfigurationType.getInstance()
        return runManager.getConfigurationSettingsList(type).firstOrNull { settings ->
            val configuration = settings.configuration as? GoRunConfiguration ?: return@firstOrNull false
            configuration.directoryPath == directory && configuration.target == GoRunConfiguration.CURRENT_PACKAGE
        }
    }

    private fun create(runManager: RunManager, name: String, directory: String): RunnerAndConfigurationSettings {
        val factory = GoRunConfigurationType.getInstance().configurationFactories.first()
        val settings = runManager.createConfiguration(name, factory)
        (settings.configuration as GoRunConfiguration).apply {
            directoryPath = directory
            target = GoRunConfiguration.CURRENT_PACKAGE
        }
        settings.isTemporary = true
        return settings
    }
}
