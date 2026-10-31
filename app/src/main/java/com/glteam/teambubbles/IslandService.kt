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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Foreground service that owns the floating island window (spec §5, §6).
 *
 * The window lives in one of two hosts: with the accessibility service on, it is a
 * TYPE_ACCESSIBILITY_OVERLAY window, above the status bar, docked beside the camera (taps always land);
 * without it, a TYPE_APPLICATION_OVERLAY window docked just below the status bar (the status bar would
 * otherwise swallow the first taps, spec §19 bug 14). Idle, the window is only the pill + 12 dp margin;
 * on tap it is first resized to the expanded frame and only then does the morph start (no blank frame).
 * The window is never resized while a finger is down; dragging only moves it.
 */
class IslandService : Service(), IslandView.Listener {

    private lateinit var params: WindowManager.LayoutParams
    private var hostWm: WindowManager? = null
    private var root: IslandRoot? = null
    private lateinit var island: IslandView
    private lateinit var reply: TextView
    private lateinit var input: EditText
    private lateinit var send: SendButton
    private lateinit var prefs: IslandPrefs

    private var margin = 0
    private lateinit var pill: Box
    private lateinit var idleFrame: Box
    private lateinit var expandedFrame: Box
    private var openingRequested = false
    private var windowIsIdle = true // the window is pill-sized (not the expanded card frame)
    private var redockAfterCollapse = false
    private var typing = false // true only while the window holds focus for the keyboard
    private val handler = Handler(Looper.getMainLooper())
    private val idleCollapse = Runnable { if (!busy && CardPin.idleMayCollapse(typing, input.text)) collapseCard() }
    private val autoHide = Runnable { hideIsland() }
    private var inCameraZone = false // hidden, and the window shrank to the camera hole (tap it to show Mouou)
    private val moveToCameraZone = Runnable {
        val cam = cameraCutout() ?: return@Runnable
        if (!island.isHiddenOrHiding || !usingAccessibility) return@Runnable
        inCameraZone = true
        applyFrame(cameraTapZone(cam, statusBarHeight(), dp(14f), screenWidth()))
    }

    // Brain (spec §10): one background thread, one request per message.
    private val brainThread = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val brain = Brain(OkHttpTransport(), log = DebugLog::add)
    private val memory = ConvoMemory()
    private lateinit var vault: KeyVault
    private lateinit var brainSettings: BrainSettings
    private var busy = false
    private var dots = 0
    private val thinkingDots = object : Runnable {
        override fun run() {
            if (!busy) return
            dots = dots % 3 + 1
            reply.text = "💭 " + ".".repeat(dots)
            handler.postDelayed(this, 380)
        }
    }

