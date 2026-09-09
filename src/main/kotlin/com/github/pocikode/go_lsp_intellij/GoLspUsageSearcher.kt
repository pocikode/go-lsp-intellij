package com.github.pocikode.go_lsp_intellij

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.EmptyQuery
import com.intellij.util.Query

/** Answers usage searches for [GoLspSymbol] targets with `textDocument/references` results from `gopls`. */
class GoLspUsageSearcher : UsageSearcher {
    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> {
        val target = parameters.target as? GoLspSymbol ?: return emptyList()
        val project = parameters.project
        return ReadAction.compute<Collection<Usage>, RuntimeException> {
            val server = GoLspRequests.runningServer(project, target.file) ?: return@compute emptyList()
            val psiManager = PsiManager.getInstance(project)
            val documentManager = FileDocumentManager.getInstance()

            GoLspRequests.references(server, target.file, target.range.start).mapNotNull { location ->
                val usageFile = server.descriptor.findFileByUri(location.uri) ?: return@mapNotNull null
                if (!parameters.searchScope.contains(usageFile)) return@mapNotNull null
                val usageDocument = documentManager.getDocument(usageFile) ?: return@mapNotNull null
                val range = GoLspRequests.textRange(usageDocument, location.range) ?: return@mapNotNull null
                val psiFile = psiManager.findFile(usageFile) ?: return@mapNotNull null
                PsiUsage.textUsage(psiFile, range)
            }
        }
    }

    // Override both defaults so Kotlin does not generate bridges that invoke override-only methods.
    override fun collectSearchRequests(parameters: UsageSearchParameters): Collection<Query<out Usage>> = emptyList()

    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage> = EmptyQuery.getEmptyQuery()
}
