package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterEnergyState
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergySampleBufferTest {
    @Test
    fun `no sample yet yields nothing`() {
        assertNull(EnergySampleBuffer().take())
    }

    @Test
    fun `latest ratios are delivered to the recognition frame`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(mapOf(CharacterRole.ROLE_1 to 0.25f)), 10L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_1 to 0.80f)), 20L)

        val taken = buffer.take()!!

        assertEquals(0.80f, taken.characters.getValue(CharacterRole.ROLE_1).blueRatio, 0.0001f)
    }

    /**
     * 快充 UB：TP 25→100→25 可能只占一两个采样帧，且整段发生在两次完整识别之间。
     * 缓冲必须把这次事件留到下一个完整帧交付。
     */
    @Test
    fun `ub between two recognition frames is not lost`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(mapOf(CharacterRole.ROLE_3 to 0.25f)), 10L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_3 to 1.0f)), 20L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_3 to 0.25f), triggered = setOf(CharacterRole.ROLE_3)), 30L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_3 to 0.28f)), 40L)

        val taken = buffer.take()!!

        assertEquals(setOf(CharacterRole.ROLE_3), taken.triggeredRoles)
        assertEquals(mapOf(CharacterRole.ROLE_3 to 30L), taken.triggeredRoleTimesNanos)
        assertTrue(taken.characters.getValue(CharacterRole.ROLE_3).triggered)
        // 比例仍取最新一帧，不会退回触发瞬间的旧值。
        assertEquals(0.28f, taken.characters.getValue(CharacterRole.ROLE_3).blueRatio, 0.0001f)
    }

    @Test
    fun `several ub events between recognition frames are all delivered together`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(mapOf(CharacterRole.ROLE_1 to 0.1f), triggered = setOf(CharacterRole.ROLE_1)), 10L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_4 to 0.1f), triggered = setOf(CharacterRole.ROLE_4)), 20L)

        assertEquals(
            setOf(CharacterRole.ROLE_1, CharacterRole.ROLE_4),
            buffer.take()!!.triggeredRoles
        )
    }

    @Test
    fun `visual obstruction between recognition frames is retained once`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(emptyMap(), visualObstruction = true), 10L)
        buffer.submit(sample(mapOf(CharacterRole.ROLE_1 to 0.4f)), 20L)

        assertTrue(buffer.take()!!.visualObstruction)
        assertTrue(!buffer.take()!!.visualObstruction)
    }

    /** 关键回归：同一次 UB 不能因为快照被反复读取而重复上报。 */
    @Test
    fun `a delivered ub is never delivered twice`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(mapOf(CharacterRole.ROLE_2 to 0.2f), triggered = setOf(CharacterRole.ROLE_2)), 10L)

        assertEquals(setOf(CharacterRole.ROLE_2), buffer.take()!!.triggeredRoles)

        repeat(5) {
            val again = buffer.take()!!
            assertEquals(emptySet<CharacterRole>(), again.triggeredRoles)
            assertTrue(again.characters.values.none { it.triggered })
        }
    }

    @Test
    fun `reset drops both the snapshot and undelivered events`() {
        val buffer = EnergySampleBuffer()
        buffer.submit(sample(mapOf(CharacterRole.ROLE_5 to 0.1f), triggered = setOf(CharacterRole.ROLE_5)), 10L)

        buffer.reset()

        assertNull(buffer.take())
    }

    private fun sample(
        ratios: Map<CharacterRole, Float>,
        triggered: Set<CharacterRole> = emptySet(),
        visualObstruction: Boolean = false
    ): EnergyDetectionResult {
        val characters = CharacterRole.entries.associateWith { role ->
            val ratio = ratios[role] ?: 0f
            CharacterEnergyState(
                blueRatio = ratio,
                isFull = ratio >= 0.97f,
                delta = null,
                triggered = role in triggered
            )
        }
        return EnergyDetectionResult(
            characters,
            energyDelta = null,
            triggeredRoles = triggered,
            visualObstruction = visualObstruction
        )
    }
}
