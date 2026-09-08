package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Exposes gopls's lazy fillStruct rewrite when the generic LSP intention list omits it. */
class GoFillAllFieldsIntention : IntentionAction, PriorityAction {
    @Volatile
    private var cached: CachedAction? = null

    override fun getText(): String = FAMILY_NAME

    override fun getFamilyName(): String = FAMILY_NAME

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        action(project, editor, file) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        action(project, editor, file)?.delegate?.invoke(project, editor, file)
    }

    override fun startInWriteAction(): Boolean = false

    @Synchronized
    private fun action(project: Project, editor: Editor, file: PsiFile): CachedAction? {
        val virtualFile = file.virtualFile?.takeIf(::isSupported) ?: return null
        val document = editor.document
        val selection = editor.selectionModel
        val range = requestRange(
            editor.caretModel.offset,
            selection.takeIf { it.hasSelection() }?.selectionStart,
            selection.takeIf { it.hasSelection() }?.selectionEnd,
            document.textLength,
            document::getLineNumber,
            document::getLineStartOffset,
            document::getLineEndOffset,
        )
        val key = CacheKey(virtualFile, document.modificationStamp, range.startOffset, range.endOffset)
        cached?.takeIf { it.key == key }?.let { return it }

        val server = GoLspRequests.runningServer(project, virtualFile) ?: return null
        val params = CodeActionParams(
            server.getDocumentIdentifier(virtualFile),
            Range(position(document, range.startOffset), position(document, range.endOffset)),
            CodeActionContext(emptyList(), listOf(FILL_STRUCT_KIND)).apply {
                triggerKind = CodeActionTriggerKind.Invoked
            },
        )
        val response = server.sendRequestSync(REQUEST_TIMEOUT_MS) { it.textDocumentService.codeAction(params) }.orEmpty()
        val codeAction = response.firstNotNullOfOrNull { result ->
            result?.takeIf { it.isRight }?.right?.takeIf {
                it.disabled == null && it.kind == FILL_STRUCT_KIND
            }
        } ?: return null

        // fillStruct is lazy: this resolves its edit and prepares the documents the platform applier needs.
        val delegate = LspIntentionAction(server as LspClient, codeAction)
        if (!delegate.isAvailable()) return null
        return CachedAction(key, delegate).also { cached = it }
    }

    private fun position(document: com.intellij.openapi.editor.Document, offset: Int): Position =
        GoLspRequests.position(document, offset.coerceIn(0, document.textLength))

    private fun isSupported(file: VirtualFile): Boolean =
        GoLspSupport.isGoFile(file) && !GoLspSupport.isNativeGoPluginLoaded()

    private data class CacheKey(
        val file: VirtualFile,
        val modificationStamp: Long,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class CachedAction(val key: CacheKey, val delegate: LspIntentionAction)

    private companion object {
        const val FAMILY_NAME = "Fill all fields"
        const val FILL_STRUCT_KIND = "refactor.rewrite.fillStruct"
        const val REQUEST_TIMEOUT_MS = 5_000
    }
}

internal data class GoFillAllFieldsRange(val startOffset: Int, val endOffset: Int)

internal fun requestRange(
    caretOffset: Int,
    selectionStart: Int?,
    selectionEnd: Int?,
    textLength: Int,
    lineNumber: (Int) -> Int,
    lineStart: (Int) -> Int,
    lineEnd: (Int) -> Int,
): GoFillAllFieldsRange {
    if (selectionStart != null && selectionEnd != null && selectionStart < selectionEnd) {
        return GoFillAllFieldsRange(selectionStart, selectionEnd)
    }
    val offset = caretOffset.coerceIn(0, textLength)
    val line = lineNumber(offset)
    return GoFillAllFieldsRange(lineStart(line), lineEnd(line))
}
