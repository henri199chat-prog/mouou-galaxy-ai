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
- [ ] 1.2 Overlay foreground service + idle black pill beside the camera cutout (DisplayCutout), pill-only window, touches pass through.
- [ ] 1.3 Tap → card morph (spring 420 / 0.62, window resized before first expanded frame), card UI: mini mascot, input «احكي معايا...», send button; tap outside collapses, pinned while typing.
- [ ] 1.4 Gesture engine: tap / long-press / one-finger drag + magnet / two-finger move / fling up (raw coords, slop×3).
- [ ] 1.5 Gemini brain: key storage (Keystore AES-GCM), key test (models.list), model list + fallback on 429/404/403/5xx/timeout, JSON reply parsing, Darija error messages, system prompt §10.5. Unit tests with a mock server where model 1 returns 429.
- [ ] 1.6 Screen awareness: AccessibilityService screenshot + window text, prompt prefix, "service off" message + enable button.
- [ ] 1.7 Mascot drawing (body, eyes, breathing, blink) and the basic faces needed for phase 1 (idle, thinking, scanning, happy, error).
- [ ] 1.8 Settings sections for phase 1 (permissions, brain, island), signed release APK v2.2.1, install steps.

## Decisions

- Package stays `com.glteam.teambubbles`, versionCode 14 / 2.2.1 (spec §1).
- minSdk 26, targetSdk 35, compileSdk 36 (spec §2). Compose BOM 2025.12.01, activity-compose 1.11.0, Kotlin 2.2.21, AGP 8.13.0, Gradle 8.14.3, OkHttp 4.12.0.
- ntfy topic is a setting, never hard-coded (public repo).
- Signing (Ayman, 2026-10-31): **new key**, made by Claude, stored in `app-keys/mouou/` (`mouou-release.jks` + `keystore.properties`, alias `mouou`, valid to 2054). SHA-256 `8d:62:78:44:81:be:d6:27:ac:e3:04:15:7d:3b:c0:d1:3c:56:9e:6f:a3:89:ec:df:1f:d0:9f:a5:8a:71:61:dd`. The old v2.2.0 (APKMEN key) must be uninstalled once before installing 2.2.1. Never make another key.

## Open questions for Ayman

- Design pack `Mouou_Galaxy_AI_design_pack.zip` not received yet (needed for exact visuals; phase 3 depends on it).

## Session log

- **Session 1 (2026-10-31):** read the spec; created the project skeleton (step 1.1); build + test pass. Repo `mouou-galaxy-ai` created (public) and pushed; spec saved privately in `app-keys/mouou/spec.pdf`; new signing key created in `app-keys/mouou/`. Next: step 1.2.
