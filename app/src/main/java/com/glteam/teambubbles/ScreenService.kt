package com.glteam.teambubbles

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service «… – الشاشة» (spec §9). Step 1.4 uses it for one thing: hosting the island as a
 * TYPE_ACCESSIBILITY_OVERLAY window, which sits above the status bar, so taps beside the camera always
 * reach the pill (spec §19 bug 14). Screen reading (screenshot + text) comes in step 1.6.
 */
class ScreenService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        IslandService.onAccessibilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) {
            instance = null
            IslandService.onAccessibilityChanged() // falls back to the normal overlay (spec §19 bug 16)
        }
    }

    companion object {
        @Volatile var instance: ScreenService? = null
            private set
    }
}
