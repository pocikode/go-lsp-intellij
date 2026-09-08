package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoProjectModelTest {
    @Test
    fun `package stream preserves module and source files`() {
        val packages = GoProjectModelParsers.packages(
            """{"Dir":"/workspace/api","ImportPath":"example.com/api","Name":"api","Standard":false,"Module":{"Path":"example.com"},"GoFiles":["main.go"],"TestGoFiles":["main_test.go"]}""",
        )

        assertEquals("example.com/api", packages.single().importPath)
        assertEquals("example.com", packages.single().modulePath)
        assertEquals("/workspace/api/main.go", packages.single().goFiles.single().toString())
        assertTrue(packages.single().testGoFiles.single().toString().endsWith("main_test.go"))
    }

    @Test
    fun `build tags are split and deduplicated`() {
        val tags = "linux cgo,linux integration".split(Regex("[ ,]+" )).filter(String::isNotBlank).distinct()
        assertEquals(listOf("linux", "cgo", "integration"), tags)
    }

    @Test
    fun `sdk download accepts official go version names only`() {
        assertTrue(Regex("go\\d+(?:\\.\\d+){1,2}(?:[a-z]\\d+)?").matches("go1.24.2"))
        assertTrue(!Regex("go\\d+(?:\\.\\d+){1,2}(?:[a-z]\\d+)?").matches("latest"))
    }
}
