package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeControlStateTest {
    @Test fun `automatic role taps deterministically toggle internal state`() {
        val authority = AuthoritativeControlState()
        authority.seedIfAbsent(state("XOOXO", auto = VisualToggleState.OFF))

        authority.apply(ControlAction.TapRole(CharacterRole.ROLE_3))
        authority.apply(ControlAction.TapRole(CharacterRole.ROLE_4))

        assertEquals("XOXOO", mask(requireNotNull(authority.snapshot())))
    }

    @Test fun `target actions are planned from internal state not visual state`() {
        val authority = AuthoritativeControlState()
        authority.seedIfAbsent(state("XOOXO", auto = VisualToggleState.OFF))
        val target = OpeningControlTarget(
            auto = VisualToggleState.ON,
            roles = roleStates("XOXOO")
        )

        assertEquals(
            listOf(
                ControlAction.TapRole(CharacterRole.ROLE_3),
                ControlAction.TapRole(CharacterRole.ROLE_4),
                ControlAction.TapAuto
            ),
            authority.actionsTo(target)
        )
    }

    @Test fun `visual mismatch is auxiliary and needs consecutive clean frames`() {
        val authority = AuthoritativeControlState(visualMismatchFramesBeforeDesync = 3)
        authority.seedIfAbsent(state("XOOXO"))
        val polluted = state("XOOXX")

        assertFalse(authority.audit(polluted, allowed = true).desynchronized)
        assertFalse(authority.audit(polluted, allowed = true).desynchronized)
        assertTrue(authority.audit(polluted, allowed = true).desynchronized)
    }

    @Test fun `disallowed audit does not accumulate animation mismatch`() {
        val authority = AuthoritativeControlState(visualMismatchFramesBeforeDesync = 2)
        authority.seedIfAbsent(state("XOOXO"))
        val polluted = state("XOOXX")

        assertFalse(authority.audit(polluted, allowed = true).desynchronized)
        assertFalse(authority.audit(polluted, allowed = false).desynchronized)
        assertFalse(authority.audit(polluted, allowed = true).desynchronized)
    }

    @Test fun `explicit gesture failure can rollback optimistic toggle`() {
        val authority = AuthoritativeControlState()
        authority.seedIfAbsent(state("XXXXX", auto = VisualToggleState.OFF))
        val action = ControlAction.TapRole(CharacterRole.ROLE_3)

        authority.apply(action)
        assertEquals("XXOXX", mask(requireNotNull(authority.snapshot())))

        authority.rollback(action)
        assertEquals("XXXXX", mask(requireNotNull(authority.snapshot())))
    }

    private fun state(mask: String, auto: VisualToggleState = VisualToggleState.OFF) = BattleControlState(
        auto = auto,
        globalSet = if (mask.all { it == 'O' }) VisualToggleState.ON else VisualToggleState.OFF,
        roles = roleStates(mask)
    )

    private fun roleStates(mask: String) = CharacterRole.entries.mapIndexed { index, role ->
        role to if (mask[index] == 'O') VisualToggleState.ON else VisualToggleState.OFF
    }.toMap()

    private fun mask(state: BattleControlState) = CharacterRole.entries.joinToString("") { role ->
        if (state.roles.getValue(role) == VisualToggleState.ON) "O" else "X"
    }
}
