package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GoStructTagsTest {

    /** The `key:` slices [GoStructTags] would recolour, as text, so the offsets are readable. */
    private fun keys(source: String): List<String> =
        GoStructTags.keyRanges(source).map { source.substring(it.startOffset, it.endOffset) }

    @Test
    fun `finds the key of a single tag`() {
        assertEquals(listOf("json:"), keys("""x string `json:"message_action"`"""))
    }

    @Test
    fun `finds every key of a multi-part tag`() {
        assertEquals(
            listOf("json:", "xml:", "db:"),
            keys("""x string `json:"message_data,omitempty" xml:"md" db:"message_data"`"""),
        )
    }

    @Test
    fun `accepts the key characters Go allows`() {
        assertEquals(listOf("go.mod-x_1:"), keys("""x string `go.mod-x_1:"v"`"""))
    }

    @Test
    fun `leaves raw strings that are not tags alone`() {
        assertEquals(emptyList<String>(), keys("""q := `SELECT * FROM t WHERE a = 1`"""))
        assertEquals(emptyList<String>(), keys("""re := `^[a-z]+:"?$`"""))
        assertEquals(emptyList<String>(), keys("""empty := ``"""))
        // A pair needs its quoted value; a bare `key:` is not a tag.
        assertEquals(emptyList<String>(), keys("""x := `json:`"""))
    }

    @Test
    fun `ignores backticks inside comments and strings`() {
        assertEquals(emptyList<String>(), keys("""// a ` json:"x" ` in a line comment"""))
        assertEquals(emptyList<String>(), keys("""/* a ` json:"x" ` in a block comment */"""))
        assertEquals(emptyList<String>(), keys("""s := "a ` json:\"x\" ` in a string""""))
        assertEquals(emptyList<String>(), keys("""r := '`'"""))
    }

    @Test
    fun `an unterminated raw string does not swallow later tags`() {
        // The stray backtick opens a literal that runs to the one on the next line; nothing after it
        // parses as a tag, and the scan must still terminate.
        assertEquals(emptyList<String>(), keys("x := `unterminated\ny := 1\n"))
    }

    @Test
    fun `finds tags across a whole struct`() {
        val source = """
            package main

            // Tagged is ` not a tag `.
            type Tagged struct {
            	MessageAction string `json:"message_action"`
            	MessageData   any    `json:"message_data,omitempty" xml:"md"`
            	Untagged      int
            }
        """.trimIndent()
        assertEquals(listOf("json:", "json:", "xml:"), keys(source))
    }

    @Test
    fun `ranges cover the key and its colon but not the quote`() {
        val source = """x string `json:"v"`"""
        val range = GoStructTags.keyRanges(source).single()
        assertEquals("json:", source.substring(range.startOffset, range.endOffset))
        assertEquals('`', source[range.startOffset - 1])
        assertEquals('"', source[range.endOffset])
    }
}
