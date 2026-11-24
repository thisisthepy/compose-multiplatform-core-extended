// Resize probe for the shared Win32 window, run on a Windows runner by the
// extended-window-windows-shared workflow (FR-19.8, compose-rust#684).
//
// Opens the window with a stand-in renderer that paints a grey frame with a magenta strip
// along its right and its bottom edge, at whatever size it is asked for, on the CPU and on
// the GPU. A second thread reads every frame DWM composes (DXGI desktop duplication) and
// judges it: the window's visible right edge and bottom edge must carry the strips. A frame
// that shows the window at one size with the picture drawn for another (cut, or padded with
// an older picture) lacks a strip at one of those edges, and is counted.
//
//   win32_resize_probe states   maximise, restore, left-half snap and back, three times
//   win32_resize_probe drag     a right-edge drag with SendInput, out and back, and how far
//                               the window's edge lags the pointer
//   win32_resize_probe drag-left, drag-top, drag-topleft
//                               drags of the left edge, the top edge and the top-left corner,
//                               in and out at a real hand's pace (4 to 8 ms between moves, 40 to
//                               120 px a move, a fixed seed), two drags each
//   win32_resize_probe drag-fast the window grown fast by its right edge, its bottom edge, its
//                               bottom-right corner and its top-left corner, at the same pace
//   win32_resize_probe drag-right, drag-bottom, drag-bottomright, drag-topright, drag-bottomleft
//                               in and out over many steps (8 to 60 px a move), three drags each
//   win32_resize_probe drag-mixed the bottom-right corner with the width growing while the
//                               height shrinks, and the other way, three drags
//   win32_resize_probe drag-slow  the right edge and the bottom-right corner, 1 to 3 px a move
//
// Every judged mode prints how far the window followed the pointer ("probe: follow <mode>
// sides X px of pointer Y px = Z %; lag moves=..."): the dragged sides' travel against the
// pointer's, and each move's distance between where the pointer asked a side to be and where
// it was before the next move. PROBE_DRAGS=<n> sets how many drags a mode makes.
//
// The drag modes above (all but drag) are judged by two
// more judges besides the strips (c/test/win32_probe_judge.h). Their picture adds a marker
// 16 px in from each corner and a row of blocks spelling the frame's size, and the probe asks
// DWM to draw the window's border in pure blue (DWMWA_BORDER_COLOR, Windows build 22000 and
// later), so every composed frame says where the window's rectangle really is, independently
// of the picture:
//  - anchor: the right markers must sit 16 px inside the border's right side and the bottom
//    markers 16 px inside its bottom (within 1 px; the distances are measured at rest first).
//    A frame where they are not is "shifted by N px": the picture slid, as when the rectangle
//    and the picture of a step reach DWM in different compositions and the origin moved.
//  - see-through: no backdrop green anywhere inside the border (sampled on 14 rows and 14
//    columns), and the size the picture spells must be the size inside the border; otherwise
//    "see-through N px at the <edge>" or "frame of another size".
// On a build without the border colour these judges print "not available on this build" and
// judge nothing; the exit status does not change. Their modes exit 0 unless the capture
// failed, as drag does.
// PROBE_DRAW_MS=<n> makes every frame take n ms longer, for a renderer that is slow to draw.
// PROBE_RASTER=0 draws resize frames on the GPU (the window's DXGI path) instead of the CPU.
// PROBE_SAVE=<dir> writes the first faulty frames there as BMP files.
// PROBE_SAVE_PHASE=<phase> (with PROBE_SAVE) also writes every composed frame of that phase,
// faulty or not, as phase-NNN-<phase>.bmp, the first PROBE_SAVE_LIMIT (default 16) of them.
// PROBE_VERBOSE=1 prints every composed frame judged, not only the faulty ones.
// DXC_DCOMP=1 (read by the window) opens it on DirectComposition instead of the GDI route;
// the window says which route it took ("compose-rust: dcomp: route"), and the memory lines
// the probe asks for at rest and at the end carry it too ("route=").
// Exit status: 0 when no composed frame was faulty (states) or always (the drag modes, unless
// the capture failed), 1 otherwise.
#define COBJMACROS
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d12.h>
#include <dxgi1_2.h>
#include <dwmapi.h>

#include "win32_window.h"
#include "win32_probe_judge.h"

static struct dxc_native_window probe_window;
static int probe_draw_ms;
static LARGE_INTEGER probe_frequency;

static double probe_now_ms(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return (double)now.QuadPart * 1000.0 / (double)probe_frequency.QuadPart;
}

// The picture: grey, with a 4 px magenta strip at the right and at the bottom (PROBE_STRIP).
// The modes judged by the border (probe_marked) draw the marked picture of
// win32_probe_judge.h instead: the same, with the four markers and the size code.
static int probe_marked;
static void probe_fill(uint8_t *pixels, int32_t row_bytes, int32_t width, int32_t height) {
    if (probe_marked) {
        probe_fill_marked(pixels, row_bytes, width, height);
        return;
    }
    for (int32_t y = 0; y < height; y++) {
        uint32_t *row = (uint32_t *)(pixels + (size_t)y * (size_t)row_bytes);
        for (int32_t x = 0; x < width; x++) {
            int strip = x >= width - PROBE_STRIP || y >= height - PROBE_STRIP;
            // BGRA in memory.
            row[x] = strip ? 0xFFFF00FFu : 0xFF505050u;
        }
    }
}

// The GPU frame: the same picture, copied into the window's draw texture from an upload buffer.
static ID3D12CommandAllocator *gpu_allocator;
static ID3D12GraphicsCommandList *gpu_list;
static ID3D12Resource *gpu_upload;
static uint8_t *gpu_upload_bits;
static ID3D12Fence *gpu_fence;
static UINT64 gpu_fence_value;
static HANDLE gpu_event;
static size_t gpu_upload_size;
static int gpu_broken;

static int gpu_setup(ID3D12Device *device) {
    if (FAILED(ID3D12Device_CreateCommandAllocator(device, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                                   &IID_ID3D12CommandAllocator, (void **)&gpu_allocator)) ||
        FAILED(ID3D12Device_CreateCommandList(device, 0, D3D12_COMMAND_LIST_TYPE_DIRECT, gpu_allocator, NULL,
                                              &IID_ID3D12GraphicsCommandList, (void **)&gpu_list)) ||
        FAILED(ID3D12GraphicsCommandList_Close(gpu_list)) ||
        FAILED(ID3D12Device_CreateFence(device, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence, (void **)&gpu_fence))) {
        return 1;
    }
    gpu_event = CreateEventW(NULL, FALSE, FALSE, NULL);
    int screen_width = GetSystemMetrics(SM_CXVIRTUALSCREEN) + 64;
    int screen_height = GetSystemMetrics(SM_CYVIRTUALSCREEN) + 64;
    size_t pitch = ((size_t)screen_width * 4 + 255) / 256 * 256;
    gpu_upload_size = pitch * (size_t)screen_height;
    D3D12_HEAP_PROPERTIES heap;
    memset(&heap, 0, sizeof heap);
    heap.Type = D3D12_HEAP_TYPE_UPLOAD;
    D3D12_RESOURCE_DESC description;
    memset(&description, 0, sizeof description);
    description.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    description.Width = gpu_upload_size;
    description.Height = 1;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.Format = DXGI_FORMAT_UNKNOWN;
    description.SampleDesc.Count = 1;
    description.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    if (FAILED(ID3D12Device_CreateCommittedResource(device, &heap, D3D12_HEAP_FLAG_NONE, &description,
                                                    D3D12_RESOURCE_STATE_GENERIC_READ, NULL, &IID_ID3D12Resource,
                                                    (void **)&gpu_upload))) {
        return 1;
    }
    D3D12_RANGE none = {0, 0};
    if (FAILED(ID3D12Resource_Map(gpu_upload, 0, &none, (void **)&gpu_upload_bits))) {
        return 1;
    }
    return 0;
}

static void gpu_barrier(ID3D12Resource *texture, D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after) {
    D3D12_RESOURCE_BARRIER barrier;
    memset(&barrier, 0, sizeof barrier);
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition.pResource = texture;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = before;
    barrier.Transition.StateAfter = after;
    ID3D12GraphicsCommandList_ResourceBarrier(gpu_list, 1, &barrier);
}

