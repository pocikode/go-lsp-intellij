package com.github.pocikode.go_lsp_intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.VirtualFile

/** Shared rules for deciding whether a file belongs to the Go language server. */
object GoLspSupport {
    private val NATIVE_GO_PLUGIN_ID: PluginId = PluginId.getId("org.jetbrains.plugins.go")

    fun isGoFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension == "go"

    /**
     * True only when JetBrains' native Go plugin is actually loaded. A plugin that is installed but
     * could not load (for example because it needs the Ultimate module, which is disabled without a
     * license) must not suppress gopls, so `isPluginInstalled` is deliberately not used here.
     */
    fun isNativeGoPluginLoaded(): Boolean = PluginManagerCore.isLoaded(NATIVE_GO_PLUGIN_ID)
}
