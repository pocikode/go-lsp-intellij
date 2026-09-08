package com.github.pocikode.go_lsp_intellij

import com.intellij.lang.Language
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

private val GO_TODO_LINE_COMMENT = IElementType("GO_TODO_LINE_COMMENT", Language.ANY)
private val GO_TODO_BLOCK_COMMENT = IElementType("GO_TODO_BLOCK_COMMENT", Language.ANY)
private val GO_TODO_COMMENT_TOKENS = TokenSet.create(GO_TODO_LINE_COMMENT, GO_TODO_BLOCK_COMMENT)

/** Supplies the comment ranges that TextMate's empty PSI lexer cannot provide to the TODO search. */
class GoTodoPatternBuilder : IndexPatternBuilder {

    override fun getIndexingLexer(file: PsiFile): Lexer? =
        if (isGoTextMateFile(file)) GoTodoCommentLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? =
        if (isGoTextMateFile(file)) GO_TODO_COMMENT_TOKENS else null

    override fun getCommentStartDelta(tokenType: IElementType): Int = 2

    override fun getCommentEndDelta(tokenType: IElementType): Int =
        if (tokenType === GO_TODO_BLOCK_COMMENT) 2 else 0

    private fun isGoTextMateFile(file: PsiFile): Boolean =
        !GoLspSupport.isNativeGoPluginLoaded() &&
            file.fileType.name == "textmate" &&
            file.virtualFile?.extension == "go"
}

private class GoTodoCommentLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var comments = emptyList<GoTodoComments.Comment>()
    private var commentIndex = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        comments = GoTodoComments.find(buffer)
        commentIndex = comments.indexOfFirst { it.range.endOffset > startOffset }.let {
            if (it < 0) comments.size else it
        }
        locateToken(startOffset)
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        locateToken(tokenEnd)
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    private fun locateToken(offset: Int) {
        tokenStart = offset
        if (offset >= bufferEnd) {
            tokenEnd = offset
            tokenType = null
            return
        }

        while (commentIndex < comments.size && comments[commentIndex].range.endOffset <= offset) {
            commentIndex++
        }
        val comment = comments.getOrNull(commentIndex)
        if (comment == null || comment.range.startOffset >= bufferEnd) {
            tokenEnd = bufferEnd
            tokenType = TokenType.WHITE_SPACE
        } else if (comment.range.startOffset > offset) {
            tokenEnd = comment.range.startOffset.coerceAtMost(bufferEnd)
            tokenType = TokenType.WHITE_SPACE
        } else {
            tokenEnd = comment.range.endOffset.coerceAtMost(bufferEnd)
            tokenType = when (comment.kind) {
                GoTodoComments.Kind.LINE -> GO_TODO_LINE_COMMENT
                GoTodoComments.Kind.BLOCK -> GO_TODO_BLOCK_COMMENT
            }
        }
    }
}
