package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.PauseFrameTarget
import com.kokkoro.clanbattle.recognition.CharacterRole

/**
 * Battle SET/AUTO truth owned by the automation after the first visual seed.
 *
 * The game does not mutate SET/AUTO by itself: every automatic control tap is a
 * deterministic toggle.  Visual recognition is therefore used to establish the
 * initial state and to audit for desynchronisation, but it must not overwrite the
 * predicted state on a single animation-corrupted frame.
 */
class AuthoritativeControlState(
    private val visualMismatchFramesBeforeDesync: Int = DEFAULT_VISUAL_MISMATCH_FRAMES
) {
    private var current: BattleControlState? = null
    private var visualMismatchFrames = 0

    init {
        require(visualMismatchFramesBeforeDesync >= 1)
    }

    fun reset() {
        current = null
        visualMismatchFrames = 0
    }

    /** Seed exactly once from a fully known, trustworthy visual observation. */
    fun seedIfAbsent(state: BattleControlState?): Boolean {
        if (current != null || state == null || !state.isFullyKnown()) return false
        current = state
        visualMismatchFrames = 0
        return true
    }

    /** Explicit re-seed after a user-owned interaction/recovery boundary. */
    fun reseed(state: BattleControlState?) {
        current = state?.takeIf { it.isFullyKnown() }
        visualMismatchFrames = 0
    }

    fun snapshot(): BattleControlState? = current

    fun apply(action: ControlAction): BattleControlState? {
        val before = current ?: return null
        current = before.after(action)
        visualMismatchFrames = 0
        return current
    }

    fun apply(actions: Iterable<ControlAction>): BattleControlState? {
        actions.forEach(::apply)
        return current
    }

    /** The automatic pause menu performs the same deterministic toggles as battle controls. */
    fun applyPauseFrameTarget(target: PauseFrameTarget): BattleControlState? = when (target) {
        is PauseFrameTarget.Role -> apply(ControlAction.TapRole(target.role))
        PauseFrameTarget.Auto -> apply(ControlAction.TapAuto)
    }

    /** All automatic control operations are toggles, so the same action is its inverse. */
    fun rollback(action: ControlAction): BattleControlState? = apply(action)

    fun assumeRole(role: CharacterRole, state: VisualToggleState): BattleControlState? {
        require(state != VisualToggleState.UNKNOWN)
        val before = current ?: return null
        val roles = before.roles.toMutableMap().apply { this[role] = state }
        current = before.copy(
            globalSet = if (roles.values.all { it == VisualToggleState.ON }) {
                VisualToggleState.ON
            } else {
                VisualToggleState.OFF
            },
            roles = roles
        )
        visualMismatchFrames = 0
        return current
    }

    /**
     * Return the deterministic toggles needed to reach [target] from the internal
     * state.  SET changes stay before AUTO to preserve existing execution order.
     */
    fun actionsTo(target: OpeningControlTarget): List<ControlAction> {
        val state = current ?: return emptyList()
        return target.actionsFrom(state)
    }

    /**
     * Visual recognition is only an audit path.  A single disagreement is ignored;
     * several consecutive clean trustworthy disagreements report desynchronisation.
     */
    fun audit(visual: BattleControlState?, allowed: Boolean): ControlStateAudit {
        val expected = current
        if (!allowed || expected == null || visual == null || !visual.isFullyKnown()) {
            // A visual audit is intentionally scoped to one short post-click
            // verification window.  UB/menu/ordinary battle frames outside that
            // window must not contribute stale mismatch counts to a later action.
            visualMismatchFrames = 0
            return ControlStateAudit(expected, visual, 0, desynchronized = false)
        }
        if (expected == visual) {
            visualMismatchFrames = 0
            return ControlStateAudit(expected, visual, 0, desynchronized = false)
        }
        visualMismatchFrames++
        return ControlStateAudit(
            expected = expected,
            visual = visual,
            mismatchFrames = visualMismatchFrames,
            desynchronized = visualMismatchFrames >= visualMismatchFramesBeforeDesync
        )
    }

    private fun BattleControlState.after(action: ControlAction): BattleControlState = when (action) {
        ControlAction.TapAuto -> copy(auto = auto.toggled())
        ControlAction.TapGlobalSet -> {
            val next = if (globalSet == VisualToggleState.ON) VisualToggleState.OFF else VisualToggleState.ON
            copy(
                globalSet = next,
                roles = CharacterRole.entries.associateWith { next }
            )
        }
        is ControlAction.TapRole -> {
            val roles = roles.toMutableMap().apply {
                this[action.role] = getValue(action.role).toggled()
            }
            copy(
                globalSet = if (roles.values.all { it == VisualToggleState.ON }) {
                    VisualToggleState.ON
                } else {
                    VisualToggleState.OFF
                },
                roles = roles
            )
        }
        ControlAction.None,
        ControlAction.TapMenu -> this
    }

    private fun BattleControlState.isFullyKnown(): Boolean =
        auto != VisualToggleState.UNKNOWN &&
            globalSet != VisualToggleState.UNKNOWN &&
            roles.keys == CharacterRole.entries.toSet() &&
            roles.values.none { it == VisualToggleState.UNKNOWN }

    private fun VisualToggleState.toggled(): VisualToggleState = when (this) {
        VisualToggleState.ON -> VisualToggleState.OFF
        VisualToggleState.OFF -> VisualToggleState.ON
        VisualToggleState.UNKNOWN -> error("不能反转未知控制状态")
    }

    companion object {
        const val DEFAULT_VISUAL_MISMATCH_FRAMES = 3
    }
}

data class ControlStateAudit(
    val expected: BattleControlState?,
    val visual: BattleControlState?,
    val mismatchFrames: Int,
    val desynchronized: Boolean
)

internal fun BattleControlObservation.toControlState(): BattleControlState = BattleControlState(
    auto = auto.state,
    globalSet = globalSet.state,
    roles = roles.mapValues { it.value.state }
)

internal fun OpeningControlTarget.actionsFrom(current: BattleControlState): List<ControlAction> {
    val actions = mutableListOf<ControlAction>()
    roles?.let { wantedRoles ->
        val allWantedOn = wantedRoles.values.all { it == VisualToggleState.ON }
        val allWantedOff = wantedRoles.values.all { it == VisualToggleState.OFF }
        when {
            allWantedOn && current.globalSet != VisualToggleState.ON ->
                actions += ControlAction.TapGlobalSet

            allWantedOff && current.globalSet == VisualToggleState.ON ->
                actions += ControlAction.TapGlobalSet

            else -> CharacterRole.entries.forEach { role ->
                if (current.roles.getValue(role) != wantedRoles.getValue(role)) {
                    actions += ControlAction.TapRole(role)
                }
            }
        }
    }
    auto?.let { wanted ->
        if (current.auto != wanted) actions += ControlAction.TapAuto
    }
    return actions
}
