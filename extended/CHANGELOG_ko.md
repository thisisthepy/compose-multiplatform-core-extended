# 포크 자체 모듈(`extended/`) 의 변경

`compose/` 아래의 upstream 변경 기록은 upstream 의 것입니다. 이 문서는 이 포크가 더한 모듈의 변경을 적습니다.
영어판은 [CHANGELOG.md](CHANGELOG.md) 입니다.

## 출시 전

### 바뀐 것

- `graalvm/graalvm-windows` 모듈이 새로 생겼습니다. 네이티브 이미지의 Win32 창(`Win32GraalWindow`,
  `Win32Frames`, 크기 조절 중 그리는 업콜)을 compose-rust 에서 옮겨 왔습니다. `frameTimeNanos()` 는
  단조 시계의 `FrameClock` 을 읽으므로, 프레임 시각은 16 ms 씩 세지 않고 실제 시계를 따릅니다.
- **Linux 창은 이제 기본으로 장식이 없습니다.** `native/linux` 와 `graalvm/graalvm-linux` 는 이제
  `WindowConfig.chrome` 을 읽으며, 기본값은 `WindowChrome.Modern` 입니다. 창 관리자의 프레임을 떼고,
  장면이 캡션과 버튼을 그립니다(`caption()` 이 그 띠를 알려 주며 `buttonsDrawnByScene = true` 입니다).
  `decorated = true` 여도 같습니다. 창 관리자의 프레임과 제목 표시줄을 두려면
  `chrome = WindowChrome.System` 을 넘깁니다
  ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).
- Linux 창은 장면의 `AccessibleElement` 레코드로 AT-SPI 를 내보냅니다
  (`WindowPlatform.setAccessibility`). `AtspiSemanticsSource`, `NodeFacts`, `describeFacts`, 그리고
  graalvm-linux 의 `X11Accessibility` 는 없어졌고, `AtspiActions` 는 이제
  `perform(id, action, argument)` 와 `windowOrigin()` 입니다
  ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).

### 더한 것

- 두 Linux 창에 `startMove`, `startResize`, `performCaptionAction` 이 생겼습니다.
  `_NET_WM_MOVERESIZE` 와 `_NET_WM_STATE` 를 쓰며, 캡션을 더블 클릭하면 최대화하거나 되돌립니다
  ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).
- `native/linux-scene` (새 모듈, `org.thisisthepy.compose.window:native-linux-scene`): Kotlin/Native X11
  창에서 `ComposeWindowHost` 가 그리는 표면 `x11WindowSurface` 입니다. 창의 새 `makeCurrent()` 와
  `resizePainter` 를 씁니다 ([#149](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/149)).
