package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionFilterTest {
    @Test
    fun `accepts real device 1 29 confidence while preserving countdown direction`() {
        val filter = RecognitionFilter(minConfidence = 0.75)

        assertTrue(filter.update(RecognitionResult.ok(90, "1:30", 0.8142), 0).accepted)

        val nextSecond = filter.update(RecognitionResult.ok(89, "1:29", 0.7794), 1_000)
        assertTrue(nextSecond.accepted)
        assertEquals(89, nextSecond.timeSeconds)

        val falseIncrease = filter.update(RecognitionResult.ok(90, "1:30", 0.90), 1_100)
        assertFalse(falseIncrease.accepted)
        assertEquals("time-increased", falseIncrease.reason)
    }

    @Test
    fun `accepts exact next second candidate even when six score is below normal alternative threshold`() {
        val filter = RecognitionFilter(minAlternativeScore = 0.55)
        assertTrue(filter.update(RecognitionResult.ok(7, "0:07", 0.95), 1_000).accepted)

        val recovered = filter.update(
            RecognitionResult(
                ok = false,
                timeSeconds = 0,
                rawText = "0:00",
                confidence = 0.4,
                reason = "out-of-range",
                candidates = listOf(
                    ClockCandidate(6, "0:06", 0.40, isPrimary = false)
                )
            ),
            1_200
        )

        assertTrue(recovered.accepted)
        assertEquals(6, recovered.timeSeconds)
        assertEquals(ReadingSource.ALTERNATIVE, recovered.source)
    }

    @Test
    fun `uses temporal alternative when primary is implausible`() {
        val filter = RecognitionFilter(minConfidence = 0.8, minAlternativeScore = 0.55)

        assertTrue(filter.update(RecognitionResult.ok(80, "1:20", 1.0), 0).accepted)

        val recovered = filter.update(
            RecognitionResult(
                ok = false,
                timeSeconds = 70,
                rawText = "1:10",
                confidence = 0.59,
                reason = "low-confidence",
                candidates = listOf(
                    ClockCandidate(70, "1:10", 0.78, true),
                    ClockCandidate(79, "1:19", 0.72, false)
                )
            ),
            1_000
        )

        assertTrue(recovered.accepted)
        assertEquals(79, recovered.timeSeconds)
        assertEquals("1:19", recovered.rawText)
        assertEquals(ReadingSource.ALTERNATIVE, recovered.source)
    }

    @Test
    fun `rejects invalid range and time increase`() {
        val filter = RecognitionFilter(minConfidence = 0.8)
        assertFalse(filter.update(RecognitionResult.ok(0, "0:00", 1.0), 0).accepted)
        assertTrue(filter.update(RecognitionResult.ok(60, "1:00", 1.0), 1_000).accepted)

        val increased = filter.update(RecognitionResult.ok(70, "1:10", 1.0), 2_000)
        assertFalse(increased.accepted)
        assertEquals("time-increased", increased.reason)
    }

    @Test
    fun `low confidence primary may safely hold the last accepted second`() {
        val filter = RecognitionFilter(minConfidence = 0.75, minAlternativeScore = 0.30)
        filter.update(RecognitionResult.ok(70, "1:10", 0.9), 0)

        val held = filter.update(
            RecognitionResult(
                ok = false,
                timeSeconds = 70,
                rawText = "1:10",
                confidence = 0.02,
                reason = "low-confidence",
                candidates = listOf(ClockCandidate(70, "1:10", 0.40, true))
            ),
            400
        )

        assertFalse(held.accepted)
        assertEquals("same-time", held.reason)
        assertEquals(70, held.timeSeconds)
    }

    @Test
    fun `accepts a higher clock as a new battle after the previous reading is stale`() {
        val filter = RecognitionFilter(minConfidence = 0.8, newBattleGapMs = 5_000)
        assertTrue(filter.update(RecognitionResult.ok(20, "0:20", 1.0), 0).accepted)

        val newBattle = filter.update(RecognitionResult.ok(77, "1:17", 1.0), 5_001)

        assertTrue(newBattle.accepted)
        assertEquals(77, newBattle.timeSeconds)
        assertEquals("1:17", newBattle.rawText)
    }

    @Test
    fun `same clock refreshes activity and prevents a false new battle reset`() {
        val filter = RecognitionFilter(minConfidence = 0.8, newBattleGapMs = 5_000)
        assertTrue(filter.update(RecognitionResult.ok(20, "0:20", 1.0), 0).accepted)
        assertEquals("same-time", filter.update(RecognitionResult.ok(20, "0:20", 1.0), 4_000).reason)

        val increased = filter.update(RecognitionResult.ok(77, "1:17", 1.0), 6_000)

        assertFalse(increased.accepted)
        assertEquals("time-increased", increased.reason)
    }

    @Test
    fun `accepts a structurally valid low confidence 1 30 as the opening anchor`() {
        val filter = RecognitionFilter(minConfidence = 0.8)

        val opening = filter.update(RecognitionResult.ok(90, "1:30", 0.25), 0)

        assertTrue(opening.accepted)
        assertEquals(90, opening.timeSeconds)
        assertEquals("1:30", opening.rawText)
    }

    @Test
    fun `does not accept low confidence 1 30 during an active battle`() {
        val filter = RecognitionFilter(minConfidence = 0.8)
        assertTrue(filter.update(RecognitionResult.ok(70, "1:10", 1.0), 0).accepted)

        val falseOpening = filter.update(RecognitionResult.ok(90, "1:30", 0.25), 1_000)

        assertFalse(falseOpening.accepted)
        assertEquals("low-confidence", falseOpening.reason)
    }
}
