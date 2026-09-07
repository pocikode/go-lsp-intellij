package com.github.pocikode.go_lsp_intellij

import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement

/**
 * Resolves the Go identifier under the caret to its declaration through `gopls`.
 *
 * The platform LSP client has its own reference provider, but it only answers while a
 * "Go to Declaration" action is running, so Cmd/Ctrl+hover never styles call sites as links.
 * This provider answers whenever it is asked. The descriptor disables the platform's go-to-definition
 * support so the two never produce duplicate targets.
 */
class GoLspReferenceProvider : ImplicitReferenceProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        if (element.firstChild != null) return null
        val psiFile = element.containingFile ?: return null
        val file = psiFile.virtualFile ?: return null
        if (!GoLspSupport.isGoFile(file)) return null

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val elementRange = element.textRange
        val offset = elementRange.startOffset + offsetInElement
        val identifierRange = GoLspRequests.identifierRangeAt(document, offset) ?: return null
        val server = GoLspRequests.runningServer(element.project, file) ?: return null

        val links = GoLspRequests.definitions(server, file, document, offset)
        if (links.isEmpty()) return null
        // The caret is on the declaration itself: that is GoLspDeclarationProvider's job.
        if (links.any { GoLspRequests.isSelfDefinition(server, file, document, offset, it) }) return null

        val targets = links.mapNotNull { link ->
            val targetFile = server.descriptor.findFileByUri(link.targetUri) ?: return@mapNotNull null
            GoLspSymbol(targetFile, link.targetSelectionRange ?: link.targetRange)
        }.distinct()
        if (targets.isEmpty()) return null

        // Prefer the origin range gopls reports; otherwise style exactly the identifier under the caret.
        val absoluteRange = links.firstNotNullOfOrNull { it.originSelectionRange }
            ?.let { GoLspRequests.textRange(document, it) }
            ?.takeUnless { it.isEmpty }
            ?: identifierRange
        val rangeInElement = absoluteRange.intersection(elementRange)
            ?.shiftLeft(elementRange.startOffset)
            ?.takeUnless { it.isEmpty }
            ?: return null
        return GoLspReference(element, rangeInElement, targets)
    }
}
