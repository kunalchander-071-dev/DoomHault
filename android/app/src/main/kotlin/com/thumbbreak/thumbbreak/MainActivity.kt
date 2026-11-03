package com.thumbbreak.thumbbreak

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private lateinit var store: Store

    // SharedPreferences holds listeners weakly, so keep a strong reference.
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        store = Store(this)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "thumbbreak/control").setMethodCallHandler { call, result ->
            when (call.method) {
                "getState" -> result.success(store.toMap() + ("serviceEnabled" to isServiceEnabled()))
                "setLimit" -> {
                    store.limit = call.argument<Int>("limit") ?: store.limit
                    result.success(null)
                }
                "getViewIds" -> result.success(store.viewIds())
                "openAccessibilitySettings" -> {
                    startActivity(accessibilitySettingsIntent())
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Live counter: push a fresh snapshot whenever the service writes a new count.
        EventChannel(messenger, "thumbbreak/state").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        runOnUiThread { events.success(store.toMap()) }
                    }
                    store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
                    events.success(store.toMap())
                }

                override fun onCancel(arguments: Any?) {
                    store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
                    prefsListener = null
                }
            }
        )
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
