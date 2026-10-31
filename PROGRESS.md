# PROGRESS — Mouou Galaxy AI

Read this first in every session. Update it before the session ends.

## Plan (approved by Ayman, 2026-10-31)

| Phase | Content | Status |
| --- | --- | --- |
| 1 | Island + brain: pill beside the camera, tap → card morph (13 %, no blank frame), typing + reply, Gemini with 429 fallback, encrypted key + test, screen awareness (accessibility screenshot), drag + magnet + two-finger move, basic settings | **in progress** |
| 2 | Voice: long-press listen (STT), offline wake word (Vosk), Darija voice (Edge TTS + Android TTS fallback) | not started |
| 3 | Looks: expressions, topic costumes (stardust), animals | not started (needs the design pack zip) |
| 4 | Team: personas, ntfy + Grok cards, QS tile, visibility modes | not started |

## Phase 1 steps

- [x] 1.1 Project skeleton: Gradle KTS, manifest, Compose settings screen (Arabic RTL, dark space), temporary icon, first unit test, signing guard. Debug build OK, 1/1 tests.
- [x] 1.2 Overlay foreground service (`IslandService`, specialUse) + idle black pill with mini Mouou (`IslandView`) beside the camera cutout (`CutoutPlacer`, absolute TOP|LEFT, 8 dp gap, side RIGHT), pill-only window (pill + 12 dp margin), NOT_FOCUSABLE | NOT_TOUCH_MODAL, cutout ALWAYS, highest refresh mode. Settings: overlay permission + show/hide. 6/6 unit tests, lint 0 errors. Verified on the phone by Ayman (screenshot): beside the camera, but too big and close to the status icons.
- [x] 1.2b (Ayman's feedback) Smaller pill: height 26 dp (never taller than the status bar − 4 dp), width 2.9 × height, gap 6 dp, so it stays inside the status-bar row. Mouou's idle life added early (`MascotLife`: breathing ±2.2 %/3.2 s as squash-stretch, 130 ms blink every 3.7–5 s, glance every ~2.6 s), redrawn every vsync, no allocations in onDraw. 10/10 tests. Test APK 2.2.2-dev sent. Ayman said "fix and move on" without a new screenshot: read as the battery note → 1.3 build idles at 30 fps and stops drawing with the screen off.
- [x] 1.3 Tap → card morph. `MorphSpring` (420 / 0.62, fixed 2 ms sub-steps) drives one progress 0→1; `IslandGeometry.card` (screen − 16 dp ≤ 420 dp, 13 % height, 4 dp below the status bar) and `expandedWindow`. Window resized to the expanded frame FIRST; the morph starts only in `onWindowApplied` (origin applied in `onSizeChanged`, so resize + matching drawing land in the same frame). Overshoot only on the bottom edge; closing snaps on the pill (no undershoot). Card: space #030308, 14 twinkling stars, violet radial glow behind Mouou, Mouou travels from the pill to 48 dp on the left; reply text (13 sp, scrolls), input «احكي معايا...» (radius 19, #14FFFFFF), round send button (`CardViews.kt`). Content fades in over the last 40 %. Outside tap collapses unless pinned (focused or draft); back key closes the keyboard first (draft kept, 25 s timer) then collapses; 25 s idle collapse. Focus taken only while typing. Send shows an honest placeholder until the brain (1.5). Battery: idle 30 fps, morph every vsync, nothing while the screen is off. 18/18 tests, lint 0 errors. Test APK 2.2.3-dev sent; **not yet verified on the phone**.
- [ ] 1.4 Gesture engine: tap / long-press / one-finger drag + magnet / two-finger move / fling up (raw coords, slop×3).
- [ ] 1.5 Gemini brain: key storage (Keystore AES-GCM), key test (models.list), model list + fallback on 429/404/403/5xx/timeout, JSON reply parsing, Darija error messages, system prompt §10.5. Unit tests with a mock server where model 1 returns 429.
- [ ] 1.6 Screen awareness: AccessibilityService screenshot + window text, prompt prefix, "service off" message + enable button.
- [ ] 1.7 Mascot drawing (body, eyes, breathing, blink) and the basic faces needed for phase 1 (idle, thinking, scanning, happy, error).
- [ ] 1.8 Settings sections for phase 1 (permissions, brain, island), signed release APK v2.2.1, install steps.

## Decisions

- Package stays `com.glteam.teambubbles`. Version now 16 / 2.2.3 (bumped for every APK, test builds included).
- Idle pill size overrides spec §5.3 (96–120 × 32–36 dp) by Ayman's request: smaller, inside the status bar.
- minSdk 26, targetSdk 35, compileSdk 36 (spec §2). Compose BOM 2025.12.01, activity-compose 1.11.0, Kotlin 2.2.21, AGP 8.13.0, Gradle 8.14.3, OkHttp 4.12.0.
- ntfy topic is a setting, never hard-coded (public repo).
- Test builds (`assembleDebug`) install as a separate app `com.glteam.teambubbles.dev` named «Mouou تجربة», signed with the release key, so Ayman keeps his old app while testing. Real releases are `assembleRelease`.
- Signing (Ayman, 2026-10-31): **new key**, made by Claude, stored in `app-keys/mouou/` (`mouou-release.jks` + `keystore.properties`, alias `mouou`, valid to 2054). SHA-256 `8d:62:78:44:81:be:d6:27:ac:e3:04:15:7d:3b:c0:d1:3c:56:9e:6f:a3:89:ec:df:1f:d0:9f:a5:8a:71:61:dd`. The old v2.2.0 (APKMEN key) must be uninstalled once before installing 2.2.1. Never make another key.

## Open questions for Ayman

- Design pack `Mouou_Galaxy_AI_design_pack.zip` not received yet (needed for exact visuals; phase 3 depends on it).

## Session log

- **Session 1 (2026-10-31):** read the spec; created the project skeleton (step 1.1); build + test pass. Repo `mouou-galaxy-ai` created (public) and pushed; spec saved privately in `app-keys/mouou/spec.pdf`; new signing key created in `app-keys/mouou/`. Step 1.2 done (pill beside the camera); test APK 2.2.1-dev sent to Ayman. Ayman's screenshot: pill too big → made smaller + alive (1.2b), test APK 2.2.2-dev sent. Step 1.3 done (tap → card, typing, collapse) + battery pacing; test APK 2.2.3-dev sent. Next: step 1.4 (gesture engine: long-press, drag + magnet, two-finger move, fling up), after Ayman's recording of 1.3.
