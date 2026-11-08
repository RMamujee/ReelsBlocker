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
        /** Home tab selected; feedList is the biggest visible scrolling list on it. */
        val onHome: Boolean = false,
        val feedList: AccessibilityNodeInfo? = null,
        /** The largest visible story-viewer view, so ad checks never see the feed left under it. */
        val storyViewer: AccessibilityNodeInfo? = null
    )

    /** One child of the home feed list. Instagram splits a post across several rows. */
    private class FeedRow(val node: AccessibilityNodeInfo, val rect: Rect) {
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

    // Home feed: the list being filtered, and which of its rows were hidden last pass, so
    // the rest of an ad stays hidden after its header scrolls off the top.
    private var feedList: AccessibilityNodeInfo? = null
    private var feedClipBottom = -1
    private var hiddenRows = HashMap<AccessibilityNodeInfo, Int>()
    private var rowFlags = HashMap<AccessibilityNodeInfo, Int>()
    private var rowFlagsMode = -1
    private var lastFeedScanAt = 0L
    private var lastFeedLogAt = 0L
    private val feedTrailing = Runnable { refreshFeedCovers() }

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

    // The service only hears Instagram's events, so it can't tell when the user leaves.
    // Covers sit above every app, so while one is on screen (and only then) poll which
    // app is in front and remove them the moment it isn't Instagram.
    private var watchdogRunning = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (!covers.isShowing) {
                watchdogRunning = false
                return
            }
            // Only the package name is needed, so skip prefetching the window's nodes.
            val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getRootInActiveWindow(0)
            } else {
                rootInActiveWindow
            }
            val pkg = root?.packageName?.toString()
            if (pkg != null && pkg != IG) {
                covers.hideAll()
                // The notification shade or recents over Instagram isn't leaving it.
                if (pkg != SYSTEM_UI) resetDmState()
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
        // Feed covers follow scrolling: at most one rescan per FEED_SCROLL_MS, plus one
        // after the list settles.
        private const val FEED_SCROLL_MS = 100L
        private const val FEED_TRAILING_MS = 150L
        private const val MAX_FEED_ROWS = 30
        private const val MAX_FEED_COVERS = 6
        private const val MIN_FEED_COVER_PX = 8
        private const val ROW_HEADER = 1
        private const val ROW_AD = 2
        private const val ROW_SUGGESTED = 4
        private const val ROW_CAUGHT_UP = 8
        private const val ROW_CTA = 16
        /** Rows that begin a new post or section. */
        private const val ROW_STARTS = ROW_HEADER or ROW_AD or ROW_SUGGESTED or ROW_CAUGHT_UP
        // A skip swipe plus Instagram's page animation; the next story is checked after.
        private const val STORY_SWIPE_MS = 120L
        private const val STORY_SKIP_COOLDOWN_MS = 900L
        private const val STORY_COVER_MS = 800L

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
        if (event.packageName?.toString() != IG) return

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
        if (watchdogRunning || !covers.isShowing) return
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
        if (wantStoryAds && story != null && handleStoryAd(story)) return
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
            clearFeedCovers()
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
            feedClipBottom = scan.tabBarTop
            updateFeedCovers(list, scan.tabBarTop)
        } else {
            clearFeedCovers()
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

    private fun clearFeedCovers() {
        feedList = null
        hiddenRows = HashMap()
        rowFlags = HashMap()
        handler.removeCallbacks(feedTrailing)
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
        var exploreTabBar: AccessibilityNodeInfo? = null
        var searchActive = false
        var searchBarBottom = -1
        var tabBarTop = -1
        var reelsTabRect: Rect? = null
        var reelsTabChecked = false
        var sawDm = false
        var onHome = false
        var feedList: AccessibilityNodeInfo? = null
        var feedArea = 0L
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

            if (wantFeed && visible && node.isScrollable) {
                node.getBoundsInScreen(bounds)
                val a = area(bounds)
                if (a > feedArea) {
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
                        bar.getBoundsInScreen(bounds)
                        if (tabBarTop <= 0) tabBarTop = bounds.top
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
                            bar.getBoundsInScreen(bounds)
                            tabBarTop = bounds.top
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
                        val barBounds = Rect()
                        bar?.getBoundsInScreen(barBounds)
                        // A real bottom nav is on screen and sits in the bottom quarter.
                        if (bar != null && bar.isVisibleToUser && barBounds.top >= screenHeight * 3 / 4) {
                            reelsTabChecked = true
                            // Cover the whole tab cell, not just the icon, so it reads as empty.
                            val cell = if (node.parent == bar) node else node.parent ?: node
                            val cellRect = Rect().also { cell.getBoundsInScreen(it) }
                            reelsTabRect = cellRect
                            cachedReelsLabel.set(label)
                            cachedReelsCell = Rect(cellRect)
                            cachedTabBarTop = barBounds.top
                            if (tabBarTop <= 0) tabBarTop = barBounds.top
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
            onHome = onHome,
            feedList = if (onHome) feedList else null,
            storyViewer = storyViewer
        )
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height().toLong()

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
            it?.contains("search", ignoreCase = true) == true
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
        if (state.debugMode) Log.d(TAG, "Search bar tapped: Explore cover off")
    }

    private fun onFeedScrolled(event: AccessibilityEvent) {
        val list = feedList ?: return
        if (event.source != list) return
        handler.removeCallbacks(feedTrailing)
        handler.postDelayed(feedTrailing, FEED_TRAILING_MS)
        if (SystemClock.uptimeMillis() - lastFeedScanAt < FEED_SCROLL_MS) return
        refreshFeedCovers()
    }

    private fun refreshFeedCovers() {
        val list = feedList ?: return
        if (!state.isActive() || !(state.hideSuggestedPosts || state.hideFeedAds)) {
            clearFeedCovers()
            return
        }
        // Same rule as every other screen read: only while Instagram is in front.
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getRootInActiveWindow(0)
        } else {
            rootInActiveWindow
        }
        if (root?.packageName?.toString() != IG || !list.refresh()) {
            clearFeedCovers()
            return
        }
        updateFeedCovers(list, feedClipBottom)
    }

    /**
     * Hides ads and suggested posts in the home feed behind covers that follow the list.
     * A post spans several rows, so rows are grouped from one post header (or section
     * heading) to the next, and a whole group is hidden when its header carries
     * "Sponsored", "Suggested" or a Follow button. Everything after "You're all caught up"
     * is suggested. Markers are found with one text search per label rather than a tree
     * walk, and only when new rows appear, so this is cheap enough to rerun while
     * scrolling. Covers pass touches through
     * so the feed still scrolls under them.
     */
    private fun updateFeedCovers(list: AccessibilityNodeInfo, clipBottom: Int) {
        lastFeedScanAt = SystemClock.uptimeMillis()
        val hideAds = state.hideFeedAds
        val hideSuggested = state.hideSuggestedPosts
        val listRect = Rect().also { list.getBoundsInScreen(it) }
        if (clipBottom > 0 && clipBottom < listRect.bottom) listRect.bottom = clipBottom

        val rows = ArrayList<FeedRow>()
        for (i in 0 until minOf(list.childCount, MAX_FEED_ROWS)) {
            val node = list.getChild(i) ?: continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.intersect(listRect)) rows.add(FeedRow(node, r))
        }
        rows.sortBy { it.rect.top }

        // Each text search walks Instagram's tree on its UI thread, so they only run when a
        // row has scrolled in since the last pass. A row that stayed attached keeps its
        // content, so its markers carry over and a plain scroll just moves the covers.
        val mode = (if (hideAds) 1 else 0) or (if (hideSuggested) 2 else 0)
        val fresh = mode != rowFlagsMode || rows.any { it.node !in rowFlags }
        if (fresh) {
            for (id in rules.feedHeaderViewIds) {
                markRows(list.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id), rows, listRect, ROW_HEADER)
            }
            markLabels(list, rows, listRect, rules.feedHeaderDescs, ROW_HEADER, contains = false)
            if (hideAds) markLabels(list, rows, listRect, rules.sponsoredLabels, ROW_AD, contains = false)
            if (hideSuggested) {
                markLabels(list, rows, listRect, rules.suggestedLabels, ROW_SUGGESTED, contains = false)
                markLabels(list, rows, listRect, rules.followLabels, ROW_SUGGESTED, contains = false)
                markLabels(list, rows, listRect, rules.caughtUpLabels, ROW_CAUGHT_UP, contains = true)
            }
        } else {
            for (row in rows) row.flags = rowFlags[row.node] ?: 0
        }

        // Rows above the first header belong to a post whose header is off screen: hidden
        // if they were last pass, or if an ad's call-to-action bar is among them.
        val firstStart = rows.indexOfFirst { it.flags and ROW_STARTS != 0 }
        val orphanEnd = if (firstStart < 0) rows.size else firstStart
        var groupReason = (0 until orphanEnd).firstNotNullOfOrNull { hiddenRows[rows[it].node] } ?: 0
        if (fresh && groupReason == 0 && hideAds && orphanEnd > 0) {
            markLabels(list, rows, listRect, rules.adCtaLabels, ROW_CTA, contains = false)
        }
        if (fresh) {
            rowFlags = rows.associateTo(HashMap()) { it.node to it.flags }
            rowFlagsMode = mode
        }

        val reasons = IntArray(rows.size)
        var start = 0
        var caughtUp = false
        for (i in rows.indices) {
            val f = rows[i].flags
            if (caughtUp) {
                reasons[i] = ROW_SUGGESTED
                continue
            }
            if (f and ROW_CAUGHT_UP != 0) {
                // The "caught up" row itself stays visible as the end of the friends feed.
                reasons.fill(groupReason, start, i)
                caughtUp = true
                continue
            }
            if (f and ROW_STARTS != 0) {
                reasons.fill(groupReason, start, i)
                start = i
                groupReason = 0
            }
            // An ad button only identifies rows whose header is off screen: a friend's post
            // can carry "Learn more" or "Shop now" too.
            if (f and ROW_AD != 0 || (f and ROW_CTA != 0 && i < orphanEnd)) {
                groupReason = ROW_AD
            } else if (f and ROW_SUGGESTED != 0 && groupReason == 0) {
                groupReason = ROW_SUGGESTED
            }
        }
        if (!caughtUp) reasons.fill(groupReason, start, rows.size)

        val nowHidden = HashMap<AccessibilityNodeInfo, Int>()
        var shown = 0
        var i = 0
        while (i < rows.size) {
            if (reasons[i] == 0) {
                i++
                continue
            }
            val rect = Rect(rows[i].rect)
            var reason = reasons[i]
            var j = i
            while (j < rows.size && reasons[j] != 0) {
                rect.union(rows[j].rect)
                if (reasons[j] == ROW_AD) reason = ROW_AD
                nowHidden[rows[j].node] = reasons[j]
                j++
            }
            rect.left = listRect.left
            rect.right = listRect.right
            if (shown < MAX_FEED_COVERS && rect.height() >= MIN_FEED_COVER_PX) {
                val label = getString(if (reason == ROW_AD) R.string.feed_cover_ad else R.string.feed_cover_suggested)
                covers.show(COVER_FEED + shown, rect, label, touchable = false)
                shown++
            }
            i = j
        }
        for (k in shown until MAX_FEED_COVERS) covers.hide(COVER_FEED + k)
        hiddenRows = nowHidden
        if (shown > 0) ensureWatchdog()

        if (state.debugMode && lastFeedScanAt - lastFeedLogAt > 1000) {
            lastFeedLogAt = lastFeedScanAt
            val summary = rows.indices.joinToString(" ") { rowCode(rows[it].flags) + if (reasons[it] != 0) "*" else "" }
            Log.d(TAG, "Feed rows (H=header A=ad S=suggested C=caught-up T=ad button, *=hidden): $summary")
        }
    }

    private fun rowCode(flags: Int): String = buildString {
        if (flags and ROW_HEADER != 0) append('H')
        if (flags and ROW_AD != 0) append('A')
        if (flags and ROW_SUGGESTED != 0) append('S')
        if (flags and ROW_CAUGHT_UP != 0) append('C')
        if (flags and ROW_CTA != 0) append('T')
        if (isEmpty()) append('.')
    }

    private fun markLabels(
        list: AccessibilityNodeInfo,
        rows: List<FeedRow>,
        listRect: Rect,
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
            markRows(hits, rows, listRect, flag)
        }
    }

    /** Flags the row each visible node sits in. */
    private fun markRows(nodes: List<AccessibilityNodeInfo>, rows: List<FeedRow>, listRect: Rect, flag: Int) {
        val b = Rect()
        for (n in nodes) {
            if (!n.isVisibleToUser) continue
            n.getBoundsInScreen(b)
            if (!b.intersect(listRect)) continue
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
        val isAd = rules.sponsoredLabels.any { label ->
            viewer.findAccessibilityNodeInfosByText(label).any { n ->
                n.isVisibleToUser &&
                    listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                        .any { DetectionRules.labelMatches(it, label) } &&
                    b.also { n.getBoundsInScreen(it) }.let { screen.contains(it) && it.top < topZone }
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
private const val COVER_FEED = "feed"
private const val COVER_STORY_AD = "storyAd"
