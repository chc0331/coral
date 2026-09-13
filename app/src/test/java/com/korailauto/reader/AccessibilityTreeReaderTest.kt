package com.korailauto.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTreeReaderTest {
    @Test
    fun `finds an enabled parent button from its text child`() {
        val buttonBounds = ScreenBounds(540, 2200, 1032, 2360)
        val nodes = listOf(
            node(index = 0, bounds = buttonBounds, clickable = true),
            node(index = 1, parentIndex = 0, bounds = buttonBounds, text = "확인"),
        )

        assertEquals(buttonBounds, AccessibilityTreeReader.findEnabledClickTarget(nodes, setOf("확인")))
    }

    @Test
    fun `finds the unchecked checkbox to the left of its label`() {
        val checkBoxBounds = ScreenBounds(64, 1200, 112, 1248)
        val nodes = listOf(
            node(index = 0, bounds = checkBoxBounds, clickable = true, checkable = true),
            node(index = 1, bounds = ScreenBounds(144, 1190, 500, 1260), text = "개인정보 수집 및 이용 동의"),
        )

        val target = AccessibilityTreeReader.findCheckBoxTarget(nodes, "개인정보수집및이용동의")

        assertEquals(checkBoxBounds, target?.bounds)
        assertFalse(target?.checked ?: true)
    }

    @Test
    fun `recognizes a waitlist usage notice before the application dialog`() {
        val nodes = listOf(
            node(index = 0, bounds = ScreenBounds(48, 900, 1032, 1500), text = "이용 안내"),
            node(index = 1, bounds = ScreenBounds(540, 1360, 980, 1480), text = "확인", clickable = true),
        )

        assertTrue(AccessibilityTreeReader.hasWaitlistNotice(nodes))
        assertEquals(
            ScreenBounds(540, 1360, 980, 1480),
            AccessibilityTreeReader.findEnabledClickTarget(nodes, setOf("확인")),
        )
    }

    private fun node(
        index: Int,
        bounds: ScreenBounds,
        parentIndex: Int? = null,
        text: String = "",
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
    ) = AccessibleNodeSnapshot(
        index = index,
        parentIndex = parentIndex,
        depth = 0,
        className = "android.view.View",
        text = text,
        contentDescription = "",
        stateDescription = "",
        hintText = "",
        paneTitle = "",
        viewId = "",
        bounds = bounds,
        clickable = clickable,
        checkable = checkable,
        checked = checked,
        enabled = true,
        visible = true,
        scrollable = false,
    )
}
