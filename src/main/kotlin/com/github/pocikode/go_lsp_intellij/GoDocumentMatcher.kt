package com.github.pocikode.go_lsp_intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.AbstractDocumentMatcher

/** Prevents LSP/native Go navigation providers from being active together. */
class GoDocumentMatcher : AbstractDocumentMatcher() {
    override fun match(file: VirtualFile, project: Project): Boolean =
        !PluginManagerCore.isPluginInstalled(NATIVE_GO_PLUGIN_ID)

    private companion object {
        val NATIVE_GO_PLUGIN_ID: PluginId = PluginId.getId("org.jetbrains.plugins.go")
    }
}
