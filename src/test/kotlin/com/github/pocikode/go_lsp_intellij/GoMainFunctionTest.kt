package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GoMainFunctionTest {

    @Test
    fun `finds main in package main`() {
        val source = "package main\n\nfunc main() {}\n"
        val offset = GoMainFunction.findNameOffset(source)
        assertEquals("main", source.substring(offset!!, offset + "main".length))
    }

    @Test
    fun `allows a multiline empty parameter list`() {
        val source = "package main\n\nfunc main(\n) {\n}\n"
        assertEquals(source.indexOf("main("), GoMainFunction.findNameOffset(source))
    }

    @Test
    fun `finds main in a CRLF file`() {
        val source = "package main\r\n\r\nfunc main() {}\r\n"
        assertEquals(source.indexOf("main("), GoMainFunction.findNameOffset(source))
    }

    @Test
    fun `rejects main outside package main`() {
        assertNull(GoMainFunction.findNameOffset("package command\n\nfunc main() {}\n"))
    }

    @Test
    fun `rejects main with parameters or results`() {
        assertNull(GoMainFunction.findNameOffset("package main\n\nfunc main(args []string) {}\n"))
        assertNull(GoMainFunction.findNameOffset("package main\n\nfunc main() error { return nil }\n"))
    }

    @Test
    fun `rejects an opening brace on the next line`() {
        assertNull(GoMainFunction.findNameOffset("package main\n\nfunc main()\n{}\n"))
    }

    @Test
    fun `rejects a method named main`() {
        assertNull(GoMainFunction.findNameOffset("package main\n\nfunc (app App) main() {}\n"))
    }
}
