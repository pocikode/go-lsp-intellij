package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.util.TextRange

/** Finds Go line and block comments without mistaking comment markers inside literals for comments. */
object GoTodoComments {

    enum class Kind {
        LINE,
        BLOCK,
    }

    data class Comment(val range: TextRange, val kind: Kind)

    fun find(text: CharSequence): List<Comment> {
        val comments = mutableListOf<Comment>()
        var offset = 0
        while (offset < text.length) {
            when {
                text.startsWith("//", offset) -> {
                    val end = text.indexOf('\n', offset + 2).let { if (it < 0) text.length else it }
                    comments += Comment(TextRange(offset, end), Kind.LINE)
                    offset = end
                }
                text.startsWith("/*", offset) -> {
                    val close = text.indexOf("*/", offset + 2)
                    val end = if (close < 0) text.length else close + 2
                    comments += Comment(TextRange(offset, end), Kind.BLOCK)
                    offset = end
                }
                text[offset] == '"' || text[offset] == '\'' -> offset = text.skipQuoted(offset)
                text[offset] == '`' -> {
                    val close = text.indexOf('`', offset + 1)
                    offset = if (close < 0) text.length else close + 1
                }
                else -> offset++
            }
        }
        return comments
    }

    private fun CharSequence.skipQuoted(open: Int): Int {
        val quote = this[open]
        var offset = open + 1
        while (offset < length) {
            when (this[offset]) {
                '\\' -> offset = (offset + 2).coerceAtMost(length)
                quote -> return offset + 1
                // Recover at a newline so one malformed literal does not hide the rest of the file.
                '\n' -> return offset
                else -> offset++
            }
        }
        return offset
    }

    private fun CharSequence.startsWith(value: String, offset: Int): Boolean {
        if (offset + value.length > length) return false
        return value.indices.all { this[offset + it] == value[it] }
    }

    private fun CharSequence.indexOf(value: Char, from: Int): Int {
        for (offset in from until length) if (this[offset] == value) return offset
        return -1
    }

    private fun CharSequence.indexOf(value: String, from: Int): Int {
        for (offset in from..length - value.length) if (startsWith(value, offset)) return offset
        return -1
    }
}
