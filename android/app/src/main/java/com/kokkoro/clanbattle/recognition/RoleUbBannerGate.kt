package com.kokkoro.clanbattle.recognition

/**
 * Converts raw TP-release candidates into confirmed character-UB events.
 *
 * A candidate is confirmed only when a new UB-name banner cycle starts after
 * the TP release. A banner that was already visible may never confirm a later
 * TP drop, because that drop can be caused by another character or a boss UB.
 */
class RoleUbBannerGate(
    private val maxConfirmationDelayNanos: Long = DEFAULT_MAX_CONFIRMATION_DELAY_NANOS,
    private val quickRecoveryCancellationNanos: Long = DEFAULT_QUICK_RECOVERY_CANCELLATION_NANOS
) {
    private val pendingCandidates = mutableMapOf<CharacterRole, Long>()
    private val cycleCandidates = mutableMapOf<CharacterRole, Long>()
    private var pendingFlashCandidate: Pair<CharacterRole, Long>? = null
    private var cycleFlashCandidate: Pair<CharacterRole, Long>? = null
    private var bannerCycleOpen = false
    private var bannerCycleStartedAtNanos: Long? = null
    private var cycleConfirmed = false

    init {
        require(maxConfirmationDelayNanos > 0L)
        require(quickRecoveryCancellationNanos >= 0L)
    }

    private fun acceptLatePreBannerFlash(flashRoleTimesNanos: Map<CharacterRole, Long>) {
        val cycleStartedAt = bannerCycleStartedAtNanos ?: return
        latestFlash(flashRoleTimesNanos)
            ?.takeIf { (_, timestamp) ->
                !cycleConfirmed &&
                    timestamp <= cycleStartedAt &&
                    cycleStartedAt - timestamp <= maxConfirmationDelayNanos
            }
            ?.let { cycleFlashCandidate = it }
    }

    private fun latestFlash(values: Map<CharacterRole, Long>): Pair<CharacterRole, Long>? =
        values.maxByOrNull { (_, timestamp) -> timestamp }?.toPair()

    fun update(
        candidateTimesNanos: Map<CharacterRole, Long>,
        bannerRawPresent: Boolean,
        bannerActive: Boolean,
        bannerFrameTimestampNanos: Long,
        currentlyFullRoles: Set<CharacterRole> = emptySet(),
        flashRoleTimesNanos: Map<CharacterRole, Long> = emptyMap()
    ): Map<CharacterRole, Long> {
        val wasBannerCycleOpen = bannerCycleOpen
        val newBannerCycle = bannerRawPresent && !wasBannerCycleOpen

        if (wasBannerCycleOpen) {
            // The TP fast path can deliver a timestamped candidate one slow
            // recognition frame after the banner cycle opens. Accept it only
            // when its capture timestamp proves that the TP release happened
            // before the cycle started. Candidates genuinely captured during
            // the visible banner still belong to that animation and are dropped.
            val cycleStartedAt = bannerCycleStartedAtNanos
            if (!cycleConfirmed && cycleStartedAt != null) {
                candidateTimesNanos.forEach { (role, timestamp) ->
                    if (
                        timestamp <= cycleStartedAt &&
                        cycleStartedAt - timestamp <= maxConfirmationDelayNanos
                    ) {
                        cycleCandidates[role] = timestamp
                    }
                }
            }
            candidateTimesNanos.keys.forEach(pendingCandidates::remove)
            acceptLatePreBannerFlash(flashRoleTimesNanos)
        } else if (!newBannerCycle) {
            candidateTimesNanos.forEach { (role, timestamp) ->
                pendingCandidates[role] = timestamp
            }
            latestFlash(flashRoleTimesNanos)?.let { pendingFlashCandidate = it }
        }

        if (!wasBannerCycleOpen && !newBannerCycle) {
            cancelQuickRecoveries(
                candidates = pendingCandidates,
                currentlyFullRoles = currentlyFullRoles,
                currentCandidateRoles = candidateTimesNanos.keys,
                nowNanos = bannerFrameTimestampNanos
            )
            pruneExpired(pendingCandidates, bannerFrameTimestampNanos)
            pendingFlashCandidate = pendingFlashCandidate?.takeIf { (_, timestamp) ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            }
        }

        if (newBannerCycle) {
            bannerCycleStartedAtNanos = bannerFrameTimestampNanos
            candidateTimesNanos.forEach { (role, timestamp) ->
                if (timestamp <= bannerFrameTimestampNanos) pendingCandidates[role] = timestamp
            }
            pruneExpired(pendingCandidates, bannerFrameTimestampNanos)
            cycleCandidates.clear()
            cycleCandidates.putAll(pendingCandidates.filterValues { timestamp ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            })
            cycleFlashCandidate = latestFlash(flashRoleTimesNanos)
                ?.takeIf { (_, timestamp) -> timestamp <= bannerFrameTimestampNanos }
                ?: pendingFlashCandidate
            pendingCandidates.clear()
            pendingFlashCandidate = null
            cycleConfirmed = false
        }

        if (newBannerCycle || wasBannerCycleOpen) {
            cancelQuickRecoveries(
                candidates = cycleCandidates,
                currentlyFullRoles = currentlyFullRoles,
                currentCandidateRoles = candidateTimesNanos.keys,
                nowNanos = bannerFrameTimestampNanos
            )
        }

        // The initial UB flash can obscure several TP bars at once. Keep an
        // ambiguous candidate set alive for the current moving banner cycle;
        // once the false bars recover, confirm the one role that remains.
        val flashConfirmed = cycleFlashCandidate?.takeIf {
            !cycleConfirmed && (newBannerCycle || wasBannerCycleOpen)
        }
        val confirmed = when {
            flashConfirmed != null -> {
                cycleConfirmed = true
                mapOf(flashConfirmed.first to flashConfirmed.second)
            }
            !cycleConfirmed && cycleCandidates.size == 1 &&
                (newBannerCycle || wasBannerCycleOpen) -> {
                cycleConfirmed = true
                cycleCandidates.toMap()
            }
            else -> emptyMap()
        }

        bannerCycleOpen = bannerRawPresent || bannerActive
        if (!bannerCycleOpen) {
            cycleCandidates.clear()
            cycleFlashCandidate = null
            bannerCycleStartedAtNanos = null
            cycleConfirmed = false
        }
        return confirmed
    }

    fun reset() {
        pendingCandidates.clear()
        cycleCandidates.clear()
        pendingFlashCandidate = null
        cycleFlashCandidate = null
        bannerCycleOpen = false
        bannerCycleStartedAtNanos = null
        cycleConfirmed = false
    }

    private fun pruneExpired(candidates: MutableMap<CharacterRole, Long>, nowNanos: Long) {
        candidates.entries.removeAll { (_, timestamp) ->
            timestamp <= nowNanos && nowNanos - timestamp > maxConfirmationDelayNanos
        }
    }

    private fun cancelQuickRecoveries(
        candidates: MutableMap<CharacterRole, Long>,
        currentlyFullRoles: Set<CharacterRole>,
        currentCandidateRoles: Set<CharacterRole>,
        nowNanos: Long
    ) {
        candidates.entries.removeAll { (role, timestamp) ->
            role !in currentCandidateRoles &&
                role in currentlyFullRoles &&
                timestamp <= nowNanos &&
                nowNanos - timestamp <= quickRecoveryCancellationNanos
        }
    }

    companion object {
        // Screenshot sequences show that the skill-name wave starts almost
        // immediately after the TP release (roughly within a few hundred ms).
        // Keep scheduling headroom without allowing a stale TP drop to bind to
        // a much later character or BOSS skill banner.
        const val DEFAULT_MAX_CONFIRMATION_DELAY_NANOS = 2_000_000_000L

        // A second role can briefly read as empty during another character's
        // UB transition. If it returns to full almost immediately, revoke only
        // that false candidate while keeping the role whose TP stays low.
        const val DEFAULT_QUICK_RECOVERY_CANCELLATION_NANOS = 500_000_000L
    }
}
