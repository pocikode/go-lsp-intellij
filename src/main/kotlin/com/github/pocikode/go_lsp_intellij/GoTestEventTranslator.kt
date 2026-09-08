package com.github.pocikode.go_lsp_intellij

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/**
 * Turns the `go test -json` event stream into the service messages the IntelliJ test runner builds
 * its tree from.
 *
 * `go test -json` is why a GoLand-like test tree is possible without a Go parser: `test2json`
 * already reports every test's start, output, outcome and duration as one JSON object per line, so
 * nothing here has to understand Go. Anything that is not such an object - a compiler error or the
 * runner's own "process finished" line - is passed through to the console untouched. Panic output
 * normally arrives inside test events and is attached to the failed test with the rest of its logs.
 *
 * Two shapes of the protocol are used deliberately:
 *
 * - Nodes carry `nodeId`/`parentNodeId` rather than relying on the order messages arrive in. Go
 *   interleaves the events of parallel tests, so a stack-shaped protocol would nest them wrongly.
 * - A test's start is held back until its outcome is known. Whether `TestA` is a test or a suite of
 *   subtests is only decided by whether `TestA/case` ever runs, and a node is created as one or the
 *   other by the message that starts it. The cost is that a test appears in the tree when it
 *   finishes rather than when it starts; package progress and package-level output stay live.
 */
class GoTestEventTranslator(private val emit: (String) -> Unit) {

    private class Node(val id: Int, val parentId: Int, val name: String, val locationHint: String)

    private class Pending(val packageName: String, val node: Node) {
        val output = StringBuilder()

        /** A panic can terminate the stream before `test2json` emits the test's `fail` event. */
        var panicked: Boolean = false

        /** True once the node has been announced as a suite, which happens when a subtest runs. */
        var suite: Boolean = false
    }

    private enum class Outcome { PASSED, FAILED, SKIPPED }

    private var nextId = 1

    /** Set once the toolchain has shown that it marks its own output, which makes [PROGRESS] moot. */
    private var labelsFrames = false
    private val packages = LinkedHashMap<String, Node>()
    private val tests = LinkedHashMap<String, Pending>()

    /** Consumes one line of `go test -json` output, newline included. */
    fun line(text: String) {
        val event = parse(text) ?: run { emit(text); return }
        val action = event.string("Action") ?: run { emit(text); return }
        val output = event.string("Output")
        val packageName = event.string("Package") ?: run {
            // A build failure names the package as ImportPath and has no test to attach to.
            output?.let(emit)
            return
        }
        val test = event.string("Test")
        if (test == null) {
            packageEvent(packageName, action, output, event)
        } else {
            testEvent(packageName, test, action, output, event)
        }
    }

    /**
     * Closes whatever is still open, so a run that was stopped or died leaves no node spinning.
     *
     * A test that never reported an outcome is ignored when interrupted, unless its output contains
     * a panic; a panic is always a failure even if the process dies before emitting an outcome.
     */
    fun finish() {
        for (key in tests.keys.toList().asReversed()) {
            val pending = tests.remove(key) ?: continue
            finish(pending, if (pending.panicked) Outcome.FAILED else Outcome.SKIPPED, null, INTERRUPTED)
        }
        for (name in packages.keys.toList()) closePackage(name)
    }

    private fun packageEvent(packageName: String, action: String, output: String?, event: JsonObject) {
        when (action) {
            "start" -> packageNode(packageName)
            "pass", "skip" -> {
                output?.let(emit)
                closePackage(packageName, event.duration())
            }
            "fail" -> {
                output?.let(emit)
                closePackageTests(packageName)
                closePackage(packageName, event.duration())
            }
            // Everything a package says outside a test - "no test files", a build error, the final
            // "ok" line - belongs in the console as it arrives.
            else -> output?.let(emit)
        }
    }

