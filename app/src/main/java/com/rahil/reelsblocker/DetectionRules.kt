package com.rahil.reelsblocker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Instagram renames its internal view IDs across updates, so detection rules live in JSON:
 * a copy in files/detection_rules.json overrides the one shipped in assets — update detection
 * without reinstalling the app.
 */
class DetectionRules(
    val reelsViewIds: List<String>,
    val reelsTabDescs: List<String>,
    val exploreViewIds: List<String>,
    val exploreTabDescs: List<String>
) {
    companion object {
        private const val TAG = "ReelsBlocker"
        private const val FILE_NAME = "detection_rules.json"

        fun load(context: Context): DetectionRules {
            val override = File(context.filesDir, FILE_NAME)
            val json = try {
                if (override.exists()) override.readText()
                else context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read detection rules, using built-in fallback", e)
                return fallback()
            }
            return try {
                parse(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse detection rules, using built-in fallback", e)
                fallback()
            }
        }

        private fun parse(json: String): DetectionRules {
            val root = JSONObject(json)
            fun list(section: String, key: String): List<String> {
                val arr = root.optJSONObject(section)?.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }
            return DetectionRules(
                reelsViewIds = list("reels", "viewIds"),
                reelsTabDescs = list("reels", "selectedTabDescs"),
                exploreViewIds = list("explore", "viewIds"),
                exploreTabDescs = list("explore", "selectedTabDescs")
            )
        }

        private fun fallback() = DetectionRules(
            reelsViewIds = listOf("clips_viewer_view_pager", "clips_video_container"),
            reelsTabDescs = listOf("Reels"),
            exploreViewIds = listOf("explore_grid"),
            exploreTabDescs = listOf("Search and explore", "Explore")
        )
    }
}
