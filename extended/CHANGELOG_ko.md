# 변경 기록

`extended/` 아래 포크 자체 모듈의 변경입니다. 최신이 위에 있습니다. upstream Compose 의 변경은
upstream 의 릴리스 노트에 있습니다.

## 출시 전

### 바뀐 것

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
