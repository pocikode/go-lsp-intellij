package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * The colour keys Go highlighting paints with, under GoLand's own external names.
 *
 * Naming GoLand's keys rather than plugin-private ones is what makes a scheme resolve both IDEs the
 * same way: a scheme tuned for GoLand names these keys directly, one that says nothing about Go
 * falls through to `colorSchemes/GoLsp*.xml`, and anything left resolves through the fallback below,
 * which is the fallback GoLand's own key declares.
 *
 * This object deliberately does not reference `com.intellij.platform.lsp`, so it can be used from
 * extensions registered outside `go-lsp.xml`.
 */
object GoLspColors {
    private fun goLandKey(externalName: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(externalName, fallback)

    val PACKAGE = goLandKey("GO_PACKAGE", DefaultLanguageHighlighterColors.IDENTIFIER)
    val TYPE_SPECIFICATION = goLandKey("GO_TYPE_SPECIFICATION", DefaultLanguageHighlighterColors.CLASS_NAME)
    val TYPE_REFERENCE = goLandKey("GO_TYPE_REFERENCE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    val BUILTIN_TYPE_REFERENCE =
        goLandKey("GO_BUILTIN_TYPE_REFERENCE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    val FUNCTION_DECLARATION =
        goLandKey("GO_LOCAL_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val FUNCTION_CALL = goLandKey("GO_LOCAL_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val BUILTIN_FUNCTION_CALL =
        goLandKey("GO_BUILTIN_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val FUNCTION_PARAMETER = goLandKey("GO_FUNCTION_PARAMETER", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val STRUCT_MEMBER = goLandKey("GO_STRUCT_LOCAL_MEMBER", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val CONSTANT = goLandKey("GO_LOCAL_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    val BUILTIN_CONSTANT = goLandKey("GO_BUILTIN_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    val BUILTIN_VARIABLE = goLandKey("GO_BUILTIN_VARIABLE", DefaultLanguageHighlighterColors.GLOBAL_VARIABLE)
    val SHADOWING_VARIABLE = goLandKey("GO_SHADOWING_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val LOCAL_VARIABLE = goLandKey("GO_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val LABEL = goLandKey("GO_LABEL", DefaultLanguageHighlighterColors.LABEL)
    val VALID_STRING_ESCAPE =
        goLandKey("GO_VALID_STRING_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)

    /**
     * The `json:` part of a struct tag. GoLand has no key of its own for this - it paints the tag key
     * in the plain identifier colour and leaves the backticks and the quoted value looking like the
     * raw string they are - so this is the platform key, not a `GO_*` name.
     */
    val STRUCT_TAG_KEY = DefaultLanguageHighlighterColors.IDENTIFIER
}
