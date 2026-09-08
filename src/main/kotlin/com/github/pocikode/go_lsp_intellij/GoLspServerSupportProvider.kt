package com.github.pocikode.go_lsp_intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import icons.GoLspIcons

/** Starts `gopls` through the IntelliJ LSP API when a Go source or module file is opened. */
class GoLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerStarter) {
        if (!GoLspSupport.isGoLspFile(file) || GoLspSupport.isNativeGoPluginLoaded()) return

        val executable = GoLspDiscovery.findExecutable()
        if (executable == null) {
            notifyMissingServer(project)
            return
        }

        serverStarter.ensureServerStarted(GoLspServerDescriptor(project, executable))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?): LspServerWidgetItem =
        LspServerWidgetItem(lspServer, currentFile, GoLspIcons.GO, GoLspConfigurable::class.java)

    private fun notifyMissingServer(project: Project) {
        if (project.getUserData(MISSING_SERVER_NOTIFIED) == true) return
        project.putUserData(MISSING_SERVER_NOTIFIED, true)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Go LSP")
            .createNotification(
                "gopls was not found",
                "Install gopls with <code>go install golang.org/x/tools/gopls@latest</code> " +
                    "or configure its path in Settings | Tools | Go LSP, then reopen the Go file.",
                NotificationType.WARNING,
            )
            .addAction(
                com.intellij.notification.NotificationAction.createSimple("Open settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GoLspConfigurable::class.java)
                },
            )
            .notify(project)
    }

    private companion object {
        val MISSING_SERVER_NOTIFIED: Key<Boolean> = Key.create("go.lsp.missing.server.notified")
    }
}
