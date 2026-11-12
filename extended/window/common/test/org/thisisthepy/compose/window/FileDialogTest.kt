package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun fileDialogNormalizeDropsAFilterWhoseExtensionsAllCleanToNothing() {
        val filters = listOf(
            FileDialogFilter("junk", listOf(".", "*.", " ")),
            FileDialogFilter("png", listOf("png")),
        )
        val result = normalizeFileDialogRequest(request(filters, defaultFilter = 1))
        assertEquals(listOf("png"), result.filters.map { it.label })
        assertEquals(0, result.defaultFilter)
    }

    @Test
    fun fileDialogNormalizeKeepsTheDefaultFilterOnTheSameFilterAfterADrop() {
        val filters = listOf(
            FileDialogFilter("junk", listOf(".")),
            FileDialogFilter("a", listOf("a")),
            FileDialogFilter("b", listOf("b")),
        )
        val result = normalizeFileDialogRequest(request(filters, defaultFilter = 2))
        assertEquals(listOf("a", "b"), result.filters.map { it.label })
        assertEquals(1, result.defaultFilter)
        assertEquals(0, normalizeFileDialogRequest(request(filters, defaultFilter = 0)).defaultFilter)
    }

    @Test
    fun fileDialogNormalizeKeepsAFilterGivenWithNoExtensionsAsAllFiles() {
        val result = normalizeFileDialogRequest(request(listOf(FileDialogFilter("all", emptyList()))))
        assertEquals(1, result.filters.size)
        assertTrue(result.filters[0].extensions.isEmpty())
    }

    // Lanes and outcome rules

    private class FakePresenter : SheetPresenter {
        val presented = ArrayList<FileDialogRequest>()
        var completion: ((FileDialogOutcome) -> Unit)? = null
        var dismissed = 0
        var released = 0
        override fun present(request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit) {
            presented.add(request)
            this.completion = completion
        }
        override fun dismiss() { dismissed++ }
        override fun release() { released++ }
    }

    private class Setup {
        var window: String? = "a"
        val presenters = HashMap<String, FakePresenter>()
        val lanes = FileDialogLanes<String>({ window }) { presenters.getOrPut(it) { FakePresenter() } }
    }

    private fun open(name: String) = FileDialogRequest(FileDialogKind.OpenFile, title = name)

    @Test
    fun fileDialogLanesRunOneWindowInOrder() {
        val s = Setup()
        val results = ArrayList<String>()
        s.lanes.show(open("1")) { results.add("1") }
        s.lanes.show(open("2")) { results.add("2") }
        val p = s.presenters.getValue("a")
        assertEquals(listOf("1"), p.presented.map { it.title })
        p.completion!!(FileDialogOutcome.Cancelled)
        assertEquals(listOf("1", "2"), p.presented.map { it.title })
        assertEquals(listOf("1"), results)
        p.completion!!(FileDialogOutcome.Cancelled)
        assertEquals(listOf("1", "2"), results)
    }

    @Test
    fun fileDialogLanesDoNotMakeAnotherWindowWait() {
        val s = Setup()
        s.lanes.show(open("1")) {}
        s.window = "b"
        s.lanes.show(open("2")) {}
        assertEquals(1, s.presenters.getValue("a").presented.size)
        assertEquals(1, s.presenters.getValue("b").presented.size)
    }

    @Test
    fun fileDialogLanesDeliverACompletionTwiceOnce() {
        val s = Setup()
        var count = 0
        s.lanes.show(open("1")) { count++ }
        val completion = s.presenters.getValue("a").completion!!
        completion(FileDialogOutcome.Cancelled)
        completion(FileDialogOutcome.Unavailable)
        assertEquals(1, count)
    }

    @Test
    fun fileDialogLanesReleaseThePresenterAfterCompletion() {
        val s = Setup()
        s.lanes.show(open("1")) {}
        val p = s.presenters.getValue("a")
        p.completion!!(FileDialogOutcome.Cancelled)
        assertTrue(p.released > 0)
    }

    @Test
    fun fileDialogCancelAllDeliversOneCancelled() {
        val s = Setup()
        val results = ArrayList<FileDialogOutcome>()
        s.lanes.show(open("1")) { results.add(it) }
        val p = s.presenters.getValue("a")
        val late = p.completion!!
        s.lanes.cancelAll()
        assertEquals(1, p.dismissed)
        assertEquals(1, results.size)
        assertIs<FileDialogOutcome.Cancelled>(results[0])
        late(FileDialogOutcome.Chosen(listOf("/x"), -1))
        assertEquals(1, results.size)
        assertTrue(p.released > 0)
    }

    @Test
    fun fileDialogCancelAllDeliversCancelledForQueuedRequests() {
        val s = Setup()
        val results = ArrayList<FileDialogOutcome>()
        s.lanes.show(open("1")) { results.add(it) }
        s.lanes.show(open("2")) { results.add(it) }
        s.lanes.show(open("3")) { results.add(it) }
        s.lanes.cancelAll()
        assertEquals(3, results.size)
        assertTrue(results.all { it is FileDialogOutcome.Cancelled })
        assertEquals(1, s.presenters.getValue("a").presented.size)
    }

    @Test
    fun fileDialogCancelAllSkipsDismissWhenTheWindowIsGone() {
        val s = Setup()
        var outcome: FileDialogOutcome? = null
        s.lanes.show(open("1")) { outcome = it }
        s.window = null
        s.lanes.cancelAll()
        assertEquals(0, s.presenters.getValue("a").dismissed)
        assertIs<FileDialogOutcome.Cancelled>(outcome)
    }

    @Test
    fun fileDialogShowAfterCancelAllAnswersUnavailable() {
        val s = Setup()
        s.lanes.show(open("1")) {}
        s.lanes.cancelAll()
        var outcome: FileDialogOutcome? = null
        s.lanes.show(open("2")) { outcome = it }
        assertIs<FileDialogOutcome.Unavailable>(outcome)
        assertEquals(1, s.presenters.getValue("a").presented.size)
    }

    @Test
    fun fileDialogShowWithOwnerGoneAnswersUnavailable() {
        val s = Setup()
        s.window = null
        var outcome: FileDialogOutcome? = null
        s.lanes.show(open("1")) { outcome = it }
        assertIs<FileDialogOutcome.Unavailable>(outcome)
        assertTrue(s.presenters.isEmpty())
    }

    @Test
    fun fileDialogLanesAnswerUnavailableToAQueuedRequestWhoseWindowWentAway() {
        val s = Setup()
        var second: FileDialogOutcome? = null
        s.lanes.show(open("1")) {}
        s.lanes.show(open("2")) { second = it }
        s.window = null
        s.presenters.getValue("a").completion!!(FileDialogOutcome.Cancelled)
        assertIs<FileDialogOutcome.Unavailable>(second)
    }

    @Test
    fun fileDialogLanesReleaseBeforeTheCallbackAndKeepARetryAlive() {
        val s = Setup()
        var releasedAtCallback = -1
        var retryResult: FileDialogOutcome? = null
        s.lanes.show(open("1")) {
            releasedAtCallback = s.presenters.getValue("a").released
            s.lanes.show(open("retry")) { retryResult = it }
        }
        val p = s.presenters.getValue("a")
        p.completion!!(FileDialogOutcome.Cancelled)
        assertEquals(1, releasedAtCallback)
        assertEquals(listOf("1", "retry"), p.presented.map { it.title })
        // The old completion must not have released the retry's presenter state.
        assertEquals(1, p.released)
        p.completion!!(FileDialogOutcome.Chosen(listOf("/x"), -1))
        assertIs<FileDialogOutcome.Chosen>(retryResult)
    }

    @Test
    fun fileDialogLanesRunRequestsQueuedBeforeARetryFirst() {
        val s = Setup()
        s.lanes.show(open("1")) { s.lanes.show(open("retry")) {} }
        s.lanes.show(open("2")) {}
        val p = s.presenters.getValue("a")
        p.completion!!(FileDialogOutcome.Cancelled)
        assertEquals(listOf("1", "2"), p.presented.map { it.title })
        p.completion!!(FileDialogOutcome.Cancelled)
        assertEquals(listOf("1", "2", "retry"), p.presented.map { it.title })
    }

    @Test
    fun fileDialogOutcomeOfNoPathsIsCancelled() {
        assertIs<FileDialogOutcome.Cancelled>(fileDialogOutcome(emptyList(), 0))
    }

    @Test
    fun fileDialogOutcomeWithANullPathIsUnavailable() {
        assertIs<FileDialogOutcome.Unavailable>(fileDialogOutcome(listOf("/a", null), 0))
    }

    @Test
    fun fileDialogOutcomeOverTheCountBoundIsUnavailable() {
        val paths = (0..FILE_DIALOG_MAX_PATHS).map { "/p$it" }
        assertIs<FileDialogOutcome.Unavailable>(fileDialogOutcome(paths, 0))
        assertIs<FileDialogOutcome.Chosen>(fileDialogOutcome(paths.take(FILE_DIALOG_MAX_PATHS), 0))
    }

    @Test
    fun fileDialogOutcomeOverThePayloadBoundIsUnavailable() {
        val big = "/" + "a".repeat(FILE_DIALOG_MAX_PAYLOAD_BYTES / 2)
        assertIs<FileDialogOutcome.Unavailable>(fileDialogOutcome(listOf(big, big, "/b"), 0))
        // Multi-byte characters count by their UTF-8 length.
        val wide = "/" + "\u00e9".repeat(FILE_DIALOG_MAX_PAYLOAD_BYTES / 2)
        assertIs<FileDialogOutcome.Unavailable>(fileDialogOutcome(listOf(wide), 0))
    }

    @Test
    fun fileDialogOutcomeOfNormalPathsIsChosenWithTheFilterIndex() {
        val outcome = fileDialogOutcome(listOf("/a", "/b"), 2)
        assertIs<FileDialogOutcome.Chosen>(outcome)
        assertEquals(listOf("/a", "/b"), outcome.paths)
        assertEquals(2, outcome.filterIndex)
    }
}
