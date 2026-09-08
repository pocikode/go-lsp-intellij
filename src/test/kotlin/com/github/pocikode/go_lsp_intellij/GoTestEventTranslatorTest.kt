package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoTestEventTranslatorTest {

    private val emitted = mutableListOf<String>()
    private val translator = GoTestEventTranslator { emitted += it }

    private fun feed(vararg lines: String) = lines.forEach { translator.line(it + "\n") }

    /** The service messages produced, without the surrounding `##teamcity[...]` noise. */
    private fun messages(): List<String> = emitted.filter { it.startsWith("##teamcity") }.map { it.trim() }

    private fun names(name: String): List<String> = messages().filter { it.startsWith("##teamcity[$name") }

    @Test
    fun `reports a package as a suite and a test as a test`() {
        feed(
            """{"Action":"start","Package":"m/pkg"}""",
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne","Elapsed":0.25}""",
            """{"Action":"pass","Package":"m/pkg","Elapsed":0.3}""",
        )
        assertEquals(1, names("testSuiteStarted").size)
        assertTrue(names("testSuiteStarted").single().contains("name='m/pkg'"))
        assertTrue(names("testStarted").single().contains("name='TestOne'"))
        assertTrue(names("testFinished").single().contains("duration='250'"))
        assertEquals(1, names("testSuiteFinished").size)
    }

    @Test
    fun `turns a test with subtests into a suite of its own`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"run","Package":"m/pkg","Test":"TestOne/first"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne/first"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne"}""",
        )
        // The package and TestOne are suites; only the subtest is a test, named by its last part.
        assertEquals(2, names("testSuiteStarted").size)
        assertTrue(names("testSuiteStarted")[1].contains("name='TestOne'"))
        assertTrue(names("testStarted").single().contains("name='first'"))
    }

    @Test
    fun `nests a subtest under its parent by node id, not by ordering`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"run","Package":"m/pkg","Test":"TestOne/first"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne/first"}""",
        )
        val parentId = nodeId(names("testSuiteStarted")[1])
        assertEquals(parentId, attribute(names("testStarted").single(), "parentNodeId"))
    }

    @Test
    fun `reports a failure with the test's own output as the details`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"=== RUN   TestOne\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"    one_test.go:9: got 1, want 2\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"--- FAIL: TestOne (0.00s)\n"}""",
            """{"Action":"fail","Package":"m/pkg","Test":"TestOne","Elapsed":0.01}""",
        )
        val failed = names("testFailed").single()
        assertTrue(failed.contains("one_test.go:9: got 1, want 2"), failed)
        // go test's own progress lines are the tree's job, not the output pane's.
        assertTrue(!failed.contains("=== RUN"), failed)
        assertTrue(!failed.contains("--- FAIL"), failed)
        // Every log line remains visible as ordinary test output, including on failure.
        assertTrue(names("testStdOut").single().contains("one_test.go:9: got 1, want 2"))
        assertTrue(!attribute(failed, "details").contains("one_test.go"), failed)
    }

    @Test
    fun `reports a skipped test as ignored`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"skip","Package":"m/pkg","Test":"TestOne"}""",
        )
        assertEquals(1, names("testIgnored").size)
    }

    @Test
    fun `passes anything that is not an event through to the console`() {
        val error = "# m/pkg\n./one.go:4:2: undefined: missing\n"
        translator.line(error)
        assertEquals(listOf(error), emitted)
    }

    @Test
    fun `passes package output through as it arrives`() {
        feed("""{"Action":"output","Package":"m/pkg","Output":"ok  \tm/pkg\t0.3s\n"}""")
        assertEquals(listOf("ok  \tm/pkg\t0.3s\n"), emitted)
    }

    @Test
    fun `keeps logs from a successful test`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"application log\n"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne"}""",
        )
        assertTrue(names("testStdOut").single().contains("application log"))
    }

    @Test
    fun `keeps benchmark result output`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"BenchmarkOne"}""",
            """{"Action":"bench","Package":"m/pkg","Test":"BenchmarkOne","Output":"BenchmarkOne-8  100  12 ns/op\n"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"BenchmarkOne"}""",
        )
        assertTrue(names("testStdOut").single().contains("12 ns/op"))
    }

    @Test
    fun `prints fmt and log output and marks a panic as failed`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestPanic"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestPanic","Output":"fmt panic\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestPanic","Output":"2026/09/08 13:45:17 log panic\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestPanic","Output":"panic: boom [recovered, repanicked]\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestPanic","Output":"example.com/pkg.TestPanic()\n"}""",
            """{"Action":"fail","Package":"m/pkg","Test":"TestPanic"}""",
            """{"Action":"fail","Package":"m/pkg"}""",
        )
        val output = names("testStdOut").single()
        assertTrue(output.contains("fmt panic"), output)
        assertTrue(output.contains("log panic"), output)
        assertTrue(output.contains("panic: boom"), output)
        assertTrue(output.contains("TestPanic"), output)
        assertTrue(names("testFailed").single().contains("message='panic: boom"))
    }

    @Test
    fun `a panic followed by package failure cannot leave the test ignored`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestPanic"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestPanic","Output":"panic: boom\n"}""",
            """{"Action":"fail","Package":"m/pkg"}""",
        )
        assertEquals(1, names("testFailed").size)
        assertEquals(0, names("testIgnored").size)
    }

    @Test
    fun `closes what an interrupted run left open`() {
        feed(
            """{"Action":"start","Package":"m/pkg"}""",
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
        )
        translator.finish()
        // An unfinished test is ignored rather than passed: the run was stopped, not successful.
        assertEquals(1, names("testIgnored").size)
        assertEquals(1, names("testSuiteFinished").size)
    }

    @Test
    fun `carries the package and test name in the location hint`() {
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne/first"}""",
            """{"Action":"pass","Package":"m/pkg","Test":"TestOne/first"}""",
        )
        assertEquals(
            "go_test://m/pkg::TestOne/first",
            attribute(names("testStarted").single(), "locationHint"),
        )
    }

    @Test
    fun `keeps a test's own output when the toolchain labels its framing lines`() {
        // What go 1.27 emits: its own lines are OutputType "frame", the test's are not.
        feed(
            """{"Action":"run","Package":"m/pkg","Test":"TestOne"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"=== RUN   TestOne\n","OutputType":"frame"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"--- FAIL: printed by the test\n"}""",
            """{"Action":"output","Package":"m/pkg","Test":"TestOne","Output":"--- FAIL: TestOne (0.00s)\n","OutputType":"frame"}""",
            """{"Action":"fail","Package":"m/pkg","Test":"TestOne","Elapsed":0}""",
        )
        val failed = names("testFailed").single()
        assertTrue(failed.contains("printed by the test"), failed)
        assertTrue(!failed.contains("=== RUN"), failed)
        assertTrue(!failed.contains("TestOne (0.00s)"), failed)
        assertTrue(names("testStdOut").single().contains("printed by the test"))
    }

    private fun attribute(message: String, name: String): String =
        Regex("$name='([^']*)'").find(message)?.groupValues?.get(1).orEmpty()

    private fun nodeId(message: String): String = attribute(message, "nodeId")
}
