package com.github.pocikode.go_lsp_intellij

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoLspMethodStubsTest {

    @Test
    fun `a gopls method detail becomes the text after the method name`() {
        assertEquals(
            "(ctx context.Context, id string) (*V1, error)",
            GoLspMethodStubs.signatureOf("func(ctx context.Context, id string) (*V1, error)"),
        )
        assertEquals("()", GoLspMethodStubs.signatureOf("func()"))
        assertEquals("() error", GoLspMethodStubs.signatureOf("func() error"))
    }

    @Test
    fun `details that are not signatures are skipped`() {
        // An embedded interface is reported with the type name as its detail, and a struct member
        // with its type; neither can be turned into a method.
        assertNull(GoLspMethodStubs.signatureOf("io.Closer"))
        assertNull(GoLspMethodStubs.signatureOf("string"))
        assertNull(GoLspMethodStubs.signatureOf(null))
    }

    @Test
    fun `the default receiver is the first letter of the type`() {
        assertEquals("v", GoLspMethodStubs.receiverNameFor("V1"))
        assertEquals("r", GoLspMethodStubs.receiverNameFor("Repo"))
        // Nothing usable to derive it from.
        assertEquals("r", GoLspMethodStubs.receiverNameFor("_hidden"))
        assertEquals("r", GoLspMethodStubs.receiverNameFor(""))
    }

    @Test
    fun `a rendered method is compilable Go with GoLand's body`() {
        val methods = listOf(
            GoLspInterfaceMethod("Get", "(ctx context.Context, id string) (*V1, error)"),
            GoLspInterfaceMethod("Close", "() error"),
        )
        // Two blank lines lead, so the block follows whatever it is appended to.
        val expected = "\n\nfunc (r *Repo) Get(ctx context.Context, id string) (*V1, error) {\n" +
            "\t//TODO implement me\n" +
            "\tpanic(\"implement me\")\n" +
            "}" +
            "\n\nfunc (r *Repo) Close() error {\n" +
            "\t//TODO implement me\n" +
            "\tpanic(\"implement me\")\n" +
            "}"
        assertEquals(
            expected,
            GoLspMethodStubs.render("Repo", "r", pointer = true, methods = methods),
        )
    }

    @Test
    fun `a value receiver drops the star`() {
        assertTrue(
            GoLspMethodStubs.render("Repo", "r", pointer = false, methods = listOf(GoLspInterfaceMethod("Ping", "()")))
                .contains("func (r Repo) Ping() {"),
        )
    }

    @Test
    fun `gopls method names carry the receiver they are declared on`() {
        val pointerMethod = declaration("(*Repo).Get")
        assertEquals("Repo", pointerMethod.methodOf)
        assertEquals("Get", pointerMethod.simpleName)
        assertTrue(pointerMethod.hasPointerReceiver)

        val valueMethod = declaration("(Repo).Ping")
        assertEquals("Repo", valueMethod.methodOf)
        assertEquals("Ping", valueMethod.simpleName)
        assertFalse(valueMethod.hasPointerReceiver)
    }

    @Test
    fun `a plain declaration is nobody's method`() {
        val type = declaration("Repo")
        assertNull(type.methodOf)
        assertEquals("Repo", type.simpleName)
    }

    private fun declaration(name: String): GoLspCodeVisionService.Declaration {
        val range = Range(Position(0, 0), Position(0, 1))
        return GoLspCodeVisionService.Declaration(name, SymbolKind.Method, range, range)
    }
}
