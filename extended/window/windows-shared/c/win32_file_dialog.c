/*
 * File open and save dialogs on Windows: the Common Item Dialog (IFileOpenDialog and
 * IFileSaveDialog), owned by one of this process's windows.
 *
 * The dialog must not stop the window's loop. IFileDialog::Show is a modal call: it runs a
 * message loop of its own and returns when the reader is done, so calling it on the window's
 * thread would leave the renderer without a turn for as long as the dialog is open. Each
 * dialog therefore gets a thread of its own, a single threaded apartment that creates the
 * dialog, shows it with the window as its owner and reads the answer. The window's thread
 * goes on pumping and drawing.
 *
 * What the owner means across threads. Show disables the owner for input and enables it
 * again when the dialog closes, which is what makes the dialog modal to that window and to
 * no other; the dialog stays above its owner and is destroyed with it. Windows joins the
 * input queues of the two threads while one owns a window of the other, so the owner's
 * thread has to keep taking messages for the dialog to stay responsive. It does: that is the
 * pump the renderer already runs every frame.
 *
 * The answer goes back as data, not as a call. The dialog's thread writes the result on a
 * list and wakes the thread that asked with a thread message; that thread reads the list
 * with dxc_native_file_dialog_next and dxc_native_file_dialog_take after it pumps. Nothing
 * of the caller's runs on the dialog's thread.
 *
 * Ending a dialog early (dxc_native_file_dialog_cancel, or its owner being destroyed) is
 * done on the dialog's own thread, because the dialog belongs to that apartment: a timer of
 * that thread, which the dialog's loop dispatches, sees the request and calls
 * IFileDialog::Close. The dialog then answers cancelled like any other.
 *
 * Plain C, like the window beside it. The class and interface ids are written out below, so
 * the application links ole32 and shell32 and needs no uuid library.
 */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define COBJMACROS
#include <windows.h>
#include <objbase.h>
#include <shobjidl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "win32_file_dialog_spec.h"

// What a dialog picks and how it ended, numbered as cinterop/include/win32_window.h numbers
// them (this file does not include that header; the shared test checks the two agree).
enum {
    DXC_FILE_DIALOG_OPEN_FILE = 1,
    DXC_FILE_DIALOG_OPEN_FILES = 2,
    DXC_FILE_DIALOG_OPEN_FOLDER = 3,
    DXC_FILE_DIALOG_OPEN_FOLDERS = 4,
    DXC_FILE_DIALOG_SAVE = 5,
};
enum {
    DXC_FILE_DIALOG_CHOSEN = 1,
    DXC_FILE_DIALOG_CANCELLED = 2,
    DXC_FILE_DIALOG_UNAVAILABLE = 3,
};

// The most one answer carries. More than this is answered unavailable rather than cut,
// because a selection that arrives in part is one the application cannot tell from a whole.
#define DXC_FILE_DIALOG_MAX_PATHS 10000
#define DXC_FILE_DIALOG_MAX_BYTES (32 * 1024 * 1024)

// How often the dialog's thread looks for a request to end it, in milliseconds.
#define DXC_FILE_DIALOG_CANCEL_POLL 50

static const CLSID DXC_CLSID_FileOpenDialog =
    {0xdc1c5a9c, 0xe88a, 0x4dde, {0xa5, 0xa1, 0x60, 0xf8, 0x2a, 0x20, 0xae, 0xf7}};
static const CLSID DXC_CLSID_FileSaveDialog =
    {0xc0b4e2f3, 0xba21, 0x4773, {0x8d, 0xba, 0x33, 0x5e, 0xc9, 0x46, 0xeb, 0x8b}};
static const IID DXC_IID_IFileOpenDialog =
    {0xd57c7288, 0xd4ad, 0x4768, {0xbe, 0x02, 0x9d, 0x96, 0x95, 0x32, 0xd9, 0x60}};
static const IID DXC_IID_IFileSaveDialog =
    {0x84bccd23, 0x5fde, 0x4cdb, {0xae, 0xa4, 0xaf, 0x64, 0xb8, 0x3d, 0x78, 0xab}};
