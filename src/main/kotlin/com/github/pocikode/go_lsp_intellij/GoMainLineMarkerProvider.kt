package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.Function

/** Places a GoLand-style run arrow on `func main()` in `package main`. */
class GoMainLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (GoLspSupport.isNativeGoPluginLoaded()) return
        val leaves = elements.filter { it.firstChild == null }
        val file = leaves.firstOrNull()?.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!GoLspSupport.isGoFile(virtualFile) || GoLspSupport.isGoTestFile(virtualFile)) return

        val nameOffset = GoMainFunction.findNameOffset(file.text) ?: return
        val anchor = leaves.firstOrNull { it.textRange.containsOffset(nameOffset) } ?: return
        val directory = virtualFile.parent?.path ?: return
        val tooltip = "Run 'main'"
        result.add(
            LineMarkerInfo(
                anchor,
                TextRange.from(nameOffset, MAIN.length),
                AllIcons.RunConfigurations.TestState.Run,
                Function { tooltip },
                GutterIconNavigationHandler { _, _ -> GoRunRunner.run(file.project, MAIN, directory) },
                GutterIconRenderer.Alignment.LEFT,
                { tooltip },
            ),
        )
    }

    private companion object {
        const val MAIN = "main"
    }
}
