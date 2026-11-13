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
//
// PROBE_DRAW_MS=<n> makes every frame take n ms longer, for a renderer that is slow to draw.
// PROBE_RASTER=0 draws resize frames on the GPU (the window's DXGI path) instead of the CPU.
// PROBE_SAVE=<dir> writes the first faulty frames there as BMP files.
// Exit status: 0 when no composed frame was faulty (states) or always (drag), 1 otherwise.
#define COBJMACROS
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

// The toasts ask the renderer for a frame; there is no renderer here.
void compose_rust_renderer_request_frame(void) {}

static struct dxc_native_window probe_window;
static int probe_draw_ms;
static LARGE_INTEGER probe_frequency;

static double probe_now_ms(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return (double)now.QuadPart * 1000.0 / (double)probe_frequency.QuadPart;
}

// The picture: grey, with a 4 px magenta strip at the right and at the bottom.
#define PROBE_STRIP 4
static void probe_fill(uint8_t *pixels, int32_t row_bytes, int32_t width, int32_t height) {
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
static const char *probe_phase_names[] = {"rest", "maximise", "restore", "left-half", "back", "drag", "done"};
enum { PHASE_REST, PHASE_MAXIMISE, PHASE_RESTORE, PHASE_LEFT_HALF, PHASE_BACK, PHASE_DRAG, PHASE_DONE };
static volatile LONG probe_capture_stop;
static RECT probe_work;

// Capture: every composed frame, one row and one column of it read back.
static int capture_frames, capture_faulty, capture_missed, capture_saved, capture_failed;
static int capture_faulty_by_phase[PHASE_DONE + 1];
static int capture_frames_by_phase[PHASE_DONE + 1];
static const char *capture_save_dir;

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
        if (info.LastPresentTime.QuadPart == 0) {
            // Only the pointer moved.
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
                    "probe: FAULT frame %d at %.1f ms phase %s: window right edge x=%d %s, bottom edge y=%d %s %s\n",
                    capture_frames, probe_now_ms(), probe_phase_names[phase], right,
                    right_strip ? "with its strip" : "WITHOUT its strip", bottom,
                    bottom_strip ? "with its strip" : "WITHOUT its strip", saved);
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

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "states";
    QueryPerformanceFrequency(&probe_frequency);
    probe_draw_ms = getenv("PROBE_DRAW_MS") != NULL ? atoi(getenv("PROBE_DRAW_MS")) : 0;
    capture_save_dir = getenv("PROBE_SAVE");
    if (capture_save_dir != NULL) CreateDirectoryA(capture_save_dir, NULL);
    int raster = getenv("PROBE_RASTER") == NULL || strcmp(getenv("PROBE_RASTER"), "0") != 0;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    // No minimise and maximise animation: it would be composed frames of a window being
    // scaled, which are not what is judged here.
    ANIMATIONINFO animation = {sizeof animation, 0};
    SystemParametersInfoW(SPI_SETANIMATION, sizeof animation, &animation, 0);

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

    HANDLE capture = CreateThread(NULL, 0, capture_thread, NULL, 0, NULL);
    Sleep(300);
    CloseHandle(CreateThread(NULL, 0, strcmp(mode, "drag") == 0 ? drag_thread : states_thread, NULL, 0, NULL));
    struct dxc_event event;
    while (!probe_driver_done && !dxc_native_window_closed()) {
        dxc_native_pump(0.016);
        while (dxc_native_poll_event(&event) != 0) {
        }
    }
    double tail_until = probe_now_ms() + 500.0;
    while (probe_now_ms() < tail_until) dxc_native_pump(0.016);
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
    fprintf(stderr, "probe: verdict %s (%s: %d composed frames, %d faulty, %d missed by the capture%s)\n",
            clean ? "PASS" : "FAIL", mode, capture_frames, capture_faulty, capture_missed,
            capture_failed ? ", capture failed" : "");
    fflush(stderr);
    if (strcmp(mode, "drag") == 0) return capture_failed ? 1 : 0;
    return clean ? 0 : 1;
}
