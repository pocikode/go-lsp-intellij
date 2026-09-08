package com.github.pocikode.go_lsp_intellij

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoFormatOnSaveStateTest {
    @Test
    fun `format on save is enabled by default`() {
        assertTrue(GoFormatOnSaveState().enabled)
    }

    @Test
    fun `loading a disabled preference keeps format on save disabled`() {
        val state = GoFormatOnSaveState()

        state.loadState(GoFormatOnSaveState().apply { enabled = false })

        assertFalse(state.enabled)
    }
}
