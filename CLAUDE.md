# Mouou Galaxy AI — rules for Claude Code

- Native Android app (Kotlin; overlay = custom Canvas View in WindowManager; settings = Compose M3).
  Package `com.glteam.teambubbles`. Owner: Ayman (Tunisia, Tunisian Darija/Arabic, phone only,
  beginner): explain simply in Arabic.
- **Start every session by reading `PROGRESS.md`** (where we stopped, what was added, what is next),
  and **end every session by updating it** (done, decisions, next step). Never skip this.
- The full spec is `Mouou Galaxy AI — Complete Product Specification (v2.2)`, kept privately in
  `henri199chat-prog/app-keys` at `mouou/spec.pdf` (it contains a private ntfy topic, so it must not
  be in this public repo). Section 3 "Owner requirements" is authoritative.
- Save tokens: read only the files you need; don't analyse the whole project unless asked.
- Before any big change, write a short plan and wait for Ayman's approval.
- Never commit secrets: no keystore, passwords, API keys or the ntfy topic. Those are settings or live
  in `app-keys`.
- Signing: release key in the private repo `app-keys` at `mouou/` (clone it next to this repo as
  `../app-keys`). Release builds fail on purpose without it. Never generate a replacement key once a
  release is published.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each new APK.
- Download link (GitHub Releases), asset named exactly `MououGalaxyAI.apk`:
  https://github.com/henri199chat-prog/mouou-galaxy-ai/releases/latest/download/MououGalaxyAI.apk
  Claude cannot create releases from a cloud session: hand Ayman the APK and the release steps.
- Mascot rules: Mouou is an original white blob, no mouth, no hands, ever. Teammate personas
  (Mikasa, Eren, Otaku, Lukas, APKMEN) are the same blob tinted with their colour and an accessory,
  never drawings of any existing character.
- Build: `./gradlew assembleDebug testDebugUnitTest`. Android SDK path goes in `local.properties`
  (`sdk.dir=...`, not committed). Compose BOM must stay compatible with compileSdk 36 (2026.x BOMs
  need API 37). Google's Maven Central mirror is listed first because Maven Central returns 429 to
  parallel downloads from the cloud proxy.
- Cannot test on a real phone here (no emulator): unit-test pure logic, then Ayman tests the APK and
  sends screen recordings for frame-by-frame checks.
