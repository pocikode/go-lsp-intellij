package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GoFillAllFieldsIntentionTest {
    @Test
    fun `caret requests the whole line rather than a zero width range`() {
        val source = "_ = Person{}\nnext()"
        assertEquals(
            GoFillAllFieldsRange(0, 12),
            range(source, source.indexOf('{')),
        )
    }

    @Test
    fun `selection is preserved as the request range`() {
        val source = "_ = Person{}\n"
        assertEquals(
            GoFillAllFieldsRange(4, 12),
            range(source, 7, 4, 12),
        )
    }

    private fun range(source: String, caret: Int, start: Int? = null, end: Int? = null): GoFillAllFieldsRange {
        val lines = source.lineSequence().toList()
        val starts = buildList {
            var offset = 0
            for (line in lines) {
                add(offset)
                offset += line.length + 1
            }
        }
        return requestRange(
            caret,
            start,
            end,
            source.length,
            { offset -> starts.indexOfLast { it <= offset }.coerceAtLeast(0) },
            { line -> starts[line] },
            { line -> starts[line] + lines[line].length },
        )
    }
}
