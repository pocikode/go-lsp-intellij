package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vfs.VirtualFile

/**
 * Who last touched a declaration, the way the platform's own "Code author" vision reports it.
 *
 * That provider cannot answer for Go here. It walks the PSI looking for elements a
 * `VcsCodeVisionLanguageContext` recognises as declarations, and the TextMate grammar that claims
 * `.go` files parses the whole file into a *single* leaf - there is no per-declaration element to
 * hand it, whatever the context says. So the declarations come from `gopls` and the blame is read
 * here, formatted the way the platform formats it: the author owning the most lines of the
 * declaration, `+N` for the others, and a trailing `*` when part of the range is uncommitted.
 */
internal object GoLspCodeAuthors {
    /** The author summary for one declaration; [mainAuthor] is null for code that was never committed. */
    data class Author(val mainAuthor: String?, val otherAuthorsCount: Int, val isModified: Boolean)

    /** Git blame for [file], or null when it is not under Git or the blame failed. */
    fun annotate(project: Project, file: VirtualFile): FileAnnotation? {
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return null
        // The platform's code author vision is Git-only, and so is this.
        if (vcs.name != "Git") return null
        val provider = vcs.annotationProvider ?: return null
        return try {
            provider.annotate(file)
        } catch (exception: Exception) {
            LOG.debug("Unable to annotate ${file.name}", exception)
            null
        }
    }

    /**
     * The author of lines [startLine]..[endLine].
     *
     * The blame is indexed by the lines of the file **on disk**, so this is only asked for a saved
     * document - see [GoLspCodeVisionService], which keeps the previous answer while a file has
     * unsaved edits rather than blaming lines that have since moved.
     */
    fun authorOf(document: Document, annotation: FileAnnotation, startLine: Int, endLine: Int): Author {
        val aspect = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.AUTHOR } ?: return NEW_CODE

        val authors = mutableListOf<String>()
        var uncommitted = false
        for (line in startLine..endLine) {
            if (line < 0 || line >= document.lineCount || line >= annotation.lineCount) continue
            val lineText = document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(line),
                    document.getLineEndOffset(line),
                ),
            )
            // A blank line belongs to whoever wrote the code around it, not to a commit of its own.
            if (lineText.isBlank()) continue
            val author = aspect.getValue(line)?.trim()
            if (author.isNullOrEmpty() || author == NOT_COMMITTED) {
                uncommitted = true
                continue
            }
            authors.add(author)
        }
        if (authors.isEmpty()) return NEW_CODE

        val counts = authors.groupingBy { it }.eachCount()
        return Author(
            mainAuthor = counts.maxByOrNull { it.value }?.key,
            otherAuthorsCount = counts.size - 1,
            isModified = uncommitted,
        )
    }

    /**
     * The label the platform uses: `Name`, `Name *`, `Name +2`, `Name +2 *`, or `new *`.
     *
     * The name is always the full one. The platform abbreviates it according to the annotation
     * gutter's "short name" setting, but that setting's class lives in a platform module this
     * plugin does not compile against, and its default is the full name anyway.
     */
    fun textOf(author: Author): String {
        val name = author.mainAuthor?.let(::displayName) ?: return NEW_CODE_LABEL
        return when {
            author.otherAuthorsCount > 0 && author.isModified -> "$name +${author.otherAuthorsCount} *"
            author.otherAuthorsCount > 0 -> "$name +${author.otherAuthorsCount}"
            author.isModified -> "$name *"
            else -> name
        }
    }

    /**
     * `Agus Supriyatna <dev@example.com>` is how Git reports an author; GoLand shows the name only.
     * An author with no name in front of the address keeps the address.
     */
    private fun displayName(author: String): String {
        val address = author.indexOf('<')
        if (address <= 0) return author.trim()
        return author.substring(0, address).trim().ifEmpty { author.trim() }
    }

    private val NEW_CODE = Author(mainAuthor = null, otherAuthorsCount = 0, isModified = true)
    private const val NEW_CODE_LABEL = "new *"
    private const val NOT_COMMITTED = "Not Committed Yet"
    private val LOG: Logger = Logger.getInstance(GoLspCodeAuthors::class.java)
}