static void gpu_paint(ID3D12Resource *texture, int32_t width, int32_t height) {
    if (gpu_broken || width <= 0 || height <= 0) return;
    UINT pitch = (UINT)(((size_t)width * 4 + 255) / 256 * 256);
    if ((size_t)pitch * (size_t)height > gpu_upload_size) return;
    probe_fill(gpu_upload_bits, (int32_t)pitch, width, height);
    ID3D12CommandAllocator_Reset(gpu_allocator);
    ID3D12GraphicsCommandList_Reset(gpu_list, gpu_allocator, NULL);
    // The window keeps its draw texture in the present state between frames, and its frame
    // end moves it from render target to present.
    gpu_barrier(texture, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_DEST);
    D3D12_TEXTURE_COPY_LOCATION to;
    memset(&to, 0, sizeof to);
    to.pResource = texture;
    to.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    to.SubresourceIndex = 0;
    D3D12_TEXTURE_COPY_LOCATION from;
    memset(&from, 0, sizeof from);
    from.pResource = gpu_upload;
    from.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    from.PlacedFootprint.Offset = 0;
    from.PlacedFootprint.Footprint.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    from.PlacedFootprint.Footprint.Width = (UINT)width;
    from.PlacedFootprint.Footprint.Height = (UINT)height;
    from.PlacedFootprint.Footprint.Depth = 1;
    from.PlacedFootprint.Footprint.RowPitch = pitch;
    ID3D12GraphicsCommandList_CopyTextureRegion(gpu_list, &to, 0, 0, 0, &from, NULL);
    gpu_barrier(texture, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_RENDER_TARGET);
    ID3D12GraphicsCommandList_Close(gpu_list);
    ID3D12CommandQueue *queue = (ID3D12CommandQueue *)probe_window.queue;
    ID3D12CommandList *lists[1] = {(ID3D12CommandList *)(void *)gpu_list};
    ID3D12CommandQueue_ExecuteCommandLists(queue, 1, lists);
    gpu_fence_value++;
    ID3D12CommandQueue_Signal(queue, gpu_fence, gpu_fence_value);
    if (ID3D12Fence_GetCompletedValue(gpu_fence) < gpu_fence_value &&
        SUCCEEDED(ID3D12Fence_SetEventOnCompletion(gpu_fence, gpu_fence_value, gpu_event))) {
        WaitForSingleObject(gpu_event, 2000);
    }
}

static int probe_frames_cpu;
static int probe_frames_gpu;

// The stand-in renderer, called by the window wherever it needs a frame.
static void probe_draw(void *unused) {
    (void)unused;
    if (probe_draw_ms > 0) Sleep((DWORD)probe_draw_ms);
    if (dxc_native_frame_mode() != 0) {
        void *pixels = NULL;
        int32_t row_bytes = 0, width = 0, height = 0;
        if (dxc_native_raster_begin(&pixels, &row_bytes, &width, &height) == 0) {
            probe_fill((uint8_t *)pixels, row_bytes, width, height);
            dxc_native_raster_end();
            probe_frames_cpu++;
        }
        return;
    }
    void *texture = NULL;
    if (dxc_native_frame_begin(probe_window.swapchain, &texture) != 0 || texture == NULL) return;
    int32_t width = 0, height = 0;
    float scale = 1.0f;
    dxc_native_window_size(probe_window.window, &width, &height, &scale);
    gpu_paint((ID3D12Resource *)texture, width, height);
    dxc_native_frame_end(probe_window.queue);
    probe_frames_gpu++;
}

// A full-screen green window behind the probe's window, so the window's edges can be found.
static HWND backdrop;
static HANDLE backdrop_ready;

static LRESULT CALLBACK backdrop_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_ERASEBKGND) {
        RECT all;
        GetClientRect(window, &all);
        HBRUSH green = CreateSolidBrush(RGB(0, 200, 0));
        FillRect((HDC)wparam, &all, green);
        DeleteObject(green);
        return 1;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

static DWORD WINAPI backdrop_thread(LPVOID unused) {
    (void)unused;
    WNDCLASSW type;
    memset(&type, 0, sizeof type);
    type.lpfnWndProc = backdrop_proc;
    type.hInstance = GetModuleHandleW(NULL);
    type.lpszClassName = L"ProbeBackdrop";
    RegisterClassW(&type);
    backdrop = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, L"ProbeBackdrop", L"backdrop", WS_POPUP,
                               GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN),
                               GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN),
                               NULL, NULL, type.hInstance, NULL);
    ShowWindow(backdrop, SW_SHOWNOACTIVATE);
    UpdateWindow(backdrop);
    SetEvent(backdrop_ready);
    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    return 0;
}

// What the driver is doing, for the frames the capture judges.
static volatile LONG probe_phase;
static const char *probe_phase_names[] = {"rest", "maximise", "restore", "left-half", "back", "drag",
                                          "setup", "drag-left", "drag-top", "drag-topleft", "drag-fast",
                                          "drag-right", "drag-bottom", "drag-bottomright", "drag-topright",
                                          "drag-bottomleft", "drag-mixed", "drag-slow", "done"};
enum {
    PHASE_REST, PHASE_MAXIMISE, PHASE_RESTORE, PHASE_LEFT_HALF, PHASE_BACK, PHASE_DRAG,
    // The judged drag modes: setup places the window and calibrates, and is not judged.
    PHASE_SETUP, PHASE_DRAG_LEFT, PHASE_DRAG_TOP, PHASE_DRAG_TOPLEFT, PHASE_DRAG_FAST,
    // Added after Fable's drag review (2026-11-22): the right and bottom edges going in and
    // out, the two corners that move one side of each axis, a bottom-right drag that grows
    // one side while it shrinks the other, and a slow drag of 1 to 3 px a step.
    PHASE_DRAG_RIGHT, PHASE_DRAG_BOTTOM, PHASE_DRAG_BOTTOMRIGHT, PHASE_DRAG_TOPRIGHT, PHASE_DRAG_BOTTOMLEFT,
    PHASE_DRAG_MIXED, PHASE_DRAG_SLOW,
    PHASE_DONE
};
static volatile LONG probe_capture_stop;
static RECT probe_work;

// Capture: every composed frame, one row and one column of it read back.
static int capture_frames, capture_faulty, capture_missed, capture_saved, capture_failed;
static int capture_faulty_by_phase[PHASE_DONE + 1];
static int capture_frames_by_phase[PHASE_DONE + 1];
static const char *capture_save_dir;
static int capture_verbose;
static int capture_save_phase = -1, capture_save_limit = 16, capture_phase_saved;

static int is_backdrop(const uint8_t *bgra) {
    int b = bgra[0], g = bgra[1], r = bgra[2];
    // Green, or green darkened by the window's shadow.
    return g > r + 30 && g > b + 30;
}

static int is_strip(const uint8_t *bgra) {
    return bgra[2] > 200 && bgra[1] < 70 && bgra[0] > 200;
}

static void save_bmp(const char *path, const uint8_t *bits, UINT pitch, UINT width, UINT height) {
    FILE *file = fopen(path, "wb");
    if (file == NULL) return;
    BITMAPFILEHEADER header;
    BITMAPINFOHEADER info;
    memset(&header, 0, sizeof header);
    memset(&info, 0, sizeof info);
    header.bfType = 0x4D42;
    header.bfOffBits = sizeof header + sizeof info;
    header.bfSize = header.bfOffBits + width * height * 4;
    info.biSize = sizeof info;
    info.biWidth = (LONG)width;
    info.biHeight = -(LONG)height;
    info.biPlanes = 1;
    info.biBitCount = 32;
    info.biCompression = BI_RGB;
    fwrite(&header, sizeof header, 1, file);
    fwrite(&info, sizeof info, 1, file);
    for (UINT y = 0; y < height; y++) fwrite(bits + (size_t)y * pitch, 4, width, file);
    fclose(file);
}

// The judges of the drag modes that move the window's origin (win32_probe_judge.h), against
// the border DWM draws in PROBE_BORDER_COLORREF. Written by the capture thread only, except
// where said.
static int judge_mode;  // the mode is one of them (main)
static int judge_available;  // the border colour took (main)
static char judge_unavailable[200];  // why not (main)
static RECT judge_area;  // the screen area read back from each frame: the work area (main)
static volatile LONG judge_calibrate;  // 1: calibrate on the next frame at rest (the driver)
static volatile LONG judge_calibrated;  // 1 done; 0 pending or failed (the driver sets 0)
static RECT judge_rest_client;  // the client area on screen at rest (the driver)
static struct probe_calibration judge_calibration;
static int judge_frames, judge_no_border, judge_uncalibrated, judge_lines;
static int anchor_faulty, anchor_missing, anchor_cut, anchor_max_shift;
// Faulty frames by the size of their shift: 2-4, 5-16, 17-64, over 64 px, and only missing.
static int anchor_by_size[5];
static int anchor_worst_shift[5], anchor_worst_frame[5];
static int see_faulty, see_strips, see_other_size, see_unreadable, see_max_px, see_max_edge;
static LONG desk_left, desk_top;  // the captured output's place on the virtual screen
// Where DXGI last said the pointer's shape is drawn (its top left, on the output), and
// whether it is shown: a capture without a hardware cursor may carry it in the frame, over a
// marker at the edge being dragged. Frames where it covers a marker or the size code leave
// those out (win32_probe_judge.h) and count them here.
static int judge_pointer_shown;
static LONG judge_pointer_x, judge_pointer_y;
// The places DXGI gave before that one. A composed frame can carry the pointer where it was a
// move or two ago, 40 to 120 px away at this pace: run 44920692034 read a size code as
// unreadable with the pointer's arrow drawn over it and DXGI's latest place elsewhere. So what
// counts as under the pointer is the box around its last JUDGE_POINTER_TRAIL places.
#define JUDGE_POINTER_TRAIL 4
static LONG judge_pointer_trail_x[JUDGE_POINTER_TRAIL], judge_pointer_trail_y[JUDGE_POINTER_TRAIL];
static int judge_pointer_trail_count;
static int judge_covered_marks, judge_covered_codes;

static void judge_line(const char *format, ...) {
    // Enough to read the first faults of a run, not a log of every frame of every drag.
    if (judge_lines >= 300) {
        if (judge_lines == 300) fprintf(stderr, "probe: further judge lines are not printed\n");
        judge_lines++;
        return;
    }
    judge_lines++;
    va_list arguments;
    va_start(arguments, format);
    vfprintf(stderr, format, arguments);
    va_end(arguments);
}

