package com.glteam.teambubbles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * One-shot reactions layered on top of the idle life (spec §4 "squash & stretch on drag/land; wobble",
 * §5.5 "5 taps in 1.3 s → playful shake 9 Hz, e^(−6.5t)"). Pure time maths: each reaction is a decaying
 * curve that returns exactly to neutral, so nothing can get stuck squashed or offset.
 */
class MascotBounce {
    private var landAt = Long.MIN_VALUE
    private var hopAt = Long.MIN_VALUE
    private var shakeAt = Long.MIN_VALUE

    /** Soft landing squash (card opened, hop finished). */
    fun land(nowMs: Long) { landAt = nowMs }

    /** Small happy hop (message sent). Lands with a squash at the end. */
    fun hop(nowMs: Long) { hopAt = nowMs }

    /** Playful shake (5 quick taps). */
    fun shake(nowMs: Long) { shakeAt = nowMs }

    /** Vertical scale multiplier; the horizontal one is 2 − this, so the volume looks constant. */
    fun squash(nowMs: Long): Float {
        var s = 1f
        val tl = nowMs - landAt
        if (landAt != Long.MIN_VALUE && tl in 0 until LAND_MS) s -= landCurve(tl)
        val th = nowMs - hopAt
        if (hopAt != Long.MIN_VALUE) {
            when {
                th in 0 until HOP_CROUCH_MS -> s -= 0.10f * sin(PI * th / HOP_CROUCH_MS).toFloat() // crouch before jumping
                th in HOP_CROUCH_MS until HOP_CROUCH_MS + HOP_AIR_MS -> s += 0.06f // stretched in the air
                th in HOP_CROUCH_MS + HOP_AIR_MS until HOP_TOTAL_MS -> s -= landCurve(th - HOP_CROUCH_MS - HOP_AIR_MS)
            }
        }
        return s
    }

    /** Upward offset as a fraction of body height (negative = up). */
    fun offsetY(nowMs: Long): Float {
        val t = nowMs - hopAt - HOP_CROUCH_MS
        if (hopAt == Long.MIN_VALUE || t !in 0 until HOP_AIR_MS) return 0f
        return -HOP_HEIGHT * sin(PI * t / HOP_AIR_MS).toFloat()
    }

    /** Sideways shake as a fraction of body width. */
    fun offsetX(nowMs: Long): Float {
        val t = nowMs - shakeAt
        if (shakeAt == Long.MIN_VALUE || t !in 0 until SHAKE_MS) return 0f
        val sec = t / 1000.0
        return (SHAKE_AMPLITUDE * sin(2 * PI * 9.0 * sec) * exp(-6.5 * sec)).toFloat()
    }

    /** True while any reaction is still playing (the view then draws every vsync). */
    fun active(nowMs: Long): Boolean =
        (nowMs - landAt) in 0 until LAND_MS || (nowMs - hopAt) in 0 until HOP_TOTAL_MS || (nowMs - shakeAt) in 0 until SHAKE_MS

    private fun landCurve(t: Long): Float {
        val sec = t / 1000.0
        return (LAND_DEPTH * cos(2 * PI * sec / 0.26) * exp(-sec / 0.09)).toFloat()
    }

    companion object {
        const val LAND_MS = 450L
        const val LAND_DEPTH = 0.12f
        const val HOP_CROUCH_MS = 90L
        const val HOP_AIR_MS = 300L
        const val HOP_TOTAL_MS = HOP_CROUCH_MS + HOP_AIR_MS + LAND_MS
        const val HOP_HEIGHT = 0.35f
        const val SHAKE_MS = 750L
        const val SHAKE_AMPLITUDE = 0.08f
    }
}

/** 2.6.0: the card glow lights up when an answer arrives (an "aha"), then fades back. Pure, tested. */
object GlowBloom {
    const val RISE_MS = 140f
    const val FADE_MS = 220f
    const val TOTAL_MS = 1100L

    /** 0..1: a soft rise to 1 in 140 ms, then an exponential fade, 0 after 1.1 s (and before a bloom ever happened). */
    fun amount(sinceMs: Long): Float = when {
        sinceMs < 0 || sinceMs >= TOTAL_MS -> 0f
        sinceMs < RISE_MS -> { val t = sinceMs / RISE_MS; t * t * (3 - 2 * t) }
        else -> kotlin.math.exp(-(sinceMs - RISE_MS) / FADE_MS)
    }
}
