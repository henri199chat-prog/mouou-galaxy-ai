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

    /** Auto-hide after this long without use (0 = never). */
    var hideAfterMs: Long
        get() = prefs.getLong(KEY_HIDE_AFTER, 0L)
        set(v) = prefs.edit().putLong(KEY_HIDE_AFTER, v).apply()

    /** Which side of the camera the docked pill sits on (spec §3 "Island": side setting, default right). */
    var side: PillSide
        get() = if (prefs.getString(KEY_SIDE, "right") == "left") PillSide.LEFT else PillSide.RIGHT
        set(v) = prefs.edit().putString(KEY_SIDE, if (v == PillSide.LEFT) "left" else "right").apply()

    /** Whether Ayman wants the island on: restored after a reboot or an update (spec §17 BootReceiver). */
    var islandOn: Boolean
        get() = prefs.getBoolean(KEY_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_ON, v).apply()

    private companion object {
        const val KEY_SIDE = "pill_side"
        const val KEY_ON = "island_on"
        const val KEY_HIDE_AFTER = "hide_after_ms"
        const val KEY_CUSTOM = "pill_custom"
        const val KEY_LEFT = "pill_left"
        const val KEY_TOP = "pill_top"
    }
}
