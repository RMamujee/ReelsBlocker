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

    /** viaTab = the Reels/Explore bottom tab itself is selected (escape by clicking Home); otherwise a viewer opened on top (escape with BACK). */
    private data class Detection(val target: Target, val viaTab: Boolean)

    private lateinit var state: BlockerState
    private lateinit var rules: DetectionRules

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var lastDetectAt = 0L
    private var lastDebugLogAt = 0L
    private val recentBlocks = ArrayDeque<Long>()
    private var overlayView: View? = null

    // Screens that settle after their last event (e.g. the static Explore grid) would
    // otherwise slip past the throttle, so every event re-arms one trailing re-check.
    private val trailingCheck = Runnable { runDetection(deepSearch = true) }

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val IG = "com.instagram.android"
        // Long enough to outlast Instagram's exit animation on a slow frame, so one
        // escape never fires twice and pops an extra screen (or exits the app).
        private const val ACTION_COOLDOWN_MS = 1500L
        private const val DETECT_THROTTLE_MS = 250L
        private const val TRAILING_CHECK_MS = 350L
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

        handler.removeCallbacks(trailingCheck)
        handler.postDelayed(trailingCheck, TRAILING_CHECK_MS)

        if (SystemClock.uptimeMillis() - lastDetectAt < DETECT_THROTTLE_MS) return

        runDetection(deepSearch = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun runDetection(deepSearch: Boolean) {
        if (!::state.isInitialized || !state.isActive()) return
        lastDetectAt = SystemClock.uptimeMillis()

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return

        if (state.debugMode && deepSearch) debugLogIds(root)

        val wantReels = state.blockReels
        val wantExplore = state.blockExplore
        if (!wantReels && !wantExplore) return

        val detection = detect(root, wantReels, wantExplore, deepSearch) ?: return
        block(detection, root)
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
                if (viewId != null) {
                    if (wantReels && viewId in rules.reelsIdSet) idHit = Target.REELS
                    else if (wantExplore && viewId in rules.exploreIdSet) idHit = Target.EXPLORE
                }
            }

            if (node.isSelected) {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrBlank()) {
                    val reelsMatch = wantReels && rules.reelsTabDescs.any { descMatches(desc, it) }
                    val exploreMatch = !reelsMatch && wantExplore && rules.exploreTabDescs.any { descMatches(desc, it) }
                    // Tab-bar context check keeps a selected "Reels" grid tab on a
                    // profile page (or anything else labeled Reels) from matching.
                    if ((reelsMatch || exploreMatch) && inBottomTabBar(node)) {
                        return Detection(if (reelsMatch) Target.REELS else Target.EXPLORE, viaTab = true)
                    }
                }
            }

            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        idHit?.let { return Detection(it, viaTab = false) }

        // The walk is capped, so on (infrequent) screen transitions also do the full
        // remote search in case the viewer sits deeper than MAX_NODES.
        if (deepSearch) {
            if (wantReels) {
                for (id in rules.reelsViewIds)
                    if (root.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id).isNotEmpty())
                        return Detection(Target.REELS, viaTab = false)
            }
            if (wantExplore) {
                for (id in rules.exploreViewIds)
                    if (root.findAccessibilityNodeInfosByViewId(DetectionRules.IG_ID_PREFIX + id).isNotEmpty())
                        return Detection(Target.EXPLORE, viaTab = false)
            }
        }
        return null
    }

    /** Exact label or "Label,..." prefix — never a bare substring, so "Home decor" can't match "Home". */
    private fun descMatches(desc: String, candidate: String): Boolean =
        desc.equals(candidate, ignoreCase = true) ||
            desc.startsWith("$candidate,", ignoreCase = true)

    /** A real tab lives next to the Home tab; labels are sometimes nested one level down. */
    private fun inBottomTabBar(node: AccessibilityNodeInfo): Boolean =
        hasHomeChild(node.parent) || hasHomeChild(node.parent?.parent)

    private fun hasHomeChild(container: AccessibilityNodeInfo?): Boolean {
        if (container == null) return false
        val count = container.childCount
        if (count == 0 || count > 12) return false
        for (i in 0 until count) {
            val child = container.getChild(i) ?: continue
            if (isHomeDesc(child.contentDescription?.toString())) return true
            val cc = child.childCount
            if (cc in 1..4) {
                for (j in 0 until cc) {
                    val grandchild = child.getChild(j) ?: continue
                    if (isHomeDesc(grandchild.contentDescription?.toString())) return true
                }
            }
        }
        return false
    }

    private fun isHomeDesc(desc: String?): Boolean =
        desc != null && rules.homeTabDescs.any { descMatches(desc, it) }

    private fun block(detection: Detection, root: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return
        lastActionAt = now

        state.incrementToday()
        while (recentBlocks.isNotEmpty() && now - recentBlocks.first() > RAPID_WINDOW_MS) recentBlocks.removeFirst()
        recentBlocks.addLast(now)

        Log.i(TAG, "Blocked ${detection.target} (viaTab=${detection.viaTab}, today=${state.todayCount()})")

        if (recentBlocks.size >= RAPID_THRESHOLD) showOverlay(detection.target)

        if (detection.viaTab) {
            if (!clickHomeTab(root)) safeBack()
        } else {
            safeBack()
        }
    }

    /** BACK goes to whatever window holds focus when it lands — re-check it's still Instagram. */
    private fun safeBack() {
        if (rootInActiveWindow?.packageName?.toString() == IG) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun clickHomeTab(root: AccessibilityNodeInfo): Boolean {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (isHomeDesc(node.contentDescription?.toString())) {
                val clickable = if (node.isClickable) node else node.parent?.takeIf { it.isClickable }
                if (clickable != null) return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
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

    private fun debugLogIds(root: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDebugLogAt < 1000) return
        lastDebugLogAt = now
        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids.add(it.removePrefix(DetectionRules.IG_ID_PREFIX)) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        Log.d(TAG, "Screen IDs (${ids.size}): ${ids.take(60).joinToString(",")}")
    }
}
