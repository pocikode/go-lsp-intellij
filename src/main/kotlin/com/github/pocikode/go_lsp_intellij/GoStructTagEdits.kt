package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.util.TextRange

internal object GoStructTagEdits {
    data class Edit(val offset: Int, val text: String)

    fun addKey(source: CharSequence, caretOffset: Int, key: String): List<Edit> {
        if (!isValidKey(key)) return emptyList()
        val struct = GoStructFields.enclosing(source, caretOffset) ?: return emptyList()
        return struct.fields.mapNotNull { field ->
            if (key in field.tagKeys) return@mapNotNull null
            val pair = pair(key, field.name)
            field.tagBodyRange?.let { Edit(it.endOffset, if (it.isEmpty) pair else " $pair") }
                ?: Edit(field.typeEndOffset, " `$pair`")
        }
    }

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    fun pair(key: String, fieldName: String): String = "$key:\"${fieldName.toSnakeCase()}\""

    private fun String.toSnakeCase(): String {
        val result = StringBuilder(length + 4)
        forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) {
                val previous = this[index - 1]
                val nextIsLower = index + 1 < length && this[index + 1].isLowerCase()
                if (previous.isLowerCase() || previous.isDigit() || previous.isUpperCase() && nextIsLower) result.append('_')
            }
            result.append(character.lowercaseChar())
        }
        return result.toString()
    }

    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")
}
