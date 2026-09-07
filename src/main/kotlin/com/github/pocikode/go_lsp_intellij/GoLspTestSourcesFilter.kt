package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reports `*_test.go` as test sources, which is what tints them green in the project view.
 *
 * The colour is not set here and no scope is declared: the platform's built-in "Tests" scope is a
 * filtered package set over `TestSourcesFilter.isTestSources`, and File Colors paints that scope
 * Green out of the box, taking the shade from the active theme. Go has no test source root for the
 * platform to find on its own, so without a filter nothing is ever in that scope. This is the same
 * hook, and the same single rule, GoLand uses.
 *
 * Being a test source also means the usual things - test scopes in Find Usages, inspections that
 * skip tests - which is the intended meaning for a Go test file.
 */
class GoLspTestSourcesFilter : TestSourcesFilter() {

    /** JetBrains' Go plugin registers this same rule; leave it to them when it is loaded. */
    private val nativeGoPluginLoaded: Boolean by lazy { GoLspSupport.isNativeGoPluginLoaded() }

    override fun isTestSource(file: VirtualFile, project: Project): Boolean =
        !nativeGoPluginLoaded && GoLspSupport.isGoTestFile(file)
}
