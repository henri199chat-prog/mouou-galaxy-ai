package com.glteam.teambubbles

import android.Manifest
import android.content.ClipboardManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/** Settings home (Arabic RTL, dark space). Phase 1: permissions, island, auto-hide and the brain (Gemini). */
class MainActivity : ComponentActivity() {

    private var canOverlay by mutableStateOf(false)
    private var islandOn by mutableStateOf(false)
    private var accessibilityOn by mutableStateOf(false)
    private var hideAfterMs by mutableStateOf(0L)
    private val prefs by lazy { IslandPrefs(this) }
    private val vault by lazy { KeyVault(this) }
    private val brainSettings by lazy { BrainSettings(this) }
    private var brain by mutableStateOf(BrainUi())

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
                brain = brain,
                brainActions = BrainActions(
                    onKeyInput = { brain = brain.copy(keyInput = it) },
                    onToggleShow = { brain = brain.copy(showKey = !brain.showKey) },
                    onPaste = {
                        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                        brain = brain.copy(keyInput = KeySanitizer.clean(text))
                    },
                    onSave = ::saveKey,
                    onTest = ::testKey,
                    onSearch = { brainSettings.useSearch = it; brain = brain.copy(useSearch = it) },
                    onClearFacts = {
                        brainSettings.saveFacts(Facts())
                        brain = brain.copy(factsCount = 0)
                    },
                    onToggleLog = { brain = brain.copy(showLog = !brain.showLog, log = DebugLog.text()) },
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        canOverlay = Settings.canDrawOverlays(this)
        islandOn = IslandService.running
        accessibilityOn = ScreenService.instance != null
        hideAfterMs = prefs.hideAfterMs
        brain = brain.copy(
            keySaved = vault.has(),
            useSearch = brainSettings.useSearch,
            factsCount = brainSettings.facts().all.size,
            models = brainSettings.models,
        )
    }

    private fun saveKey() {
        val key = KeySanitizer.clean(brain.keyInput)
        if (key.isEmpty()) return
        vault.save(key)
        brain = brain.copy(keyInput = "", keySaved = true, result = if (KeySanitizer.looksValid(key)) "تسجّل ✅ (اضغط «اختبار» باش نتأكدو)" else "تسجّل، أما الشكل غريب: لازم يبدا بـ AIza")
    }

    /** Spec §10.8: tests the typed key (saving it) or the saved one: models.list + a ping on 2 models. */
    private fun testKey() {
        val typed = KeySanitizer.clean(brain.keyInput)
        if (typed.isNotEmpty()) vault.save(typed)
        val key = typed.ifEmpty { vault.load().orEmpty() }
        if (key.isEmpty()) {
            brain = brain.copy(result = BrainError.NO_KEY.darija)
            return
        }
        brain = brain.copy(testing = true, keyInput = "", keySaved = true, result = "نجرّب…")
        Thread {
            val r = runCatching { Brain(OkHttpTransport(), log = DebugLog::add).testKey(key) }
                .getOrElse { Brain.KeyTest(false, "صار مشكل: ${it.javaClass.simpleName}", emptyList(), emptyList()) }
            if (r.ok) brainSettings.models = r.models
            runOnUiThread {
                brain = brain.copy(
                    testing = false,
                    models = if (r.ok) r.models else brain.models,
                    result = (listOf(r.summary) + r.pings).joinToString("\n"),
                )
            }
        }.start()
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
    brain: BrainUi,
    brainActions: BrainActions,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6A3FD0), background = Color(0xFF030308))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF030308)).systemBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
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
                BrainSection(brain, brainActions)
            }
        }
    }
}

/** What the brain section shows. */
data class BrainUi(
    val keyInput: String = "",
    val showKey: Boolean = false,
    val keySaved: Boolean = false,
    val testing: Boolean = false,
    val result: String = "",
    val models: List<String> = emptyList(),
    val useSearch: Boolean = true,
    val factsCount: Int = 0,
    val showLog: Boolean = false,
    val log: String = "",
)

class BrainActions(
    val onKeyInput: (String) -> Unit,
    val onToggleShow: () -> Unit,
    val onPaste: () -> Unit,
    val onSave: () -> Unit,
    val onTest: () -> Unit,
    val onSearch: (Boolean) -> Unit,
    val onClearFacts: () -> Unit,
    val onToggleLog: () -> Unit,
)

/** Spec §16 section 2 «العقل (Gemini)»: key field, paste, show/hide, save, test, privacy note, search, facts, log. */
@Composable
private fun BrainSection(ui: BrainUi, a: BrainActions) {
    val grey = Color(0xFF8E8E96)
    Text("🧠 العقل (Gemini)", color = Color.White, fontSize = 18.sp)
    Text(
        if (ui.keySaved) "✅ فما مفتاح متسجّل" else "⛔ ما فماش مفتاح: خوذ مفتاح مجاني من aistudio.google.com وحطّو هنا",
        color = if (ui.keySaved) Color(0xFF39FF88) else Color(0xFFFF9F1C), fontSize = 14.sp,
    )
    OutlinedTextField(
        value = ui.keyInput,
        onValueChange = a.onKeyInput,
        label = { Text(if (ui.keySaved) "مفتاح جديد (اختياري)" else "مفتاح Gemini") },
        singleLine = true,
        visualTransformation = if (ui.showKey) VisualTransformation.None else PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = a.onPaste) { Text("لصق") }
        OutlinedButton(onClick = a.onToggleShow) { Text(if (ui.showKey) "خبّي" else "ورّي") }
        Button(onClick = a.onSave) { Text("حفظ") }
        Button(onClick = a.onTest, enabled = !ui.testing) { Text("اختبار") }
    }
    if (ui.result.isNotEmpty()) Text(ui.result, color = Color.White, fontSize = 13.sp)
    if (ui.models.isNotEmpty()) Text("الموديلات: " + ui.models.joinToString(" ← "), color = grey, fontSize = 12.sp)
    Text("🔒 المفتاح يتخزّن مشفّر في الهاتف برك (Android Keystore)، وما يتبعث كان لـ Google.", color = grey, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = ui.useSearch, onCheckedChange = a.onSearch)
        Text("يستعمل البحث في Google للأخبار والأسعار والطقس", color = Color.White, fontSize = 14.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("حاجات يعرفها عليك: ${ui.factsCount}", color = Color.White, fontSize = 14.sp)
        if (ui.factsCount > 0) OutlinedButton(onClick = a.onClearFacts) { Text("امسحهم") }
    }
    OutlinedButton(onClick = a.onToggleLog) { Text(if (ui.showLog) "سكّر السجلّ" else "سجلّ الأخطاء") }
    if (ui.showLog) Text(ui.log.ifEmpty { "فارغ" }, color = grey, fontSize = 11.sp)
}
