package com.doomhault.doomhault

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min

/**
 * The live counter drawn over Reels/Shorts: the DoomHault sage's face inside a progress ring,
 * plus "7/20". Uses an accessibility overlay window, so it needs no "Display over other apps"
 * permission.
 *
 * Sits top-right by default, below the apps' top bar and above their like/comment/share column,
 * clear of the caption at the bottom. It can be dragged anywhere; on release it snaps to the
 * nearest side and the position is remembered. Only the badge itself takes touches — swipes
 * everywhere else go straight to the app underneath.
 */
class CounterBadge(private val service: AccessibilityService, private val store: Store) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: BadgeView? = null
    private var onLeft = false
    private var dragging = false

    fun show(count: Int, limit: Int) {
        val badge = view ?: create() ?: return
        badge.update(count, limit)
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
    private fun create(): BadgeView? {
        val badge = BadgeView(service)
        val (savedLeft, savedY) = store.badgePosition()
        onLeft = savedLeft
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = savedY ?: dp(DEFAULT_TOP_DP)
        }
        badge.setOnTouchListener(DragToMove(badge, p))
        // Keep the right edge in place when the text grows (e.g. 9 -> 10).
        badge.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ ->
            if (!dragging && r - l != or_ - ol) snap(badge, p, onLeft)
        }
        return try {
            windowManager.addView(badge, p)
            view = badge
            // Place on its side once its width is known.
            badge.post { snap(badge, p, toLeft = onLeft) }
            badge
        } catch (e: WindowManager.BadTokenException) {
            null
        }
    }

    private fun screen(): Pair<Int, Int> =
        service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun snap(badge: View, p: WindowManager.LayoutParams, toLeft: Boolean) {
        val (w, h) = screen()
        p.x = if (toLeft) dp(EDGE_DP) else w - badge.width - dp(EDGE_DP)
        p.y = p.y.coerceIn(dp(MIN_TOP_DP), (h - badge.height - dp(MIN_BOTTOM_DP)).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(badge, p) }
    }

    /** Drag to move; a touch that doesn't move is ignored (it doesn't reach the app either). */
    private inner class DragToMove(
        private val badge: View,
        private val p: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(badge, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    onLeft = p.x + badge.width / 2 < screen().first / 2
                    snap(badge, p, onLeft)
                    store.saveBadgePosition(onLeft, p.y)
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = dp(service, value.toFloat()).toInt()

    companion object {
        private const val DEFAULT_TOP_DP = 108 // below the Reels/Shorts top bar
        private const val MIN_TOP_DP = 32 // keep clear of the status bar
        private const val MIN_BOTTOM_DP = 96 // keep clear of the apps' bottom navigation
        private const val EDGE_DP = 10

        fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )
    }
}

/** Mood for a share of the daily limit: smile under 50%, neutral to 80%, sleepy from 80%. */
enum class Mood(val drawable: Int, val ringColor: Int) {
    HAPPY(R.drawable.mood_happy, Color.rgb(0x9D, 0xB5, 0x8F)), // sage
    NEUTRAL(R.drawable.mood_neutral, Color.rgb(0xE3, 0x9A, 0x52)), // saffron
    SLEEPY(R.drawable.mood_sleepy, Color.rgb(0xE5, 0x78, 0x6C)); // soft red

    companion object {
        fun of(count: Int, limit: Int): Mood {
            val used = if (limit <= 0) 1.0 else count.toDouble() / limit
            return when {
                used < 0.5 -> HAPPY
                used < 0.8 -> NEUTRAL
                else -> SLEEPY
            }
        }
    }
}

/**
 * Pill: [face in a progress ring] 7/20. Drawn by hand — one small view, no layout inflation.
 * The pill is a dark translucent green so it stays readable over any video.
 */
private class BadgeView(context: Context) : View(context) {
    private fun dp(v: Float) = CounterBadge.dp(context, v)

    private val pillHeight = dp(44f)
    private val ringSize = dp(36f)
    private val ringWidth = dp(3f)
    private val pad = dp(4f)
    private val gap = dp(8f)
    private val endPad = dp(12f)

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 0x16, 0x1B, 0x15) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(60, 0xEC, 0xE6, 0xD8)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.argb(70, 0xEC, 0xE6, 0xD8)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xEC, 0xE6, 0xD8)
        textSize = dp(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xA3, 0x9C, 0x8C)
        textSize = dp(13f)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val faces = mutableMapOf<Mood, Bitmap>()
    private var count = 0
    private var limit = 1
    private var mood = Mood.HAPPY

    private val pill = RectF()
    private val ring = RectF()
    private val faceDst = Rect()

    fun update(count: Int, limit: Int) {
        val widthChanged = "$count".length != "${this.count}".length ||
            "$limit".length != "${this.limit}".length
        this.count = count
        this.limit = limit
        mood = Mood.of(count, limit)
        contentDescription = "DoomHault: $count of $limit reels and shorts today"
        if (widthChanged) requestLayout()
        invalidate()
    }

    private fun countText() = "$count"
    private fun limitText() = "/$limit"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = countPaint.measureText(countText()) + limitPaint.measureText(limitText())
        val w = pad + ringSize + gap + text + endPad
        setMeasuredDimension(w.toInt() + 1, pillHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = pillHeight / 2
        pill.set(0f, 0f, width.toFloat(), pillHeight)
        canvas.drawRoundRect(pill, r, r, pillPaint)
        pill.inset(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2)
        canvas.drawRoundRect(pill, r, r, borderPaint)

        // Face inside the ring.
        val cx = pad + ringSize / 2
        val cy = pillHeight / 2
        val half = ringSize / 2 - ringWidth / 2
        ring.set(cx - half, cy - half, cx + half, cy + half)
        val faceHalf = (ringSize / 2 - ringWidth - dp(1.5f)).toInt()
        faceDst.set((cx - faceHalf).toInt(), (cy - faceHalf).toInt(), (cx + faceHalf).toInt(), (cy + faceHalf).toInt())
        face(mood)?.let { canvas.drawBitmap(it, null, faceDst, bitmapPaint) }
        canvas.drawArc(ring, 0f, 360f, false, trackPaint)
        ringPaint.color = mood.ringColor
        val sweep = 360f * min(1f, count.toFloat() / limit.coerceAtLeast(1))
        if (sweep > 0f) canvas.drawArc(ring, -90f, sweep, false, ringPaint)

        // "7" bold + "/20" muted, vertically centred.
        val x = pad + ringSize + gap
        val baseline = cy - (countPaint.descent() + countPaint.ascent()) / 2
        canvas.drawText(countText(), x, baseline, countPaint)
        canvas.drawText(limitText(), x + countPaint.measureText(countText()), baseline, limitPaint)
    }

    private fun face(m: Mood): Bitmap? = faces[m]
        ?: runCatching { BitmapFactory.decodeResource(resources, m.drawable) }.getOrNull()
            ?.also { faces[m] = it }
}
