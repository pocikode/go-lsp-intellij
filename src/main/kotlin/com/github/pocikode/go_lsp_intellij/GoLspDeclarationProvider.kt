package com.github.pocikode.go_lsp_intellij

import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement

/**
 * Tells the platform when the caret sits on a Go declaration, so "Go to Declaration or Usages"
 * (Cmd/Ctrl+click) shows the usages popup there, matching GoLand. References are handled by
 * [GoLspReferenceProvider].
 */
class GoLspDeclarationProvider : PsiSymbolDeclarationProvider {
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        // TextMate files are a flat list of token leaves; only leaves are inspected so gopls is asked once.
        if (element.firstChild != null) return emptyList()
        val psiFile = element.containingFile ?: return emptyList()
        val file = psiFile.virtualFile ?: return emptyList()
        if (!GoLspSupport.isGoFile(file)) return emptyList()

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        val server = GoLspRequests.runningServer(element.project, file) ?: return emptyList()
        val elementRange = element.textRange
        val offset = elementRange.startOffset + offsetInElement

        val self = GoLspRequests.definitions(server, file, document, offset)
            .firstOrNull { GoLspRequests.isSelfDefinition(server, file, document, offset, it) }
            ?: return emptyList()
        val selectionRange = self.targetSelectionRange ?: self.targetRange
        val declarationRange = GoLspRequests.textRange(document, selectionRange) ?: return emptyList()
        val rangeInElement = declarationRange.intersection(elementRange)?.shiftLeft(elementRange.startOffset)
            ?.takeUnless { it.isEmpty } ?: return emptyList()

        return listOf(GoLspDeclaration(element, rangeInElement, GoLspSymbol(file, selectionRange)))
    }
}
