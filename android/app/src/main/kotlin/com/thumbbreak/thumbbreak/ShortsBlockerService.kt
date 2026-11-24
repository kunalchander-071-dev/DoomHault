package com.thumbbreak.thumbbreak

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    @Volatile private var lastCheckAt = 0L
    private var lastBlockAt = 0L
    private var lastCaptureAt = 0L
    private var lastPageKey: List<String?>? = null
    private var pendingKey: List<String?>? = null
    private var pendingSince = 0L
    private val recentKeys = ArrayDeque<List<String?>>()

    // Main thread: receives events and owns the badge. Every node lookup is a blocking IPC into
    // Instagram/YouTube (slow while video plays), so detection runs on [worker]; events that
    // arrive while it's busy are coalesced into one pass instead of queuing on the main thread.
    private val handler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("thumbbreak-detect").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val detectScheduled = AtomicBoolean(false)
    @Volatile private var latestApp: TargetApp? = null

    // Walking the whole view tree is hundreds of IPC calls (slow on Instagram's large tree),
    // so diagnostics run on their own thread, rarely.
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    // Diagnostics: events received per app since the service started (scrolled, content, window).
    private val eventCounts = TargetApp.entries.associateWith { IntArray(3) }

    // The service only receives events from Instagram/YouTube (see accessibility_service_config),
    // so switching to another app produces no event. While the badge is up, poll the
    // foreground window (on the worker) and drop the badge once it's no longer a target app.
    private val foregroundCheck = object : Runnable {
        override fun run() {
            if (!inViewer) return
            val pkg = rootInActiveWindow?.packageName
            if (pkg != null && TargetApp.forPackage(pkg) == null && pkg != packageName) {
                Log.d(TAG, "left to $pkg")
                leaveViewer()
            } else {
                worker.postDelayed(this, FOREGROUND_CHECK_MS)
            }
        }
    }

    private val detectPass = Runnable {
        detectScheduled.set(false)
        val app = latestApp ?: return@Runnable
        val start = SystemClock.uptimeMillis()
        val result = try {
            detect(app, start)
        } catch (e: Exception) {
            "error ${e.javaClass.simpleName}"
        }
        Log.d(TAG, "${app.name} $result took=${SystemClock.uptimeMillis() - start}ms")
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
            ) worker.post { leaveViewer() }
            return
        }
        eventCounts.getValue(app).let { c ->
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> c[0]++
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> c[1]++
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> c[2]++
            }
        }
        latestApp = app
        // At most one pending pass, and at most one every CHECK_THROTTLE_MS.
        if (detectScheduled.compareAndSet(false, true)) {
            val wait = (lastCheckAt + CHECK_THROTTLE_MS - SystemClock.uptimeMillis())
                .coerceAtLeast(0)
            worker.postDelayed(detectPass, wait)
        }
    }

    /** One detection pass on the worker thread. Returns a short summary for the log. */
    private fun detect(app: TargetApp, now: Long): String {
        lastCheckAt = now
        val root = rootInActiveWindow ?: return "no-root"
        if (!isViewerVisible(root, app)) {
            captureViewIdsAsync(root, app, now)
            leaveViewer()
            return "viewer=no"
        }
        val justEntered = !inViewer
        inViewer = true
        if (justEntered) worker.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS)

        if (store.isLimitReached()) {
            block(now)
            return "viewer=yes limit"
        }

        // Count when a different short has settled on screen. Transient keys (sheets opening or
        // closing, mid-swipe) must persist for SETTLE_MS, and shorts seen recently — including
        // the current one after returning to the player — are never recounted.
        val page = visiblePageKey(root, app)
        val last = lastPageKey
        var state: String
        if (page == null || (last != null && sameShort(page, last))) {
            // Keep the fuller key once a late-loading caption appears.
            if (page != null && page.count { it != null } > last!!.count { it != null }) {
                lastPageKey = page
            }
            pendingKey = null
            state = if (page == null) "key=none" else "same"
        } else if (pendingKey?.let { sameShort(page, it) } != true) {
            pendingKey = page
            pendingSince = now
            state = "new-pending"
        } else if (now - pendingSince >= SETTLE_MS) {
            lastPageKey = page
            pendingKey = null
            if (recentKeys.none { sameShort(it, page) }) {
                recentKeys.addLast(page)
                if (recentKeys.size > RECENT_KEYS) recentKeys.removeFirst()
                store.increment(app)
                state = "COUNTED"
            } else {
                state = "recent"
            }
        } else {
            state = "settling"
        }
        val total = store.total()
        val limit = store.limit
        handler.post { badge.show(total, limit) }
        if (store.isLimitReached()) block(now)
        // Keys are logged as a hash only — never caption or title text.
        return "viewer=yes $state key=#${page?.hashCode()?.toString(16)} total=$total"
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

    /** Worker thread only. */
    private fun leaveViewer() {
        inViewer = false
        worker.removeCallbacks(foregroundCheck)
        handler.post { badge.hide() }
    }

    /** Worker thread only. */
    private fun block(now: Long) {
        // One Back per block window, so we don't back out of the whole app.
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return
        lastBlockAt = now
        leaveViewer()
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.post {
            Toast.makeText(this, "Daily limit reached — take a break", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        captureExecutor.shutdownNow()
        badge.hide()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThumbBreak"
        private const val CHECK_THROTTLE_MS = 300L
        private const val BLOCK_COOLDOWN_MS = 1500L
        private const val CAPTURE_INTERVAL_MS = 30_000L
        private const val MAX_CAPTURE_NODES = 600
        private const val FOREGROUND_CHECK_MS = 1000L
        private const val SETTLE_MS = 600L
        private const val RECENT_KEYS = 5
        private const val MAX_KEY_NODES = 40
        private const val KEY_LABEL_CHARS = 40
        private const val INFO_AREA_LEFT = 0.3f
    }
}
