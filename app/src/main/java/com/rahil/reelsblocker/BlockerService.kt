package com.rahil.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

class BlockerService : AccessibilityService() {

    private enum class Target { REELS, EXPLORE }

    /**
     * viaTab = the Reels/Explore bottom tab itself is selected, escaped by clicking Home
     * inside [tabBar]; otherwise a viewer opened on top, escaped with BACK.
     */
    private class Detection(val target: Target, val viaTab: Boolean, val tabBar: AccessibilityNodeInfo? = null)

    private class Scan(
        /** Reels tab selected, or a reels viewer on screen. */
        val reels: Detection?,
        /** Explore tab selected (tabBar set) or an Explore grid id on screen. */
        val exploreVisible: Boolean,
        val exploreTabBar: AccessibilityNodeInfo?,
        val exploreGridRect: Rect?,
        /** The user is typing in, or looking at results for, the search bar. */
        val searchActive: Boolean,
        val searchBarBottom: Int,
        val tabBarTop: Int,
        val reelsTabRect: Rect?,
        val sawDm: Boolean,
        /** A chat, story, call or viewer is on screen: the tab bar may linger hidden in the tree, so no covers. */
        val coverBlocked: Boolean,
        /** Home tab selected; feedList is the biggest visible vertical list on it. */
        val onHome: Boolean = false,
        val feedList: AccessibilityNodeInfo? = null,
        /** Bottom of the top bar and stories row, which float over the top of the feed. */
        val feedTop: Int = -1,
        /** The largest visible story-viewer view, so ad checks never see the feed left under it. */
        val storyViewer: AccessibilityNodeInfo? = null
    )

    /** One child of the home feed list. Instagram splits a post across several rows. */
    /** [pos] is the row's position in the whole list, or -1 if it isn't known. */
    private class FeedRow(val node: AccessibilityNodeInfo, val rect: Rect, var pos: Int) {
        var flags = 0
    }

    private lateinit var state: BlockerState
    private lateinit var rules: DetectionRules
    private lateinit var covers: CoverManager

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var lastDetectAt = 0L
    private var lastDebugLogAt = 0L
    private val recentBlocks = ArrayDeque<Long>()
    private var overlayView: View? = null

    // The remote per-id search walks Instagram's whole tree on its UI thread, so it only
    // runs after a real screen transition (or a re-check we scheduled), never on every
    // quiet gap in the feed.
    private var pendingDeep = false
    private var failedAttempts = 0

    // A reel opened straight from a DM chat is allowed until the user leaves the viewer
    // or swipes on to another reel. leftDmAt is 0 while a chat is on screen, otherwise
    // when the user first left it; the context only counts within DM_CONTEXT_GRACE_MS
    // of leaving, or within DM_RECENT_MS of a pass last seeing the chat (tapping a reel
    // fires events that refresh it). No polling: leaving Instagram from a chat just lets
    // the context go stale. swipeLock keeps a re-check from re-allowing the viewer after
    // a swipe kick (the chat can stay in the tree under it).
    private var dmContext = false
    private var leftDmAt = 0L
    private var lastDmSeenAt = 0L
    private var allowedViewer = false
    private var allowedAt = 0L
    private var swipeBaseline = -1
    private var scrollAccum = 0
    private var swipeLock = false
    private var lastScrollLogAt = 0L

    // Explore search: a tap on the search bar hides the grid cover at once and holds off
    // kicks for SEARCH_GRACE_MS, so the field is usable before Instagram reports focus.
    // Once the grid's id has been seen, the cover only goes where the grid really is, so
    // the search/results pages under the Explore tab are never covered.
    private var exploreOnScreen = false
    private var searchGraceUntil = 0L
    private var exploreGridKnown = false
    private var gridMissRechecked = false
    private var lastSearchBarBottom = -1

    // Home feed: the list being filtered, the part of it not under the top bar, stories row
    // or tab bar, and (by list position) which rows were unwanted last pass, so the rest of
    // an ad is still known after its header scrolls off.
    private var feedList: AccessibilityNodeInfo? = null
    private var feedClipTop = -1
    private var feedClipBottom = -1
    private var badPositions = HashMap<Int, Int>()
    private var rowFlags = HashMap<AccessibilityNodeInfo, Int>()
    private var rowFlagsMode = -1
    private var caughtUpPos = -1
    private var lastFeedScanAt = 0L
    private var lastFeedFreshAt = 0L
    private var lastFeedLogAt = 0L
    private var lastFeedScrollAt = 0L
    // +1 while the user scrolls down the feed, -1 up.
    private var feedDirection = 1
    private var lastFeedFrom = -1
    private var skipUntil = 0L
    private var skipDirection = 0
    private var skipsWithoutUser = 0
    // Goes through the full screen check, so a skip never lands on a story, chat or sheet
    // opened just as the feed stopped.
    private val feedSettled = Runnable { runDetection(deepSearch = false) }

