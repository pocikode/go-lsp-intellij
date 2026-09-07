package dev.go_lsp.intellij

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** Runs the normal IntelliJ reformat pipeline so LSP4IJ can delegate it to gopls. */
class GoFormatOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
    override val presentableName: String = "Go LSP formatting"

    override fun isEnabledForProject(project: Project): Boolean =
        GoFormatOnSaveState.getInstance(project).enabled

    override suspend fun updateDocument(project: Project, document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.extension != "go") return

        val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
            PsiDocumentManager.getInstance(project).getPsiFile(document)
        }
        if (psiFile == null || !psiFile.isValid) return

        ReformatCodeProcessor(psiFile, false).run()
    }
}

class GoFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<GoFormatOnSaveInfo> =
        listOf(GoFormatOnSaveInfo(context))

    override fun getSearchableOptions(): Collection<String> =
        listOf("Reformat Go files with gopls")
}

class GoFormatOnSaveInfo(context: ActionOnSaveContext) : ActionOnSaveInfo(context) {
    private var enabled = GoFormatOnSaveState.getInstance(context.project).enabled

    override fun apply() {
        GoFormatOnSaveState.getInstance(project).enabled = enabled
    }

    override fun isModified(): Boolean =
        enabled != GoFormatOnSaveState.getInstance(project).enabled

    override fun getActionOnSaveName(): String = "Reformat Go files with gopls"

    override fun isActionOnSaveEnabled(): Boolean = enabled

    override fun setActionOnSaveEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
