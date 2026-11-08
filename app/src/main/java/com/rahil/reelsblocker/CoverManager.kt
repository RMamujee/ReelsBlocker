package com.rahil.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Opaque patches drawn over parts of Instagram's screen (the Reels tab icon, the Explore
 * grid, ads and suggested posts in the feed, a story ad being skipped). Accessibility overlays sit above every app, so the caller must hide them the
 * moment Instagram leaves the foreground.
 */
class CoverManager(private val service: AccessibilityService) {

    private class Cover(val view: View, val rect: Rect, val label: String?, val touchable: Boolean)

    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val covers = HashMap<String, Cover>()

    val isShowing: Boolean get() = covers.isNotEmpty()

    fun has(key: String): Boolean = key in covers

    /**
     * [touchable] covers swallow taps (the Reels icon, the Explore grid). Feed and story
     * covers pass touches through so the feed still scrolls and gestures still land.
     */
    fun show(key: String, rect: Rect, label: String? = null, touchable: Boolean = true) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            hide(key)
            return
        }
        val existing = covers[key]
        if (existing != null && (existing.label != label || existing.touchable != touchable)) hide(key)
        val current = covers[key]
        if (current != null) {
            if (current.rect == rect) return
            try {
                wm.updateViewLayout(current.view, params(rect, touchable))
                covers[key] = Cover(current.view, Rect(rect), label, touchable)
            } catch (e: Exception) {
                hide(key)
            }
            return
        }
        val view = buildView(label, touchable)
        try {
            wm.addView(view, params(rect, touchable))
            covers[key] = Cover(view, Rect(rect), label, touchable)
        } catch (e: Exception) {
            Log.e(TAG, "Cover failed", e)
        }
    }

    fun hide(key: String) {
        covers.remove(key)?.let {
            try {
                wm.removeView(it.view)
            } catch (_: Exception) {
            }
        }
    }

    fun hideAll() {
        covers.keys.toList().forEach { hide(it) }
    }

    fun hideWithPrefix(prefix: String) {
        covers.keys.filter { it.startsWith(prefix) }.forEach { hide(it) }
    }

    private fun params(rect: Rect, touchable: Boolean) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.OPAQUE
    ).apply {
        // getBoundsInScreen coordinates are absolute, so position from the screen's
        // top-left, including the punch-hole cutout area.
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Instagram follows the system theme by default: pure black in dark mode, white in light. */
    private fun buildView(label: String?, touchable: Boolean): View {
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.BLACK else Color.WHITE
        val view = if (label == null) {
            View(service)
        } else {
            TextView(service).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(if (night) 0xFF8E8E8E.toInt() else 0xFF737373.toInt())
            }
        }
        view.setBackgroundColor(bg)
        view.isClickable = touchable
        return view
    }

    companion object {
        private const val TAG = "ReelsBlocker"
    }
}
