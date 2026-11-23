package com.thumbbreak.thumbbreak

/**
 * Apps we watch, and the view IDs that only exist while their short-form player is on screen.
 * These IDs are internal to Instagram/YouTube and can change with app updates —
 * verify with `adb shell uiautomator dump` or Android Studio's Layout Inspector.
 *
 * [pageId] is the view wrapping one short (one per pager page). A short is identified by its
 * labels — [keyIds] (e.g. author + caption) or, when empty, the longest label in the page's
 * left-hand info area (the title) — and counted when that changes. View identity isn't enough:
 * Instagram rebuilds the page after comment/share sheets close. Scroll events aren't used:
 * YouTube's Shorts swipes don't reach the service as scroll events.
 */
enum class TargetApp(
    val packageName: String,
    val viewerIds: List<String>,
    val pageId: String,
    val keyIds: List<String> = emptyList(),
) {
    INSTAGRAM(
        "com.instagram.android",
        listOf("clips_viewer_view_pager", "clips_video_container"),
        pageId = "clips_media_component",
        keyIds = listOf("clips_author_username", "clips_caption_component"),
    ),
    YOUTUBE(
        "com.google.android.youtube",
        listOf(
            "reel_recycler",
            "reel_player_page_container",
            "reel_watch_player",
            "reel_player_underlay",
            "reel_time_bar",
        ),
        pageId = "reel_player_page_container",
    );

    companion object {
        fun forPackage(pkg: CharSequence?): TargetApp? =
            entries.firstOrNull { it.packageName == pkg?.toString() }
    }
}
