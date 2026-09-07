package com.github.pocikode.go_lsp_intellij

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import icons.GoLspIcons
import javax.swing.Icon

/**
 * Gives Go files and the module files a Go icon in the project view, editor tabs and file lists.
 *
 * Without this they fall back to the icon of whichever file type claims them - the bundled TextMate
 * grammar, or plain text - which is what makes the project tree look unlike GoLand's. This provider
 * is registered outside `go-lsp.xml` because it does not depend on the LSP module.
 */
class GoLspFileIconProvider : FileIconProvider {

    /** JetBrains' Go plugin registers its own file type icons; leave the tree to them when it is loaded. */
    private val nativeGoPluginLoaded: Boolean by lazy { GoLspSupport.isNativeGoPluginLoaded() }

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (nativeGoPluginLoaded) return null
        return when {
            GoLspSupport.isGoFile(file) -> GoLspIcons.GO
            GoLspSupport.isGoModuleFile(file) -> GoLspIcons.GO_MODULE
            else -> null
        }
    }
}
