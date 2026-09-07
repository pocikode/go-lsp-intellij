package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

/**
 * The `gopls` answers behind the code vision above a Go declaration - what GoLand shows there.
 *
 * Everything is computed off the highlighting pass and cached: a `textDocument/references` call per
 * declaration is far too slow to answer [GoLspUsagesCodeVisionProvider] inline, so the providers
 * only ever read this cache, and a background refresh restarts the daemon once the answers land.
 * Until then a file shows the previous answers, or nothing at all when it has just been opened.
 *
 * Usage counts are keyed by declaration name rather than position, so they survive an edit
 * elsewhere in the file and only the ranges are refreshed while typing.
 */
@Service(Service.Level.PROJECT)
class GoLspCodeVisionService(private val project: Project, private val scope: CoroutineScope) {

    /** A top-level Go declaration, as `gopls` reported it. */
    data class Declaration(
        val name: String,
        val kind: SymbolKind,
        /** The whole declaration, closing brace included; what the author hint is blamed over. */
        val range: Range,
        /** The declared identifier; where the code vision is anchored and usages are searched from. */
        val selectionRange: Range,
    ) {
        /** `(*Repo).Get` and `(Repo).Ping` are how gopls names a method; everything else is a plain name. */
        val methodOf: String?
            get() = METHOD_NAME.matchEntire(name)?.groupValues?.get(2)

        val simpleName: String
            get() = METHOD_NAME.matchEntire(name)?.groupValues?.get(3) ?: name

        /** True when the receiver is declared as a pointer, which new methods should match. */
        val hasPointerReceiver: Boolean
            get() = METHOD_NAME.matchEntire(name)?.groupValues?.get(1) == "*"

        private companion object {
            val METHOD_NAME = Regex("""\((\*?)(\w+)\)\.(\w+)""")
        }
    }

    private class Snapshot(val stamp: Long, val declarations: List<Declaration>)

    /** A `git blame` of the file as it was on disk at [stamp]. */
    private class Blame(val stamp: Long, val annotation: FileAnnotation)

    private val snapshots = ConcurrentHashMap<VirtualFile, Snapshot>()
    private val usages = ConcurrentHashMap<VirtualFile, Map<String, Int>>()
    private val authors = ConcurrentHashMap<VirtualFile, Map<String, GoLspCodeAuthors.Author>>()
    private val blames = ConcurrentHashMap<VirtualFile, Blame>()
    private val requestedStamps = ConcurrentHashMap<VirtualFile, Long>()
    private val jobs = ConcurrentHashMap<VirtualFile, Job>()

    /**
     * The declarations known for [file], scheduling a refresh when they are missing or stale.
     *
     * The list is the same instance until the next refresh completes.
     */
    fun declarations(file: VirtualFile, document: Document): List<Declaration> {
        val snapshot = snapshots[file]
        if (snapshot == null || snapshot.stamp != document.modificationStamp) schedule(file, document)
        return snapshot?.declarations ?: emptyList()
    }

    /** How many places use [declaration], or null while `gopls` has not answered for it yet. */
    fun usageCount(file: VirtualFile, declaration: Declaration): Int? = usages[file]?.get(declaration.name)

    /** Who last touched [declaration], or null while the blame has not been read yet. */
    internal fun author(file: VirtualFile, declaration: Declaration): GoLspCodeAuthors.Author? =
        authors[file]?.get(declaration.name)

    private fun schedule(file: VirtualFile, document: Document) {
        val stamp = document.modificationStamp
        if (requestedStamps.put(file, stamp) == stamp) return
        val previous = jobs.put(file, scope.launch {
            // Typing invalidates the stamp on every keystroke; only ask gopls once the file settles.
            delay(REFRESH_DELAY_MS)
            refresh(file, stamp)
        })
        previous?.cancel()
    }