    private fun testEvent(packageName: String, test: String, action: String, output: String?, event: JsonObject) {
        when (action) {
            "run" -> pending(packageName, test)
            "output", "bench" -> output?.let { testOutput(packageName, test, it, event.string("OutputType")) }
            "pass" -> close(packageName, test, Outcome.PASSED, event.duration())
            "fail" -> close(packageName, test, Outcome.FAILED, event.duration())
            "skip" -> close(packageName, test, Outcome.SKIPPED, event.duration())
            // "pause" and "cont" normally carry no output, but preserve it if a toolchain adds any.
            else -> output?.let { testOutput(packageName, test, it, event.string("OutputType")) }
        }
    }

    private fun testOutput(packageName: String, test: String, output: String, outputType: String?) {
        val pending = pending(packageName, test)
        val text = strip(output, outputType) ?: return
        if (PANIC.containsMatchIn(text)) pending.panicked = true
        if (pending.suite) {
            emit(
                ServiceMessageBuilder.testStdOut(pending.node.name).node(pending.node.id)
                    .addAttribute("out", text).line(),
            )
        } else {
            pending.output.append(text)
        }
    }

    private fun close(packageName: String, test: String, outcome: Outcome, duration: Long?) {
        val pending = tests.remove(key(packageName, test)) ?: return
        finish(pending, outcome, duration)
    }

    private fun finish(pending: Pending, outcome: Outcome, duration: Long?, ignoredMessage: String = "") {
        if (pending.suite) {
            if (outcome == Outcome.FAILED) fail(pending)
            emit(ServiceMessageBuilder.testSuiteFinished(pending.node.name).node(pending.node.id).line())
            return
        }
        start(pending)
        when (outcome) {
            Outcome.FAILED -> fail(pending)
            Outcome.PASSED -> flush(pending)
            Outcome.SKIPPED -> {
                flush(pending)
                emit(
                    ServiceMessageBuilder.testIgnored(pending.node.name).node(pending.node.id)
                        .addAttribute("message", ignoredMessage).line(),
                )
            }
        }
        val finished = ServiceMessageBuilder.testFinished(pending.node.name).node(pending.node.id)
        if (duration != null) finished.addAttribute("duration", duration.toString())
        emit(finished.line())
    }

    /** Prints every captured line, then marks the node failed without duplicating it as details. */
    private fun fail(pending: Pending) {
        val message = message(pending.output)
        flush(pending)
        emit(
            ServiceMessageBuilder.testFailed(pending.node.name).node(pending.node.id)
                .addAttribute("message", message)
                .line(),
        )
    }

    private fun closePackageTests(packageName: String) {
        for ((key, pending) in tests.entries.toList().asReversed()) {
            if (pending.packageName != packageName) continue
            tests.remove(key)
            finish(pending, Outcome.FAILED, null)
        }
    }

    private fun start(pending: Pending) {
        emit(
            ServiceMessageBuilder.testStarted(pending.node.name)
                .node(pending.node.id)
                .addAttribute("parentNodeId", pending.node.parentId.toString())
                .addAttribute("locationHint", pending.node.locationHint)
                .line(),
        )
    }

    private fun flush(pending: Pending) {
        if (pending.output.isEmpty()) return
        emit(
            ServiceMessageBuilder.testStdOut(pending.node.name).node(pending.node.id)
                .addAttribute("out", pending.output.toString()).line(),
        )
        pending.output.setLength(0)
    }

    /**
     * The node for [test], creating it - and every parent it needs - on first sight.
     *
     * Creating a parent is what turns it into a suite, which is why nothing is emitted for a test
     * until it finishes: by then every subtest that will ever run has already claimed it.
     */
    private fun pending(packageName: String, test: String): Pending {
        tests[key(packageName, test)]?.let { return it }
        val separator = test.lastIndexOf('/')
        val parentId = if (separator < 0) {
            packageNode(packageName).id
        } else {
            promote(packageName, test.substring(0, separator)).node.id
        }
        val node = Node(nextId++, parentId, test.substring(separator + 1), locationHint(packageName, test))
        return Pending(packageName, node).also { tests[key(packageName, test)] = it }
    }

