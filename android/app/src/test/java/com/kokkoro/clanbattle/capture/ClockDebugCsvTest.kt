package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.DigitRecognitionTrace
import com.kokkoro.clanbattle.recognition.DigitSlot
import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.ScoreKind
import com.kokkoro.clanbattle.recognition.CharacterEnergyState
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlState
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.ToggleObservation
import com.kokkoro.clanbattle.control.VisualToggleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ClockDebugCsvTest {
    @Test fun `control diagnostic row matches header`() {
        val roles = CharacterRole.entries.associateWith { VisualToggleState.ON }
        val observation = BattleControlObservation(
            auto = ToggleObservation(VisualToggleState.ON, 0.91, 0.12, 0.79),
            globalSet = ToggleObservation(VisualToggleState.OFF, 0.20, 0.88, 0.68),
            roles = CharacterRole.entries.associateWith { ToggleObservation(VisualToggleState.ON, 0.9) },
            consistent = true
        )
        val step = ControlStep(
            action = ControlAction.TapRole(CharacterRole.ROLE_2),
            reason = "test",
            observed = BattleControlState(VisualToggleState.ON, VisualToggleState.OFF, roles),
            desired = OpeningControlTarget(roles = roles),
            expected = BattleControlState(VisualToggleState.ON, VisualToggleState.OFF, roles),
            safety = ControlSafetyState.SAFETY_PAUSED,
            retryCount = 1
        )

        val values = ClockDebugCsv.controlValues(12, 34, observation, step, 0.82, "controls-12")
        val columns = ClockDebugCsv.CONTROL_HEADER.split(',')
        val row = columns.zip(values.map(Any?::toString)).toMap()

        assertEquals(columns.size, values.size)
        assertEquals("ON", row.getValue("autoState"))
        assertEquals("TapRole:ROLE_2", row.getValue("action"))
        assertEquals("SAFETY_PAUSED", row.getValue("safetyState"))
        assertEquals("controls-12", row.getValue("cropPrefix"))
    }

    @Test fun `energy row exactly matches stable per-role column contract`() {
        val result = EnergyDetectionResult(
            characters = CharacterRole.entries.associateWith { role ->
                CharacterEnergyState(role.ordinal / 10f, role == CharacterRole.ROLE_1, role.ordinal / 100f, role == CharacterRole.ROLE_3)
            },
            energyDelta = 0.25f,
            triggeredRoles = setOf(CharacterRole.ROLE_3)
        )
        val columns = ClockDebugCsv.ENERGY_HEADER.split(',')
        val values = ClockDebugCsv.energyValues(12, 34, result)
        val row = columns.zip(values.map(Any?::toString)).toMap()

        assertEquals(columns.size, values.size)
        assertEquals("0.25", row.getValue("energyDelta"))
        assertEquals("ROLE_3", row.getValue("triggeredRoles"))
        assertEquals("0.4", row.getValue("role5Ratio"))
        assertEquals("true", row.getValue("role1Full"))
        assertEquals("true", row.getValue("role3Triggered"))
    }
    @Test fun `frames header and values are escaped`() {
        val out = StringWriter()
        val csv = ClockDebugCsv(out, ClockDebugCsv.FRAME_HEADER)
        csv.write(listOf("1", "raw,clock", "reason \"quoted\""))
        assertEquals(
            "${ClockDebugCsv.FRAME_HEADER}\n1,\"raw,clock\",\"reason \"\"quoted\"\"\"\n",
            out.toString()
        )
    }

    @Test fun `digit header contains decision and ncc score columns`() {
        val columns = ClockDebugCsv.DIGIT_HEADER.split(',')
        assertEquals((0..9).map { "decision$it" }, columns.filter { it.matches(Regex("decision\\d")) })
        assertEquals((0..9).map { "ncc$it" }, columns.filter { it.matches(Regex("ncc\\d")) })
        assertTrue("scoreKind" in columns)
    }

    @Test fun `digit row exactly matches header order and column count`() {
        val trace = DigitRecognitionTrace(
            slot = DigitSlot.SECOND_ONES,
            crop = PixelImage(1, 1, intArrayOf(0)),
            allowedRange = 0..9,
            decisionScores = (0..9).associateWith { it + 0.1 },
            nccScores = (0..9).associateWith { it + 0.01 },
            scoreKind = ScoreKind.STRUCTURAL_IOU,
            rawTop1 = 6,
            rawTop2 = 0,
            rawMargin = 0.2,
            chosen = 6,
            chosenScore = 0.9,
            decisionMargin = 0.2,
            decisionRule = "test"
        )

        val columns = ClockDebugCsv.DIGIT_HEADER.split(',')
        val values = ClockDebugCsv.digitValues(12, 34, trace, "crop.png")
        val row = columns.zip(values.map(Any?::toString)).toMap()

        assertEquals(columns.size, values.size)
        assertEquals("STRUCTURAL_IOU", row.getValue("scoreKind"))
        (0..9).forEach { digit ->
            assertEquals((digit + 0.1).toString(), row.getValue("decision$digit"))
            assertEquals((digit + 0.01).toString(), row.getValue("ncc$digit"))
        }
        assertEquals("crop.png", row.getValue("cropFile"))
    }

    @Test fun `close prevents later writes`() {
        val out = StringWriter()
        val csv = ClockDebugCsv(out, "a")
        csv.close()
        assertFalse(csv.write(listOf("later")))
        assertEquals("a\n", out.toString())
        assertTrue(csv.isClosed)
    }
}
