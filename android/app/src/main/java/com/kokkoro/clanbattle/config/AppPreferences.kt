package com.kokkoro.clanbattle.config

import android.content.Context
import kotlin.math.roundToInt

object AppPreferences {
    private const val FILE_NAME = "kokkoro_preferences"
    private const val KEY_AXIS_TEXT = "axis_text"
    private const val KEY_AXIS_NAME = "axis_name"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_CLOCK_DEBUG = "clock_debug"
    private const val KEY_REGION_OVERLAY = "region_overlay"
    private const val KEY_SELECTED_AXIS_ID = "selected_axis_id"
    private const val KEY_AXIS_SELECTION_LOCKED = "axis_selection_locked"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_OVERLAY_MIN_X = "overlay_min_x"
    private const val KEY_OVERLAY_MIN_Y = "overlay_min_y"
    private const val KEY_ENERGY_FULL = "energy_full_threshold"
    private const val KEY_ENERGY_DROP = "energy_drop_threshold"
    private const val KEY_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS = "boss_ub_early_confirmation_hold_ms"

    const val DEFAULT_ENERGY_FULL_PERCENT = 97
    const val DEFAULT_ENERGY_DROP_PERCENT = 30
    const val DEFAULT_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS = 7_000
    const val MIN_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS = 3_000
    const val MAX_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS = 15_000
    private const val KEY_PAUSE_FRAME_MS = "pauseframe_frame_ms"
    private const val KEY_PAUSE_PRESET_A = "pauseframe_preset_a"
    private const val KEY_PAUSE_PRESET_B = "pauseframe_preset_b"
    private const val KEY_PAUSE_MENU_WAIT_MS = "pauseframe_menu_wait_ms"
    private const val KEY_STANDALONE_PAUSE_ENABLED = "standalone_pause_enabled"
    private const val KEY_STANDALONE_PAUSE_TIER1_RATE = "standalone_pause_tier1_rate_ms"
    private const val KEY_STANDALONE_PAUSE_TIER1_FRAMES = "standalone_pause_tier1_frames"
    private const val KEY_STANDALONE_PAUSE_TIER2_RATE = "standalone_pause_tier2_rate_ms"
    private const val KEY_STANDALONE_PAUSE_TIER2_FRAMES = "standalone_pause_tier2_frames"
    private const val KEY_STANDALONE_PAUSE_TIER3_RATE = "standalone_pause_tier3_rate_ms"
    private const val KEY_STANDALONE_PAUSE_TIER3_FRAMES = "standalone_pause_tier3_frames"
    private const val KEY_STANDALONE_PAUSE_X = "standalone_pause_x"
    private const val KEY_STANDALONE_PAUSE_Y = "standalone_pause_y"
    private const val KEY_STANDALONE_PAUSE_SCALE = "standalone_pause_scale"
    private const val KEY_STANDALONE_PAUSE_MIN_X = "standalone_pause_min_x"
    private const val KEY_STANDALONE_PAUSE_MIN_Y = "standalone_pause_min_y"

    const val DEFAULT_PAUSE_FRAME_MS = 40
    const val DEFAULT_PAUSE_PRESET_A = 5
    const val DEFAULT_PAUSE_PRESET_B = 20
    const val DEFAULT_PAUSE_MENU_WAIT_MS = 700
    const val DEFAULT_STANDALONE_PAUSE_RATE_MS = 40
    const val DEFAULT_STANDALONE_PAUSE_FRAMES = 20

