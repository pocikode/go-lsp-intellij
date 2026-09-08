package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Makes the `foo_test.go:12` prefix `go test` puts in front of a failure a link to that line.
 *
 * `go test` reports the file by its base name, relative to nothing, so the name is resolved through
 * the filename index rather than the filesystem. A panic prints an absolute path instead, which is
 * matched by the same pattern and taken as-is.
 */
class GoTestOutputFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = LOCATION.find(line) ?: return null
        val lineNumber = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val file = resolve(match.groupValues[1]) ?: return null

        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + match.range.first,
            lineStart + match.range.last + 1,
            OpenFileHyperlinkInfo(project, file, lineNumber - 1),
        )
    }

    private fun resolve(path: String): VirtualFile? {
        if (path.startsWith("/") || path.contains(":\\")) {
            return LocalFileSystem.getInstance().findFileByPath(path)
        }
        if (path.contains('/') || DumbService.isDumb(project)) return null
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            if (project.isDisposed) return@compute null
            try {
                FilenameIndex.getVirtualFilesByName(path, GlobalSearchScope.allScope(project)).firstOrNull()
            } catch (exception: IndexNotReadyException) {
                // Indexing can start between the dumb-mode check and the query; a console line
                // without a link is the right outcome, an exception out of a filter is not.
                null
            }
        }
    }

    private companion object {
        val LOCATION = Regex("""([^\s:()\[\]]+\.go):(\d+)""")
    }
}

/**
 * Adds [GoTestOutputFilter] to every console in the project.
 *
 * There is no narrower hook - a filter is attached per project, not per run configuration - but the
 * pattern only ever links a `.go` file that the project actually contains, so it stays inert
 * everywhere else. It stands down when JetBrains' Go plugin is loaded, which brings its own.
 */
class GoTestConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        if (GoLspSupport.isNativeGoPluginLoaded()) Filter.EMPTY_ARRAY else arrayOf(GoTestOutputFilter(project))
}
