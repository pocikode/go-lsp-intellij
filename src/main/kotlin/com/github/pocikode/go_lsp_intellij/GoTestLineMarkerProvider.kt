package com.github.pocikode.go_lsp_intellij

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import javax.swing.Icon

/**
 * GoLand's green run arrows in the gutter of a `*_test.go` file: one per test function, and one on
 * the package clause for the whole file.
 *
 * This is a plain [LineMarkerProvider] rather than a `RunLineMarkerContributor`, which is the
 * normal way to put a run arrow in the gutter, because a contributor is offered PSI elements to
 * recognise and there are none here: the bundled TextMate grammar parses a whole `.go` file into a
 * single PSI leaf. The markers are therefore placed by offset inside that leaf, from the
 * declarations [GoTestFunctions] reads out of the file's text.
 *
 * Working from the text rather than from `gopls` is deliberate twice over: the arrows are there the
 * moment the file opens, and they are there in a build with no LSP module at all, where `go test`
 * still runs perfectly well.
 */
class GoTestLineMarkerProvider : LineMarkerProvider {

    /** Nothing is cheap enough for the fast pass; the whole file has to be scanned. */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (GoLspSupport.isNativeGoPluginLoaded()) return
        val leaves = elements.filter { it.firstChild == null }
        val file = leaves.firstOrNull()?.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!GoLspSupport.isGoTestFile(virtualFile)) return

        val text = file.text
        val declarations = GoTestFunctions.find(text)
        if (declarations.isEmpty()) return
        val project = file.project
        val directory = virtualFile.parent?.path ?: return

        for (declaration in declarations) {
            val anchor = leaves.firstOrNull { it.textRange.containsOffset(declaration.nameOffset) } ?: continue
            result.add(
                marker(
                    anchor = anchor,
                    range = TextRange.from(declaration.nameOffset, declaration.name.length),
                    icon = AllIcons.RunConfigurations.TestState.Run,
                    tooltip = "Run '${declaration.name}'",
                ) {
                    GoTestRunner.run(
                        project,
                        declaration.name,
                        directory,
                        SINGLE_PACKAGE,
                        GoTestFunctions.runPattern(listOf(declaration.name)),
                    )
                },
            )
        }

        val packageClause = PACKAGE_CLAUSE.find(text) ?: return
        val anchor = leaves.firstOrNull { it.textRange.containsOffset(packageClause.range.first) } ?: return
        result.add(
            marker(
                anchor = anchor,
                range = TextRange(packageClause.range.first, packageClause.range.last + 1),
                icon = AllIcons.RunConfigurations.TestState.Run_run,
                tooltip = "Run tests in ${virtualFile.name}",
            ) {
                GoTestRunner.run(
                    project,
                    virtualFile.nameWithoutExtension,
                    directory,
                    SINGLE_PACKAGE,
                    GoTestFunctions.runPattern(declarations.map { it.name }),
                )
            },
        )
    }

    private fun marker(
        anchor: PsiElement,
        range: TextRange,
        icon: Icon,
        tooltip: String,
        run: () -> Unit,
    ): LineMarkerInfo<PsiElement> = LineMarkerInfo(
        anchor,
        range,
        icon,
        Function { tooltip },
        GutterIconNavigationHandler { _, _ -> run() },
        GutterIconRenderer.Alignment.LEFT,
        { tooltip },
    )

    private companion object {
        /** A file's tests live in its own package, so the run never needs to recurse. */
        const val SINGLE_PACKAGE = "."

        val PACKAGE_CLAUSE = Regex("""(?m)^package[ \t]+\w+""")
    }
}
