package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiFile
import javax.swing.Icon

/** GoLand's tag-generation intention, implemented from text because TextMate has no field PSI. */
class GoAddKeyToTagsIntention : IntentionAction, PriorityAction, Iconable {
    override fun getText(): String = FAMILY_NAME

    override fun getFamilyName(): String = FAMILY_NAME

    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Tag

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        GoLspSupport.isGoTextMateFile(file) && GoStructFields.enclosing(editor.document.charsSequence, editor.caretModel.offset)
            ?.fields?.isNotEmpty() == true

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        addKeyToAllFields(project, editor)
    }

    override fun startInWriteAction(): Boolean = false

    companion object {
        internal const val FAMILY_NAME = "Add key to tags"

        internal fun addKeyToAllFields(project: Project, editor: Editor) {
            val key = Messages.showInputDialog(
                project,
                "Tag key to add to every eligible field:",
                FAMILY_NAME,
                AllIcons.Nodes.Tag,
                "json",
                object : InputValidator {
                    override fun checkInput(inputString: String?): Boolean =
                        inputString != null && GoStructTagEdits.isValidKey(inputString.trim())

                    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                },
            )?.trim() ?: return

            val document = editor.document
            val edits = GoStructTagEdits.addKey(document.charsSequence, editor.caretModel.offset, key)
            if (edits.isEmpty()) return
            WriteCommandAction.runWriteCommandAction(project, FAMILY_NAME, null, {
                for (edit in edits.sortedByDescending { it.offset }) document.insertString(edit.offset, edit.text)
            })
        }
    }
}
