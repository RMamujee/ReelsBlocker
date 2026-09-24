package com.rahil.reelsblocker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Instagram renames its internal view IDs across updates, so detection rules live in JSON:
 * a copy in files/detection_rules.json overrides the one shipped in assets — update detection
 * without reinstalling the app (use the in-app "Import rules file" button).
 *
 * The loader is hardened against hostile/broken override files: size cap, list caps,
 * view-id charset validation, and any per-list result that comes back empty falls back to
 * the built-in defaults so a typo can never silently disable blocking.
 */
class DetectionRules(
    val reelsViewIds: List<String>,
    val reelsTabDescs: List<String>,
    val exploreViewIds: List<String>,
    val exploreTabDescs: List<String>,
    val homeTabDescs: List<String>
) {
    /** Fully-qualified resource ids for O(1) membership checks during the tree walk. */
    val reelsIdSet: Set<String> = reelsViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    val exploreIdSet: Set<String> = exploreViewIds.map { IG_ID_PREFIX + it }.toHashSet()

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val FILE_NAME = "detection_rules.json"
        const val MAX_FILE_BYTES = 64L * 1024
        private const val MAX_LIST_ITEMS = 50
        private const val MAX_ITEM_LENGTH = 100
        const val IG_ID_PREFIX = "com.instagram.android:id/"
        private val VIEW_ID_PATTERN = Regex("[A-Za-z0-9_]+")

        fun load(context: Context): DetectionRules {
            val json = try {
                val override = File(context.filesDir, FILE_NAME)
                if (override.exists() && override.length() in 1..MAX_FILE_BYTES) {
                    override.readText()
                } else {
                    if (override.exists()) Log.e(TAG, "Override rules file empty or too large, using assets")
                    context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read detection rules, using built-in fallback", t)
                return fallback()
            }
            return try {
                parse(json)
            } catch (t: Throwable) {
                // Throwable, not Exception: pathological JSON can throw StackOverflowError/OOM
                Log.e(TAG, "Failed to parse detection rules, using built-in fallback", t)
                fallback()
            }
        }

        private fun parse(json: String): DetectionRules {
            val root = JSONObject(json)
            val fb = fallback()
            fun list(section: String, key: String, idsOnly: Boolean, fallbackList: List<String>): List<String> {
                val arr = root.optJSONObject(section)?.optJSONArray(key) ?: return fallbackList
                val items = (0 until minOf(arr.length(), MAX_LIST_ITEMS))
                    .map { arr.getString(it).trim() }
                    .filter { it.isNotEmpty() && it.length <= MAX_ITEM_LENGTH }
                    .filter { !idsOnly || VIEW_ID_PATTERN.matches(it) }
                return items.ifEmpty { fallbackList }
            }
            return DetectionRules(
                reelsViewIds = list("reels", "viewIds", true, fb.reelsViewIds),
                reelsTabDescs = list("reels", "selectedTabDescs", false, fb.reelsTabDescs),
                exploreViewIds = list("explore", "viewIds", true, fb.exploreViewIds),
                exploreTabDescs = list("explore", "selectedTabDescs", false, fb.exploreTabDescs),
                homeTabDescs = list("home", "tabDescs", false, fb.homeTabDescs)
            )
        }

        private fun fallback() = DetectionRules(
            reelsViewIds = listOf("clips_viewer_view_pager", "clips_video_container"),
            reelsTabDescs = listOf("Reels"),
            exploreViewIds = listOf("explore_grid"),
            exploreTabDescs = listOf("Search and explore", "Explore"),
            homeTabDescs = listOf("Home")
        )
    }
}
