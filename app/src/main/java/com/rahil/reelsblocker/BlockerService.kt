package com.rahil.reelsblocker

import android.accessibilityservice.AccessibilityService
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
        val coverBlocked: Boolean
    )

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
            // Scrolls only matter while a DM-shared reel is on screen; everywhere else
            // they return here, before any other work.
            if (allowedViewer && state.isActive() && state.blockReels) onViewerScrolled(event)
            return
        }

        if (!state.isActive()) {
            covers.hideAll()
            resetDmState()
            return
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
        if (!wantReels && !wantExplore && !hideIcon) {
            covers.hideAll()
            return
        }

        val scan = scan(root, wantReels, wantExplore, hideIcon, deepSearch)
        val exploreCovered = updateCovers(scan, wantExplore, hideIcon, deepSearch)
        val now = SystemClock.uptimeMillis()

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
            if (wantExplore && scan.exploreVisible && !scan.searchActive && !scan.coverBlocked && !exploreCovered) {
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
    private fun updateCovers(scan: Scan, wantExplore: Boolean, hideIcon: Boolean, deepSearch: Boolean): Boolean {
        if (scan.coverBlocked) {
            covers.hideAll()
            if (state.debugMode && deepSearch) Log.d(TAG, "Covers: off (chat/story/call/viewer on screen)")
            return false
        }
        var exploreRect: Rect? = null
        if (wantExplore && scan.exploreVisible && !scan.searchActive) {
            exploreRect = scan.exploreGridRect?.let { Rect(it) } ?: fallbackGridRect(scan)
            if (exploreRect != null) {
                // Never over the search bar or the tab bar.
                if (scan.searchBarBottom > 0) exploreRect.top = maxOf(exploreRect.top, scan.searchBarBottom)
                if (scan.tabBarTop > 0) exploreRect.bottom = minOf(exploreRect.bottom, scan.tabBarTop)
                if (exploreRect.isEmpty) exploreRect = null
            }
        }
        if (exploreRect != null) {
            covers.show(COVER_EXPLORE, exploreRect, getString(R.string.explore_cover))
        } else {
            covers.hide(COVER_EXPLORE)
        }

        val reelsRect = if (hideIcon) scan.reelsTabRect else null
        if (reelsRect != null) covers.show(COVER_REELS_TAB, reelsRect) else covers.hide(COVER_REELS_TAB)

        if (state.debugMode && deepSearch) {
            Log.d(
                TAG,
                "Covers: explore=$exploreRect (grid=${scan.exploreGridRect}, search=${scan.searchActive}) " +
                    "reelsTab=$reelsRect"
            )
        }
        ensureWatchdog()
        return exploreRect != null && covers.has(COVER_EXPLORE)
    }

    /** Everything between the search bar and the tab bar, for when the grid's id isn't known. */
    private fun fallbackGridRect(scan: Scan): Rect? {
        val bar = scan.exploreTabBar ?: return null
        if (scan.tabBarTop <= 0) return null
        val barBounds = Rect().also { bar.getBoundsInScreen(it) }
        val top = if (scan.searchBarBottom > 0) {
            scan.searchBarBottom
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
        deepSearch: Boolean
    ): Scan {
        val checkDm = wantReels && state.allowDmReels
        val wantCovers = hideIcon || wantExplore
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
            }

            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                val isReelsLabel = rules.reelsTabDescs.any { descMatches(desc, it) }
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
                                    reelsTabRect = null, sawDm = false, coverBlocked = false
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
            coverBlocked = coverBlocked || (wantCovers && reelsViewer)
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

        if (recentBlocks.size >= RAPID_THRESHOLD) showOverlay(detection.target)

        // A delivered click or BACK can still be ignored by Instagram mid-animation;
        // verify once the cooldown ends.
        scheduleRecheck(ACTION_COOLDOWN_MS + 50)
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
