package com.rahil.reelsblocker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * SilentFeed isn't in an app store, so it checks its own download page for a newer version
 * (at most every 12 hours) and offers to download it. The update is only ever downloaded
 * from that page: the version file can't point the app anywhere else. Android then installs
 * it over this version, which keeps your login, because it's signed with the same key.
 */
object UpdateChecker {
    private const val TAG = "ReelsBlocker"
    private const val PREFS = "updates"
    private const val LAST_CHECK = "last_check"
    private const val SKIPPED = "skipped_version"
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    private val worker = Executors.newSingleThreadExecutor()

    fun checkIfDue(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_CHECK, 0) < INTERVAL_MS) return
        prefs.edit().putLong(LAST_CHECK, now).apply()
        val site = Uri.parse(BuildConfig.SITE_URL)
        worker.execute {
            val latest = try {
                fetchLatest(site)
            } catch (e: Exception) {
                Log.i(TAG, "Update check failed: ${e.javaClass.simpleName}")
                null
            } ?: return@execute
            if (latest.versionCode <= BuildConfig.VERSION_CODE || latest.versionCode == prefs.getInt(SKIPPED, 0)) return@execute
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) offer(activity, latest)
            }
        }
    }

    private class Latest(val versionCode: Int, val versionName: String, val notes: String, val download: Uri)

    private fun fetchLatest(site: Uri): Latest? {
        val conn = URL(site.buildUpon().path("/version.json").build().toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText().take(16_000) }
            val json = JSONObject(text)
            // Only a download on the site itself, over https.
            val download = Uri.parse(site.toString()).buildUpon()
                .path(json.optString("apk", "/download/silentfeed.apk")).build()
            if (download.scheme != "https" || download.host != site.host) return null
            return Latest(json.getInt("versionCode"), json.optString("versionName").take(20), json.optString("notes").take(300), download)
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(activity: Activity, latest: Latest) {
        val message = buildString {
            append(activity.getString(R.string.update_message, latest.versionName))
            if (latest.notes.isNotBlank()) append("\n\n").append(latest.notes)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, latest.download).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No browser to download the update")
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip) { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(SKIPPED, latest.versionCode).apply()
            }
            .show()
    }
}
