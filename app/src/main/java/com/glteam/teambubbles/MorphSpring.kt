package com.glteam.teambubbles

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Damped spring for the pill ↔ card morph (spec §5.5): stiffness 420, damping ratio 0.62, integrated in
 * fixed 2 ms sub-steps so it moves identically at 60 and 120 Hz. Drives one progress value
 * (0 = pill, 1 = card); 0.62 gives the small natural overshoot of the "drop".
 */
class MorphSpring(
    private val stiffness: Float = 420f,
    dampingRatio: Float = 0.62f,
) {
    private val damping = 2f * dampingRatio * sqrt(stiffness)
    private var leftoverMs = 0f

    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f

    val isSettled get() = abs(value - target) < SETTLE_DISTANCE && abs(velocity) < SETTLE_VELOCITY

    /** Advances by [dtMs] of real time. Returns true while still moving. */
    fun step(dtMs: Float): Boolean {
        leftoverMs += dtMs.coerceAtMost(MAX_FRAME_MS) // a long stall must not explode the integration
        val dt = SUB_STEP_MS / 1000f
        while (leftoverMs >= SUB_STEP_MS) {
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * dt
            value += velocity * dt
            leftoverMs -= SUB_STEP_MS
        }
        if (isSettled) {
            value = target
            velocity = 0f
            leftoverMs = 0f
            return false
        }
        return true
    }

    fun snapTo(v: Float) {
        value = v
        target = v
        velocity = 0f
        leftoverMs = 0f
    }

    companion object {
        const val SUB_STEP_MS = 2f
        private const val MAX_FRAME_MS = 64f
        private const val SETTLE_DISTANCE = 0.001f
        private const val SETTLE_VELOCITY = 0.01f
    }
}