    // Spring that carries the pill to where it settles after a drag (magnet or clamp).
    private val settleSpring = MorphSpring()
    private var settleFrom = Box(0, 0, 0, 0)
    private var settleTo = Box(0, 0, 0, 0)
    private var settleLastMs = 0L
    private val settleFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            val moving = settleSpring.step((now - settleLastMs).toFloat().coerceAtLeast(1f))
            settleLastMs = now
            val p = settleSpring.value
            movePillTo(
                (settleFrom.left + (settleTo.left - settleFrom.left) * p).toInt(),
                (settleFrom.top + (settleTo.top - settleFrom.top) * p).toInt(),
            )
            if (moving) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::island.isInitialized) island.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this) && ScreenService.instance == null) {
            stopSelf()
            return
        }
        prefs = IslandPrefs(this)
        vault = KeyVault(this)
        brainSettings = BrainSettings(this)
        current = this
        showIsland()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REDOCK && root != null) redock()
        if (intent?.action == ACTION_TOGGLE && root != null) {
            when {
                island.isHiddenOrHiding -> revealIsland()
                !windowIsIdle || island.isExpandedOrOpening -> {
                    collapseCard() // close the card first, then hide once it is a pill again
                    handler.postDelayed({ hideIsland() }, HIDE_AFTER_COLLAPSE_MS)
                }
                else -> hideIsland()
            }
        }
        if (intent?.action == ACTION_SETTINGS_CHANGED && root != null) noteActivity()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        brainThread.shutdownNow()
        Choreographer.getInstance().removeFrameCallback(settleFrame)
        if (root != null) runCatching { unregisterReceiver(screenReceiver) }
        removeWindow()
        root = null
        if (current === this) current = null
        running = false
        super.onDestroy()
    }

    // ---- window host (accessibility overlay or normal overlay) ----

    private val usingAccessibility get() = ScreenService.instance != null

    private fun currentHost(): Pair<WindowManager, Int> {
        val acc = ScreenService.instance
        return if (acc != null) {
            (acc.getSystemService(WINDOW_SERVICE) as WindowManager) to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            (getSystemService(WINDOW_SERVICE) as WindowManager) to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    private fun addWindow() {
        val r = root ?: return
        val (wm, type) = currentHost()
        params.type = type
        island.expectFrame(Box(params.x, params.y, params.x + params.width, params.y + params.height))
        wm.addView(r, params)
        hostWm = wm
    }

    private fun removeWindow() {
        val r = root ?: return
        hostWm?.let { wm -> runCatching { wm.removeView(r) } }
        hostWm = null
    }

    /** The accessibility service was switched on or off: move the window to the right host and dock. */
    private fun switchHost() {
        if (root == null) return
        if (!usingAccessibility && !Settings.canDrawOverlays(this)) {
            stopSelf() // no host left
            return
        }
        Choreographer.getInstance().removeFrameCallback(settleFrame)
        if (island.isExpandedOrOpening || typing) {
            setWindowTyping(false)
            island.snapClosed()
            setContentAlpha(0f)
        }
        removeWindow()
        root?.alpha = 1f
        redockAfterCollapse = false
        pill = prefs.customPill(pill.width, pill.height)?.let { clamp(it) } ?: dock()
        idleFrame = pill.inflate(margin)
        island.pill = pill
        setParamsFrame(idleFrame)
        windowIsIdle = true
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        handler.removeCallbacks(moveToCameraZone)
        inCameraZone = false
        addWindow()
        emerge()
        noteActivity()
    }

    // ---- building the window ----

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private val density get() = resources.displayMetrics.density

    private fun showIsland() {
        // Small enough to sit inside the status-bar row without touching the icons (Ayman, 2026-10-31).
        val pillH = minOf(dp(26f), statusBarHeight() - dp(4f)).coerceAtLeast(dp(20f))
        val pillW = (pillH * 2.9f).toInt()
        margin = dp(12f)
        pill = prefs.customPill(pillW, pillH)?.let { clamp(it) } ?: dock(pillW, pillH)
        idleFrame = pill.inflate(margin)

        island = IslandView(this).apply {
            this.pill = this@IslandService.pill
            listener = this@IslandService
        }
        val rootView = IslandRoot(this, onOutside = ::onOutsideTouch, onBack = ::onBackKey)
        rootView.addView(island, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildCardContent(rootView)

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
        root = rootView
        addWindow()
        running = true
        emerge()
        noteActivity()
    }

    /** Come out of the camera (or out of the pill's own centre when the phone reports no camera hole). */
    private fun emerge() {
        val cam = cameraCutout()
        island.emergeFrom((cam?.centerX ?: pill.centerX).toFloat(), (cam?.centerY ?: pill.centerY).toFloat())
    }

    /** Dock: beside the camera above the status bar (accessibility host), or just below the status bar. */
    private fun dock(w: Int = pill.width, h: Int = pill.height): Box =
        if (usingAccessibility) {
            CutoutPlacer.idleWindow(screenWidth(), cameraCutout(), w, h, dp(6f), 0, PillSide.RIGHT, dp(6f))
        } else {
            IslandGeometry.dockBelowStatusBar(screenWidth(), cameraCutout(), w, h, statusBarHeight(), dp(2f))
        }

    private fun clamp(p: Box) = IslandGeometry.clampPill(p, screenWidth(), navBarTop(), dp(8f))

    /** Card children (spec §5.3): reply on top, input row below; positions are set on every open. */
    private fun buildCardContent(rootView: FrameLayout) {
        reply = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF8E8E96.toInt())
            text = "● Mouou"
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            gravity = Gravity.START or Gravity.TOP
            movementMethod = ScrollingMovementMethod() // long replies scroll inside; the card never grows
            isVerticalScrollBarEnabled = true
            setOnClickListener { openAccessibilitySettings() }
        }
        input = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
            hint = "احكي معايا..."
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            // Empty field = right-to-left, so the cursor starts on the right like the hint (2.2.6 had it on the left).
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
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
        send = SendButton(this).apply { setOnClickListener { onSend() } }
        rootView.addView(reply, absolute(0, 0, 1, 1))
        rootView.addView(input, absolute(0, 0, 1, 1))
        rootView.addView(send, absolute(0, 0, 1, 1))
        setContentAlpha(0f)
    }

    /** Places the card children for a card at [cardScreen] inside a window at [frame]. */
    private fun layoutCardContent(cardScreen: Box, frame: Box) {
        val card = cardScreen.offset(-frame.left, -frame.top)
        val pad = dp(12f)
        val contentLeft = card.left + pad + dp(48f) + dp(10f)
        val contentRight = card.right - pad
        val rowH = dp(38f)
        val sendSize = dp(28f)
        val replyH = card.height - 2 * pad - rowH - dp(4f)
        reply.layoutParams = absolute(contentLeft, card.top + pad, contentRight - contentLeft, replyH)
        val fieldRight = contentRight - sendSize - dp(8f)
        input.layoutParams = absolute(contentLeft, card.bottom - pad - rowH, fieldRight - contentLeft, rowH)
        send.layoutParams = absolute(contentRight - sendSize, card.bottom - pad - rowH + (rowH - sendSize) / 2, sendSize, sendSize)
    }

    private fun absolute(left: Int, top: Int, width: Int, height: Int) =
        FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = left
            topMargin = top
        }

    // ---- auto-hide (Ayman, 2026-10-31) ----

    /** Any use of Mouou restarts the auto-hide countdown; it only runs while nobody is using him. */
    private fun noteActivity() {
        handler.removeCallbacks(autoHide)
        val after = prefs.hideAfterMs
        if (HideAfter.mayHide(after, cardOpen = !windowIsIdle || island.isExpandedOrOpening, typing = typing, fingerDown = island.isFingerDown)) {
            handler.postDelayed(autoHide, after)
        }
    }

    private fun hideIsland() {
        handler.removeCallbacks(autoHide)
        if (!windowIsIdle || island.isExpandedOrOpening || typing || island.isFingerDown) return // in use: never hide
        val cam = cameraCutout()
        island.hideInto((cam?.centerX ?: pill.centerX).toFloat(), (cam?.centerY ?: pill.centerY).toFloat())
        if (!usingAccessibility) {
            // Below the status bar an invisible window would block the app under it: let touches pass through.
            // (With the accessibility host the spot beside the camera stays tappable to show Mouou again.)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            root?.let { hostWm?.updateViewLayout(it, params) }
        } else {
            // Once fully hidden, the window becomes the camera hole itself: tap the camera to bring Mouou back.
            handler.postDelayed(moveToCameraZone, VisibilityAnim.DURATION_MS + 40)
        }
    }

    private fun revealIsland() {
        handler.removeCallbacks(moveToCameraZone)
        if (inCameraZone) {
            inCameraZone = false
            applyFrame(idleFrame) // back to the pill's window; drawing follows the real window position
        }
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            root?.let { hostWm?.updateViewLayout(it, params) }
        }
        island.show()
        noteActivity()
    }

    override fun onRevealTap() = revealIsland()

    // ---- open / close ----

    override fun onPillTap() {
        if (island.isExpandedOrOpening) return
        handler.removeCallbacks(autoHide) // in use
        Choreographer.getInstance().removeFrameCallback(settleFrame)
        val card = IslandGeometry.cardFor(pill, screenWidth(), screenHeight(), statusBarHeight(), navBarTop(), density)
        expandedFrame = IslandGeometry.expandedWindow(pill, card, margin, screenWidth())
        island.card = card
        layoutCardContent(card, expandedFrame)
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
        if (island.isFingerDown) {
            // Never resize under a finger (spec §5.5): try again once the touch is over.
            handler.postDelayed({ if (!island.isExpandedOrOpening) onCollapsed() }, 50)
            return
        }
        applyFrame(idleFrame)
        noteActivity()
        if (redockAfterCollapse) {
            redockAfterCollapse = false
            settleTo(dock())
        }
    }

    private fun collapseCard() {
        handler.removeCallbacks(idleCollapse)
        root?.alpha = 1f
        setWindowTyping(false)
        island.collapse()
    }

    private fun setParamsFrame(frame: Box) {
        params.x = frame.left
        params.y = frame.top
        params.width = frame.width
        params.height = frame.height
    }

    private fun applyFrame(frame: Box) {
        val r = root ?: return
        windowIsIdle = frame == idleFrame
        setParamsFrame(frame)
        island.expectFrame(frame)
        hostWm?.updateViewLayout(r, params)
    }

    private fun setContentAlpha(a: Float) {
        val visibility = if (a > 0f) View.VISIBLE else View.INVISIBLE
        for (v in listOf(reply, input, send)) {
            v.alpha = a
            v.visibility = visibility
        }
    }

    // ---- moving (spec §3 "Moving", §6) ----

    /** Moves the idle pill window to (left, top); the size never changes, so no resize under the finger. */
    private fun movePillTo(left: Int, top: Int) {
        val r = root ?: return
        if (!windowIsIdle) return // never move a pill-sized frame while the window is still card-sized
        pill = pill.moveTo(left, top)
        idleFrame = pill.inflate(margin)
        island.pill = pill
        params.x = idleFrame.left
        params.y = idleFrame.top
        island.expectFrame(idleFrame)
        hostWm?.updateViewLayout(r, params)
    }

    override fun onDragStart() {
        handler.removeCallbacks(autoHide)
        Choreographer.getInstance().removeFrameCallback(settleFrame)
    }

    override fun onDrag(dx: Float, dy: Float) {
        if (island.isHiddenOrHiding) return // an invisible pill is never dragged around
        if (!windowIsIdle) return // one finger on the card (or a closing card) does not move it; two fingers do
        movePillTo(pill.left + dx.toInt(), pill.top + dy.toInt())
    }

    override fun onDragEnd(vx: Float, vy: Float) {
        if (island.isHiddenOrHiding) return
        if (!windowIsIdle) return
        val dock = dock()
        val target = if (IslandGeometry.shouldDock(pill, dock, density)) {
            prefs.saveDocked()
            buzz()
            dock
        } else {
            clamp(pill).also { prefs.saveCustom(it) }
        }
        settleTo(target)
        noteActivity()
    }

    private fun settleTo(target: Box) {
        settleFrom = pill
        settleTo = target
        settleSpring.snapTo(0f)
        settleSpring.target = 1f
        settleLastMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().removeFrameCallback(settleFrame)
        Choreographer.getInstance().postFrameCallback(settleFrame)
    }

    override fun onTwoFingerDrag(dx: Float, dy: Float) {
        if (island.isHiddenOrHiding) return
        val ix = dx.toInt()
        val iy = dy.toInt()
        if (ix == 0 && iy == 0) return
        if (windowIsIdle) {
            movePillTo(pill.left + ix, pill.top + iy)
            return
        }
        // Expanded: move the whole island (pill, card and window together; the drawing stays identical).
        val r = root ?: return
        pill = pill.offset(ix, iy)
        idleFrame = pill.inflate(margin)
        expandedFrame = expandedFrame.offset(ix, iy)
        island.pill = pill
        island.card = island.card.offset(ix, iy)
        params.x = expandedFrame.left
        params.y = expandedFrame.top
        island.expectFrame(expandedFrame)
        hostWm?.updateViewLayout(r, params)
    }

    override fun onTwoFingerEnd() {
        if (island.isHiddenOrHiding) return
        noteActivity()
        if (!windowIsIdle) {
            prefs.saveCustom(pill)
            return
        }
        val target = clamp(pill)
        prefs.saveCustom(target)
        settleTo(target)
    }

    override fun onFlingUp(): Boolean {
        if (!island.isExpandedOrOpening) return false // hiding the pill by fling is an opt-in setting (phase 4)
        collapseCard()
        return true
    }

    override fun onLongPress() {
        if (island.isHiddenOrHiding) {
            revealIsland()
            return
        }
        noteActivity()
        buzz() // listening (STT) arrives in phase 2; for now Mouou reacts with a squash and a tick
    }

    private fun redock() {
        prefs.saveDocked()
        if (island.isHiddenOrHiding) revealIsland() // bring him out first, then back to the camera
        if (!windowIsIdle) {
            redockAfterCollapse = true // move only once the window is pill-sized again
            collapseCard()
            return
        }
        settleTo(dock())
    }

    private fun buzz() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE) // EFFECT_TICK needs API 29
        }
        v.vibrate(effect)
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
        if (text.isEmpty() || busy) return // one request per message; wait for the answer
        val screen = ScreenService.instance
        val wantScreen = brainSettings.attachScreen
        if (wantScreen && screen == null && SystemPrompt.isAboutScreen(text)) {
            // Asked about the screen with the screen service off: say so and offer the switch (spec §9).
            input.text.clear()
            showScreenOffHint()
            return
        }
        input.text.clear()
        busy = true
        screenOffHint = false
        handler.removeCallbacks(idleCollapse)
        reply.setTextColor(0xFFFFFFFF.toInt())
        reply.scrollTo(0, 0)
        island.hop()
        if (wantScreen && screen != null) {
            // Spec §9: island invisible → ~140 ms → screenshot + window text → island back → send.
            reply.text = "👁 نشوف في الشاشة…"
            island.setScanning(true)
            root?.alpha = 0f
            var delivered = false
            fun deliver(cap: ScreenCapture?) {
                if (delivered) return // the capture and the watchdog can both arrive: only the first counts
                delivered = true
                root?.alpha = 1f // never leave Mouou invisible
                island.setScanning(false)
                if (cap?.jpegBase64 != null) reply.text = "👁 شاف الشاشة"
                askBrain(text, cap, ScreenService.instance?.foregroundAppLabel())
            }
            handler.postDelayed({
                runCatching { screen.capture { cap -> deliver(cap) } }
                    .onFailure { DebugLog.add("capture error: ${it.javaClass.simpleName}"); deliver(null) }
            }, SCREEN_HIDE_MS)
            // Watchdog: if the screenshot never answers (service switched off mid-way…), carry on without it.
            handler.postDelayed({ if (!delivered) { DebugLog.add("capture timeout"); deliver(null) } }, CAPTURE_TIMEOUT_MS)
        } else {
            askBrain(text, null, screen?.foregroundAppLabel())
        }
    }

    private fun askBrain(question: String, capture: ScreenCapture?, foregroundApp: String?) {
        dots = 0
        handler.postDelayed(thinkingDots, if (capture?.jpegBase64 != null) 450 else 0)
        island.setThinking(true)
        val key = vault.load()
        val models = brainSettings.models
        val useSearch = brainSettings.useSearch
        val facts = brainSettings.facts()
        val history = memory.history()
        val userTurn = if (capture != null) {
            SystemPrompt.userTurnWithScreen(question, capture.text, capture.jpegBase64 != null)
        } else {
            SystemPrompt.userTurn(question, screenAvailable = false)
        }
        val image = capture?.jpegBase64 // kept in memory for this one request only, never stored (spec §9)
        val prompt = SystemPrompt.withContext(SystemPrompt.mouou(), nowInTunis(), facts.all, foregroundApp)
        brainThread.execute {
            val result = runCatching { brain.ask(key, models, prompt, history, userTurn, useSearch, image) }
                .getOrElse { e -> DebugLog.add("brain crash: ${e.javaClass.simpleName}"); Brain.Result.Failure(BrainError.SERVER) }
            handler.post { showResult(question, result, facts) }
        }
    }

    private var screenOffHint = false

    private fun showScreenOffHint() {
        screenOffHint = true
        reply.setTextColor(0xFFFF9F1C.toInt())
        reply.text = "🙈 رؤية الشاشة مسكّرة توا. اضغط هنا باش تشعّل «الشاشة» في إعدادات الوصول."
        reply.scrollTo(0, 0)
        island.glanceAtReply()
        restartIdleTimer()
    }

    private fun openAccessibilitySettings() {
        if (!screenOffHint) return
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        collapseCard()
    }

    private fun showResult(question: String, result: Brain.Result, facts: Facts) {
        if (root == null) return
        busy = false
        handler.removeCallbacks(thinkingDots)
        island.setThinking(false)
        when (result) {
            is Brain.Result.Answer -> {
                memory.add(true, question) // history: text only, no screen text and no image (spec §10.4)
                memory.add(false, result.reply.rawJson.ifBlank { result.reply.reply })
                if (result.reply.remember.isNotBlank() && facts.remember(result.reply.remember)) brainSettings.saveFacts(facts)
                reply.setTextColor(0xFFFFFFFF.toInt())
                reply.text = result.reply.reply
                if (result.reply.emotion in HAPPY_EMOTIONS) island.hop()
            }
            is Brain.Result.Failure -> {
                reply.setTextColor(0xFFFF9F1C.toInt())
                reply.text = result.error.darija
            }
        }
        reply.scrollTo(0, 0)
        island.glanceAtReply()
        restartIdleTimer()
    }

    private fun nowInTunis(): String {
        val f = java.text.SimpleDateFormat("EEEE d MMMM yyyy، HH:mm", java.util.Locale.forLanguageTag("ar-TN"))
        f.timeZone = java.util.TimeZone.getTimeZone("Africa/Tunis")
        return f.format(java.util.Date())
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
        island.setAttentive(focusable)
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            hostWm?.updateViewLayout(r, params)
            input.post { input.requestFocus(); imm.showSoftInput(input, 0) }
            handler.removeCallbacks(idleCollapse)
        } else {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            hostWm?.updateViewLayout(r, params)
        }
    }

    // ---- screen facts ----

    private val appWm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) appWm.maximumWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) appWm.maximumWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return appWm.maximumWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        }
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun navBarTop(): Int {
        val nav = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appWm.maximumWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else 0
        return screenHeight() - nav
    }

    /** The front-camera hole from DisplayCutout.boundingRects: the one touching the top edge. */
    private fun cameraCutout(): Box? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val cutout = appWm.maximumWindowMetrics.windowInsets.displayCutout ?: return null
        val rect = cutout.boundingRects.minByOrNull { it.top } ?: return null
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }

    /** Ask for the fastest refresh mode at the current resolution (120 Hz on the S25 Ultra). */
    private fun highestRefreshModeId(): Int {
        @Suppress("DEPRECATION")
        val display = appWm.defaultDisplay ?: return 0
        val currentMode = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "الجزيرة", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Mouou Galaxy AI")
            .setContentText("الجزيرة خدّامة")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "ورّي / خبّي", toggle).build())
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
        private const val HIDE_AFTER_COLLAPSE_MS = 450L
        private const val SCREEN_HIDE_MS = 140L
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        private val HAPPY_EMOTIONS = setOf("happy", "laugh", "excited", "love", "wink")
        private const val ACTION_REDOCK = "com.glteam.teambubbles.REDOCK"
        private const val ACTION_TOGGLE = "com.glteam.teambubbles.TOGGLE"
        private const val ACTION_SETTINGS_CHANGED = "com.glteam.teambubbles.SETTINGS_CHANGED"
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private var current: IslandService? = null

        @Volatile var running = false
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, IslandService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, IslandService::class.java))

        /** «رجّع للكاميرا»: back to the dock beside the camera. */
        fun redock(context: Context) {
            if (running) context.startService(Intent(context, IslandService::class.java).setAction(ACTION_REDOCK))
        }

        /** Settings changed (e.g. auto-hide time): restart the countdown with the new value. */
        fun settingsChanged(context: Context) {
            if (running) context.startService(Intent(context, IslandService::class.java).setAction(ACTION_SETTINGS_CHANGED))
        }

        /** Called by ScreenService on the main thread when it connects or disconnects. */
        fun onAccessibilityChanged() {
            current?.switchHost()
        }
    }
}
