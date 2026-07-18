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

    @Test fun `single character tp drop suppresses boss detection for that hold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        detector.update(60, setOf(CharacterRole.ROLE_4), 2_200)

        assertNull(detector.update(59, emptySet(), 7_000))
        assertNull(detector.latestEvent(7_000))
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
