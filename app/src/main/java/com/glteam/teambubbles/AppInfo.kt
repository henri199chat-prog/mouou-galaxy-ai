package com.glteam.teambubbles

/** Card height rule from the spec (§3, §5.3): about 13 % of the screen, hard max 15 %. */
object CardSize {
    const val TARGET_FRACTION = 0.13f
    const val MAX_FRACTION = 0.15f

    fun heightPx(screenHeightPx: Int): Int =
        (screenHeightPx * TARGET_FRACTION).toInt().coerceAtMost((screenHeightPx * MAX_FRACTION).toInt())
}
