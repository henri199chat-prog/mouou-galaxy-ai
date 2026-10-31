package com.glteam.teambubbles

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Idle life of Mouou (spec §4): breathing ±2.2 % over 3.2 s, a 130 ms blink every 3.7–5 s, and a
 * glance around about every 2.6 s. Pure time-based maths (no Android types), so it runs the same at
 * 60 or 120 Hz and can be unit-tested.
 */
class MascotLife(private val random: Random = Random.Default) {

    /** Where the eyes look, as a fraction of body width/height. */
    data class Look(val x: Float, val y: Float)

    private var nextBlinkAt = -1L
    private var blinkStart = Long.MIN_VALUE
    private var lastWasDouble = false
    private var nextLookAt = -1L
    private var lookFrom = Look(0f, 0f)
    private var lookTo = Look(0f, 0f)
    private var lookStart = 0L

    /** Body scale: 1 ± 2.2 %, one breath every 3.2 s. */
    fun breath(nowMs: Long): Float = 1f + BREATH_AMPLITUDE * sin(2.0 * PI * (nowMs % BREATH_MS) / BREATH_MS).toFloat()

    /** 1 = eyes fully open, 0 = closed. Closes and reopens within 130 ms. */
    fun eyeOpenness(nowMs: Long): Float {
        if (nextBlinkAt < 0) nextBlinkAt = nowMs + blinkGap()
        if (nowMs >= nextBlinkAt) {
            blinkStart = nextBlinkAt
            // Now and then a quick double blink, like a real face (about one blink in four).
            nextBlinkAt = if (!lastWasDouble && random.nextFloat() < DOUBLE_BLINK_CHANCE) {
                lastWasDouble = true
                nowMs + DOUBLE_BLINK_GAP_MS
            } else {
                lastWasDouble = false
                nowMs + blinkGap()
            }
        }
        val t = nowMs - blinkStart
        if (t < 0 || t >= BLINK_MS) return 1f
        val half = BLINK_MS / 2f
        return kotlin.math.abs(t - half) / half
    }

    /** Eye offset: glances to a new spot every ~2.6 s, eased over 220 ms; mostly looks straight ahead. */
    fun look(nowMs: Long): Look {
        if (nextLookAt < 0) nextLookAt = nowMs + LOOK_EVERY_MS
        if (nowMs >= nextLookAt) {
            lookFrom = lookAt(nowMs)
            lookTo = GLANCES[random.nextInt(GLANCES.size)]
            lookStart = nowMs
            nextLookAt = nowMs + LOOK_EVERY_MS + random.nextLong(-400, 400)
        }
        return lookAt(nowMs)
    }

    private fun lookAt(nowMs: Long): Look {
        val p = ((nowMs - lookStart) / LOOK_EASE_MS.toFloat()).coerceIn(0f, 1f)
        val e = p * p * (3 - 2 * p) // smoothstep
        return Look(lookFrom.x + (lookTo.x - lookFrom.x) * e, lookFrom.y + (lookTo.y - lookFrom.y) * e)
    }

    private fun blinkGap() = random.nextLong(BLINK_MIN_GAP_MS, BLINK_MAX_GAP_MS + 1)

    companion object {
        const val BREATH_AMPLITUDE = 0.022f
        const val BREATH_MS = 3200L
        const val BLINK_MS = 130L
        const val BLINK_MIN_GAP_MS = 3700L
        const val BLINK_MAX_GAP_MS = 5000L
        const val DOUBLE_BLINK_CHANCE = 0.25f
        const val DOUBLE_BLINK_GAP_MS = 220L
        const val LOOK_EVERY_MS = 2600L
        const val LOOK_EASE_MS = 220L
        private val GLANCES = listOf(
            Look(0f, 0f), Look(0f, 0f), Look(0.06f, 0f), Look(-0.06f, 0f), Look(0.04f, -0.05f), Look(-0.04f, 0.04f),
        )
    }
}
