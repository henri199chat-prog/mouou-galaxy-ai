package com.glteam.teambubbles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings home (Arabic RTL, dark space). Phase 1: permissions + show/hide the island. */
class MainActivity : ComponentActivity() {

    private var canOverlay by mutableStateOf(false)
    private var islandOn by mutableStateOf(false)
    private var accessibilityOn by mutableStateOf(false)
    private var hideAfterMs by mutableStateOf(0L)
    private val prefs by lazy { IslandPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            SettingsHome(
                canOverlay = canOverlay,
                islandOn = islandOn,
                accessibilityOn = accessibilityOn,
                onOverlay = ::openOverlaySettings,
                onToggleIsland = ::toggleIsland,
                onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onRedock = { IslandService.redock(this) },
                hideAfterMs = hideAfterMs,
                onHideAfter = { ms ->
                    prefs.hideAfterMs = ms
                    hideAfterMs = ms
                    IslandService.settingsChanged(this)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        canOverlay = Settings.canDrawOverlays(this)
        islandOn = IslandService.running
        accessibilityOn = ScreenService.instance != null
        hideAfterMs = prefs.hideAfterMs
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun toggleIsland() {
        if (islandOn) IslandService.stop(this) else IslandService.start(this)
        islandOn = !islandOn
    }
}

@Composable
private fun SettingsHome(
    canOverlay: Boolean,
    islandOn: Boolean,
    accessibilityOn: Boolean,
    onOverlay: () -> Unit,
    onToggleIsland: () -> Unit,
    onAccessibility: () -> Unit,
    onRedock: () -> Unit,
    hideAfterMs: Long,
    onHideAfter: (Long) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6A3FD0), background = Color(0xFF030308))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF030308)).systemBarsPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Mouou Galaxy AI", color = Color.White, fontSize = 26.sp)
                Text("يسمعك، يشوف شاشتك ويجاوبك", color = Color(0xFF8E8E96), fontSize = 15.sp)
                if (!canOverlay) {
                    Text("⛔ لازم تعطيه إذن «الظهور فوق التطبيقات»", color = Color(0xFFFF5C6C), fontSize = 14.sp)
                    Button(onClick = onOverlay) { Text("منح الإذن") }
                } else {
                    Text("✅ إذن الظهور فوق التطبيقات", color = Color(0xFF39FF88), fontSize = 14.sp)
                    if (islandOn) {
                        OutlinedButton(onClick = onToggleIsland) { Text("خبّي الجزيرة") }
                        OutlinedButton(onClick = onRedock) { Text("رجّع للكاميرا") }
                    } else {
                        Button(onClick = onToggleIsland) { Text("ورّي الجزيرة") }
                    }
                }
                if (accessibilityOn) {
                    Text("✅ الجزيرة فوق شريط الإشعارات، حذا الكاميرا", color = Color(0xFF39FF88), fontSize = 14.sp)
                } else {
                    Text(
                        "باش الجزيرة تقعد حذا الكاميرا واللمسة تخدم من أول مرة، شغّل «الشاشة» في إعدادات الوصول. " +
                            "توا هي تحت شريط الإشعارات باش اللمسة تخدم ديما.",
                        color = Color(0xFF8E8E96), fontSize = 13.sp,
                    )
                    Button(onClick = onAccessibility) { Text("إعدادات الوصول") }
                    Text(
                        "إذا قالك «إعداد مقيّد»: الإعدادات ← التطبيقات ← هذا التطبيق ← ⋮ ← «السماح بالإعدادات المقيّدة»، وارجع شغّلو.",
                        color = Color(0xFF8E8E96), fontSize = 12.sp,
                    )
                }
                Text("يختفي وحدو كي ما تستعملوش بعد:", color = Color.White, fontSize = 15.sp)
                for (row in HideAfter.CHOICES_MS.toList().chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ms in row) {
                            if (ms == hideAfterMs) {
                                Button(onClick = { onHideAfter(ms) }) { Text(HideAfter.label(ms)) }
                            } else {
                                OutlinedButton(onClick = { onHideAfter(ms) }) { Text(HideAfter.label(ms)) }
                            }
                        }
                    }
                }
                Text(
                    "كي تبدا تخدم معاه ما يختفيش. باش ترجعو: ورّي / خبّي في الإشعار، " +
                        "ولا اضغط حذا الكاميرا إذا «الشاشة» مشعّلة. «Mouu» بالصوت تجي في المرحلة الجاية.",
                    color = Color(0xFF8E8E96), fontSize = 12.sp,
                )
            }
        }
    }
}
