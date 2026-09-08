package com.github.pocikode.go_lsp_intellij

/**
 * The test functions a Go file declares, and the `-run` patterns that select them.
 *
 * These are read out of the file's text rather than its PSI or `gopls`. The bundled TextMate
 * grammar parses a whole `.go` file into a single PSI leaf, so there is no declaration to hang a
 * gutter icon on - the same problem [GoLspCodeVisionService] works around - and going through
 * `gopls` would mean no run icons at all in a build without the LSP module, where `go test` itself
 * works perfectly well. A test function is always a top-level `func` whose name follows a fixed
 * shape, so a line-anchored match over the text finds exactly what `go test` will run.
 */
object GoTestFunctions {

    enum class Kind { TEST, BENCHMARK, FUZZ, EXAMPLE }

    /** A statically named subtest, with [nameOffset] pointing at its name in the source. */
    data class Subtest(val name: String, val nameOffset: Int, val nameLength: Int)

    /** A test function declaration, with [nameOffset] pointing at the identifier itself. */
    data class Declaration(
        val name: String,
        val kind: Kind,
        val nameOffset: Int,
        val subtests: List<Subtest> = emptyList(),
    )

    /**
     * The test functions declared in [text].
     *
     * `TestMain` is deliberately left out: it is the package's test entry point rather than a test,
     * and `-run TestMain` selects nothing.
     */
    fun find(text: String): List<Declaration> =
        DECLARATION.findAll(text)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                if (name == TEST_MAIN) return@mapNotNull null
                val kind = KINDS[match.groupValues[2]] ?: return@mapNotNull null
                val nameOffset = match.range.first + match.value.indexOf(name)
                Declaration(name, kind, nameOffset, findSubtests(text, match.range.last + 1))
            }
            .toList()

    /**
     * Finds subtests whose names can be known without running Go. This covers direct literal names
     * and the conventional table-driven shape `for _, tt := range tests { t.Run(tt.name, ...) }`
     * where `tests` is a slice of anonymous struct literals.
     */
    private fun findSubtests(text: String, signatureEnd: Int): List<Subtest> {
        val bodyStart = text.indexOf('{', signatureEnd).takeIf { it >= 0 } ?: return emptyList()
        val bodyEnd = matchingBrace(text, bodyStart) ?: return emptyList()
        val body = text.substring(bodyStart + 1, bodyEnd)
        val bodyOffset = bodyStart + 1

        val tables = tableFields(body, bodyOffset)
        val loops = TABLE_LOOP.findAll(body).map { match ->
            Loop(match.range.first, match.groupValues[1], match.groupValues[2])
        }.toList()

        return RUN_CALL.findAll(body).mapNotNull { match ->
            val argument = match.groupValues[1]
            val literal = decodeString(argument)
            if (literal != null) {
                val literalOffset = bodyOffset + match.range.first + match.value.indexOf(argument) + 1
                return@mapNotNull Subtest(normalizeName(literal), literalOffset, argument.length - 2)
            }

            val variable = match.groupValues[2]
            val field = match.groupValues[3]
            val table = loops.lastOrNull { it.offset < match.range.first && it.variable == variable }?.table
                ?: return@mapNotNull null
            tables[table to field]
        }.flatMap { value ->
            when (value) {
                is Subtest -> sequenceOf(value)
                is List<*> -> value.asSequence().filterIsInstance<Subtest>()
                else -> emptySequence()
            }
        }.distinctBy { it.name to it.nameOffset }.sortedBy { it.nameOffset }.toList()
    }

    private fun tableFields(body: String, bodyOffset: Int): Map<Pair<String, String>, List<Subtest>> {
        val result = LinkedHashMap<Pair<String, String>, MutableList<Subtest>>()
        for (match in TABLE.findAll(body)) {
            val structStart = body.indexOf('{', match.range.first)
            val structEnd = matchingBrace(body, structStart) ?: continue
            val fields = structFields(body.substring(structStart + 1, structEnd))
            val valuesStart = body.indexOfFirstFrom(structEnd + 1) { !it.isWhitespace() }
            if (valuesStart < 0 || body[valuesStart] != '{') continue
            val valuesEnd = matchingBrace(body, valuesStart) ?: continue
            val values = body.substring(valuesStart + 1, valuesEnd)
            val table = match.groupValues[1]
            for (field in TABLE_FIELD.findAll(values)) {
                val literal = field.groupValues[2]
                val name = decodeString(literal) ?: continue
                val offset = bodyOffset + valuesStart + 1 + field.range.first + field.value.indexOf(literal) + 1
                result.getOrPut(table to field.groupValues[1]) { mutableListOf() }
                    .add(Subtest(normalizeName(name), offset, literal.length - 2))
            }
            for ((rowOffset, row) in compositeRows(values)) {
                if (TABLE_FIELD.containsMatchIn(row)) continue
                for ((index, value) in commaSeparated(row).withIndex()) {
                    val field = fields.getOrNull(index) ?: continue
                    val literal = value.second.trim()
                    val name = decodeString(literal) ?: continue
                    val leading = value.second.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val offset = bodyOffset + valuesStart + 1 + rowOffset + 1 + value.first + leading + 1
                    result.getOrPut(table to field) { mutableListOf() }
                        .add(Subtest(normalizeName(name), offset, literal.length - 2))
                }
            }
        }
        return result
    }

    private fun structFields(struct: String): List<String> = struct
        .split('\n', ';')
        .flatMap { declaration ->
            val names = FIELD_DECLARATION.find(declaration.trim())?.groupValues?.get(1) ?: return@flatMap emptyList()
            names.split(',').map(String::trim)
        }

    private fun compositeRows(values: String): List<Pair<Int, String>> = buildList {
        var index = 0
        while (index < values.length) {
            if (values[index] != '{') {
                index = when {
                    values[index] == '"' || values[index] == '\'' || values[index] == '`' -> stringEnd(values, index) + 1
                    else -> index + 1
                }
                continue
            }
            val end = matchingBrace(values, index) ?: break
            add(index to values.substring(index + 1, end))
            index = end + 1
        }
    }

    private fun commaSeparated(row: String): List<Pair<Int, String>> = buildList {
        var start = 0
        var depth = 0
        var index = 0
        while (index <= row.length) {
            if (index == row.length || row[index] == ',' && depth == 0) {
                add(start to row.substring(start, index))
                start = index + 1
            } else {
                when (row[index]) {
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> depth--
                    '"', '\'', '`' -> index = stringEnd(row, index)
                }
            }
            index++
        }
    }

    private fun matchingBrace(text: String, opening: Int): Int? {
        if (opening !in text.indices || text[opening] != '{') return null
        var depth = 0
        var index = opening
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
                '"', '\'', '`' -> index = stringEnd(text, index)
                '/' -> when (text.getOrNull(index + 1)) {
                    '/' -> index = text.indexOf('\n', index + 2).takeIf { it >= 0 } ?: text.lastIndex
                    '*' -> index = text.indexOf("*/", index + 2).let { if (it < 0) text.lastIndex else it + 1 }
                }
            }
            index++
        }
        return null
    }

    private fun stringEnd(text: String, opening: Int): Int {
        val quote = text[opening]
        var index = opening + 1
        while (index < text.length) {
            if (quote != '`' && text[index] == '\\') index++
            else if (text[index] == quote) return index
            index++
        }
        return text.lastIndex
    }

    private fun decodeString(literal: String): String? {
        if (literal.length < 2) return null
        if (literal.first() == '`' && literal.last() == '`') {
            return literal.substring(1, literal.lastIndex).replace("\r", "")
        }
        if (literal.first() != '"' || literal.last() != '"') return null
        return buildString {
            var index = 1
            while (index < literal.lastIndex) {
                val character = literal[index++]
                if (character != '\\' || index >= literal.lastIndex) {
                    append(character)
                    continue
                }
                when (val escaped = literal[index++]) {
                    'a' -> append('\u0007')
                    'b' -> append('\b')
                    'f' -> append('\u000c')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'v' -> append('\u000b')
                    '\\', '"', '\'' -> append(escaped)
                    'x' -> appendEscapedCodePoint(literal, index, 2).also { index += 2 }
                    'u' -> appendEscapedCodePoint(literal, index, 4).also { index += 4 }
                    'U' -> appendEscapedCodePoint(literal, index, 8).also { index += 8 }
                    in '0'..'7' -> {
                        if (index + 1 >= literal.lastIndex) return null
                        val value = literal.substring(index - 1, index + 2).toIntOrNull(8) ?: return null
                        append(value.toChar())
                        index += 2
                    }
                    else -> return null
                }
            }
        }
    }

    private fun StringBuilder.appendEscapedCodePoint(literal: String, start: Int, length: Int) {
        if (start + length > literal.lastIndex) return
        val value = literal.substring(start, start + length).toIntOrNull(16) ?: return
        if (Character.isValidCodePoint(value) && value !in 0xd800..0xdfff) appendCodePoint(value)
    }

    /** Mirrors `testing.rewrite`: whitespace becomes `_` and non-printing runes stay escaped. */
    private fun normalizeName(name: String): String = buildString(name.length) {
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            when {
                isGoSpace(codePoint) -> append('_')
                Character.isISOControl(codePoint) -> appendQuoted(codePoint)
                else -> appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isGoSpace(codePoint: Int): Boolean = codePoint in 0x2000..0x200a || codePoint in GO_SPACES

    private fun StringBuilder.appendQuoted(codePoint: Int) {
        when (codePoint) {
            0x07 -> append("\\a")
            0x08 -> append("\\b")
            0x0c -> append("\\f")
            0x0a -> append("\\n")
            0x0d -> append("\\r")
            0x09 -> append("\\t")
            0x0b -> append("\\v")
            in 0..0xff -> append("\\x%02x".format(codePoint))
            in 0..0xffff -> append("\\u%04x".format(codePoint))
            else -> append("\\U%08x".format(codePoint))
        }
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }

    private data class Loop(val offset: Int, val variable: String, val table: String)

    /**
     * The `-run` value that selects exactly [names], each a full `go test` name whose `/` separated
     * parts are the test and its subtests.
     *
     * `-run` matches each part of a name against the corresponding part of the pattern, so one name
     * becomes one anchored pattern per part. Several names have to share a single pattern, which
     * would over-match once subtests are involved, so they are reduced to their top-level tests:
     * re-running a parent runs the subtest that failed along with its siblings, which is the
     * behaviour to prefer over silently running something else.
     */
    fun runPattern(names: Collection<String>): String {
        if (names.isEmpty()) return ""
        val single = names.singleOrNull()
        if (single != null) return single.split('/').joinToString("/") { anchored(it) }
        return anchored(names.map { it.substringBefore('/') }.distinct())
    }

    private fun anchored(part: String): String = "^${quote(part)}$"

    private fun anchored(parts: List<String>): String =
        parts.singleOrNull()?.let(::anchored) ?: "^(${parts.joinToString("|") { quote(it) }})$"

    /** Escapes [text] for Go's RE2, which `-run` compiles the pattern with. */
    private fun quote(text: String): String = buildString {
        for (character in text) {
            if (character in RE2_METACHARACTERS) append('\\')
            append(character)
        }
    }

    private const val TEST_MAIN = "TestMain"
    private const val RE2_METACHARACTERS = """\.+*?()|[]{}^$"""
    private val GO_SPACES = setOf(0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, 0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)

    /**
     * A top-level test declaration. Go requires the character after the prefix not to be a
     * lowercase letter, which is what keeps `func Testing()` out; it holds for an example too,
     * whose lowercase suffix is always introduced by an underscore.
     *
     * A method is not matched because the pattern is anchored to `func` followed by the name, and a
     * method declaration has its receiver in between.
     */
    private val DECLARATION = Regex("""(?m)^func[ \t]+((Test|Benchmark|Fuzz|Example)(?![a-z])\w*)[ \t]*\(""")

    private val RUN_CALL = Regex("""\b\w+\.Run\s*\(\s*(\"(?:\\.|[^\"\\])*\"|`[^`]*`|(\w+)\.(\w+))""")

    private val TABLE = Regex("""\b(\w+)\s*:=\s*\[\s*]\s*struct\s*\{""")

    private val TABLE_FIELD = Regex("""\b(\w+)\s*:\s*(\"(?:\\.|[^\"\\])*\"|`[^`]*`)""")

    private val TABLE_LOOP = Regex("""\bfor\s+(?:\w+\s*,\s*)?(\w+)\s*:=\s*range\s+(\w+)\b""")

    private val FIELD_DECLARATION = Regex("""^(\w+(?:\s*,\s*\w+)*)\s+.+$""")

    private val KINDS = mapOf(
        "Test" to Kind.TEST,
        "Benchmark" to Kind.BENCHMARK,
        "Fuzz" to Kind.FUZZ,
        "Example" to Kind.EXAMPLE,
    )
}
