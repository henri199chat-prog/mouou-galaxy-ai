package com.glteam.teambubbles

/**
 * Show / hide animation of the idle island (Ayman, 2026-10-31: "hide after a time I choose, appear and
 * disappear smoothly"). Hiding: Mouou closes his eyes, then the pill shrinks and fades into the camera;
 * showing is the exact reverse. Time-based smoothstep, so it never overshoots or jumps.
 */
class VisibilityAnim(initiallyShown: Boolean = true) {
    private var from = if (initiallyShown) 1f else 0f
    private var to = from
    private var startMs = 0L

    fun show(nowMs: Long) = animateTo(1f, nowMs)

    /** Instantly hidden (no animation): the starting point of an emerge-from-the-camera. */
    fun snapHidden(nowMs: Long) {
        from = 0f
        to = 0f
        startMs = nowMs - DURATION_MS
    }
    fun hide(nowMs: Long) = animateTo(0f, nowMs)

    val target get() = to

    /** 0 = hidden, 1 = fully shown. */
    fun value(nowMs: Long): Float {
        val p = ((nowMs - startMs) / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val e = p * p * (3 - 2 * p)
        return from + (to - from) * e
    }

    fun animating(nowMs: Long) = nowMs - startMs < DURATION_MS

    private fun animateTo(v: Float, nowMs: Long) {
        from = value(nowMs) // start from wherever it is now: reversing mid-way never jumps
        to = v
        startMs = nowMs
    }

    companion object {
        const val DURATION_MS = 380L

        /** Eyes stay closed while the pill is small; they open only in the last 40 % of showing. */
        fun eyeOpenness(shown: Float): Float {
            val p = ((shown - 0.6f) / 0.4f).coerceIn(0f, 1f)
            return p * p * (3 - 2 * p)
        }
    }
}

/**
 * While hidden, the spot to tap to bring Mouou back is the camera hole itself (Ayman, 2026-10-31):
 * the hole plus a comfortable margin, from the top of the screen to the bottom of the status bar.
 */
fun cameraTapZone(cutout: Box, statusBarBottom: Int, margin: Int, screenWidth: Int): Box = Box(
    (cutout.left - margin).coerceAtLeast(0),
    0,
    (cutout.right + margin).coerceAtMost(screenWidth),
    maxOf(cutout.bottom + margin / 2, statusBarBottom),
)

/** Auto-hide choices in settings (0 = never). */
object HideAfter {
    val CHOICES_MS = longArrayOf(0L, 1_000L, 5_000L, 30_000L, 60_000L, 300_000L)

    fun label(ms: Long): String = when (ms) {
        0L -> "أبدًا"
        1_000L -> "ثانية"
        5_000L -> "5 ثواني"
        30_000L -> "30 ثانية"
        60_000L -> "دقيقة"
        300_000L -> "5 دقايق"
        else -> "${ms / 1000} ثانية"
    }

    /** The timer may only run while nobody is using Mouou: card closed, not typing, no finger down. */
    fun mayHide(hideAfterMs: Long, cardOpen: Boolean, typing: Boolean, fingerDown: Boolean): Boolean =
        hideAfterMs > 0 && !cardOpen && !typing && !fingerDown
}
