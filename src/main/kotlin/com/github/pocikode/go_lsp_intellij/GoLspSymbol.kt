package com.github.pocikode.go_lsp_intellij

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.model.Pointer
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import icons.GoLspIcons
import org.eclipse.lsp4j.Range

/**
 * A Go declaration identified by the file and selection range `gopls` reported for it.
 *
 * As a [NavigatableSymbol] it is what a Go reference resolves to, which gives Cmd/Ctrl+hover its
 * link style and Cmd/Ctrl+click its navigation. As a [SearchTarget] it is what "Find Usages" and
 * "Go to Declaration or Usages" on a declaration search for, answered by [GoLspUsageSearcher].
 */
data class GoLspSymbol(val file: VirtualFile, val range: Range) : NavigatableSymbol, SearchTarget {
    /** The declared identifier, read lazily because targets in other files may not be loaded yet. */
    val name: String
        get() {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return file.name
            val textRange = GoLspRequests.textRange(document, range) ?: return file.name
            return document.getText(textRange).ifBlank { file.name }
        }

    override fun createPointer(): Pointer<GoLspSymbol> = Pointer.hardPointer(this)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        if (file.isValid) listOf(GoLspNavigationTarget(project, this)) else emptyList()

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(name).icon(GoLspIcons.GO).locationText(file.name).presentation()

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler(name)
}

data class GoLspNavigationTarget(private val project: Project, private val symbol: GoLspSymbol) : NavigationTarget {
    override fun createPointer(): Pointer<GoLspNavigationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = symbol.presentation()

    override fun navigationRequest(): NavigationRequest? {
        val document = FileDocumentManager.getInstance().getDocument(symbol.file) ?: return null
        val offset = GoLspRequests.offset(document, symbol.range.start) ?: return null
        return NavigationRequest.sourceNavigationRequest(project, symbol.file, offset)
    }
}

class GoLspDeclaration(
    private val element: PsiElement,
    private val rangeInElement: TextRange,
    private val symbol: GoLspSymbol,
) : PsiSymbolDeclaration {
    override fun getDeclaringElement(): PsiElement = element

    override fun getRangeInDeclaringElement(): TextRange = rangeInElement

    override fun getSymbol(): GoLspSymbol = symbol
}

class GoLspReference(
    private val element: PsiElement,
    private val rangeInElement: TextRange,
    private val targets: List<GoLspSymbol>,
) : PsiSymbolReference {
    override fun getElement(): PsiElement = element

    override fun getRangeInElement(): TextRange = rangeInElement

    override fun resolveReference(): Collection<GoLspSymbol> = targets
}
