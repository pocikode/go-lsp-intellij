package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoStructTagEditsTest {
    @Test
    fun `adds snake case json tags to every field`() {
        val source = """
            package main

            type User struct {
            	FirstName  string
            	HTTPServer *Server
            	City       string `xml:"city"`
            }
        """.trimIndent()

        assertEquals(
            """
                package main

                type User struct {
                	FirstName  string `json:"first_name"`
                	HTTPServer *Server `json:"http_server"`
                	City       string `xml:"city" json:"city"`
                }
            """.trimIndent(),
            apply(source, source.indexOf("FirstName"), "json"),
        )
    }

    @Test
    fun `keeps matching tags and does not copy options between keys`() {
        val source = """
            type User struct {
            	FirstName string `json:"first_name,omitempty"`
            	City      string `xml:"city"`
            	Hidden    string `db:"hidden" json:"hidden"`
            }
        """.trimIndent()

        assertEquals(
            """
                type User struct {
                	FirstName string `json:"first_name,omitempty" xml:"first_name"`
                	City      string `xml:"city"`
                	Hidden    string `db:"hidden" json:"hidden" xml:"hidden"`
                }
            """.trimIndent(),
            apply(source, source.indexOf("City"), "xml"),
        )
    }

    @Test
    fun `adds a pair without padding an empty tag`() {
        val source = "type User struct {\n\tName string ``\n}"

        assertEquals(
            "type User struct {\n\tName string `json:\"name\"`\n}",
            apply(source, source.indexOf("``") + 1, "json"),
        )
    }

    @Test
    fun `limits edits to the enclosing struct`() {
        val source = """
            type First struct {
            	Value string
            }

            type Second struct {
            	Other string
            }
        """.trimIndent()

        assertEquals(
            """
                type First struct {
                	Value string
                }

                type Second struct {
                	Other string `json:"other"`
                }
            """.trimIndent(),
            apply(source, source.indexOf("Other"), "json"),
        )
    }

    @Test
    fun `supports embedded and generic fields but skips blank and grouped declarations`() {
        val source = """
            type Holder[T any] struct {
            	*Client
            	pkg.Item[T]
            	A, B string
            	_ string
            }
        """.trimIndent()

        assertEquals(
            """
                type Holder[T any] struct {
                	*Client `json:"client"`
                	pkg.Item[T] `json:"item"`
                	A, B string
                	_ string
                }
            """.trimIndent(),
            apply(source, source.indexOf("*Client"), "json"),
        )
    }

    @Test
    fun `does not offer edits outside a struct or for invalid keys`() {
        val source = "package main\nvar x = 1\n"
        assertTrue(GoStructTagEdits.addKey(source, source.indexOf("x"), "json").isEmpty())
        assertTrue(GoStructTagEdits.addKey("type T struct {\n X int\n}", 5, "bad key").isEmpty())
        assertTrue(GoStructTagEdits.isValidKey("go.mod-x_1"))
        assertFalse(GoStructTagEdits.isValidKey(""))
    }

    @Test
    fun `ignores struct-looking text in comments and strings`() {
        val source = """
            // type Fake struct { Value string }
            var sample = "type Other struct { Field int }"

            type Real struct {
            	Name string // type Nested struct { Wrong bool }
            }
        """.trimIndent()

        assertEquals(1, GoStructFields.all(source).size)
        assertEquals(
            """
                // type Fake struct { Value string }
                var sample = "type Other struct { Field int }"

                type Real struct {
                	Name string `json:"name"` // type Nested struct { Wrong bool }
                }
            """.trimIndent(),
            apply(source, source.indexOf("Name string"), "json"),
        )
    }

    @Test
    fun `supports anonymous and nested struct types`() {
        val source = """
            var value = struct {
            	Name string
            	Meta struct {
            		ID string
            	}
            }{}
        """.trimIndent()

        assertEquals(
            """
                var value = struct {
                	Name string
                	Meta struct {
                		ID string `json:"id"`
                	}
                }{}
            """.trimIndent(),
            apply(source, source.indexOf("ID"), "json"),
        )

        assertEquals(
            """
                var value = struct {
                	Name string `json:"name"`
                	Meta struct {
                		ID string
                	}
                }{}
            """.trimIndent(),
            apply(source, source.indexOf("Name"), "json"),
        )
    }

    private fun apply(source: String, caret: Int, key: String): String {
        val result = StringBuilder(source)
        for (edit in GoStructTagEdits.addKey(source, caret, key).sortedByDescending { it.offset }) {
            result.insert(edit.offset, edit.text)
        }
        return result.toString()
    }
}
