package com.github.pocikode.go_lsp_intellij

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Drops the `json:` part of a struct tag to the plain identifier colour, the way GoLand does, leaving
 * the backticks and the quoted value in the raw string colour.
 *
 * Neither source of highlighting can do this on its own: `gopls` sends the tag as one flat `string`
 * token and the TextMate grammar scopes it as one `string.quoted.raw.go`. See [GoStructTags] for
 * what counts as a tag.
 *
 * Registered for the TextMate language, which is what claims `.go` files here. When JetBrains' Go
 * plugin is loaded it claims them instead, so this never runs alongside GoLand's own tag support.
 */
class GoLspStructTagAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!GoLspSupport.isGoFile(virtualFile)) return

        val keyRanges = tagKeyRanges(file)
        if (keyRanges.isEmpty()) return

        // A highlighting pass may hand over the whole file or only the elements under the range it
        // is refreshing, and an annotation has to stay inside the element it is attached to. Emitting
        // whichever keys this element covers works either way; a key seen twice paints the same.
        val elementRange = element.textRange ?: return
        for (range in keyRanges) {
            if (!elementRange.contains(range)) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(GoLspColors.STRUCT_TAG_KEY)
                .create()
        }
    }

    /** Scanning is per document version, not per element; the pass visits a lot of elements. */
    private fun tagKeyRanges(file: PsiFile): List<TextRange> =
        CachedValuesManager.getCachedValue(file, TAG_KEY_RANGES) {
            val text = file.viewProvider.document?.charsSequence ?: file.text
            CachedValueProvider.Result.create(GoStructTags.keyRanges(text), file)
        }

    private companion object {
        val TAG_KEY_RANGES: Key<CachedValue<List<TextRange>>> = Key.create("go.lsp.struct.tag.key.ranges")
    }
}
