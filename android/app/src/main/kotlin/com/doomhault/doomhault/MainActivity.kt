package com.doomhault.doomhault

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * The UI process. Counts and the limit live in the `:service` process and are read and written
 * through [StateProvider] (see AndroidManifest for why).
 */
class MainActivity : FlutterActivity() {

    private var stateObserver: ContentObserver? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "doomhault/control").setMethodCallHandler { call, result ->
            when (call.method) {
                "getState" -> result.success(
                    readState() + mapOf(
                        "serviceEnabled" to isServiceEnabled(),
                        "sdkInt" to Build.VERSION.SDK_INT,
                    )
                )
                "setLimit" -> {
                    call.argument<Int>("limit")?.let { limit ->
                        contentResolver.update(
                            StateProvider.uri(this),
                            ContentValues().apply { put(StateProvider.COL_LIMIT, limit) },
                            null,
                            null,
                        )
                    }
                    result.success(null)
                }
                "getViewIds" -> {
                    val ids = contentResolver.call(
                        StateProvider.uri(this), StateProvider.METHOD_VIEW_IDS, null, null
                    )
                    result.success(
                        ids?.let { b -> b.keySet().associateWith { b.getString(it) ?: "" } }
                            ?: emptyMap<String, String>()
                    )
                }
                "openAccessibilitySettings" -> {
                    startActivity(accessibilitySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(null)
                }
                // App info page, where the ⋮ menu has "Allow restricted settings" (Android 13+).
                "openAppInfo" -> {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Live counter: push a fresh snapshot whenever the service changes a count or the limit.
        EventChannel(messenger, "doomhault/state").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            events.success(readState())
                        }
                    }
                    stateObserver = observer
                    contentResolver.registerContentObserver(
                        StateProvider.uri(this@MainActivity), false, observer
                    )
                    events.success(readState())
                }

                override fun onCancel(arguments: Any?) {
                    stateObserver?.let(contentResolver::unregisterContentObserver)
                    stateObserver = null
                }
            }
        )
    }

    override fun onDestroy() {
        stateObserver?.let(contentResolver::unregisterContentObserver)
        stateObserver = null
        super.onDestroy()
    }

    /** Today's counts and the limit from [StateProvider]; zeros if it can't be reached. */
    private fun readState(): Map<String, Any> {
        val columns = listOf(
            StateProvider.COL_INSTAGRAM, StateProvider.COL_YOUTUBE,
            StateProvider.COL_LIMIT, StateProvider.COL_EXTRA,
        )
        contentResolver.query(StateProvider.uri(this), null, null, null, null)?.use { c ->
            if (c.moveToFirst()) return columns.associateWith { c.getInt(c.getColumnIndexOrThrow(it)) }
        }
        return emptyMap()
    }

    // Many Settings apps scroll to and highlight the entry named by this (unofficial) extra;
    // others ignore it and just show the Accessibility list.
    private fun accessibilitySettingsIntent(): Intent {
        val key = ComponentName(this, ShortsBlockerService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", key)
            })
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, ShortsBlockerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
