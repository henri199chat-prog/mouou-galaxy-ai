package com.glteam.teambubbles

/** Where the expanded card goes and how big the window must be (spec §3 "Tap → card", §5.3). */
object IslandGeometry {

    /**
     * The chat card: width screen − 16 dp (at most 420 dp), height ≈ 13 % of the screen (max 15 %),
     * starting 4 dp BELOW the status bar so the clock, battery and notifications stay visible.
     */
    fun card(screenWidth: Int, screenHeight: Int, statusBarBottom: Int, density: Float): Box {
        val width = minOf(screenWidth - (16 * density).toInt(), (420 * density).toInt())
        val height = CardSize.heightPx(screenHeight)
        val left = (screenWidth - width) / 2
        val top = statusBarBottom + (4 * density).toInt()
        return Box(left, top, left + width, top + height)
    }

    /**
     * The expanded window: covers the pill and the card plus [margin] for the spring's overshoot and glow.
     * Resized to this BEFORE the first expanded frame is drawn (spec §5.5, blank-frame bug).
     */
    fun expandedWindow(pill: Box, card: Box, margin: Int, screenWidth: Int): Box = Box(
        left = (minOf(pill.left, card.left) - margin).coerceAtLeast(0),
        top = (minOf(pill.top, card.top) - margin).coerceAtLeast(0),
        right = (maxOf(pill.right, card.right) + margin).coerceAtMost(screenWidth),
        bottom = maxOf(pill.bottom, card.bottom) + margin,
    )

    /** Linear blend from [a] (p = 0) to [b] (p = 1); p slightly above 1 extrapolates the overshoot. */
    fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
}
