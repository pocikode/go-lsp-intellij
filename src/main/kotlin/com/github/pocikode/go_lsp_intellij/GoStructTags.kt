package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.util.TextRange

/**
 * Finds the `json:` parts of Go struct tags.
 *
 * GoLand paints a struct tag in three pieces: the backticks and the quoted value keep the raw string
 * colour, and the key with its colon is dropped to the plain identifier colour. `gopls` reports the
 * whole tag as one flat `string` token and the TextMate grammar scopes it as one `string.quoted.raw`,
 * so neither can express that split; this does, from the text alone.
 *
 * Only a raw string whose entire content is a run of `key:"value"` pairs is treated as a tag, which
 * is what the Go convention prescribes and what `reflect.StructTag` parses. A raw string used for
 * anything else - SQL, a regexp, a template - does not match and is left alone.
 */
object GoStructTags {

    /** A whole tag body: one or more `key:"value"` pairs, nothing else but spacing between them. */
    private val TAG_BODY = Regex("""\s*(?:[A-Za-z_][A-Za-z0-9_.\-]*:"(?:[^"\\]|\\.)*"\s*)+""")

    /** The `key:` of one pair, up to and including the colon. */
    private val TAG_KEY = Regex("""([A-Za-z_][A-Za-z0-9_.\-]*:)"""")

    /** Ranges of every `key:` in every struct tag in [text], in document offsets. */
    fun keyRanges(text: CharSequence): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        for (tag in rawStrings(text)) {
            val body = text.subSequence(tag.startOffset, tag.endOffset)
            if (!TAG_BODY.matches(body)) continue
            for (match in TAG_KEY.findAll(body)) {
                val key = match.groups[1]!!.range
                ranges += TextRange(tag.startOffset + key.first, tag.startOffset + key.last + 1)
            }
        }
        return ranges
    }

    /**
     * The contents of every raw string literal in [text], backticks excluded.
     *
     * Go has no escapes inside a raw string, so one runs to the next backtick. The scan still has to
     * step over comments, interpreted strings, and rune literals, or a backtick written inside any of
     * them would open a literal that is not there.
     */
    private fun rawStrings(text: CharSequence): List<TextRange> {
        val found = mutableListOf<TextRange>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> i = text.indexOfFrom('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> {
                    val close = text.indexOfFrom("*/", i + 2)
                    i = if (close < 0) text.length else close + 2
                }
                text[i] == '"' || text[i] == '\'' -> i = skipQuoted(text, i)
                text[i] == '`' -> {
                    val close = text.indexOfFrom('`', i + 1)
                    if (close < 0) return found
                    found += TextRange(i + 1, close)
                    i = close + 1
                }
                else -> i++
            }
        }
        return found
    }

    /** Index just past the literal opened by the quote at [open], honouring backslash escapes. */
    private fun skipQuoted(text: CharSequence, open: Int): Int {
        val quote = text[open]
        var i = open + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                // An interpreted string cannot span lines; bail out rather than swallow the file.
                '\n' -> return i
                else -> i++
            }
        }
        return i
    }

    private fun CharSequence.indexOfFrom(c: Char, from: Int): Int {
        var i = from
        while (i < length) {
            if (this[i] == c) return i
            i++
        }
        return -1
    }

    private fun CharSequence.indexOfFrom(s: String, from: Int): Int {
        var i = from
        while (i + s.length <= length) {
            if (startsWith(s, i)) return i
            i++
        }
        return -1
    }

    private fun CharSequence.startsWith(s: String, at: Int): Boolean {
        if (at + s.length > length) return false
        for (j in s.indices) if (this[at + j] != s[j]) return false
        return true
    }
}
