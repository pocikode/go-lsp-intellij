package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GoTodoCommentsTest {

    private fun comments(source: String): List<String> =
        GoTodoComments.find(source).map { source.substring(it.range.startOffset, it.range.endOffset) }

    @Test
    fun `finds line and block comments`() {
        val source = """
            package main

            // TODO first
            var x = 1 /* FIXME second */
            // NOTE third
        """.trimIndent()

        assertEquals(
            listOf("// TODO first", "/* FIXME second */", "// NOTE third"),
            comments(source),
        )
    }

    @Test
    fun `ignores comment markers in every Go literal form`() {
        val source = """
            var interpreted = "// TODO not a comment"
            var escaped = "quote: \" /* FIXME neither */"
            var raw = `// TODO still not a comment`
            var rune = '/'
            // TODO real comment
        """.trimIndent()

        assertEquals(listOf("// TODO real comment"), comments(source))
    }

    @Test
    fun `block comments can span lines and do not nest`() {
        val source = "/* TODO first\n// FIXME same comment */\n/* outer /* inner */ code */"

        assertEquals(
            listOf("/* TODO first\n// FIXME same comment */", "/* outer /* inner */"),
            comments(source),
        )
    }

    @Test
    fun `unterminated comments and raw strings stop at end of file`() {
        assertEquals(listOf("/* TODO unfinished"), comments("x := 1\n/* TODO unfinished"))
        assertEquals(emptyList<String>(), comments("x := `// TODO unfinished"))
    }

    @Test
    fun `malformed quoted literal recovers on the next line`() {
        val source = "x := \"unfinished\n// TODO visible"

        assertEquals(listOf("// TODO visible"), comments(source))
    }
}
