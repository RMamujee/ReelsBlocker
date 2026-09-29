package com.rahil.reelsblocker

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All settings + counters, backed by SharedPreferences. Service and UI share the same process, so values are always current. */
class BlockerState(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("blocker_state", Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = prefs.getBoolean("master", true)
        set(v) = prefs.edit().putBoolean("master", v).apply()

    var blockReels: Boolean
        get() = prefs.getBoolean("block_reels", true)
        set(v) = prefs.edit().putBoolean("block_reels", v).apply()

    var blockExplore: Boolean
        get() = prefs.getBoolean("block_explore", true)
        set(v) = prefs.edit().putBoolean("block_explore", v).apply()

    var hideReelsIcon: Boolean
        get() = prefs.getBoolean("hide_reels_icon", true)
        set(v) = prefs.edit().putBoolean("hide_reels_icon", v).apply()

    var allowDmReels: Boolean
        get() = prefs.getBoolean("allow_dm_reels", true)
        set(v) = prefs.edit().putBoolean("allow_dm_reels", v).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean("debug", false)
        set(v) = prefs.edit().putBoolean("debug", v).apply()

    var pauseUntil: Long
        get() = prefs.getLong("pause_until", 0L)
        set(v) = prefs.edit().putLong("pause_until", v).apply()

    fun isPaused(): Boolean = System.currentTimeMillis() < pauseUntil

    fun isActive(): Boolean = masterEnabled && !isPaused()

    private fun todayKey(): String =
        "count_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun todayCount(): Int = prefs.getInt(todayKey(), 0)

    fun incrementToday() {
        val key = todayKey()
        val current = prefs.getInt(key, 0)
        val editor = prefs.edit()
        if (current == 0) {
            // First block of a new day: drop previous days' counters so they don't pile up.
            prefs.all.keys.filter { it.startsWith("count_") && it != key }.forEach { editor.remove(it) }
        }
        editor.putInt(key, current + 1).apply()
    }
}
