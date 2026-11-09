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
 * The loader is hardened against hostile/broken override files: size cap, nesting-depth cap,
 * list caps, view-id charset validation, and any per-list result that comes back empty falls
 * back to the built-in defaults so a typo can never silently disable blocking.
 */
class DetectionRules(
    val reelsViewIds: List<String>,
    val reelsTabDescs: List<String>,
    val exploreViewIds: List<String>,
    val exploreTabDescs: List<String>,
    val homeTabDescs: List<String>,
    /** Ids that only exist on a DM chat screen, used to allow reels opened from a chat. */
    val dmViewIds: List<String>,
    val dmViewIdPrefixes: List<String>,
    /** Full-screen surfaces (stories, calls, viewers) that may leave a hidden tab bar in the tree; no covers while one is showing. */
    val noCoverViewIdPrefixes: List<String>,
    /** The Explore search bar, which must never be covered. */
    val exploreSearchViewIds: List<String>,
    /** Home feed: the row that starts each post, by id or by its overflow-menu label. */
    val feedHeaderViewIds: List<String>,
    val feedHeaderDescs: List<String>,
    /** "Sponsored" under the author's name, in feed posts and story ads. */
    val sponsoredLabels: List<String>,
    /** Posts and carousels from accounts the user doesn't follow. */
    val suggestedLabels: List<String>,
    val followLabels: List<String>,
    /** "You're all caught up": everything after it is suggested. Matched anywhere in the text. */
    val caughtUpLabels: List<String>,
    /** An ad's call-to-action bar ("Learn more", "Shop now"). */
    val feedAdViewIds: List<String>,
    /** The title of the Following / Favorites feeds, which open without the tab bar. */
    val feedPageTitleViewIds: List<String>,
    val feedPageTitles: List<String>,
    /** The line under the author's name in a post header, where ads say "Ad". */
    val feedSubtitleViewIds: List<String>,
    /** The Follow button in a post header: only accounts the user doesn't follow have one. */
    val feedFollowViewIds: List<String>,
    /** The top bar and stories row, which float over the top of the feed list. */
    val feedTopViewIds: List<String>,
    /** The grid under the Explore tab. Generic ids, so only used while that tab is selected. */
    val exploreTabGridViewIds: List<String>,
    val storyViewIdPrefixes: List<String>,
    /** Views only a story ad has: the "Ad" pill and the ad's message box. */
    val storyAdViewIds: List<String>,
    /** Matched anywhere in a story header's text, e.g. "<account>'s sponsored story". */
    val storyAdPhrases: List<String>
) {
    /** Fully-qualified resource ids for O(1) membership checks during the tree walk. */
    val reelsIdSet: Set<String> = reelsViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    val exploreIdSet: Set<String> = exploreViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    private val dmIdSet: Set<String> = dmViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    private val dmFullPrefixes: List<String> = dmViewIdPrefixes.map { IG_ID_PREFIX + it }

    private val noCoverFullPrefixes: List<String> = noCoverViewIdPrefixes.map { IG_ID_PREFIX + it }
    val exploreSearchIdSet: Set<String> = exploreSearchViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    val feedPageTitleIdSet: Set<String> = feedPageTitleViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    val feedTopIdSet: Set<String> = feedTopViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    val exploreTabGridIdSet: Set<String> = exploreTabGridViewIds.map { IG_ID_PREFIX + it }.toHashSet()
    private val storyFullPrefixes: List<String> = storyViewIdPrefixes.map { IG_ID_PREFIX + it }

    fun isDmViewId(viewId: String): Boolean =
        viewId in dmIdSet || dmFullPrefixes.any { viewId.startsWith(it) }

    fun isNoCoverViewId(viewId: String): Boolean = noCoverFullPrefixes.any { viewId.startsWith(it) }

    fun isStoryViewId(viewId: String): Boolean = storyFullPrefixes.any { viewId.startsWith(it) }

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val FILE_NAME = "detection_rules.json"
        const val MAX_FILE_BYTES = 64L * 1024
        // The real schema nests 3 deep; org.json's parser is recursive, so deep nesting
        // must be rejected before parsing rather than caught as a StackOverflowError.
        private const val MAX_JSON_DEPTH = 16
        private const val MAX_LIST_ITEMS = 50
        private const val MAX_ITEM_LENGTH = 100
        const val IG_ID_PREFIX = "com.instagram.android:id/"
        // Labels shorter than this ("Ad") must match exactly: "Ad Astra" or "ad.vibes" isn't an ad.
        private const val MIN_PREFIX_LABEL = 3
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
            if (!depthOk(json)) {
                Log.e(TAG, "Detection rules nested too deeply, using built-in fallback")
                return fallback()
            }
            return try {
                parse(json)
            } catch (t: Throwable) {
                // Error type only: JSONException messages embed the whole input.
                Log.e(TAG, "Failed to parse detection rules (${t.javaClass.simpleName}), using built-in fallback")
                fallback()
            }
        }

        /** Returns a user-facing reason the file would be rejected or partly ignored, or null if it's clean. */
        fun checkImport(text: String): String? {
            if (text.isBlank()) return "File is empty"
            if (!depthOk(text)) return "File is nested too deeply"
            val root = try {
                JSONObject(text)
            } catch (t: Throwable) {
                return "Not valid JSON"
            }
            if (listOf("reels", "explore", "home", "feed", "stories", "dm", "noCover").none { root.has(it) }) {
                return "No reels/explore/home/feed/stories/dm/noCover sections found"
            }
            val parsed = parse(text)
            if (tabListsOverlap(parsed.homeTabDescs, parsed.reelsTabDescs, parsed.exploreTabDescs)) {
                return "Home labels overlap Reels/Explore labels"
            }
            return null
        }

        private fun depthOk(text: String): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                    // org.json's lenient tokenizer accepts comments and single-quoted
                    // strings, which would hide brackets from this scan; rules files never need them.
                    '\'', '/', '#' -> return false
                }
            }
            return true
        }

        /**
         * Exact label, or the label followed by badge text ("Reels, 1 new", "Reels 1 new",
         * "Reels. New").
         */
        fun labelMatches(desc: String, candidate: String): Boolean =
            desc.equals(candidate, ignoreCase = true) || candidate.length >= MIN_PREFIX_LABEL && (
                desc.startsWith("$candidate,", ignoreCase = true) ||
                desc.startsWith("$candidate ", ignoreCase = true) ||
                desc.startsWith("$candidate.", ignoreCase = true))

        /** Uses the same prefix matching as detection, in both directions, so e.g. home "Search" can't slip past "Search and explore". */
        private fun tabListsOverlap(home: List<String>, reels: List<String>, explore: List<String>): Boolean =
            (reels + explore).any { blocked ->
                home.any { h -> labelMatches(blocked, h) || labelMatches(h, blocked) }
            }

        private fun parse(json: String): DetectionRules {
            val root = JSONObject(json)
            val fb = fallback()
            fun list(section: String, key: String, idsOnly: Boolean, fallbackList: List<String>): List<String> {
                val arr = root.optJSONObject(section)?.optJSONArray(key) ?: return fallbackList
                val items = (0 until minOf(arr.length(), MAX_LIST_ITEMS))
                    .map { arr.optString(it, "").trim() }
                    .filter { it.isNotEmpty() && it.length <= MAX_ITEM_LENGTH }
                    .filter { !idsOnly || VIEW_ID_PATTERN.matches(it) }
                return items.ifEmpty { fallbackList }
            }
            var reelsTabs = list("reels", "selectedTabDescs", false, fb.reelsTabDescs)
            var exploreTabs = list("explore", "selectedTabDescs", false, fb.exploreTabDescs)
            var homeTabs = list("home", "tabDescs", false, fb.homeTabDescs)
            // A label in both lists would make every screen look like a blocked tab and
            // turn the Home click into an arbitrary-button clicker, so ignore all three.
            if (tabListsOverlap(homeTabs, reelsTabs, exploreTabs)) {
                Log.e(TAG, "Home tab labels overlap Reels/Explore labels, using built-in tab labels")
                reelsTabs = fb.reelsTabDescs
                exploreTabs = fb.exploreTabDescs
                homeTabs = fb.homeTabDescs
            }
            return DetectionRules(
                reelsViewIds = list("reels", "viewIds", true, fb.reelsViewIds),
                reelsTabDescs = reelsTabs,
                exploreViewIds = list("explore", "viewIds", true, fb.exploreViewIds),
                exploreTabDescs = exploreTabs,
                homeTabDescs = homeTabs,
                dmViewIds = list("dm", "viewIds", true, fb.dmViewIds),
                dmViewIdPrefixes = list("dm", "viewIdPrefixes", true, fb.dmViewIdPrefixes),
                noCoverViewIdPrefixes = list("noCover", "viewIdPrefixes", true, fb.noCoverViewIdPrefixes),
                exploreSearchViewIds = list("explore", "searchViewIds", true, fb.exploreSearchViewIds),
                feedHeaderViewIds = list("feed", "postHeaderViewIds", true, fb.feedHeaderViewIds),
                feedHeaderDescs = list("feed", "postHeaderDescs", false, fb.feedHeaderDescs),
                sponsoredLabels = list("feed", "sponsoredLabels", false, fb.sponsoredLabels),
                suggestedLabels = list("feed", "suggestedLabels", false, fb.suggestedLabels),
                followLabels = list("feed", "followLabels", false, fb.followLabels),
                caughtUpLabels = list("feed", "caughtUpLabels", false, fb.caughtUpLabels),
                feedAdViewIds = list("feed", "adViewIds", true, fb.feedAdViewIds),
                feedFollowViewIds = list("feed", "followViewIds", true, fb.feedFollowViewIds),
                feedSubtitleViewIds = list("feed", "subtitleViewIds", true, fb.feedSubtitleViewIds),
                feedPageTitleViewIds = list("feed", "pageTitleViewIds", true, fb.feedPageTitleViewIds),
                feedPageTitles = list("feed", "pageTitles", false, fb.feedPageTitles),
                feedTopViewIds = list("feed", "topBarViewIds", true, fb.feedTopViewIds),
                exploreTabGridViewIds = list("explore", "tabGridViewIds", true, fb.exploreTabGridViewIds),
                storyViewIdPrefixes = list("stories", "viewIdPrefixes", true, fb.storyViewIdPrefixes),
                storyAdViewIds = list("stories", "adViewIds", true, fb.storyAdViewIds),
                storyAdPhrases = list("stories", "adPhrases", false, fb.storyAdPhrases)
            )
        }

        private fun fallback() = DetectionRules(
            reelsViewIds = listOf("clips_viewer_view_pager", "clips_video_container"),
            reelsTabDescs = listOf("Reels"),
            exploreViewIds = listOf("explore_grid"),
            exploreTabDescs = listOf("Search and explore", "Explore", "Search"),
            homeTabDescs = listOf("Home"),
            dmViewIds = listOf("row_thread_composer_edittext", "row_thread_composer_container", "direct_thread_fragment_container"),
            dmViewIdPrefixes = listOf("row_thread_composer", "direct_thread"),
            noCoverViewIdPrefixes = listOf("reel_viewer", "story_viewer", "stories_viewer", "video_call", "rtc_call", "clips_viewer"),
            exploreSearchViewIds = listOf("action_bar_search_edit_text", "action_bar_search_hints_text_layout", "search_edit_text"),
            feedHeaderViewIds = listOf("row_feed_profile_header", "row_feed_photo_profile_header"),
            feedHeaderDescs = listOf("More options", "More actions"),
            sponsoredLabels = listOf("Sponsored", "Ad"),
            suggestedLabels = listOf("Suggested"),
            followLabels = listOf("Follow"),
            caughtUpLabels = listOf("all caught up", "completely caught up"),
            feedAdViewIds = listOf("cta_container"),
            feedFollowViewIds = listOf("inline_follow_button"),
            feedSubtitleViewIds = listOf("secondary_label"),
            feedPageTitleViewIds = listOf("action_bar_title"),
            feedPageTitles = listOf("Following", "Favorites"),
            feedTopViewIds = listOf("action_bar_LinearLayout", "main_feed_action_bar", "overlay_stories_tray_container", "action_bar_container"),
            exploreTabGridViewIds = listOf("recycler_view"),
            storyViewIdPrefixes = listOf("reel_viewer", "story_viewer", "stories_viewer"),
            storyAdViewIds = listOf("reel_item_sponsored_label_footer_pill", "sponsored_message_composer_container"),
            storyAdPhrases = listOf("sponsored story")
        )
    }
}
