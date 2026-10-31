package com.glteam.teambubbles

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Accessibility service «… – الشاشة» (spec §9).
 * 1. Hosts the island as TYPE_ACCESSIBILITY_OVERLAY (above the status bar, reliable taps, spec §19 bug 14).
 * 2. Sees the screen when Ayman asks: a screenshot (longest side ≤ 1024 px, JPEG 70) and the text of the
 *    other apps' windows. Kept in memory for that one question only: never stored, never logged.
 * 3. Knows which app is in front (for the live context).
 */
class ScreenService : AccessibilityService() {

    private val encoder = Executors.newSingleThreadExecutor()

    /** Package of the app in front, ignoring Mouou itself, the system UI and keyboards. */
    @Volatile var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
        IslandService.onAccessibilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.android.systemui" || cls.contains("InputMethod") || pkg.contains("honeyboard")) return
        foregroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        encoder.shutdownNow()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) {
            instance = null
            IslandService.onAccessibilityChanged() // falls back to the normal overlay (spec §19 bug 16)
        }
    }

    /** The app in front as a readable name, e.g. «Instagram (com.instagram.android)». */
    fun foregroundAppLabel(): String? {
        val pkg = foregroundPackage ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return if (label.isNullOrBlank()) pkg else "$label ($pkg)"
    }

    /**
     * Screenshot + window text, delivered on the main thread. The island must already be invisible
     * (the caller sets its alpha to 0 and waits ~140 ms, spec §9). Any failure still delivers the text.
     */
    fun capture(onDone: (ScreenCapture) -> Unit) {
        val text = runCatching { readWindowsText() }.getOrDefault("")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(ScreenCapture(null, text))
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                encoder.execute {
                    val jpeg = runCatching { encode(result) }.getOrNull()
                    runCatching { result.hardwareBuffer.close() }
                    mainExecutor.execute { onDone(ScreenCapture(jpeg, text)) }
                }
            }

            override fun onFailure(errorCode: Int) {
                DebugLog.add("screenshot failed: code $errorCode")
                onDone(ScreenCapture(null, text))
            }
        })
    }

    private fun encode(result: ScreenshotResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return null
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        val (w, h) = ScreenImage.fit(soft.width, soft.height)
        val scaled = if (w == soft.width && h == soft.height) soft else Bitmap.createScaledBitmap(soft, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ScreenImage.JPEG_QUALITY, out)
        if (scaled !== soft) scaled.recycle()
        soft.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Text of every window except Mouou's own (spec §9 limits applied by [ScreenText]). */
    private fun readWindowsText(): String {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() != packageName }
        return ScreenText.collect(roots.map(::NodeAdapter))
    }

    private class NodeAdapter(private val node: AccessibilityNodeInfo) : TextNode {
        override val text: CharSequence? get() = node.text
        override val description: CharSequence? get() = node.contentDescription
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): TextNode? = node.getChild(index)?.let(::NodeAdapter)
    }

    companion object {
        @Volatile var instance: ScreenService? = null
            private set
    }
}
