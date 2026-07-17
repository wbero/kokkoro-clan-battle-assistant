package com.kokkoro.clanbattle.config

import android.content.Context
import kotlin.math.roundToInt

object AppPreferences {
    private const val FILE_NAME = "kokkoro_preferences"
    private const val KEY_AXIS_TEXT = "axis_text"
    private const val KEY_AXIS_NAME = "axis_name"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_CLOCK_DEBUG = "clock_debug"
    private const val KEY_SELECTED_AXIS_ID = "selected_axis_id"
    private const val KEY_AXIS_SELECTION_LOCKED = "axis_selection_locked"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_OVERLAY_MIN_X = "overlay_min_x"
    private const val KEY_OVERLAY_MIN_Y = "overlay_min_y"
    private const val KEY_ENERGY_FULL = "energy_full_threshold"
    private const val KEY_ENERGY_DROP = "energy_drop_threshold"

    const val DEFAULT_ENERGY_FULL_PERCENT = 97
    const val DEFAULT_ENERGY_DROP_PERCENT = 30

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

    private fun prefs(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

data class EnergyThresholdPercents(val full: Int, val drop: Int)

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
