package com.glteam.teambubbles

import android.content.Context

/** Where Ayman left the pill (spec §3 "Moving": persisted). Null = docked beside the camera. */
class IslandPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun customPill(width: Int, height: Int): Box? {
        if (!prefs.getBoolean(KEY_CUSTOM, false)) return null
        val left = prefs.getInt(KEY_LEFT, 0)
        val top = prefs.getInt(KEY_TOP, 0)
        return Box(left, top, left + width, top + height)
    }

    fun saveCustom(pill: Box) = prefs.edit().putBoolean(KEY_CUSTOM, true).putInt(KEY_LEFT, pill.left).putInt(KEY_TOP, pill.top).apply()

    fun saveDocked() = prefs.edit().putBoolean(KEY_CUSTOM, false).apply()

    private companion object {
        const val KEY_CUSTOM = "pill_custom"
        const val KEY_LEFT = "pill_left"
        const val KEY_TOP = "pill_top"
    }
}
