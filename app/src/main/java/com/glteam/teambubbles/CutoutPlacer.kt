package com.glteam.teambubbles

/** Which side of the front camera the pill sits on (spec §3 "Island": side setting, default right). */
enum class PillSide { LEFT, RIGHT }

/** A rectangle in absolute screen pixels. Plain data so the placement maths can be unit-tested off-device. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
    fun inflate(m: Int) = Box(left - m, top - m, right + m, bottom + m)
    fun moveTo(newLeft: Int, newTop: Int) = Box(newLeft, newTop, newLeft + width, newTop + height)
}

/**
 * Places the idle pill beside the camera cutout, never over the hole (taps on the hole misfire), with an
 * 8 dp gap. Returns the WINDOW box (pill + touch margin on every side) in absolute LEFT/TOP coordinates:
 * gravity is always TOP|LEFT, never START/END, because RTL mirrors those (spec §5.1, bug 2).
 */
object CutoutPlacer {
    fun idleWindow(
        screenWidth: Int,
        cutout: Box?,
        pillWidth: Int,
        pillHeight: Int,
        gap: Int,
        touchMargin: Int,
        side: PillSide,
        fallbackTop: Int,
    ): Box {
        val pillTop: Int
        val pillLeft: Int
        if (cutout == null || cutout.width <= 0) {
            // No punch-hole reported: centre the pill at the top.
            pillTop = fallbackTop
            pillLeft = (screenWidth - pillWidth) / 2
        } else {
            pillTop = cutout.centerY - pillHeight / 2
            pillLeft = when (side) {
                PillSide.RIGHT -> cutout.right + gap
                PillSide.LEFT -> cutout.left - gap - pillWidth
            }
        }
        // Keep the whole pill on screen.
        val left = pillLeft.coerceIn(0, (screenWidth - pillWidth).coerceAtLeast(0))
        val top = pillTop.coerceAtLeast(0)
        return Box(left - touchMargin, top - touchMargin, left + pillWidth + touchMargin, top + pillHeight + touchMargin)
    }
}
