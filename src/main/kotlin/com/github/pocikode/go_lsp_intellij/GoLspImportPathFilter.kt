package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.psi.PsiFile

/**
 * Keeps import paths looking like the strings they are.
 *
 * `gopls` reports the path in `import "net/http"` as a `namespace` token, the same type it uses for
 * the `gin` in `gin.Engine` and with no modifier to tell the two apart, so [GoLspSemanticTokens]
 * cannot help but colour it as a package. GoLand paints qualifiers as packages but leaves import
 * paths as plain strings, and the whole import block stood out here because of it.
 *
 * A token that is bracketed by double quotes is an import path: no package qualifier ever is. That
 * is the only test applied, so `%d` inside a format string and every other in-string highlight are
 * untouched.
 */
class GoLspImportPathFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        val psiFile = file ?: return true
        val virtualFile = psiFile.virtualFile ?: return true
        if (!GoLspSupport.isGoFile(virtualFile)) return true

        // Keep document links enabled for go.mod/go.work navigation, but hide them in source files:
        // gopls turns every import string into an underlined pkg.go.dev link unlike GoLand.
        if (highlightInfo.forcedTextAttributesKey === CodeInsightColors.INACTIVE_HYPERLINK_ATTRIBUTES) return false
        if (highlightInfo.forcedTextAttributesKey !== GoLspColors.PACKAGE) return true

        val text = psiFile.viewProvider.document?.charsSequence ?: return true
        val start = highlightInfo.startOffset
        val end = highlightInfo.endOffset
        if (start <= 0 || end >= text.length || start >= end) return true

        return !(text[start - 1] == '"' && text[end] == '"')
    }
}
