# The OS webview host (design, for review)

Status: **design only, no code.** For the owner's review on 2026-11-28 at 13:00. Tracked in the
fork's issue "OS webview host in the window modules".

## 1. What and why

The owner, 2026-11-27: *"아니 뭔 소리야 extended에 있어야지. 그리고 그걸 compose-rust랑
dioxus-compose에 넣어야지. 왜 자꾸 웹뷰를 안쓴다는 말을 과대 해석하는거지? 개들 UI 구현을
웹뷰로 하면 안된다는거지 웹뷰가 없어도 된다는 말이 아니잖아"*

So:

- "No webview" means **no UI built out of a webview**. It never meant that a window cannot
  *contain* one. Web content belongs inside a native window as one widget among others. Examples
  are a VS Code Web session, a plugin's JCEF page (the thin client's L3), a document preview or
  a login page.
- The host for that widget lives **in this fork** (`compose-multiplatform-core-extended`, the
  window modules), next to the native view slots it sits in.
- compose-rust and dioxus-compose expose it as a widget. Ember's `ember/ide-webview` moves onto
  it (Ember INTENT D58).

Today `ember/ide-webview` is a separate process with its own window (tao + wry). After this, the
same page sits inside the Ember window as a widget. It is laid out, clipped, covered by menus and
focused like any other node.

### Out of scope

- Building any application UI in HTML. The rule above stands.
- A browser: no tabs, history UI, downloads UI or extensions. Those are the caller's to build if
  it wants them.
- Rendering web content into the Compose scene (offscreen web rendering). The OS view is a real
  child view above the scene, as the native view slots already are (§4).

## 2. What already exists

Most of the hard part is done. Common's `NativeViews` (`extended/window/common/.../NativeViews.kt`,
compose-rust FR-81) places a platform view the caller made in a window. It does this on macOS
(`MacosNativeViews`), Windows (`WindowsNativeViews`, `c/win32_native_views.h`) and X11
(`LinuxNativeViews`):

- `attach(id, handle)` for an `NSView*`, `HWND` or X window id, and `detach`;
- `setFrame` / `setVisibleRegion` / `setFrameAndVisibleRegion` in window pixels (Compose's
  `boundsInWindow`). The view is cut to its ancestors' clip, and every Compose overlay above it
  (menu, popover, dialog, sheet) is cut out as a rounded hole, so the overlay shows on top and
  gets the pointer;
