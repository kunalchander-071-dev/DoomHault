package com.doomhault.doomhault

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Small "7 / 20" pill drawn over Reels/Shorts. Uses an accessibility overlay window,
 * so it needs no "Display over other apps" permission. It ignores touches, so taps
 * and swipes go through to the app underneath.
 */
class CounterBadge(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show(count: Int, limit: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        val badge = view ?: create()?.also { view = it } ?: return
        badge.text = "$count / $limit"
        (badge.background as GradientDrawable).setColor(
            when {
                count >= limit -> LIMIT_COLOR
                limit - count <= WARN_REMAINING || count >= limit * NEAR_FRACTION -> NEAR_COLOR
                else -> CALM_COLOR
            }
        )
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        view = null
    }

    /**
     * Null if the overlay can't be added: its window token belongs to the service's connection,
     * which can drop between scheduling and drawing (service turned off or rebound, or UI
     * automation suppressing accessibility services). Adding then throws BadTokenException,
     * which would crash the app — and Android disables accessibility services that crash.
     */
    private fun create(): TextView? {
        val badge = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(96)
        }
        return try {
            windowManager.addView(badge, params)
            badge
        } catch (e: WindowManager.BadTokenException) {
            null
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics
    ).toInt()

    companion object {
        // Brand palette (see lib/main.dart): calm sage, saffron near the limit, red at it.
        // White text stays above 4.5:1 contrast on all three.
        private const val WARN_REMAINING = 2
        private const val NEAR_FRACTION = 0.75
        private val CALM_COLOR = Color.argb(230, 0x4E, 0x6A, 0x46)
        private val NEAR_COLOR = Color.argb(235, 0xA3, 0x56, 0x1B)
        private val LIMIT_COLOR = Color.argb(240, 0xB2, 0x3A, 0x30)
    }
}
