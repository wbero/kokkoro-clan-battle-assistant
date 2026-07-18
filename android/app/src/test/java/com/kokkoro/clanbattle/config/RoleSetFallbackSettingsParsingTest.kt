package com.kokkoro.clanbattle.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleSetFallbackSettingsParsingTest {
    @Test fun `parses valid millisecond delay`() {
        assertEquals(0, parseRoleSetFallbackGraceMs(" 0 "))
        assertEquals(1_500, parseRoleSetFallbackGraceMs("1500"))
        assertEquals(30_000, parseRoleSetFallbackGraceMs("30000"))
    }

    @Test fun `rejects invalid millisecond delay`() {
        assertNull(parseRoleSetFallbackGraceMs(""))
        assertNull(parseRoleSetFallbackGraceMs("-1"))
        assertNull(parseRoleSetFallbackGraceMs("30001"))
        assertNull(parseRoleSetFallbackGraceMs("一千"))
    }
}
