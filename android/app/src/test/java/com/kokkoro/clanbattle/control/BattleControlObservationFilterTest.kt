package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleControlObservationFilterTest {
    @Test
    fun `initial state requires consecutive confirmation`() {
        val filter = BattleControlObservationFilter()
        val state = observation(VisualToggleState.ON, "OOOOO")

        val first = filter.update(state)
        val second = filter.update(state)

        assertNull(first.observation)
        assertFalse(first.trustworthy)
        assertEquals(ControlObservationStatus.PENDING_CONFIRMATION, first.status)
        assertEquals(state, second.observation)
        assertTrue(second.trustworthy)
        assertEquals(ControlObservationStatus.TRUSTWORTHY, second.status)
    }

    @Test
    fun `single role change is accepted after confirmation`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))
        val changed = observation(VisualToggleState.OFF, "OOOXO")

        val first = filter.update(changed)
        val second = filter.update(changed)

        assertFalse(first.trustworthy)
        assertEquals(ControlObservationStatus.PENDING_CONFIRMATION, first.status)
        assertEquals("OXOXO", first.observation!!.roleText())
        assertTrue(second.trustworthy)
        assertEquals("OOOXO", second.observation!!.roleText())
    }

    @Test
    fun `bulk role change without global set change is treated as visual corruption`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))
        val corrupted = observation(VisualToggleState.OFF, "XOXOX")

        repeat(5) {
            val result = filter.update(corrupted)
            assertFalse(result.trustworthy)
            assertEquals(ControlObservationStatus.IMPLAUSIBLE_TRANSITION, result.status)
            assertEquals("OXOXO", result.observation!!.roleText())
        }
    }

    @Test
    fun `global set transition can change all roles together`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "XXXXX"))
        val enabled = observation(VisualToggleState.ON, "OOOOO")

        assertFalse(filter.update(enabled).trustworthy)
        val confirmed = filter.update(enabled)

        assertTrue(confirmed.trustworthy)
        assertEquals("OOOOO", confirmed.observation!!.roleText())
    }

    @Test
    fun `unknown frame holds stable state but is not trustworthy`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))
        val result = filter.update(observation(VisualToggleState.OFF, "O?OXO"))

        assertFalse(result.trustworthy)
        assertEquals(ControlObservationStatus.RAW_UNTRUSTWORTHY, result.status)
        assertEquals("OXOXO", result.observation!!.roleText())
    }

    @Test
    fun `missing crop is distinguished from an untrustworthy raw observation`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))

        val result = filter.missing()

        assertFalse(result.trustworthy)
        assertEquals(ControlObservationStatus.MISSING, result.status)
        assertEquals("OXOXO", result.observation!!.roleText())
    }

    private fun preparedFilter(initial: BattleControlObservation) =
        BattleControlObservationFilter().also { filter ->
            filter.update(initial)
            assertTrue(filter.update(initial).trustworthy)
        }

    private fun observation(global: VisualToggleState, roles: String): BattleControlObservation {
        val roleStates = CharacterRole.entries.associateWith { role ->
            when (roles[role.ordinal]) {
                'O' -> toggle(VisualToggleState.ON)
                'X' -> toggle(VisualToggleState.OFF)
                else -> toggle(VisualToggleState.UNKNOWN)
            }
        }
        return BattleControlObservation(
            auto = toggle(VisualToggleState.ON),
            globalSet = toggle(global),
            roles = roleStates,
            consistent = global != VisualToggleState.ON || roleStates.values.all { it.state == VisualToggleState.ON }
        )
    }

    private fun toggle(state: VisualToggleState) = ToggleObservation(state, onScore = 1.0)

    private fun BattleControlObservation.roleText(): String = CharacterRole.entries.joinToString("") { role ->
        when (roles.getValue(role).state) {
            VisualToggleState.ON -> "O"
            VisualToggleState.OFF -> "X"
            VisualToggleState.UNKNOWN -> "?"
        }
    }
}
