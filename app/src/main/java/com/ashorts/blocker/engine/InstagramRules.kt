package com.ashorts.blocker.engine

/**
 * Detection rules for Instagram (com.instagram.android).
 *
 * IMPORTANT terminology (learned the hard way from device logs):
 * Instagram's internal namespace for the **Reels feed** is `clips_`
 * (confirmed: `clips_tab` for the tab, `clips_viewer_debug_container` for the feed).
 * The internal namespace for **stories** is `reel_` — the story viewer is ReelViewerFragment,
 * and story-viewer ids look like `reel_viewer_...`. Patterns with `reel_` must therefore NEVER
 * be treated as the Reels feed: doing so made aShorts press Back whenever a story opened,
 * which broke continuous story browsing. Only evidence-backed `clips_` patterns are used.
 *
 * Content descriptions are localized, so the English "Reels" tab keyword is a fallback signal;
 * when a new Instagram release breaks detection, the in-app detection log tells you what the
 * relevant nodes are called now — extend these lists from evidence only.
 */
object InstagramRules {

    const val PACKAGE = "com.instagram.android"

    /** Bottom-bar background, measured from a device screenshot (dark is not pure black). */
    const val BAR_COLOR_DARK = 0xFF0C0F14.toInt()
    const val BAR_COLOR_LIGHT = 0xFFFFFFFF.toInt()

    private val SCREEN_ID_SUBSTRINGS = listOf(
        "clips_viewer",   // confirmed on-device: clips_viewer_debug_container
        "clips_pager",
        "clips_feed",
        "clips_recycler"
    )

    private val TAB_ID_SUBSTRINGS = listOf(
        "clips_tab",      // confirmed on-device
        "tab_clips"
    )

    private val TAB_DESC_KEYWORDS = listOf("reels")

    /** Not yet confirmed on-device; the detection log prints the matched Home tab. */
    private val HOME_TAB_ID_SUBSTRINGS = listOf("feed_tab", "tab_home")
    private val HOME_TAB_DESC_KEYWORDS = listOf("home")

    fun rules(aggressiveTextMatch: Boolean): AppBlockRules = AppBlockRules(
        appPackage = PACKAGE,
        screenIdPatterns = SCREEN_ID_SUBSTRINGS.map { Regex(Regex.escape(it)) },
        // Aggressive mode: also treat any node described as "Reels" as the feed itself.
        // Use only when id-based detection stops matching; may over-block (e.g. in DMs).
        screenDescKeywords = if (aggressiveTextMatch) listOf("reels") else emptyList(),
        tabIdPatterns = TAB_ID_SUBSTRINGS.map { Regex(Regex.escape(it)) },
        tabDescKeywords = TAB_DESC_KEYWORDS,
        homeTabIdPatterns = HOME_TAB_ID_SUBSTRINGS.map { Regex(Regex.escape(it)) },
        homeTabDescKeywords = HOME_TAB_DESC_KEYWORDS
    )
}
