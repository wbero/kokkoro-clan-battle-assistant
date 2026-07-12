package com.kokkoro.clanbattle.automation

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionCoordinatesTest {
    @Test fun `roles are left to right one through five`() {
        assertEquals(ReferencePoint(480, 845), ActionCoordinates.role(CharacterRole.ROLE_1))
        assertEquals(ReferencePoint(720, 845), ActionCoordinates.role(CharacterRole.ROLE_2))
        assertEquals(ReferencePoint(960, 845), ActionCoordinates.role(CharacterRole.ROLE_3))
        assertEquals(ReferencePoint(1200, 845), ActionCoordinates.role(CharacterRole.ROLE_4))
        assertEquals(ReferencePoint(1440, 845), ActionCoordinates.role(CharacterRole.ROLE_5))
    }

    @Test fun `global auto and menu points match reference UI`() {
        assertEquals(ReferencePoint(1828, 690), ActionCoordinates.globalSet)
        assertEquals(ReferencePoint(1828, 845), ActionCoordinates.autoButton)
        assertEquals(ReferencePoint(1805, 50), ActionCoordinates.menu)
    }

    @Test fun `axis role names map to the same left to right slots`() {
        assertEquals(ActionCoordinates.role(CharacterRole.ROLE_1), ActionCoordinates.role("角色1"))
        assertEquals(ActionCoordinates.role(CharacterRole.ROLE_5), ActionCoordinates.role("角色5"))
    }
}