static const IID DXC_IID_IOleWindow =
    {0x00000114, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const IID DXC_IID_IShellItem =
    {0x43826d1e, 0xe718, 0x42ee, {0xbc, 0x55, 0xa1, 0xe2, 0x61, 0xc3, 0x7b, 0xfe}};

struct dxc_file_dialog {
    struct dxc_file_dialog *next;
    int64_t request;
    HWND owner;
    // The thread that asked, woken when the answer is ready.
    DWORD asker;
    int32_t kind;
    WCHAR *title;
    WCHAR *directory;
    WCHAR *name;
    WCHAR *default_extension;
    COMDLG_FILTERSPEC *filters;
    UINT filter_count;
    UINT default_filter;
    // Set by dxc_native_file_dialog_cancel on the asker's thread, read on the dialog's.
    volatile LONG cancel;
    // The answer.
    int32_t status;
    int32_t filter_index;
    int32_t path_count;
    // The paths in UTF-8, a NUL after each.
    char *paths;
    size_t path_bytes;
    size_t path_capacity;
};

// Dialogs that are open, and answers nobody has taken yet, oldest first.
static SRWLOCK dxc_file_dialog_lock = SRWLOCK_INIT;
static struct dxc_file_dialog *dxc_file_dialogs_open;
static struct dxc_file_dialog *dxc_file_dialogs_done;

// The dialog this thread is showing, for the timer that ends it early.
#if defined(_MSC_VER)
#define DXC_THREAD_LOCAL __declspec(thread)
#else
#define DXC_THREAD_LOCAL _Thread_local
#endif
static DXC_THREAD_LOCAL struct dxc_file_dialog *dxc_file_dialog_shown;
static DXC_THREAD_LOCAL IFileDialog *dxc_file_dialog_shown_object;

/** [utf8] as UTF-16, [length] bytes of it or all of it when [length] is below zero. NULL when it is not UTF-8. */
static WCHAR *dxc_file_dialog_wide(const char *utf8, int length) {
    if (utf8 == NULL) utf8 = "";
    if (length < 0) length = (int)strlen(utf8);
    int units = length == 0 ? 0 : MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, length, NULL, 0);
    if (length != 0 && units <= 0) return NULL;
    WCHAR *wide = (WCHAR *)malloc(((size_t)units + 1) * sizeof(WCHAR));
    if (wide == NULL) return NULL;
    if (units > 0) MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, length, wide, units);
    wide[units] = L'\0';
    return wide;
}

static void dxc_file_dialog_free(struct dxc_file_dialog *dialog) {
    if (dialog == NULL) return;
    for (UINT index = 0; index < dialog->filter_count; index++) {
        free((void *)dialog->filters[index].pszName);
        free((void *)dialog->filters[index].pszSpec);
    }
    free(dialog->filters);
    free(dialog->title);
    free(dialog->directory);
    free(dialog->name);
    free(dialog->default_extension);
    free(dialog->paths);
    free(dialog);
}

/** Reads the filter lines into the dialog's type list. 0 when every line was read. */
static int dxc_file_dialog_read_filters(struct dxc_file_dialog *dialog, const char *filters,
                                        int32_t default_filter) {
    int32_t count = dxc_file_filter_count(filters);
    if (count == 0) return 0;
    dialog->filters = (COMDLG_FILTERSPEC *)calloc((size_t)count, sizeof(COMDLG_FILTERSPEC));
    if (dialog->filters == NULL) return 1;
    if (default_filter < 0 || default_filter >= count) default_filter = 0;
    dialog->default_filter = (UINT)default_filter;
    for (int32_t index = 0; index < count; index++) {
        const char *label, *extensions;
        size_t label_length, extensions_length;
        dxc_file_filter_at(filters, index, &label, &label_length, &extensions, &extensions_length);
        size_t pattern_length = dxc_file_filter_pattern(extensions, extensions_length, NULL, 0);
        char *pattern = (char *)malloc(pattern_length + 1);
        if (pattern == NULL) return 1;
        dxc_file_filter_pattern(extensions, extensions_length, pattern, pattern_length + 1);
        WCHAR *wide_label = dxc_file_dialog_wide(label, (int)label_length);
        WCHAR *wide_pattern = dxc_file_dialog_wide(pattern, (int)pattern_length);
        free(pattern);
        // Counted before the check, so a half made entry is freed with the rest.
        dialog->filters[index].pszName = wide_label;
        dialog->filters[index].pszSpec = wide_pattern;
        dialog->filter_count = (UINT)index + 1;
        if (wide_label == NULL || wide_pattern == NULL) return 1;
        if (index == default_filter) {
            const char *first;
            size_t first_length = dxc_file_filter_first_extension(extensions, extensions_length, &first);
            if (first_length > 0) {
                dialog->default_extension = dxc_file_dialog_wide(first, (int)first_length);
                if (dialog->default_extension == NULL) return 1;
            }
        }
    }
    return 0;
}