/** Calibrates on an image of the window at rest (the driver asked); one when it took. */
static int judge_calibrate_on(const struct probe_image *image, LONG area_left, LONG area_top, int presented) {
    int32_t width = (int32_t)(judge_rest_client.right - judge_rest_client.left);
    int32_t height = (int32_t)(judge_rest_client.bottom - judge_rest_client.top);
    struct probe_calibration calibration;
    if (probe_calibrate(image, width, height, &calibration)) {
        struct probe_border border;
        probe_find_border(image, 2, &border);
        judge_calibration = calibration;
        fprintf(stderr,
                "probe: calibrated at rest: border %ld,%ld-%ld,%ld (inside %ld,%ld-%ld,%ld), client %ld,%ld-%ld,%ld; "
                "right markers %d and %d px inside the border, bottom markers %d and %d px; picture %dx%d is the "
                "inside %+d x %+d px\n",
                (long)(area_left + border.left), (long)(area_top + border.top), (long)(area_left + border.right),
                (long)(area_top + border.bottom), (long)(area_left + border.inner.left),
                (long)(area_top + border.inner.top), (long)(area_left + border.inner.right),
                (long)(area_top + border.inner.bottom), judge_rest_client.left, judge_rest_client.top,
                judge_rest_client.right, judge_rest_client.bottom, (int)calibration.top_right_x,
                (int)calibration.bottom_right_x, (int)calibration.bottom_left_y, (int)calibration.bottom_right_y,
                (int)calibration.picture_width, (int)calibration.picture_height, (int)calibration.extra_width,
                (int)calibration.extra_height);
        InterlockedExchange(&judge_calibrated, 1);
        InterlockedExchange(&judge_calibrate, 0);
        return 1;
    }
    // A frame where only the pointer moved may not carry the desktop's pixels; wait for one
    // that was presented before saying why.
    if (presented) {
        struct probe_border border;
        if (!probe_find_border(image, 2, &border)) {
            judge_line("probe: calibration: no border in the border colour found in %ldx%ld px at %ld,%ld\n",
                       (long)image->width, (long)image->height, (long)area_left, (long)area_top);
        } else {
            judge_line("probe: calibration: border %ld,%ld-%ld,%ld, but the picture there reads %dx%d for a "
                       "client of %dx%d\n",
                       (long)(area_left + border.left), (long)(area_top + border.top),
                       (long)(area_left + border.right), (long)(area_top + border.bottom),
                       (int)calibration.picture_width, (int)calibration.picture_height, (int)width, (int)height);
        }
    }
    return 0;
}

static void judge_worst(int shift, int frame) {
    // The five largest shifts, largest first.
    for (int i = 0; i < 5; i++) {
        if (anchor_worst_frame[i] == 0 || shift > anchor_worst_shift[i]) {
            for (int j = 4; j > i; j--) {
                anchor_worst_shift[j] = anchor_worst_shift[j - 1];
                anchor_worst_frame[j] = anchor_worst_frame[j - 1];
            }
            anchor_worst_shift[i] = shift;
            anchor_worst_frame[i] = frame;
            return;
        }
    }
}

/**
 * Judges one composed frame of a judged drag: anchoring and see-through. Writes the frame
 * under PROBE_SAVE (the area read, as BMP) while fewer than 16 are saved.
 */
static void judge_frame_on(const struct probe_image *image, LONG area_left, LONG area_top, LONG judged_phase,
                           int frame, double present_ms) {
    // The pointer's shape as a box in the image, with room for a large or scaled cursor.
    struct probe_box pointer = {0, 0, 0, 0};
    if (judge_pointer_shown) {
        pointer.left = (int32_t)(desk_left + judge_pointer_x - area_left) - 8;
        pointer.top = (int32_t)(desk_top + judge_pointer_y - area_top) - 8;
        pointer.right = pointer.left + 112;
        pointer.bottom = pointer.top + 112;
        // And where it was drawn the moves before (see judge_pointer_trail_x).
        for (int past = 0; past < judge_pointer_trail_count; past++) {
            int32_t left = (int32_t)(desk_left + judge_pointer_trail_x[past] - area_left) - 8;
            int32_t top = (int32_t)(desk_top + judge_pointer_trail_y[past] - area_top) - 8;
            if (left < pointer.left) pointer.left = left;
            if (top < pointer.top) pointer.top = top;
            if (left + 112 > pointer.right) pointer.right = left + 112;
            if (top + 112 > pointer.bottom) pointer.bottom = top + 112;
        }
    }
    judge_frames++;
    if (judge_calibrated != 1) {
        judge_uncalibrated++;
        return;
    }
    struct probe_border border;
    if (!probe_find_border(image, 4, &border)) {
        judge_no_border++;
        return;
    }
    struct probe_anchor anchor;
    struct probe_see see;
    probe_judge_anchor(image, &border, &judge_calibration, pointer, &anchor);
    probe_judge_see(image, &border, &judge_calibration, pointer, &see);
    judge_covered_marks += anchor.covered;
    judge_covered_codes += see.covered;
    const char *name = probe_phase_names[judged_phase];
    int32_t inner_width = border.inner.right - border.inner.left;
    int32_t inner_height = border.inner.bottom - border.inner.top;
    if (anchor.faulty) {
        int shift = anchor.shift < 0 ? -anchor.shift : anchor.shift;
        anchor_faulty++;
        if (anchor.missing) anchor_missing++;
        if (anchor.cut) anchor_cut++;
        if (shift > anchor_max_shift) anchor_max_shift = shift;
        anchor_by_size[shift > 64 ? 3 : shift > 16 ? 2 : shift > 4 ? 1 : shift > PROBE_ANCHOR_TOLERANCE ? 0 : 4]++;
        judge_worst(shift, frame);
        judge_line("probe: ANCHOR frame %d at %.1f ms phase %s: shifted by %d px (%s marker), %d markers missing, "
                   "%d cut; border %ld,%ld-%ld,%ld\n",
                   frame, present_ms, name, (int)anchor.shift,
                   anchor.worst >= 0 ? probe_mark_names[anchor.worst] : "no", anchor.missing, anchor.cut,
                   (long)(area_left + border.left), (long)(area_top + border.top), (long)(area_left + border.right),
                   (long)(area_top + border.bottom));
    }
    if (see.faulty) {
        see_faulty++;
        if (see.total > 0) {
            see_strips++;
            if (see.px > see_max_px) {
                see_max_px = see.px;
                see_max_edge = see.edge;
            }
            judge_line("probe: SEE-THROUGH frame %d at %.1f ms phase %s: see-through %d px at the %s (%d backdrop "
                       "px read), inside the border %dx%d\n",
                       frame, present_ms, name, (int)see.px, probe_edge_names[see.edge], (int)see.total,
                       (int)inner_width, (int)inner_height);
        }
        if (see.other_size) {
            see_other_size++;
            judge_line("probe: OTHER-SIZE frame %d at %.1f ms phase %s: frame of another size (picture %dx%d, "
                       "inside the border %dx%d)\n",
                       frame, present_ms, name, (int)see.picture_width, (int)see.picture_height, (int)inner_width,
                       (int)inner_height);
        }
        if (see.unreadable) {
            see_unreadable++;
            judge_line("probe: OTHER-SIZE frame %d at %.1f ms phase %s: the size code does not read (inside the "
                       "border %dx%d)\n",
                       frame, present_ms, name, (int)inner_width, (int)inner_height);
        }
    }
    if ((anchor.faulty || see.faulty) && capture_save_dir != NULL && capture_saved < 16) {
        char saved[MAX_PATH];
        snprintf(saved, sizeof saved, "%s\\judge-%03d-%s-%s.bmp", capture_save_dir, capture_saved, name,
                 anchor.faulty ? "anchor" : "see-through");
        save_bmp(saved, image->bits, (UINT)image->pitch, (UINT)image->width, (UINT)image->height);
        capture_saved++;
        judge_line("probe: saved %s (the area read, from %ld,%ld)\n", saved, (long)area_left, (long)area_top);
    }
}

/**
 * Copies the judged area out of a captured frame and calibrates on it (when asked, at rest)
 * or judges it (in a judged drag).
 */
static void judge_capture(ID3D11DeviceContext *context, ID3D11Texture2D *area_copy, const D3D11_BOX *box,
                          IDXGIResource *resource, LONG judged_phase, int presented, int frame, double present_ms) {
    int calibrating = judge_calibrate == 1 && judged_phase == PHASE_SETUP;
    int judging = presented && judged_phase >= PHASE_DRAG_LEFT && judged_phase <= PHASE_DRAG_SLOW;
    if ((!calibrating && !judging) || resource == NULL) return;
    ID3D11Texture2D *texture = NULL;
    if (FAILED(IDXGIResource_QueryInterface(resource, &IID_ID3D11Texture2D, (void **)&texture)) || texture == NULL) {
        return;
    }
    ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)area_copy, 0, 0, 0, 0,
                                              (ID3D11Resource *)texture, 0, box);
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)area_copy, 0, D3D11_MAP_READ, 0, &mapped))) {
        struct probe_image image;
        image.bits = (const uint8_t *)mapped.pData;
        image.pitch = (int32_t)mapped.RowPitch;
        image.width = (int32_t)(box->right - box->left);
        image.height = (int32_t)(box->bottom - box->top);
        LONG area_left = desk_left + (LONG)box->left, area_top = desk_top + (LONG)box->top;
        if (calibrating) {
            judge_calibrate_on(&image, area_left, area_top, presented);
        } else {
            judge_frame_on(&image, area_left, area_top, judged_phase, frame, present_ms);
        }
        ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)area_copy, 0);
    }
    ID3D11Texture2D_Release(texture);
}

