package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.util.execution.ParametersListUtil
import java.nio.charset.StandardCharsets

/** Starts the `go run` process for a [GoRunConfiguration]. */
class GoRunRunningState(
    environment: ExecutionEnvironment,
    private val configuration: GoRunConfiguration,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val go = GoToolchain.executable(configuration.project)
            ?: throw ExecutionException("The go executable was not found")
        val commandLine = GeneralCommandLine(go, "run")
        commandLine.addParameters(ParametersListUtil.parse(configuration.goToolArguments))
        commandLine.addParameter(configuration.target)
        commandLine.addParameters(ParametersListUtil.parse(configuration.programArguments))
        commandLine.withWorkDirectory(configuration.directoryPath)
        commandLine.withCharset(StandardCharsets.UTF_8)
        configuration.envData.configureCommandLine(commandLine, true)
        GoToolchain.configure(commandLine, configuration.project)

        return KillableColoredProcessHandler(commandLine).also(ProcessTerminatedListener::attach)
    }
}
