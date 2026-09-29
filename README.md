# Reels Blocker

Personal Android app that kicks you out of Instagram **Reels** and **Explore** the moment you land on them, while leaving DMs, stories, and the home feed alone. Built for sideloading — not for the Play Store (accessibility-service apps need special review there).

## How it works

An `AccessibilityService` (`BlockerService`) receives window events **from Instagram only** (`android:packageNames` filter). That filter limits *events*, not screen reads, so the code also checks that the window in front is Instagram before it reads anything or presses BACK. Any new code that reads `rootInActiveWindow` must keep that check. On each screen change it detects Reels/Explore two ways:

1. **Selected bottom tab**: a selected node in the bottom tab bar whose content description matches "Reels" / "Search and explore". Escaped by clicking the Home tab inside that same tab bar (BACK from a tab can behave oddly).
2. **View IDs**: Instagram's internal IDs for the clips viewer / explore grid (e.g. `clips_viewer_view_pager`), counted only when visible on screen. Escaped with a BACK press. Catches reels opened from the feed, DMs, or notifications.

After each block there's a 1.5s cooldown so one escape can't fire twice mid-animation, and the service re-checks the screen when the cooldown ends. If you get blocked 3+ times within 10 seconds, a full-screen "Blocked 🎯" overlay appears for 2.5s as a stronger nudge. Every block increments a daily counter shown in the app.

**Note:** with Explore blocking on, the whole Search tab is blocked, including searching for accounts. Turn off "Block Explore" if you need search.

## When Instagram updates break detection

Instagram renames its view IDs regularly. Detection rules live in `app/src/main/assets/detection_rules.json`, and an imported copy in the app's private files dir **overrides the built-in one without reinstalling**:

1. In the app, enable **Debug mode**, then open Instagram and visit Reels.
2. Watch `adb logcat -s ReelsBlocker`. Every screen transition logs its current view IDs plus the selected nodes' labels and whether each one passed the tab-bar check.
3. Add the new IDs to the JSON, copy the file to the phone (Drive/USB), and tap **Import rules file…** in the app. No adb, no reinstall. The importer rejects files that are too large, too deeply nested, not valid JSON, or whose Home labels overlap the Reels/Explore labels. The loader validates every entry and falls back to built-in defaults for any list that comes back empty.

**Only import rules files you wrote yourself.** A rules file tells the service which Instagram buttons to press, so don't import one someone sent you.

The selected-tab fallback usually keeps working even when IDs change, since it keys off the human-readable bottom-tab labels. **Non-English phones:** those labels are locale-specific — add your language's "Reels"/"Search and explore"/"Home" labels to `selectedTabDescs` and `home.tabDescs`.

## Build

Needs JDK 17+ and the Android SDK (platform 35). The Gradle wrapper is committed, so from the project root:

```powershell
$env:JAVA_HOME = "<path to JDK 17>"   # this machine: C:\Users\Rahil\android-dev\jdk-17.0.20.1+1
.\gradlew.bat assembleDebug
```

APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

## Install on the S21+ (Samsung / One UI)

1. **Get the APK onto the phone** — download it from the GitHub release page on the phone, or copy via USB/Drive, then tap it (allow "install unknown apps"). `adb install` also works.
2. Open **Reels Blocker** → tap **Enable in Accessibility Settings** → Installed apps → Reels Blocker → ON. Samsung shows a scary warning — that's standard for any accessibility app.
   - **Android 13+ "Restricted setting":** if the toggle is greyed out because the APK was sideloaded, go to Settings > Apps > Reels Blocker > tap the **⋮ menu** > **Allow restricted settings**, then try again. (Installing via `adb install` avoids this entirely.)
3. Tap **Exempt from battery optimization** and allow it. Then also: **Settings > Apps > Reels Blocker > Battery > Unrestricted** — One UI kills accessibility services that aren't exempted.
4. Do **not** put the app to "sleep" (Settings > Battery > Background usage limits — make sure Reels Blocker is not in Sleeping/Deep sleeping apps).
5. Open Instagram, tap the Reels tab — you should bounce straight back to Home.
6. **Turn USB debugging back off** when you're done (Settings > Developer options). This is a debuggable build; leaving adb enabled is the only meaningful security exposure this app carries, and the in-app rules importer means you never need adb for updates.
7. **Revoke "install unknown apps"** from whichever app you installed through: Settings > Apps > ⋮ > Special access > Install unknown apps > Chrome / Drive / My Files > OFF. Leaving it on lets that app install other APKs later.

## Privacy

Everything is on-device. The service only receives Instagram's events and only reads the screen when Instagram is in front. It has no network permission and no analytics, and nothing leaves the phone.