static DWORD WINAPI capture_thread(LPVOID unused) {
    (void)unused;
    IDXGIFactory1 *factory = NULL;
    IDXGIAdapter1 *adapter = NULL;
    IDXGIOutput *output = NULL;
    IDXGIOutput1 *output1 = NULL;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    IDXGIOutputDuplication *duplication = NULL;
    if (FAILED(CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory)) ||
        FAILED(IDXGIFactory1_EnumAdapters1(factory, 0, &adapter)) ||
        FAILED(IDXGIAdapter1_EnumOutputs(adapter, 0, &output)) ||
        FAILED(IDXGIOutput_QueryInterface(output, &IID_IDXGIOutput1, (void **)&output1)) ||
        FAILED(D3D11CreateDevice((IDXGIAdapter *)adapter, D3D_DRIVER_TYPE_UNKNOWN, NULL, 0, NULL, 0,
                                 D3D11_SDK_VERSION, &device, NULL, &context))) {
        fprintf(stderr, "probe: capture could not start (no adapter, output or device)\n");
        capture_failed = 1;
        return 1;
    }
    HRESULT duplicated = IDXGIOutput1_DuplicateOutput(output1, (IUnknown *)device, &duplication);
    if (FAILED(duplicated)) {
        fprintf(stderr, "probe: DuplicateOutput failed 0x%08lx\n", (unsigned long)duplicated);
        capture_failed = 1;
        return 1;
    }
    DXGI_OUTPUT_DESC output_desc;
    IDXGIOutput_GetDesc(output, &output_desc);
    UINT screen_width = (UINT)(output_desc.DesktopCoordinates.right - output_desc.DesktopCoordinates.left);
    UINT screen_height = (UINT)(output_desc.DesktopCoordinates.bottom - output_desc.DesktopCoordinates.top);
    fprintf(stderr, "probe: capturing %ux%u\n", screen_width, screen_height);
    // The row and the column the edges are looked for on, copied out of each frame.
    const UINT row_y = 100, column_x = 100;
    D3D11_TEXTURE2D_DESC staging_desc;
    memset(&staging_desc, 0, sizeof staging_desc);
    staging_desc.MipLevels = 1;
    staging_desc.ArraySize = 1;
    staging_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    staging_desc.SampleDesc.Count = 1;
    staging_desc.Usage = D3D11_USAGE_STAGING;
    staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    ID3D11Texture2D *row_copy = NULL, *column_copy = NULL, *full_copy = NULL;
    staging_desc.Width = screen_width;
    staging_desc.Height = 1;
    ID3D11Device_CreateTexture2D(device, &staging_desc, NULL, &row_copy);
    staging_desc.Width = 1;
    staging_desc.Height = screen_height;
    ID3D11Device_CreateTexture2D(device, &staging_desc, NULL, &column_copy);
    staging_desc.Width = screen_width;
    staging_desc.Height = screen_height;
    ID3D11Device_CreateTexture2D(device, &staging_desc, NULL, &full_copy);
    if (row_copy == NULL || column_copy == NULL || full_copy == NULL) {
        fprintf(stderr, "probe: capture could not make its staging textures\n");
        capture_failed = 1;
        return 1;
    }
    // The judged modes read the work area of each frame (judge_frame_on), the part of the
    // output it covers.
    desk_left = output_desc.DesktopCoordinates.left;
    desk_top = output_desc.DesktopCoordinates.top;
    D3D11_BOX area_box = {0, 0, 0, 0, 0, 1};
    ID3D11Texture2D *area_copy = NULL;
    if (judge_mode && judge_available) {
        LONG left = judge_area.left - desk_left, top = judge_area.top - desk_top;
        LONG right = judge_area.right - desk_left, bottom = judge_area.bottom - desk_top;
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (right > (LONG)screen_width) right = (LONG)screen_width;
        if (bottom > (LONG)screen_height) bottom = (LONG)screen_height;
        if (right - left >= 64 && bottom - top >= 64) {
            area_box.left = (UINT)left;
            area_box.top = (UINT)top;
            area_box.right = (UINT)right;
            area_box.bottom = (UINT)bottom;
            staging_desc.Width = area_box.right - area_box.left;
            staging_desc.Height = area_box.bottom - area_box.top;
            ID3D11Device_CreateTexture2D(device, &staging_desc, NULL, &area_copy);
        }
        if (area_copy == NULL) {
            snprintf(judge_unavailable, sizeof judge_unavailable,
                     "the work area %ld,%ld-%ld,%ld is not on the captured output, or no staging texture",
                     judge_area.left, judge_area.top, judge_area.right, judge_area.bottom);
            judge_available = 0;
        } else {
            fprintf(stderr, "probe: judges read %ux%u px of each frame at %ld,%ld\n",
                    area_box.right - area_box.left, area_box.bottom - area_box.top, desk_left + (LONG)area_box.left,
                    desk_top + (LONG)area_box.top);
        }
    }
    uint8_t *row = (uint8_t *)malloc((size_t)screen_width * 4);
    uint8_t *column = (uint8_t *)malloc((size_t)screen_height * 4);
    while (!probe_capture_stop) {
        DXGI_OUTDUPL_FRAME_INFO info;
        IDXGIResource *resource = NULL;
        HRESULT got = IDXGIOutputDuplication_AcquireNextFrame(duplication, 50, &info, &resource);
        if (got == DXGI_ERROR_WAIT_TIMEOUT) continue;
        if (FAILED(got)) {
            fprintf(stderr, "probe: AcquireNextFrame failed 0x%08lx\n", (unsigned long)got);
            capture_failed = 1;
            break;
        }
        LONG phase = probe_phase;
        if (info.LastMouseUpdateTime.QuadPart != 0) {
            if (judge_pointer_shown &&
                (judge_pointer_x != info.PointerPosition.Position.x || judge_pointer_y != info.PointerPosition.Position.y)) {
                for (int past = JUDGE_POINTER_TRAIL - 1; past > 0; past--) {
                    judge_pointer_trail_x[past] = judge_pointer_trail_x[past - 1];
                    judge_pointer_trail_y[past] = judge_pointer_trail_y[past - 1];
                }
                judge_pointer_trail_x[0] = judge_pointer_x;
                judge_pointer_trail_y[0] = judge_pointer_y;
                if (judge_pointer_trail_count < JUDGE_POINTER_TRAIL) judge_pointer_trail_count++;
            }
            judge_pointer_shown = info.PointerPosition.Visible ? 1 : 0;
            judge_pointer_x = info.PointerPosition.Position.x;
            judge_pointer_y = info.PointerPosition.Position.y;
        }
        if (info.LastPresentTime.QuadPart == 0) {
            // Only the pointer moved. A calibration at rest may still read the desktop from it.
            if (area_copy != NULL) judge_capture(context, area_copy, &area_box, resource, phase, 0, 0, 0.0);
            IDXGIResource_Release(resource);
            IDXGIOutputDuplication_ReleaseFrame(duplication);
            continue;
        }
        if (info.AccumulatedFrames > 1) capture_missed += (int)info.AccumulatedFrames - 1;
        ID3D11Texture2D *frame = NULL;
        IDXGIResource_QueryInterface(resource, &IID_ID3D11Texture2D, (void **)&frame);
        D3D11_BOX row_box = {0, row_y, 0, screen_width, row_y + 1, 1};
        D3D11_BOX column_box = {column_x, 0, 0, column_x + 1, screen_height, 1};
        ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)row_copy, 0, 0, 0, 0,
                                                  (ID3D11Resource *)frame, 0, &row_box);
        ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)column_copy, 0, 0, 0, 0,
                                                  (ID3D11Resource *)frame, 0, &column_box);
        D3D11_MAPPED_SUBRESOURCE mapped;
        if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)row_copy, 0, D3D11_MAP_READ, 0, &mapped))) {
            memcpy(row, mapped.pData, (size_t)screen_width * 4);
            ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)row_copy, 0);
        }
        if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)column_copy, 0, D3D11_MAP_READ, 0, &mapped))) {
            for (UINT y = 0; y < screen_height; y++) {
                memcpy(column + (size_t)y * 4, (const uint8_t *)mapped.pData + (size_t)y * mapped.RowPitch, 4);
            }
            ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)column_copy, 0);
        }
        // The window's visible right edge on the row, and its bottom edge on the column (above
        // the taskbar), and whether the strips are there.
        int right = -1, bottom = -1;
        for (int x = (int)screen_width - 1; x >= 0; x--) {
            if (!is_backdrop(row + (size_t)x * 4)) { right = x; break; }
        }
        int bottom_from = probe_work.bottom > 0 && probe_work.bottom <= (LONG)screen_height
                              ? (int)probe_work.bottom - 1 : (int)screen_height - 1;
        for (int y = bottom_from; y >= 0; y--) {
            if (!is_backdrop(column + (size_t)y * 4)) { bottom = y; break; }
        }
        int right_strip = 0, bottom_strip = 0;
        for (int x = right; x >= 0 && x > right - 10; x--) {
            if (is_strip(row + (size_t)x * 4)) right_strip = 1;
        }
        for (int y = bottom; y >= 0 && y > bottom - 10; y--) {
            if (is_strip(column + (size_t)y * 4)) bottom_strip = 1;
        }
        capture_frames++;
        capture_frames_by_phase[phase]++;
        int faulty = right >= 0 && bottom >= 0 && (!right_strip || !bottom_strip);
        // Backdrop green left of the visible right edge on the row: a window that is drawn
        // across its whole rectangle has none; a picture smaller than its rectangle, with the
        // rest transparent, shows the backdrop there.
        int green_inside = 0;
        for (int x = 0; x < right; x++) {
            if (is_backdrop(row + (size_t)x * 4)) green_inside++;
        }
        if (phase == capture_save_phase && capture_save_dir != NULL && capture_phase_saved < capture_save_limit) {
            ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)full_copy, (ID3D11Resource *)frame);
            D3D11_MAPPED_SUBRESOURCE whole;
            if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)full_copy, 0, D3D11_MAP_READ, 0, &whole))) {
                char kept[MAX_PATH];
                snprintf(kept, sizeof kept, "%s\\phase-%03d-%s.bmp", capture_save_dir, capture_phase_saved,
                         probe_phase_names[phase]);
                save_bmp(kept, (const uint8_t *)whole.pData, whole.RowPitch, screen_width, screen_height);
                ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)full_copy, 0);
                fprintf(stderr, "probe: saved frame %d as %s\n", capture_frames, kept);
                capture_phase_saved++;
            }
        }
        if (faulty) {
            capture_faulty++;
            capture_faulty_by_phase[phase]++;
            char saved[MAX_PATH] = "";
            if (capture_save_dir != NULL && capture_saved < 16) {
                ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)full_copy, (ID3D11Resource *)frame);
                if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)full_copy, 0, D3D11_MAP_READ, 0, &mapped))) {
                    snprintf(saved, sizeof saved, "%s\\fault-%03d-%s.bmp", capture_save_dir, capture_saved,
                             probe_phase_names[phase]);
                    save_bmp(saved, (const uint8_t *)mapped.pData, mapped.RowPitch, screen_width, screen_height);
                    ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)full_copy, 0);
                    capture_saved++;
                }
            }
            fprintf(stderr,
                    "probe: FAULT frame %d presented at %.1f ms phase %s: window right edge x=%d %s, bottom edge y=%d %s %s\n",
                    capture_frames,
                    (double)info.LastPresentTime.QuadPart * 1000.0 / (double)probe_frequency.QuadPart,
                    probe_phase_names[phase], right,
                    right_strip ? "with its strip" : "WITHOUT its strip", bottom,
                    bottom_strip ? "with its strip" : "WITHOUT its strip", saved);
        } else if (capture_verbose) {
            fprintf(stderr,
                    "probe: frame %d presented at %.1f ms phase %s: right x=%d, bottom y=%d, both strips, "
                    "green inside on the row %d px\n",
                    capture_frames,
                    (double)info.LastPresentTime.QuadPart * 1000.0 / (double)probe_frequency.QuadPart,
                    probe_phase_names[phase], right, bottom, green_inside);
        }
        if (area_copy != NULL) {
            judge_capture(context, area_copy, &area_box, resource, phase, 1, capture_frames,
                          (double)info.LastPresentTime.QuadPart * 1000.0 / (double)probe_frequency.QuadPart);
        }
        if (frame != NULL) ID3D11Texture2D_Release(frame);
        IDXGIResource_Release(resource);
        IDXGIOutputDuplication_ReleaseFrame(duplication);
    }
    free(row);
    free(column);
    return 0;
}

