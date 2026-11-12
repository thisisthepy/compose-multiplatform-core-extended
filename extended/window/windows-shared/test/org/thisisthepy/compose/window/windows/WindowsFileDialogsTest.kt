package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.FILE_DIALOG_MAX_PATHS
import org.thisisthepy.compose.window.FileDialogFilter
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest

// The dialogs over a backend that is a few lists. Nothing here reaches the C dialogs, whose
// object is linked by MSVC into applications and never by this toolchain; the C side of the
// same requirements is checked by c/test/win32_shared_test.c.

internal class FakeDialogs : Win32FileDialogBackend {
    class Started(val window: Long, val id: Long, val request: FileDialogRequest)

    val started = ArrayList<Started>()
    val cancelled = ArrayList<Long>()
    val answers = ArrayDeque<Win32FileDialogAnswer>()
    var refuse = false

    override fun start(window: Long, id: Long, request: FileDialogRequest): Boolean {
        if (refuse) return false
        started.add(Started(window, id, request))
        return true
    }

    override fun cancel(id: Long) {
        cancelled.add(id)
        // As the C side does: a dialog told to close still answers, as cancelled.
        answers.addLast(Win32FileDialogAnswer(id, Win32FileDialogAnswer.CANCELLED, -1, emptyList()))
    }

    override fun poll(): Win32FileDialogAnswer? = answers.removeFirstOrNull()

    fun choose(id: Long, vararg paths: String, filter: Int = -1) {
        answers.addLast(Win32FileDialogAnswer(id, Win32FileDialogAnswer.CHOSEN, filter, paths.toList()))
    }

    fun end(id: Long, status: Int) {
        answers.addLast(Win32FileDialogAnswer(id, status, -1, emptyList()))
    }
}

private fun open(kind: FileDialogKind = FileDialogKind.OpenFile, filters: List<FileDialogFilter> = emptyList()) =
    FileDialogRequest(kind, filters = filters)

private class Fixture(var handle: Long? = 0x1000L) {
    val backend = FakeDialogs()
    val center = Win32FileDialogCenter(backend)
    val dialogs = WindowsFileDialogs(center) { handle }
    val outcomes = ArrayList<FileDialogOutcome>()

    fun show(request: FileDialogRequest = open()) = dialogs.show(request) { outcomes.add(it) }
}

class WindowsFileDialogsTest {

    @Test
    fun fr72_result_is_delivered_once_and_only_when_the_pump_delivers() {
        val f = Fixture()
        f.show()
        assertEquals(1, f.backend.started.size)
        assertEquals(0x1000L, f.backend.started[0].window)
        assertEquals(1, f.center.openCount)
        f.backend.choose(f.backend.started[0].id, "C:\\a.txt")
        // The dialog ended on its own thread; nothing reaches the caller until the pump delivers.
        assertTrue(f.outcomes.isEmpty())
        f.center.deliver()
        f.center.deliver()
        assertEquals(1, f.outcomes.size)
        val chosen = assertIs<FileDialogOutcome.Chosen>(f.outcomes[0])
        assertEquals(listOf("C:\\a.txt"), chosen.paths)
        assertEquals(-1, chosen.filterIndex)
        assertEquals(0, f.center.openCount)
    }

