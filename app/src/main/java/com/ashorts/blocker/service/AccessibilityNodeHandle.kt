package com.ashorts.blocker.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ashorts.blocker.engine.IntBounds
import com.ashorts.blocker.engine.NodeHandle

/**
 * Wraps an [AccessibilityNodeInfo] into the engine's [NodeHandle]. Children and parents are
 * fetched lazily (each fetch is a binder call into the target app), and bounds are only read
 * on first use.
 */
class AccessibilityNodeHandle(
    private val node: AccessibilityNodeInfo,
    override val depth: Int
) : NodeHandle {

    override val viewId: String? get() = node.viewIdResourceName
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val isClickable: Boolean get() = node.isClickable
    override val childCount: Int get() = node.childCount

    override val bounds: IntBounds by lazy {
        val r = Rect()
        node.getBoundsInScreen(r)
        IntBounds(r.left, r.top, r.right, r.bottom)
    }

    override fun childAt(index: Int): NodeHandle? =
        node.getChild(index)?.let { AccessibilityNodeHandle(it, depth + 1) }

    override fun parent(): NodeHandle? =
        node.parent?.let { AccessibilityNodeHandle(it, depth - 1) }
}
