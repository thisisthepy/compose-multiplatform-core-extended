# 포크 자체 모듈(`extended/`) 의 변경

`compose/` 아래의 upstream 변경 기록은 upstream 의 것입니다. 이 문서는 이 포크가 더한 모듈의 변경을 적습니다.
영어판은 [CHANGELOG.md](CHANGELOG.md) 입니다.

## 출시 전

### 배포

- **고친 것.** Maven Central 릴리스에 Kotlin/JS 를 다시 담습니다(#325). 1.11.1-ext.6 은 `js` 를 뺐으므로
  (`CENTRAL_SKIP_TARGETS`) Kotlin/JS 소비자는 "No matching variant" 를 받았고, Gradle 플러그인의 JS 통합
  테스트 12개가 실패했습니다. 이 포크는 업스트림 Compose Multiplatform 을 그대로 대신하므로, 업스트림이
  UI 스택을 내는 타깃(desktop, macosArm64, iosArm64, iosSimulatorArm64, js, wasmJs)을 릴리스가 빼면
  `extended/publish/check_skip_targets.py` 가 배포 워크플로의 계획 단계를 실패시킵니다. tvOS, watchOS 는
  그대로 뺍니다.

### 패키징 템플릿 (`extended/packaging`)

- **바뀐 것.** 템플릿이 compose-rust 패키저가 실제로 내보내는 것을 담습니다. 그 패키저들이 템플릿을
  채우고(compose-rust FR-22.6 기준 11) 템플릿이 채운 것에 더하기만 할 수 있기 때문입니다. 규칙은
  `metadata-map.md`에 있습니다.
  - `flatpak-manifest.json.in`은 모든 Flathub 매니페스트에 공통인 부분입니다. Flathub가 받지 않는
    `dir` 소스가 없고, 페이로드(URL과 SHA-256의 압축 파일, patchelf 모듈, 창의 소켓, 아이콘)는
    패키저가 더합니다. 런타임 기본값은 25.08입니다.
  - `application.manifest.in`은 DPI 인식과 함께 `asInvoker`를 담고 앱 정보와 설명은 담지 않습니다.
    compose-rust로 만든 모든 Windows 실행 파일이 같은 매니페스트를 담으므로 자리표시자가 없습니다.
  - `AppxManifest.xml.in`은 `{{identity_name}}`(스토어가 예약한 이름), `{{arch}}`, `{{language}}`,
    `{{background_color}}`(기본 `transparent`)를 받습니다.
  - `app.metainfo.xml.in`은 `{{summary}}`, `{{developer_id}}`, `{{metadata_license}}`를 받고,
    `app.desktop.in`의 `Comment=`는 `{{summary}}`입니다.

## 1.11.1-ext.6 (2026-11-23)

### 배포

- Maven Central 릴리스에서 tvOS, watchOS, Kotlin/JS 를 뺍니다(`CENTRAL_SKIP_TARGETS`. `all_targets` 를 켜고
  수동 실행하면 냅니다). 체크섬은 Central 이 요구하는 `.md5`, `.sha1` 만 붙입니다. 1.11.1-ext.5 번들로는
  17,844 개 대신 9,356 개 파일입니다. Wasm, iOS, macOS, Linux, Windows, 데스크톱 JVM, Android 는 그대로입니다.
- 업로드하는 워크플로는 브랜치 푸시, 풀 리퀘스트, 일정으로 시작되지 않습니다. 그렇게 될 수 있으면
  `extended/publish/check_publish_triggers.py` 가 배포 계획 단계를 실패시킵니다. 2026-11-14 이후 아무것도
  시작하지 않던 GitHub Packages 개발 빌드는 워크플로에서 없앴습니다.

### 바뀐 것

- **macOS Kotlin/Native 창에서 크기는 그대로 두고 움직이는 네이티브 뷰를 더 싸게 옮깁니다.** 스크롤처럼 자리만 바뀌면
  `setFrame:` 대신 `setFrameOrigin:`, 크기만 바뀌면 `setFrameSize:`를 부릅니다. 크기를 유지한 뷰 넷을 옮기는 데
  뒤따르는 Core Animation 커밋까지 합해 `setFrame:`로는 프레임당 69~71µs, `setFrameOrigin:`로는 51~61µs가
  들었습니다(400 프레임의 중앙값). 뷰가 놓이는 자리는 같습니다. (DarkPyonix/compose-rust#855)

- **Windows 창이 DPI가 바뀐 뒤 포인트 단위 크기를 지키지 못하던 문제를 고쳤습니다.** `windows-shared` 의
  문제여서 Kotlin/Native 창과 GraalVM 창 모두 해당합니다. 창은 `WM_DPICHANGED` 가 제안하는 사각형을 그대로
  썼는데, Windows는 이 창의 틀을 모른 채 예전 바깥 사각형을 비율대로 늘이거나 줄여 그 사각형을 만듭니다. 그래서
  320x240 포인트 창(200 %에서 640x480 픽셀)이 125 %에서 400x300이 아니라 398x297 픽셀이 되었습니다. 이제 창은
  자기 크기를 기억하고(애플리케이션이 준 크기는 포인트로, 사용자가 끌어 맞춘 크기는 그때 DPI의 픽셀로,
  `win32_dpi.h`), 새 DPI에서 클라이언트 영역을 그 크기로 맞춥니다. 반 픽셀은 X11처럼 올림하고, 위치는 Windows가
  제안한 왼쪽 위이며, 최소 크기보다 작아지지 않습니다. `WM_GETDPISCALEDSIZE` 에도 같은 크기로 답하므로
  Windows가 제안하는 사각형부터 맞고, 적용한 뒤 클라이언트 영역을 재어 틀이 다르면 바깥 크기를 한 번 고칩니다.
  DPI가 여러 번 오가도 크기가 조금씩 어긋나지 않고 처음 크기로 돌아옵니다. 최대화, 최소화, 스냅된 창은 차지한
  자리를 그대로 지키고, 복원될 때 제 크기를 돌려받습니다. (DarkPyonix/compose-rust FR-82)
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