/** Adds one path to the answer. 0 when added, 1 when it cannot be carried. */
static int dxc_file_dialog_add_path(struct dxc_file_dialog *dialog, IShellItem *item) {
    if (dialog->path_count >= DXC_FILE_DIALOG_MAX_PATHS) return 1;
    WCHAR *wide = NULL;
    // The whole path as the shell gives it: not cut at MAX_PATH and with no prefix added.
    if (FAILED(IShellItem_GetDisplayName(item, SIGDN_FILESYSPATH, &wide)) || wide == NULL) return 1;
    int failed = 1;
    // With the ending NUL, which is the separator the answer keeps. A name that is not
    // UTF-16 (a lone surrogate) has no UTF-8 and fails here.
    int bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, -1, NULL, 0, NULL, NULL);
    if (bytes > 1 && dialog->path_bytes + (size_t)bytes <= DXC_FILE_DIALOG_MAX_BYTES) {
        if (dialog->path_bytes + (size_t)bytes > dialog->path_capacity) {
            size_t capacity = dialog->path_capacity == 0 ? 1024 : dialog->path_capacity * 2;
            while (capacity < dialog->path_bytes + (size_t)bytes) capacity *= 2;
            char *grown = (char *)realloc(dialog->paths, capacity);
            if (grown != NULL) {
                dialog->paths = grown;
                dialog->path_capacity = capacity;
            }
        }
        if (dialog->path_bytes + (size_t)bytes <= dialog->path_capacity) {
            WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, -1,
                                dialog->paths + dialog->path_bytes, bytes, NULL, NULL);
            dialog->path_bytes += (size_t)bytes;
            dialog->path_count++;
            failed = 0;
        }
    }
    CoTaskMemFree(wide);
    return failed;
}

/** Reads what the reader chose. 0 when every chosen path was read. */
static int dxc_file_dialog_read_result(struct dxc_file_dialog *dialog, IFileDialog *object) {
    int failed = 0;
    if (dialog->kind == DXC_FILE_DIALOG_SAVE) {
        IShellItem *item = NULL;
        if (FAILED(IFileDialog_GetResult(object, &item)) || item == NULL) return 1;
        failed = dxc_file_dialog_add_path(dialog, item);
        IShellItem_Release(item);
        return failed;
    }
    IShellItemArray *items = NULL;
    if (FAILED(IFileOpenDialog_GetResults((IFileOpenDialog *)object, &items)) || items == NULL) return 1;
    DWORD count = 0;
    if (FAILED(IShellItemArray_GetCount(items, &count)) || count > DXC_FILE_DIALOG_MAX_PATHS) {
        failed = 1;
    }
    for (DWORD index = 0; !failed && index < count; index++) {
        IShellItem *item = NULL;
        if (FAILED(IShellItemArray_GetItemAt(items, index, &item)) || item == NULL) {
            failed = 1;
            break;
        }
        failed = dxc_file_dialog_add_path(dialog, item);
        IShellItem_Release(item);
    }
    IShellItemArray_Release(items);
    return failed;
}

static int dxc_file_dialog_should_end(const struct dxc_file_dialog *dialog) {
    return dialog->cancel != 0 || !IsWindow(dialog->owner);
}

/** Runs on the dialog's thread, from the dialog's own loop, while the dialog is open. */
static void CALLBACK dxc_file_dialog_timer(HWND window, UINT message, UINT_PTR id, DWORD time) {
    (void)window;
    (void)message;
    (void)id;
    (void)time;
    struct dxc_file_dialog *dialog = dxc_file_dialog_shown;
    IFileDialog *object = dxc_file_dialog_shown_object;
    if (dialog == NULL || object == NULL || !dxc_file_dialog_should_end(dialog)) return;
    // A question of the dialog's own may be standing over it (replace this file?), and the
    // dialog does not close under one. It is answered no first; this timer comes round again.
    IOleWindow *as_window = NULL;
    if (SUCCEEDED(IFileDialog_QueryInterface(object, &DXC_IID_IOleWindow, (void **)&as_window)) &&
        as_window != NULL) {
        HWND shown = NULL;
        if (SUCCEEDED(IOleWindow_GetWindow(as_window, &shown)) && shown != NULL && !IsWindowEnabled(shown)) {
            HWND question = GetWindow(shown, GW_ENABLEDPOPUP);
            if (question != NULL && question != shown) {
                PostMessageW(question, WM_COMMAND, MAKEWPARAM(IDNO, BN_CLICKED), 0);
                PostMessageW(question, WM_COMMAND, MAKEWPARAM(IDCANCEL, BN_CLICKED), 0);
            }
        }
        IOleWindow_Release(as_window);
    }
    IFileDialog_Close(object, HRESULT_FROM_WIN32(ERROR_CANCELLED));
}

