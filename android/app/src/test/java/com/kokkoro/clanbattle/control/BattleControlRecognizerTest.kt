package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.capture.BattleReferenceRegions
import com.kokkoro.clanbattle.capture.ReferenceRegion
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.loadPngResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BattleControlRecognizerTest {
    @Test
    fun `paired score without enough margin is unknown`() {
        val observation = BattleControlRecognizer.classifyPair(
            onScore = 0.76,
            offScore = 0.73,
            minScore = 0.65,
            minMargin = 0.08
        )

        assertEquals(observation.describe(), VisualToggleState.UNKNOWN, observation.state)
    }

    @Test
    fun `role badge score uses an uncertainty band`() {
        val on = BattleControlRecognizer.classifyBadge(0.82, onThreshold = 0.75, offThreshold = 0.55)
        val off = BattleControlRecognizer.classifyBadge(0.42, onThreshold = 0.75, offThreshold = 0.55)
        val unknown = BattleControlRecognizer.classifyBadge(0.64, onThreshold = 0.75, offThreshold = 0.55)

        assertEquals(on.describe(), VisualToggleState.ON, on.state)
        assertEquals(off.describe(), VisualToggleState.OFF, off.state)
        assertEquals(unknown.describe(), VisualToggleState.UNKNOWN, unknown.state)
    }

    @Test
    fun `known mixed fixture reports auto and global off with roles one three five on`() {
        val result = recognizer().recognize(cropsFromReferenceFixture())
        val scores = result.describe()

        assertEquals(scores, VisualToggleState.OFF, result.auto.state)
        assertEquals(scores, VisualToggleState.OFF, result.globalSet.state)
        assertEquals(
            scores,
            listOf(
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON
            ),
            CharacterRole.entries.map { result.roles.getValue(it).state }
        )
    }

    @Test
    fun `global on with an explicitly off role is inconsistent`() {
        val crops = cropsFromReferenceFixture().copy(
            globalSet = loadBmpResource("control/templates/set_on.bmp")
        )

        val result = recognizer().recognize(crops)

        assertFalse(result.describe(), result.consistent)
        assertEquals("global-set-on-but-role-off:ROLE_2,ROLE_4", result.reason)
    }

    @Test
    fun `live enabled button crops are recognized as on`() {
        val onBadge = loadBmpResource("control/templates/set.bmp")
        val crops = ControlCrops(
            auto = loadPngResource("control/live_auto_on.png"),
            globalSet = loadPngResource("control/live_global_on.png"),
            roles = CharacterRole.entries.associateWith { onBadge }
        )

        val result = recognizer().recognize(crops)

        assertEquals(result.describe(), VisualToggleState.ON, result.auto.state)
        assertEquals(result.describe(), VisualToggleState.ON, result.globalSet.state)
    }

    private fun recognizer() = BattleControlRecognizer(
        BattleControlTemplates(
            autoOn = loadBmpResource("control/templates/auto_on.bmp"),
            autoOff = loadBmpResource("control/templates/auto_off.bmp"),
            globalSetOn = loadBmpResource("control/templates/set_on.bmp"),
            globalSetOff = loadBmpResource("control/templates/set_off.bmp"),
            roleSetOn = loadBmpResource("control/templates/set.bmp")
        )
    )

    private fun cropsFromReferenceFixture(): ControlCrops {
        val fixture = loadPngResource("control/set_on_off_on_off_on.png")
        return ControlCrops(
            auto = fixture.crop(BattleReferenceRegions.AUTO_BUTTON),
            globalSet = fixture.crop(BattleReferenceRegions.GLOBAL_SET_BUTTON),
            roles = BattleReferenceRegions.ROLE_SET_BADGES.mapValues { (_, region) -> fixture.crop(region) }
        )
    }

    private fun PixelImage.crop(region: ReferenceRegion): PixelImage =
        crop(region.x, region.y, region.width, region.height)

    private fun ToggleObservation.describe(): String =
        "state=$state onScore=$onScore offScore=$offScore margin=$margin"

    private fun BattleControlObservation.describe(): String = buildString {
        append("auto=").append(auto.describe())
        append(" global=").append(globalSet.describe())
        CharacterRole.entries.forEach { role ->
            append(' ').append(role).append('=').append(roles.getValue(role).describe())
        }
        append(" consistent=").append(consistent).append(" reason=").append(reason)
    }
}
