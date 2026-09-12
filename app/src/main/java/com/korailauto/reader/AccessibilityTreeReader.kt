package com.korailauto.reader

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

data class TreeReadResult(
    val packageName: String,
    val nodes: List<AccessibleNodeSnapshot>,
    val refreshTarget: ScreenBounds?,
    val itemBounds: List<ScreenBounds>,
    val waitlistItemBounds: List<ScreenBounds>,
)

data class CheckBoxTarget(
    val bounds: ScreenBounds,
    val checked: Boolean,
)

object AccessibilityTreeReader {
    private const val REFRESH_LABEL = "새로고침"

    fun read(root: AccessibilityNodeInfo): TreeReadResult {
        val nodes = mutableListOf<AccessibleNodeSnapshot>()
        val rootPackage = root.packageName?.toString().orEmpty()

        fun visit(node: AccessibilityNodeInfo, parentIndex: Int?, depth: Int) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val index = nodes.size
            nodes += AccessibleNodeSnapshot(
                index = index,
                parentIndex = parentIndex,
                depth = depth,
                className = node.className?.toString().orEmpty(),
                text = node.text?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                stateDescription = node.stateDescription?.toString().orEmpty(),
                hintText = node.hintText?.toString().orEmpty(),
                paneTitle = node.paneTitle?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                scrollable = node.isScrollable,
            )

            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                try {
                    visit(child, index, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, parentIndex = null, depth = 0)
        val refreshTarget = findRefreshTarget(nodes)
        val itemBounds = findTrainItemBounds(nodes)
        val waitlistItemBounds = findWaitlistItemBounds(nodes, itemBounds)
        return TreeReadResult(rootPackage, nodes, refreshTarget, itemBounds, waitlistItemBounds)
    }

    fun performClickAtBounds(root: AccessibilityNodeInfo, target: ScreenBounds): Boolean {
        var clicked = false

        fun visit(node: AccessibilityNodeInfo) {
            if (clicked) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom)
            if (
                bounds == target &&
                    (node.isClickable || node.isCheckable) &&
                    node.isEnabled &&
                    node.isVisibleToUser
            ) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                try {
                    visit(child)
                } finally {
                    child.recycle()
                }
                if (clicked) return
            }
        }

        visit(root)
        return clicked
    }

    fun findReservationActionTarget(
        nodes: List<AccessibleNodeSnapshot>,
        action: ReservationAction,
    ): ScreenBounds? {
        val labels = when (action) {
            ReservationAction.BOOK -> setOf("예매", "예매하기", "바로예매", "바로예매하기")
            ReservationAction.WAITLIST -> setOf("예약대기신청", "예약대기신청하기", "예약대기")
        }
        return findEnabledClickTarget(nodes, labels)
    }

    fun containsLabel(nodes: List<AccessibleNodeSnapshot>, label: String): Boolean =
        nodes.any { node ->
            node.visible && (compact(node.text).contains(compact(label)) || compact(node.contentDescription).contains(compact(label)))
        }

    fun findEnabledClickTarget(
        nodes: List<AccessibleNodeSnapshot>,
        labels: Set<String>,
    ): ScreenBounds? {
        val labelNode = nodes.firstOrNull { node ->
            node.visible && labels.any { label ->
                compact(node.text) == compact(label) || compact(node.contentDescription) == compact(label)
            }
        } ?: return null

        var current: AccessibleNodeSnapshot? = labelNode
        while (current != null) {
            if (current.clickable && current.enabled && current.visible) return current.bounds
            current = current.parentIndex?.let(nodes::get)
        }
        return null
    }

    fun findCheckBoxTarget(
        nodes: List<AccessibleNodeSnapshot>,
        label: String,
    ): CheckBoxTarget? {
        val labelNode = nodes.firstOrNull { node ->
            node.visible && (compact(node.text).contains(compact(label)) || compact(node.contentDescription).contains(compact(label)))
        } ?: return null

        var current: AccessibleNodeSnapshot? = labelNode
        while (current != null) {
            if (current.checkable && current.enabled && current.visible) {
                return CheckBoxTarget(current.bounds, current.checked)
            }
            current = current.parentIndex?.let(nodes::get)
        }

        val rowTolerance = max(80f, labelNode.bounds.height * 1.5f)
        return nodes.asSequence()
            .filter { node ->
                node.checkable &&
                    node.enabled &&
                    node.visible &&
                    node.bounds.centerX <= labelNode.bounds.centerX &&
                    abs(node.bounds.centerY - labelNode.bounds.centerY) <= rowTolerance
            }
            .minWithOrNull(
                compareBy<AccessibleNodeSnapshot> { labelNode.bounds.left - it.bounds.right }
                    .thenBy { abs(it.bounds.centerY - labelNode.bounds.centerY) },
            )
            ?.let { node -> CheckBoxTarget(node.bounds, node.checked) }
    }

    private fun findRefreshTarget(nodes: List<AccessibleNodeSnapshot>): ScreenBounds? {
        val labelNode = nodes.firstOrNull { node ->
            node.text.trim() == REFRESH_LABEL || node.contentDescription.trim() == REFRESH_LABEL
        } ?: return null

        var current: AccessibleNodeSnapshot? = labelNode
        while (current != null) {
            if (current.clickable && current.enabled && current.visible) return current.bounds
            current = current.parentIndex?.let(nodes::get)
        }
        return labelNode.bounds
    }

    private fun findTrainItemBounds(nodes: List<AccessibleNodeSnapshot>): List<ScreenBounds> {
        val screenWidth = nodes.maxOfOrNull { it.bounds.right } ?: return emptyList()
        return nodes.asSequence()
            .filter { node ->
                node.className == "android.widget.Button" &&
                    node.clickable &&
                    node.enabled &&
                    node.visible &&
                    node.text.isBlank() &&
                    node.contentDescription.isBlank() &&
                    node.bounds.width >= screenWidth * 0.7 &&
                    node.bounds.height >= 140
            }
            .map { it.bounds }
            .distinct()
            .sortedBy { it.top }
            .toList()
    }

    private fun findWaitlistItemBounds(
        nodes: List<AccessibleNodeSnapshot>,
        itemBounds: List<ScreenBounds>,
    ): List<ScreenBounds> =
        nodes.asSequence()
            .filter { node ->
                node.visible &&
                    (compact(node.text).contains("예약대기") || compact(node.contentDescription).contains("예약대기"))
            }
            .mapNotNull { node ->
                itemBounds.firstOrNull { bounds -> bounds.contains(node.bounds.centerX, node.bounds.centerY) }
            }
            .distinct()
            .sortedBy { it.top }
            .toList()

    private fun compact(value: String): String = value.filterNot(Char::isWhitespace)
}
