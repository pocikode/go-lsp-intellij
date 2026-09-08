package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.util.execution.ParametersListUtil
import java.nio.charset.StandardCharsets

/**
 * Runs one `go test` process and hands its output to the platform's test runner.
 *
 * `-json` is what makes the tree possible: it asks the toolchain for the machine-readable event
 * stream [GoTestEventTranslator] reads. `-v` is also passed explicitly so successful-test logs are
 * part of that stream even when custom arguments contain another verbosity setting.
 */
class GoTestRunningState(
    environment: ExecutionEnvironment,
    private val configuration: GoTestRunConfiguration,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val go = GoToolchain.executable(configuration.project)
            ?: throw ExecutionException("The go executable was not found")

        val commandLine = GeneralCommandLine(go, "test", "-json")
        if (configuration.runPattern.isNotEmpty()) commandLine.addParameters("-run", configuration.runPattern)
        // Flags have to precede the package pattern, which go test treats as the end of its own
        // arguments.
        commandLine.addParameters(ParametersListUtil.parse(configuration.goToolArguments))
        commandLine.addParameter("-v")
        commandLine.addParameter(configuration.packagePattern)
        commandLine.withWorkDirectory(configuration.directoryPath)
        commandLine.withCharset(StandardCharsets.UTF_8)
        configuration.envData.configureCommandLine(commandLine, true)
        GoToolchain.configure(commandLine, configuration.project)

        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = startProcess()
        val properties = configuration.createTestConsoleProperties(executor) as GoTestConsoleProperties
        val console = SMTestRunnerConnectionUtil
            .createAndAttachConsole(GO_TEST_FRAMEWORK_NAME, processHandler, properties) as SMTRunnerConsoleView

        val result = DefaultExecutionResult(console, processHandler)
        properties.createRerunFailedTestsAction(console)?.let { result.setRestartActions(it) }
        return result
    }
}
