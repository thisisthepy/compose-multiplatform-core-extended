package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class Win32FileDropTest {

    @Test
    fun twoPathsComeOutAsTwoAndKeepTheirSpaces() {
        assertEquals(
            listOf("C:\\Users\\a b\\x.txt", "D:\\\u00fc.txt"),
            splitDroppedPaths("C:\\Users\\a b\\x.txt\u0000D:\\\u00fc.txt"),
        )
    }

    @Test
    fun noDropIsNoPaths() {
        assertEquals(emptyList(), splitDroppedPaths(""))
        assertEquals(listOf("C:\\a"), splitDroppedPaths("C:\\a\u0000"))
    }
}
