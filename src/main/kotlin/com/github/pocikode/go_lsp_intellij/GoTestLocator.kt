package com.github.pocikode.go_lsp_intellij

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Navigates from a node in the test tree back to the Go source it came from.
 *
 * The tree only knows what `go test` reported: a package's import path and a test's name. Turning
 * the import path back into a directory is a string operation once `go.mod` has been read - the
 * module path is the prefix every package under it shares - which keeps navigation working without
 * `gopls`, and without asking the toolchain a second time.
 *
 * A statically named subtest resolves to its literal or table field. Dynamic names have no source
 * declaration to match and fall back to their parent test function.
 */
class GoTestLocator(private val workingDirectory: String) : SMTestLocator {

    private val directories = ConcurrentHashMap<String, String>()

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != GoTestEventTranslator.PROTOCOL) return emptyList()
        val importPath = path.substringBefore(GoTestEventTranslator.SEPARATOR)
        val test = path.substringAfter(GoTestEventTranslator.SEPARATOR, "")

        return ReadAction.compute<List<Location<*>>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            val directory = packageDirectory(importPath) ?: return@compute emptyList()
            if (test.isEmpty()) {
                val psiDirectory = PsiManager.getInstance(project).findDirectory(directory)
                    ?: return@compute emptyList()
                listOf(PsiLocation(project, psiDirectory))
            } else listOfNotNull(declaration(project, directory, test))
        }
    }

    private fun declaration(project: Project, directory: VirtualFile, test: String): Location<*>? {
        val name = test.substringBefore('/')
        val subtest = test.substringAfter('/', "")
        for (file in directory.children) {
            if (!GoLspSupport.isGoTestFile(file)) continue
            // The document rather than the bytes on disk, so an edited file still lands on the
            // right line; go test ran against the saved text, but the user is looking at this one.
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val declaration = GoTestFunctions.find(document.text).firstOrNull { it.name == name } ?: continue
            val offset = declaration.subtests.firstOrNull { it.name == subtest }?.nameOffset
                ?: declaration.nameOffset
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
            return GoTestLocation(project, psiFile, document.getLineNumber(offset))
        }
        return null
    }

    /**
     * The directory holding [importPath], found by taking the module path off the front of it.
     *
     * The run's own directory is the fallback: it is where `go test` was pointed, so for the common
     * single-package run it is already the right answer.
     */
    private fun packageDirectory(importPath: String): VirtualFile? {
        directories[importPath]?.let { cached ->
            LocalFileSystem.getInstance().findFileByPath(cached)?.takeIf { it.isValid }?.let { return it }
        }
        val start = LocalFileSystem.getInstance().findFileByPath(workingDirectory) ?: return null

        var candidate: VirtualFile? = start
        while (candidate != null) {
            val modulePath = candidate.findChild("go.mod")?.let(::modulePath)
            if (modulePath != null) {
                val resolved = when {
                    importPath == modulePath -> candidate
                    importPath.startsWith("$modulePath/") ->
                        candidate.findFileByRelativePath(importPath.removePrefix("$modulePath/"))
                    else -> null
                }
                if (resolved != null && resolved.isDirectory) {
                    directories[importPath] = resolved.path
                    return resolved
                }
            }
            candidate = candidate.parent
        }
        return start
    }

    /**
     * A place in a Go file, by line.
     *
     * [PsiLocation] would navigate to the element it is given, and the bundled TextMate grammar
     * gives a `.go` file exactly one element covering all of it, so every test would open at the
     * top of its file. The line is known here, so the descriptor is built from it directly.
     */
    private class GoTestLocation(
        private val project: Project,
        private val file: PsiFile,
        private val line: Int,
    ) : Location<PsiElement>() {

        override fun getPsiElement(): PsiElement = file

        override fun getProject(): Project = project

        override fun getModule(): Module? = ModuleUtilCore.findModuleForPsiElement(file)

        override fun <T : PsiElement> getAncestors(ancestorClass: Class<T>, strict: Boolean): Iterator<Location<T>> =
            emptyList<Location<T>>().iterator()

        override fun getOpenFileDescriptor(): OpenFileDescriptor? =
            file.virtualFile?.let { OpenFileDescriptor(project, it, line, 0) }

        override fun getNavigatable(): Navigatable = openFileDescriptor ?: file
    }

    companion object {
        private val MODULE = Regex("""module\s+(\S+)""")

        /** Maps a source directory to the package path emitted by `go test -json`. */
        fun importPath(directory: VirtualFile): String? {
            var candidate: VirtualFile? = directory
            while (candidate != null) {
                val module = candidate.findChild("go.mod")?.let(::modulePath)
                if (module != null) {
                    val relative = VfsUtilCore.getRelativePath(directory, candidate, '/') ?: return null
                    return if (relative.isEmpty()) module else "$module/$relative"
                }
                candidate = candidate.parent
            }
            return null
        }

        private fun modulePath(goMod: VirtualFile): String? =
            FileDocumentManager.getInstance().getDocument(goMod)?.text?.lineSequence()
                ?.firstNotNullOfOrNull { MODULE.matchEntire(it.trim())?.groupValues?.get(1) }
    }
}
