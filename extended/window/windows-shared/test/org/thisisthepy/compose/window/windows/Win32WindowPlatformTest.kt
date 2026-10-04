package org.thisisthepy.compose.window.windows

import org.thisisthepy.compose.window.ContextMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals

class Win32WindowPlatformTest {
    @Test
    fun menuItemsPackOneLineEachWithTabsAndNewlinesRemovedFromLabels() {
        val packed = Win32WindowPlatform.packMenu(
            listOf(
                ContextMenuItem(1, "Copy"),
                ContextMenuItem(2, "Pa\tste\n", enabled = false, separatorAfter = true),
            ),
        )
        assertEquals("1\t1\t0\tCopy\n2\t0\t1\tPa ste ", packed)
    }
}