- `setVisible` (hidden keeps the view's state), `focus`, `focusScene`;
- focus reported back as `WindowEvent.NATIVE_VIEW_FOCUS_IN` / `NATIVE_VIEW_FOCUS_OUT`.

compose-rust's `NativeView` node (`renderer/desktop/src/renderer/HostNativeView.kt`) drives it
from Compose's layout pass in the same frame, so a view scrolled with its content does not trail
by a frame.

What is missing is the view itself. `NativeViews` "never creates one and links nothing for one".
The webview host is that missing piece: it **creates, drives and destroys the OS webview**, and
hands its handle to `NativeViews`.

## 3. Layering

```
compose-rust  WebView node  ─┐            dioxus-compose  <webview> element ─┐
                             │ (widget: props, events)                       │
                             ▼                                               ▼
fork  extended/window/webview   WebViewHost / WebView   (common API, per-OS backends)
                             │ handle (NSView*, HWND, X window)
                             ▼
fork  extended/window/common    NativeViews   (place, clip, holes, z-order, focus)  ← exists
                             │
                             ▼
      OS: WKWebView · WebView2 · WebKitGTK · android.webkit.WebView
```

A new module `extended/window/webview` (`org.thisisthepy.compose.window:webview`) is published
like the other window modules: Kotlin/Native per OS, and the C/Objective-C that the GraalVM
windows call, for the same list on both paths.

## 4. API (common)

The shape follows what every one of the four webviews can do, and nothing that only one can.

```kotlin
/** Makes webviews for one window. Nothing is loaded until the first create (§7). */
interface WebViewHost {
    /** Whether this OS has a usable webview now (WebView2 runtime present, libwebkit2gtk loadable). */
    val availability: WebViewAvailability

    /**
     * Starts making a webview for [config]. Creation is asynchronous on Windows (WebView2) and
     * may be on the others, so the view arrives in [WebViewListener.onReady] (or onFailed).
     */
    fun create(config: WebViewConfig, listener: WebViewListener): WebView
}

data class WebViewConfig(
    val url: String? = null,                       // or html below
    val html: String? = null,
    val baseUrl: String? = null,                   // for html
    val schemes: Map<String, WebResourceHandler> = emptyMap(),   // app://, ember://: §6.4
    val documentStartScripts: List<String> = emptyList(),        // injected before page scripts
    val profileDirectory: String? = null,          // cookies, storage; null = ephemeral
    val transparentBackground: Boolean = false,
    val devTools: Boolean = false,                 // inspector, off in release builds
    val userAgentSuffix: String? = null,
)

interface WebView {
    /** The view to attach with NativeViews.attach: NSView*, HWND or X window id. 0 until ready. */
    val handle: Long

    fun loadUrl(url: String)
    fun loadHtml(html: String, baseUrl: String? = null)
    fun reload(); fun stop(); fun goBack(); fun goForward()

    /** Runs [script] in the page's main frame; the result is the JSON of its value. */
    fun evaluateJavaScript(script: String, onResult: (Result<String>) -> Unit)

    /** Host → page: delivered as a `message` event on `window.chrome.webview`-like shim (§6.3). */
    fun postMessage(json: String)

    fun setZoom(factor: Double)

    /** Focus inside the view, for Tab into it from the scene (§5). */
    fun focus(direction: FocusDirection = FocusDirection.Programmatic)

    /** Takes the view down. The caller detaches it from NativeViews first. */
    fun destroy()
}

interface WebViewListener {
    fun onReady(view: WebView) {}
    fun onFailed(reason: String) {}
    fun onNavigationStarting(url: String, isMainFrame: Boolean, userInitiated: Boolean): NavigationDecision =
        NavigationDecision.Allow
    fun onNewWindowRequested(url: String): NavigationDecision = NavigationDecision.Deny
    fun onLoadFinished(url: String, success: Boolean) {}
    fun onTitleChanged(title: String) {}
    fun onMessage(json: String) {}                 // page → host (§6.3)
    fun onFocusLeaving(direction: FocusDirection) {}   // Tab past the last element (§5)
    fun onProcessGone(reason: String) {}           // web content process crashed or was killed
}
```

Threading: every call and every callback is on the thread that pumps the window, as with
`NativeViews`. The backends marshal from their own threads (WebView2 completion handlers, GTK
callbacks) onto it.

What the widget layer does with it:

1. On first composition, it calls `create`. On `onReady`, it calls `NativeViews.attach(slot, view.handle)`.
2. Each layout pass calls `setFrameAndVisibleRegion`, as the `NativeView` node already does.
3. On dispose, it calls `detach`, then `destroy`.

## 5. Focus, keyboard and IME

- **The OS webview owns text input inside the page**, IME composition included. Nothing of the
  scene's IME session crosses into it. While the webview has focus, the scene's text input
  session is ended, as when any native view slot takes focus.
- **Focus sync** uses the existing slot events: a click into the view gives
  `NATIVE_VIEW_FOCUS_IN`, and the widget moves Compose focus to its node. Focus moving elsewhere
  gives `NATIVE_VIEW_FOCUS_OUT`. When Compose focus moves onto the node (Tab from the previous
  focusable), the widget calls `NativeViews.focus(slot)` and `WebView.focus(Next)`.
- **Tab out of the page** at its last (or, with Shift, first) element calls `onFocusLeaving`,
  and the widget moves Compose focus on. The backends:
  - WebView2 raises `MoveFocusRequested`;
  - WKWebView needs the key view loop (`nextKeyView`), or a document-start script that
    catches Tab at the ends;
  - WebKitGTK uses the XEmbed focus protocol (§6.3).
- **Application shortcuts while the page has focus** (Cmd/Ctrl+W, Q, the IDE keymap) reach the
  application first, which consumes them or passes them to the page:
  - WebView2 raises `AcceleratorKeyPressed` before the page;
  - macOS calls `performKeyEquivalent` on the window before the view;
  - GTK delivers `key-press-event` to the toplevel first.

  The window reports these as ordinary key events with a "from the native view" flag, so the
  keymap code does not change.

## 6. Per-OS backends

### 6.1 macOS: WKWebView

- **View:** system WebKit, nothing to ship. A `WKWebView` is added to the slot's container view.
- **Calls:** Kotlin/Native through `platform.WebKit`; the GraalVM window through
  `appkit_window.m`.
- **Resources:** custom schemes through `WKURLSchemeHandler`, registered in the
  `WKWebViewConfiguration` before creation.
- **Bridge:** messages through `WKScriptMessageHandler`. Document-start scripts through
  `WKUserScript`.
- **Inspector:** `isInspectable` (macOS 13.3+) when `devTools`.
- **Process crash:** `webViewWebContentProcessDidTerminate`.
- **Scale:** the container and its view are in points. The slot already converts window pixels.

### 6.2 Windows: WebView2

- **Runtime:** the Evergreen WebView2 runtime, present on Windows 11 and on updated Windows 10.
  `availability` reads `GetAvailableCoreWebView2BrowserVersionString`. Where it is missing, the
  widget shows the caller's fallback, and the host can name the bootstrapper.
- **Loader:** linked statically (`WebView2LoaderStatic.lib` for MSVC). For the Kotlin/Native
  mingwX64 build, the loader's C interface is reached by `LoadLibrary` of the runtime's
  `EmbeddedBrowserWebView.dll` path, or the static lib through the MSVC link the fork already
  uses (`kotlin-native-desktop`). That choice is the first prototype step.
- **Creation:** `CreateCoreWebView2EnvironmentWithOptions` → `CreateCoreWebView2Controller`
  with the slot's view `HWND` as parent. It is asynchronous: that is why `create` reports in
  `onReady`.
- **Hosting:** windowed first, as a child `HWND` in the slot (the slot already makes the view
  `HWND` a child of its container). This works on both the GDI and the DirectComposition route:
  a child window has its own redirection. Visual hosting (`ICoreWebView2CompositionController`
  into the window's DComp tree) is a later option, if the airspace or the holes need it.
- **Resources:** custom schemes through `CustomSchemeRegistration` (environment options 4) and
  `WebResourceRequested`.
- **Bridge:** `PostWebMessageAsJson` / `WebMessageReceived`, and
  `AddScriptToExecuteOnDocumentCreated`.
- **Focus and keys:** `MoveFocus` / `MoveFocusRequested`, `GotFocus` / `LostFocus`,
  `AcceleratorKeyPressed`.
- **Process crash:** `ProcessFailed`.
- **Profile:** the user data folder is `profileDirectory`, or a per-application temporary one.
  WebView2 always needs one.
- **DPI:** the controller's `RasterizationScale` follows the window's DPI. Bounds are client
  pixels, the slot's units.

### 6.3 Linux: WebKitGTK

The window modules' Linux window is an X11 window, and the slots take X window ids
(`LinuxNativeViews`).

- **Library and embedding:** WebKitGTK is a GTK widget, so it is embedded through XEmbed:
  - a `GtkPlug` holding a `WebKitWebView`, whose X window id is the slot's handle;
  - this needs **GTK 3 and `libwebkit2gtk-4.1`**, because GTK 4 (`webkitgtk-6.0`) removed
    XEmbed;
  - the library is opened with `dlopen` at the first `create`, so the binary has no hard
    dependency, and `availability` says when it is missing.
- **Main loop:** GTK's main context is iterated (`g_main_context_iteration(NULL, FALSE)`) from
  the window's pump, while a webview exists. There is no second thread.
- **Resources:** custom schemes through `webkit_web_context_register_uri_scheme`.
- **Bridge:** messages through `webkit_user_content_manager_register_script_message_handler`.
- **Focus:** the XEmbed messages (`XEMBED_FOCUS_IN`, `XEMBED_FOCUS_NEXT/PREV`), which the slot
  code handles alongside its own focus bookkeeping.
- **Wayland:** not now. The fork's Linux window is X11 (XWayland on a Wayland desktop). A Wayland
  window module would host WebKitGTK as a subsurface, later.

### 6.4 Android: android.webkit.WebView

- **Where:** the fork's window modules have no Android window. Android's view tree already hosts
  views (compose-rust places them with `AndroidView`). So the Android backend is the same
  `WebView` interface over `android.webkit.WebView`, in the module's Android target, made with
  the host's `Context`.
- **Resources:** custom schemes through `shouldInterceptRequest`.
- **Bridge:** `WebMessagePort` or `addJavascriptInterface`, only on the app's own origin.
- **Focus and IME:** Android's own: the WebView is an ordinary focusable view.

### 6.5 iOS (not asked; for completeness)

`WKWebView`, the macOS backend's code with `UIView` in place of `NSView`, placed by compose-rust's
iOS native view. The same code is shared through `appleMain`, so this is cheap if it is wanted.

### 6.6 The bridge shim

Each backend injects one document-start script that defines the same page-side API on all four:

```js
window.darkpyonix.postMessage(json)                       // page → host (onMessage)
window.darkpyonix.addEventListener('message', e => …)     // host → page (postMessage)
```

So a page (Ember's VS Code Web glue, a JCEF page through the thin client's proxy) has one API,
not four.

## 7. Lifecycle and cost

- **Created lazily:** nothing is loaded until the first `create`. A session that never shows web
  content pays nothing (thin client spec §8.3). This applies to WebView2's runtime, GTK and
  WebKit alike.
- **One webview per widget.** Several can share a profile (`profileDirectory`).
- **Hidden** (`setVisible(false)`, for example a tab that is not selected): the view keeps its
  page and state. **Detached and destroyed** when the widget leaves the composition.
- **Process crash:** `onProcessGone`. The widget shows its fallback and can recreate.

## 8. Security

- **Navigation:** every main-frame navigation goes through `onNavigationStarting`. The widget's
  default allows only the configured origin and the custom schemes. New windows are denied by
  default (the caller may open them in the system browser).
- **Local files:** no `file://` access unless the caller allows a directory explicitly.
- **Bridge:** the bridge is installed only for the app's own origins: the custom schemes and the
  configured URL's origin. Messages from other frames or origins are dropped.
- **DevTools:** off unless `devTools`, which release builds do not set.

## 9. Verification

Each backend gets a probe on its CI runner (`macos-15`, `windows-2025`/`2022`, `ubuntu-24.04`
with `libwebkit2gtk-4.1` installed), checking:

- **Load:** a `data:` page loads, and `onLoadFinished` fires.
- **JavaScript:** `evaluateJavaScript("1+1")` returns `2`.
- **Messages:** a round trip each way through the shim.
- **Custom scheme:** an `app://` request is answered by the handler.
- **Placement:**
  - a screenshot with the view placed at a frame, clipped by a scroll container, with a menu
    hole cut out;
  - the same frame after a DPI change (Windows).
- **Focus:** focus in by click and by Tab, and out by Tab (`onFocusLeaving`).
- **Shortcuts:** a shortcut (Ctrl/Cmd+W) reaches the application while the page has focus.
- **Unavailable:** runtime missing → `availability` says so and `create` fails cleanly (Windows,
  with the runtime path hidden; Linux without the library).

## 10. Consumers

- **compose-rust:** a `WebView` widget on its `NativeView` node (FR-81). Its SPEC rows are written
  by the compose-rust teammate (docs only).
- **dioxus-compose:** a `<webview>` element. Its SPEC rows are written by the dioxus-compose
  teammate (docs only).
- **Ember:** `ember/ide-webview` (tao + wry, its own process and window) becomes a widget in the
  Ember window (INTENT D58). The VS Code Web session's glue moves to the §6.6 shim.

## 11. Open questions for the owner

1. **Windows loader link for Kotlin/Native mingwX64:** the static loader through the MSVC link
   the fork already has, or a runtime load. This affects only how the binary is built.
2. **Linux:** GTK 3 + `libwebkit2gtk-4.1` through XEmbed now, accepting that GTK 4's WebKit has
   no XEmbed. The alternative is to wait for a Wayland window module.
3. **Windows visual hosting** (composition controller) instead of a child `HWND`: only if the
   holes or the DComp route need it after the first prototype.
4. **iOS:** include it in the first round (§6.5)?
5. **Module name and coordinate:** `org.thisisthepy.compose.window:webview`.
