package com.glteam.teambubbles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings the island back after a reboot or an app update, if Ayman had it on (spec §17).
 * Without the overlay permission or the accessibility host there is nowhere to show it, so it stays off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!IslandPrefs(context).islandOn || IslandService.running) return
        if (!Settings.canDrawOverlays(context) && ScreenService.instance == null) return
        runCatching { context.startForegroundService(Intent(context, IslandService::class.java)) }
            .onFailure { DebugLog.add("boot start refused: ${it.javaClass.simpleName}") }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_USER_UNLOCKED,
        )
    }
}
