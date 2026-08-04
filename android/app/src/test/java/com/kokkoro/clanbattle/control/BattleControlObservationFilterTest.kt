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
    fun `bulk role change is rejected as corruption until it repeats`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))
        val corrupted = observation(VisualToggleState.OFF, "XOXOX")

        repeat(2) {
            val result = filter.update(corrupted)
            assertFalse(result.trustworthy)
            assertEquals(ControlObservationStatus.IMPLAUSIBLE_TRANSITION, result.status)
            assertEquals("OXOXO", result.observation!!.roleText())
        }

        // 连续第三帧仍是同一状态：画面确实变了，必须接受，否则稳定状态永久锁死。
        val accepted = filter.update(corrupted)
        assertTrue(accepted.trustworthy)
        assertEquals("XOXOX", accepted.observation!!.roleText())
    }

    @Test
    fun `isolated corrupt frames never change the stable state`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))
        val stable = observation(VisualToggleState.OFF, "OXOXO")

        repeat(4) {
            assertEquals(
                ControlObservationStatus.IMPLAUSIBLE_TRANSITION,
                filter.update(observation(VisualToggleState.OFF, "XOXOX")).status
            )
            assertTrue(filter.update(stable).trustworthy)
        }
    }

    @Test
    fun `alternating corrupt states do not accumulate confirmation`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "OXOXO"))

        repeat(4) {
            assertFalse(filter.update(observation(VisualToggleState.OFF, "XOXOX")).trustworthy)
            assertFalse(filter.update(observation(VisualToggleState.OFF, "XXOOX")).trustworthy)
        }

        assertEquals("OXOXO", filter.missing().observation!!.roleText())
    }

    /**
     * 手机实战日志 session 帧 2648~2766 的真实序列：SET 徽标呼吸动画让稳定状态
     * 一次掉一个角色地退到全关，恢复时两个角色同时出现。旧实现在这里被永久锁死，
     * 每 8 帧触发一次安全暂停。
     */
    @Test
    fun `breathing badge dropout does not latch the filter permanently`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "XXOOX"))

        // 动画逐个吃掉徽标：每次只差一个角色，属于"合理"跳变，稳定状态被带到全关。
        val lostRole3 = observation(VisualToggleState.OFF, "XXXOX")
        assertFalse(filter.update(lostRole3).trustworthy)
        assertTrue(filter.update(lostRole3).trustworthy)
        val lostBoth = observation(VisualToggleState.OFF, "XXXXX")
        assertFalse(filter.update(lostBoth).trustworthy)
        assertTrue(filter.update(lostBoth).trustworthy)

        // 两个徽标同时恢复：旧实现永远拒绝，新实现在有限帧后回到真实状态。
        val recovered = observation(VisualToggleState.OFF, "XXOOX")
        val results = (1..3).map { filter.update(recovered) }

        assertEquals(ControlObservationStatus.IMPLAUSIBLE_TRANSITION, results[0].status)
        assertEquals(ControlObservationStatus.IMPLAUSIBLE_TRANSITION, results[1].status)
        assertTrue(results[2].trustworthy)
        assertEquals("XXOOX", results[2].observation!!.roleText())
    }

    /** 恢复所需帧数必须小于安全门的暂停阈值，否则死锁只是变慢而没有被解决。 */
    @Test
    fun `implausible recovery completes before the safety gate would pause`() {
        val filter = preparedFilter(observation(VisualToggleState.OFF, "XXXXX"))
        val gate = ControlObservationSafetyGate()
        val recovered = observation(VisualToggleState.OFF, "XXOOX")

        var pauses = 0
        repeat(8) {
            val result = filter.update(recovered)
            if (gate.evaluate(result).decision == ControlObservationSafetyDecision.PAUSE) pauses++
        }

        assertEquals(0, pauses)
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
