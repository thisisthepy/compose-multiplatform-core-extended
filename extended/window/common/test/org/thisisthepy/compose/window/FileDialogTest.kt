package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals

class FileDialogTest {
    private fun request(
        filters: List<FileDialogFilter> = emptyList(),
        name: String = "",
        defaultFilter: Int = 0,
    ) = FileDialogRequest(FileDialogKind.Save, name = name, filters = filters, defaultFilter = defaultFilter)

    @Test
    fun fileDialogNormalizeDropsFiltersOverSixteen() {
        val filters = (0 until 20).map { FileDialogFilter("f$it", listOf("e$it")) }
        val result = normalizeFileDialogRequest(request(filters))
        assertEquals(16, result.filters.size)
        assertEquals("f15", result.filters.last().label)
    }

    @Test
    fun fileDialogNormalizeDropsExtensionsOverThirtyTwo() {
        val extensions = (0 until 40).map { "e$it" }
        val result = normalizeFileDialogRequest(request(listOf(FileDialogFilter("all", extensions))))
        assertEquals(extensions.take(32), result.filters[0].extensions)
    }

    @Test
    fun fileDialogNormalizeStripsDotsAndLowercases() {
        val filter = FileDialogFilter("images", listOf(".PNG", "*.Jpg", "gif", "..webp"))
        val result = normalizeFileDialogRequest(request(listOf(filter)))
        assertEquals(listOf("png", "jpg", "gif", "webp"), result.filters[0].extensions)
    }

    @Test
    fun fileDialogNormalizeKeepsOnlyTheLastPathSegmentOfName() {
        assertEquals("c.txt", normalizeFileDialogRequest(request(name = "/a/b/c.txt")).name)
        assertEquals("c.txt", normalizeFileDialogRequest(request(name = "a\\b\\c.txt")).name)
        assertEquals("c.txt", normalizeFileDialogRequest(request(name = "c.txt")).name)
        assertEquals("", normalizeFileDialogRequest(request(name = "dir/")).name)
    }

    @Test
    fun fileDialogNormalizeResetsOutOfRangeDefaultFilter() {
        val filters = listOf(FileDialogFilter("a", listOf("a")), FileDialogFilter("b", listOf("b")))
        assertEquals(0, normalizeFileDialogRequest(request(filters, defaultFilter = 2)).defaultFilter)
        assertEquals(0, normalizeFileDialogRequest(request(filters, defaultFilter = -1)).defaultFilter)
        assertEquals(1, normalizeFileDialogRequest(request(filters, defaultFilter = 1)).defaultFilter)
    }

    @Test
    fun fileDialogNormalizeResetsDefaultFilterCutOffByTheFilterLimit() {
        val filters = (0 until 20).map { FileDialogFilter("f$it", emptyList()) }
        assertEquals(0, normalizeFileDialogRequest(request(filters, defaultFilter = 18)).defaultFilter)
    }
}
