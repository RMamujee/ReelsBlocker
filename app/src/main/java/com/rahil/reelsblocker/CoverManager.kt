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
 * grid). Accessibility overlays sit above every app, so the caller must hide them the
 * moment Instagram leaves the foreground.
 */
class CoverManager(private val service: AccessibilityService) {

    private class Cover(val view: View, val rect: Rect)

    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val covers = HashMap<String, Cover>()

    val isShowing: Boolean get() = covers.isNotEmpty()

    fun has(key: String): Boolean = key in covers

    fun show(key: String, rect: Rect, label: String? = null) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            hide(key)
            return
        }
        val existing = covers[key]
        if (existing != null) {
            if (existing.rect == rect) return
            try {
                wm.updateViewLayout(existing.view, params(rect))
                covers[key] = Cover(existing.view, Rect(rect))
            } catch (e: Exception) {
                hide(key)
            }
            return
        }
        val view = buildView(label)
        try {
            wm.addView(view, params(rect))
            covers[key] = Cover(view, Rect(rect))
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

    private fun params(rect: Rect) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable on purpose: taps on a cover must not reach the icon or grid under it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
    private fun buildView(label: String?): View {
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
        view.isClickable = true
        return view
    }

    companion object {
        private const val TAG = "ReelsBlocker"
    }
}
