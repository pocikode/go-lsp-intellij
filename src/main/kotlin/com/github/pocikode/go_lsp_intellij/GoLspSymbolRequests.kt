package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbolParams

/**
 * One `textDocument/documentSymbol` node, normalized out of the two shapes lsp4j can return.
 *
 * [detail] carries the Go signature `gopls` prints for the symbol - `func(ctx context.Context) error`
 * for a method, `struct{...}` for a struct, the type name for an embedded interface - and is what
 * "Implement interface" turns into a method stub.
 */
data class GoLspSymbolNode(
    val name: String,
    val kind: SymbolKind,
    val detail: String?,
    val range: Range,
    val selectionRange: Range,
    val children: List<GoLspSymbolNode>,
)

/** A named interface `gopls` reported for a `workspace/symbol` query. */
data class GoLspInterface(val name: String, val packagePath: String, val location: Location) {
    /** How GoLand labels an interface in the "Implement Methods" chooser. */
    val qualifiedName: String
        get() = if (packagePath.isEmpty()) name else "${packagePath.substringAfterLast('/')}.$name"
}

/**
 * Suspending `gopls` requests behind the Go code vision.
 *
 * These run off the UI thread through [LspServer.sendRequest] rather than the blocking helpers in
 * [GoLspRequests]: a file's worth of `textDocument/references` calls is far too slow to answer a
 * highlighting pass inline, so [GoLspCodeVisionService] issues them in the background and caches
 * the counts.
 */
object GoLspSymbolRequests {
    /** The declarations of [file], as a tree. Empty when `gopls` has nothing to say about the file. */
    suspend fun documentSymbols(server: LspServer, file: VirtualFile): List<GoLspSymbolNode> {
        val params = DocumentSymbolParams(server.getDocumentIdentifier(file))
        val response = server.sendRequest { it.textDocumentService.documentSymbol(params) } ?: return emptyList()
        return response.mapNotNull { either ->
            when {
                either == null -> null
                either.isRight -> either.right?.let(::toNode)
                else -> either.left?.let(::toNode)
            }
        }
    }

    /** References to the symbol at [position], excluding its declaration - the count GoLand shows. */
    suspend fun references(server: LspServer, file: VirtualFile, position: Position): List<Location> {
        val params = ReferenceParams(server.getDocumentIdentifier(file), position, ReferenceContext(false))
        return server.sendRequest { it.textDocumentService.references(params) }.orEmpty().filterNotNull()
    }

    /** Where the symbol at [position] is declared; used to follow an embedded interface to its own file. */
    suspend fun definitions(server: LspServer, file: VirtualFile, position: Position): List<Location> {
        val params = DefinitionParams(server.getDocumentIdentifier(file), position)
        val response = server.sendRequest { it.textDocumentService.definition(params) } ?: return emptyList()
        return when {
            response.isLeft -> response.left.orEmpty().filterNotNull()
            else -> response.right.orEmpty().mapNotNull { link ->
                link?.let { Location(it.targetUri, it.targetSelectionRange ?: it.targetRange) }
            }
        }
    }

    /**
     * Named interfaces matching [query], fuzzily, across the module and everything it imports.
     *
     * `gopls` answers an empty query with nothing, and reports an embedded interface as a symbol of
     * its own named `Outer.Embedded`; neither is something to offer as an implementation target.
     */
    suspend fun interfaces(server: LspServer, query: String): List<GoLspInterface> {
        if (query.isBlank()) return emptyList()
        val response = server.sendRequest { it.workspaceService.symbol(WorkspaceSymbolParams(query)) }
            ?: return emptyList()
        val candidates: List<RawSymbol> = when {
            response.isLeft -> response.left.orEmpty().filterNotNull()
                .map { RawSymbol(it.name, it.kind, it.containerName, it.location) }
            else -> response.right.orEmpty().filterNotNull()
                .map { RawSymbol(it.name, it.kind, it.containerName, it.location?.left) }
        }

        return candidates.mapNotNull { candidate ->
            if (candidate.kind != SymbolKind.Interface || candidate.location == null) return@mapNotNull null
            val packagePath = candidate.containerName.orEmpty()
            // gopls qualifies the name with the package path whenever the query looks like one.
            val simpleName = candidate.name.removePrefix("$packagePath.")
            // `Outer.Embedded` names an interface embedded in another one, not a declaration.
            if (simpleName.isEmpty() || simpleName.contains('.')) return@mapNotNull null
            GoLspInterface(simpleName, packagePath, candidate.location)
        }.distinctBy { it.packagePath to it.name }
    }

    private data class RawSymbol(
        val name: String,
        val kind: SymbolKind?,
        val containerName: String?,
        val location: Location?,
    )

    private fun toNode(symbol: DocumentSymbol): GoLspSymbolNode = GoLspSymbolNode(
        name = symbol.name,
        kind = symbol.kind,
        detail = symbol.detail,
        range = symbol.range,
        selectionRange = symbol.selectionRange ?: symbol.range,
        children = symbol.children.orEmpty().filterNotNull().map(::toNode),
    )

    /**
     * The flat shape, which `gopls` falls back to for a client that does not advertise
     * `hierarchicalDocumentSymbolSupport`. [GoLspServerDescriptor] does advertise it, so this only
     * guards against a server that ignores the capability - such a response carries no signatures
     * and no children, and "Implement interface" simply finds nothing to offer.
     */
    private fun toNode(symbol: SymbolInformation): GoLspSymbolNode = GoLspSymbolNode(
        name = symbol.name,
        kind = symbol.kind,
        detail = null,
        range = symbol.location.range,
        selectionRange = symbol.location.range,
        children = emptyList(),
    )
}
