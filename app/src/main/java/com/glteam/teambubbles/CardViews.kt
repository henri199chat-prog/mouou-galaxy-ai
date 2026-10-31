package com.glteam.teambubbles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Root of the island window. Absolute LTR layout (children are placed by pixel margins, spec §5.1),
 * and it forwards the two events a window gets from outside its own views: a touch outside the
 * window (FLAG_WATCH_OUTSIDE_TOUCH) and the back key while the input is focused.
 */
@SuppressLint("ViewConstructor")
class IslandRoot(context: Context, private val onOutside: () -> Unit, private val onBack: () -> Boolean) :
    FrameLayout(context) {

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && onBack()) return true
        return super.dispatchKeyEvent(event)
    }
}

/** Round send button (spec §5.3): 28 dp, #F4F4F6, black up-arrow. */
class SendButton(context: Context) : View(context) {
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F4F6.toInt() }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        isClickable = true
        contentDescription = "إرسال"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val s = minOf(w, h).toFloat()
        arrow.strokeWidth = s * 0.09f
        path.reset()
        path.moveTo(cx, h * 0.70f)
        path.lineTo(cx, h * 0.30f)
        path.moveTo(cx - s * 0.17f, h * 0.46f)
        path.lineTo(cx, h * 0.30f)
        path.lineTo(cx + s * 0.17f, h * 0.46f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, circle)
        canvas.drawPath(path, arrow)
    }
}
