package com.github.pocikode.go_lsp_intellij

import com.intellij.lang.Language
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

internal object GoModuleTokenTypes {
    val COMMENT = IElementType("GO_MODULE_COMMENT", GoModuleLanguage)
    val DIRECTIVE = IElementType("GO_MODULE_DIRECTIVE", GoModuleLanguage)
    val MODULE_PATH = IElementType("GO_MODULE_PATH", GoModuleLanguage)
    val VERSION = IElementType("GO_MODULE_VERSION", GoModuleLanguage)
    val CHECKSUM = IElementType("GO_MODULE_CHECKSUM", GoModuleLanguage)
    val STRING = IElementType("GO_MODULE_STRING", GoModuleLanguage)
    val OPERATOR = IElementType("GO_MODULE_OPERATOR", GoModuleLanguage)
    val PARENTHESES = IElementType("GO_MODULE_PARENTHESES", GoModuleLanguage)
    val TEXT = IElementType("GO_MODULE_TEXT", GoModuleLanguage)
}

internal data class GoModuleToken(val type: IElementType, val start: Int, val end: Int)

internal object GoModuleTokenizer {
    private val directives = setOf(
        "module", "go", "toolchain", "godebug", "require", "exclude", "replace", "retract", "tool", "use", "ignore",
    )
    private val version = Regex("(?:v|go)?\\d+(?:\\.\\d+)*(?:[-+][0-9A-Za-z.-]+)?(?:/go\\.mod)?")

    fun tokenize(text: CharSequence, start: Int = 0, end: Int = text.length): List<GoModuleToken> {
        val result = mutableListOf<GoModuleToken>()
        var offset = start
        while (offset < end) {
            val tokenStart = offset
            val type = when {
                text[offset].isWhitespace() -> {
                    while (offset < end && text[offset].isWhitespace()) offset++
                    TokenType.WHITE_SPACE
                }
                text[offset] == '/' && offset + 1 < end && text[offset + 1] == '/' -> {
                    offset += 2
                    while (offset < end && text[offset] != '\n') offset++
                    GoModuleTokenTypes.COMMENT
                }
                text[offset] == '=' && offset + 1 < end && text[offset + 1] == '>' -> {
                    offset += 2
                    GoModuleTokenTypes.OPERATOR
                }
                text[offset] == '(' || text[offset] == ')' -> {
                    offset++
                    GoModuleTokenTypes.PARENTHESES
                }
                text[offset] == '"' || text[offset] == '`' -> {
                    val quote = text[offset++]
                    while (offset < end) {
                        if (quote == '"' && text[offset] == '\\' && offset + 1 < end) {
                            offset += 2
                        } else if (text[offset++] == quote) {
                            break
                        }
                    }
                    GoModuleTokenTypes.STRING
                }
                else -> {
                    while (offset < end && !text[offset].isWhitespace() && text[offset] !in "()" &&
                        !(text[offset] == '/' && offset + 1 < end && text[offset + 1] == '/') &&
                        !(text[offset] == '=' && offset + 1 < end && text[offset + 1] == '>')
                    ) offset++
                    classify(text.subSequence(tokenStart, offset).toString())
                }
            }
            result += GoModuleToken(type, tokenStart, offset)
        }
        return result
    }

    private fun classify(word: String): IElementType = when {
        word in directives -> GoModuleTokenTypes.DIRECTIVE
        word.startsWith("h1:") -> GoModuleTokenTypes.CHECKSUM
        version.matches(word) -> GoModuleTokenTypes.VERSION
        '/' in word || '.' in word || word.startsWith(".") -> GoModuleTokenTypes.MODULE_PATH
        else -> GoModuleTokenTypes.TEXT
    }
}

internal class GoModuleLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokens: List<GoModuleToken> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokens = GoModuleTokenizer.tokenize(buffer, startOffset, endOffset)
        index = 0
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type
    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset
    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset
    override fun advance() { index++ }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
}

class GoModuleSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GoModuleLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        GoModuleTokenTypes.COMMENT -> pack(DefaultLanguageHighlighterColors.LINE_COMMENT)
        GoModuleTokenTypes.DIRECTIVE -> pack(DefaultLanguageHighlighterColors.KEYWORD)
        GoModuleTokenTypes.MODULE_PATH -> pack(GoLspColors.PACKAGE)
        GoModuleTokenTypes.VERSION -> pack(DefaultLanguageHighlighterColors.NUMBER)
        GoModuleTokenTypes.CHECKSUM -> pack(DefaultLanguageHighlighterColors.STRING)
        GoModuleTokenTypes.STRING -> pack(DefaultLanguageHighlighterColors.STRING)
        GoModuleTokenTypes.OPERATOR -> pack(DefaultLanguageHighlighterColors.OPERATION_SIGN)
        GoModuleTokenTypes.PARENTHESES -> pack(DefaultLanguageHighlighterColors.PARENTHESES)
        else -> TextAttributesKey.EMPTY_ARRAY
    }
}

class GoModuleSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GoModuleSyntaxHighlighter()
}
