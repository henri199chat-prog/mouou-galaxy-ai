package com.glteam.teambubbles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The idle island: a plain black pill (spec §5.3: radius h/2, #000000) with a mini Mouou inside.
 * The view fills its window; the pill is inset by the touch margin so taps just outside it still land.
 * Step 1.2 draws only the idle state; morphing and gestures come in steps 1.3 and 1.4.
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

    override fun onDraw(canvas: Canvas) {
        pill.set(touchMarginPx, touchMarginPx, width - touchMarginPx, height - touchMarginPx)
        val r = pill.height() / 2f
        canvas.drawRoundRect(pill, r, r, pillPaint)
        drawMiniMascot(canvas, pill.centerX(), pill.centerY(), pill.height() * 0.80f)
    }

    /** Mini Mouou (spec §4): rounded-square mochi blob ≈ 1.25 : 1, white gradient, two vertical black ovals. */
    private fun drawMiniMascot(canvas: Canvas, cx: Float, cy: Float, heightPx: Float) {
        val h = heightPx * 0.78f
        val w = h * 1.25f
        body.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val corner = h * 0.42f

        // Soft glow, then the body with its top-to-bottom gradient.
        val g = 2f * density
        canvas.drawRoundRect(body.left - g, body.top - g, body.right + g, body.bottom + g, corner + g, corner + g, glowPaint)
        bodyPaint.shader = LinearGradient(
            0f, body.top, 0f, body.bottom,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt(), 0xFFE4E7EE.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(body, corner, corner, bodyPaint)

        // Eyes: centres at ±13.5 % of body width, 56 % of body height.
        val eyeW = w * 0.10f
        val eyeH = h * 0.26f
        val eyeY = body.top + h * 0.56f
        for (dx in floatArrayOf(-0.135f, 0.135f)) {
            val ex = cx + w * dx
            eye.set(ex - eyeW / 2, eyeY - eyeH / 2, ex + eyeW / 2, eyeY + eyeH / 2)
            canvas.drawOval(eye, eyePaint)
        }
    }
}