static int dxc_file_dialog_is_directory(const WCHAR *path) {
    DWORD attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

/** Makes the dialog, shows it and reads the answer into [dialog]. On the dialog's thread. */
static void dxc_file_dialog_run(struct dxc_file_dialog *dialog) {
    int save = dialog->kind == DXC_FILE_DIALOG_SAVE;
    int folders = dialog->kind == DXC_FILE_DIALOG_OPEN_FOLDER || dialog->kind == DXC_FILE_DIALOG_OPEN_FOLDERS;
    int many = dialog->kind == DXC_FILE_DIALOG_OPEN_FILES || dialog->kind == DXC_FILE_DIALOG_OPEN_FOLDERS;
    IFileDialog *object = NULL;
    HRESULT result = CoCreateInstance(save ? &DXC_CLSID_FileSaveDialog : &DXC_CLSID_FileOpenDialog, NULL,
                                      CLSCTX_INPROC_SERVER,
                                      save ? &DXC_IID_IFileSaveDialog : &DXC_IID_IFileOpenDialog,
                                      (void **)&object);
    if (FAILED(result) || object == NULL) return;

    FILEOPENDIALOGOPTIONS options = 0;
    IFileDialog_GetOptions(object, &options);
    // Paths of the file system only, since a path is what the answer carries, and the
    // process's working directory is left where it is.
    options |= FOS_FORCEFILESYSTEM | FOS_NOCHANGEDIR | FOS_PATHMUSTEXIST;
    if (save) {
        // The system asks before a file is replaced; the caller cannot turn that off.
        options |= FOS_OVERWRITEPROMPT | FOS_NOREADONLYRETURN;
    } else {
        options |= FOS_FILEMUSTEXIST;
        if (folders) options |= FOS_PICKFOLDERS;
        if (many) options |= FOS_ALLOWMULTISELECT;
    }
    IFileDialog_SetOptions(object, options);

    if (dialog->title != NULL && dialog->title[0] != L'\0') IFileDialog_SetTitle(object, dialog->title);
    // A folder picker has no type list.
    if (!folders && dialog->filter_count > 0) {
        IFileDialog_SetFileTypes(object, dialog->filter_count, dialog->filters);
        // The dialog counts its types from one.
        IFileDialog_SetFileTypeIndex(object, dialog->default_filter + 1);
        // With this a save adds the selected type's first extension to a name typed without
        // one, and follows the type list when the reader picks another.
        if (save && dialog->default_extension != NULL) {
            IFileDialog_SetDefaultExtension(object, dialog->default_extension);
        }
    }
    if (save && dialog->name != NULL && dialog->name[0] != L'\0') IFileDialog_SetFileName(object, dialog->name);
    // A folder that is not there is not an error: the dialog opens where the system would.
    if (dialog->directory != NULL && dialog->directory[0] != L'\0' &&
        dxc_file_dialog_is_directory(dialog->directory)) {
        IShellItem *folder = NULL;
        if (SUCCEEDED(SHCreateItemFromParsingName(dialog->directory, NULL, &DXC_IID_IShellItem,
                                                  (void **)&folder)) && folder != NULL) {
            IFileDialog_SetFolder(object, folder);
            IShellItem_Release(folder);
        }
    }

    if (dxc_file_dialog_should_end(dialog)) {
        dialog->status = DXC_FILE_DIALOG_CANCELLED;
    } else {
        dxc_file_dialog_shown = dialog;
        dxc_file_dialog_shown_object = object;
        UINT_PTR timer = SetTimer(NULL, 0, DXC_FILE_DIALOG_CANCEL_POLL, dxc_file_dialog_timer);
        result = IFileDialog_Show(object, dialog->owner);
        if (timer != 0) KillTimer(NULL, timer);
        dxc_file_dialog_shown = NULL;
        dxc_file_dialog_shown_object = NULL;
        if (result == HRESULT_FROM_WIN32(ERROR_CANCELLED)) {
            dialog->status = DXC_FILE_DIALOG_CANCELLED;
        } else if (SUCCEEDED(result)) {
            UINT type = 0;
            if (!folders && dialog->filter_count > 0 && SUCCEEDED(IFileDialog_GetFileTypeIndex(object, &type)) &&
                type >= 1 && type <= dialog->filter_count) {
                dialog->filter_index = (int32_t)type - 1;
            }
            if (dxc_file_dialog_read_result(dialog, object) == 0) {
                // Chosen with nothing chosen is a dismissal, never an empty selection.
                dialog->status = dialog->path_count > 0 ? DXC_FILE_DIALOG_CHOSEN : DXC_FILE_DIALOG_CANCELLED;
            }
        }
    }
    IFileDialog_Release(object);
}

static DWORD WINAPI dxc_file_dialog_thread(LPVOID parameter) {
    struct dxc_file_dialog *dialog = (struct dxc_file_dialog *)parameter;
    // An apartment of this thread's own: the dialog is made, shown and released in it.
    HRESULT apartment = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    if (SUCCEEDED(apartment)) {
        dxc_file_dialog_run(dialog);
        CoUninitialize();
    }
    // Ended from outside is cancelled whatever the dialog was doing when it was told.
    if (dialog->cancel != 0) dialog->status = DXC_FILE_DIALOG_CANCELLED;
    if (dialog->status != DXC_FILE_DIALOG_CHOSEN) {
        dialog->path_count = 0;
        dialog->path_bytes = 0;
        dialog->filter_index = -1;
    }

    AcquireSRWLockExclusive(&dxc_file_dialog_lock);
    struct dxc_file_dialog **link = &dxc_file_dialogs_open;
    while (*link != NULL && *link != dialog) link = &(*link)->next;
    if (*link == dialog) *link = dialog->next;
    dialog->next = NULL;
    link = &dxc_file_dialogs_done;
    while (*link != NULL) link = &(*link)->next;
    *link = dialog;
    DWORD asker = dialog->asker;
    ReleaseSRWLockExclusive(&dxc_file_dialog_lock);
    // The asker may be resting in its pump: a message, any message, brings it back to read
    // the answer. After the lock, and not through [dialog], which the asker may already
    // have taken and freed.
    PostThreadMessageW(asker, WM_NULL, 0, 0);
    return 0;
}

static struct dxc_file_dialog *dxc_file_dialog_find(struct dxc_file_dialog *list, int64_t request) {
    while (list != NULL && list->request != request) list = list->next;
    return list;
}

/**
 * Shows a file dialog owned by [window] and returns at once. 0 when the dialog was started:
 * exactly one answer for [request] then comes through dxc_native_file_dialog_next, on the
 * thread that pumps. Not 0 when nothing was started and no answer will come: no such window,
 * a kind that is not one, text that is not UTF-8, or a [request] that is still open or
 * whose answer has not been taken.
 *
 * [kind] is one of DXC_FILE_DIALOG_OPEN_FILE and the rest. [title], [directory] and [name]
 * are UTF-8 and empty means the system's own choice; [name] is the file name a save
 * suggests. [filters] is one line per file type, the label, a tab and the extensions
 * without dots separated by commas ("Images\tpng,jpg\nAll files\t"); a line with no
 * extensions is every file. [default_filter] is the line selected first. Folder kinds
 * ignore the filters.
 *
 * Call it on the thread that pumps the window: that thread is the one woken for the answer.
 */
int32_t dxc_native_file_dialog_show(void *window, int64_t request, int32_t kind, const char *title,
                                    const char *directory, const char *name, const char *filters,
                                    int32_t default_filter) {
    if (window == NULL || !IsWindow((HWND)window)) return 1;
    if (kind < DXC_FILE_DIALOG_OPEN_FILE || kind > DXC_FILE_DIALOG_SAVE) return 1;
    struct dxc_file_dialog *dialog = (struct dxc_file_dialog *)calloc(1, sizeof *dialog);
    if (dialog == NULL) return 1;
    dialog->request = request;
    dialog->owner = (HWND)window;
    dialog->asker = GetCurrentThreadId();
    dialog->kind = kind;
    dialog->status = DXC_FILE_DIALOG_UNAVAILABLE;
    dialog->filter_index = -1;
    dialog->title = dxc_file_dialog_wide(title, -1);
    dialog->directory = dxc_file_dialog_wide(directory, -1);
    dialog->name = dxc_file_dialog_wide(name, -1);
    if (dialog->title == NULL || dialog->directory == NULL || dialog->name == NULL ||
        dxc_file_dialog_read_filters(dialog, filters, default_filter) != 0) {
        dxc_file_dialog_free(dialog);
        return 1;
    }
    // The shell reads a path with backslashes.
    for (WCHAR *at = dialog->directory; *at != L'\0'; at++) {
        if (*at == L'/') *at = L'\\';
    }

    AcquireSRWLockExclusive(&dxc_file_dialog_lock);
    int taken = dxc_file_dialog_find(dxc_file_dialogs_open, request) != NULL ||
                dxc_file_dialog_find(dxc_file_dialogs_done, request) != NULL;
    if (!taken) {
        dialog->next = dxc_file_dialogs_open;
        dxc_file_dialogs_open = dialog;
    }
    ReleaseSRWLockExclusive(&dxc_file_dialog_lock);
    if (taken) {
        dxc_file_dialog_free(dialog);
        return 1;
    }

    HANDLE thread = CreateThread(NULL, 0, dxc_file_dialog_thread, dialog, 0, NULL);
    if (thread == NULL) {
        AcquireSRWLockExclusive(&dxc_file_dialog_lock);
        struct dxc_file_dialog **link = &dxc_file_dialogs_open;
        while (*link != NULL && *link != dialog) link = &(*link)->next;
        if (*link == dialog) *link = dialog->next;
        ReleaseSRWLockExclusive(&dxc_file_dialog_lock);
        dxc_file_dialog_free(dialog);
        return 1;
    }
    CloseHandle(thread);
    return 0;
}

/**
 * Asks the open dialog of [request] to close. Its answer still comes, once, as cancelled.
 * Nothing happens for a request that is not open.
 */
void dxc_native_file_dialog_cancel(int64_t request) {
    AcquireSRWLockShared(&dxc_file_dialog_lock);
    struct dxc_file_dialog *dialog = dxc_file_dialog_find(dxc_file_dialogs_open, request);
    if (dialog != NULL) InterlockedExchange(&dialog->cancel, 1);
    ReleaseSRWLockShared(&dxc_file_dialog_lock);
}

/**
 * The oldest answer not taken yet: 1 with its request, how it ended
 * (DXC_FILE_DIALOG_CHOSEN and the rest), the type selected (from 0, or -1 for none), how
 * many paths were chosen and how many bytes dxc_native_file_dialog_take needs for them.
 * 0 when no dialog has ended. The answer stays until it is taken.
 */
int32_t dxc_native_file_dialog_next(int64_t *request, int32_t *status, int32_t *filter_index,
                                    int32_t *path_count, int32_t *path_bytes) {
    int32_t found = 0;
    AcquireSRWLockShared(&dxc_file_dialog_lock);
    const struct dxc_file_dialog *dialog = dxc_file_dialogs_done;
    if (dialog != NULL) {
        found = 1;
        if (request != NULL) *request = dialog->request;
        if (status != NULL) *status = dialog->status;
        if (filter_index != NULL) *filter_index = dialog->filter_index;
        if (path_count != NULL) *path_count = dialog->path_count;
        // Without the NUL after the last path: the NULs that remain are between paths.
        if (path_bytes != NULL) *path_bytes = dialog->path_bytes > 0 ? (int32_t)dialog->path_bytes - 1 : 0;
    }
    ReleaseSRWLockShared(&dxc_file_dialog_lock);
    return found;
}

/**
 * Takes the answer of [request]: copies its paths into [out], absolute and in UTF-8 with a
 * NUL between one and the next, forgets the answer and returns the bytes written (0 for an
 * answer with no path). -1, and the answer stays, when [request] has no answer waiting or
 * [capacity] is below what dxc_native_file_dialog_next reported.
 */
int32_t dxc_native_file_dialog_take(int64_t request, char *out, int32_t capacity) {
    struct dxc_file_dialog *taken = NULL;
    int32_t bytes = -1;
    AcquireSRWLockExclusive(&dxc_file_dialog_lock);
    struct dxc_file_dialog **link = &dxc_file_dialogs_done;
    while (*link != NULL && (*link)->request != request) link = &(*link)->next;
    if (*link != NULL) {
        int32_t needed = (*link)->path_bytes > 0 ? (int32_t)(*link)->path_bytes - 1 : 0;
        if (needed == 0 || (out != NULL && capacity >= needed)) {
            taken = *link;
            *link = taken->next;
            bytes = needed;
        }
    }
    ReleaseSRWLockExclusive(&dxc_file_dialog_lock);
    if (taken != NULL) {
        if (bytes > 0) memcpy(out, taken->paths, (size_t)bytes);
        dxc_file_dialog_free(taken);
    }
    return bytes;
}