    fun axisText(context: Context): String = prefs(context).getString(KEY_AXIS_TEXT, "").orEmpty()
    fun axisName(context: Context): String = prefs(context).getString(KEY_AXIS_NAME, "未选择").orEmpty()
    fun dryRun(context: Context): Boolean = prefs(context).getBoolean(KEY_DRY_RUN, true)
    fun clockDebugEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CLOCK_DEBUG, false)
    fun selectedAxisId(context: Context): String? = prefs(context).getString(KEY_SELECTED_AXIS_ID, null)
    fun axisSelectionLocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AXIS_SELECTION_LOCKED, false)

    fun saveAxis(context: Context, name: String, text: String) {
        prefs(context).edit().putString(KEY_AXIS_NAME, name).putString(KEY_AXIS_TEXT, text).apply()
    }

    fun setDryRun(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRY_RUN, value).apply()
    }

    fun setClockDebugEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK_DEBUG, value).apply()
    }

    /** 在游戏画面上层描出识别 ROI 与 TP 刻度，用于肉眼确认裁剪位置。 */
    fun regionOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REGION_OVERLAY, false)

    fun setRegionOverlayEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REGION_OVERLAY, value).apply()
    }

    fun setSelectedAxisId(context: Context, value: String?) {
        prefs(context).edit().apply {
            if (value == null) remove(KEY_SELECTED_AXIS_ID) else putString(KEY_SELECTED_AXIS_ID, value)
        }.apply()
    }

    fun setAxisSelectionLocked(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AXIS_SELECTION_LOCKED, value).apply()
    }

    fun overlayX(context: Context, fallback: Int): Int = prefs(context).getInt(KEY_OVERLAY_X, fallback)
    fun overlayY(context: Context, fallback: Int): Int = prefs(context).getInt(KEY_OVERLAY_Y, fallback)
    fun overlayScale(context: Context, fallback: Float): Float =
        prefs(context).getFloat(KEY_OVERLAY_SCALE, fallback)

    fun overlayMinimizedX(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY_OVERLAY_MIN_X) }?.getInt(KEY_OVERLAY_MIN_X, 0)

    fun overlayMinimizedY(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY_OVERLAY_MIN_Y) }?.getInt(KEY_OVERLAY_MIN_Y, 0)

    fun saveOverlayPanel(context: Context, x: Int, y: Int, scale: Float) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .putFloat(KEY_OVERLAY_SCALE, scale)
            .apply()
    }

    fun saveOverlayMinimized(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_MIN_X, x)
            .putInt(KEY_OVERLAY_MIN_Y, y)
            .apply()
    }

    /** UB 释放判定：某角色 TP 上一帧 ≥ 满 TP 值、当前帧 < 释放后 TP，记为释放了 UB。存 0~1 比例。 */
    fun energyFullThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_ENERGY_FULL, DEFAULT_ENERGY_FULL_PERCENT / 100f)

    fun energyDropThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_ENERGY_DROP, DEFAULT_ENERGY_DROP_PERCENT / 100f)

    fun energyFullPercent(context: Context): Int = (energyFullThreshold(context) * 100).roundToInt()
    fun energyDropPercent(context: Context): Int = (energyDropThreshold(context) * 100).roundToInt()

    fun saveEnergyThresholds(context: Context, percents: EnergyThresholdPercents) {
        prefs(context).edit()
            .putFloat(KEY_ENERGY_FULL, percents.full / 100f)
            .putFloat(KEY_ENERGY_DROP, percents.drop / 100f)
            .apply()
    }

    /** 同一倒计时停留多久后，可为无延迟 BOSS 节点发出提前确认。 */
    fun bossUbEarlyConfirmationHoldMs(context: Context): Int =
        prefs(context).getInt(
            KEY_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS,
            DEFAULT_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS
        )

    fun setBossUbEarlyConfirmationHoldMs(context: Context, value: Int) {
        require(value in MIN_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS..MAX_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS)
        prefs(context).edit().putInt(KEY_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS, value).apply()
    }

    /** 卡帧步进：单帧时长(ms) 与两个"释放N帧"预设档。 */
    fun pauseFrameMs(context: Context): Int = prefs(context).getInt(KEY_PAUSE_FRAME_MS, DEFAULT_PAUSE_FRAME_MS)
    fun pauseFramePresetA(context: Context): Int = prefs(context).getInt(KEY_PAUSE_PRESET_A, DEFAULT_PAUSE_PRESET_A)
    fun pauseFramePresetB(context: Context): Int = prefs(context).getInt(KEY_PAUSE_PRESET_B, DEFAULT_PAUSE_PRESET_B)

    /** 卡帧确定时，打开主菜单后等待多久再点头像（等菜单开启动画完成）。 */
    fun pauseFrameMenuWaitMs(context: Context): Int =
        prefs(context).getInt(KEY_PAUSE_MENU_WAIT_MS, DEFAULT_PAUSE_MENU_WAIT_MS)

    fun savePauseFrameSettings(context: Context, settings: PauseFrameSettings) {
        prefs(context).edit()
            .putInt(KEY_PAUSE_FRAME_MS, settings.frameMs)
            .putInt(KEY_PAUSE_PRESET_A, settings.presetA)
            .putInt(KEY_PAUSE_PRESET_B, settings.presetB)
            .putInt(KEY_PAUSE_MENU_WAIT_MS, settings.menuWaitMs)
            .apply()
    }

    /** 独立卡帧悬浮窗：三档各自独立的帧率(ms/帧)与帧数，与主面板卡帧步进设置互不影响。 */
    fun standalonePauseEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STANDALONE_PAUSE_ENABLED, false)

    fun setStandalonePauseEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_STANDALONE_PAUSE_ENABLED, value).apply()
    }

    fun standalonePauseTiers(context: Context): StandalonePauseSettings {
        val prefs = prefs(context)
        fun tier(rateKey: String, framesKey: String) = StandalonePauseTier(
            rateMs = prefs.getInt(rateKey, DEFAULT_STANDALONE_PAUSE_RATE_MS),
            frames = prefs.getInt(framesKey, DEFAULT_STANDALONE_PAUSE_FRAMES)
        )
        return StandalonePauseSettings(
            tier1 = tier(KEY_STANDALONE_PAUSE_TIER1_RATE, KEY_STANDALONE_PAUSE_TIER1_FRAMES),
            tier2 = tier(KEY_STANDALONE_PAUSE_TIER2_RATE, KEY_STANDALONE_PAUSE_TIER2_FRAMES),
            tier3 = tier(KEY_STANDALONE_PAUSE_TIER3_RATE, KEY_STANDALONE_PAUSE_TIER3_FRAMES)
        )
    }

    fun saveStandalonePauseTiers(context: Context, settings: StandalonePauseSettings) {
        prefs(context).edit()
            .putInt(KEY_STANDALONE_PAUSE_TIER1_RATE, settings.tier1.rateMs)
            .putInt(KEY_STANDALONE_PAUSE_TIER1_FRAMES, settings.tier1.frames)
            .putInt(KEY_STANDALONE_PAUSE_TIER2_RATE, settings.tier2.rateMs)
            .putInt(KEY_STANDALONE_PAUSE_TIER2_FRAMES, settings.tier2.frames)
            .putInt(KEY_STANDALONE_PAUSE_TIER3_RATE, settings.tier3.rateMs)
            .putInt(KEY_STANDALONE_PAUSE_TIER3_FRAMES, settings.tier3.frames)
            .apply()
    }

    fun standalonePauseX(context: Context, fallback: Int): Int =
        prefs(context).getInt(KEY_STANDALONE_PAUSE_X, fallback)

    fun standalonePauseY(context: Context, fallback: Int): Int =
        prefs(context).getInt(KEY_STANDALONE_PAUSE_Y, fallback)

    fun standalonePauseScale(context: Context, fallback: Float): Float =
        prefs(context).getFloat(KEY_STANDALONE_PAUSE_SCALE, fallback)

    fun standalonePauseMinimizedX(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY_STANDALONE_PAUSE_MIN_X) }
            ?.getInt(KEY_STANDALONE_PAUSE_MIN_X, 0)

    fun standalonePauseMinimizedY(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY_STANDALONE_PAUSE_MIN_Y) }
            ?.getInt(KEY_STANDALONE_PAUSE_MIN_Y, 0)

    fun saveStandalonePausePanel(context: Context, x: Int, y: Int, scale: Float) {
        prefs(context).edit()
            .putInt(KEY_STANDALONE_PAUSE_X, x)
            .putInt(KEY_STANDALONE_PAUSE_Y, y)
            .putFloat(KEY_STANDALONE_PAUSE_SCALE, scale)
            .apply()
    }

    fun saveStandalonePauseMinimized(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_STANDALONE_PAUSE_MIN_X, x)
            .putInt(KEY_STANDALONE_PAUSE_MIN_Y, y)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

