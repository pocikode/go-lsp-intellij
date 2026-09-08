package com.github.pocikode.go_lsp_intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoLspSupportTest {

    private fun file(name: String): VirtualFile = LightVirtualFile(name)

    @Test
    fun `test files are recognised by the _test dot go suffix`() {
        assertTrue(GoLspSupport.isGoTestFile(file("promo_test.go")))
        assertTrue(GoLspSupport.isGoTestFile(file("promo_crud_test.go")))
        assertTrue(GoLspSupport.isGoTestFile(file("a_test.go")))
    }

    @Test
    fun `ordinary Go files are not test files`() {
        assertFalse(GoLspSupport.isGoTestFile(file("promo.go")))
        assertFalse(GoLspSupport.isGoTestFile(file("helpers.go")))
        assertFalse(GoLspSupport.isGoTestFile(file("testing.go")))
        assertFalse(GoLspSupport.isGoTestFile(file("test.go")))
    }

    @Test
    fun `the bare suffix is not a test file`() {
        // Go needs a name before the suffix; `_test.go` alone is not a test file to the toolchain.
        assertFalse(GoLspSupport.isGoTestFile(file("_test.go")))
    }

    @Test
    fun `look-alikes outside Go are not test files`() {
        assertFalse(GoLspSupport.isGoTestFile(file("promo_test.goo")))
        assertFalse(GoLspSupport.isGoTestFile(file("promo_test.md")))
        assertFalse(GoLspSupport.isGoTestFile(file("promo-test.go")))
    }

    @Test
    fun `module files are recognised`() {
        assertTrue(GoLspSupport.isGoModuleFile(file("go.mod")))
        assertTrue(GoLspSupport.isGoModuleFile(file("go.sum")))
        assertTrue(GoLspSupport.isGoModuleFile(file("go.work")))
        assertTrue(GoLspSupport.isGoModuleFile(file("go.work.sum")))
        assertFalse(GoLspSupport.isGoModuleFile(file("go.mod.bak")))
        assertFalse(GoLspSupport.isGoModuleFile(file("main.go")))
    }

    @Test
    fun `gopls handles source and editable module manifests`() {
        assertTrue(GoLspSupport.isGoLspFile(file("main.go")))
        assertTrue(GoLspSupport.isGoLspFile(file("go.mod")))
        assertTrue(GoLspSupport.isGoLspFile(file("go.work")))
        assertFalse(GoLspSupport.isGoLspFile(file("go.sum")))
        assertFalse(GoLspSupport.isGoLspFile(file("go.work.sum")))
    }

    @Test
    fun `go files are recognised by extension`() {
        assertTrue(GoLspSupport.isGoFile(file("main.go")))
        assertTrue(GoLspSupport.isGoFile(file("promo_test.go")))
        assertFalse(GoLspSupport.isGoFile(file("go.mod")))
    }
}
