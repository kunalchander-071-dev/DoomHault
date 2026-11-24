package com.thumbbreak.thumbbreak

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Gives the UI process access to [Store], which lives in the `:service` process alongside
 * [ShortsBlockerService] (see AndroidManifest). SharedPreferences are cached per process and
 * never re-read, so both processes using them directly would see stale counts and limits.
 * Not exported: only ThumbBreak itself can use it.
 *
 * - query: one row with instagram, youtube, limit (today's counts; rolls over at midnight)
 * - update: ContentValues with "limit"
 * - call(METHOD_VIEW_IDS): diagnostics, view IDs per app as a Bundle
 * Observers on [uri] are notified whenever a count or the limit changes.
 */
class StateProvider : ContentProvider() {

    private val store by lazy { Store(context!!) }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val state = store.toMap()
        return MatrixCursor(COLUMNS).apply {
            addRow(COLUMNS.map { state[it] })
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val limit = values?.getAsInteger(COL_LIMIT) ?: return 0
        store.limit = limit
        return 1
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            METHOD_VIEW_IDS -> Bundle().apply {
                store.viewIds().forEach { (app, ids) -> putString(app, ids) }
            }
            else -> null
        }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val COL_INSTAGRAM = "instagram"
        const val COL_YOUTUBE = "youtube"
        const val COL_LIMIT = "limit"
        private val COLUMNS = arrayOf(COL_INSTAGRAM, COL_YOUTUBE, COL_LIMIT)
        const val METHOD_VIEW_IDS = "viewIds"

        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.state")
    }
}
