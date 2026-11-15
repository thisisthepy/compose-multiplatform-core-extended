# 포크 자체 모듈(`extended/`) 의 변경

`compose/` 아래의 upstream 변경 기록은 upstream 의 것입니다. 이 문서는 이 포크가 더한 모듈의 변경을 적습니다.
영어판은 [CHANGELOG.md](CHANGELOG.md) 입니다.

## 출시 전

### 바뀐 것

- **Kotlin/Native Windows와 Linux 창에서 마우스 휠이 반대로, 그리고 너무 멀리 스크롤되던 문제를 고쳤습니다.**
  창은 스크롤의 이동량을 줄 단위로 보내며, 양수 y는 아래쪽을 보여 달라는 뜻입니다(AWT의 휠 회전과 같은
  부호). 데스크톱 Compose(GraalVM 창이 거치는 쪽)는 이것을 뒤집지만, foundation의 `WindowsScrollConfig`
  (mingwX64)와 `LinuxScrollConfig`(linuxX64, linuxArm64)는 뒤집지 않았습니다. Windows는 #169가 GraalVM
  경로를 위해 창의 세로 값을 뒤집기 전까지는 맞았는데, 그 변경이 Kotlin/Native 설정에도 닿았습니다. 그
  설정은 한 칸이 움직이는 줄 수도 한 번 더 곱해서, 기본값에서 세 배 멀리 움직였습니다. Linux 설정은 처음부터
  부호가 틀렸고 한 줄에 64 dp, 한 칸에 192 dp를 움직였습니다. 이제 둘 다 같은 시스템의 데스크톱 Compose와
  같은 줄 거리로 답합니다. Windows는 스크롤되는 변의 1/20, Linux는 그 제곱근입니다. 창은 바꾸지 않았으므로
  GraalVM 경로가 두 번 뒤집히지 않습니다. `WindowEvent.SCROLL` 에 각 창이 보내는 값과 각 설정이 그것을
  어떻게 바꾸는지 표로 적었습니다. foundation이 바뀌었습니다.
- **Kotlin/Native macOS 창에서 휠과 트랙패드로 아무것도 스크롤되지 않던 문제를 고쳤습니다.** 원인은 두 가지였습니다.
  창의 스크롤 기록은 포인터 이벤트가 위치를 담는 자리에 이동량을 담기 때문에, 장면은 모든 스크롤을 창의 왼쪽 위
  모서리로 보냈습니다. Compose는 스크롤의 위치에서 대상을 찾으므로, 그 모서리 아래에 있는 것만 스크롤될 수
  있었습니다. 또 foundation의 macOS 스크롤 거리(`MacOsScrollConfig`)는 `NSEvent` 만으로 계산했는데, 이 창은
  장면에 `NSEvent` 를 넘기지 않으므로 모든 스크롤이 0이 되었습니다. 이제 장면은 마지막 이동, 누름, 뗌, 진입이
  포인터를 둔 곳을 기억해 스크롤을 그곳으로 보내고, macOS 창은 이동보다 먼저 온 스크롤 앞에 포인터의 현재 위치를
  알려 줍니다. foundation의 macOS 설정은 `NSEvent` 가 없으면 포인터 변화의 이동량을 읽습니다. 한 줄은
  NSScrollView의 한 줄인 10 dp이고, 트랙패드 10 포인트가 한 줄이므로 트랙패드는 손가락이 움직인 만큼 내용을
  움직입니다. `NSEvent` 가 있는 스크롤은 전과 같이 계산합니다. `WindowEvent.SCROLL` 에 모든 창 계층이 보내는
  단위와 부호를 적었습니다. foundation이 바뀌었으므로, 사용하는 쪽은 이 커밋 이후의 Compose 빌드가 필요합니다.
- `graalvm/graalvm-windows` 모듈이 새로 생겼습니다. 네이티브 이미지의 Win32 창(`Win32GraalWindow`,
  `Win32Frames`, 크기 조절 중 그리는 업콜)을 compose-rust 에서 옮겨 왔습니다. `frameTimeNanos()` 는
  단조 시계의 `FrameClock` 을 읽으므로, 프레임 시각은 16 ms 씩 세지 않고 실제 시계를 따릅니다.
- **Windows 마우스 휠이 반대로 스크롤되던 문제를 고쳤습니다.** GraalVM과 Kotlin/Native 두 경로 모두입니다.
  Win32는 휠을 사용자 반대쪽으로 굴리면 양수로 세고 Compose는 양수 `scrollDelta.y` 를 아래로 읽으므로,
  이제 `WM_MOUSEWHEEL` 의 값을 뒤집습니다. (#169)
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