data class EnergyThresholdPercents(val full: Int, val drop: Int)

data class PauseFrameSettings(val frameMs: Int, val presetA: Int, val presetB: Int, val menuWaitMs: Int)

/** 独立卡帧悬浮窗的一档：帧率(ms/帧) 与前进帧数。 */
data class StandalonePauseTier(val rateMs: Int, val frames: Int)

data class StandalonePauseSettings(
    val tier1: StandalonePauseTier,
    val tier2: StandalonePauseTier,
    val tier3: StandalonePauseTier
)

/**
 * 校验卡帧设置：单帧时长 5~500ms、两档帧数各 1~600、菜单等待 100~3000ms。合法返回 [PauseFrameSettings]，否则 null。
 */
fun parsePauseFrameSettings(msText: String, aText: String, bText: String, menuWaitText: String): PauseFrameSettings? {
    val ms = msText.trim().toIntOrNull() ?: return null
    val a = aText.trim().toIntOrNull() ?: return null
    val b = bText.trim().toIntOrNull() ?: return null
    val menuWait = menuWaitText.trim().toIntOrNull() ?: return null
    if (ms !in 5..500) return null
    if (a !in 1..600) return null
    if (b !in 1..600) return null
    if (menuWait !in 100..3000) return null
    return PauseFrameSettings(ms, a, b, menuWait)
}

/**
 * 校验独立卡帧悬浮窗的一档设置：帧率 5~500ms、帧数 1~600。合法返回 [StandalonePauseTier]，否则 null。
 */
fun parseStandalonePauseTier(rateText: String, framesText: String): StandalonePauseTier? {
    val rate = rateText.trim().toIntOrNull() ?: return null
    val frames = framesText.trim().toIntOrNull() ?: return null
    if (rate !in 5..500) return null
    if (frames !in 1..600) return null
    return StandalonePauseTier(rate, frames)
}

/**
 * 校验 UB 阈值输入（百分比）：满 TP 值 50~100、释放后 TP 1~95、且满 TP 值至少高出释放后 TP 5 个百分点（保留滞回带）。
 * 合法返回 [EnergyThresholdPercents]，否则返回 null。
 */
fun parseEnergyThresholdPercents(fullText: String, dropText: String): EnergyThresholdPercents? {
    val full = fullText.trim().toIntOrNull() ?: return null
    val drop = dropText.trim().toIntOrNull() ?: return null
    if (full !in 50..100) return null
    if (drop !in 1..95) return null
    if (full - drop < 5) return null
    return EnergyThresholdPercents(full, drop)
}

fun parseBossUbEarlyConfirmationHoldMs(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf {
        it in AppPreferences.MIN_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS..
            AppPreferences.MAX_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS
    }
