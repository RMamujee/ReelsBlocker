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

    /** viaTab = detected because the Reels/Explore bottom tab is selected (escape by clicking Home); otherwise a viewer opened on top (escape with BACK). */
    private data class Detection(val target: Target, val viaTab: Boolean)

    private lateinit var state: BlockerState
    private lateinit var rules: DetectionRules

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var lastDetectAt = 0L
    private var lastDebugLogAt = 0L
    private val recentBlocks = ArrayDeque<Long>()
    private var overlayView: View? = null

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val IG = "com.instagram.android"
        private const val ACTION_COOLDOWN_MS = 600L
        private const val DETECT_THROTTLE_MS = 250L
        private const val RAPID_WINDOW_MS = 10_000L
        private const val RAPID_THRESHOLD = 3
        private const val OVERLAY_MS = 2500L
        private const val MAX_NODES = 300

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
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::state.isInitialized) return
        if (event.packageName?.toString() != IG) return
        if (!state.isActive()) return

        val now = SystemClock.uptimeMillis()
        if (now - lastDetectAt < DETECT_THROTTLE_MS) return
        lastDetectAt = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return

        if (state.debugMode && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            debugLogIds(root)
        }

        val detection = detect(root) ?: return
        val enabled = when (detection.target) {
            Target.REELS -> state.blockReels
            Target.EXPLORE -> state.blockExplore
        }
        if (enabled) block(detection, root)
    }

    private fun detect(root: AccessibilityNodeInfo): Detection? {
        // Selected bottom tab first: from the Reels/Explore tab, BACK could bounce oddly, so
        // tab detections escape via a Home-tab click instead.
        detectSelectedTab(root)?.let { return Detection(it, viaTab = true) }

        for (id in rules.reelsViewIds) {
            if (root.findAccessibilityNodeInfosByViewId("$IG:id/$id").isNotEmpty())
                return Detection(Target.REELS, viaTab = false)
        }
        for (id in rules.exploreViewIds) {
            if (root.findAccessibilityNodeInfosByViewId("$IG:id/$id").isNotEmpty())
                return Detection(Target.EXPLORE, viaTab = false)
        }
        return null
    }

    private fun detectSelectedTab(root: AccessibilityNodeInfo): Target? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isSelected) {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrBlank()) {
                    if (rules.reelsTabDescs.any { desc.contains(it, ignoreCase = true) }) return Target.REELS
                    if (rules.exploreTabDescs.any { desc.contains(it, ignoreCase = true) }) return Target.EXPLORE
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

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
            if (!clickHomeTab(root)) performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
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
            val desc = node.contentDescription?.toString()
            if (desc != null && desc.startsWith("Home", ignoreCase = true) && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
            node.viewIdResourceName?.let { ids.add(it.removePrefix("$IG:id/")) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        Log.d(TAG, "Screen IDs (${ids.size}): ${ids.take(60).joinToString(",")}")
    }
}
