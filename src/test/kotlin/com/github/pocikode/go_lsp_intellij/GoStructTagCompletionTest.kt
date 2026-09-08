package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GoStructTagCompletionTest {
    @Test
    fun `offers the GoLand tag keys in order`() {
        assertEquals(listOf("asn1", "bson", "json", "xml", "yaml"), GoStructTagCompletion.keys)
    }

    @Test
    fun `recognizes the caret inside or after an empty field tag`() {
        val source = "type User struct {\n\tHTTPServer string ``\n}"
        val body = source.indexOf("``") + 1
        val expected = GoStructTagCompletion.Context("HTTPServer", body)

        assertEquals(expected, GoStructTagCompletion.contextAt(source, body))
        assertEquals(expected, GoStructTagCompletion.contextAt(source, body + 1))
        assertEquals("json:\"http_server\"", GoStructTagEdits.pair("json", expected.fieldName))
    }

    @Test
    fun `does not complete nonempty tags or unrelated raw strings`() {
        val tagged = "type User struct {\n\tName string `json:\"name\"`\n}"
        val rawString = "func f() { query := `` }"

        assertNull(GoStructTagCompletion.contextAt(tagged, tagged.indexOf("json")))
        assertNull(GoStructTagCompletion.contextAt(rawString, rawString.indexOf("``") + 1))
    }

    @Test
    fun `uses the innermost struct field`() {
        val source = """
            type Outer struct {
            	Name string
            	Meta struct {
            		XMLName string ``
            	}
            }
        """.trimIndent()
        val body = source.indexOf("``") + 1

        assertEquals(
            GoStructTagCompletion.Context("XMLName", body),
            GoStructTagCompletion.contextAt(source, body + 1),
        )
    }
}
