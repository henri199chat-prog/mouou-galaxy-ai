package com.glteam.teambubbles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the island in every size between the idle pill and the chat card (spec §5).
 *
 * Positions are kept in ABSOLUTE screen pixels ([pill], [card]) and drawn relative to the window's
 * origin, which only changes in [onSizeChanged] when the new window size has really been applied.
 * So a window resize/move and the drawing that matches it always land in the same frame: no blank
 * frame, no jump (spec §5.5).
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : View(context) {

    interface Listener {
        fun onPillTap()
        fun onLongPress()
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd(vx: Float, vy: Float)
        fun onFlingUp(): Boolean
        fun onTwoFingerDrag(dx: Float, dy: Float)
        fun onTwoFingerEnd()
        /** A tap on the hidden island's spot: show it again. */
        fun onRevealTap()
        fun onWindowApplied()
        fun onProgress(progress: Float)
        fun onCollapsed()
    }

    var listener: Listener? = null
    var pill = Box(0, 0, 0, 0)
    var card = Box(0, 0, 0, 0)

    private val density = resources.displayMetrics.density
    private val spring = MorphSpring()
    private val life = MascotLife()
    private val bounce = MascotBounce()
    private var landed = false
    private var attentive = false
    private var attention = 0f // 0 = free glances, 1 = looking at the input field (eased)
    private val tapTimes = LongArray(TAPS_FOR_SHAKE)
    private var tapCount = 0
    private var originX = 0
    private var originY = 0
    private var pendingFrame: Box? = null
    private var pendingSince = 0L
    private val location = IntArray(2)
    private var replyGlanceUntil = 0L
    private var lookTargetX = 0f
    private var thinking = false
    private var scanning = false
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scanRadius = Float.NaN
    private val visibility = VisibilityAnim()
    private var anchorX = Float.NaN // where the pill shrinks to when hiding (the camera), screen px
    private var anchorY = Float.NaN
    private var settleAfterShow = false
    private var lookTargetY = 0f
    private var lastFrameMs = 0L
    private var screenOn = true
    private var wasOpen = false

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val cardGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5599FFFF }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101014.toInt() }
    private val shape = RectF()
    private val body = RectF()
    private val eye = RectF()
    private val clip = Path()
    private var bodyGradientHeight = Float.NaN
    private var cardGlowRadius = Float.NaN

    // Touch (spec §6): raw screen coordinates, slop = touchSlop × 3, long-press 480 ms.
    private val gestures = GestureEngine(
        slopPx = ViewConfiguration.get(context).scaledTouchSlop * 3f,
        longPressMs = LONG_PRESS_MS,
        density = density,
        callbacks = GestureBridge(),
    )
    private val longPressCheck = Runnable { gestures.tick(SystemClock.uptimeMillis()) }

    // Drag life: Mouou stretches along the direction he is dragged, then lands with a squash.
    private var dragVx = 0f
    private var dragVy = 0f
    private var stretch = 0f

    val isExpandedOrOpening get() = spring.target > 0f
    val isHiddenOrHiding get() = visibility.target == 0f

    /** Hide: eyes close, then the pill shrinks and fades into [anchorX, anchorY] (the camera). */
    fun hideInto(ax: Float, ay: Float) {
        anchorX = ax
        anchorY = ay
        visibility.hide(SystemClock.uptimeMillis())
        kick()
    }

    /** Show again: the exact reverse of hiding (out of the camera). */
    fun show() {
        visibility.show(SystemClock.uptimeMillis())
        settleAfterShow = true
        kick()
    }

    /** Appear by coming out of the camera at ([ax], [ay]): used when the island starts or changes host. */
    fun emergeFrom(ax: Float, ay: Float) {
        anchorX = ax
        anchorY = ay
        val now = SystemClock.uptimeMillis()
        visibility.snapHidden(now)
        visibility.show(now)
        settleAfterShow = true
        kick()
    }
    val isFingerDown get() = gestures.isFingerDown

    /** Closes instantly, without animation (used when the window moves to another host). */
    fun snapClosed() {
        spring.snapTo(0f)
        wasOpen = false
        landed = false
        invalidate()
    }

    /** Tell the view the window frame the service is about to apply (absolute screen box). */
    fun expectFrame(frame: Box) {
        pendingFrame = frame
        pendingSince = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun expand() {
        landed = false
        spring.target = 1f
        kick()
    }

    /** Happy hop (a message was sent). */
    fun hop() {
        bounce.hop(SystemClock.uptimeMillis())
        kick()
    }

    /** While Ayman types, Mouou watches the input field instead of glancing around. */
    fun setAttentive(on: Boolean) {
        attentive = on
        kick()
    }

    fun collapse() {
        spring.target = 0f
        kick()
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (on) kick()
    }

    /**
     * Draw relative to where the window REALLY is on screen this frame. On One UI the new window size can
     * arrive a frame before the new position (Ayman's 2.2.6 recording: the pill jumped to the screen edge
     * on open and close); reading the location every frame keeps the drawing glued to the screen.
     * The card only starts dropping once both the size and the position of the expected frame are applied.
     */
    private fun syncWindowOrigin(now: Long) {
        getLocationOnScreen(location)
        originX = location[0]
        originY = location[1]
        val frame = pendingFrame ?: return
        val sizeOk = width == frame.width && height == frame.height
        val placeOk = originX == frame.left && originY == frame.top
        // The system may nudge a window's position (cutout, limits): after 150 ms trust the size alone.
        if (sizeOk && (placeOk || now - pendingSince > FRAME_SETTLE_TIMEOUT_MS)) {
            pendingFrame = null
            post { listener?.onWindowApplied() }
        }
    }

    /** Scanning face (spec §11): eyes down, a blue light under him, while he looks at the screen. */
    fun setScanning(on: Boolean) {
        scanning = on
        kick()
    }

    /** Thinking face (spec §11): eyes up and to the right, a slow bob, while the brain works. */
    fun setThinking(on: Boolean) {
        thinking = on
        kick()
    }

    /** After a reply arrives Mouou looks at it for a moment (up and to his right). */
    fun glanceAtReply() {
        replyGlanceUntil = SystemClock.uptimeMillis() + REPLY_GLANCE_MS
        kick()
    }

    private fun kick() {
        // Reset the frame clock only after a real pause (idle 30 fps or screen off); resetting it
        // mid-animation would make the spring take one too-short step (a visible stutter).
        if (SystemClock.uptimeMillis() - lastFrameMs > PAUSE_MS) lastFrameMs = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        syncWindowOrigin(now)
        var moving = if (spring.isSettled) false else {
            val dt = if (lastFrameMs == 0L) 8.33f else (now - lastFrameMs).toFloat()
            spring.step(dt)
        }
        // Closing lands cleanly on the pill: no undershoot that would squeeze the pill for a few frames.
        if (spring.target == 0f && spring.value < 0f) {
            spring.snapTo(0f)
            moving = false
        }
        val frameDt = if (lastFrameMs == 0L) 8.33f else (now - lastFrameMs).toFloat()
        lastFrameMs = now
        val p = spring.value
        if (spring.target == 1f && !moving && !landed) {
            landed = true
            bounce.land(now) // soft squash when the card has finished dropping
        }
        if (settleAfterShow && visibility.target == 1f && !visibility.animating(now)) {
            settleAfterShow = false
            bounce.land(now) // out of the camera: a soft little settle, like landing in place
        }
        val glancingAtReply = !attentive && !thinking && now < replyGlanceUntil
        val goal = if (attentive || thinking || scanning || glancingAtReply) 1f else 0f
        when {
            scanning -> { lookTargetX = 0f; lookTargetY = SCAN_LOOK_Y }
            thinking -> { lookTargetX = THINK_LOOK_X; lookTargetY = THINK_LOOK_Y }
            glancingAtReply -> { lookTargetX = REPLY_LOOK_X; lookTargetY = REPLY_LOOK_Y }
            else -> { lookTargetX = FIELD_LOOK_X; lookTargetY = FIELD_LOOK_Y }
        }
        attention += (goal - attention) * (frameDt / 140f).coerceAtMost(1f)
        val speed = kotlin.math.hypot(dragVx, dragVy)
        val stretchGoal = (speed / (2500f * density)).coerceAtMost(1f) * MAX_DRAG_STRETCH
        stretch += (stretchGoal - stretch) * (frameDt / 90f).coerceAtMost(1f)

        canvas.save()
        canvas.translate(-originX.toFloat(), -originY.toFloat())
        drawShape(canvas, p, now)
        canvas.restore()

        listener?.onProgress(p)
        if (p > 0f) wasOpen = true
        if (!moving && spring.target == 0f && p == 0f && wasOpen) {
            wasOpen = false
            post { listener?.onCollapsed() }
        }

        // Pacing (battery): every vsync while morphing, 30 fps for the idle breathing, nothing with the screen off.
        if (!isAttachedToWindow || !screenOn) return
        val hiddenAndStill = visibility.target == 0f && !visibility.animating(now) && p == 0f
        if (hiddenAndStill && pendingFrame == null) return // hidden: no frames at all until show()
        if (visibility.animating(now)) {
            postInvalidateOnAnimation()
            return
        }
        val easingAttention = kotlin.math.abs(goal - attention) > 0.01f || stretch > 0.002f || pendingFrame != null
        if (moving || easingAttention || bounce.active(now)) postInvalidateOnAnimation() else postInvalidateDelayed(IDLE_FRAME_MS)
    }

    private fun drawShape(canvas: Canvas, p: Float, now: Long) {
        val lerp = IslandGeometry::lerp
        val pc = p.coerceIn(0f, 1f)
        // The spring's overshoot only stretches the bottom edge: the card "drops" and bounces down,
        // its sides never swing past the screen edges.
        shape.set(
            lerp(pill.left.toFloat(), card.left.toFloat(), pc),
            lerp(pill.top.toFloat(), card.top.toFloat(), pc),
            lerp(pill.right.toFloat(), card.right.toFloat(), pc),
            lerp(pill.bottom.toFloat(), card.bottom.toFloat(), p),
        )
        val radius = lerp(pill.height / 2f, CARD_RADIUS_DP * density, pc)
        val idleMascotH = pill.height * 0.80f * 0.78f

        if (p <= 0f) {
            val shown = visibility.value(now)
            if (shown <= 0f) return // hidden: nothing drawn
            val cx = shape.centerX()
            val cy = shape.centerY()
            canvas.save()
            if (shown < 1f) {
                // Shrink and fade into the camera (or into itself when no camera is known).
                val ax = if (anchorX.isNaN()) cx else anchorX
                val ay = if (anchorY.isNaN()) cy else anchorY
                val scale = lerp(0.15f, 1f, shown)
                canvas.translate(lerp(ax, cx, shown), lerp(ay, cy, shown))
                canvas.scale(scale, scale)
                canvas.translate(-cx, -cy)
                val alpha = (255 * shown).toInt()
                pillPaint.alpha = alpha
                bodyPaint.alpha = alpha
                bodyGlowPaint.alpha = (0x55 * shown).toInt()
                eyePaint.alpha = alpha
            }
            canvas.drawRoundRect(shape, radius, radius, pillPaint)
            drawMascot(canvas, cx, cy, idleMascotH, now, VisibilityAnim.eyeOpenness(shown))
            canvas.restore()
            if (shown < 1f) {
                pillPaint.alpha = 255
                bodyPaint.alpha = 255
                bodyGlowPaint.alpha = 0x55
                eyePaint.alpha = 255
            }
            return
        }

        // Card space: black-blue space, twinkling stars and a soft glow behind Mouou (spec §5.4).
        clip.reset()
        clip.addRoundRect(shape, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        spacePaint.color = blend(0xFF000000.toInt(), 0xFF030308.toInt(), pc)
        canvas.drawRect(shape, spacePaint)

        // Mouou travels from the pill into the card (same object, continuous) along a soft ease-in-out:
        // he never follows the spring's bounce sideways, so he does not seem to "run away" and come back.
        val travel = pc * pc * (3 - 2 * pc)
        val mascotCx = lerp(pill.centerX.toFloat(), card.left + (CARD_PAD_DP + MASCOT_DP / 2) * density, travel)
        val mascotCy = lerp(pill.centerY.toFloat(), card.centerY.toFloat(), travel)
        val mascotH = lerp(idleMascotH, MASCOT_DP * density / 1.25f, travel)

        drawCardGlow(canvas, mascotCx, mascotCy, pc)
        drawStars(canvas, now, pc)
        drawMascot(canvas, mascotCx, mascotCy, mascotH, now)
        canvas.restore()
    }

    private fun drawCardGlow(canvas: Canvas, cx: Float, cy: Float, p: Float) {
        val r = card.width * 0.62f
        if (r <= 0f) return
        if (r != cardGlowRadius) {
            // Built only when the card size changes; positioned by translating the canvas (no per-frame allocation).
            cardGlowRadius = r
            cardGlowPaint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(withAlpha(GLOW_COLOR, 0.42f), withAlpha(GLOW_COLOR, 0.13f), withAlpha(GLOW_COLOR, 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        cardGlowPaint.alpha = (255 * p).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, r, cardGlowPaint)
        canvas.restore()
    }

    private fun drawStars(canvas: Canvas, now: Long, p: Float) {
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        for (i in STARS.indices step 4) {
            val x = card.left + STARS[i] * w
            val y = card.top + STARS[i + 1] * h
            val size = (0.6f + STARS[i + 2]) * density // 0.6–1.6 dp
            val period = 2000L + (STARS[i + 3] * 3000f).toLong() // 2–5 s
            val phase = (now % period) / period.toFloat()
            val twinkle = 0.55f + 0.35f * sin(2.0 * Math.PI * phase + i).toFloat() // alpha 0.2–0.9
            starPaint.alpha = (255 * twinkle * p).toInt()
            canvas.drawCircle(x, y, size / 2f, starPaint)
        }
    }

    /** Soft blue light under Mouou while he reads the screen (#5AA8FF → #BFE0FF), pulsing gently. */
    private fun drawScanLight(canvas: Canvas, x: Float, y: Float, bodyW: Float, now: Long) {
        val r = bodyW * 0.9f
        if (r != scanRadius) {
            scanRadius = r
            scanPaint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(withAlpha(0xFFBFE0FF.toInt(), 0.75f), withAlpha(0xFF5AA8FF.toInt(), 0.35f), withAlpha(0xFF5AA8FF.toInt(), 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        val pulse = 0.75f + 0.25f * kotlin.math.sin(2.0 * Math.PI * (now % 900L) / 900.0).toFloat()
        scanPaint.alpha = (255 * pulse).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f, 0.35f) // a flat ellipse of light on the "floor"
        canvas.drawCircle(0f, 0f, r, scanPaint)
        canvas.restore()
    }

    /** Mouou (spec §4): rounded-square blob ≈ 1.25 : 1, white gradient, two black ovals. No mouth, no hands. */
    private fun drawMascot(canvas: Canvas, cx: Float, cy: Float, h: Float, now: Long, eyeFactor: Float = 1f) {
        val w = h * 1.25f
        body.set(-w / 2, -h / 2, w / 2, h / 2)
        val corner = h * 0.42f
        if (h != bodyGradientHeight) {
            bodyGradientHeight = h
            bodyPaint.shader = LinearGradient(
                0f, -h / 2, 0f, h / 2,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt(), 0xFFE4E7EE.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        val breath = life.breath(now)
        val squash = bounce.squash(now)
        canvas.save()
        if (scanning) drawScanLight(canvas, cx, cy + h * 0.62f, w, now)
        val thinkBob = if (thinking) THINK_BOB * kotlin.math.sin(2.0 * Math.PI * (now % THINK_BOB_MS) / THINK_BOB_MS).toFloat() else 0f
        canvas.translate(cx + w * bounce.offsetX(now), cy + h * (bounce.offsetY(now) + thinkBob))
        // Breathing and reactions squash/stretch around the bottom edge, keeping the volume constant.
        // Dragging stretches him along the movement (horizontal or vertical), keeping the volume.
        val horizontal = kotlin.math.abs(dragVx) >= kotlin.math.abs(dragVy)
        val sxDrag = if (horizontal) 1f + stretch else 1f - stretch * 0.6f
        val syDrag = if (horizontal) 1f - stretch * 0.6f else 1f + stretch
        canvas.scale((2f - breath) * (2f - squash) * sxDrag, breath * squash * syDrag, 0f, h / 2)

        val g = 2f * density
        canvas.drawRoundRect(body.left - g, body.top - g, body.right + g, body.bottom + g, corner + g, corner + g, bodyGlowPaint)
        canvas.drawRoundRect(body, corner, corner, bodyPaint)

        val glance = life.look(now)
        val lookX = glance.x + (lookTargetX - glance.x) * attention
        val lookY = glance.y + (lookTargetY - glance.y) * attention
        val eyeW = w * 0.10f
        val eyeH = (h * 0.26f * life.eyeOpenness(now) * eyeFactor).coerceAtLeast(eyeW * 0.25f)
        val eyeY = body.top + h * (0.56f + lookY)
        for (dx in EYE_OFFSETS) {
            val ex = w * (dx + lookX)
            eye.set(ex - eyeW / 2, eyeY - eyeH / 2, ex + eyeW / 2, eyeY + eyeH / 2)
            canvas.drawOval(eye, eyePaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Raw screen coordinates for every pointer (window moves during a drag must not distort deltas).
        val offX = event.rawX - event.getX(0)
        val offY = event.rawY - event.getY(0)
        val t = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestures.down(t, event.rawX, event.rawY)
                removeCallbacks(longPressCheck)
                postDelayed(longPressCheck, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressCheck)
                if (event.pointerCount >= 2) {
                    gestures.pointerDown(
                        (event.getX(0) + event.getX(1)) / 2 + offX,
                        (event.getY(0) + event.getY(1)) / 2 + offY,
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                gestures.move(t, (event.getX(0) + event.getX(1)) / 2 + offX, (event.getY(0) + event.getY(1)) / 2 + offY)
            } else {
                gestures.move(t, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_POINTER_UP -> gestures.pointerUp()
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressCheck)
                gestures.up(t, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressCheck)
                gestures.cancel()
            }
        }
        return true
    }

    private inner class GestureBridge : GestureEngine.Callbacks {
        override fun onTap() = onTap(SystemClock.uptimeMillis())
        override fun onLongPress() {
            bounce.land(SystemClock.uptimeMillis()) // a little "I'm listening" squash; voice comes in phase 2
            kick()
            listener?.onLongPress()
        }
        override fun onLongPressEnd(heldMs: Long) = Unit
        override fun onDragStart() { listener?.onDragStart() }
        override fun onDrag(dx: Float, dy: Float, vx: Float, vy: Float) {
            dragVx = vx
            dragVy = vy
            listener?.onDrag(dx, dy)
            kick()
        }
        override fun onDragEnd(vx: Float, vy: Float) {
            dragVx = 0f
            dragVy = 0f
            bounce.land(SystemClock.uptimeMillis())
            kick()
            listener?.onDragEnd(vx, vy)
        }
        override fun onFlingUp(): Boolean = listener?.onFlingUp() ?: false
        override fun onTwoFingerDrag(dx: Float, dy: Float) { listener?.onTwoFingerDrag(dx, dy) }
        override fun onTwoFingerEnd() { listener?.onTwoFingerEnd() }
    }

    /** A tap on the pill opens the card; 5 taps within 1.3 s make Mouou shake (spec §5.5). */
    private fun onTap(time: Long) {
        tapTimes[tapCount % TAPS_FOR_SHAKE] = time
        tapCount++
        val oldest = tapTimes[tapCount % TAPS_FOR_SHAKE]
        if (tapCount >= TAPS_FOR_SHAKE && time - oldest <= SHAKE_WINDOW_MS) {
            tapCount = 0
            bounce.shake(SystemClock.uptimeMillis())
            kick()
        }
        if (isHiddenOrHiding) {
            listener?.onRevealTap()
            return
        }
        if (!isExpandedOrOpening) listener?.onPillTap() // on the open card, empty space only counts taps
    }

    private companion object {
        const val IDLE_FRAME_MS = 33L
        const val LONG_PRESS_MS = 480L
        const val MAX_DRAG_STRETCH = 0.12f
        const val PAUSE_MS = 50L
        const val TAPS_FOR_SHAKE = 5
        const val SHAKE_WINDOW_MS = 1300L
        const val FIELD_LOOK_X = 0.07f // the input field is to Mouou's right and a little lower
        const val FIELD_LOOK_Y = 0.045f
        const val REPLY_LOOK_X = 0.07f // the reply text is to his right and higher
        const val REPLY_LOOK_Y = -0.05f
        const val REPLY_GLANCE_MS = 1200L
        const val SCAN_LOOK_Y = 0.08f
        const val THINK_LOOK_X = 0.075f
        const val THINK_LOOK_Y = -0.075f
        const val THINK_BOB = 0.045f
        const val THINK_BOB_MS = 1100L
        const val FRAME_SETTLE_TIMEOUT_MS = 150L
        const val CARD_RADIUS_DP = 30f
        const val CARD_PAD_DP = 12f
        const val MASCOT_DP = 48f
        const val GLOW_COLOR = 0xFF6A3FD0.toInt()
        val EYE_OFFSETS = floatArrayOf(-0.135f, 0.135f)

        /** 14 stars as (x, y fraction of the card, size 0–1, period 0–1). Fixed so they never jump. */
        val STARS = floatArrayOf(
            0.22f, 0.18f, 0.2f, 0.1f, 0.35f, 0.72f, 0.6f, 0.5f, 0.48f, 0.12f, 0.9f, 0.3f,
            0.57f, 0.55f, 0.3f, 0.8f, 0.66f, 0.22f, 0.5f, 0.2f, 0.74f, 0.82f, 0.1f, 0.65f,
            0.81f, 0.4f, 0.8f, 0.4f, 0.9f, 0.15f, 0.4f, 0.9f, 0.95f, 0.66f, 0.2f, 0.35f,
            0.29f, 0.9f, 0.7f, 0.05f, 0.42f, 0.35f, 0.1f, 0.7f, 0.62f, 0.9f, 1.0f, 0.25f,
            0.86f, 0.12f, 0.5f, 0.55f, 0.16f, 0.6f, 0.3f, 0.45f,
        )

        fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

        fun blend(from: Int, to: Int, t: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun mix(s: Int) = (ch(from, s) + (ch(to, s) - ch(from, s)) * t).toInt() shl s
            return mix(24) or mix(16) or mix(8) or mix(0)
        }
    }
}
