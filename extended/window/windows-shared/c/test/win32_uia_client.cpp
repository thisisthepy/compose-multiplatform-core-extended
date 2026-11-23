// The UI Automation reader win32_shared_test.c runs on a second thread. C++ only so the SDK's
// client header can be included without defining its ids twice; see win32_uia_client.h.
#include <windows.h>
#include <wchar.h>
#include <stdlib.h>
#include <uiautomation.h>

#include "win32_uia_client.h"

namespace {

template <typename T> void release(T *&object) {
    if (object != nullptr) {
        object->Release();
        object = nullptr;
    }
}

IUIAutomationElement *find(IUIAutomation *automation, IUIAutomationElement *root, PROPERTYID property,
                           VARIANT value) {
    IUIAutomationCondition *condition = nullptr;
    IUIAutomationElement *found = nullptr;
    if (SUCCEEDED(automation->CreatePropertyCondition(property, value, &condition))) {
        root->FindFirst(TreeScope_Descendants, condition, &found);
        release(condition);
    }
    return found;
}

IUIAutomationElement *find_id(IUIAutomation *automation, IUIAutomationElement *root, const wchar_t *id) {
    VARIANT value;
    VariantInit(&value);
    value.vt = VT_BSTR;
    value.bstrVal = SysAllocString(id);
    IUIAutomationElement *found = find(automation, root, UIA_AutomationIdPropertyId, value);
    VariantClear(&value);
    return found;
}

int is_enabled(IUIAutomationElement *element) {
    BOOL enabled = FALSE;
    element->get_CurrentIsEnabled(&enabled);
    return enabled != FALSE;
}

int automation_id_is(IUIAutomationElement *element, const wchar_t *expected) {
    BSTR id = nullptr;
    element->get_CurrentAutomationId(&id);
    int same = id != nullptr && wcscmp(id, expected) == 0;
    SysFreeString(id);
    return same;
}

// The button, found the way the completion criterion says: by control type and name.
void press_save(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    VARIANT type;
    VARIANT name;
    VariantInit(&type);
    VariantInit(&name);
    type.vt = VT_I4;
    type.lVal = UIA_ButtonControlTypeId;
    name.vt = VT_BSTR;
    name.bstrVal = SysAllocString(L"Save");
    IUIAutomationCondition *by_type = nullptr, *by_name = nullptr, *both = nullptr;
    automation->CreatePropertyCondition(UIA_ControlTypePropertyId, type, &by_type);
    automation->CreatePropertyCondition(UIA_NamePropertyId, name, &by_name);
    if (by_type != nullptr && by_name != nullptr) automation->CreateAndCondition(by_type, by_name, &both);
    IUIAutomationElement *button = nullptr;
    if (both != nullptr) root->FindFirst(TreeScope_Descendants, both, &button);
    if (button != nullptr) {
        probe->found_button = 1;
        probe->button_enabled = is_enabled(button);
        IUIAutomationInvokePattern *invoke = nullptr;
        button->GetCurrentPatternAs(UIA_InvokePatternId, IID_IUIAutomationInvokePattern, (void **)&invoke);
        if (invoke != nullptr) {
            probe->invoke = invoke->Invoke();
            release(invoke);
        }
        BSTR help = nullptr;
        button->get_CurrentHelpText(&help);
        probe->help_ok = help != nullptr && wcscmp(help, L"Saves the file") == 0;
        SysFreeString(help);
        release(button);
    }
    release(both);
    release(by_type);
    release(by_name);
    VariantClear(&name);
}

// A disabled button says so and refuses.
void press_disabled(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *disabled = find_id(automation, root, L"12");
    if (disabled == nullptr) return;
    probe->disabled_enabled = is_enabled(disabled);
    IUIAutomationInvokePattern *invoke = nullptr;
    disabled->GetCurrentPatternAs(UIA_InvokePatternId, IID_IUIAutomationInvokePattern, (void **)&invoke);
    if (invoke != nullptr) {
        probe->disabled_refused = invoke->Invoke() == UIA_E_ELEMENTNOTENABLED;
        release(invoke);
    }
    release(disabled);
}

// The checkbox reads its state and is toggled.
void toggle_wrap(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *check = find_id(automation, root, L"8");
    if (check == nullptr) return;
    IUIAutomationTogglePattern *toggle = nullptr;
    check->GetCurrentPatternAs(UIA_TogglePatternId, IID_IUIAutomationTogglePattern, (void **)&toggle);
    if (toggle != nullptr) {
        ToggleState state = ToggleState_Off;
        toggle->get_CurrentToggleState(&state);
        probe->toggle_was_on = state == ToggleState_On;
        probe->toggle = toggle->Toggle();
        release(toggle);
    }
    release(check);
}

// The field takes a new value and focus.
void write_name(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *field = find_id(automation, root, L"9");
    if (field == nullptr) return;
    IUIAutomationValuePattern *value = nullptr;
    field->GetCurrentPatternAs(UIA_ValuePatternId, IID_IUIAutomationValuePattern, (void **)&value);
    if (value != nullptr) {
        BOOL read_only = TRUE;
        value->get_CurrentIsReadOnly(&read_only);
        probe->field_read_only = read_only != FALSE;
        BSTR text = SysAllocString(
            L"\xd55c\xae00 and more than ninety-six bytes of text, so the value cannot travel in an event's own text field");
        probe->set_value = value->SetValue(text);
        SysFreeString(text);
        release(value);
    }
    probe->set_focus = field->SetFocus();
    release(field);
}

// The slider reads its range and takes a value.
void move_volume(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *slider = find_id(automation, root, L"15");
    if (slider == nullptr) return;
    IUIAutomationRangeValuePattern *range = nullptr;
    slider->GetCurrentPatternAs(UIA_RangeValuePatternId, IID_IUIAutomationRangeValuePattern, (void **)&range);
    if (range != nullptr) {
        range->get_CurrentValue(&probe->range_value);
        range->get_CurrentMaximum(&probe->range_max);
        probe->set_range = range->SetValue(0.75);
        release(range);
    }
    release(slider);
}

// Text offers no Invoke, and it is inside the group, not directly in the window.
void read_inside(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *text = find_id(automation, root, L"11");
    if (text == nullptr) return;
    IUnknown *pattern = nullptr;
    text->GetCurrentPattern(UIA_InvokePatternId, &pattern);
    probe->has_invoke_on_text = pattern != nullptr;
    release(pattern);
    IUIAutomationTreeWalker *walker = nullptr;
    automation->get_RawViewWalker(&walker);
    if (walker != nullptr) {
        IUIAutomationElement *parent = nullptr;
        walker->GetParentElement(text, &parent);
        if (parent != nullptr) {
            probe->nested_parent_ok = automation_id_is(parent, L"10");
            release(parent);
        }
        release(walker);
    }
    release(text);
}

// The window's own children are the elements directly in it, counted by the scene's ids, since
// the window may also hold a child window of its own.
void count_children(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationCondition *all = nullptr;
    automation->CreateTrueCondition(&all);
    IUIAutomationElementArray *children = nullptr;
    if (all != nullptr) root->FindAll(TreeScope_Children, all, &children);
    if (children != nullptr) {
        int length = 0;
        children->get_Length(&length);
        for (int i = 0; i < length; i++) {
            IUIAutomationElement *child = nullptr;
            children->GetElement(i, &child);
            if (child == nullptr) continue;
            BSTR id = nullptr;
            child->get_CurrentAutomationId(&id);
            if (id != nullptr && id[0] != 0) {
                int value = _wtoi(id);
                if (value == 11 || value == 13) probe->root_children = -100;
                else probe->root_children++;
            }
            SysFreeString(id);
            release(child);
        }
        release(children);
    }
    release(all);
}

// The element the scene says has focus is the one a reader is told has it.
void read_focus(uia_probe *probe, IUIAutomation *automation, IUIAutomationElement *root) {
    IUIAutomationElement *focused = find_id(automation, root, L"13");
    if (focused == nullptr) return;
    BOOL has = FALSE;
    focused->get_CurrentHasKeyboardFocus(&has);
    probe->focused_ok = has != FALSE;
    release(focused);
}

} // namespace

extern "C" DWORD WINAPI dxc_test_uia_client(void *argument) {
    uia_probe *probe = static_cast<uia_probe *>(argument);
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    IUIAutomation *automation = nullptr;
    IUIAutomationElement *root = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER, IID_IUIAutomation,
                                   (void **)&automation)) &&
        SUCCEEDED(automation->ElementFromHandle(probe->window, &root)) && root != nullptr) {
        press_save(probe, automation, root);
        press_disabled(probe, automation, root);
        toggle_wrap(probe, automation, root);
        write_name(probe, automation, root);
        move_volume(probe, automation, root);
        read_inside(probe, automation, root);
        count_children(probe, automation, root);
        read_focus(probe, automation, root);
    }
    release(root);
    release(automation);
    CoUninitialize();
    InterlockedExchange(&probe->done, 1);
    return 0;
}

extern "C" void dxc_test_uia_sdk_numbers(int numbers[4]) {
    numbers[0] = ToggleState_On;
    numbers[1] = ToggleState_Indeterminate;
    numbers[2] = ExpandCollapseState_Expanded;
    numbers[3] = ExpandCollapseState_LeafNode;
}
