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

    /** A test function declaration, with [nameOffset] pointing at the identifier itself. */
    data class Declaration(val name: String, val kind: Kind, val nameOffset: Int)

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
                Declaration(name, kind, match.range.first + match.value.indexOf(name))
            }
            .toList()

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

    /**
     * A top-level test declaration. Go requires the character after the prefix not to be a
     * lowercase letter, which is what keeps `func Testing()` out; it holds for an example too,
     * whose lowercase suffix is always introduced by an underscore.
     *
     * A method is not matched because the pattern is anchored to `func` followed by the name, and a
     * method declaration has its receiver in between.
     */
    private val DECLARATION = Regex("""(?m)^func[ \t]+((Test|Benchmark|Fuzz|Example)(?![a-z])\w*)[ \t]*\(""")

    private val KINDS = mapOf(
        "Test" to Kind.TEST,
        "Benchmark" to Kind.BENCHMARK,
        "Fuzz" to Kind.FUZZ,
        "Example" to Kind.EXAMPLE,
    )
}