    private fun promote(packageName: String, test: String): Pending {
        val pending = pending(packageName, test)
        if (pending.suite) return pending
        pending.suite = true
        emit(
            ServiceMessageBuilder.testSuiteStarted(pending.node.name)
                .node(pending.node.id)
                .addAttribute("parentNodeId", pending.node.parentId.toString())
                .addAttribute("locationHint", pending.node.locationHint)
                .line(),
        )
        flush(pending)
        return pending
    }

    private fun packageNode(packageName: String): Node = packages.getOrPut(packageName) {
        val node = Node(nextId++, ROOT_NODE_ID, packageName, packageLocationHint(packageName))
        emit(
            ServiceMessageBuilder.testSuiteStarted(node.name)
                .node(node.id)
                .addAttribute("parentNodeId", node.parentId.toString())
                .addAttribute("locationHint", node.locationHint)
                .line(),
        )
        node
    }

    private fun closePackage(packageName: String, duration: Long? = null) {
        val node = packages.remove(packageName) ?: return
        val finished = ServiceMessageBuilder.testSuiteFinished(node.name).node(node.id)
        if (duration != null) finished.addAttribute("duration", duration.toString())
        emit(finished.line())
    }

    private fun parse(text: String): JsonObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (exception: RuntimeException) {
            null
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.duration(): Long? =
        get("Elapsed")?.takeIf { it.isJsonPrimitive }?.let { (it.asDouble * 1000).toLong() }

    private fun ServiceMessageBuilder.node(id: Int): ServiceMessageBuilder = addAttribute("nodeId", id.toString())

    private fun ServiceMessageBuilder.line(): String = toString() + "\n"

    /** A panic is the useful failure title even when ordinary logs preceded it. */
    private fun message(output: CharSequence): String =
        output.lineSequence().firstOrNull { PANIC.containsMatchIn(it) }?.trim()
            ?: output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: FAILED

    /**
     * Drops `go test`'s own progress lines from a test's output. They repeat what the tree already
     * shows, and GoLand does not put them in a test's output pane either.
     *
     * Recent Go versions label exactly those lines `"OutputType":"frame"`, which is both cheaper
     * and more honest than matching them: a test that prints a line beginning `--- FAIL:` itself
     * keeps it. The pattern is the fallback for a toolchain that does not report the field, and is
     * dropped for the rest of the run as soon as one event shows that it does - an unlabelled line
     * in a labelling stream is the test's own, not the toolchain's.
     */
    private fun strip(output: String, outputType: String?): String? {
        if (outputType != null) labelsFrames = true
        return when {
            outputType == FRAME -> null
            labelsFrames -> output
            PROGRESS.containsMatchIn(output) -> null
            else -> output
        }
    }

    private fun key(packageName: String, test: String): String = "$packageName $test"

    companion object {
        /** The scheme [GoTestLocator] answers for, linking a node in the tree back to its source. */
        const val PROTOCOL: String = "go_test"

        /** Separates the package's import path from the test name inside a location hint. */
        const val SEPARATOR: String = "::"

        /** The stable key used by tree navigation and IntelliJ's persisted test-state storage. */
        fun locationHint(packageName: String, test: String): String = "$PROTOCOL://$packageName$SEPARATOR$test"

        fun packageLocationHint(packageName: String): String = "$PROTOCOL://$packageName"

        private const val ROOT_NODE_ID = 0
        private const val INTERRUPTED = "Test did not finish"
        private const val FRAME = "frame"
        private const val FAILED = "Test failed"

        private val PANIC = Regex("""^\s*panic:""")
        private val PROGRESS = Regex("""^\s*(===\s+(RUN|PAUSE|CONT|NAME)\b|---\s+(PASS|FAIL|SKIP|BENCH):)""")
    }
}
