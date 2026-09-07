package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.find.actions.ShowUsagesAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import javax.swing.JComponent
import org.eclipse.lsp4j.SymbolKind

/**
 * Base for the code vision entries the plugin puts above a Go declaration, all of which read the
 * `gopls` answers [GoLspCodeVisionService] has already cached.
 */
abstract class GoLspCodeVisionProvider : DaemonBoundCodeVisionProvider {
    final override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        val virtualFile = file.virtualFile ?: return emptyList()
        if (!GoLspSupport.isGoFile(virtualFile) || GoLspSupport.isNativeGoPluginLoaded()) return emptyList()
        val document = editor.document
        val service = GoLspCodeVisionService.getInstance(file.project)

        return service.declarations(virtualFile, document).mapNotNull { declaration ->
            val range = GoLspRequests.textRange(document, declaration.selectionRange) ?: return@mapNotNull null
            val entry = createEntry(file.project, virtualFile, service, declaration) ?: return@mapNotNull null
            range to entry
        }
    }

    protected abstract fun createEntry(
        project: Project,
        file: VirtualFile,
        service: GoLspCodeVisionService,
        declaration: GoLspCodeVisionService.Declaration,
    ): CodeVisionEntry?
}

/**
 * "N usages" above every Go declaration, from `textDocument/references`, opening the same
 * "Show Usages" popup GoLand does. The search itself is answered by [GoLspUsageSearcher] through
 * the [GoLspSymbol] target the entry carries.
 */
class GoLspUsagesCodeVisionProvider : GoLspCodeVisionProvider() {
    override val id: String = ID
    override val name: String = "Go usages"
    override val groupId: String = PlatformCodeVisionIds.USAGES.key
    /** Follows the IDE's own code vision position, so it sits beside the author the way GoLand does. */
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Default
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override fun createEntry(
        project: Project,
        file: VirtualFile,
        service: GoLspCodeVisionService,
        declaration: GoLspCodeVisionService.Declaration,
    ): CodeVisionEntry? {
        val count = service.usageCount(file, declaration) ?: return null
        if (count == 0) return null
        val text = if (count == 1) "1 usage" else "$count usages"
        val symbol = GoLspSymbol(file, declaration.selectionRange)
        return ClickableTextCodeVisionEntry(text, id, { event, editor ->
            showUsages(project, symbol, event, editor)
        })
    }

    private fun showUsages(project: Project, symbol: GoLspSymbol, event: MouseEvent?, editor: Editor) {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .build()
        val point = event?.let { RelativePoint(it) }
            ?: RelativePoint(editor.contentComponent, editor.visualPositionToXY(editor.caretModel.visualPosition))
        ShowUsagesAction.showUsages(project, dataContext, point, symbol)
    }

    companion object {
        const val ID: String = "go.lsp.usages"
    }
}

/**
 * "Implement interface" above every Go type declaration, opening the chooser in
 * [GoLspImplementInterfaceService].
 *
 * Interfaces are left out: in Go an interface is satisfied structurally, so the methods are only
 * ever generated onto a concrete type.
 */
class GoLspImplementInterfaceCodeVisionProvider : GoLspCodeVisionProvider() {
    override val id: String = ID
    override val name: String = "Implement interface"
    override val groupId: String = ID
    /** GoLand pins this one above the declaration rather than following the IDE's default position. */
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override fun createEntry(
        project: Project,
        file: VirtualFile,
        service: GoLspCodeVisionService,
        declaration: GoLspCodeVisionService.Declaration,
    ): CodeVisionEntry? {
        if (declaration.kind !in IMPLEMENTABLE_KINDS) return null
        // A method is reported with the same kind as nothing else here, but guard anyway: only a
        // bare type name can receive new methods.
        if (declaration.methodOf != null) return null
        return ClickableTextCodeVisionEntry(
            "Implement interface",
            id,
            { event, editor ->
                GoLspImplementInterfaceService.getInstance(project).choose(editor, file, declaration, event)
            },
            AllIcons.Actions.SuggestedRefactoringBulb,
        )
    }

    companion object {
        const val ID: String = "go.lsp.implement.interface"

        /** How gopls reports a Go named type: a struct as [SymbolKind.Struct], anything else as a class. */
        private val IMPLEMENTABLE_KINDS = setOf(SymbolKind.Struct, SymbolKind.Class)
    }
}

/**
 * The last committer beside the usage count, the way GoLand shows it, and the Git blame gutter on
 * click.
 *
 * The platform ships this vision, but it cannot answer for Go here: it walks the PSI for elements a
 * `VcsCodeVisionLanguageContext` recognises, and the TextMate grammar that claims `.go` files
 * parses the whole file into a single leaf. See [GoLspCodeAuthors].
 */
class GoLspAuthorCodeVisionProvider : GoLspCodeVisionProvider() {
    override val id: String = ID

    /** Shares the platform's "Code author" settings group, so one switch governs both. */
    override val name: String = "Code author"
    override val groupId: String = PLATFORM_GROUP_ID
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Default
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(GoLspUsagesCodeVisionProvider.ID))

    override fun createEntry(
        project: Project,
        file: VirtualFile,
        service: GoLspCodeVisionService,
        declaration: GoLspCodeVisionService.Declaration,
    ): CodeVisionEntry? {
        val author = service.author(file, declaration) ?: return null
        return ClickableTextCodeVisionEntry(
            GoLspCodeAuthors.textOf(author),
            id,
            { event, editor -> showAnnotations(event, editor) },
            AllIcons.Vcs.Author,
        )
    }

    /** Toggles the Git annotations gutter, which is what the platform's own author hint does. */
    private fun showAnnotations(event: MouseEvent?, editor: Editor) {
        val action = ActionManager.getInstance().getAction(ANNOTATE_ACTION_ID) ?: return
        val component = event?.component as? JComponent ?: editor.contentComponent
        ActionUtil.invokeAction(action, component, "EditorInlay", event, null)
    }

    companion object {
        const val ID: String = "go.lsp.vcs.author"

        private const val PLATFORM_GROUP_ID = "vcs.code.vision"
        private const val ANNOTATE_ACTION_ID = "Annotate"
    }
}