    private suspend fun refresh(file: VirtualFile, stamp: Long) {
        // A file usually opens before gopls has finished starting, and opening it is not an edit, so
        // nothing would ever ask again: forget the stamp so the next highlighting pass retries.
        val server = GoLspRequests.runningServer(project, file) ?: run {
            requestedStamps.remove(file)
            return
        }
        val declarations = GoLspSymbolRequests.documentSymbols(server, file)
            .filter { it.kind in VISIBLE_KINDS }
            .map { Declaration(it.name, it.kind, it.range, it.selectionRange) }
        // A newer edit already scheduled its own pass; let that one publish.
        if (requestedStamps[file] != stamp) return
        if (declarations.isEmpty()) {
            // Either the file really declares nothing, or gopls is still loading the workspace.
            // Publishing nothing would settle the stamp and stop anyone asking again, so leave the
            // snapshot as it was and let the next highlighting pass retry.
            requestedStamps.remove(file)
            return
        }
        snapshots[file] = Snapshot(stamp, declarations)
        recomputeCodeVision(file)

        // References are workspace-wide in Go, so they are the expensive half; keep a lid on both
        // how many run at once and how many a single generated-looking file may ask for.
        val counted = declarations.take(MAX_COUNTED_DECLARATIONS)
        val limit = Semaphore(MAX_PARALLEL_REQUESTS)
        val counts = coroutineScope {
            counted.map { declaration ->
                async {
                    limit.withPermit {
                        declaration.name to GoLspSymbolRequests
                            .references(server, file, declaration.selectionRange.start).size
                    }
                }
            }.awaitAll()
        }.toMap()

        // A newer edit already superseded this pass; its own refresh will publish fresher counts.
        if (requestedStamps[file] != stamp) return
        usages[file] = counts
        refreshAuthors(file, declarations)
        recomputeCodeVision(file)
        forgetStaleFiles(file)
    }

    /**
     * Reads `git blame` for the file and attributes each declaration to an author.
     *
     * The blame is indexed by the lines of the file on disk, so a document with unsaved edits keeps
     * the authors from the last saved state instead: they are keyed by declaration name, so they
     * stay attached to the right declaration however far the lines have moved, and are re-read on
     * the first refresh after a save.
     */
    private suspend fun refreshAuthors(file: VirtualFile, declarations: List<Declaration>) {
        val document = readAction { FileDocumentManager.getInstance().getDocument(file) } ?: return
        if (readAction { FileDocumentManager.getInstance().isDocumentUnsaved(document) }) return

        val cached = blames[file]
        val annotation = if (cached != null && cached.stamp == file.modificationStamp) {
            cached.annotation
        } else {
            val fresh = GoLspCodeAuthors.annotate(project, file) ?: return
            blames.put(file, Blame(file.modificationStamp, fresh))?.annotation?.dispose()
            fresh
        }

        authors[file] = readAction {
            declarations.mapNotNull { declaration ->
                val range = GoLspRequests.textRange(document, declaration.range) ?: return@mapNotNull null
                val startLine = document.getLineNumber(range.startOffset)
                val endLine = document.getLineNumber(range.endOffset)
                declaration.name to GoLspCodeAuthors.authorOf(document, annotation, startLine, endLine)
            }.toMap()
        }
    }

    /**
     * Puts the fresh answers on screen. A `gopls` answer arriving is not an edit, and neither half
     * of the code vision machinery notices it on its own.
     *
     * Every provider here is daemon-bound, and the highlighting pass they run in skips a file
     * whose PSI has not changed since it last ran, so the stamp it compares against has to be
     * dropped before the daemon is restarted.
     */
    private suspend fun recomputeCodeVision(file: VirtualFile) {
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || !file.isValid) return@withContext
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@withContext
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@withContext
            EditorFactory.getInstance().editors(document, project).forEach { editor ->
                ModificationStampUtil.clearModificationStamp(editor)
            }
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
        }
    }

    /**
     * Keeps the caches from growing for the lifetime of the project. Deleted files go first; past
     * [MAX_CACHED_FILES] everything but [keep] goes, and the files still on screen simply ask again.
     */
    private fun forgetStaleFiles(keep: VirtualFile) {
        for (file in snapshots.keys) {
            if (!file.isValid) forget(file)
        }
        if (snapshots.size <= MAX_CACHED_FILES) return
        for (file in snapshots.keys) {
            if (file != keep) forget(file)
        }
    }

    private fun forget(file: VirtualFile) {
        snapshots.remove(file)
        usages.remove(file)
        authors.remove(file)
        blames.remove(file)?.annotation?.dispose()
        requestedStamps.remove(file)
        jobs.remove(file)
    }

    companion object {
        fun getInstance(project: Project): GoLspCodeVisionService = project.service()

        /**
         * The declarations GoLand puts a usage count on: everything a Go file declares at the top
         * level, plus the methods it declares on those types. Struct fields and interface methods
         * are children of their type and are deliberately left out, as they are in GoLand.
         */
        private val VISIBLE_KINDS = setOf(
            SymbolKind.Function,
            SymbolKind.Method,
            SymbolKind.Struct,
            SymbolKind.Interface,
            SymbolKind.Class,
            SymbolKind.Enum,
            SymbolKind.Constant,
            SymbolKind.Variable,
        )

        private const val REFRESH_DELAY_MS = 300L
        private const val MAX_PARALLEL_REQUESTS = 4
        private const val MAX_COUNTED_DECLARATIONS = 200
        private const val MAX_CACHED_FILES = 100
    }
}
