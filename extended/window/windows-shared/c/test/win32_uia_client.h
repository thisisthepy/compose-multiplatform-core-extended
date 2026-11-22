// A UI Automation reader for win32_shared_test.c, compiled as C++ in win32_uia_client.cpp.
//
// It lives in its own C++ file because the SDK's UIAutomationClient.h defines hundreds of
// `const long` ids at file scope. In C those have external linkage, so a C test including it
// defines every id a second time beside win32_window.c, which includes it too, and the link
// fails with LNK2005. In C++ a const at file scope is internal, so the reader can include the
// SDK's client header and the C test does not have to.
#ifndef DXC_WIN32_UIA_CLIENT_H
#define DXC_WIN32_UIA_CLIENT_H

#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

// What the client thread found, for the window's thread to check.
struct uia_probe {
    HWND window;
    volatile LONG done;
    HRESULT invoke;
    HRESULT toggle;
    HRESULT set_value;
    HRESULT set_range;
    HRESULT set_focus;
    int found_button;
    int button_enabled;
    int disabled_enabled;
    int disabled_refused;   // its Invoke answered UIA_E_ELEMENTNOTENABLED
    int toggle_was_on;      // the checkbox read ToggleState_On before it was toggled
    int field_read_only;
    int has_invoke_on_text;
    double range_value;
    double range_max;
    int nested_parent_ok;
    int root_children;
    int focused_ok;
    int help_ok;
};

// The thread body: CreateThread(NULL, 0, dxc_test_uia_client, &probe, 0, NULL). The thread
// that owns probe->window has to keep pumping until probe->done is 1, since UI Automation
// brings every call to the providers onto that thread.
DWORD WINAPI dxc_test_uia_client(void *probe);

// The SDK's numbers that win32_uia.h repeats so it compiles without the SDK: ToggleState_On,
// ToggleState_Indeterminate, ExpandCollapseState_Expanded, ExpandCollapseState_LeafNode.
void dxc_test_uia_sdk_numbers(int numbers[4]);

#ifdef __cplusplus
}
#endif

#endif
