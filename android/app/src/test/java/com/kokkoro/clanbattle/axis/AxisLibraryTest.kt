package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisLibraryTest {
    @Test fun `valid imports are selectable and selected id is exposed`() {
        val library = AxisLibrary(InMemoryAxisStorage())

        val imported = library.import("switch.txt", validSwitch("E5刀1"))

        assertTrue(imported.valid)
        assertTrue(library.select(imported.id))
        assertEquals(imported.id, library.selected()?.id)
        assertEquals(AxisType.SWITCH, library.selectedDocument()?.type)
    }

    @Test fun `invalid imports remain listed but cannot be selected`() {
        val library = AxisLibrary(InMemoryAxisStorage())

        val imported = library.import("bad.txt", "轴类型=未知\n[轴]\n1:00 | 提示=坏轴")

        assertFalse(imported.valid)
        assertFalse(library.select(imported.id))
        assertEquals(imported.id, library.list().single().id)
        assertNull(library.selected())
    }

    @Test fun `active battle locks selection until reset`() {
        val storage = InMemoryAxisStorage()
        val library = AxisLibrary(storage)
        val secondView = AxisLibrary(storage)
        val first = library.import("a.txt", validSwitch("E5刀1"))
        val second = library.import("b.txt", validSwitch("E5刀2"))
        library.select(first.id)

        library.lock()
        assertTrue(secondView.isLocked())
        assertFalse(secondView.select(second.id))
        assertFalse(secondView.remove(first.id))
        assertEquals(first.id, library.selected()?.id)

        secondView.unlock()
        assertFalse(library.isLocked())
        assertTrue(secondView.select(second.id))
        assertEquals(second.id, library.selected()?.id)
    }

    @Test fun `reimporting normalized text replaces metadata without duplication`() {
        val storage = InMemoryAxisStorage()
        val library = AxisLibrary(storage)
        val text = validSwitch("E5刀1")

        val first = library.import("old.txt", text)
        val second = library.import("new.txt", "\r\n${text.replace("\n", "\r\n")}\r\n")

        assertEquals(first.id, second.id)
        assertEquals(1, library.list().size)
        assertEquals("new.txt", library.list().single().sourceName)
    }

    @Test fun `removing selected axis clears selection`() {
        val library = AxisLibrary(InMemoryAxisStorage())
        val imported = library.import("switch.txt", validSwitch("E5刀1"))
        library.select(imported.id)

        assertTrue(library.remove(imported.id))

        assertNull(library.selected())
        assertTrue(library.list().isEmpty())
    }

    private fun validSwitch(name: String) =
        """
        轴类型=开关
        轴名称=$name
        [轴开局] | SET=关,关,关,关,开 | AUTO=开
        1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
        """.trimIndent()

    private class InMemoryAxisStorage : AxisStorage {
        private val entries = linkedMapOf<String, StoredAxisText>()
        private var selectedId: String? = null
        private var locked = false

        override fun list(): List<StoredAxisText> = entries.values.toList()
        override fun put(entry: StoredAxisText) { entries[entry.id] = entry }
        override fun remove(id: String): Boolean = entries.remove(id) != null
        override fun selectedId(): String? = selectedId
        override fun setSelectedId(id: String?) { selectedId = id }
        override fun selectionLocked(): Boolean = locked
        override fun setSelectionLocked(locked: Boolean) { this.locked = locked }
    }
}
