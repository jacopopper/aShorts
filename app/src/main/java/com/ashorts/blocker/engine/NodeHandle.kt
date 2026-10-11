package com.ashorts.blocker.engine

/**
 * A minimal, read-only view of a UI node in the target app.
 *
 * The accessibility service wraps [android.view.accessibility.AccessibilityNodeInfo] into this
 * interface so the detection engine is pure Kotlin and can be unit-tested on the JVM with fake
 * nodes. Wrappers fetch children and parents lazily, which keeps traversals cheap.
 */
interface NodeHandle {
    /** Fully-qualified resource id, e.g. "com.instagram.android:id/reel_viewer_root", or null. */
    val viewId: String?

    val text: CharSequence?
    val contentDescription: CharSequence?
    val isClickable: Boolean

    /** Bounding box in screen coordinates. */
    val bounds: IntBounds

    /** Depth of this node below the window root. */
    val depth: Int
    val childCount: Int
    fun childAt(index: Int): NodeHandle?
    fun parent(): NodeHandle?
}
