package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager

class RestartGoLspAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(GoLspServerSupportProvider::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
