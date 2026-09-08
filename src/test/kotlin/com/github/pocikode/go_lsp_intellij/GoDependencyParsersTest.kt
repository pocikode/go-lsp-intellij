package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoDependencyParsersTest {
    @Test
    fun `module stream includes updates replacements deprecations and retractions`() {
        val modules = GoDependencyParsers.modules(
            """
            {"Path":"example.com/app","Main":true,"GoMod":"/tmp/go.mod"}
            {"Path":"example.com/old","Version":"v1.2.0","Indirect":true,
             "Update":{"Path":"example.com/old","Version":"v1.4.0"},
             "Replace":{"Path":"../old"},"Deprecated":"use example.com/new",
             "Retracted":["broken release"]}
            """.trimIndent(),
        )

        assertEquals(2, modules.size)
        assertTrue(modules[0].main)
        assertEquals("example.com/old", modules[1].path)
        assertEquals("v1.4.0", modules[1].update?.version)
        assertEquals("../old", modules[1].replacement?.path)
        assertEquals("use example.com/new", modules[1].deprecated)
        assertEquals(listOf("broken release"), modules[1].retracted)
        assertEquals("indirect, replaced, update available, deprecated, retracted", modules[1].status)
    }

    @Test
    fun `module graph preserves versions and local modules`() {
        val graph = GoDependencyParsers.graph(
            """
            example.com/app example.com/lib@v1.2.3
            example.com/lib@v1.2.3 ../local
            """.trimIndent(),
        )

        assertEquals(2, graph.size)
        assertEquals(GoModuleVersion("example.com/lib", "v1.2.3"), graph[0].to)
        assertEquals(GoModuleVersion("../local", ""), graph[1].to)
    }

    @Test
    fun `govulncheck stream keeps the called trace and fixed version`() {
        val vulnerabilities = GoDependencyParsers.vulnerabilities(
            """
            {"config":{"protocol_version":"v1.0.0"}}
            {"osv":{"id":"GO-2024-0001","aliases":["CVE-1"],"summary":"bad thing"}}
            {"finding":{"osv":"GO-2024-0001","fixed_version":"v1.3.0",
             "trace":[{"module":"example.com/lib","version":"v1.2.0"}]}}
            {"finding":{"osv":"GO-2024-0001","fixed_version":"v1.3.0",
             "trace":[{"module":"example.com/lib","version":"v1.2.0","function":"lib.Bad"}]}}
            """.trimIndent(),
        )

        assertEquals(1, vulnerabilities.size)
        assertEquals("GO-2024-0001", vulnerabilities.single().id)
        assertEquals(listOf("CVE-1"), vulnerabilities.single().aliases)
        assertEquals("v1.3.0", vulnerabilities.single().fixedVersion)
        assertTrue(vulnerabilities.single().called)
        assertFalse(vulnerabilities.single().summary.isEmpty())
    }

    @Test
    fun `workspace use paths resolve relative to go work`() {
        val directories = GoDependencyParsers.workspaceDirectories(
            """{"Go":"1.27","Use":[{"DiskPath":"./api"},{"DiskPath":"../shared"}]}""",
            java.nio.file.Path.of("/workspace/app"),
        )

        assertEquals(
            listOf(java.nio.file.Path.of("/workspace/app/api"), java.nio.file.Path.of("/workspace/shared")),
            directories,
        )
    }
}