    @Test
    fun fr72_requests_on_one_window_are_shown_in_order() {
        val f = Fixture()
        val order = ArrayList<String>()
        f.dialogs.show(open()) { order.add("first") }
        f.dialogs.show(FileDialogRequest(FileDialogKind.Save, name = "b.txt")) { order.add("second") }
        // The second waits for the first.
        assertEquals(1, f.backend.started.size)
        f.backend.end(f.backend.started[0].id, Win32FileDialogAnswer.CANCELLED)
        f.center.deliver()
        assertEquals(listOf("first"), order)
        assertEquals(2, f.backend.started.size)
        assertEquals(FileDialogKind.Save, f.backend.started[1].request.kind)
        f.backend.choose(f.backend.started[1].id, "C:\\b.txt")
        f.center.deliver()
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun fr72_requests_on_different_windows_do_not_wait_for_each_other() {
        val backend = FakeDialogs()
        val center = Win32FileDialogCenter(backend)
        val first = WindowsFileDialogs(center) { 0x10L }
        val second = WindowsFileDialogs(center) { 0x20L }
        val order = ArrayList<String>()
        first.show(open()) { order.add("first") }
        second.show(open()) { order.add("second") }
        assertEquals(listOf(0x10L, 0x20L), backend.started.map { it.window })
        // The answers find their own callers whichever dialog ends first.
        backend.choose(backend.started[1].id, "C:\\2")
        backend.choose(backend.started[0].id, "C:\\1")
        center.deliver()
        assertEquals(listOf("second", "first"), order)
    }

    @Test
    fun fr72_cancel_all_ends_open_and_waiting_requests_once_as_cancelled() {
        val f = Fixture()
        f.show()
        f.show()
        val id = f.backend.started[0].id
        f.dialogs.cancelAll()
        assertEquals(listOf(id), f.backend.cancelled)
        assertEquals(listOf<FileDialogOutcome>(FileDialogOutcome.Cancelled, FileDialogOutcome.Cancelled), f.outcomes)
        // The closed dialog's own answer arrives later and is not a second result.
        f.center.deliver()
        assertEquals(2, f.outcomes.size)
        assertEquals(1, f.backend.started.size)
        // Closed for good.
        f.show()
        assertSame(FileDialogOutcome.Unavailable, f.outcomes[2])
        assertEquals(1, f.backend.started.size)
    }

    @Test
    fun fr72_a_dialog_that_cannot_start_answers_unavailable_and_the_next_one_runs() {
        val f = Fixture()
        f.backend.refuse = true
        f.show()
        assertEquals(listOf<FileDialogOutcome>(FileDialogOutcome.Unavailable), f.outcomes)
        f.backend.refuse = false
        f.show()
        assertEquals(1, f.backend.started.size)
        f.backend.choose(f.backend.started[0].id, "C:\\x")
        f.center.deliver()
        assertIs<FileDialogOutcome.Chosen>(f.outcomes[1])
    }

    @Test
    fun fr72_a_window_that_is_gone_answers_unavailable() {
        val f = Fixture(handle = null)
        f.show()
        assertEquals(listOf<FileDialogOutcome>(FileDialogOutcome.Unavailable), f.outcomes)
        assertTrue(f.backend.started.isEmpty())

        // Gone while a request waited: the waiting one is not shown on a window that is not there.
        val g = Fixture()
        g.show()
        g.show()
        g.handle = null
        g.backend.end(g.backend.started[0].id, Win32FileDialogAnswer.CANCELLED)
        g.center.deliver()
        assertEquals(listOf(FileDialogOutcome.Cancelled, FileDialogOutcome.Unavailable), g.outcomes)
        assertEquals(1, g.backend.started.size)
    }

    @Test
    fun fr72_the_callback_may_show_again() {
        val f = Fixture()
        f.dialogs.show(open()) { first ->
            f.outcomes.add(first)
            f.show()
        }
        f.backend.end(f.backend.started[0].id, Win32FileDialogAnswer.CANCELLED)
        f.center.deliver()
        assertEquals(2, f.backend.started.size)
        f.backend.choose(f.backend.started[1].id, "C:\\again")
        f.center.deliver()
        assertEquals(2, f.outcomes.size)
        assertSame(FileDialogOutcome.Cancelled, f.outcomes[0])
        assertEquals(listOf("C:\\again"), assertIs<FileDialogOutcome.Chosen>(f.outcomes[1]).paths)
    }

    @Test
    fun fr72_statuses_become_outcomes() {
        val f = Fixture()
        f.show()
        f.backend.end(f.backend.started[0].id, Win32FileDialogAnswer.CANCELLED)
        f.center.deliver()
        f.show()
        f.backend.end(f.backend.started[1].id, Win32FileDialogAnswer.UNAVAILABLE)
        f.center.deliver()
        f.show()
        f.backend.end(f.backend.started[2].id, 99)
        f.center.deliver()
        // Chosen with no path is a dismissal, never an empty selection.
        f.show()
        f.backend.choose(f.backend.started[3].id)
        f.center.deliver()
        assertEquals(
            listOf(
                FileDialogOutcome.Cancelled,
                FileDialogOutcome.Unavailable,
                FileDialogOutcome.Unavailable,
                FileDialogOutcome.Cancelled,
            ),
            f.outcomes,
        )
    }

    @Test
    fun fr72_paths_arrive_whole_and_more_than_ten_thousand_answer_unavailable() {
        val f = Fixture()
        val long = "C:\\" + "가".repeat(300) + "\\파일.txt"
        f.show(open(FileDialogKind.OpenFiles))
        f.backend.choose(f.backend.started[0].id, long, "D:\\b c\\d.txt")
        f.center.deliver()
        assertEquals(listOf(long, "D:\\b c\\d.txt"), assertIs<FileDialogOutcome.Chosen>(f.outcomes[0]).paths)

        f.show(open(FileDialogKind.OpenFiles))
        val many = Array(FILE_DIALOG_MAX_PATHS + 1) { "C:\\$it" }
        f.backend.choose(f.backend.started[1].id, *many)
        f.center.deliver()
        assertSame(FileDialogOutcome.Unavailable, f.outcomes[1])
    }

    @Test
    fun fr72_the_selected_filter_is_reported_only_where_there_are_filters() {
        val filters = listOf(FileDialogFilter("Images", listOf("png")), FileDialogFilter("All", emptyList()))
        val f = Fixture()
        f.show(FileDialogRequest(FileDialogKind.Save, filters = filters, defaultFilter = 1))
        f.backend.choose(f.backend.started[0].id, "C:\\a.png", filter = 0)
        f.center.deliver()
        assertEquals(0, assertIs<FileDialogOutcome.Chosen>(f.outcomes[0]).filterIndex)
        // An index the dialog could not have meant falls back to the one it opened with.
        f.show(FileDialogRequest(FileDialogKind.Save, filters = filters, defaultFilter = 1))
        f.backend.choose(f.backend.started[1].id, "C:\\a.png", filter = 7)
        f.center.deliver()
        assertEquals(1, assertIs<FileDialogOutcome.Chosen>(f.outcomes[1]).filterIndex)
        // Folder pickers have no type list.
        f.show(FileDialogRequest(FileDialogKind.OpenFolder, filters = filters))
        f.backend.choose(f.backend.started[2].id, "C:\\dir", filter = 0)
        f.center.deliver()
        assertEquals(-1, assertIs<FileDialogOutcome.Chosen>(f.outcomes[2]).filterIndex)
    }

    @Test
    fun fr72_the_request_reaches_the_dialog_normalized() {
        val f = Fixture()
        val filters = listOf(
            FileDialogFilter("Im\tages\n", listOf(".PNG", "*.jpg")),
            FileDialogFilter("", emptyList()),
        )
        f.show(FileDialogRequest(FileDialogKind.Save, title = "t", directory = "C:\\d", name = "x\\y.png", filters = filters))
        val request = f.backend.started[0].request
        assertEquals("y.png", request.name)
        assertEquals("Im ages \tpng,jpg\n \t", encodeFileDialogFilters(request))
        assertEquals("", encodeFileDialogFilters(open()))
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            listOf(
                FileDialogKind.OpenFile,
                FileDialogKind.OpenFiles,
                FileDialogKind.OpenFolder,
                FileDialogKind.OpenFolders,
                FileDialogKind.Save,
            ).map { fileDialogKindCode(it) },
        )
    }
}
