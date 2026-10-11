package com.ashorts.blocker.engine

/**
 * A minimal, read-only view of a UI node in the target app.
 *
 * The accessibility service wraps [android.view.accessibility.AccessibilityNodeInfo] into this
 * interface so the detection engine is pure Kotlin and can be unit-tested on the JVM with fake
 * nodes. Wrappers fetch children and parents lazily, which keeps traversals cheap.
 */
interface NodeHandle {
    /** Fully-qualified resource id, e.g. "com.instagram.android:id/clips_tab", or null. */
    val viewId: String?

    val text: CharSequence?
    val contentDescription: CharSequence?
    val isClickable: Boolean

    /** False when the node is in the tree but not actually shown to the user. */
    val isVisibleToUser: Boolean

    /** The active tab in a tab bar reports itself as selected (when the app exposes it). */
    val isSelected: Boolean get() = false

    /** Bounding box in screen coordinates. */
    val bounds: IntBounds

    /** Depth of this node below the window root. */
    val depth: Int
    val childCount: Int
    fun childAt(index: Int): NodeHandle?
    fun parent(): NodeHandle?

    /** Taps the node (or its nearest clickable ancestor). Returns false if that failed. */
    fun click(): Boolean = false
}
