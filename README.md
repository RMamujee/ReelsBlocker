# Reels Blocker

Personal Android app that kicks you out of Instagram **Reels** and **Explore** the moment you land on them, while leaving DMs, stories, and the home feed alone. Built for sideloading — not for the Play Store (accessibility-service apps need special review there).

## How it works

An `AccessibilityService` (`BlockerService`) listens to window events **from Instagram only** (`android:packageNames` filter — it never sees other apps). On each screen change it detects Reels/Explore two ways:

1. **Selected bottom tab** — a selected node whose content description matches "Reels" / "Search and explore". Escaped by clicking the Home tab (BACK from a tab can behave oddly).
2. **View IDs** — Instagram's internal IDs for the clips viewer / explore grid (e.g. `clips_viewer_view_pager`). Escaped with a BACK press. Catches reels opened from the feed, DMs, or notifications.

Blocks are debounced (600ms). If you get blocked 3+ times within 10 seconds, a full-screen "Blocked 🎯" overlay appears for 2.5s as a stronger nudge. Every block increments a daily counter shown in the app.

## When Instagram updates break detection

Instagram renames its view IDs regularly. Detection rules live in `app/src/main/assets/detection_rules.json`, and a copy pushed to the app's private files dir **overrides the built-in one without reinstalling**:

1. In the app, enable **Debug mode**, then open Instagram and visit Reels.
2. Watch `adb logcat -s ReelsBlocker` — every screen logs its current view IDs.
3. Add the new IDs to the JSON, then either rebuild, or push the file directly:
   ```
   adb push detection_rules.json /sdcard/Download/
   adb shell run-as com.rahil.reelsblocker cp /sdcard/Download/detection_rules.json files/
   ```
   (run-as works because it's a debug build) — then tap **Reload detection rules** in the app.

The selected-tab fallback usually keeps working even when IDs change, since it keys off human-readable descriptions.

## Build

Toolchain lives in `C:\Users\Rahil\android-dev` (JDK 17 + Gradle 8.10.2), SDK in `%LOCALAPPDATA%\Android\Sdk`. From the project root:

```powershell
$env:JAVA_HOME = "C:\Users\Rahil\android-dev\<jdk-17 dir>"
& "C:\Users\Rahil\android-dev\gradle-8.10.2\bin\gradle.bat" assembleDebug
```

APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

## Install on the S21+ (Samsung / One UI)

1. **Get the APK onto the phone** — easiest: `adb install app-debug.apk` with USB debugging on (Settings > Developer options), or just copy the APK via USB/Drive and tap it (allow "install unknown apps").
2. Open **Reels Blocker** → tap **Enable in Accessibility Settings** → Installed apps → Reels Blocker → ON. Samsung shows a scary warning — that's standard for any accessibility app.
3. Tap **Exempt from battery optimization** and allow it. Then also: **Settings > Apps > Reels Blocker > Battery > Unrestricted** — One UI kills accessibility services that aren't exempted.
4. Do **not** put the app to "sleep" (Settings > Battery > Background usage limits — make sure Reels Blocker is not in Sleeping/Deep sleeping apps).
5. Open Instagram, tap the Reels tab — you should bounce straight back to Home.

## Privacy

Everything is on-device. The service is filtered to Instagram's package only, no network permission, no analytics, nothing leaves the phone.
