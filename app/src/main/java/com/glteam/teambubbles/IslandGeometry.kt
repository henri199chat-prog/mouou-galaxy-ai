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

    /**
     * The card for a pill anywhere on screen (spec §6: "expanded card below pill, or above if pill is low,
     * always on screen"). Never above the status bar's bottom + 4 dp, never over the navigation bar.
     */
    fun cardFor(pill: Box, screenWidth: Int, screenHeight: Int, statusBarBottom: Int, navBarTop: Int, density: Float): Box {
        val width = minOf(screenWidth - (16 * density).toInt(), (420 * density).toInt())
        val height = CardSize.heightPx(screenHeight)
        val gap = (8 * density).toInt()
        val edge = (8 * density).toInt()
        val left = (pill.centerX - width / 2).coerceIn(edge, (screenWidth - edge - width).coerceAtLeast(edge))
        var top = maxOf(pill.bottom + gap, statusBarBottom + (4 * density).toInt())
        if (top + height > navBarTop - gap) top = pill.top - gap - height // no room below: open above
        top = top.coerceAtLeast(statusBarBottom + (4 * density).toInt())
        return Box(left, top, left + width, top + height)
    }

    /** Dock without the accessibility overlay: just below the status bar, centred under the camera, so taps always land. */
    fun dockBelowStatusBar(screenWidth: Int, cutout: Box?, pillWidth: Int, pillHeight: Int, statusBarBottom: Int, gap: Int): Box {
        val cx = cutout?.centerX ?: (screenWidth / 2)
        val left = (cx - pillWidth / 2).coerceIn(0, (screenWidth - pillWidth).coerceAtLeast(0))
        val top = statusBarBottom + gap
        return Box(left, top, left + pillWidth, top + pillHeight)
    }

    /** Keeps a dragged pill fully on screen and above the navigation bar. */
    fun clampPill(pill: Box, screenWidth: Int, navBarTop: Int, edge: Int): Box = pill.moveTo(
        pill.left.coerceIn(0, (screenWidth - pill.width).coerceAtLeast(0)),
        pill.top.coerceIn(0, (navBarTop - edge - pill.height).coerceAtLeast(0)),
    )

    /** Magnet back to the camera: released within 70 dp (vertically) and 110 dp (horizontally) of the dock (spec §6). */
    fun shouldDock(pill: Box, dock: Box, density: Float): Boolean =
        kotlin.math.abs(pill.top - dock.top) <= 70 * density && kotlin.math.abs(pill.centerX - dock.centerX) <= 110 * density

    /** Linear blend from [a] (p = 0) to [b] (p = 1); p slightly above 1 extrapolates the overshoot. */
    fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
}