    // A popup from another app (a notification, the volume panel) takes the covers down;
    // this puts them back if Instagram is still in front, or confirms the user left.
    private val confirmLeft = Runnable {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == IG) {
            runDetection(deepSearch = true)
        } else if (pkg != null && pkg != SYSTEM_UI) {
            resetDmState()
        }
    }
    private var watchingAllApps = false

    private var lastStorySkipAt = 0L
    private val hideStoryCover = Runnable { covers.hide(COVER_STORY_AD) }

    // Reels tab cell bounds, keyed by the label node's bounds, so the tab-bar IPC check
    // only reruns when the tab moves.
    private val cachedReelsLabel = Rect()
    private var cachedReelsCell: Rect? = null
    private var cachedTabBarTop = -1

    // Screens that settle after their last event (e.g. the static Explore grid) would
    // otherwise slip past the throttle, so every event re-arms one trailing re-check.
    private val trailingCheck = Runnable { runDetection(deepSearch = pendingDeep) }

    // Separate from trailingCheck so the per-event re-arming can't cancel it: a re-check
    // scheduled for the end of a cooldown really runs then.
    private val recheck = Runnable { runDetection(deepSearch = true) }
    private val searchGraceEnd = Runnable { runDetection(deepSearch = false) }

    // The service only hears Instagram's events, so it can't tell when the user leaves.
    // Covers sit above every app, so while one is on screen (and only then) poll which
    // app is in front and remove them the moment it isn't Instagram.
    private var watchdogRunning = false
    private var watchdogMisses = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (!covers.isShowing) {
                watchdogRunning = false
                watchAllApps(false)
                return
            }
            // Only the package name is needed, so skip prefetching the window's nodes.
            val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getRootInActiveWindow(0)
            } else {
                rootInActiveWindow
            }
            val pkg = root?.packageName?.toString()
            // Samsung can report no active window for a while after leaving an app, so a
            // run of empty answers counts as leaving too.
            watchdogMisses = if (pkg == null) watchdogMisses + 1 else 0
            if ((pkg != null && pkg != IG) || watchdogMisses >= WATCHDOG_MAX_MISSES) {
                watchdogMisses = 0
                leftInstagram()
                watchdogRunning = false
                return
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val IG = "com.instagram.android"
        private const val SYSTEM_UI = "com.android.systemui"
        // Long enough to outlast Instagram's exit animation on a slow frame, so one
        // escape never fires twice and pops an extra screen (or exits the app).
        private const val ACTION_COOLDOWN_MS = 1500L
        private const val DETECT_THROTTLE_MS = 250L
        private const val TRAILING_CHECK_MS = 350L
        private const val RETRY_MS = 150L
        private const val MAX_RETRIES = 5
        private const val DM_CONTEXT_GRACE_MS = 1200L
        private const val DM_RECENT_MS = 60_000L
        // Ignore the pager settling into place right after the shared reel opens.
        private const val SWIPE_GRACE_MS = 800L
        private const val WATCHDOG_MS = 500L
        private const val RAPID_WINDOW_MS = 10_000L
        private const val RAPID_THRESHOLD = 3
        private const val OVERLAY_MS = 2500L
        private const val MAX_NODES = 350
        // Fallback top edge of the Explore cover when the search bar can't be found.
        private const val FALLBACK_GRID_TOP_FRACTION = 0.16f
        private const val SEARCH_GRACE_MS = 2000L
        private const val GRID_MISS_RECHECK_MS = 300L
        private const val WATCHDOG_MAX_MISSES = 3
        // Ads and suggested posts are scrolled out of view once the feed has been still
        // for FEED_SETTLE_MS: covers can't keep up with a moving list.
        private const val FEED_SETTLE_MS = 250L
        private const val FEED_RESCAN_MS = 2000L
        private const val MAX_FEED_ROWS = 30
        private const val MIN_SEARCH_LABEL = 4
        // Skipped once it reaches the near quarter of the feed or fills 60% of it; the end
        // of the friends feed once it fills 30% or reaches the middle.
        private const val SKIP_EDGE_SHARE = 0.25f
        private const val SKIP_FILL_SHARE = 0.6f
        private const val SKIP_MIN_SHARE = 0.3f
        private const val SKIP_MOVE_PX_PER_MS = 6f
        private const val SKIP_MIN_MOVE_MS = 150L
        // Held still at the end of the swipe so the list doesn't fling on.
        private const val SKIP_HOLD_MS = 120L
        private const val SKIP_SETTLE_MS = 200L
        private const val MAX_FEED_COVERS = 4
        private const val MIN_SKIP_PX = 24
        // Upward skips start this far down the feed, clear of the status and top bars.
        private const val SWIPE_UP_START_SHARE = 0.2f
        // Scroll events this soon after a skip are still the skip's own.
        private const val USER_SCROLL_GAP_MS = 300L
        private const val CONFIRM_LEFT_MS = 400L
        // The top bar alone is well under this share of the feed; with the stories row, over.
        private const val STORIES_SHOWING_SHARE = 0.15f
        private const val SWIPE_EDGE_PX = 40
        // A skip that keeps failing (or a run of ads) stops until the user scrolls again.
        private const val MAX_SKIPS_WITHOUT_USER = 8
        private const val ROW_HEADER = 1
        private const val ROW_AD = 2
        private const val ROW_SUGGESTED = 4
        private const val ROW_CAUGHT_UP = 8
        private const val ROW_FOLLOW = 16
        /** Rows that begin a new post or section. "Sponsored" and Follow sit inside a post. */
        private const val ROW_STARTS = ROW_HEADER or ROW_SUGGESTED or ROW_CAUGHT_UP
        // A skip swipe plus Instagram's page animation; the next story is checked after.
        private const val STORY_SWIPE_MS = 120L
        private const val STORY_SKIP_COOLDOWN_MS = 900L
        private const val STORY_COVER_MS = 800L
        // Whole word, so a grid post described as "research…" isn't taken for the search bar.
        private val SEARCH_WORD = Regex("\\bsearch\\b", RegexOption.IGNORE_CASE)

        var instance: BlockerService? = null
            private set
    }

    override fun onServiceConnected() {
        state = BlockerState(this)
        rules = DetectionRules.load(this)
        if (::covers.isInitialized) covers.hideAll()
        covers = CoverManager(this)
        instance = this
        Log.i(TAG, "Service connected. Reels ids=${rules.reelsViewIds} Explore ids=${rules.exploreViewIds}")
    }

    fun reloadRules() {
        rules = DetectionRules.load(this)
        exploreGridKnown = false
        rowFlags = HashMap()
        rowFlagsMode = -1
        badPositions = HashMap()
        caughtUpPos = -1
        Log.i(TAG, "Rules reloaded")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::covers.isInitialized) covers.hideAll()
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::state.isInitialized) return
        val pkg = event.packageName?.toString()
        if (pkg != IG) {
            // Other apps' events only arrive while a cover is showing (see watchAllApps), so
            // leaving Instagram is noticed at once: another app's window coming to the front
            // takes the covers down. Nothing else from other apps is read. The keyboard
            // opening over Instagram isn't leaving it.
            // This app's own covers aren't activities, so they can't trigger it.
            val ownCover = pkg == packageName && event.className?.endsWith("Activity") != true
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null &&
                !ownCover && event.className?.contains("SoftInputWindow") != true && !isInputMethod(pkg)
            ) {
                leftInstagram()
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Scrolls only matter while a DM-shared reel or the filtered home feed is on
            // screen; everywhere else they return here, before any other work.
            if (!state.isActive()) return
            if (allowedViewer && state.blockReels) {
                onViewerScrolled(event)
            } else if (feedList != null) {
                onFeedScrolled(event)
            }
            return
        }

        if (!state.isActive()) {
            covers.hideAll()
            resetDmState()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            onPossibleSearchTap(event)
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pendingDeep = true
            failedAttempts = 0
        }

        handler.removeCallbacks(trailingCheck)
        handler.postDelayed(trailingCheck, TRAILING_CHECK_MS)

        if (SystemClock.uptimeMillis() - lastDetectAt < DETECT_THROTTLE_MS) return

        runDetection(deepSearch = pendingDeep)
    }

    private fun isInputMethod(pkg: String): Boolean {
        val ime = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.startsWith("$pkg/")
    }

    /**
     * Another app's window came to the front. Covers come down at once, and checks Instagram
     * scheduled on its way out are cancelled so they can't put them back over the launcher.
     * A popup over Instagram (a notification, the volume panel) isn't leaving it, so the
     * screen is looked at again shortly and the covers come back if Instagram is still there.
     */
    private fun leftInstagram() {
        if (covers.isShowing) covers.hideAll()
        handler.removeCallbacks(feedSettled)
        handler.removeCallbacks(trailingCheck)
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(searchGraceEnd)
        handler.removeCallbacks(confirmLeft)
        handler.postDelayed(confirmLeft, CONFIRM_LEFT_MS)
    }

    /**
     * Other apps' events are only needed while a cover is on screen, to notice leaving.
     * The rest of the time the service hears Instagram only.
     */
    private fun watchAllApps(on: Boolean) {
        if (on == watchingAllApps) return
        val info = serviceInfo ?: return
        info.packageNames = if (on) null else arrayOf(IG)
        serviceInfo = info
        watchingAllApps = on
    }

    private fun scheduleRecheck(delayMs: Long) {
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, delayMs)
    }

    private fun resetDmState() {
        dmContext = false
        leftDmAt = 0L
        allowedViewer = false
        swipeLock = false
    }

    private fun ensureWatchdog() {
        if (!covers.isShowing) return
        watchAllApps(true)
        if (watchdogRunning) return
        watchdogRunning = true
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun runDetection(deepSearch: Boolean) {
        if (!::state.isInitialized) return
        if (!state.isActive()) {
            covers.hideAll()
            resetDmState()
            return
        }
        lastDetectAt = SystemClock.uptimeMillis()

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) {
            covers.hideAll()
            return
        }

        if (deepSearch) pendingDeep = false
        if (state.debugMode && deepSearch) debugLog(root)

        val wantReels = state.blockReels
        val wantExplore = state.blockExplore
        val hideIcon = state.hideReelsIcon
        val wantFeed = state.hideSuggestedPosts || state.hideFeedAds
        val wantStoryAds = state.skipStoryAds
        if (!wantReels && !wantExplore && !hideIcon && !wantFeed && !wantStoryAds) {
            covers.hideAll()
            feedList = null
            return
        }

        val scan = scan(root, wantReels, wantExplore, hideIcon, wantFeed, wantStoryAds, deepSearch)
        exploreOnScreen = scan.exploreVisible
        // Before the Reels check: an ad's video player mustn't get the whole viewer backed out of.
        val story = scan.storyViewer
        if (wantStoryAds && story != null && handleStoryAd(story)) {
            // The pass ends here, so drop covers left from the screen under the viewer.
            covers.hide(COVER_EXPLORE)
            covers.hide(COVER_REELS_TAB)
            clearFeed()
            return
        }
        val exploreCovered = updateCovers(scan, wantExplore, hideIcon, wantFeed, deepSearch)
        val now = SystemClock.uptimeMillis()
        val searching = scan.searchActive || now < searchGraceUntil
        // Under the Explore tab but off the grid (search page, results): nothing to hide.
        val offGrid = exploreGridKnown && scan.exploreGridRect == null

        val reels = scan.reels
        if (reels == null) {
            // Any viewer-free screen ends a DM allowance. The DM context survives brief
            // in-between frames during the chat -> viewer transition (measured from when
            // the chat was left, not last seen), but not a real visit to another screen.
            allowedViewer = false
            swipeLock = false
            if (scan.sawDm) {
                dmContext = true
                leftDmAt = 0L
                lastDmSeenAt = now
            } else if (dmContext) {
                if (leftDmAt == 0L) {
                    leftDmAt = now
                } else if (now - leftDmAt > DM_CONTEXT_GRACE_MS) {
                    dmContext = false
                    leftDmAt = 0L
                }
            }

            // Explore is hidden behind a cover rather than kicked, so search stays usable.
            // Kick only if the cover couldn't be placed and Explore is really on top.
            if (wantExplore && scan.exploreVisible && !searching && !offGrid && !scan.coverBlocked && !exploreCovered) {
                block(Detection(Target.EXPLORE, viaTab = scan.exploreTabBar != null, tabBar = scan.exploreTabBar))
            }
            return
        }

        if (!reels.viaTab && state.allowDmReels && !swipeLock) {
            if (allowedViewer) return
            val freshDm = dmContext && if (leftDmAt == 0L) {
                now - lastDmSeenAt <= DM_RECENT_MS
            } else {
                now - leftDmAt <= DM_CONTEXT_GRACE_MS
            }
            // Only viewer-free scans establish the chat context: a chat left in the tree
            // under a viewer reached some other way mustn't count.
            if (freshDm) {
                allowedViewer = true
                allowedAt = now
                swipeBaseline = -1
                scrollAccum = 0
                Log.i(TAG, "Allowing reel opened from a DM")
                return
            }
        }
        dmContext = false
        leftDmAt = 0L
        block(reels)
    }

    /** Places or removes the covers for this scan. Returns whether the Explore grid is covered. */
    private fun updateCovers(scan: Scan, wantExplore: Boolean, hideIcon: Boolean, wantFeed: Boolean, deepSearch: Boolean): Boolean {
        if (scan.coverBlocked) {
            // The story-ad cover stays: it hides an ad while it's being skipped.
            covers.hide(COVER_EXPLORE)
            covers.hide(COVER_REELS_TAB)
            clearFeed()
            if (state.debugMode && deepSearch) Log.d(TAG, "Covers: off (chat/story/call/viewer on screen)")
            return false
        }
        val searching = scan.searchActive || SystemClock.uptimeMillis() < searchGraceUntil
        if (scan.exploreGridRect != null) exploreGridKnown = true
        if (scan.exploreVisible && scan.searchBarBottom > 0) lastSearchBarBottom = scan.searchBarBottom
        val searchBottom = if (scan.searchBarBottom > 0) scan.searchBarBottom else lastSearchBarBottom

        var exploreRect: Rect? = null
        var keepExplore = false
        if (wantExplore && scan.exploreVisible && !searching) {
            if (exploreGridKnown && scan.exploreGridRect == null) {
                // No grid: a search page under the Explore tab, or the capped walk missed it.
                // Only a full search can tell, so until then leave the cover as it is.
                keepExplore = !deepSearch
                // A full search can also run while the grid is still animating in, and a
                // static grid sends no more events, so look once more shortly after.
                if (deepSearch && !gridMissRechecked) {
                    gridMissRechecked = true
                    scheduleRecheck(GRID_MISS_RECHECK_MS)
                }
            } else {
                gridMissRechecked = false
                exploreRect = scan.exploreGridRect?.let { Rect(it) } ?: fallbackGridRect(scan, searchBottom)
                if (exploreRect != null) {
                    // Never over the search bar or the tab bar.
                    if (searchBottom > 0) exploreRect.top = maxOf(exploreRect.top, searchBottom)
                    if (scan.tabBarTop > 0) exploreRect.bottom = minOf(exploreRect.bottom, scan.tabBarTop)
                    if (exploreRect.isEmpty) exploreRect = null
                }
            }
        }
        if (exploreRect != null) {
            covers.show(COVER_EXPLORE, exploreRect, getString(R.string.explore_cover))
        } else if (!keepExplore) {
            covers.hide(COVER_EXPLORE)
        }

        val reelsRect = if (hideIcon) scan.reelsTabRect else null
        if (reelsRect != null) covers.show(COVER_REELS_TAB, reelsRect) else covers.hide(COVER_REELS_TAB)

        val list = scan.feedList
        if (wantFeed && scan.onHome && list != null) {
            feedList = list
            feedClipTop = scan.feedTop
            feedClipBottom = scan.tabBarTop
            updateFeed(list, still = SystemClock.uptimeMillis() - lastFeedScrollAt >= FEED_SETTLE_MS)
        } else {
            clearFeed()
        }

        if (state.debugMode && deepSearch) {
            Log.d(
                TAG,
                "Covers: explore=$exploreRect (grid=${scan.exploreGridRect}, search=$searching, kept=$keepExplore) " +
                    "reelsTab=$reelsRect feed=${feedList != null}"
            )
        }
        ensureWatchdog()
        return covers.has(COVER_EXPLORE)
    }

    private fun clearFeed() {
        feedList = null
        lastFeedFrom = -1
        badPositions = HashMap()
        caughtUpPos = -1
        rowFlags = HashMap()
        handler.removeCallbacks(feedSettled)
        covers.hideWithPrefix(COVER_FEED)
    }

    /** Everything between the search bar and the tab bar, for when the grid's id isn't known. */
    private fun fallbackGridRect(scan: Scan, searchBottom: Int): Rect? {
        val bar = scan.exploreTabBar ?: return null
        if (scan.tabBarTop <= 0) return null
        val barBounds = Rect().also { bar.getBoundsInScreen(it) }
        val top = if (searchBottom > 0) {
            searchBottom
        } else {
            (resources.displayMetrics.heightPixels * FALLBACK_GRID_TOP_FRACTION).toInt()
        }
        if (top >= scan.tabBarTop) return null
        return Rect(barBounds.left, top, barBounds.right, scan.tabBarTop)
    }

    /**
     * While a DM-shared reel is allowed, a page change in the full-screen reels pager
     * means the user swiped on to Instagram's follow-up reels, so kick them back to the
     * chat.
     */
    private fun onViewerScrolled(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val inGrace = now - allowedAt < SWIPE_GRACE_MS
        val source = event.source ?: return
        val isPager = isReelsPager(source)
        val from = event.fromIndex
        val to = event.toIndex
        // Only an actual page change counts: two pages partly visible also happens while the
        // pager settles or springs back from a nudge.
        val swiped = isPager && when {
            from >= 0 -> {
                if (swipeBaseline < 0) {
                    // Recorded even during the grace so a quick swipe still has a baseline.
                    swipeBaseline = from
                    false
                } else {
                    from != swipeBaseline
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                if (inGrace) {
                    scrollAccum = 0
                    false
                } else {
                    // Throttled scroll events split one swipe into pieces, so add them up;
                    // signed, so a nudge that springs back cancels out.
                    scrollAccum += event.scrollDeltaY
                    abs(scrollAccum) >= resources.displayMetrics.heightPixels / 4
                }
            }
            else -> !inGrace
        }
        if (state.debugMode && now - lastScrollLogAt > 300) {
            lastScrollLogAt = now
            Log.d(
                TAG,
                "DM-reel scroll: src=${source.viewIdResourceName} parent=${source.parent?.viewIdResourceName} " +
                    "pager=$isPager from=$from to=$to accum=$scrollAccum swiped=$swiped"
            )
        }
        if (!swiped) return

        allowedViewer = false
        dmContext = false
        leftDmAt = 0L
        swipeLock = true
        Log.i(TAG, "Swiped past the DM reel")
        block(Detection(Target.REELS, viaTab = false))
    }

    /** ViewPager2 reports scrolls from its inner RecyclerView, which has no id of its own. */
    private fun isReelsPager(source: AccessibilityNodeInfo): Boolean {
        if (source.viewIdResourceName in rules.reelsIdSet) return true
        val parent = source.parent ?: return false
        if (parent.viewIdResourceName in rules.reelsIdSet) return true
        return parent.parent?.viewIdResourceName in rules.reelsIdSet
    }

    /**
     * One capped tree walk gathers every signal: view-id membership (HashSet lookups
     * against viewIdResourceName, no remote per-id searches), the selected bottom tab,
     * the Reels tab icon's position, the Explore grid and search bar, and whether a DM
     * chat is on screen. Only ENABLED targets can match, so e.g. with Explore hiding off,
     * a selected Explore tab never masks the Reels viewer sitting in the same tree.
     */
    private fun scan(
        root: AccessibilityNodeInfo,
        wantReels: Boolean,
        wantExplore: Boolean,
        hideIcon: Boolean,
        wantFeed: Boolean,
        wantStoryAds: Boolean,
        deepSearch: Boolean
    ): Scan {
        val checkDm = wantReels && state.allowDmReels
        val wantCovers = hideIcon || wantExplore || wantFeed
        val screenHeight = resources.displayMetrics.heightPixels
        val searchZoneBottom = screenHeight * 35 / 100
        var coverBlocked = false
        var reelsViewer = false
        var exploreIdSeen = false
        var exploreGrid: Rect? = null
        var tabGrid: Rect? = null
        var exploreTabBar: AccessibilityNodeInfo? = null
        var searchActive = false
        var searchBarBottom = -1
        var tabBarTop = -1
        var reelsTabRect: Rect? = null
        var reelsTabChecked = false
        var sawDm = false
        var onHome = false
        var feedList: AccessibilityNodeInfo? = null
        var feedArea = 1L
        var feedTop = -1
        var feedPage = false
        var storyViewer: AccessibilityNodeInfo? = null
        var storyArea = 0L
        val bounds = Rect()

        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            // Preloaded or backgrounded pages stay in the tree; only what's on screen counts.
            val visible = node.isVisibleToUser

            val viewId = node.viewIdResourceName
            if (viewId != null && visible) {
                if (wantReels && !reelsViewer && viewId in rules.reelsIdSet) reelsViewer = true
                if (wantExplore && viewId in rules.exploreIdSet) {
                    exploreIdSeen = true
                    node.getBoundsInScreen(bounds)
                    if (exploreGrid == null || area(bounds) > area(exploreGrid)) exploreGrid = Rect(bounds)
                }
                if (wantExplore && viewId in rules.exploreTabGridIdSet) {
                    node.getBoundsInScreen(bounds)
                    if (tabGrid == null || area(bounds) > area(tabGrid)) tabGrid = Rect(bounds)
                }
                if (checkDm && !sawDm) sawDm = rules.isDmViewId(viewId)
                if (wantStoryAds && rules.isStoryViewId(viewId)) {
                    node.getBoundsInScreen(bounds)
                    val a = area(bounds)
                    if (a > storyArea) {
                        storyArea = a
                        storyViewer = node
                    }
                }
                if (wantCovers && !coverBlocked) {
                    coverBlocked = viewId in rules.reelsIdSet || rules.isDmViewId(viewId) ||
                        rules.isNoCoverViewId(viewId)
                }
            }

            if (wantCovers && visible && node.isEditable) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < searchZoneBottom) {
                    if (wantExplore) {
                        searchBarBottom = maxOf(searchBarBottom, bounds.bottom)
                        val typed = !node.text.isNullOrEmpty() && !node.isShowingHintText
                        if (node.isFocused || typed) searchActive = true
                    }
                } else {
                    // A typing box lower down (comments, chat, story reply) sits where the
                    // tab bar was, so never cover it, whatever screen it's on.
                    coverBlocked = true
                }
            } else if (wantExplore && visible && looksLikeSearch(node)) {
                // Newer builds show a tappable "Search" box that only turns into a text
                // field on its own page; it still marks where the cover has to stop.
                node.getBoundsInScreen(bounds)
                if (bounds.top < searchZoneBottom && bounds.height() < screenHeight / 8) {
                    searchBarBottom = maxOf(searchBarBottom, bounds.bottom)
                }
            }

            if (wantFeed && visible && viewId != null && viewId in rules.feedTopIdSet) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < screenHeight / 3) feedTop = maxOf(feedTop, bounds.bottom)
            }
            // The Following and Favorites feeds open over Home without the tab bar.
            if (wantFeed && visible && !feedPage && viewId != null && viewId in rules.feedPageTitleIdSet) {
                val title = node.text?.toString() ?: node.contentDescription?.toString()
                if (title != null && rules.feedPageTitles.any { title.equals(it, ignoreCase = true) }) feedPage = true
            }

            if (wantFeed && visible && node.isScrollable && scrollsVertically(node)) {
                node.getBoundsInScreen(bounds)
                val a = area(bounds)
                // Ties go to the deeper node: the pager around Home is exactly as big as the feed.
                if (a >= feedArea) {
                    feedArea = a
                    feedList = node
                }
            }

            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                val isReelsLabel = rules.reelsTabDescs.any { descMatches(desc, it) }
                if (node.isSelected && wantFeed && !onHome && isHomeDesc(desc)) {
                    val bar = tabBarOf(node)
                    if (bar != null) {
                        onHome = true
                        if (tabBarTop <= 0) tabBarTop = tabTop(bar)
                    }
                }
                if (node.isSelected) {
                    val exploreMatch = !isReelsLabel && wantExplore && rules.exploreTabDescs.any { descMatches(desc, it) }
                    if ((isReelsLabel && wantReels) || exploreMatch) {
                        // Tab-bar context keeps a selected "Reels" grid tab on a profile
                        // page (or anything else labeled Reels) from matching.
                        val bar = tabBarOf(node)
                        if (bar != null) {
                            if (isReelsLabel) {
                                return Scan(
                                    reels = Detection(Target.REELS, viaTab = true, tabBar = bar),
                                    exploreVisible = false, exploreTabBar = null, exploreGridRect = null,
                                    searchActive = false, searchBarBottom = -1, tabBarTop = -1,
                                    reelsTabRect = null, sawDm = false, coverBlocked = false,
                                    storyViewer = storyViewer
                                )
                            }
                            exploreTabBar = bar
                            tabBarTop = tabTop(bar)
                        }
                    }
                }
                if (hideIcon && isReelsLabel && !reelsTabChecked && visible) {
                    val label = Rect().also { node.getBoundsInScreen(it) }
                    val cached = cachedReelsCell
                    if (cached != null && label == cachedReelsLabel) {
                        reelsTabChecked = true
                        reelsTabRect = Rect(cached)
                        if (tabBarTop <= 0) tabBarTop = cachedTabBarTop
                    } else {
                        val bar = tabBarOf(node)
                        val barTop = if (bar != null) tabTop(bar) else -1
                        // A real bottom nav is on screen and sits in the bottom quarter.
                        if (bar != null && bar.isVisibleToUser && barTop >= screenHeight * 3 / 4) {
                            reelsTabChecked = true
                            // Cover the whole tab cell, not just the icon, so it reads as empty.
                            val cell = if (node.parent == bar) node else node.parent ?: node
                            val cellRect = Rect().also { cell.getBoundsInScreen(it) }
                            reelsTabRect = cellRect
                            cachedReelsLabel.set(label)
                            cachedReelsCell = Rect(cellRect)
                            cachedTabBarTop = barTop
                            if (tabBarTop <= 0) tabBarTop = barTop
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }

        // The walk is capped, so after a screen transition also do the full remote
        // search in case the viewer, grid or chat sits deeper than MAX_NODES.
        if (deepSearch) {
            if (wantReels && !reelsViewer && firstVisible(root, rules.reelsViewIds) != null) reelsViewer = true
            if (wantExplore && exploreGrid == null) {
                val grid = firstVisible(root, rules.exploreViewIds)
                if (grid != null) {
                    exploreIdSeen = true
                    exploreGrid = Rect().also { grid.getBoundsInScreen(it) }
                }
            }
            val dmAlreadyFresh = dmContext && leftDmAt == 0L
            val walkCapped = visited >= MAX_NODES
            if (checkDm && !sawDm && !dmAlreadyFresh && rules.dmViewIds.isNotEmpty()) {
                sawDm = firstVisible(root, rules.dmViewIds.take(1)) != null
            }
            // A long chat can push its ids past the walk cap while the tab label is found.
            if (wantCovers && !coverBlocked && walkCapped) {
                coverBlocked = sawDm || firstVisible(root, rules.dmViewIds) != null
            }
        }
        // Current builds give the Explore grid a generic id, so it only counts on that tab.
        if (exploreGrid == null && exploreTabBar != null) exploreGrid = tabGrid

        return Scan(
            reels = if (reelsViewer) Detection(Target.REELS, viaTab = false) else null,
            exploreVisible = exploreTabBar != null || exploreIdSeen,
            exploreTabBar = exploreTabBar,
            exploreGridRect = exploreGrid,
            searchActive = searchActive,
            searchBarBottom = searchBarBottom,
            tabBarTop = tabBarTop,
            reelsTabRect = reelsTabRect,
            sawDm = sawDm,
            coverBlocked = coverBlocked || (wantCovers && reelsViewer),
            onHome = onHome || feedPage,
            feedList = if (onHome || feedPage) feedList else null,
            feedTop = feedTop,
            storyViewer = storyViewer
        )
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height().toLong()

    /**
     * The feed scrolls vertically. The camera/DM pager around Home scrolls sideways and is
     * bigger, so taking it for the feed would put one cover over the whole screen.
     */
    private fun scrollsVertically(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.contains("ViewPager") == true) return false
        // ViewPager2 and Instagram's own pagers scroll through an inner RecyclerView.
        if (node.viewIdResourceName?.contains("pager", ignoreCase = true) == true) return false
        val info = node.collectionInfo ?: return true
        return !(info.rowCount <= 1 && info.columnCount > 1)
    }

    private fun firstVisible(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id)
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        return null
    }

    /** Only ever evaluated against selected nodes or tab-bar children, so "Home decor"-style content can't reach it. */
    private fun descMatches(desc: String, candidate: String): Boolean =
        DetectionRules.labelMatches(desc, candidate)

    /** The bottom nav container: a real tab sits next to the Home tab; labels are sometimes nested one level down. */
    private fun tabBarOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = node.parent ?: return null
        if (hasHomeChild(parent)) return parent
        val grandparent = parent.parent ?: return null
        return grandparent.takeIf { hasHomeChild(it) }
    }

    /**
     * Top of the tab row, from the Home tab itself: the tab bar's own layout isn't exposed
     * to accessibility, so the container found may be a screen-sized ancestor.
     */
    private fun tabTop(bar: AccessibilityNodeInfo): Int {
        val home = findHomeTab(bar) ?: return -1
        return Rect().also { home.getBoundsInScreen(it) }.top
    }

    private fun hasHomeChild(container: AccessibilityNodeInfo): Boolean = findHomeTab(container) != null

    /** Only direct children of the tab bar (or their immediate children) — never the whole screen. */
    private fun findHomeTab(bar: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val count = bar.childCount
        if (count == 0 || count > 12) return null
        for (i in 0 until count) {
            val tab = bar.getChild(i) ?: continue
            if (isHomeDesc(tab.contentDescription?.toString())) return tab
            val cc = tab.childCount
            if (cc in 1..4) {
                for (j in 0 until cc) {
                    val label = tab.getChild(j) ?: continue
                    if (isHomeDesc(label.contentDescription?.toString())) {
                        return if (tab.isClickable || !label.isClickable) tab else label
                    }
                }
            }
        }
        return null
    }

    private fun isHomeDesc(desc: String?): Boolean =
        desc != null && rules.homeTabDescs.any { descMatches(desc, it) }

    private fun clickHomeTab(bar: AccessibilityNodeInfo): Boolean {
        val home = findHomeTab(bar) ?: return false
        return home.isClickable && home.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun block(detection: Detection) {
        val now = SystemClock.uptimeMillis()
        val remaining = ACTION_COOLDOWN_MS - (now - lastActionAt)
        if (remaining > 0) {
            // Still escaping from the last block. A static screen sends no more events,
            // so re-check when the cooldown ends instead of dropping this detection.
            scheduleRecheck(remaining + 50)
            return
        }

        val performed = if (detection.viaTab) {
            detection.tabBar?.let { clickHomeTab(it) } == true || safeBack()
        } else {
            safeBack()
        }
        if (!performed) {
            // Nothing was pressed (e.g. focus mid-transition), so this isn't a block yet.
            // Capped so a persistently failing action can't spin deep searches forever.
            // Later events still attempt the block; they just don't schedule more retries
            // until a success or a screen change resets the count.
            if (++failedAttempts <= MAX_RETRIES) scheduleRecheck(RETRY_MS)
            return
        }

        failedAttempts = 0
        lastActionAt = now
        state.incrementToday()
        while (recentBlocks.isNotEmpty() && now - recentBlocks.first() > RAPID_WINDOW_MS) recentBlocks.removeFirst()
        recentBlocks.addLast(now)

        Log.i(TAG, "Blocked ${detection.target} (viaTab=${detection.viaTab}, today=${state.todayCount()})")

        // Explore is covered rather than kicked in normal use, and a full-screen overlay
        // there would sit on top of the search bar.
        if (recentBlocks.size >= RAPID_THRESHOLD && detection.target == Target.REELS) showOverlay(detection.target)

        // A delivered click or BACK can still be ignored by Instagram mid-animation;
        // verify once the cooldown ends.
        scheduleRecheck(ACTION_COOLDOWN_MS + 50)
    }

    /** The Explore search bar: a text field, a known id, or a "Search" label (also "Ask Meta AI or Search"). */
    private fun looksLikeSearch(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.viewIdResourceName in rules.exploreSearchIdSet) return true
        return listOf(node.text, node.contentDescription, node.hintText).any {
            it != null && SEARCH_WORD.containsMatchIn(it)
        }
    }

    /** A tap on the Explore search bar uncovers the page at once, before focus is reported. */
    private fun onPossibleSearchTap(event: AccessibilityEvent) {
        if (!state.blockExplore || !exploreOnScreen) return
        val source = event.source ?: return
        val b = Rect().also { source.getBoundsInScreen(it) }
        if (b.top >= resources.displayMetrics.heightPixels * 35 / 100) return
        if (!looksLikeSearch(source)) return
        searchGraceUntil = SystemClock.uptimeMillis() + SEARCH_GRACE_MS
        covers.hide(COVER_EXPLORE)
        // If the tap didn't open search, a static grid sends no more events to bring the cover back.
        handler.removeCallbacks(searchGraceEnd)
        handler.postDelayed(searchGraceEnd, SEARCH_GRACE_MS + 50)
        if (state.debugMode) Log.d(TAG, "Search bar tapped: Explore cover off")
    }

    private fun onFeedScrolled(event: AccessibilityEvent) {
        val list = feedList ?: return
        if (event.source != list) return
        val now = SystemClock.uptimeMillis()
        lastFeedScrollAt = now
        // Resting covers come off the moment the feed moves, so they never drag behind it.
        covers.hideWithPrefix(COVER_FEED)
        // Rows are reused as the list scrolls, so the direction comes from the first visible
        // position (or the scroll delta), not from row positions.
        val from = event.fromIndex
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val dir = when {
            dy != 0 -> if (dy > 0) 1 else -1
            from >= 0 && lastFeedFrom >= 0 && from != lastFeedFrom -> if (from > lastFeedFrom) 1 else -1
            else -> 0
        }
        if (from >= 0) lastFeedFrom = from
        // Back at the top: a pull-to-refresh can insert posts, so the remembered end of the
        // friends feed no longer holds.
        if (from == 0) caughtUpPos = -1
        // The skip swipe's own scroll events (and its tail) don't count as the user's, except
        // movement against it, which can only be the user: they win at once.
        val against = dir != 0 && dir != skipDirection && now < skipUntil + USER_SCROLL_GAP_MS
        if (against) skipUntil = 0L
        if (against || now >= skipUntil + USER_SCROLL_GAP_MS) {
            skipsWithoutUser = 0
            if (dir != 0) feedDirection = dir
        }
        handler.removeCallbacks(feedSettled)
        handler.postDelayed(feedSettled, maxOf(FEED_SETTLE_MS, skipUntil - now + 20))
    }

    /**
     * Finds ads and suggested posts in the home feed and, once the feed is still, scrolls
     * them out of view. Nothing is drawn over the feed while it moves: covers can only be
     * moved about ten times a second, so on a moving list they visibly drag.
     *
     * A post spans several rows, so rows are grouped from one post header (or section
     * heading) to the next. A group is an ad when it carries "Sponsored"/"Ad" or an ad's
     * call-to-action bar, and suggested when its header has a Follow button (accounts the
     * user already follows don't get one) or it's a "Suggested for you" section. Everything
     * after "You're all caught up" is suggested, so the feed is pushed back up to that line
     * instead of skipping through endless suggestions.
     *
     * Instagram reuses row views for new posts as the list scrolls, so markers are searched
     * again after every scroll, and what's remembered between passes (the unwanted rows, the
     * caught-up line) is keyed by list position, never by the reused view.
     */
    private fun updateFeed(list: AccessibilityNodeInfo, still: Boolean) {
        if (!still) {
            covers.hideWithPrefix(COVER_FEED)
            return
        }
        val now = SystemClock.uptimeMillis()
        lastFeedScanAt = now
        val hideAds = state.hideFeedAds
        val hideSuggested = state.hideSuggestedPosts
        val full = Rect().also { list.getBoundsInScreen(it) }
        // The visible part: the top bar and stories row float over the top of the list.
        val view = Rect(full)
        if (feedClipTop > view.top && feedClipTop < view.bottom) view.top = feedClipTop
        if (feedClipBottom > view.top && feedClipBottom < view.bottom) view.bottom = feedClipBottom
        if (view.height() <= 0) return

        val rows = ArrayList<FeedRow>()
        for (i in 0 until minOf(list.childCount, MAX_FEED_ROWS)) {
            val node = list.getChild(i) ?: continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.intersect(full)) rows.add(FeedRow(node, r, node.collectionItemInfo?.rowIndex ?: -1))
        }
        rows.sortBy { it.rect.top }
        // Instagram's rows don't report their list position, but scroll events report the
        // first visible one, and the rows on screen follow on from it.
        if (rows.any { it.pos < 0 }) {
            rows.forEachIndexed { k, row -> row.pos = if (lastFeedFrom >= 0) lastFeedFrom + k else -1 }
        }

        // Each search walks Instagram's tree on its UI thread, so results are reused while
        // nothing has scrolled (a playing video keeps sending events). Never across a
        // scroll: the list reuses its views for other posts as it moves.
        val mode = (if (hideAds) 1 else 0) or (if (hideSuggested) 2 else 0)
        if (mode != rowFlagsMode) {
            badPositions = HashMap()
            caughtUpPos = -1
        }
        val reuse = mode == rowFlagsMode && lastFeedScrollAt < lastFeedFreshAt &&
            now - lastFeedFreshAt < FEED_RESCAN_MS && rows.all { it.node in rowFlags }
        if (reuse) {
            for (row in rows) row.flags = rowFlags[row.node] ?: 0
        } else {
            val t0 = SystemClock.uptimeMillis()
            lastFeedFreshAt = now
            markIds(list, rules.feedHeaderViewIds, rows, full, ROW_HEADER)
            markLabels(list, rows, full, rules.feedHeaderDescs, ROW_HEADER, contains = false)
            if (hideAds) {
                // A text search for a short label like "Ad" matches every "add" and "read" on
                // screen, so those are only looked for in the header subtitle.
                val (short, long) = rules.sponsoredLabels.partition { it.length < MIN_SEARCH_LABEL }
                markLabels(list, rows, full, long, ROW_AD, contains = false)
                if (short.isNotEmpty()) {
                    for (id in rules.feedSubtitleViewIds) {
                        val hits = list.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id).filter { n ->
                            val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""
                            short.any { t.equals(it, ignoreCase = true) }
                        }
                        markRows(hits, rows, full, ROW_AD)
                    }
                }
                markIds(list, rules.feedAdViewIds, rows, full, ROW_AD)
            }
            if (hideSuggested) {
                markLabels(list, rows, full, rules.suggestedLabels, ROW_SUGGESTED, contains = false)
                markIds(list, rules.feedFollowViewIds, rows, full, ROW_FOLLOW)
                markLabels(list, rows, full, rules.followLabels, ROW_FOLLOW, contains = false)
                markLabels(list, rows, full, rules.caughtUpLabels, ROW_CAUGHT_UP, contains = true)
            }
            rowFlags = rows.associateTo(HashMap()) { it.node to it.flags }
            rowFlagsMode = mode
            if (state.debugMode) Log.d(TAG, "Feed markers searched in ${SystemClock.uptimeMillis() - t0}ms")
        }

        // The caught-up line, by list position. If its position now shows something else,
        // the list changed under it.
        rows.firstOrNull { it.flags and ROW_CAUGHT_UP != 0 && it.pos >= 0 }?.let { caughtUpPos = it.pos }
        if (caughtUpPos >= 0 && rows.any { it.pos == caughtUpPos && it.flags and ROW_CAUGHT_UP == 0 }) caughtUpPos = -1

        // Rows above the first header belong to a post whose header is off screen: they
        // keep what their positions were last pass, unless their own markers say more.
        val firstStart = rows.indexOfFirst { it.flags and ROW_STARTS != 0 }
        val orphanEnd = if (firstStart < 0) rows.size else firstStart
        var groupReason = (0 until orphanEnd).firstNotNullOfOrNull { i -> badPositions[rows[i].pos] } ?: 0
        val reasons = IntArray(rows.size)
        var start = 0
        var caughtUp = false
        for (i in rows.indices) {
            val f = rows[i].flags
            if (!caughtUp && caughtUpPos >= 0 && rows[i].pos > caughtUpPos) {
                // Below the "caught up" row, which has scrolled off the top.
                reasons.fill(groupReason, start, i)
                caughtUp = true
            }
            if (caughtUp) {
                reasons[i] = ROW_CAUGHT_UP
                continue
            }
            if (f and ROW_CAUGHT_UP != 0) {
                // The "caught up" row itself stays as the end of the friends feed.
                reasons.fill(groupReason, start, i)
                caughtUp = true
                continue
            }
            if (f and ROW_STARTS != 0) {
                reasons.fill(groupReason, start, i)
                start = i
                groupReason = 0
            }
            if (f and ROW_AD != 0) {
                groupReason = ROW_AD
            } else if (f and (ROW_SUGGESTED or ROW_FOLLOW) != 0 && groupReason == 0) {
                groupReason = ROW_SUGGESTED
            }
        }
        if (!caughtUp) reasons.fill(groupReason, start, rows.size)

        // Visible stretches of unwanted rows.
        val nowBad = HashMap<Int, Int>()
        val groups = ArrayList<Pair<Rect, Int>>()
        var i = 0
        while (i < rows.size) {
            if (reasons[i] == 0) {
                i++
                continue
            }
            val rect = Rect(rows[i].rect)
            val reason = reasons[i]
            var j = i
            while (j < rows.size && reasons[j] != 0) {
                rect.union(rows[j].rect)
                if (rows[j].pos >= 0 && reasons[j] != ROW_CAUGHT_UP) nowBad[rows[j].pos] = reasons[j]
                j++
            }
            if (rect.intersect(view)) groups.add(rect to reason)
            i = j
        }
        badPositions = nowBad

        if (state.debugMode && now - lastFeedLogAt > 1000) {
            lastFeedLogAt = now
            val summary = rows.indices.joinToString(" ") { rowCode(rows[it].flags) + if (reasons[it] != 0) "*" else "" }
            Log.d(
                TAG,
                "Feed rows (H=header A=ad S=suggested F=follow C=caught-up, *=unwanted): $summary " +
                    "dir=$feedDirection first=${rows.firstOrNull()?.pos} caughtUp=$caughtUpPos"
            )
        }

        if (now < skipUntil) return
        val guardTripped = skipsWithoutUser >= MAX_SKIPS_WITHOUT_USER
        // While the stories row is showing (a tall bar floats over the top of the list), the
        // user is at the top of the feed: skipping would scroll the stories away.
        val atTop = view.top - full.top > view.height() * STORIES_SHOWING_SHARE
        val skipped = groups.isNotEmpty() && !guardTripped && !atTop && skipPast(groups, view)
        // Whatever is left at rest (an ad beside a post being read, a sliver at the edge)
        // gets a plain patch until the feed moves again.
        var shown = 0
        if (!skipped) {
            for ((rect, _) in groups) {
                if (shown >= MAX_FEED_COVERS) break
                // Once skipping has given up, never blank most of the screen.
                if (guardTripped && rect.height() >= view.height() * SKIP_FILL_SHARE) continue
                covers.show(COVER_FEED + shown, rect, touchable = false)
                shown++
            }
        }
        for (k in shown until MAX_FEED_COVERS) covers.hide(COVER_FEED + k)
        if (shown > 0) ensureWatchdog()
    }

    /** Scrolls the feed so the unwanted stretch the user is moving into leaves the screen. */
    private fun skipPast(groups: List<Pair<Rect, Int>>, view: Rect): Boolean {
        val h = view.height()
        // Skipped once the user has scrolled into it (it reaches the near quarter of the
        // feed) or it fills most of the feed. Anything else is next to a post the user may
        // still be reading, so it's patched over instead of dragging that post away too.
        fun reached(r: Rect, down: Boolean) = r.height() >= h * SKIP_FILL_SHARE ||
            if (down) r.top < view.top + h * SKIP_EDGE_SHARE else r.bottom > view.bottom - h * SKIP_EDGE_SHARE
        // The end of the friends feed: push back up rather than into endless suggestions.
        val wall = groups.firstOrNull {
            it.second == ROW_CAUGHT_UP && (it.first.height() >= h * SKIP_MIN_SHARE || it.first.top < view.top + h / 2)
        }
        val distance = when {
            wall != null -> -(view.bottom - wall.first.top)
            feedDirection > 0 -> groups.firstOrNull { reached(it.first, down = true) }?.let { it.first.bottom - view.top }
            else -> groups.lastOrNull { reached(it.first, down = false) }?.let { -(view.bottom - it.first.top) }
        } ?: return false
        if (abs(distance) < MIN_SKIP_PX) return false
        if (!swipeFeed(view, distance)) return false
        skipsWithoutUser++
        Log.i(TAG, "Skipped ${if (wall != null) "back to the end of the friends feed" else "an ad or suggested post"} (${distance}px)")
        return true
    }

    /**
     * Drags the feed by [distance] px (positive: content moves up), then holds still so it
     * doesn't fling on. A skip longer than the screen finishes on the next pass. Downward
     * drags start low in the feed; upward ones start well below its top, clear of the
     * status bar and Instagram's top bar.
     */
    private fun swipeFeed(view: Rect, distance: Int): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val x = view.exactCenterX()
        val y0: Float
        val room: Float
        if (distance > 0) {
            y0 = view.bottom - SWIPE_EDGE_PX.toFloat()
            room = y0 - (view.top + SWIPE_EDGE_PX)
        } else {
            y0 = view.top + maxOf(SWIPE_EDGE_PX.toFloat(), view.height() * SWIPE_UP_START_SHARE)
            room = view.bottom - SWIPE_EDGE_PX - y0
        }
        val travel = minOf((abs(distance) + slop).toFloat(), room)
        if (travel <= slop) return false
        val y1 = if (distance > 0) y0 - travel else y0 + travel
        val moveMs = maxOf(SKIP_MIN_MOVE_MS, (travel / SKIP_MOVE_PX_PER_MS).toLong())
        return try {
            val move = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y0); lineTo(x, y1) }, 0, moveMs, true)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    try {
                        val hold = move.continueStroke(Path().apply { moveTo(x, y1) }, 0, SKIP_HOLD_MS, false)
                        if (!dispatchGesture(GestureDescription.Builder().addStroke(hold).build(), null, null)) {
                            Log.w(TAG, "Skip swipe hold refused")
                        }
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Skip swipe hold failed", e)
                    }
                }
            }
            val ok = dispatchGesture(GestureDescription.Builder().addStroke(move).build(), callback, null)
            if (ok) {
                skipUntil = SystemClock.uptimeMillis() + moveMs + SKIP_HOLD_MS + SKIP_SETTLE_MS
                skipDirection = if (distance > 0) 1 else -1
            }
            ok
        } catch (e: RuntimeException) {
            Log.w(TAG, "Skip swipe failed", e)
            false
        }
    }

    private fun rowCode(flags: Int): String = buildString {
        if (flags and ROW_HEADER != 0) append('H')
        if (flags and ROW_AD != 0) append('A')
        if (flags and ROW_SUGGESTED != 0) append('S')
        if (flags and ROW_FOLLOW != 0) append('F')
        if (flags and ROW_CAUGHT_UP != 0) append('C')
        if (isEmpty()) append('.')
    }

    private fun markIds(list: AccessibilityNodeInfo, ids: List<String>, rows: List<FeedRow>, bounds: Rect, flag: Int) {
        for (id in ids) markRows(list.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id), rows, bounds, flag)
    }

    private fun markLabels(
        list: AccessibilityNodeInfo,
        rows: List<FeedRow>,
        bounds: Rect,
        labels: List<String>,
        flag: Int,
        contains: Boolean
    ) {
        for (label in labels) {
            // The search is a case-insensitive "contains"; narrow it to the label itself.
            val hits = list.findAccessibilityNodeInfosByText(label).filter { n ->
                listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any {
                    if (contains) it.contains(label, ignoreCase = true) else DetectionRules.labelMatches(it, label)
                }
            }
            markRows(hits, rows, bounds, flag)
        }
    }

    /** Flags the row each visible node sits in. */
    private fun markRows(nodes: List<AccessibilityNodeInfo>, rows: List<FeedRow>, bounds: Rect, flag: Int) {
        val b = Rect()
        for (n in nodes) {
            if (!n.isVisibleToUser) continue
            n.getBoundsInScreen(b)
            if (!b.intersect(bounds)) continue
            val y = b.centerY()
            rows.firstOrNull { y >= it.rect.top && y < it.rect.bottom }?.let { it.flags = it.flags or flag }
        }
    }

    /**
     * A story ad is marked "Sponsored" under the account name at the top. Swipes on to the
     * next account's stories, which skips every card of the ad, behind a brief cover.
     * Returns true while an ad is being skipped, so nothing else acts on that screen.
     */
    private fun handleStoryAd(viewer: AccessibilityNodeInfo): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastStorySkipAt < STORY_SKIP_COOLDOWN_MS) return covers.has(COVER_STORY_AD)
        // Searched inside the viewer and its bounds only: the home feed stays in the tree
        // under it, and a "Sponsored" post there must not skip a friend's story.
        val screen = Rect().also { viewer.getBoundsInScreen(it) }
        if (screen.isEmpty) return false
        val topZone = screen.top + screen.height() / 3
        val b = Rect()
        fun inViewer(n: AccessibilityNodeInfo) = n.isVisibleToUser && b.also { n.getBoundsInScreen(it) }.let { screen.contains(it) }
        // Current builds mark a story ad with an "Ad" pill and an ad-only message box, and
        // describe the header as "<account>'s sponsored story". Older ones show "Sponsored"
        // under the account name.
        val isAd = rules.storyAdViewIds.any { id ->
            viewer.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id).any { inViewer(it) }
        } || rules.storyAdPhrases.any { phrase ->
            viewer.findAccessibilityNodeInfosByText(phrase).any { n ->
                inViewer(n) && b.top < topZone &&
                    listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { it.contains(phrase, ignoreCase = true) }
            }
        } || rules.sponsoredLabels.filter { it.length >= MIN_SEARCH_LABEL }.any { label ->
            viewer.findAccessibilityNodeInfosByText(label).any { n ->
                inViewer(n) && b.top < topZone &&
                    listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                        .any { DetectionRules.labelMatches(it, label) }
            }
        }
        if (!isAd) return false

        // The viewer's own bounds, so in split screen the swipe and cover stay on Instagram.
        val y = screen.exactCenterY()
        val path = Path().apply {
            moveTo(screen.left + screen.width() * 0.85f, y)
            lineTo(screen.left + screen.width() * 0.15f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, STORY_SWIPE_MS))
            .build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.w(TAG, "Story ad found but the skip swipe was refused")
            return false
        }
        lastStorySkipAt = now
        // Touches pass through, so the cover can't catch the swipe it hides.
        covers.show(COVER_STORY_AD, screen, getString(R.string.story_ad_cover), touchable = false)
        ensureWatchdog()
        handler.removeCallbacks(hideStoryCover)
        handler.postDelayed(hideStoryCover, STORY_COVER_MS)
        // Back-to-back ads, or a swipe Instagram ignored: look again once it settles.
        scheduleRecheck(STORY_SKIP_COOLDOWN_MS + 50)
        Log.i(TAG, "Skipped a story ad")
        return true
    }

    /** BACK goes to whatever window holds focus when it lands — re-check it's still Instagram. */
    private fun safeBack(): Boolean {
        if (rootInActiveWindow?.packageName?.toString() != IG) return false
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun showOverlay(target: Target) {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_blocked, null)
        view.findViewById<TextView>(R.id.tv_overlay_title).setText(
            if (target == Target.REELS) R.string.overlay_reels else R.string.overlay_explore
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
            overlayView = view
            handler.postDelayed({ removeOverlay() }, OVERLAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay failed", e)
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    /**
     * Debug mode only. Logs visible view IDs, plus selected nodes' labels (truncated) and
     * whether each passed the tab-bar check — the two things needed to fix detection
     * after an Instagram update.
     */
    private fun debugLog(root: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDebugLogAt < 1000) return
        lastDebugLogAt = now
        val ids = LinkedHashSet<String>()
        val selected = ArrayList<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids.add(it.removePrefix(DetectionRules.IG_ID_PREFIX)) }
            if (node.isSelected && selected.size < 10) {
                val desc = node.contentDescription?.toString()?.take(40) ?: "<no desc>"
                selected.add("\"$desc\" tabBar=${tabBarOf(node) != null}")
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        Log.d(TAG, "Screen IDs (${ids.size}): ${ids.take(60).joinToString(",")}")
        Log.d(TAG, "Selected nodes: ${selected.joinToString("; ").ifEmpty { "none" }}")
    }
}

private const val COVER_EXPLORE = "explore"
private const val COVER_REELS_TAB = "reelsTab"
private const val COVER_STORY_AD = "storyAd"
private const val COVER_FEED = "feed"
