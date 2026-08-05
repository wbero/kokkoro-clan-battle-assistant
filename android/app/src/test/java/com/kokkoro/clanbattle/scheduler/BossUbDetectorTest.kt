package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BossUbDetectorTest {
    @Test fun `normal clock cadence does not detect boss ub`() {
        val detector = BossUbDetector()

        assertNull(detector.update(60, emptySet(), 0))
        assertNull(detector.update(59, emptySet(), 1_000))
        assertNull(detector.update(58, emptySet(), 2_050))
        assertNull(detector.latestEvent(2_050))
    }

    @Test fun `completed abnormal hold detects boss ub`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(59, emptySet(), 1_000)
        detector.update(58, emptySet(), 2_000)

        assertNull(detector.update(58, emptySet(), 4_000))
        val event = detector.update(57, emptySet(), 8_000)

        assertEquals(BossUbEvent(58, 8_000, 6_000), event)
        assertEquals(event, detector.latestEvent(8_100))
    }

    @Test fun `long hold emits an early event before the clock ticks`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(60, emptySet(), 4_000))
        assertNull(detector.update(60, emptySet(), 6_999))
        assertEquals(
            BossUbEvent(60, 7_000, 7_000, early = true),
            detector.update(60, emptySet(), 7_000)
        )
        assertNull(detector.update(60, emptySet(), 7_100))
        assertEquals(
            BossUbEvent(60, 7_300, 7_300),
            detector.update(59, emptySet(), 7_300)
        )
    }

    @Test fun `single character tp drop suppresses early detection`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(60, setOf(CharacterRole.ROLE_4), 4_000)

        assertNull(detector.update(60, emptySet(), 7_000))
        assertNull(detector.update(59, emptySet(), 7_300))
    }

    @Test fun `configured early threshold is used for the current hold`() {
        val detector = BossUbDetector()
        detector.configureEarlyConfirmationHoldMs(5_000)
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(60, emptySet(), 4_999))
        assertEquals(
            BossUbEvent(60, 5_000, 5_000, early = true),
            detector.update(60, emptySet(), 5_000)
        )
    }

    @Test fun `single character tp drop suppresses boss detection for that hold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        detector.update(60, setOf(CharacterRole.ROLE_4), 2_200)

        assertNull(detector.update(59, emptySet(), 7_000))
        assertNull(detector.latestEvent(7_000))
    }

    @Test fun `repeated same-role tp artifacts cannot suppress a long boss hold forever`() {
        val detector = BossUbDetector()
        detector.update(50, emptySet(), 0)

        assertNull(detector.update(50, setOf(CharacterRole.ROLE_1), 3_400))
        assertNull(detector.update(50, setOf(CharacterRole.ROLE_1), 3_650))
        for (nowMs in listOf(5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L, 10_399L)) {
            assertNull(detector.update(50, emptySet(), nowMs))
        }

        assertEquals(
            BossUbEvent(50, 10_400, 10_400, early = true),
            detector.update(50, emptySet(), 10_400)
        )
        assertEquals(
            BossUbEvent(50, 13_500, 13_500),
            detector.update(49, emptySet(), 13_500)
        )
    }

    @Test fun `multi-role obstruction after a single release allows conservative boss recovery`() {
        val detector = BossUbDetector()
        detector.update(50, emptySet(), 0)

        assertNull(detector.update(50, setOf(CharacterRole.ROLE_1), 3_400))
        assertNull(
            detector.update(
                50,
                setOf(
                    CharacterRole.ROLE_2,
                    CharacterRole.ROLE_3,
                    CharacterRole.ROLE_4,
                    CharacterRole.ROLE_5
                ),
                5_000
            )
        )
        for (nowMs in listOf(6_000L, 7_000L, 8_000L, 9_000L, 10_000L)) {
            assertNull(detector.update(50, emptySet(), nowMs))
        }

        assertEquals(
            BossUbEvent(50, 10_400, 10_400, early = true),
            detector.update(50, emptySet(), 10_400)
        )
    }

    @Test fun `short noisy hold after a fake release is still not a boss ub`() {
        val detector = BossUbDetector()
        detector.update(50, emptySet(), 0)

        detector.update(50, setOf(CharacterRole.ROLE_1), 2_000)
        detector.update(50, setOf(CharacterRole.ROLE_1), 2_200)

        assertNull(detector.update(49, emptySet(), 5_000))
        assertNull(detector.latestEvent(5_000))
    }

    @Test fun `simultaneous tp drops are treated as visual obstruction`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        detector.update(
            60,
            setOf(CharacterRole.ROLE_2, CharacterRole.ROLE_5),
            2_000
        )
        val event = detector.update(59, emptySet(), 6_000)

        assertEquals(60, event?.heldClockSeconds)
    }

    @Test fun `suspend discards an in progress hold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.suspend()

        assertNull(detector.update(60, emptySet(), 8_000))
        assertNull(detector.update(59, emptySet(), 9_000))
    }

    @Test fun `capture gap is not classified as a boss hold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(59, emptySet(), 6_000))
        assertNull(detector.latestEvent(6_000))
    }

    @Test fun `reset clears retained boss event`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(59, emptySet(), 6_000)

        detector.reset()

        assertNull(detector.latestEvent(6_000))
    }
}
