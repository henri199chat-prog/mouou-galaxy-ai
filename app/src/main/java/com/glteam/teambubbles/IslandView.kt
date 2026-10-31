package com.glteam.teambubbles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * The idle island: a plain black pill (spec §5.3: radius h/2, #000000) with a mini Mouou inside.
 * The view fills its window; the pill is inset by the touch margin so taps just outside it still land.
 * Idle state with Mouou's life (breathing, blinking, glancing) redrawn every vsync while attached;
 * morphing and gestures come in steps 1.3 and 1.4.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val touchMarginPx: Float) : View(context) {

    private val density = resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5599FFFF; style = Paint.Style.FILL }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101014.toInt() }
    private val pill = RectF()
    private val body = RectF()
    private val eye = RectF()
    private val life = MascotLife()
    private var gradientTop = Float.NaN
    private var gradientBottom = Float.NaN

    override fun onDraw(canvas: Canvas) {
        pill.set(touchMarginPx, touchMarginPx, width - touchMarginPx, height - touchMarginPx)
        val r = pill.height() / 2f
        canvas.drawRoundRect(pill, r, r, pillPaint)
        val now = SystemClock.uptimeMillis()
        drawMiniMascot(canvas, pill.centerX(), pill.centerY(), pill.height() * 0.80f, now)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** Mini Mouou (spec §4): rounded-square mochi blob ≈ 1.25 : 1, white gradient, two vertical black ovals. */
    private fun drawMiniMascot(canvas: Canvas, cx: Float, cy: Float, heightPx: Float, now: Long) {
        val h = heightPx * 0.78f
        val w = h * 1.25f
        body.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val corner = h * 0.42f
        if (gradientTop != body.top || gradientBottom != body.bottom) {
            // Built only when the size changes: no allocations per frame (spec §18).
            gradientTop = body.top
            gradientBottom = body.bottom
            bodyPaint.shader = LinearGradient(
                0f, body.top, 0f, body.bottom,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt(), 0xFFE4E7EE.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }

        // Breathing: stretch up and slim down around the bottom edge, so it looks soft rather than zooming.
        val breath = life.breath(now)
        canvas.save()
        canvas.scale(2f - breath, breath, cx, body.bottom)

        val g = 2f * density
        canvas.drawRoundRect(body.left - g, body.top - g, body.right + g, body.bottom + g, corner + g, corner + g, glowPaint)
        canvas.drawRoundRect(body, corner, corner, bodyPaint)

        // Eyes: centres at ±13.5 % of body width, 56 % of body height; blinks squash them, glances move them.
        val look = life.look(now)
        val eyeW = w * 0.10f
        val eyeH = (h * 0.26f * life.eyeOpenness(now)).coerceAtLeast(eyeW * 0.25f)
        val eyeY = body.top + h * (0.56f + look.y)
        for (dx in EYE_OFFSETS) {
            val ex = cx + w * (dx + look.x)
            eye.set(ex - eyeW / 2, eyeY - eyeH / 2, ex + eyeW / 2, eyeY + eyeH / 2)
            canvas.drawOval(eye, eyePaint)
        }
        canvas.restore()
    }

    private companion object {
        val EYE_OFFSETS = floatArrayOf(-0.135f, 0.135f)
    }
}