// The driver: what a user or a window manager does to the window, from another thread.
static volatile LONG probe_driver_done;
static int drag_moves, drag_lag_max, drag_hold_max_ms;
static int drag_lags[4096];

static void send_mouse(DWORD flags, int x, int y) {
    INPUT input;
    memset(&input, 0, sizeof input);
    input.type = INPUT_MOUSE;
    int width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    int height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    int left = GetSystemMetrics(SM_XVIRTUALSCREEN);
    int top = GetSystemMetrics(SM_YVIRTUALSCREEN);
    input.mi.dx = (LONG)(((long long)(x - left) * 65535 + width / 2) / (width - 1));
    input.mi.dy = (LONG)(((long long)(y - top) * 65535 + height / 2) / (height - 1));
    input.mi.dwFlags = flags | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    SendInput(1, &input, sizeof input);
}

static void phase(LONG which) {
    InterlockedExchange(&probe_phase, which);
    fprintf(stderr, "probe: phase %s at %.1f ms\n", probe_phase_names[which], probe_now_ms());
}

static DWORD WINAPI states_thread(LPVOID unused) {
    (void)unused;
    HWND window = (HWND)probe_window.window;
    int half = (int)(probe_work.right - probe_work.left) / 2;
    int tall = (int)(probe_work.bottom - probe_work.top);
    for (int round = 0; round < 3; round++) {
        phase(PHASE_MAXIMISE);
        ShowWindow(window, SW_MAXIMIZE);
        Sleep(700);
        phase(PHASE_RESTORE);
        ShowWindow(window, SW_RESTORE);
        Sleep(700);
        phase(PHASE_LEFT_HALF);
        SetWindowPos(window, NULL, probe_work.left, probe_work.top, half, tall, SWP_NOZORDER | SWP_NOACTIVATE);
        Sleep(700);
        phase(PHASE_BACK);
        SetWindowPos(window, NULL, probe_work.left, probe_work.top, 640, 480, SWP_NOZORDER | SWP_NOACTIVATE);
        Sleep(700);
    }
    phase(PHASE_DONE);
    InterlockedExchange(&probe_driver_done, 1);
    return 0;
}

static int compare_ints(const void *a, const void *b) {
    return *(const int *)a - *(const int *)b;
}

static DWORD WINAPI drag_thread(LPVOID unused) {
    (void)unused;
    HWND window = (HWND)probe_window.window;
    RECT outer;
    GetWindowRect(window, &outer);
    int x = outer.right - 3;
    int y = (outer.top + outer.bottom) / 2;
    int travel = (int)(probe_work.right - outer.right) - 40;
    if (travel > 320) travel = 320;
    send_mouse(0, x, y);
    Sleep(200);
    phase(PHASE_DRAG);
    send_mouse(MOUSEEVENTF_LEFTDOWN, x, y);
    Sleep(100);
    int last_right = outer.right;
    double last_change = probe_now_ms();
    int steps = travel / 8;
    for (int i = 1; i <= 2 * steps; i++) {
        int moved = i <= steps ? i * 8 : (2 * steps - i) * 8;
        send_mouse(0, x + moved, y);
        Sleep(16);
        // Where the edge is when the next move is about to be sent.
        GetWindowRect(window, &outer);
        double now = probe_now_ms();
        int lag = (x + moved + 3) - outer.right;
        if (lag < 0) lag = -lag;
        if (drag_moves < (int)(sizeof drag_lags / sizeof drag_lags[0])) drag_lags[drag_moves++] = lag;
        if (lag > drag_lag_max) drag_lag_max = lag;
        if (outer.right != last_right) {
            last_right = outer.right;
            last_change = now;
        } else if (lag > 0 && (int)(now - last_change) > drag_hold_max_ms) {
            drag_hold_max_ms = (int)(now - last_change);
        }
    }
    Sleep(100);
    send_mouse(MOUSEEVENTF_LEFTUP, x, y);
    Sleep(500);
    phase(PHASE_DONE);
    InterlockedExchange(&probe_driver_done, 1);
    return 0;
}

// The judged drag modes: an edge or a corner dragged at a real hand's pace. The owner's logs
// (2026-11-21) had 6 to 16 ms between steps and up to 59 to 76 px a step on a real GPU, so
// the pointer moves 40 to 120 px every 4 to 8 ms here, from a fixed seed.
#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif
static const char *probe_mode;
static HANDLE edge_timer;
static double edge_gaps[4096];
static int edge_gap_count, edge_step_low, edge_step_high;
// How far the window followed the pointer, over every drag of the mode: the pointer's travel
// and the dragged sides' travel along each moving axis, and each move's lag, the distance
// between where the pointer asked a dragged side to be and where that side was just before
// the next move (or 100 ms after the last).
static long follow_pointer_px, follow_side_px;
// Where each drag ended: the largest distance, over the mode's drags, between the dragged sides
// and where the pointer let go, once the window has settled. Waiting for a composition before
// each step (fix/926) merges fast moves, so the sides cover less of the pointer's path; they
// must still end where the pointer did.
static int settle_worst = -1;
static int follow_lags[8192];
static int follow_lag_count;

/** Waits [ms]: a high-resolution timer where there is one, then a spin for what is left. */
static void edge_wait_ms(double ms) {
    double until = probe_now_ms() + ms;
    if (edge_timer != NULL && ms > 1.0) {
        LARGE_INTEGER due;
        due.QuadPart = -(LONGLONG)((ms - 0.5) * 10000.0);
        if (SetWaitableTimer(edge_timer, &due, 0, NULL, NULL, FALSE)) WaitForSingleObject(edge_timer, 100);
    }
    while (probe_now_ms() < until) SwitchToThread();
}

static const char *edge_name(int gx, int gy) {
    if (gy < 0) return gx < 0 ? "top-left" : gx > 0 ? "top-right" : "top";
    if (gy > 0) return gx < 0 ? "bottom-left" : gx > 0 ? "bottom-right" : "bottom";
    return gx < 0 ? "left" : "right";
}

