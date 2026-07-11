package com.kokkoro.clanbattle.config

import android.content.Context

object AppPreferences {
    private const val FILE_NAME = "kokkoro_preferences"
    private const val KEY_AXIS_TEXT = "axis_text"
    private const val KEY_AXIS_NAME = "axis_name"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_CLOCK_DEBUG = "clock_debug"

    fun axisText(context: Context): String = prefs(context).getString(KEY_AXIS_TEXT, "").orEmpty()
    fun axisName(context: Context): String = prefs(context).getString(KEY_AXIS_NAME, "未选择").orEmpty()
    fun dryRun(context: Context): Boolean = prefs(context).getBoolean(KEY_DRY_RUN, true)
    fun clockDebugEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CLOCK_DEBUG, false)

    fun saveAxis(context: Context, name: String, text: String) {
        prefs(context).edit().putString(KEY_AXIS_NAME, name).putString(KEY_AXIS_TEXT, text).apply()
    }

    fun setDryRun(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRY_RUN, value).apply()
    }

    fun setClockDebugEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK_DEBUG, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
