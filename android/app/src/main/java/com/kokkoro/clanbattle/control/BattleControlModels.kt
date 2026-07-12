package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.PixelImage

enum class VisualToggleState {
    ON,
    OFF,
    UNKNOWN
}

data class ToggleObservation(
    val state: VisualToggleState,
    val onScore: Double,
    val offScore: Double? = null,
    val margin: Double = 0.0
)

data class ControlCrops(
    val auto: PixelImage,
    val globalSet: PixelImage,
    val roles: Map<CharacterRole, PixelImage>
)

data class BattleControlTemplates(
    val autoOn: PixelImage,
    val autoOff: PixelImage,
    val globalSetOn: PixelImage,
    val globalSetOff: PixelImage,
    val roleSetOn: PixelImage
)

data class BattleControlObservation(
    val auto: ToggleObservation,
    val globalSet: ToggleObservation,
    val roles: Map<CharacterRole, ToggleObservation>,
    val consistent: Boolean,
    val reason: String? = null
)
