package com.github.pocikode.go_lsp_intellij

import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Formats the current in-memory Go document with the local Go toolchain before saving. */
class GoFormatOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
    override val presentableName: String = "Go formatting"

    override fun isEnabledForProject(project: Project): Boolean =
        GoFormatOnSaveState.getInstance(project).enabled

    override suspend fun updateDocument(project: Project, document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.extension != "go") return

        val originalText = document.text
        val formattedText = GoLspFormatting.format(project, file, originalText) ?: return
        if (formattedText == originalText) return

        withContext(Dispatchers.EDT) {
            WriteAction.run<RuntimeException> {
                document.setText(formattedText)
            }
        }
    }
}

class GoFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<GoFormatOnSaveInfo> =
        listOf(GoFormatOnSaveInfo(context))

    override fun getSearchableOptions(): Collection<String> =
        listOf("Reformat Go files with gofmt")
}

class GoFormatOnSaveInfo(context: ActionOnSaveContext) : ActionOnSaveInfo(context) {
    private var enabled = GoFormatOnSaveState.getInstance(context.project).enabled

    override fun apply() {
        GoFormatOnSaveState.getInstance(project).enabled = enabled
    }

    override fun isModified(): Boolean =
        enabled != GoFormatOnSaveState.getInstance(project).enabled

    override fun getActionOnSaveName(): String = "Reformat Go files with gofmt"

    override fun isActionOnSaveEnabled(): Boolean = enabled

    override fun setActionOnSaveEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
