package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams

/**
 * Small synchronous helpers on top of the running `gopls` server. The plugin handles Go navigation
 * itself (see [GoLspReferenceProvider] and [GoLspDeclarationProvider]) so that Cmd/Ctrl+hover,
 * Cmd/Ctrl+click, and Find Usages behave the same way from references and from declarations.
 */
object GoLspRequests {
    private const val REQUEST_TIMEOUT_MS = 5_000

    private data class DefinitionKey(val file: VirtualFile, val modificationStamp: Long, val offset: Int)

    /** Cmd/Ctrl+hover re-resolves on every mouse move; remember the last answer so gopls is asked once per spot. */
    @Volatile
    private var lastDefinition: Pair<DefinitionKey, List<LocationLink>>? = null

    fun runningServer(project: Project, file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(GoLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(file) }

    /** Definition links for the symbol at [offset]; plain locations are normalized to links without an origin range. */
    fun definitions(server: LspServer, file: VirtualFile, document: Document, offset: Int): List<LocationLink> {
        val key = DefinitionKey(file, document.modificationStamp, offset)
        lastDefinition?.let { (cachedKey, links) -> if (cachedKey == key) return links }

        val params = DefinitionParams(server.getDocumentIdentifier(file), position(document, offset))
        val response = server.sendRequestSync(REQUEST_TIMEOUT_MS) { it.textDocumentService.definition(params) }
        val links: List<LocationLink> = when {
            response == null -> emptyList()
            response.isLeft -> response.left.orEmpty().map { LocationLink(it.uri, it.range, it.range) }
            else -> response.right.orEmpty()
        }
        lastDefinition = key to links
        return links
    }

    /** True when [link] points at the identifier under [offset] in [file] itself, i.e. the caret is on a declaration. */
    fun isSelfDefinition(server: LspServer, file: VirtualFile, document: Document, offset: Int, link: LocationLink): Boolean {
        if (link.targetUri != server.descriptor.getFileUri(file)) return false
        val range = textRange(document, link.targetSelectionRange ?: link.targetRange) ?: return false
        return range.containsOffset(offset)
    }

    fun references(server: LspServer, file: VirtualFile, position: Position): List<Location> {
        val params = ReferenceParams(server.getDocumentIdentifier(file), position, ReferenceContext(false))
        return server.sendRequestSync(REQUEST_TIMEOUT_MS) { it.textDocumentService.references(params) }.orEmpty()
    }

    /**
     * The Go identifier around [offset], or null when the caret is not on one. TextMate leaves can
     * span several tokens and gopls does not always report an origin range, so this is what bounds
     * the link styling on Cmd/Ctrl+hover.
     */
    fun identifierRangeAt(document: Document, offset: Int): TextRange? {
        val text = document.charsSequence
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && isIdentifierChar(text[start - 1])) start--
        while (end < text.length && isIdentifierChar(text[end])) end++
        if (start == end) return null
        // Identifiers cannot start with a digit; the caret is inside a number literal.
        if (text[start].isDigit()) return null
        return TextRange(start, end)
    }

    private fun isIdentifierChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    fun position(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset)
        return Position(line, offset - document.getLineStartOffset(line))
    }

    fun textRange(document: Document, range: Range): TextRange? {
        val start = offset(document, range.start) ?: return null
        val end = offset(document, range.end) ?: return null
        return if (start <= end) TextRange(start, end) else null
    }

    fun offset(document: Document, position: Position): Int? {
        if (position.line < 0 || position.line >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(position.line)
        val lineEnd = document.getLineEndOffset(position.line)
        return (lineStart + position.character).coerceIn(lineStart, lineEnd)
    }
}
