package com.thumbbreak.thumbbreak

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches Instagram and YouTube. While the Reels/Shorts player is visible it counts each
 * swipe to a new item and shows a live counter badge; once the daily limit is hit it
 * presses Back to leave the player. The rest of each app (feed, DMs, search, long videos)
 * is untouched.
 */
class ShortsBlockerService : AccessibilityService() {

    private lateinit var store: Store
    private lateinit var badge: CounterBadge

    private var inViewer = false
    private var lastCheckAt = 0L
    private var lastBlockAt = 0L
    private var lastCaptureAt = 0L
    private var lastPageKey: List<String?>? = null
    private var pendingKey: List<String?>? = null
    private var pendingSince = 0L
    private val recentKeys = ArrayDeque<List<String?>>()

    private val handler = Handler(Looper.getMainLooper())

    // Walking the whole view tree is hundreds of IPC calls (slow on Instagram's large tree),
    // so diagnostics run off the main thread and never delay counting or the badge.
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    // Diagnostics: events received per app since the service started (scrolled, content, window).
    private val eventCounts = TargetApp.entries.associateWith { IntArray(3) }

    // The service only receives events from Instagram/YouTube (see accessibility_service_config),
    // so switching to another app produces no event. While the badge is up, poll the
    // foreground window and drop the badge once it's no longer a target app.
    private val foregroundCheck = object : Runnable {
        override fun run() {
            if (!inViewer) return
            val pkg = rootInActiveWindow?.packageName
            if (pkg != null && TargetApp.forPackage(pkg) == null && pkg != packageName) {
                leaveViewer()
            } else {
                handler.postDelayed(this, FOREGROUND_CHECK_MS)
            }
        }
    }

    override fun onServiceConnected() {
        store = Store(this)
        badge = CounterBadge(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = TargetApp.forPackage(event.packageName)
        if (app == null) {
            // Switched to another app (home screen, etc.) — drop the badge.
            // Our own badge/toast also fire events; ignore those.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.packageName != packageName
            ) leaveViewer()
            return
        }
        val now = SystemClock.uptimeMillis()
        eventCounts.getValue(app).let { c ->
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> c[0]++
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> c[1]++
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> c[2]++
            }
        }

