package com.glteam.teambubbles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Foreground service that owns the floating island window (spec §5).
 *
 * Idle: the window is only the pill + 12 dp touch margin, NOT_FOCUSABLE | NOT_TOUCH_MODAL, so every
 * touch outside the pill reaches the app underneath. Tap: the window is first resized to the expanded
 * size, and only once that size is applied does the morph start (no blank frame). Focus is taken only
 * while the input is being typed in.
 */
class IslandService : Service(), IslandView.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: IslandRoot? = null
    private lateinit var island: IslandView
    private lateinit var reply: TextView
    private lateinit var input: EditText
    private lateinit var send: SendButton

    private lateinit var idleFrame: Box
    private lateinit var expandedFrame: Box
    private var openingRequested = false
    private val handler = Handler(Looper.getMainLooper())
    private var typing = false // true only while the window holds focus for the keyboard
    private val idleCollapse = Runnable { if (CardPin.idleMayCollapse(typing, input.text)) collapseCard() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::island.isInitialized) island.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showIsland()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (root != null) runCatching { unregisterReceiver(screenReceiver) }
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        running = false
        super.onDestroy()
    }

    // ---- building the window ----

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun showIsland() {
        val d = resources.displayMetrics.density
        // Small enough to sit inside the status-bar row without touching the icons (Ayman, 2026-10-31).
        val pillH = minOf(dp(26f), statusBarHeight() - dp(4f)).coerceAtLeast(dp(20f))
        val pillW = (pillH * 2.9f).toInt()
        val margin = dp(12f)
        idleFrame = CutoutPlacer.idleWindow(
            screenWidth = screenWidth(),
            cutout = cameraCutout(),
            pillWidth = pillW,
            pillHeight = pillH,
            gap = dp(6f),
            touchMargin = margin,
            side = PillSide.RIGHT,
            fallbackTop = dp(6f),
        )
        val pill = Box(idleFrame.left + margin, idleFrame.top + margin, idleFrame.right - margin, idleFrame.bottom - margin)
        val card = IslandGeometry.card(screenWidth(), screenHeight(), statusBarHeight(), d)
        expandedFrame = IslandGeometry.expandedWindow(pill, card, margin, screenWidth())

        island = IslandView(this).apply {
            this.pill = pill
            this.card = card
            listener = this@IslandService
        }
        val rootView = IslandRoot(this, onOutside = ::onOutsideTouch, onBack = ::onBackKey)
        rootView.addView(island, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildCardContent(rootView, card)

        params = WindowManager.LayoutParams(
            idleFrame.width,
            idleFrame.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT // absolute coords, never START/END (RTL mirrors those)
            x = idleFrame.left
            y = idleFrame.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            preferredDisplayModeId = highestRefreshModeId()
            title = "MououIsland"
        }
        island.expectFrame(idleFrame)
        windowManager.addView(rootView, params)
        root = rootView
        running = true
    }

    /** Card layout (spec §5.3): 12 dp padding, 48 dp mascot on the left (drawn by IslandView), reply on top, input row below. */
    private fun buildCardContent(rootView: FrameLayout, cardScreen: Box) {
        val card = Box(
            cardScreen.left - expandedFrame.left, cardScreen.top - expandedFrame.top,
            cardScreen.right - expandedFrame.left, cardScreen.bottom - expandedFrame.top,
        )
        val pad = dp(12f)
        val contentLeft = card.left + pad + dp(48f) + dp(10f)
        val contentRight = card.right - pad
        val rowH = dp(38f)
        val sendSize = dp(28f)

        reply = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8E8E96.toInt())
            text = "● Mouou"
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            gravity = Gravity.START or Gravity.TOP
            movementMethod = ScrollingMovementMethod() // long replies scroll inside; the card never grows
            isVerticalScrollBarEnabled = true
        }
        val replyH = card.height - 2 * pad - rowH - dp(4f)
        rootView.addView(reply, absolute(contentLeft, card.top + pad, contentRight - contentLeft, replyH))

        input = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
            hint = "احكي معايا..."
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            setPadding(dp(14f), 0, dp(14f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(19f).toFloat()
                setColor(0x14FFFFFF)
            }
            // Named setWindowTyping, NOT setFocusable: inside this EditText block that name is View.setFocusable,
            // which silently kept the window unfocusable (no keyboard) and pinned the card (v2.2.3 bug).
            setOnTouchListener { _, _ -> setWindowTyping(true); false }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
            }
        }
        val fieldRight = contentRight - sendSize - dp(8f)
        rootView.addView(input, absolute(contentLeft, card.bottom - pad - rowH, fieldRight - contentLeft, rowH))

        send = SendButton(this).apply { setOnClickListener { onSend() } }
        rootView.addView(send, absolute(contentRight - sendSize, card.bottom - pad - rowH + (rowH - sendSize) / 2, sendSize, sendSize))

        setContentAlpha(0f)
    }

    private fun absolute(left: Int, top: Int, width: Int, height: Int) =
        FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = left
            topMargin = top
        }

    // ---- open / close ----

    override fun onPillTap() {
        if (island.isExpandedOrOpening) return
        openingRequested = true
        applyFrame(expandedFrame) // resize FIRST; the morph starts in onWindowApplied
    }

    override fun onWindowApplied() {
        if (openingRequested) {
            openingRequested = false
            island.expand()
            restartIdleTimer()
        }
    }

    override fun onProgress(progress: Float) {
        // Content fades in over the last 40 % of the drop.
        setContentAlpha(((progress - 0.6f) / 0.4f).coerceIn(0f, 1f))
    }

    override fun onCollapsed() {
        applyFrame(idleFrame)
    }

    private fun collapseCard() {
        handler.removeCallbacks(idleCollapse)
        setWindowTyping(false)
        island.collapse()
    }

    private fun applyFrame(frame: Box) {
        val r = root ?: return
        params.x = frame.left
        params.y = frame.top
        params.width = frame.width
        params.height = frame.height
        island.expectFrame(frame)
        windowManager.updateViewLayout(r, params)
    }

    private fun setContentAlpha(a: Float) {
        val visibility = if (a > 0f) View.VISIBLE else View.INVISIBLE
        for (v in listOf(reply, input, send)) {
            v.alpha = a
            v.visibility = visibility
        }
    }

    // ---- typing, pinning and timers (spec §3, §5.6) ----

    private fun restartIdleTimer() {
        handler.removeCallbacks(idleCollapse)
        handler.postDelayed(idleCollapse, CHAT_IDLE_MS)
    }

    private fun onOutsideTouch() {
        if (island.isExpandedOrOpening && CardPin.outsideTapCollapses(input.text)) collapseCard()
    }

    private fun onBackKey(): Boolean {
        if (!island.isExpandedOrOpening) return false
        if (typing) {
            setWindowTyping(false) // keyboard closes, draft kept, 25 s timer restarts
            restartIdleTimer()
        } else {
            collapseCard()
        }
        return true
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        reply.setTextColor(0xFFFFFFFF.toInt())
        // The brain (Gemini) arrives in step 1.5; until then Mouou says so honestly.
        reply.text = "مازلت ما نجمش نخمّم توا… المخ يجي في الخطوة الجاية أيمون"
        reply.scrollTo(0, 0)
        restartIdleTimer()
    }

    /** Take window focus only while typing (spec §5.1); otherwise stay NOT_FOCUSABLE. */
    private fun setWindowTyping(focusable: Boolean) {
        val r = root ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        val wasFocusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (focusable == wasFocusable) {
            if (focusable) input.post { input.requestFocus(); imm.showSoftInput(input, 0) }
            return
        }
        typing = focusable
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            windowManager.updateViewLayout(r, params)
            input.post { input.requestFocus(); imm.showSoftInput(input, 0) }
            handler.removeCallbacks(idleCollapse)
        } else {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(r, params)
        }
    }

    // ---- screen facts ----

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.maximumWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.maximumWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.maximumWindowMetrics.windowInsets
            return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
    }

    /** The front-camera hole from DisplayCutout.boundingRects: the one touching the top edge. */
    private fun cameraCutout(): Box? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val cutout = windowManager.maximumWindowMetrics.windowInsets.displayCutout ?: return null
        val rect = cutout.boundingRects.minByOrNull { it.top } ?: return null
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }

    /** Ask for the fastest refresh mode at the current resolution (120 Hz on the S25 Ultra). */
    private fun highestRefreshModeId(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "الجزيرة", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Mouou Galaxy AI")
            .setContentText("الجزيرة ظاهرة")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "island"
        private const val NOTIFICATION_ID = 1
        private const val CHAT_IDLE_MS = 25_000L
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        @Volatile var running = false
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, IslandService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, IslandService::class.java))
    }
}
