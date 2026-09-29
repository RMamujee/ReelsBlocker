package com.rahil.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
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

class BlockerService : AccessibilityService() {

    private enum class Target { REELS, EXPLORE }

    /**
     * viaTab = the Reels/Explore bottom tab itself is selected, escaped by clicking Home
     * inside [tabBar]; otherwise a viewer opened on top, escaped with BACK.
     */
    private class Detection(val target: Target, val viaTab: Boolean, val tabBar: AccessibilityNodeInfo? = null)

    private lateinit var state: BlockerState
    private lateinit var rules: DetectionRules

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

    // Screens that settle after their last event (e.g. the static Explore grid) would
    // otherwise slip past the throttle, so every event re-arms one trailing re-check.
    private val trailingCheck = Runnable { runDetection(deepSearch = pendingDeep) }

    // Separate from trailingCheck so the per-event re-arming can't cancel it: a re-check
    // scheduled for the end of a cooldown really runs then.
    private val recheck = Runnable { runDetection(deepSearch = true) }

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val IG = "com.instagram.android"
        // Long enough to outlast Instagram's exit animation on a slow frame, so one
        // escape never fires twice and pops an extra screen (or exits the app).
        private const val ACTION_COOLDOWN_MS = 1500L
        private const val DETECT_THROTTLE_MS = 250L
        private const val TRAILING_CHECK_MS = 350L
        private const val RETRY_MS = 150L
        private const val MAX_RETRIES = 5
        private const val RAPID_WINDOW_MS = 10_000L
        private const val RAPID_THRESHOLD = 3
        private const val OVERLAY_MS = 2500L
        private const val MAX_NODES = 350

        var instance: BlockerService? = null
            private set
    }

    override fun onServiceConnected() {
        state = BlockerState(this)
        rules = DetectionRules.load(this)
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
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::state.isInitialized) return
        if (event.packageName?.toString() != IG) return
        if (!state.isActive()) return

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

    private fun runDetection(deepSearch: Boolean) {
        if (!::state.isInitialized || !state.isActive()) return
        lastDetectAt = SystemClock.uptimeMillis()

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return

        if (deepSearch) pendingDeep = false
        if (state.debugMode && deepSearch) debugLog(root)

        val wantReels = state.blockReels
        val wantExplore = state.blockExplore
        if (!wantReels && !wantExplore) return

        val detection = detect(root, wantReels, wantExplore, deepSearch) ?: return
        block(detection)
    }

    /**
     * One tree walk handles both signals: view-id membership (HashSet lookups against
     * viewIdResourceName, no remote per-id searches) and the selected bottom tab.
     * Only ENABLED targets can match, so e.g. with Explore blocking off, a selected
     * Explore tab never masks the Reels viewer sitting in the same tree.
     */
    private fun detect(
        root: AccessibilityNodeInfo,
        wantReels: Boolean,
        wantExplore: Boolean,
        deepSearch: Boolean
    ): Detection? {
        var idHit: Target? = null
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            if (idHit == null) {
                val viewId = node.viewIdResourceName
                // Preloaded or backgrounded pages stay in the tree; only what's on screen counts.
                if (viewId != null && node.isVisibleToUser) {
                    if (wantReels && viewId in rules.reelsIdSet) idHit = Target.REELS
                    else if (wantExplore && viewId in rules.exploreIdSet) idHit = Target.EXPLORE
                }
            }

            if (node.isSelected) {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrBlank()) {
                    val reelsMatch = wantReels && rules.reelsTabDescs.any { descMatches(desc, it) }
                    val exploreMatch = !reelsMatch && wantExplore && rules.exploreTabDescs.any { descMatches(desc, it) }
                    if (reelsMatch || exploreMatch) {
                        // Tab-bar context keeps a selected "Reels" grid tab on a profile
                        // page (or anything else labeled Reels) from matching.
                        val bar = tabBarOf(node)
                        if (bar != null) {
                            return Detection(if (reelsMatch) Target.REELS else Target.EXPLORE, viaTab = true, tabBar = bar)
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        idHit?.let { return Detection(it, viaTab = false) }

        // The walk is capped, so after a screen transition also do the full remote
        // search in case the viewer sits deeper than MAX_NODES.
        if (deepSearch) {
            if (wantReels && anyVisible(root, rules.reelsViewIds)) return Detection(Target.REELS, viaTab = false)
            if (wantExplore && anyVisible(root, rules.exploreViewIds)) return Detection(Target.EXPLORE, viaTab = false)
        }
        return null
    }

    private fun anyVisible(root: AccessibilityNodeInfo, ids: List<String>): Boolean =
        ids.any { id ->
            root.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id).any { it.isVisibleToUser }
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
