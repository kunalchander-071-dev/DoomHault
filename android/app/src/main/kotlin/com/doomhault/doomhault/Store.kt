package com.doomhault.doomhault

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today's counts + the daily limit, persisted in SharedPreferences. Resets at local midnight.
 * Only used in the `:service` process (the service and [StateProvider]); the UI goes through
 * [StateProvider], since SharedPreferences don't stay in sync across processes.
 */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tells the UI (observing [StateProvider]) that counts or the limit changed. */
    private fun notifyChanged() =
        appContext.contentResolver.notifyChange(StateProvider.uri(appContext), null)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun rollover() {
        val today = today()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_INSTAGRAM, 0)
                .putInt(KEY_YOUTUBE, 0)
                .apply()
            notifyChanged()
        }
    }

    var limit: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT)
        set(value) {
            prefs.edit().putInt(KEY_LIMIT, value).apply()
            notifyChanged()
        }

    fun total(): Int {
        rollover()
        return prefs.getInt(KEY_INSTAGRAM, 0) + prefs.getInt(KEY_YOUTUBE, 0)
    }

    fun isLimitReached(): Boolean = total() >= limit

    fun increment(app: TargetApp) {
        rollover()
        val key = if (app == TargetApp.INSTAGRAM) KEY_INSTAGRAM else KEY_YOUTUBE
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        notifyChanged()
    }

    // Diagnostics and recent-short hashes, kept apart from the counts.
    private val debugPrefs: SharedPreferences =
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)

    fun saveViewIds(app: TargetApp, ids: String) =
        debugPrefs.edit().putString(app.name, ids).apply()

    fun viewIds(): Map<String, String> =
        TargetApp.entries.associate { it.name to (debugPrefs.getString(it.name, null) ?: "") }

    /**
     * Recently counted shorts, as label hashes (no text), so a service restart doesn't recount
     * the one on screen.
     * Format: shorts separated by ";", parts by "," ("-" = missing part).
     */
    fun saveRecentShorts(keys: Collection<List<Int?>>) =
        debugPrefs.edit().putString(KEY_RECENT, keys.joinToString(";") { key ->
            key.joinToString(",") { it?.toString() ?: "-" }
        }).apply()

    /** Where the user dragged the counter badge: (on the left side, top offset px); null y = default. */
    fun badgePosition(): Pair<Boolean, Int?> =
        debugPrefs.getBoolean(KEY_BADGE_LEFT, false) to
            debugPrefs.getInt(KEY_BADGE_Y, -1).takeIf { it >= 0 }

    fun saveBadgePosition(onLeft: Boolean, y: Int) =
        debugPrefs.edit().putBoolean(KEY_BADGE_LEFT, onLeft).putInt(KEY_BADGE_Y, y).apply()

    fun recentShorts(): List<List<Int?>> =
        debugPrefs.getString(KEY_RECENT, null).orEmpty().split(';').filter { it.isNotEmpty() }
            .map { key -> key.split(',').map { it.toIntOrNull() } }

    fun toMap(): Map<String, Any> {
        rollover()
        return mapOf(
            "instagram" to prefs.getInt(KEY_INSTAGRAM, 0),
            "youtube" to prefs.getInt(KEY_YOUTUBE, 0),
            "limit" to limit,
        )
    }

    companion object {
        private const val PREFS = "doomhault"
        private const val DEBUG_PREFS = "doomhault_debug"
        private const val KEY_RECENT = "recent_shorts"
        private const val KEY_BADGE_LEFT = "badge_left"
        private const val KEY_BADGE_Y = "badge_y"
        private const val KEY_DATE = "date"
        private const val KEY_INSTAGRAM = "count_instagram"
        private const val KEY_YOUTUBE = "count_youtube"
        private const val KEY_LIMIT = "limit"
        private const val DEFAULT_LIMIT = 20
    }
}
