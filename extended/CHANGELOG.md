# Changelog

Changes to the fork's own modules under `extended/`, newest first. Upstream Compose's changes
are in its own release notes.

## Unreleased

### Changed

- **Linux windows are undecorated by default.** `native/linux` and `graalvm/graalvm-linux` now
  read `WindowConfig.chrome`, whose default is `WindowChrome.Modern`: the window manager's frame
  is taken off and the scene draws the caption and its buttons (`caption()` reports the strip,
  with `buttonsDrawnByScene = true`). This holds with `decorated = true` too. Pass
  `chrome = WindowChrome.System` to keep the window manager's frame and title bar
  ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).
- The Linux windows publish AT-SPI from the scene's `AccessibleElement` records
  (`WindowPlatform.setAccessibility`). `AtspiSemanticsSource`, `NodeFacts` and `describeFacts`
  and graalvm-linux's `X11Accessibility` are removed, and `AtspiActions` is now
  `perform(id, action, argument)` and `windowOrigin()`
  ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).

### Added

- `startMove`, `startResize` and `performCaptionAction` on both Linux windows, through
  `_NET_WM_MOVERESIZE` and `_NET_WM_STATE`, and a double click in the caption to maximise or
  restore ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).
