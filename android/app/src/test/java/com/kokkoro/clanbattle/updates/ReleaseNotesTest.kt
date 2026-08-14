package com.kokkoro.clanbattle.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun extractsOnlyRequestedVersion() {
        val markdown = """
            # Changelog

            ## 3.1.2 (2026-08-14)

            ### 优化
            - **悬浮窗信息精简**：默认隐藏诊断信息。

            ## 3.1.1 (2026-08-14)
            - 旧内容
        """.trimIndent()

        val notes = ReleaseNotes.extractVersion(markdown, "3.1.2")
        assertNotNull(notes)
        assertTrue(notes!!.contains("悬浮窗信息精简"))
        assertTrue(notes.contains("• 悬浮窗信息精简"))
        assertFalse(notes.contains("旧内容"))
        assertFalse(notes.contains("**"))
    }

    @Test
    fun missingVersionReturnsNull() {
        assertNull(ReleaseNotes.extractVersion("## 3.1.1\n- old", "3.1.2"))
    }
}
