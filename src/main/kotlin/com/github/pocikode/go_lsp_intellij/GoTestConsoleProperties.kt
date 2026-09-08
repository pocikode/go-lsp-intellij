package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Key

/** The name the test tool window, its settings and its statistics use for this runner. */
internal const val GO_TEST_FRAMEWORK_NAME: String = "Go Test"

/**
 * Wires the platform's test runner to `go test`: the converter that feeds the tree
 * ([GoTestEventTranslator]), the locator that navigates from it ([GoTestLocator]), and the
 * rerun-failed action.
 *
 * The tree is id-based because Go interleaves the events of parallel tests; see the translator for
 * why that matters.
 */
class GoTestConsoleProperties(
    private val configuration: GoTestRunConfiguration,
    executor: Executor,
) : SMTRunnerConsoleProperties(configuration, GO_TEST_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = true
    }

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter = GoTestEventsConverter(testFrameworkName, consoleProperties)

    override fun getTestLocator(): SMTestLocator = GoTestLocator(configuration.directoryPath)

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction? =
        (consoleView as? SMTRunnerConsoleView)?.let { GoTestRerunFailedAction(it, this) }
}

/**
 * Feeds `go test -json` through [GoTestEventTranslator].
 *
 * The base class has already split the process output into whole lines by the time
 * [processConsistentText] is called, which is what makes a line-oriented JSON stream safe to parse
 * here. Everything the translator produces goes back through the base class, which is what turns a
 * service message into a node in the tree.
 */
class GoTestEventsConverter(
    testFrameworkName: String,
    properties: TestConsoleProperties,
) : OutputToGeneralTestEventsConverter(testFrameworkName, properties) {

    private val translator = GoTestEventTranslator(::pass)

    override fun processConsistentText(text: String, outputType: Key<*>) {
        // Only stdout carries the event stream. The toolchain's own errors arrive on stderr and the
        // runner's "process finished" line as system output; both are console text, and passing
        // them straight through keeps them styled as what they are.
        if (outputType !== ProcessOutputTypes.STDOUT) {
            super.processConsistentText(text, outputType)
            return
        }
        translator.line(text)
    }

    override fun finishTesting() {
        translator.finish()
        super.finishTesting()
    }

    private fun pass(text: String) = super.processConsistentText(text, ProcessOutputTypes.STDOUT)
}

/**
 * Re-runs only what failed, by narrowing `-run` to the failed tests.
 *
 * A failed subtest is re-run through its top-level test, because `go test` takes one `-run` pattern
 * for the whole invocation - see [GoTestFunctions.runPattern].
 */
class GoTestRerunFailedAction(
    private val consoleView: SMTRunnerConsoleView,
    properties: GoTestConsoleProperties,
) : AbstractRerunFailedTestsAction(consoleView) {

    init {
        init(properties)
        setModelProvider { consoleView.resultsViewer }
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? GoTestRunConfiguration ?: return null
        val failed = getFailedTests(configuration.project)
            .filterIsInstance<SMTestProxy>()
            .filter { it.isLeaf }
            .mapNotNull { testName(it) }
            .toSet()
        if (failed.isEmpty()) return null

        return object : MyRunProfile(configuration) {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
                val rerun = configuration.clone() as GoTestRunConfiguration
                rerun.runPattern = GoTestFunctions.runPattern(failed)
                return GoTestRunningState(environment, rerun)
            }
        }
    }

    /** The full `go test` name of a node, recovered from the location hint the translator gave it. */
    private fun testName(proxy: SMTestProxy): String? =
        proxy.locationUrl
            ?.takeIf { it.startsWith("${GoTestEventTranslator.PROTOCOL}://") }
            ?.substringAfter(GoTestEventTranslator.SEPARATOR, "")
            ?.takeIf { it.isNotEmpty() }
}
