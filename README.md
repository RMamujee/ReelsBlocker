# Reels Blocker

Personal Android app that strips Instagram down to your friends. It comes as two parts:

- **Insta Clean** (its own launcher icon): Instagram's website inside the app, with ads, suggested posts, the Reels feed and the Explore grid removed from the page as it loads, the way a browser ad blocker works. Nothing is covered or skipped; it simply isn't there. See [Insta Clean](#insta-clean-the-in-app-instagram).
- **Reels Blocker** (the accessibility service): for when you open the real Instagram app.

The rest of this section is about the second part. It kicks you out of **Reels**, hides **Explore** (search still works), scrolls past **ads and suggested posts** in the home feed, and **skips ads between stories**. DMs, friends' stories and friends' posts are left alone. Built for sideloading — not for the Play Store (accessibility-service apps need special review there).

## How it works

An `AccessibilityService` (`BlockerService`) receives events from Instagram only (`android:packageNames`). While one of its covers is on screen, and only then, it widens that to all apps, so it notices the moment another app's window comes to the front and takes the covers down; everything else from other apps is dropped on arrival, and it goes back to Instagram-only as soon as no cover is showing. It checks that the window in front is Instagram before it reads anything or presses BACK. Any new code that reads `rootInActiveWindow` must keep that check. On each screen change it detects Reels/Explore two ways:

1. **Selected bottom tab**: a selected node in the bottom tab bar whose content description matches "Reels" / "Search and explore". Escaped by clicking the Home tab inside that same tab bar (BACK from a tab can behave oddly).
2. **View IDs**: Instagram's internal IDs for the clips viewer / explore grid (e.g. `clips_viewer_view_pager`), counted only when visible on screen. Escaped with a BACK press. Catches reels opened from the feed, DMs, or notifications.

After each block there's a 1.5s cooldown so one escape can't fire twice mid-animation, and the service re-checks the screen when the cooldown ends. If you get kicked out of Reels 3+ times within 10 seconds, a full-screen "Blocked 🎯" overlay appears for 2.5s as a stronger nudge. Every block increments a daily counter shown in the app.

### Covers: the Reels icon and the Explore grid

Two things on the tab screens are hidden behind opaque patches (accessibility overlays matched to Instagram's background) instead of being kicked:

- **The Reels tab icon** ("Hide the Reels tab icon"): its tab cell is covered and taps on it do nothing. The Reels tab is still blocked underneath if you ever reach it.
- **The Explore grid** ("Hide the Explore grid"): on the Search tab, the grid of recommended posts is covered but the search bar (and the topic chips under it) isn't. Current builds give the grid the generic id `recycler_view`, so that id (`explore.tabGridViewIds`) only counts while the Explore tab is selected. Tap it and search for accounts as normal. The cover disappears the moment you tap the search bar (before Instagram even reports the field as focused) and stays off while it's focused or holds a query, so you can type straight away. Once the grid's view ID has been seen, the cover is only drawn where the grid actually is, so Instagram's separate search and results pages are never covered. Newer builds show a tappable "Search" (or "Ask Meta AI or Search") box instead of a text field; that counts as the search bar too, and the last known bottom edge of the bar is remembered so the cover never creeps up over it. Reels opened from search results are still blocked (except ones reached from DMs). If the grid's view ID isn't known, the cover spans from the search bar down to the tab bar. Only if no cover can be placed does it fall back to the old kick-to-Home.

Covers only exist while the thing they hide is on screen. Instagram can leave the hidden tab bar in its screen data under a chat, story, call or reel viewer, so a cover is never drawn while one of those is showing. They're recognised by the DM ids, the reels viewer ids, and the `noCover.viewIdPrefixes` list in the rules file. The Reels icon cover also requires a visible tab bar in the bottom quarter of the screen.

Overlays sit above every app, so the moment another app's window comes to the front (Home, recents, the notification shade) the covers come down, and checks Instagram scheduled on its way out are cancelled so they can't bring them back over the launcher. The keyboard opening isn't leaving. A popup over Instagram (a notification, the volume panel) isn't leaving either, so the screen is looked at again 0.4s later and the covers come back if Instagram is still in front. As a backstop, while a cover is showing the service also checks twice a second which app is in front, and treats a run of empty answers (Samsung reports none for a moment after leaving an app) as leaving too.

The tab bar's own layout isn't exposed to accessibility services, so where the tab bar starts is measured from the Home tab itself. Covers use Instagram's own background: `#0C1014` in dark mode, white in light. In chats, stories, calls and viewers there are no covers, so there's no check either. The DM-reel feature doesn't poll at all.

### Home feed: friends only, no ads

With **Home feed: friends only** and **Hide ads in the home feed** on (both default), posts that aren't from people you follow are **scrolled out of view** once the feed stops moving. Nothing is drawn over the feed while it moves: an app can't remove posts from Instagram's list, and covers can only be repositioned about ten times a second, so on a moving feed they visibly drag behind it.

Instagram splits one post across several list rows (header, media, ad bar, buttons, caption), so the service groups rows from one post header to the next:

- **Ad**: "Sponsored" (the header subtitle, or the start of the media's description) or exactly "Ad" in the header subtitle (`feed.sponsoredLabels`, `feed.subtitleViewIds`), or an ad's call-to-action bar (`cta_container`, `feed.adViewIds`). Labels shorter than three letters only ever match exactly, so a friend's "Ad Astra" song or "ad.vibes" username isn't an ad.
- **Suggested**: a Follow button in the header (`inline_follow_button`, `feed.followViewIds`; accounts you follow don't get one), or a "Suggested for you" section.
- **"You're all caught up"**: everything below it is suggested, so instead of skipping through endless suggestions the feed is pushed back up to that line.

When the feed comes to rest, the screen is checked again (so a skip never lands on a story, chat or menu opened just as the feed stopped) and an unwanted stretch you've scrolled into (it reaches the near quarter of the feed, or fills 60% of it) is dragged off screen in the direction you were scrolling. The swipe holds still at the end so the list doesn't fling on, and scrolling the other way during a skip cancels it. Anything else, like an ad below a post you're still reading or a sliver at the edge, gets a plain background-coloured patch until the feed moves again rather than pushing that post away. While the stories row is showing (the top of the feed), nothing is skipped, so the stories never scroll away; an ad right under them is patched instead. The top bar and stories row float over the top of the feed (`feed.topBarViewIds`), so nothing is ever placed over them. After eight skips in a row without you scrolling, it stops until you scroll again, and never blanks most of the screen.

Instagram reuses its row views for other posts as the list scrolls, so markers are searched again after every scroll (results are reused only while nothing has moved, e.g. while a video plays). What's remembered between passes, the rest of an ad whose header has scrolled off and the "caught up" line, is keyed by list position, taken from the scroll events. The **Following** and **Favorites** feeds (which open without the tab bar) are filtered the same way, recognised by their title (`feed.pageTitles`).

Instagram's **For you** home feed is mostly suggested posts, so expect several skips in a row there. Its **Following** feed (tap the Instagram logo → Following) has only accounts you follow, plus the odd ad, which is skipped the same way.

If ads or suggested posts slip through, turn on Debug mode and scroll the feed: `Feed rows (H=header A=ad S=suggested F=follow C=caught-up, *=unwanted): ...` lines show what each visible row was recognised as, and `Skipped ...` lines show each skip. A post with no `H` means its header changed; add the new id or label to the `feed` section.

### Story ads

With **Skip ads between stories** on (the default), a story ad is skipped with a swipe to the next account's stories, behind a brief "Skipping ad…" cover that lets the swipe through. That skips every card of the ad. The service looks again after the swipe settles, so back-to-back ads are skipped too. Current builds mark a story ad with an "Ad" pill and an ad-only message box (`stories.adViewIds`) and describe its header as "<account>'s sponsored story" (`stories.adPhrases`); older ones show "Sponsored" under the account name (`feed.sponsoredLabels`). The story viewer is recognised by `stories.viewIdPrefixes`. Phrases and labels are locale-specific: add your language's.

### Reels friends send you

With **Allow reels friends send in DMs** on (the default), a reel you open straight from a DM chat plays normally. The service remembers that the last screen was a chat (spotted by the chat screen's view IDs, the `dm` section of the rules file) and allows that one viewer. Swiping on to the next reel is blocked: while an allowed reel is open, the service listens for scroll events from the reels pager, and a page change sends you back to the chat. Leaving the viewer by any route ends the allowance, so reels reached from a DM reel's profile, audio page, etc. are blocked as usual. Scroll events are ignored everywhere else, so this adds no work while you use the rest of Instagram.

If DM reels still get blocked, the chat screen's IDs have changed. With Debug mode on, open a chat and look for its IDs in `adb logcat -s ReelsBlocker`, then add them to `dm.viewIds`. If swiping on isn't blocked, the `DM-reel scroll` log lines show what the pager reports.

## Insta Clean: the in-app Instagram

`FeedActivity` loads `https://www.instagram.com/` in a WebView, with its own login (cookies stay in the app). A filter script, `app/src/main/assets/web_filter.js`, is installed with `WebViewCompat.addDocumentStartJavaScript`, so it runs on Instagram's pages before any of Instagram's own code, and a `MutationObserver` checks every element as it's added, before it's painted. It uses the same switches as the main screen.

- **Feed**: a post is removed when its header says "Ad"/"Sponsored"/"Paid partnership", or "Suggested for you" or has a Follow button (accounts you follow don't). "Suggestions for you" account strips go too. Instagram's feed is a virtual list that pads its bottom for the posts it expects below, counting removed ones, so the filter sets that padding to zero: the feed ends at the last post from someone you follow, with a note under it, and more slot in as Instagram loads them. No blank screens.
- **Reels**: the Reels tab is gone and the endless Reels feed (`/reels/`) never opens. A single reel (`/reel/<id>/`, from a friend, a chat or a profile) is just a post with one video and opens normally.
- **Explore**: the grid tiles (links to posts and reels) are removed; the search box and its results (links to profiles, tags, places) stay.
- **Story ads**: a story whose header says "Ad" makes the page fully transparent (so you see black) and every card of the ad is tapped past straight away, the way the right side of a story is tapped to advance.
- **"Use the app" prompts**: dialogs and banners pushing the app are removed, and the page scroll they lock is unlocked.
- **Like counts** (switch on the main screen, on by default): the numbers next to the Like, Comment and Share buttons and the "Liked by … and N others" line are hidden. The buttons still work.
- The page is kept clear of the status bar, navigation bar and keyboard, and Instagram's bottom bar hides while you type.

Only Instagram and Meta's login pages load inside; every other link (including Instagram's `l.instagram.com` redirects) opens in your browser. Camera and microphone requests are refused; picking photos to post works. It's the website, so there are no push notifications, and some app-only features (e.g. posting reels or stories with effects) aren't there.

If Instagram changes its page and something slips through: debug builds allow Chrome DevTools to attach (`chrome://inspect`, or `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`), and with Debug mode on the filter logs `[rb]` lines to the console. Labels live at the top of `web_filter.js`.

## Planned

- Real app: skip feed ads with accessibility scroll actions instead of injected swipes, so a skip can never land as a tap (scripted testing once opened a profile and an ad's link).
- Insta Clean: open to the Following feed (only accounts you follow, newest first).
- Insta Clean: leave the story viewer after the last story instead of pausing on it.
- Open instagram.com links from other apps in Insta Clean.
- A daily time limit for Insta Clean.

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
5. **After every update** (reinstalling the APK), turn the service off and on again in Accessibility settings. Android stops the app to update it and then marks its accessibility service as crashed: it still looks enabled but gets no events until it's toggled.
6. Open Instagram, tap the Reels tab — you should bounce straight back to Home.
7. **Turn USB debugging back off** when you're done (Settings > Developer options). This is a debuggable build; leaving adb enabled is the only meaningful security exposure this app carries, and the in-app rules importer means you never need adb for updates.
8. **Revoke "install unknown apps"** from whichever app you installed through: Settings > Apps > ⋮ > Special access > Install unknown apps > Chrome / Drive / My Files > OFF. Leaving it on lets that app install other APKs later.

## Privacy

Everything is on-device. The service reads the screen only when Instagram is in front, and hears other apps only while one of its covers is showing, and then only to notice their window coming to the front. The app has the internet permission only for Insta Clean, which talks to Instagram (and Meta's login pages) and nothing else; the blocker service never uses the network. No analytics, and nothing else leaves the phone.