static int edge_hit(int gx, int gy) {
    if (gy < 0) return gx < 0 ? HTTOPLEFT : gx > 0 ? HTTOPRIGHT : HTTOP;
    if (gy > 0) return gx < 0 ? HTBOTTOMLEFT : gx > 0 ? HTBOTTOMRIGHT : HTBOTTOM;
    return gx < 0 ? HTLEFT : HTRIGHT;
}

/**
 * Asks the capture thread to calibrate on a frame of the window at rest, and makes one: a
 * pointer move and a corner of the backdrop repainted, so DWM composes a frame. Gives up
 * after 1.5 s; the drag after it is then not judged (counted as uncalibrated).
 */
static void edge_calibrate(HWND window, const char *name) {
    if (!judge_available) return;
    RECT client;
    POINT origin = {0, 0};
    GetClientRect(window, &client);
    ClientToScreen(window, &origin);
    judge_rest_client.left = origin.x;
    judge_rest_client.top = origin.y;
    judge_rest_client.right = origin.x + client.right;
    judge_rest_client.bottom = origin.y + client.bottom;
    InterlockedExchange(&judge_calibrated, 0);
    InterlockedExchange(&judge_calibrate, 1);
    // The pointer out of the way, in the work area's top left corner, far from the window
    // (placed in the middle), so a capture that draws it does not draw it over a marker.
    double until = probe_now_ms() + 1500.0;
    for (int i = 0; judge_calibrated != 1 && probe_now_ms() < until; i++) {
        send_mouse(0, (int)probe_work.left + 2 + (i & 1), (int)probe_work.top + 2);
        RECT corner = {0, 0, 4, 4};
        RedrawWindow(backdrop, &corner, NULL, RDW_INVALIDATE | RDW_ERASE);
        Sleep(50);
    }
    InterlockedExchange(&judge_calibrate, 0);
    if (judge_calibrated != 1) {
        fprintf(stderr, "probe: %s: no calibration at rest within 1.5 s; this drag is not judged\n", name);
    }
}

/** The lag of a dragged side: where the pointer asked it to be against where it is. */
/** How far the dragged sides of [at] are from where the pointer asked them to be ([asked] px along the drag). */
static int side_lag(const RECT *at, const RECT *outer, int gx, int gy, int ysign, int32_t asked) {
    int lag = 0;
    if (gx != 0) {
        LONG want = (gx < 0 ? outer->left : outer->right) + gx * asked;
        LONG have = gx < 0 ? at->left : at->right;
        lag = (int)(want > have ? want - have : have - want);
    }
    if (gy != 0) {
        LONG want = (gy < 0 ? outer->top : outer->bottom) + gy * ysign * asked;
        LONG have = gy < 0 ? at->top : at->bottom;
        int y = (int)(want > have ? want - have : have - want);
        if (y > lag) lag = y;
    }
    return lag;
}

static void follow_lag(const RECT *at, const RECT *outer, int gx, int gy, int ysign, int32_t asked) {
    int lag = side_lag(at, outer, gx, gy, ysign, asked);
    if (follow_lag_count < (int)(sizeof follow_lags / sizeof follow_lags[0])) follow_lags[follow_lag_count++] = lag;
}

/**
 * One drag of the edge or corner the pointer moves along (gx, gy): -1 left or up, 1 right or
 * down, 0 not on that axis, so that moving the pointer by (gx, gy) * n grows the window by n.
 * [ysign] -1 turns the vertical axis round (a bottom-right drag whose width grows while its
 * height shrinks, and the other way), otherwise 1. [out_and_in]: out, in past where it
 * started, out, in, back; otherwise out and back twice (a fast grow). Each move is
 * [step_low] to [step_high] px. The window is placed in the middle of the work area first,
 * at about half its size, so every move stays within the work area and above the window's
 * minimum size.
 */
static void edge_drag(HWND window, struct probe_rng *rng, int gx, int gy, int ysign, int out_and_in,
                      int32_t step_low, int32_t step_high, int32_t travel, LONG drag_phase) {
    phase(PHASE_SETUP);
    const char *name = edge_name(gx, gy);
    int dpi = (int)GetDpiForWindow(window);
    if (dpi <= 0) dpi = 96;
    int work_width = (int)(probe_work.right - probe_work.left);
    int work_height = (int)(probe_work.bottom - probe_work.top);
    int width = MulDiv(560, dpi, 96), height = MulDiv(400, dpi, 96);
    if (width > work_width / 2) width = work_width / 2;
    if (height > work_height / 2) height = work_height / 2;
    SetWindowPos(window, NULL, (int)probe_work.left + (work_width - width) / 2,
                 (int)probe_work.top + (work_height - height) / 2, width, height, SWP_NOZORDER | SWP_NOACTIVATE);
    Sleep(600);
    RECT outer;
    GetWindowRect(window, &outer);
    // Pressed 3 px inside the outer rectangle: in the invisible resize border on the left,
    // the right and the bottom, and in the window's top resize band at the top.
    int x = gx < 0 ? (int)outer.left + 3 : gx > 0 ? (int)outer.right - 3 : (int)(outer.left + outer.right) / 2;
    int y = gy < 0 ? (int)outer.top + 3 : gy > 0 ? (int)outer.bottom - 3 : (int)(outer.top + outer.bottom) / 2;
    int out = MulDiv(travel, dpi, 96), in = out;
    // Room to grow along each axis (to the work area's edge) and to shrink (to above the
    // window's minimum size). Out grows an axis and in shrinks it, unless ysign turns the
    // vertical axis round.
    int grow_x = gx < 0 ? (int)(outer.left - probe_work.left) - 8 : (int)(probe_work.right - outer.right) - 8;
    int grow_y = gy < 0 ? (int)(outer.top - probe_work.top) - 8 : (int)(probe_work.bottom - outer.bottom) - 8;
    int shrink_x = (int)(outer.right - outer.left) - MulDiv(280, dpi, 96);
    int shrink_y = (int)(outer.bottom - outer.top) - MulDiv(220, dpi, 96);
    if (gx != 0 && grow_x < out) out = grow_x;
    if (gx != 0 && shrink_x < in) in = shrink_x;
    if (gy != 0 && (ysign > 0 ? grow_y : shrink_y) < out) out = ysign > 0 ? grow_y : shrink_y;
    if (gy != 0 && (ysign > 0 ? shrink_y : grow_y) < in) in = ysign > 0 ? shrink_y : grow_y;
    if (out < 40 || (out_and_in && in < 40)) {
        fprintf(stderr, "probe: %s drag of the %s edge: no room (out %d px, in %d px), skipped\n",
                probe_phase_names[drag_phase], name, out, in);
        return;
    }
    int32_t targets[5];
    int target_count;
    if (out_and_in) {
        targets[0] = out;
        targets[1] = -in;
        targets[2] = out;
        targets[3] = -in;
        targets[4] = 0;
        target_count = 5;
    } else {
        targets[0] = out;
        targets[1] = 0;
        targets[2] = out;
        targets[3] = 0;
        target_count = 4;
    }
    int32_t path[1024];
    int moves = probe_path(rng, targets, target_count, step_low, step_high, path, 1024);
    // What the window answers at the press point: the edge the drag should take hold of.
    DWORD_PTR hit = 0;
    SendMessageTimeoutW(window, WM_NCHITTEST, 0, MAKELPARAM(x, y), SMTO_ABORTIFHUNG, 500, &hit);
    edge_calibrate(window, probe_phase_names[drag_phase]);
    send_mouse(0, x, y);
    Sleep(150);
    fprintf(stderr,
            "probe: %s drag of the %s edge%s: pressed at %d,%d, hit test %d (%s %d), window %ld,%ld-%ld,%ld, out %d px, "
            "in %d px, %d moves of %d to %d px\n",
            probe_phase_names[drag_phase], name, ysign < 0 ? " (height turned round)" : "", x, y, (int)hit,
            (int)hit == edge_hit(gx, gy) ? "as expected," : "EXPECTED", edge_hit(gx, gy), outer.left, outer.top,
            outer.right, outer.bottom, out, out_and_in ? in : 0, moves, (int)step_low, (int)step_high);
    phase(drag_phase);
    send_mouse(MOUSEEVENTF_LEFTDOWN, x, y);
    Sleep(100);
    RECT low = outer, high = outer;
    double last = probe_now_ms();
    for (int i = 0; i < moves; i++) {
        edge_wait_ms((double)probe_rng_between(rng, 4, 8));
        if (i > 0) {
            RECT before;
            GetWindowRect(window, &before);
            follow_lag(&before, &outer, gx, gy, ysign, path[i - 1]);
        }
        send_mouse(0, x + gx * (int)path[i], y + gy * ysign * (int)path[i]);
        double now = probe_now_ms();
        if (edge_gap_count < (int)(sizeof edge_gaps / sizeof edge_gaps[0])) edge_gaps[edge_gap_count++] = now - last;
        last = now;
        int step = (int)(i == 0 ? path[0] : path[i] - path[i - 1]);
        if (step < 0) step = -step;
        if (edge_step_low == 0 || step < edge_step_low) edge_step_low = step;
        if (step > edge_step_high) edge_step_high = step;
        RECT at;
        GetWindowRect(window, &at);
        if (at.left < low.left) low.left = at.left;
        if (at.top < low.top) low.top = at.top;
        if (at.right < low.right) low.right = at.right;
        if (at.bottom < low.bottom) low.bottom = at.bottom;
        if (at.left > high.left) high.left = at.left;
        if (at.top > high.top) high.top = at.top;
        if (at.right > high.right) high.right = at.right;
        if (at.bottom > high.bottom) high.bottom = at.bottom;
    }
    Sleep(100);
    if (moves > 0) {
        RECT last_at;
        GetWindowRect(window, &last_at);
        follow_lag(&last_at, &outer, gx, gy, ysign, path[moves - 1]);
    }
    send_mouse(MOUSEEVENTF_LEFTUP, x, y);
    // Settled frames of the drag's end are judged too: they must be clean.
    Sleep(400);
    if (moves > 0) {
        RECT settled;
        GetWindowRect(window, &settled);
        int off = side_lag(&settled, &outer, gx, gy, ysign, path[moves - 1]);
        if (off > settle_worst) settle_worst = off;
    }
    // The pointer's travel along each moving axis (the path starts at 0), against the
    // dragged sides' travel.
    int32_t path_low = 0, path_high = 0;
    for (int i = 0; i < moves; i++) {
        if (path[i] < path_low) path_low = path[i];
        if (path[i] > path_high) path_high = path[i];
    }
    long pointer = 0, sides = 0;
    if (gx != 0) {
        pointer += path_high - path_low;
        sides += gx < 0 ? high.left - low.left : high.right - low.right;
    }
    if (gy != 0) {
        pointer += path_high - path_low;
        sides += gy < 0 ? high.top - low.top : high.bottom - low.bottom;
    }
    follow_pointer_px += pointer;
    follow_side_px += sides;
    fprintf(stderr,
            "probe: %s drag of the %s edge: the window's sides moved over left %ld px, top %ld, right %ld, bottom %ld; "
            "followed %ld of %ld px\n",
            probe_phase_names[drag_phase], name, high.left - low.left, high.top - low.top, high.right - low.right,
            high.bottom - low.bottom, sides, pointer);
    phase(PHASE_SETUP);
}

