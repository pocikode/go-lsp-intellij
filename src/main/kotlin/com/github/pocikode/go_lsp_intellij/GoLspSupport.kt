package com.github.pocikode.go_lsp_intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** Shared rules for deciding whether a file belongs to the Go language server. */
object GoLspSupport {
    private val NATIVE_GO_PLUGIN_ID: PluginId = PluginId.getId("org.jetbrains.plugins.go")

    fun isGoFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension == "go"

    /** Files for which gopls provides editor features rather than only workspace change tracking. */
    fun isGoLspFile(file: VirtualFile): Boolean = isGoFile(file) || isGoModuleLanguageFile(file)

    /** A Go file currently owned by the bundled TextMate language rather than the native Go plugin. */
    fun isGoTextMateFile(file: PsiFile): Boolean =
        !isNativeGoPluginLoaded() &&
            file.fileType.name == "textmate" &&
            file.virtualFile?.let(::isGoFile) == true

    /**
     * A Go test file, by the only rule the toolchain and GoLand both use: the name ends `_test.go`.
     * Note that `_test.go` itself is not a test file - Go requires a name before the suffix.
     */
    fun isGoTestFile(file: VirtualFile): Boolean =
        !file.isDirectory && file.name.length > TEST_SUFFIX.length && file.name.endsWith(TEST_SUFFIX)

    private const val TEST_SUFFIX = "_test.go"

    /** The Go module and workspace manifests, which GoLand shows with their own icon. */
    fun isGoModuleFile(file: VirtualFile): Boolean = !file.isDirectory && file.name in MODULE_FILE_NAMES

    fun isGoModuleLanguageFile(file: VirtualFile): Boolean =
        !file.isDirectory && file.name in MODULE_LANGUAGE_FILE_NAMES

    private val MODULE_FILE_NAMES = setOf("go.mod", "go.sum", "go.work", "go.work.sum")
    private val MODULE_LANGUAGE_FILE_NAMES = setOf("go.mod", "go.work")

    /**
     * True only when JetBrains' native Go plugin is actually loaded. A plugin that is installed but
     * could not load (for example because it needs the Ultimate module, which is disabled without a
     * license) must not suppress gopls, so `isPluginInstalled` is deliberately not used here.
     */
    fun isNativeGoPluginLoaded(): Boolean = PluginManagerCore.isLoaded(NATIVE_GO_PLUGIN_ID)
}