        // Content-changed events fire constantly; don't walk the view tree for every one.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastCheckAt < CHECK_THROTTLE_MS
        ) return
        lastCheckAt = now

        val root = rootInActiveWindow ?: return
        if (!isViewerVisible(root, app)) {
            captureViewIdsAsync(root, app, now)
            leaveViewer()
            return
        }
        val justEntered = !inViewer
        inViewer = true
        if (justEntered) handler.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS)

        if (store.isLimitReached()) {
            block(now)
            return
        }

        // Count when a different short has settled on screen. Transient keys (sheets opening or
        // closing, mid-swipe) must persist for SETTLE_MS, and shorts seen recently — including
        // the current one after returning to the player — are never recounted.
        val page = visiblePageKey(root, app)
        val last = lastPageKey
        if (page == null || (last != null && sameShort(page, last))) {
            // Keep the fuller key once a late-loading caption appears.
            if (page != null && page.count { it != null } > last!!.count { it != null }) {
                lastPageKey = page
            }
            pendingKey = null
        } else if (pendingKey?.let { sameShort(page, it) } != true) {
            pendingKey = page
            pendingSince = now
        } else if (now - pendingSince >= SETTLE_MS) {
            lastPageKey = page
            pendingKey = null
            if (recentKeys.none { sameShort(it, page) }) {
                recentKeys.addLast(page)
                if (recentKeys.size > RECENT_KEYS) recentKeys.removeFirst()
                store.increment(app)
            }
        }
        badge.show(store.total(), store.limit)
        if (store.isLimitReached()) block(now) else captureViewIdsAsync(root, app, now)
    }

    /**
     * Identifies the short on screen by its labels (see [TargetApp]): the first part (author or
     * title) is required, later ones (caption) are optional since not every reel has one. Null
     * mid-swipe (two pages visible) or when the first part is missing, so nothing is counted
     * until the screen settles. Labels are only compared in memory, never stored.
     */
    private fun visiblePageKey(root: AccessibilityNodeInfo, app: TargetApp): List<String?>? {
        val page = root.findAccessibilityNodeInfosByViewId("${app.packageName}:id/${app.pageId}")
            .filter { it.isVisibleToUser }
            .singleOrNull() ?: return null
        val labels = if (app.keyIds.isNotEmpty()) {
            // On-screen by position, not isVisibleToUser: a caption covered by a comments or
            // share sheet must still yield the same key.
            val screen = Rect().also(root::getBoundsInScreen)
            val bounds = Rect()
            app.keyIds.map { id ->
                root.findAccessibilityNodeInfosByViewId("${app.packageName}:id/$id")
                    .firstOrNull { node ->
                        node.getBoundsInScreen(bounds)
                        bounds.top >= screen.top && bounds.bottom <= screen.bottom
                    }
                    ?.let(::firstLabel)
            }
        } else {
            listOf(longestInfoLabel(root, page))
        }
        if (labels.first() == null) return null
        return labels.map { it?.take(KEY_LABEL_CHARS) }
    }

    /** Same short if the first parts match and every part present in both matches. */
    private fun sameShort(a: List<String?>, b: List<String?>): Boolean =
        a.first() == b.first() &&
            a.indices.all { a[it] == null || b.getOrNull(it) == null || a[it] == b[it] }

    /** First non-empty text/description in [node]'s subtree. */
    private fun firstLabel(node: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_KEY_NODES) {
            val n = queue.removeFirst()
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    /**
     * Longest label starting at the page's left edge (title, else channel). Labels starting
     * further right are skipped: the Subscribe button (changes when tapped, same for every
     * short of a channel) and the like/comment/share column (counts change).
     */
    private fun longestInfoLabel(root: AccessibilityNodeInfo, page: AccessibilityNodeInfo): String? {
        val screen = Rect().also(root::getBoundsInScreen)
        val bounds = Rect()
        var best: String? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(page) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_KEY_NODES) {
            val n = queue.removeFirst()
            n.getBoundsInScreen(bounds)
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!label.isNullOrEmpty() && bounds.left < screen.width() * INFO_AREA_LEFT &&
                label.length > (best?.length ?: 0)
            ) best = label
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return best
    }

    private fun isViewerVisible(root: AccessibilityNodeInfo, app: TargetApp): Boolean =
        app.viewerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${app.packageName}:id/$id")
                .any { it.isVisibleToUser }
        }

    /**
     * Diagnostics: remember which view IDs were on the latest screen of each app, so the
     * correct Reels/Shorts IDs can be found from inside ThumbBreak (long-press the title).
     * Only IDs are stored — no on-screen text.
     */
    private fun captureViewIdsAsync(root: AccessibilityNodeInfo, app: TargetApp, now: Long) {
        if (now - lastCaptureAt < CAPTURE_INTERVAL_MS) return
        if (!capturing.compareAndSet(false, true)) return
        lastCaptureAt = now
        val c = eventCounts.getValue(app)
        val summary = "# events: scrolled=${c[0]} content=${c[1]} window=${c[2]}\n" +
            "# player detected: $inViewer"
        captureExecutor.execute {
            try {
                captureViewIds(root, app, summary)
            } catch (e: Exception) {
                // Window changed mid-walk; try again next interval.
            } finally {
                capturing.set(false)
            }
        }
    }

    private fun captureViewIds(root: AccessibilityNodeInfo, app: TargetApp, summary: String) {
        val ids = sortedSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_CAPTURE_NODES) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids.add(it.substringAfter(":id/")) }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        store.saveViewIds(app, summary + "\n" + ids.joinToString("\n"))
    }

    private fun leaveViewer() {
        inViewer = false
        handler.removeCallbacks(foregroundCheck)
        badge.hide()
    }

    private fun block(now: Long) {
        // One Back per block window, so we don't back out of the whole app.
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return
        lastBlockAt = now
        leaveViewer()
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Daily limit reached — take a break", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(foregroundCheck)
        captureExecutor.shutdownNow()
        badge.hide()
        super.onDestroy()
    }

    companion object {
        private const val CHECK_THROTTLE_MS = 300L
        private const val BLOCK_COOLDOWN_MS = 1500L
        private const val CAPTURE_INTERVAL_MS = 5000L
        private const val MAX_CAPTURE_NODES = 600
        private const val FOREGROUND_CHECK_MS = 1000L
        private const val SETTLE_MS = 600L
        private const val RECENT_KEYS = 5
        private const val MAX_KEY_NODES = 40
        private const val KEY_LABEL_CHARS = 40
        private const val INFO_AREA_LEFT = 0.3f
    }
}