static int compare_doubles(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static DWORD WINAPI edge_thread(LPVOID unused) {
    (void)unused;
    HWND window = (HWND)probe_window.window;
    edge_timer = CreateWaitableTimerExW(NULL, NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    // A seed a mode, so a run drags as every other run of that mode does.
    struct probe_rng rng;
    // PROBE_DRAGS: drags a mode makes (the four first modes: 2, the later ones: 3), so a
    // longer run judges more frames.
    int drags = getenv("PROBE_DRAGS") != NULL ? atoi(getenv("PROBE_DRAGS")) : 0;
    int few = drags > 0 ? drags : 2, more = drags > 0 ? drags : 3;
    if (strcmp(probe_mode, "drag-left") == 0) {
        rng.state = 101;
        for (int i = 0; i < few; i++) edge_drag(window, &rng, -1, 0, 1, 1, 40, 120, 320, PHASE_DRAG_LEFT);
    } else if (strcmp(probe_mode, "drag-top") == 0) {
        rng.state = 202;
        for (int i = 0; i < few; i++) edge_drag(window, &rng, 0, -1, 1, 1, 40, 120, 320, PHASE_DRAG_TOP);
    } else if (strcmp(probe_mode, "drag-topleft") == 0) {
        rng.state = 303;
        for (int i = 0; i < few; i++) edge_drag(window, &rng, -1, -1, 1, 1, 40, 120, 320, PHASE_DRAG_TOPLEFT);
    } else if (strcmp(probe_mode, "drag-fast") == 0) {
        rng.state = 404;
        edge_drag(window, &rng, 1, 0, 1, 0, 40, 120, 320, PHASE_DRAG_FAST);
        edge_drag(window, &rng, 0, 1, 1, 0, 40, 120, 320, PHASE_DRAG_FAST);
        edge_drag(window, &rng, 1, 1, 1, 0, 40, 120, 320, PHASE_DRAG_FAST);
        edge_drag(window, &rng, -1, -1, 1, 0, 40, 120, 320, PHASE_DRAG_FAST);
    } else if (strcmp(probe_mode, "drag-right") == 0) {
        rng.state = 505;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, 1, 0, 1, 1, 8, 60, 320, PHASE_DRAG_RIGHT);
    } else if (strcmp(probe_mode, "drag-bottom") == 0) {
        rng.state = 606;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, 0, 1, 1, 1, 8, 60, 320, PHASE_DRAG_BOTTOM);
    } else if (strcmp(probe_mode, "drag-bottomright") == 0) {
        rng.state = 707;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, 1, 1, 1, 1, 8, 60, 320, PHASE_DRAG_BOTTOMRIGHT);
    } else if (strcmp(probe_mode, "drag-topright") == 0) {
        rng.state = 808;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, 1, -1, 1, 1, 8, 60, 320, PHASE_DRAG_TOPRIGHT);
    } else if (strcmp(probe_mode, "drag-bottomleft") == 0) {
        rng.state = 909;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, -1, 1, 1, 1, 8, 60, 320, PHASE_DRAG_BOTTOMLEFT);
    } else if (strcmp(probe_mode, "drag-mixed") == 0) {
        rng.state = 1010;
        for (int i = 0; i < more; i++) edge_drag(window, &rng, 1, 1, -1, 1, 8, 60, 320, PHASE_DRAG_MIXED);
    } else {
        // drag-slow: the right edge, then the bottom-right corner, 1 to 3 px a move over 96 px:
        // about 380 moves a drag, so one pair unless PROBE_DRAGS asks for more (on WARP each
        // move waits for a composition of 45 to 78 ms).
        rng.state = 1111;
        for (int i = 0; i < (drags > 0 ? drags : 1); i++) {
            edge_drag(window, &rng, 1, 0, 1, 1, 1, 3, 96, PHASE_DRAG_SLOW);
            edge_drag(window, &rng, 1, 1, 1, 1, 1, 3, 96, PHASE_DRAG_SLOW);
        }
    }
    if (edge_gap_count > 0) {
        qsort(edge_gaps, (size_t)edge_gap_count, sizeof edge_gaps[0], compare_doubles);
        fprintf(stderr,
                "probe: %s pace moves=%d gap_ms median %.1f p95 %.1f max %.1f, step px %d to %d (%s timer)\n",
                probe_mode, edge_gap_count, edge_gaps[edge_gap_count / 2], edge_gaps[edge_gap_count * 95 / 100],
                edge_gaps[edge_gap_count - 1], edge_step_low, edge_step_high,
                edge_timer != NULL ? "high-resolution" : "spinning");
    }
    if (follow_pointer_px > 0) {
        qsort(follow_lags, (size_t)follow_lag_count, sizeof follow_lags[0], compare_ints);
        fprintf(stderr,
                "probe: follow %s sides %ld px of pointer %ld px = %ld %%; settled worst %d px; lag moves=%d px median %d p95 %d max %d\n",
                probe_mode, follow_side_px, follow_pointer_px, follow_side_px * 100 / follow_pointer_px, settle_worst,
                follow_lag_count, follow_lag_count > 0 ? follow_lags[follow_lag_count / 2] : 0,
                follow_lag_count > 0 ? follow_lags[follow_lag_count * 95 / 100] : 0,
                follow_lag_count > 0 ? follow_lags[follow_lag_count - 1] : 0);
    }
    if (edge_timer != NULL) CloseHandle(edge_timer);
    phase(PHASE_DONE);
    InterlockedExchange(&probe_driver_done, 1);
    return 0;
}

/** The running Windows build (RtlGetVersion: GetVersionEx answers with the manifest's), or 0. */
static unsigned long probe_windows_build(void) {
    typedef LONG(WINAPI * rtl_get_version_fn)(OSVERSIONINFOW *);
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    rtl_get_version_fn get = ntdll != NULL ? (rtl_get_version_fn)(void *)GetProcAddress(ntdll, "RtlGetVersion") : NULL;
    OSVERSIONINFOW version;
    memset(&version, 0, sizeof version);
    version.dwOSVersionInfoSize = sizeof version;
    if (get == NULL || get(&version) != 0) return 0;
    return (unsigned long)version.dwBuildNumber;
}

// DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND and DWMWA_BORDER_COLOR, by number:
// Windows build 22000 and later take them, and an older SDK does not name them.
#define PROBE_DWMWA_WINDOW_CORNER_PREFERENCE 33
#define PROBE_DWMWCP_DONOTROUND 1
#define PROBE_DWMWA_BORDER_COLOR 34

/**
 * For the judged modes: asks DWM to draw the window's border in pure blue and its corners
 * square (so the area inside the border is a rectangle), and says whether it took.
 */
static void judge_setup(HWND window) {
    unsigned long build = probe_windows_build();
    if (build < 22000) {
        snprintf(judge_unavailable, sizeof judge_unavailable,
                 "Windows build %lu; DWMWA_BORDER_COLOR needs build 22000 or later", build);
        return;
    }
    COLORREF blue = PROBE_BORDER_COLORREF;
    HRESULT border = DwmSetWindowAttribute(window, PROBE_DWMWA_BORDER_COLOR, &blue, sizeof blue);
    if (FAILED(border)) {
        snprintf(judge_unavailable, sizeof judge_unavailable,
                 "Windows build %lu; DwmSetWindowAttribute(DWMWA_BORDER_COLOR) failed 0x%08lx", build,
                 (unsigned long)border);
        return;
    }
    DWORD square = PROBE_DWMWCP_DONOTROUND;
    HRESULT corners = DwmSetWindowAttribute(window, PROBE_DWMWA_WINDOW_CORNER_PREFERENCE, &square, sizeof square);
    judge_available = 1;
    fprintf(stderr, "probe: judges on: border drawn in pure blue on build %lu, corners %s\n", build,
            SUCCEEDED(corners) ? "square" : "as DWM draws them (the corner preference was refused)");
}

