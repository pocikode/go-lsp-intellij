package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.intellij.util.ThreeState

internal object GoStructTagCompletion {
    val keys: List<String> = listOf("asn1", "bson", "json", "xml", "yaml")

    data class Context(val fieldName: String, val bodyOffset: Int)

    fun contextAt(source: CharSequence, caretOffset: Int): Context? {
        val struct = GoStructFields.enclosing(source, caretOffset) ?: return null
        return struct.fields.firstNotNullOfOrNull { field ->
            val body = field.tagBodyRange ?: return@firstNotNullOfOrNull null
            if (body.isEmpty && (caretOffset == body.startOffset || caretOffset == body.startOffset + 1)) {
                Context(field.name, body.startOffset)
            } else {
                null
            }
        }
    }
}

/** GoLand-style completion for the empty raw string after a Go struct field. */
class GoStructTagCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val file = parameters.originalFile
                    if (!GoLspSupport.isGoTextMateFile(file)) return
                    val completion = GoStructTagCompletion.contextAt(
                        parameters.editor.document.charsSequence,
                        parameters.offset,
                    ) ?: return

                    result.addElement(allFieldsElement(completion))
                    GoStructTagCompletion.keys.forEachIndexed { index, key ->
                        result.addElement(tagKeyElement(key, completion, 10.0 - index))
                    }
                    result.stopHere()
                }
            },
        )
    }

    private fun allFieldsElement(completion: GoStructTagCompletion.Context) = PrioritizedLookupElement.withPriority(
        LookupElementBuilder.create(ADD_TO_ALL_FIELDS)
            .withIcon(AllIcons.Nodes.Tag)
            .withInsertHandler { context, _ ->
                context.document.deleteString(context.startOffset, context.tailOffset)
                context.editor.caretModel.moveToOffset(completion.bodyOffset)
                context.setLaterRunnable {
                    GoAddKeyToTagsIntention.addKeyToAllFields(context.project, context.editor)
                }
            },
        100.0,
    )

    private fun tagKeyElement(
        key: String,
        completion: GoStructTagCompletion.Context,
        priority: Double,
    ) = PrioritizedLookupElement.withPriority(
        LookupElementBuilder.create(key)
            .withIcon(AllIcons.Nodes.Tag)
            .withInsertHandler { context, _ ->
                context.document.deleteString(context.startOffset, context.tailOffset)
                val pair = GoStructTagEdits.pair(key, completion.fieldName)
                context.document.insertString(completion.bodyOffset, pair)
                context.editor.caretModel.moveToOffset(completion.bodyOffset + pair.length)
            },
        priority,
    )

    private companion object {
        const val ADD_TO_ALL_FIELDS = "Add tag key to all fields"
    }
}

/** Opens completion after the closing backtick creates an empty struct tag. */
class GoStructTagCompletionAutoPopup : TypedHandlerDelegate(), DumbAware {
    override fun checkAutoPopup(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '`' || !GoLspSupport.isGoTextMateFile(file)) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor) { popupFile ->
            GoLspSupport.isGoTextMateFile(popupFile) &&
                GoStructTagCompletion.contextAt(
                    editor.document.charsSequence,
                    editor.caretModel.offset,
                ) != null
        }
        return Result.CONTINUE
    }
}

/** TextMate normally suppresses automatic completion inside strings; an empty field tag is special. */
class GoStructTagCompletionConfidence : CompletionConfidence(), DumbAware {
    override fun shouldSkipAutopopup(
        editor: Editor,
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState =
        if (
            GoLspSupport.isGoTextMateFile(psiFile) &&
            GoStructTagCompletion.contextAt(editor.document.charsSequence, editor.caretModel.offset) != null
        ) {
            ThreeState.NO
        } else {
            ThreeState.UNSURE
        }
}
