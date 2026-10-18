# Changes in the fork's own modules (`extended/`)

The upstream changelogs under `compose/` stay upstream's. This one is for the modules this fork
adds: the window modules, the design systems, the skiko scripts and the packaging templates.

## Unreleased

### Window modules (`org.thisisthepy.compose.window`, 0.1.0)

- **Breaking default.** `WindowConfig` gains `chrome` and `titleBar`, defaulting to
  `WindowChrome.Modern` and `TitleBarStyle.Normal`: the content runs to the top of the window.
  On Linux that means no system title bar; the scene draws the caption and its buttons. Pass
  `chrome = WindowChrome.System` to keep the platform's title bar. (#144, #146)
- `WindowPlatform` gains `caption()`, `setAccessibility(elements)`, `accessibilityActive`,
  `startMove()`, `startResize(edge)` and `performCaptionAction(action)`, each with a default; an
  implementation that already had a `setAccessibility(List<AccessibleElement>)` now marks it
  `override`. (#144)
- `AccessibleElement` gains `id`, `parentId`, `value`, `hint`, `states`, `actions` and a range,
  and `WindowListener.onAccessibilityAction(id, action, argument)` carries a reader's request
  back. (#144)
- `scene`: `LocalWindowCaption`, `Modifier.windowCaptionPadding()`, the caption buttons it draws
  where the platform has none, and the semantics it hands to the platform while a reader listens.
  `ComposeWindowHost` and `runComposeWindow` take `padContentForCaption` before `content`; a call
  that passed `content` fourth by position names it. The module gains `linuxX64` and
  `linuxArm64`. (#146)
- `scene`: Compose's main-thread work runs on a dispatcher every host shares, so it keeps running
  while any window is open. (#142, #143)
- `graalvm-macos` and `graalvm-linux`: `appKitWindowSurface` and `x11WindowSurface`, the surfaces
  `runComposeWindow` draws into. (#138)