/** The judged modes' summary lines, which the workflow and run-lag-probe.ps1 read. */
static int judge_report(const char *mode) {
    if (!judge_available) {
        fprintf(stderr, "probe: anchor %s not available on this build (%s)\n", mode, judge_unavailable);
        fprintf(stderr, "probe: see-through %s not available on this build (%s)\n", mode, judge_unavailable);
        return 0;
    }
    int judged = judge_frames - judge_no_border - judge_uncalibrated;
    fprintf(stderr,
            "probe: judged %s frames=%d judged=%d without a border found=%d uncalibrated=%d; under the pointer, not "
            "judged: markers %d, size codes %d\n",
            mode, judge_frames, judged, judge_no_border, judge_uncalibrated, judge_covered_marks, judge_covered_codes);
    char worst[160] = "";
    size_t used = 0;
    for (int i = 0; i < 5 && anchor_worst_frame[i] != 0 && used < sizeof worst; i++) {
        int wrote = snprintf(worst + used, sizeof worst - used, "%sframe %d %d px", i > 0 ? ", " : "",
                             anchor_worst_frame[i], anchor_worst_shift[i]);
        if (wrote < 0) break;
        used += (size_t)wrote;
    }
    fprintf(stderr,
            "probe: anchor %s judged %d faulty %d max shift %d px (markers missing %d, cut %d); faulty by shift: "
            "2-4 px %d, 5-16 px %d, 17-64 px %d, over 64 px %d, missing or cut only %d; worst: %s\n",
            mode, judged, anchor_faulty, anchor_max_shift, anchor_missing, anchor_cut, anchor_by_size[0],
            anchor_by_size[1], anchor_by_size[2], anchor_by_size[3], anchor_by_size[4], used > 0 ? worst : "none");
    fprintf(stderr,
            "probe: see-through %s judged %d faulty %d max %d px at the %s (see-through %d, frame of another size %d, "
            "size code unreadable %d)\n",
            mode, judged, see_faulty, see_max_px, see_max_px > 0 ? probe_edge_names[see_max_edge] : "none", see_strips,
            see_other_size, see_unreadable);
    return judged;
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "states";
    probe_mode = mode;
    judge_mode = 0;
    for (LONG named = PHASE_DRAG_LEFT; named <= PHASE_DRAG_SLOW; named++) {
        if (strcmp(mode, probe_phase_names[named]) == 0) judge_mode = 1;
    }
    probe_marked = judge_mode;
    QueryPerformanceFrequency(&probe_frequency);
    probe_draw_ms = getenv("PROBE_DRAW_MS") != NULL ? atoi(getenv("PROBE_DRAW_MS")) : 0;
    capture_save_dir = getenv("PROBE_SAVE");
    const char *save_phase = getenv("PROBE_SAVE_PHASE");
    for (int named = 0; save_phase != NULL && named <= PHASE_DONE; named++) {
        if (strcmp(save_phase, probe_phase_names[named]) == 0) capture_save_phase = named;
    }
    if (getenv("PROBE_SAVE_LIMIT") != NULL) capture_save_limit = atoi(getenv("PROBE_SAVE_LIMIT"));
    capture_verbose = getenv("PROBE_VERBOSE") != NULL && strcmp(getenv("PROBE_VERBOSE"), "0") != 0;
    if (capture_save_dir != NULL) CreateDirectoryA(capture_save_dir, NULL);
    int raster = getenv("PROBE_RASTER") == NULL || strcmp(getenv("PROBE_RASTER"), "0") != 0;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    // No minimise and maximise animation: it would be composed frames of a window being
    // scaled, which are not what is judged here.
    ANIMATIONINFO animation = {sizeof animation, 0};
    SystemParametersInfoW(SPI_SETANIMATION, sizeof animation, &animation, 0);
    // The window follows a dragged edge only with full-window drag on; off, the system
    // drags an outline and resizes once, on release.
    SystemParametersInfoW(SPI_SETDRAGFULLWINDOWS, TRUE, NULL, 0);

    backdrop_ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    CloseHandle(CreateThread(NULL, 0, backdrop_thread, NULL, 0, NULL));
    WaitForSingleObject(backdrop_ready, 5000);

    dxc_native_window_configure(1, 200, 150, 0, 0);
    int32_t opened = dxc_native_window_open("resize probe", 640, 480, &probe_window);
    if (opened != 0) {
        fprintf(stderr, "probe: the window did not open (%d)\n", (int)opened);
        return 2;
    }
    HWND window = (HWND)probe_window.window;
    BOOL off = TRUE;
    DwmSetWindowAttribute(window, DWMWA_TRANSITIONS_FORCEDISABLED, &off, sizeof off);
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor);
    probe_work = monitor.rcWork;
    if (judge_mode) {
        judge_area = probe_work;
        judge_setup(window);
        if (!judge_available) fprintf(stderr, "probe: judges not available on this build (%s)\n", judge_unavailable);
    }
    fprintf(stderr, "probe: mode %s, draw +%d ms, resize frames on the %s, work area %ld,%ld-%ld,%ld\n", mode,
            probe_draw_ms, raster ? "CPU" : "GPU", probe_work.left, probe_work.top, probe_work.right,
            probe_work.bottom);
    if (gpu_setup((ID3D12Device *)probe_window.device) != 0) {
        fprintf(stderr, "probe: GPU frames could not be set up; they are left unpainted\n");
        gpu_broken = 1;
    }
    dxc_native_set_raster_resize(raster);
    dxc_native_set_draw_callback(probe_draw, NULL);
    SetWindowPos(window, NULL, probe_work.left, probe_work.top, 640, 480, SWP_NOZORDER);
    SetForegroundWindow(window);
    probe_draw(NULL);
    double settle_until = probe_now_ms() + 1500.0;
    while (probe_now_ms() < settle_until) dxc_native_pump(0.016);
    // What the window holds at rest, before anything resizes it, to compare the two routes.
    dxc_native_report_metrics("probe-idle");

    HANDLE capture = CreateThread(NULL, 0, capture_thread, NULL, 0, NULL);
    Sleep(300);
    CloseHandle(CreateThread(NULL, 0, judge_mode ? edge_thread : strcmp(mode, "drag") == 0 ? drag_thread : states_thread,
                             NULL, 0, NULL));
    struct dxc_event event;
    while (!probe_driver_done && !dxc_native_window_closed()) {
        dxc_native_pump(0.016);
        while (dxc_native_poll_event(&event) != 0) {
        }
    }
    double tail_until = probe_now_ms() + 500.0;
    while (probe_now_ms() < tail_until) dxc_native_pump(0.016);
    dxc_native_report_metrics("probe-end");
    InterlockedExchange(&probe_capture_stop, 1);
    WaitForSingleObject(capture, 5000);

    fprintf(stderr, "probe: frames drawn cpu=%d gpu=%d\n", probe_frames_cpu, probe_frames_gpu);
    for (int i = 0; i <= PHASE_DONE; i++) {
        if (capture_frames_by_phase[i] > 0) {
            fprintf(stderr, "probe: phase %s composed %d frames, %d faulty\n", probe_phase_names[i],
                    capture_frames_by_phase[i], capture_faulty_by_phase[i]);
        }
    }
    if (strcmp(mode, "drag") == 0 && drag_moves > 0) {
        qsort(drag_lags, (size_t)drag_moves, sizeof drag_lags[0], compare_ints);
        fprintf(stderr, "probe: drag lag moves=%d px_median=%d px_p95=%d px_max=%d longest_hold_ms=%d\n", drag_moves,
                drag_lags[drag_moves / 2], drag_lags[drag_moves * 95 / 100], drag_lag_max, drag_hold_max_ms);
    }
    int clean = !capture_failed && capture_frames > 0 && capture_faulty == 0;
    if (judge_mode) {
        // The judged modes' verdict is their judges': none faulty in a run that judged frames.
        // The strips' count stays in the line as for the other modes.
        int judged = judge_report(mode);
        int judges_clean = !capture_failed && judged > 0 && anchor_faulty == 0 && see_faulty == 0;
        fprintf(stderr,
                "probe: verdict %s (%s: %d composed frames, %d faulty, %d missed by the capture%s; anchor %d faulty, "
                "see-through %d faulty in %d judged)\n",
                capture_failed ? "FAIL" : !judge_available ? "NOT JUDGED" : judges_clean ? "PASS" : "FAIL", mode,
                capture_frames, capture_faulty, capture_missed, capture_failed ? ", capture failed" : "",
                anchor_faulty, see_faulty, judged);
        fflush(stderr);
        return capture_failed ? 1 : 0;
    }
    fprintf(stderr, "probe: verdict %s (%s: %d composed frames, %d faulty, %d missed by the capture%s)\n",
            clean ? "PASS" : "FAIL", mode, capture_frames, capture_faulty, capture_missed,
            capture_failed ? ", capture failed" : "");
    fflush(stderr);
    if (strcmp(mode, "drag") == 0) return capture_failed ? 1 : 0;
    return clean ? 0 : 1;
}
