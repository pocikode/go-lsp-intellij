package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager

class RestartGoLspAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        val manager = LanguageServerManager.getInstance(project)
        manager.stop("go-lsp")
        manager.start("go-lsp")
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
