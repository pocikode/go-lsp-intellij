package com.github.pocikode.go_lsp_intellij

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoModuleSyntaxTest {
    @Test
    fun `go mod tokens distinguish directives modules versions operators and comments`() {
        val source = "require example.com/lib v1.2.3 // indirect\nreplace example.com/old => ../local"
        val tokens = GoModuleTokenizer.tokenize(source).filterNot { it.type == com.intellij.psi.TokenType.WHITE_SPACE }

        assertEquals(
            listOf(
                GoModuleTokenTypes.DIRECTIVE,
                GoModuleTokenTypes.MODULE_PATH,
                GoModuleTokenTypes.VERSION,
                GoModuleTokenTypes.COMMENT,
                GoModuleTokenTypes.DIRECTIVE,
                GoModuleTokenTypes.MODULE_PATH,
                GoModuleTokenTypes.OPERATOR,
                GoModuleTokenTypes.MODULE_PATH,
            ),
            tokens.map(GoModuleToken::type),
        )
        assertEquals("// indirect", source.substring(tokens[3].start, tokens[3].end))
    }

    @Test
    fun `go sum tokens highlight version and checksum`() {
        val source = "example.com/lib v1.2.3/go.mod h1:abcDEF123="
        val types: List<IElementType> = GoModuleTokenizer.tokenize(source)
            .filterNot { it.type == com.intellij.psi.TokenType.WHITE_SPACE }
            .map(GoModuleToken::type)

        assertEquals(
            listOf(GoModuleTokenTypes.MODULE_PATH, GoModuleTokenTypes.VERSION, GoModuleTokenTypes.CHECKSUM),
            types,
        )
    }

    @Test
    fun `quoted replacement path does not start a comment`() {
        val source = "replace example.com/lib => `../local // module`"
        val tokens = GoModuleTokenizer.tokenize(source).filterNot { it.type == com.intellij.psi.TokenType.WHITE_SPACE }

        assertEquals(GoModuleTokenTypes.STRING, tokens.last().type)
        assertEquals("`../local // module`", source.substring(tokens.last().start, tokens.last().end))
    }

    @Test
    fun `completion offers directives modules and known versions`() {
        val modules = listOf(
            GoModuleDependency("example.com/app", "", true, false, null, null, null, emptyList()),
            GoModuleDependency(
                "example.com/lib",
                "v1.2.3",
                false,
                false,
                null,
                GoModuleVersion("example.com/lib", "v1.4.0"),
                null,
                emptyList(),
            ),
        )

        assertTrue(GoModuleCompletion.suggestions("go.mod", "req", 3, modules).any { it.value == "require" })
        assertEquals(
            listOf("example.com/lib"),
            GoModuleCompletion.suggestions("go.mod", "require ex", 10, modules).map(GoModuleSuggestion::value),
        )
        assertEquals(
            listOf("v1.2.3", "v1.4.0"),
            GoModuleCompletion.suggestions("go.sum", "example.com/lib ", 16, modules).map(GoModuleSuggestion::value),
        )
        assertEquals(
            listOf("example.com/lib"),
            GoModuleCompletion.suggestions("go.mod", "require (\n\tex", 13, modules).map(GoModuleSuggestion::value),
        )
    }

    @Test
    fun `module candidates are recovered before dependency report refresh`() {
        val modules = GoModuleCompletion.modulesFromFile(
            "go.mod",
            """
            require (
                example.com/one v1.2.3
                example.com/two v2.0.0 // indirect
            )
            """.trimIndent(),
        )

        assertEquals(listOf("example.com/one", "example.com/two"), modules.map(GoModuleDependency::path))
        assertTrue(modules.last().indirect)
    }
}
