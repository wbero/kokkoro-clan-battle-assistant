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
    // Keep a bounded history of every recent release role in addition to the
    // newest causal cohort. The normal TP path still uses only the newest
    // cohort, but a high-strength portrait flash whose margin narrowly misses
    // the normal threshold may use this history as independent corroboration.
    private val pendingCandidateHistory = mutableMapOf<CharacterRole, Long>()
    private val cycleCandidateHistory = mutableMapOf<CharacterRole, Long>()
    private var pendingFlashCandidate: Pair<CharacterRole, Long>? = null
    private var cycleFlashCandidate: Pair<CharacterRole, Long>? = null
    private var pendingBorderlineFlashCandidate: Pair<CharacterRole, Long>? = null
    private var cycleBorderlineFlashCandidate: Pair<CharacterRole, Long>? = null
    private var bannerCycleOpen = false
    private var bannerCycleStartedAtNanos: Long? = null
    private var cycleConfirmed = false

    init {
        require(maxConfirmationDelayNanos > 0L)
        require(quickRecoveryCancellationNanos >= 0L)
    }

    private fun acceptLatePreBannerFlash(
        flashRoleTimesNanos: Map<CharacterRole, Long>,
        borderlineFlashRoleTimesNanos: Map<CharacterRole, Long>
    ) {
        val cycleStartedAt = bannerCycleStartedAtNanos ?: return
        latestFlash(flashRoleTimesNanos)
            ?.takeIf { (_, timestamp) ->
                !cycleConfirmed &&
                    timestamp <= cycleStartedAt &&
                    cycleStartedAt - timestamp <= maxConfirmationDelayNanos
            }
            ?.let { cycleFlashCandidate = it }
        latestFlash(borderlineFlashRoleTimesNanos)
            ?.takeIf { (_, timestamp) ->
                !cycleConfirmed &&
                    timestamp <= cycleStartedAt &&
                    cycleStartedAt - timestamp <= maxConfirmationDelayNanos
            }
            ?.let { cycleBorderlineFlashCandidate = it }
    }

    private fun latestFlash(values: Map<CharacterRole, Long>): Pair<CharacterRole, Long>? =
        values.maxByOrNull { (_, timestamp) -> timestamp }?.toPair()

    private fun mergeCandidateHistory(
        target: MutableMap<CharacterRole, Long>,
        values: Map<CharacterRole, Long>,
        latestAllowedTimestamp: Long = Long.MAX_VALUE
    ) {
        values.forEach { (role, timestamp) ->
            if (timestamp > latestAllowedTimestamp) return@forEach
            val current = target[role]
            if (current == null || timestamp > current) target[role] = timestamp
        }
    }

    private fun isCorroboratedBorderlineFlash(
        flash: Pair<CharacterRole, Long>,
        candidateHistory: Map<CharacterRole, Long>
    ): Boolean {
        val candidateAt = candidateHistory[flash.first] ?: return false
        return candidateAt <= flash.second && flash.second - candidateAt <= maxConfirmationDelayNanos
    }

    private fun latestReleaseCohort(
        values: Map<CharacterRole, Long>,
        latestAllowedTimestamp: Long = Long.MAX_VALUE
    ): Map<CharacterRole, Long> {
        val latestTimestamp = values.values
            .filter { it <= latestAllowedTimestamp }
            .maxOrNull()
            ?: return emptyMap()
        return values.filterValues { timestamp -> timestamp == latestTimestamp }
    }

    private fun mergeLatestReleaseCohort(
        target: MutableMap<CharacterRole, Long>,
        values: Map<CharacterRole, Long>,
        latestAllowedTimestamp: Long = Long.MAX_VALUE
    ) {
        val cohort = latestReleaseCohort(values, latestAllowedTimestamp)
        if (cohort.isEmpty()) return
        val incomingTimestamp = cohort.values.first()
        val currentTimestamp = target.values.maxOrNull()
        when {
            currentTimestamp == null || incomingTimestamp > currentTimestamp -> {
                target.clear()
                target.putAll(cohort)
            }
            incomingTimestamp == currentTimestamp -> target.putAll(cohort)
        }
    }

    fun update(
        candidateTimesNanos: Map<CharacterRole, Long>,
        bannerRawPresent: Boolean,
        bannerActive: Boolean,
        bannerFrameTimestampNanos: Long,
        currentlyFullRoles: Set<CharacterRole> = emptySet(),
        flashRoleTimesNanos: Map<CharacterRole, Long> = emptyMap(),
        borderlineFlashRoleTimesNanos: Map<CharacterRole, Long> = emptyMap()
    ): Map<CharacterRole, Long> {
        val wasBannerCycleOpen = bannerCycleOpen
        val newBannerCycle = bannerRawPresent && !wasBannerCycleOpen

        if (!wasBannerCycleOpen) {
            mergeCandidateHistory(
                pendingCandidateHistory,
                candidateTimesNanos,
                bannerFrameTimestampNanos
            )
        }

        if (wasBannerCycleOpen) {
            // The TP fast path can deliver a timestamped candidate one slow
            // recognition frame after the banner cycle opens. Accept it only
            // when its capture timestamp proves that the TP release happened
            // before the cycle started. Candidates genuinely captured during
            // the visible banner still belong to that animation and are dropped.
            val cycleStartedAt = bannerCycleStartedAtNanos
            if (!cycleConfirmed && cycleStartedAt != null) {
                val eligible = candidateTimesNanos.filterValues { timestamp ->
                    timestamp <= cycleStartedAt &&
                        cycleStartedAt - timestamp <= maxConfirmationDelayNanos
                }
                mergeLatestReleaseCohort(cycleCandidates, eligible, cycleStartedAt)
                mergeCandidateHistory(cycleCandidateHistory, eligible, cycleStartedAt)
            }
            candidateTimesNanos.keys.forEach(pendingCandidates::remove)
            acceptLatePreBannerFlash(flashRoleTimesNanos, borderlineFlashRoleTimesNanos)
        } else if (!newBannerCycle) {
            if (candidateTimesNanos.isNotEmpty()) {
                // EnergySampleBuffer can deliver several high-frequency release
                // events together on one slower recognition frame. Preserve the
                // true capture timestamps: only the newest sampling instant is
                // causal for the upcoming skill-name banner. Exact timestamp ties
                // remain ambiguous because they came from the same TP sample.
                mergeLatestReleaseCohort(pendingCandidates, candidateTimesNanos)
            }
            latestFlash(flashRoleTimesNanos)?.let { pendingFlashCandidate = it }
            latestFlash(borderlineFlashRoleTimesNanos)?.let {
                pendingBorderlineFlashCandidate = it
            }
        }

        if (!wasBannerCycleOpen && !newBannerCycle) {
            cancelQuickRecoveries(
                candidates = pendingCandidates,
                currentlyFullRoles = currentlyFullRoles,
                currentCandidateRoles = candidateTimesNanos.keys,
                nowNanos = bannerFrameTimestampNanos
            )
            cancelQuickRecoveries(
                candidates = pendingCandidateHistory,
                currentlyFullRoles = currentlyFullRoles,
                currentCandidateRoles = candidateTimesNanos.keys,
                nowNanos = bannerFrameTimestampNanos
            )
            pruneExpired(pendingCandidates, bannerFrameTimestampNanos)
            pruneExpired(pendingCandidateHistory, bannerFrameTimestampNanos)
            pendingFlashCandidate = pendingFlashCandidate?.takeIf { (_, timestamp) ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            }
            pendingBorderlineFlashCandidate = pendingBorderlineFlashCandidate?.takeIf { (_, timestamp) ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            }
        }

        if (newBannerCycle) {
            bannerCycleStartedAtNanos = bannerFrameTimestampNanos
            if (candidateTimesNanos.isNotEmpty()) {
                mergeLatestReleaseCohort(
                    pendingCandidates,
                    candidateTimesNanos,
                    bannerFrameTimestampNanos
                )
            }
            pruneExpired(pendingCandidates, bannerFrameTimestampNanos)
            pruneExpired(pendingCandidateHistory, bannerFrameTimestampNanos)
            cycleCandidates.clear()
            cycleCandidates.putAll(pendingCandidates.filterValues { timestamp ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            })
            cycleCandidateHistory.clear()
            cycleCandidateHistory.putAll(pendingCandidateHistory.filterValues { timestamp ->
                timestamp <= bannerFrameTimestampNanos &&
                    bannerFrameTimestampNanos - timestamp <= maxConfirmationDelayNanos
            })
            cycleFlashCandidate = latestFlash(flashRoleTimesNanos)
                ?.takeIf { (_, timestamp) -> timestamp <= bannerFrameTimestampNanos }
                ?: pendingFlashCandidate
            cycleBorderlineFlashCandidate = latestFlash(borderlineFlashRoleTimesNanos)
                ?.takeIf { (_, timestamp) -> timestamp <= bannerFrameTimestampNanos }
                ?: pendingBorderlineFlashCandidate
            pendingCandidates.clear()
            pendingCandidateHistory.clear()
            pendingFlashCandidate = null
            pendingBorderlineFlashCandidate = null
            cycleConfirmed = false
        }

        if (newBannerCycle || wasBannerCycleOpen) {
            cancelQuickRecoveries(
                candidates = cycleCandidates,
                currentlyFullRoles = currentlyFullRoles,
                currentCandidateRoles = candidateTimesNanos.keys,
                nowNanos = bannerFrameTimestampNanos
            )
            cancelQuickRecoveries(
                candidates = cycleCandidateHistory,
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
        val borderlineFlashConfirmed = cycleBorderlineFlashCandidate?.takeIf { flash ->
            !cycleConfirmed &&
                (newBannerCycle || wasBannerCycleOpen) &&
                isCorroboratedBorderlineFlash(flash, cycleCandidateHistory)
        }
        val confirmed = when {
            flashConfirmed != null -> {
                cycleConfirmed = true
                mapOf(flashConfirmed.first to flashConfirmed.second)
            }
            borderlineFlashConfirmed != null -> {
                cycleConfirmed = true
                mapOf(borderlineFlashConfirmed.first to borderlineFlashConfirmed.second)
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
            cycleCandidateHistory.clear()
            cycleFlashCandidate = null
            cycleBorderlineFlashCandidate = null
            bannerCycleStartedAtNanos = null
            cycleConfirmed = false
        }
        return confirmed
    }

    fun reset() {
        pendingCandidates.clear()
        cycleCandidates.clear()
        pendingCandidateHistory.clear()
        cycleCandidateHistory.clear()
        pendingFlashCandidate = null
        cycleFlashCandidate = null
        pendingBorderlineFlashCandidate = null
        cycleBorderlineFlashCandidate = null
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
