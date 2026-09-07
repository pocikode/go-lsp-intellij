package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport

/**
 * Maps `gopls` semantic tokens onto GoLand's Go colour keys, so one colour scheme paints Go the same
 * way in both IDEs. The keys themselves, and why they carry GoLand's names, live in [GoLspColors].
 *
 * The token/modifier combinations below are the ones `gopls` actually emits. The GoLand key chosen
 * for each is the one GoLand itself applies with its optional semantic highlighting off, which is
 * the default: every non-builtin type reference is `GO_TYPE_REFERENCE`, whether it names a struct or
 * an interface, and struct members are not coloured apart from other locals.
 *
 * Purely lexical tokens (keywords, comments, numbers, operators, plain strings) return null so the
 * bundled TextMate grammar keeps painting them. TextMate already agrees with GoLand there and is
 * more precise than the LSP token stream: `gopls` reports one flat `comment` type for both line and
 * block comments, and does not split escape sequences out of a string literal.
 *
 * One case cannot be settled here. `gopls` reports the path in `import "net/http"` as a `namespace`
 * token with no modifier to tell it from a package qualifier, so it arrives looking like the `gin`
 * in `gin.Engine`, and GoLand paints it as the plain string it is. Nothing in this signature says
 * where the token sits, so [GoLspImportPathFilter] drops that one afterwards, by position.
 */
object GoLspSemanticTokens : LspSemanticTokensSupport() {

    override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? {
        val isDefinition = DEFINITION in modifiers
        val isBuiltin = DEFAULT_LIBRARY in modifiers
        return when (tokenType) {
            "namespace" -> GoLspColors.PACKAGE

            "type", "typeParameter" -> when {
                isDefinition -> GoLspColors.TYPE_SPECIFICATION
                isBuiltin -> GoLspColors.BUILTIN_TYPE_REFERENCE
                else -> GoLspColors.TYPE_REFERENCE
            }

            "function", "method" -> when {
                isDefinition -> GoLspColors.FUNCTION_DECLARATION
                isBuiltin -> GoLspColors.BUILTIN_FUNCTION_CALL
                else -> GoLspColors.FUNCTION_CALL
            }

            // Receivers arrive as parameters too, and GoLand gives both the same colour unless its
            // semantic highlighting is on. The token carries no name, so the two cannot be told apart.
            "parameter" -> GoLspColors.FUNCTION_PARAMETER

            "property" -> GoLspColors.STRUCT_MEMBER

            "variable" -> when {
                READONLY in modifiers -> if (isBuiltin) GoLspColors.BUILTIN_CONSTANT else GoLspColors.CONSTANT
                isBuiltin -> GoLspColors.BUILTIN_VARIABLE
                "shadowing" in modifiers -> GoLspColors.SHADOWING_VARIABLE
                else -> GoLspColors.LOCAL_VARIABLE
            }

            "label" -> GoLspColors.LABEL

            // `gopls` emits the `%d`/`%s` verbs of a format string as separate tokens. The rest of the
            // literal is left to TextMate so escape sequences keep their colour.
            "string" -> if ("format" in modifiers) GoLspColors.VALID_STRING_ESCAPE else null

            else -> null
        }
    }

    private const val DEFINITION = "definition"
    private const val DEFAULT_LIBRARY = "defaultLibrary"
    private const val READONLY = "readonly"
}
