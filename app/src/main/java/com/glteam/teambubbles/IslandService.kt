package com.glteam.teambubbles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * Foreground service that owns the floating island window (spec §5.1).
 * One TYPE_APPLICATION_OVERLAY window, pill-only size (pill + 12 dp touch margin), never MATCH_PARENT,
 * NOT_FOCUSABLE | NOT_TOUCH_MODAL so every touch outside the pill reaches the app underneath.
 */
class IslandService : Service() {

    private lateinit var windowManager: WindowManager
    private var islandView: IslandView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        if (Settings.canDrawOverlays(this)) showIsland() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        islandView?.let { runCatching { windowManager.removeView(it) } }
        islandView = null
        running = false
        super.onDestroy()
    }

    private fun showIsland() {
        val d = resources.displayMetrics.density
        val pillW = (106 * d).toInt()
        val pillH = (34 * d).toInt()
        val margin = (12 * d).toInt()
        val window = CutoutPlacer.idleWindow(
            screenWidth = screenWidth(),
            cutout = cameraCutout(),
            pillWidth = pillW,
            pillHeight = pillH,
            gap = (8 * d).toInt(),
            touchMargin = margin,
            side = PillSide.RIGHT,
            fallbackTop = (6 * d).toInt(),
        )

        val params = WindowManager.LayoutParams(
            window.width,
            window.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT // absolute coords, never START/END (RTL mirrors those)
            x = window.left
            y = window.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            preferredDisplayModeId = highestRefreshModeId()
            title = "MououIsland"
        }

        val view = IslandView(this, margin.toFloat())
        windowManager.addView(view, params)
        islandView = view
        running = true
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.maximumWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

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
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
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

        @Volatile var running = false
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, IslandService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, IslandService::class.java))
    }
}
