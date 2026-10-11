package com.ashorts.blocker.engine

/**
 * Detection rules for one target app. Patterns are matched as plain substrings of the fully
 * qualified view id (they are escaped when compiled). Keeping the rules as data makes it easy to
 * add YouTube Shorts / X later and to tune patterns when an app update renames its view ids.
 */
data class AppBlockRules(
    val appPackage: String,

    /** View-id substrings that identify the full-screen feed we want to block (e.g. Reels). */
    val screenIdPatterns: List<Regex>,

    /** Optional content-description keywords for screen detection; empty = id-based only. */
    val screenDescKeywords: List<String>,

    /** View-id substrings that identify the nav-bar tab that opens the feed. */
    val tabIdPatterns: List<Regex>,

    /** Content-description/text keywords that identify the nav-bar tab ("Reels"). */
    val tabDescKeywords: List<String>,

    val tabRegionTopFraction: Float = 0.72f,
    val tabMaxHeightFraction: Float = 0.14f,
    val tabMaxWidthFraction: Float = 0.30f,

    /** Traversal caps: keep scans fast so the service stays light on battery. */
    val maxNodes: Int = 450,
    val maxDepth: Int = 30,
    val maxChildrenPerNode: Int = 25
) {
    fun withScreenDesc(keywords: List<String>): AppBlockRules =
        copy(screenDescKeywords = keywords)
}
