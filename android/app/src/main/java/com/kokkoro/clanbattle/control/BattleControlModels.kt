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

data class BattleControlState(
    val auto: VisualToggleState,
    val globalSet: VisualToggleState,
    val roles: Map<CharacterRole, VisualToggleState>
)

sealed interface ControlAction {
    data object None : ControlAction
    data object TapAuto : ControlAction
    data object TapGlobalSet : ControlAction
    data class TapRole(val role: CharacterRole) : ControlAction
    data object TapMenu : ControlAction
}

enum class ControlSafetyState { RUNNING, SAFETY_PAUSING, SAFETY_PAUSED }

data class ControlStep(
    val action: ControlAction,
    val reason: String,
    val observed: BattleControlState?,
    val desired: OpeningControlTarget?,
    val expected: BattleControlState?,
    val safety: ControlSafetyState,
    val retryCount: Int = 0,
    val confirmed: Boolean = false
)
