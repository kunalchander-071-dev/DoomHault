package com.doomhault.doomhault

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Full-screen "That's all for today" screen shown over Reels/Shorts once the daily limit is
 * used, instead of pressing Back mid-scroll. The user leaves on their own terms ("Leave Reels",
 * or the system Back button, which goes to the app underneath), or once a day can breathe for
 * a few seconds to watch [Store.EXTRA_REELS] more.
 *
 * It's an accessibility overlay that takes all touches, so the reel underneath can't be
 * scrolled. While it's up it requests transient audio focus, which makes Instagram/YouTube
 * pause or duck the video.
 *
 * Main thread only.
 */
class HaltScreen(
    private val service: AccessibilityService,
    private val store: Store,
    /** Called after "Breathe & watch 3 more" completes and the extra reels are granted. */
    private val onExtended: () -> Unit,
    /** Called after "Leave Reels"; the service re-checks and shows this again if still on Reels. */
    private val onLeft: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var focusRequest: AudioFocusRequest? = null
    private var breathing: ValueAnimator? = null

    private lateinit var face: ImageView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var reopen: TextView
    private lateinit var leave: TextView
    private lateinit var extend: TextView

    val isShowing: Boolean get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            reopen.text = reopenText()
            handler.postDelayed(this, 30_000)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    fun show() {
        if (root != null) {
            if (breathing == null) refresh()
            return
        }
        val view = build()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            return // service disconnected; see CounterBadge.create
        }
        root = view
        refresh()
        requestFocus()
        handler.post(tick)
    }

    fun hide() {
        breathing?.cancel()
        breathing = null
        handler.removeCallbacks(tick)
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
        abandonFocus()
    }

    private fun refresh() {
        val total = store.total()
        face.scaleX = 1f
        face.scaleY = 1f
        title.text = "That's all for today 🙏"
        body.text = "You watched $total ${if (total == 1) "reel" else "reels"} today."
        reopen.text = reopenText()
        reopen.visibility = View.VISIBLE
        leave.text = "Leave Reels"
        leave.visibility = View.VISIBLE
        extend.text = "Breathe & watch ${Store.EXTRA_REELS} more"
        extend.visibility = if (store.canExtend()) View.VISIBLE else View.GONE
    }

    private fun reopenText(): String {
        val now = Calendar.getInstance()
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val minutes = ((midnight.timeInMillis - now.timeInMillis) / 60_000).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        val left = if (h > 0) "${h}h ${m}m" else "${m}m"
        return "Reels & Shorts reopen in $left."
    }

    private fun leaveReels() {
        // Back leaves the Reels/Shorts player for the app's previous screen; the service then
        // sees the player gone and hides this screen. Hide now too, in case it doesn't.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        hide()
        onLeft()
    }

    /** ~12 s of slow breathing (in 4 s, out 4 s, ×1.5), then grant the extra reels. */
    private fun startBreathing() {
        leave.visibility = View.GONE
        extend.text = "Cancel"
        reopen.visibility = View.INVISIBLE
        body.text = "Watch ${Store.EXTRA_REELS} more after this breath."
        var inhale = true
        title.text = "Breathe in…"
        breathing = ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = BREATH_MS
            repeatCount = 2 // in, out, in
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                face.scaleX = s
                face.scaleY = s
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    inhale = !inhale
                    title.text = if (inhale) "Breathe in…" else "Breathe out…"
                }

                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    breathing = null
                    store.grantExtra()
                    hide()
                    onExtended()
                }
            })
            start()
        }
    }

    private fun cancelBreathing() {
        breathing?.cancel()
        breathing = null
        refresh()
    }

    private fun build(): View {
        val ctx: Context = service
        face = ImageView(ctx).apply {
            setImageResource(R.drawable.halt_face)
            contentDescription = "DoomHault sage, sleeping"
        }
        title = text(22f, CREAM, bold = true)
        body = text(16f, MUTED)
        reopen = text(16f, MUTED)
        leave = text(16f, DARK, bold = true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(28f)
                setColor(SAGE)
            }
            setPadding(dp(32f).toInt(), dp(14f).toInt(), dp(32f).toInt(), dp(14f).toInt())
            setOnClickListener { leaveReels() }
        }
        extend = text(14f, MUTED).apply {
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            setOnClickListener { if (breathing != null) cancelBreathing() else startBreathing() }
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val faceSize = dp(150f).toInt()
            addView(face, LinearLayout.LayoutParams(faceSize, faceSize).apply {
                bottomMargin = dp(28f).toInt()
            })
            addView(title, wrap(bottom = 12f))
            addView(body, wrap(bottom = 4f))
            addView(reopen, wrap(bottom = 36f))
            addView(leave, wrap(bottom = 8f))
            addView(extend, wrap())
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(BACKGROUND)
            // Swallow every touch so the reel underneath can't be scrolled or tapped.
            isClickable = true
            isFocusable = false
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { setMargins(dp(32f).toInt(), 0, dp(32f).toInt(), 0) })
        }
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.2f)
    }

    private fun wrap(bottom: Float = 0f) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(bottom).toInt() }

    private fun dp(v: Float) = CounterBadge.dp(service, v)

    // Transient focus makes the video app pause (or duck) while this screen is up.
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            runCatching { audio.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.abandonAudioFocus(focusListener) }
        }
    }

    private companion object {
        const val BREATH_MS = 4_000L
        // Dark palette from lib/main.dart (DoomColors.dark).
        val BACKGROUND = Color.argb(245, 0x16, 0x1B, 0x15)
        val CREAM = Color.rgb(0xEC, 0xE6, 0xD8)
        val MUTED = Color.rgb(0xA3, 0x9C, 0x8C)
        val SAGE = Color.rgb(0x9D, 0xB5, 0x8F)
        val DARK = Color.rgb(0x16, 0x1B, 0x15)
    }
}
