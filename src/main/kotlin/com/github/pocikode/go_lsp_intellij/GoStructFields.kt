package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.util.TextRange

/** Text-only struct field parsing for editor features that cannot use the single-leaf TextMate PSI. */
internal object GoStructFields {
    data class Struct(val range: TextRange, val fields: List<Field>)

    data class Field(
        val name: String,
        val typeEndOffset: Int,
        val tagBodyRange: TextRange?,
        val tagKeys: Set<String>,
    )

    private val STRUCT = Regex("""\bstruct\s*\{""")
    private val NAMED_FIELD = Regex("""^([\p{L}_][\p{L}\p{N}_]*)\s+(.+)$""")
    private val EMBEDDED_FIELD = Regex("""^\*?(?:[\p{L}_][\p{L}\p{N}_]*\.)?([\p{L}_][\p{L}\p{N}_]*(?:\[[^]]+])?)$""")
    private val TAG_PAIR = Regex("""([A-Za-z_][A-Za-z0-9_.\-]*):"((?:[^"\\]|\\.)*)"""")

    fun enclosing(text: CharSequence, offset: Int): Struct? =
        all(text).filter { it.range.containsOffset(offset) }.minByOrNull { it.range.length }

    fun all(text: CharSequence): List<Struct> {
        val ignored = ignoredRanges(text)
        val result = mutableListOf<Struct>()
        for (match in STRUCT.findAll(text)) {
            val open = match.range.last
            if (ignored.containsOffset(open)) continue
            val close = matchingBrace(text, open) ?: continue
            result += Struct(TextRange(match.range.first, close + 1), fields(text, open + 1, close))
        }
        return result
    }

    private fun fields(text: CharSequence, start: Int, end: Int): List<Field> {
        val fields = mutableListOf<Field>()
        var lineStart = start
        var depth = 0
        while (lineStart < end) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > end) end else it }
            if (depth == 0) parseField(text, lineStart, lineEnd)?.let(fields::add)
            depth += braceDelta(text, lineStart, lineEnd)
            lineStart = lineEnd + 1
        }
        return fields
    }

    /** Braces on one field line, excluding comments and literals, to skip nested struct fields. */
    private fun braceDelta(text: CharSequence, start: Int, end: Int): Int {
        var depth = 0
        var offset = start
        while (offset < end) {
            when {
                text.startsWith("//", offset) || text.startsWith("/*", offset) -> return depth
                text[offset] == '"' || text[offset] == '\'' -> offset = skipQuoted(text, offset).coerceAtMost(end)
                text[offset] == '`' -> offset = text.indexOf('`', offset + 1).let { if (it < 0 || it >= end) end else it + 1 }
                text[offset] == '{' -> { depth++; offset++ }
                text[offset] == '}' -> { depth--; offset++ }
                else -> offset++
            }
        }
        return depth
    }

    private fun parseField(text: CharSequence, lineStart: Int, lineEnd: Int): Field? {
        val codeEnd = commentStart(text, lineStart, lineEnd)
        var start = lineStart
        while (start < codeEnd && text[start].isWhitespace()) start++
        var end = codeEnd
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end || text.startsWith("//", start) || text.startsWith("/*", start)) return null

        val tagOpen = lastRawStringOpen(text, start, end)
        val tagBodyRange = if (tagOpen != null && text[end - 1] == '`') TextRange(tagOpen + 1, end - 1) else null
        var declarationEnd = tagOpen ?: end
        while (declarationEnd > start && text[declarationEnd - 1].isWhitespace()) declarationEnd--
        if (declarationEnd <= start) return null

        val declaration = text.subSequence(start, declarationEnd).toString()
        if (declaration.contains(';') || declaration.endsWith('{')) return null
        val named = NAMED_FIELD.matchEntire(declaration)?.takeIf { it.groupValues[2].firstOrNull() != ',' }
        val embedded = if (named == null) EMBEDDED_FIELD.matchEntire(declaration) else null
        val name = named?.groupValues?.get(1)
            ?: embedded?.groupValues?.get(1)?.substringBefore('[')
            ?: return null
        if (name == "_") return null

        val body = tagBodyRange?.let { text.subSequence(it.startOffset, it.endOffset).toString() }.orEmpty()
        val pairs = TAG_PAIR.findAll(body).toList()
        val keys = pairs.mapTo(linkedSetOf()) { it.groupValues[1] }
        return Field(name, declarationEnd, tagBodyRange, keys)
    }

    private fun commentStart(text: CharSequence, start: Int, end: Int): Int {
        var offset = start
        var raw = false
        while (offset < end) {
            when {
                text[offset] == '`' -> raw = !raw
                !raw && text.startsWith("//", offset) -> return offset
                !raw && text.startsWith("/*", offset) -> return offset
            }
            offset++
        }
        return end
    }

    private fun lastRawStringOpen(text: CharSequence, start: Int, end: Int): Int? {
        if (end <= start || text[end - 1] != '`') return null
        var offset = end - 2
        while (offset >= start) {
            if (text[offset] == '`') return offset
            offset--
        }
        return null
    }

    private fun matchingBrace(text: CharSequence, open: Int): Int? {
        var depth = 0
        var offset = open
        while (offset < text.length) {
            when {
                text.startsWith("//", offset) -> offset = text.indexOf('\n', offset + 2).let { if (it < 0) text.length else it }
                text.startsWith("/*", offset) -> offset = text.indexOf("*/", offset + 2).let { if (it < 0) text.length else it + 2 }
                text[offset] == '"' || text[offset] == '\'' -> offset = skipQuoted(text, offset)
                text[offset] == '`' -> offset = text.indexOf('`', offset + 1).let { if (it < 0) text.length else it + 1 }
                text[offset] == '{' -> { depth++; offset++ }
                text[offset] == '}' -> {
                    depth--
                    if (depth == 0) return offset
                    offset++
                }
                else -> offset++
            }
        }
        return null
    }

    private fun ignoredRanges(text: CharSequence): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var offset = 0
        while (offset < text.length) {
            val start = offset
            offset = when {
                text.startsWith("//", offset) -> text.indexOf('\n', offset + 2).let { if (it < 0) text.length else it }
                text.startsWith("/*", offset) -> text.indexOf("*/", offset + 2).let { if (it < 0) text.length else it + 2 }
                text[offset] == '"' || text[offset] == '\'' -> skipQuoted(text, offset)
                text[offset] == '`' -> text.indexOf('`', offset + 1).let { if (it < 0) text.length else it + 1 }
                else -> offset + 1
            }
            if (offset > start + 1) ranges += TextRange(start, offset)
        }
        return ranges
    }

    private fun List<TextRange>.containsOffset(offset: Int): Boolean = any { it.containsOffset(offset) }

    private fun skipQuoted(text: CharSequence, open: Int): Int {
        val quote = text[open]
        var offset = open + 1
        while (offset < text.length) {
            when (text[offset]) {
                '\\' -> offset = (offset + 2).coerceAtMost(text.length)
                quote -> return offset + 1
                '\n' -> return offset
                else -> offset++
            }
        }
        return offset
    }

    private fun CharSequence.startsWith(value: String, offset: Int): Boolean =
        offset + value.length <= length && value.indices.all { this[offset + it] == value[it] }

    private fun CharSequence.indexOf(value: Char, from: Int): Int {
        for (offset in from until length) if (this[offset] == value) return offset
        return -1
    }

    private fun CharSequence.indexOf(value: String, from: Int): Int {
        for (offset in from..length - value.length) if (startsWith(value, offset)) return offset
        return -1
    }
}
