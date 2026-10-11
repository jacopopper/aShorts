package com.ashorts.blocker.engine

/**
 * Detection rules for Instagram (com.instagram.android).
 *
 * Instagram is a moving target: view ids change between releases and content descriptions are
 * localized. The primary signals here are locale-independent view-id substrings; the Reels tab
 * is additionally matched by its English description. When a new Instagram release breaks
 * detection, open the in-app detection log, find what the relevant nodes are called now, and
 * extend these lists.
 */
object InstagramRules {

    const val PACKAGE = "com.instagram.android"

    private val SCREEN_ID_SUBSTRINGS = listOf(
        "reel_viewer",
        "reel_pager",
        "reel_container",
        "reel_feed",
        "reels_feed",
        "reel_recycler",
        "clips_viewer",
        "clips_pager",
        "clips_feed",
        "clips_recycler"
    )

    private val TAB_ID_SUBSTRINGS = listOf(
        "reel_tab",
        "reels_tab",
        "tab_reel",
        "tab_reels",
        "reel_button",
        "reel_icon"
    )

    private val TAB_DESC_KEYWORDS = listOf("reels")

    fun rules(aggressiveTextMatch: Boolean): AppBlockRules = AppBlockRules(
        appPackage = PACKAGE,
        screenIdPatterns = SCREEN_ID_SUBSTRINGS.map { Regex(Regex.escape(it)) },
        // Aggressive mode: also treat any node described as "Reels" as the feed itself.
        // Useful when id-based detection stops matching, but can over-block (e.g. in DMs).
        screenDescKeywords = if (aggressiveTextMatch) listOf("reels") else emptyList(),
        tabIdPatterns = TAB_ID_SUBSTRINGS.map { Regex(Regex.escape(it)) },
        tabDescKeywords = TAB_DESC_KEYWORDS
    )
}
