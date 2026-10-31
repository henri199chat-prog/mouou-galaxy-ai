package com.glteam.teambubbles

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gesture rules of the island (spec §6), as pure logic fed with raw screen coordinates and times, so
 * every rule is unit-tested off-device.
 *
 * - Tap: moved ≤ slop and released before the long-press time.
 * - Long-press: fires WHILE the finger is down once the long-press time passes without moving.
 * - One-finger drag: past slop and before a long-press.
 * - Two-finger drag: a second finger turns the gesture into a move; it never becomes a tap or long-press.
 * - Fling up: released moving up ≥ 48 dp at ≥ 1000 dp/s.
 */
class GestureEngine(
    private val slopPx: Float,
    private val longPressMs: Long,
    private val density: Float,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTap()
        fun onLongPress()
        fun onLongPressEnd(heldMs: Long)
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float, vx: Float, vy: Float)
        fun onDragEnd(vx: Float, vy: Float)
        /** Return true if the fling was used (then no onDragEnd follows). */
        fun onFlingUp(): Boolean
        fun onTwoFingerDrag(dx: Float, dy: Float)
        fun onTwoFingerEnd()
    }

    private enum class State { IDLE, PRESSED, LONG, DRAG, TWO, IGNORE }

    private var state = State.IDLE
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var twoX = 0f
    private var twoY = 0f

    // Last few samples for the release velocity (px/s).
    private val sampleT = LongArray(SAMPLES)
    private val sampleX = FloatArray(SAMPLES)
    private val sampleY = FloatArray(SAMPLES)
    private var sampleCount = 0

    val isFingerDown get() = state != State.IDLE

    fun down(t: Long, x: Float, y: Float) {
        state = State.PRESSED
        downT = t
        downX = x
        downY = y
        lastX = x
        lastY = y
        sampleCount = 0
        addSample(t, x, y)
    }

    /** A second finger touched: the gesture becomes a two-finger move. (cx, cy) = centroid. */
    fun pointerDown(cx: Float, cy: Float) {
        if (state == State.IDLE) return
        if (state == State.LONG) callbacks.onLongPressEnd(0)
        if (state == State.DRAG) callbacks.onDragEnd(0f, 0f)
        state = State.TWO
        twoX = cx
        twoY = cy
    }

    /** A finger lifted while another stays: a two-finger move ends; the rest of the touch is ignored. */
    fun pointerUp() {
        if (state == State.TWO) callbacks.onTwoFingerEnd()
        if (state != State.IDLE) state = State.IGNORE
    }

    /** One-finger move at (x, y); for two fingers pass the centroid. */
    fun move(t: Long, x: Float, y: Float) {
        when (state) {
            State.PRESSED -> {
                addSample(t, x, y)
                if (hypot(x - downX, y - downY) > slopPx) {
                    state = State.DRAG
                    callbacks.onDragStart()
                    emitDrag(x, y)
                }
            }
            State.DRAG -> {
                addSample(t, x, y)
                emitDrag(x, y)
            }
            State.TWO -> {
                callbacks.onTwoFingerDrag(x - twoX, y - twoY)
                twoX = x
                twoY = y
            }
            else -> Unit
        }
    }

    fun up(t: Long, x: Float, y: Float) {
        addSample(t, x, y)
        val (vx, vy) = velocity()
        val flungUp = (y - downY) <= -FLING_MIN_DP * density && vy <= -FLING_MIN_DP_PER_S * density
        when (state) {
            State.PRESSED -> if (flungUp) callbacks.onFlingUp() else if (t - downT < longPressMs) callbacks.onTap()
            State.LONG -> callbacks.onLongPressEnd(t - downT)
            State.DRAG -> if (!(flungUp && callbacks.onFlingUp())) callbacks.onDragEnd(vx, vy)
            State.TWO -> callbacks.onTwoFingerEnd()
            else -> Unit
        }
        state = State.IDLE
    }

    fun cancel() {
        when (state) {
            State.LONG -> callbacks.onLongPressEnd(0)
            State.DRAG -> callbacks.onDragEnd(0f, 0f)
            State.TWO -> callbacks.onTwoFingerEnd()
            else -> Unit
        }
        state = State.IDLE
    }

    /** Call when the long-press time may have passed; fires the long-press while the finger is still down. */
    fun tick(t: Long) {
        if (state == State.PRESSED && t - downT >= longPressMs) {
            state = State.LONG
            callbacks.onLongPress()
        }
    }

    private fun emitDrag(x: Float, y: Float) {
        val (vx, vy) = velocity()
        callbacks.onDrag(x - lastX, y - lastY, vx, vy)
        lastX = x
        lastY = y
    }

    private fun addSample(t: Long, x: Float, y: Float) {
        val i = sampleCount % SAMPLES
        sampleT[i] = t
        sampleX[i] = x
        sampleY[i] = y
        sampleCount++
    }

    /** Velocity over the stored samples (newest − oldest), in px/s. */
    private fun velocity(): Pair<Float, Float> {
        if (sampleCount < 2) return 0f to 0f
        val newest = (sampleCount - 1) % SAMPLES
        val oldest = if (sampleCount <= SAMPLES) 0 else sampleCount % SAMPLES
        val dt = (sampleT[newest] - sampleT[oldest]).coerceAtLeast(1L) / 1000f
        if (abs(dt) < 1e-6f) return 0f to 0f
        return (sampleX[newest] - sampleX[oldest]) / dt to (sampleY[newest] - sampleY[oldest]) / dt
    }

    companion object {
        private const val SAMPLES = 4
        const val FLING_MIN_DP = 48f
        const val FLING_MIN_DP_PER_S = 1000f
    }
}
