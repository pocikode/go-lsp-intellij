package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Every case below is a token/modifier pair `gopls` actually emits, taken from its semantic token
 * response for a file exercising the corresponding Go construct. The expected key is GoLand's own,
 * by external name, so a scheme resolves both IDEs to the same colour.
 */
class GoLspSemanticTokensTest {

    private fun name(type: String, vararg modifiers: String): String? =
        GoLspSemanticTokens.getTextAttributesKey(type, modifiers.toList())?.externalName

    private fun fallbackOf(type: String, vararg modifiers: String): TextAttributesKey? =
        GoLspSemanticTokens.getTextAttributesKey(type, modifiers.toList())?.fallbackAttributeKey

    @Test
    fun `package clauses and qualifiers use the package key`() {
        // GO_PACKAGE carries an explicit olive in Darcula that IDENTIFIER does not reproduce; this
        // is what made `package router` and the `gin` in `gin.Engine` come out grey.
        assertEquals("GO_PACKAGE", name("namespace"))
        assertEquals(DefaultLanguageHighlighterColors.IDENTIFIER, fallbackOf("namespace"))
    }

    @Test
    fun `type references use the type reference key whatever they name`() {
        // GoLand tells structs from interfaces only with its semantic highlighting on, which is off
        // by default; both are GO_TYPE_REFERENCE, explicitly teal in Darcula.
        assertEquals("GO_TYPE_REFERENCE", name("type", "struct"))
        assertEquals("GO_TYPE_REFERENCE", name("type", "interface"))
        assertEquals("GO_TYPE_REFERENCE", name("typeParameter"))
        assertEquals(DefaultLanguageHighlighterColors.CLASS_REFERENCE, fallbackOf("type", "struct"))
    }

    @Test
    fun `type declarations use the specification key`() {
        assertEquals("GO_TYPE_SPECIFICATION", name("type", "definition", "struct"))
        assertEquals("GO_TYPE_SPECIFICATION", name("type", "definition", "interface"))
        assertEquals(DefaultLanguageHighlighterColors.CLASS_NAME, fallbackOf("type", "definition", "struct"))
    }

    @Test
    fun `builtin types beat the interface modifier`() {
        // `error` arrives as an interface too, and must stay a builtin.
        assertEquals("GO_BUILTIN_TYPE_REFERENCE", name("type", "defaultLibrary", "number"))
        assertEquals("GO_BUILTIN_TYPE_REFERENCE", name("type", "defaultLibrary", "interface"))
    }

    @Test
    fun `calls are told apart from declarations`() {
        assertEquals("GO_LOCAL_FUNCTION", name("function", "definition", "signature"))
        assertEquals("GO_LOCAL_FUNCTION_CALL", name("function", "signature"))
        assertEquals("GO_LOCAL_FUNCTION", name("method", "definition", "signature"))
        assertEquals("GO_LOCAL_FUNCTION_CALL", name("method", "signature"))
        // `len` and `make`.
        assertEquals("GO_BUILTIN_FUNCTION_CALL", name("function", "defaultLibrary"))
        assertEquals(
            DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            fallbackOf("function", "definition", "signature"),
        )
        assertEquals(DefaultLanguageHighlighterColors.FUNCTION_CALL, fallbackOf("function", "signature"))
    }

    @Test
    fun `parameters and receivers share the parameter key`() {
        assertEquals("GO_FUNCTION_PARAMETER", name("parameter", "definition", "slice"))
        assertEquals("GO_FUNCTION_PARAMETER", name("parameter", "struct"))
        assertEquals(DefaultLanguageHighlighterColors.LOCAL_VARIABLE, fallbackOf("parameter", "struct"))
    }

    @Test
    fun `constants and builtins are told apart from variables`() {
        assertEquals("GO_LOCAL_CONSTANT", name("variable", "readonly", "number"))
        // nil, true, false.
        assertEquals("GO_BUILTIN_CONSTANT", name("variable", "readonly", "defaultLibrary"))
        assertEquals("GO_BUILTIN_VARIABLE", name("variable", "defaultLibrary"))
        assertEquals("GO_SHADOWING_VARIABLE", name("variable", "shadowing"))
        assertEquals("GO_LOCAL_VARIABLE", name("variable", "definition", "map"))
    }

    @Test
    fun `struct members and labels use their own keys`() {
        assertEquals("GO_STRUCT_LOCAL_MEMBER", name("property", "definition", "number"))
        assertEquals("GO_LABEL", name("label", "definition"))
        // GoLand leaves struct members the colour of other locals unless semantic highlighting is on.
        assertEquals(DefaultLanguageHighlighterColors.LOCAL_VARIABLE, fallbackOf("property", "number"))
    }

    @Test
    fun `only format verbs are taken out of a string`() {
        assertEquals("GO_VALID_STRING_ESCAPE", name("string", "format"))
        assertNull(name("string"))
    }

    @Test
    fun `lexical tokens are left to the TextMate grammar`() {
        // TextMate already agrees with GoLand here, and tells line from block comments, which the flat
        // `comment` token type cannot.
        assertNull(name("keyword"))
        assertNull(name("comment"))
        assertNull(name("number"))
        assertNull(name("operator"))
        // A token type gopls does not currently emit must not be guessed at either.
        assertNull(name("macro"))
    }
}
