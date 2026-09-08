package com.github.pocikode.go_lsp_intellij

/** Finds the runnable `main` declaration without depending on Go PSI or `gopls`. */
object GoMainFunction {

    /** The offset of `main` in a parameterless, result-less top-level function in `package main`. */
    fun findNameOffset(text: String): Int? {
        if (PACKAGE_MAIN.find(text) == null) return null
        val declaration = MAIN_DECLARATION.find(text) ?: return null
        return declaration.groups[1]?.range?.first
    }

    private val PACKAGE_MAIN = Regex("""(?m)^package[ \t]+main(?:[ \t]|$)""")
    private val MAIN_DECLARATION = Regex("""(?m)^func[ \t]+(main)[ \t]*\(\s*\)[ \t]*\{""")
}
