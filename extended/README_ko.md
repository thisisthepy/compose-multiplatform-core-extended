# compose-multiplatform-core-extended

GraalVM native-image 와 Kotlin/Native 에서 AWT 없이 동작하는 데스크톱 창을 갖춘 Compose Multiplatform 라이브러리입니다.

English: [README.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README.md)

## 기능

상태는 2026-10-17 기준입니다. 진행 중이거나 계획인 항목은 아직 `extended` 에 없습니다.

- AWT 없는 데스크톱 애플리케이션으로 가는 두 경로: GraalVM native-image 와 Kotlin/Native. 어느 경로든 애플리케이션을 실행 파일 하나로 내보내며, 옆에 Java 런타임을 두지 않고 시작할 때 풀어내는 것도 없습니다.
- 두 경로의 AWT 없는 데스크톱 창. macOS 는 두 경로 모두 동작합니다(Kotlin/Native 창은 AppKit 과 Metal, GraalVM native-image 창). Linux(X11)도 두 경로 모두 동작합니다(GraalVM 창, 그리고 Xlib, GLX, XIM, AT-SPI 를 쓰는 Kotlin/Native 창). Windows 는 진행 중이며, 공유 Win32 창 `windows-shared` 는 아직 병합되지 않았습니다([#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32)). 병합될 때까지 compose-rust 의 Kotlin/Native 렌더러가 자기 Windows 창을 유지합니다([DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26)).
- 창 모듈: `extended/window` 아래에 있고 그룹은 `org.thisisthepy.compose.window` 입니다. 창, 포인터와 키와 IME 입력, 텍스트 메뉴와 편집 메뉴, 클립보드, 파일 드롭, 창 크롬이 어느 렌더러도 아닌 이곳에 있습니다. `common` 모듈에는 창 상태 기계, 최소 크기, 이벤트 정규화, 프레임 요청 병합, IME 조합 상태, 다크 모드와 DPI 가 있고, Kotlin 표준 라이브러리에만 의존합니다. 두 경로가 이를 함께 씁니다. 같은 검사를 양쪽에 실행하는 패리티 테스트는 계획입니다([#70](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/70)). [창 모듈](#창-모듈) 을 보십시오.
- Windows 실행 파일은 두 경로 모두 MSVC 가 링크합니다. JetBrains 가 Windows 용 Skia 를 MSVC 로 빌드하므로 최종 링크는 MSVC 의 것이고, MinGW 경로는 없습니다. GraalVM 경로는 지금 이렇게 링크하고, Kotlin/Native 링크 단계는 열려 있습니다([MinGW x64 타깃](#mingw-x64-타깃) 참고).
- 디자인 시스템과 Liquid Glass: Material 3, Cupertino, Fluent, GNOME, Breeze, Deepin 테마와 Liquid Glass 소재를 `extended/design-systems` 의 독립 라이브러리로 제공합니다. 배포된 Compose 에만 의존하고 렌더러에는 절대 의존하지 않으므로, 따로 배포할 수 있습니다.
- JAWT 없는 skiko: native image 가 링크하는 정적 skiko 아카이브로, JDK 의 `libjawt` 에 의존하지 않습니다.
- AWT 없는 클립보드와 포인터 아이콘: AWT 없는 창도 복사, 붙여넣기, 커서 변경을 할 수 있습니다.
- Compose UI 모듈의 `linuxX64`, `linuxArm64`, `mingwX64` Kotlin/Native 소스 세트가 트리에 있습니다. Linux 것은 CI 에서 빌드합니다. "Extended window linux" 워크플로가 이를 먼저 배포한 다음 `native/linux` 를 빌드하고 테스트합니다. `mingwX64` 것을 빌드하는 CI 작업은 아직 없고, 이것으로 링크한 렌더러를 Windows 에서 실행하는 일은 [#3](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/3) 에서 열려 있습니다.
- 업스트림 공개 API 가드: 워크플로가 Compose 모듈의 공개 API 를 업스트림과 비교하므로 기존 Compose 코드가 계속 컴파일됩니다. 포크 자체의 API 는 `extended/` 아래 별도 모듈에 있습니다.

이를 쓰는 애플리케이션을 패키징하려면 Gradle 플러그인 포크
[compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README_ko.md)
를 보십시오.

## 이 포크에 대하여

[JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)
를 thisisthepy 가 포크한 저장소입니다. 작업은 `extended` 브랜치에 있으며, JetBrains
`release/1.11` 의 `73ac849` ("Copy Jetpack Compose 1.11.2") 위에 쌓여 있습니다. 저장소 루트는
업스트림 그대로 둡니다. 포크가 더한 것은 Compose 모듈 안의 플랫폼 소스 세트이거나 `extended/`
아래의 스크립트입니다.

## 더한 것

| 항목 | 상태 | 위치 |
|---|---|---|
| Compose UI 모듈의 `linuxX64`, `linuxArm64` 타깃 | 구현 | 모듈 소스 세트 |
| Compose UI 모듈의 `mingwX64` 타깃과 그에 맞춘 skiko | 구현 | 모듈 소스 세트, [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| macOS 의 네이티브 텍스트 컨텍스트 메뉴 (`NSMenu`) | 구현 | `foundation` |
| GraalVM native image 용 정적 아카이브로 묶은 skiko JVM 네이티브 (macOS arm64, Linux x64, Windows x64) | 구현 | [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| Windows 정적 아카이브에 컴파일해 넣은 Skia 의 ICU 데이터 | 구현 | `extended/skiko/embedded_icu.cpp` |
| 툴킷 없는 native image 에 Swing 과 `UIManager` 가 남지 않도록 AWT 스위치를 단 skiko-awt | 구현 | `extended/skiko/build-skiko-awt.sh` |
| 창 모듈: 두 경로의 창, 입력, IME, 메뉴, 크롬 | macOS 와 Linux 구현, Windows 는 [#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32) 에서 열려 있음 | [`extended/window`](#창-모듈) |
| Windows 용 Compose 창 프로시저 (캡션, 제목 표시줄, 아이콘) | 계획 | 브랜치 [`feature/windows-window-chrome`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/feature/windows-window-chrome), [DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26) |
| `org.thisisthepy.compose` 좌표, 버전은 `<upstream version>-ext.<N>` | 구현됨 | [`extended/COORDINATES.md`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/COORDINATES.md), [Maven 좌표](#maven-좌표) |
| compose-rust에 있던 그대로의 디자인 시스템 여섯 개와 Liquid Glass 재질 | 가져옴 | [`extended/design-systems`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/design-systems/README.md) |
| `org.thisisthepy.compose.*` 라이브러리로 나오는 디자인 시스템 | 계획 | [DarkPyonix/compose-rust#39](https://github.com/DarkPyonix/compose-rust/issues/39) |

### Linux 타깃

JetBrains 는 Linux 용 Kotlin/Native 타깃을 배포하지 않습니다. 빌드할 수 없어서 빠진 것이 아니라,
다른 타깃에는 모두 있는 몇 가지 플랫폼 선언이 없을 뿐입니다. 포크가 그 선언을 채웁니다. 키 테이블,
로케일, 클립보드 (세션에 있는 `wl-copy`, `xclip`, `xsel` 중 하나), URI 핸들러 (`xdg-open`),
드래그 앤 드롭, 포커스, 포인터 아이콘, 트레이싱, 폰트 리졸버, 문자열 델리게이트입니다. 스냅샷 적용
알림을 보낼 디스패처도 따로 둡니다. Linux 에는 그 알림을 보낼 메인 런 루프가 없기 때문입니다.

### MinGW x64 타깃

Kotlin/Native 가 Windows 에서 지원하는 타깃은 MinGW 하나뿐인데, Compose 도 skiko 도 이 타깃을
배포하지 않습니다. 플랫폼 선언 대부분은 Linux 것을 그대로 씁니다. Windows 고유의 것은 여섯 가지로,
스레드 식별, 휠 이동량, 포인터 아이콘, URI 핸들러, 로케일, Win32 클립보드입니다.

skiko 는 ABI 가 다른 두 부분으로 나뉩니다. Kotlin 쪽은 고정한 skiko (v0.144.6) 에 패치를 적용해
`mingwX64` 로 빌드합니다. C++ 쪽은 MSVC 모드로 컴파일해 정적 라이브러리 `skiko-bridges.lib` 로
만듭니다. JetBrains 가 Windows 용 Skia 를 MSVC 로 빌드하기 때문입니다. skiko 를 포크하지 않은
이유와 두 부분이 만나는 방식은
[extended/skiko/README.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md)
에 있습니다.

Windows 실행 파일의 최종 링크는 어느 경로로 빌드했든 MSVC 의 것입니다. Windows 의 GraalVM native
image 는 지금 MSVC 가 링크합니다. Kotlin/Native `mingwX64` 실행 파일도 같은 방식으로 링크합니다.
Kotlin/Native 의 MinGW 오브젝트를 MSVC 링커가 받아들이도록 고쳐 쓰고, MSVC 링커가 이를 Skia 와
skiko 의 MSVC 쪽과 함께 링크합니다. MinGW 경로는 없습니다. MinGW 만으로 링크하려면 MinGW 용으로
빌드한 Skia 가 있어야 하는데, JetBrains 는 이를 만들지 않기 때문입니다. **상태: 열림.** Kotlin/Native
용 Gradle 플러그인의 MSVC 링크 단계는
[compose-multiplatform-extended#31](https://github.com/thisisthepy/compose-multiplatform-extended/pull/31)
에 있습니다.

이것으로 링크한 렌더러를 Windows 에서 직접 실행하는 확인은
[#3](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/3) 에 남아 있습니다.
지금까지는 Wine 에서만 실행했습니다.

### native image 용 정적 skiko

skiko 는 JVM 네이티브를 jar 안의 공유 라이브러리로 배포하고, 데스크톱 로더가 이를 풀어서 경로로
엽니다. 단일 실행 파일은 두 번째 파일을 가질 수 없습니다. 그래서 `build-skiko-static-jvm.sh` 가
그 라이브러리를 링크하는 오브젝트와 미리 빌드된 Skia 를 아카이브로 묶고, native image 가 이를 링크해
넣습니다. 오브젝트 하나만 바꿉니다. skiko 의 `jawt.o` 는 `<java.home>/lib/libjawt` 를 경로로 여는데,
native image 에는 `java.home` 이 없습니다. 그래서 `static_jawt.c` 가 링크된 `JAWT_GetAWT` 를 직접
부릅니다.

Windows 에서 Skia 는 실행 파일 옆의 `icudtl.dat` 를 찾고, 없으면 멈춥니다. `embedded_icu.cpp` 가 이
데이터를 `#embed` 로 아카이브에 컴파일해 넣고 메모리에서 ICU 에 넘깁니다. 그래서 실행 파일은 데이터
파일이 필요 없습니다.

이 아카이브를 쓰는 Gradle 플러그인 태스크는
[thisisthepy/compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README_ko.md)
의 `packageNativeImage` 입니다.

### 창 모듈

데스크톱 창 코드는 `extended/design-systems` 처럼 독립 Amper 프로젝트인 `extended/window` 에
있습니다. 창, 포인터와 키 입력, IME, 텍스트 컨텍스트 메뉴와 편집 메뉴, 클립보드, 파일 드롭, 창 크롬,
접근성이 모두 여기에 있습니다. 여기 있는 어떤 것도 렌더러나 그 와이어 프로토콜, 노드 테이블에 의존할
수 없습니다(`extended/window/project.yaml` 에 그렇게 적혀 있습니다). 그래서 어떤 Compose
애플리케이션이든 이것으로 창을 열 수 있습니다.

| 모듈 | 아티팩트 | 플랫폼 | 내용 |
|---|---|---|---|
| `common` | `org.thisisthepy.compose.window:common` | JVM, `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` | `WindowPlatform`, 이벤트와 상태와 정책 타입, 모든 플랫폼 계층이 함께 쓰는 규칙. 툴킷도 Compose 도 없습니다 |
| `scene` | `org.thisisthepy.compose.window:scene` | JVM, `macosArm64` | `WindowPlatform` 으로 연 창에 Compose 장면을 올립니다. 프레임 루프, 그리고 Compose 의 플랫폼 텍스트 입력을 거치는 포인터, 키, IME 텍스트 |
| `native/macos` | `org.thisisthepy.compose.window:native-macos` | `macosArm64` | Kotlin/Native 창: AppKit, Metal, 네이티브 텍스트 메뉴와 편집 메뉴, 창 크롬 |
| `native/linux` | `org.thisisthepy.compose.window:native-linux` | `linuxX64`, `linuxArm64` | Kotlin/Native 창: Xlib, GLX, XIM, D-Bus 위의 AT-SPI |
| `graalvm/graalvm-macos` | `org.thisisthepy.compose.window:graalvm-macos` | JVM (native image) | native image 용 AppKit 창: `appkit_window.m` 과 그 Kotlin 쪽, 그리고 `scene`이 그리는 Metal 표면 `appKitWindowSurface` |
| `graalvm/graalvm-linux` | `org.thisisthepy.compose.window:graalvm-linux` | JVM (native image) | native image 용 X11 창: `x11_window.c` 와 그 Kotlin 쪽, 그리고 `scene`이 그리는 GL 표면 `x11WindowSurface` |

버전은 모두 `0.1.0` 입니다. "Extended window publish" 워크플로가 compose-rust 가 쓰는 방식대로 이를
로컬 Maven 저장소에 배포합니다. `native/linux` 는 [#111](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/111) 에서 여기에 더해집니다.
아직 공개 저장소에 올린 것은 없습니다. Windows 창 `windows-shared` 는 [#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32) 에서
열려 있습니다.

### Windows 창 크롬 (계획)

`feature/windows-window-chrome` 브랜치는 Windows 용 Compose 창 프로시저를 정적 skiko 아카이브에
더합니다. 이것이 있으면 콘텐츠가 캡션 아래까지 들어가 제목 표시줄을 직접 그리고, 테두리 없는 창이 어느
가장자리에서든 최소 크기를 지키며, 소유자 없는 창에 애플리케이션 아이콘이 붙습니다. 아직 `extended` 에
병합되지 않았습니다.

### 디자인 시스템

compose-rust가 `design-systems/` 디렉터리에 두던 디자인 시스템은 바뀐 것 없이
[`extended/design-systems`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/design-systems/README.md) 에 있습니다. 자기 래퍼와 자기 워크플로를 가진 독립 Amper 프로젝트이고,
이 저장소의 모듈이 아니라 배포된 Compose를 대상으로 빌드합니다. Liquid Glass 재질도 `liquid-glass`
모듈로 여기에 있습니다. 어느 것도 렌더러에 의존하지 않고, 그렇게 만들 것을 더해서도 안 됩니다. 그래야
디자인 시스템을 따로 배포할 수 있기 때문입니다. 여기서부터 라이브러리가 되며,
[DarkPyonix/compose-rust#39](https://github.com/DarkPyonix/compose-rust/issues/39) 에서 추적합니다.

- `org.thisisthepy.compose.designsystem`: 공통 계약 (`DesignSystem` 인터페이스, 역할 enum, 토큰).
- 1층, 시스템마다 하나씩: `org.thisisthepy.compose.material3` (`androidx.compose.material3` 위의
  어댑터), `.cupertino`, `.fluent`, `.gnome`, `.breeze`, `.deepin`, `.liquidglass`. 각각 자기
  `XxxTheme`, 색 구성표, 타이포그래피, 도형, 컴포넌트를 가집니다.
- 2층, `org.thisisthepy.compose.adaptive`: `AdaptiveTheme` 이 플랫폼의 시스템을 고르고, 중립
  컴포넌트가 1층에 위임합니다.

1층은 `adaptive` 에 의존하지 않습니다. `adaptive` 가 1층 전체에 의존하기 때문입니다. 창 크기 클래스를
다루는 JetBrains 의 `androidx.compose.material3.adaptive` 와는 다른 것입니다.

## 빌드

아래 명령은 `extended` 체크아웃의 저장소 루트에서 실행합니다. 따로 적지 않으면 `JAVA_HOME` 에 JDK 17
이 있어야 합니다. 모든 빌드는 로컬 Maven 저장소 (`~/.m2/repository`) 에 배포합니다.

### Linux, macOS 또는 데스크톱 JVM 용 Compose 모듈

```sh
extended/compose/publish-local.sh linuxX64          # 또는 macosArm64, linuxArm64, mingwX64, desktop
python3 extended/compose/check-local.py             # ~/.m2 에 남은 것이 빠짐없는지
```

애플리케이션이 그리는 데 쓰는 모듈 전부(foundation, Material 3, 그리고 그 둘이 닿는 것)를 지정한
타깃으로, `gradle.properties` 의 버전으로 배포합니다. `--list` 는 실행하지 않고 Gradle 태스크만
출력합니다. 모듈마다 배포가 두 개입니다. 하나는 klib 이나 jar 를 담은 타깃 자체의 배포이고, 하나는
어떤 타깃이 있는지 알려 주는 루트 배포입니다. 루트가 없으면 사용하는 쪽은 모듈이 그 플랫폼을 지원하지
않는다는 답을 받습니다. Material 2 의 navigation, adaptive 계열, navigation suite 는 목록에 없습니다.
각각 Linux 변형이 없는 배포 아티팩트에 의존하기 때문입니다.

runtime, `runtime-saveable`, 내부용 annotation 과 collection 라이브러리도 목록에 있습니다. 클래스는
없습니다. JetBrains 의 것과 같이 androidx 아티팩트를 가리키는 호환 스텁이고, 위 모듈들이 이 포크의
버전으로 그것들을 찾기 때문입니다.

창 모듈을 빌드하는 CI 작업은 `.github/actions/fork-compose` 로 이것을 실행하고, Compose 소스가 바뀔
때까지 결과를 캐시합니다.
[compose-rust 의 `build-compose.sh`](https://github.com/DarkPyonix/compose-rust/blob/develop/renderer/scripts/build-compose.sh)
가 포크의 고정 커밋에 대해 이것을 실행합니다.

### mingwX64

skiko 를 먼저, 그다음 Compose 모듈을 빌드합니다. Compose 빌드는 `org.jetbrains.skiko` 를 Maven Central
보다 로컬 Maven 저장소에서 먼저 읽습니다 (`buildSrc/repos.gradle`). 먼저 찾은 루트 메타데이터가 이기고,
`mingw_x64` 를 담은 것은 로컬 쪽뿐이기 때문입니다.

```sh
extended/skiko/build-skiko-mingw.sh <work-dir>          # 처음부터 다시 하려면 --clean
```

이 스크립트는 `org.jetbrains.skiko:skiko-mingwx64:0.144.6` 을 배포하고, C++ 쪽을
`<work-dir>/out/windows-x64/` (`skiko-bridges.lib`, `skia/`, `skia-include/`) 에 씁니다. git, curl,
unzip, python3, JDK 17 또는 21, `~/.konan` 의 Kotlin/Native LLVM, 그리고 cargo-xwin 이 배치한 MSVC
런타임과 Windows SDK 가 필요합니다 (`cargo xwin build --target x86_64-pc-windows-msvc` 를 한 번
실행하거나 `XWIN_DIR` 을 지정합니다).

그다음 위와 같이 모듈마다 배포하되, Linux 태스크 대신 `publishMingwX64PublicationToMavenLocal` 을 씁니다.

### native image 용 정적 skiko

```sh
extended/skiko/build-skiko-static-jvm.sh <work-dir>
```

실행하는 호스트용으로 빌드합니다. macOS arm64, Linux x64, Windows x64 중 하나입니다. 결과는
`<work-dir>/out/<os>-<arch>/` 에 있는 `libskiko-static.a` (Windows 에서는 `skiko-static.lib`) 와
`skia/` 입니다. 각각을 어떻게 링크해야 하는지는 skiko README 에 있습니다.

- 모든 호스트: git, 그리고 `JAVA_HOME` 의 JDK 17 또는 21.
- Linux x64: g++, ar, 그리고 X11, GL, fontconfig, dbus 개발 헤더.
- Windows x64: MSVC 도구가 PATH 에 있는 Git Bash (Developer 프롬프트), 그리고 PATH 의 `clang-cl`.
  skiko 가 Windows 바인딩을 `clang-cl` 로 컴파일하기 때문입니다 (`winget install LLVM.LLVM`).
  `clang-cl` 이 없으면 스크립트가 처음에 멈춥니다.

## Maven 좌표

이 포크가 빌드하는 것은 모두 `org.thisisthepy.compose` 아래로 배포하고, JetBrains 그룹으로는 내지
않습니다. JetBrains 가 빌드하지 않은 `org.jetbrains.compose` 아티팩트는 진짜와 구별할 수 없고, 둘 중
버전이 높은 쪽이 말없이 다른 쪽을 대신하기 때문입니다. 아티팩트 이름, 패키지, 클래스, 시그니처는
JetBrains 의 것 그대로이고 그룹과 버전만 다릅니다. 모든 좌표는 [`COORDINATES.md`](COORDINATES.md)
에 있습니다.

| 대상 | 좌표 |
|---|---|
| Compose 모듈 | `org.thisisthepy.compose.<group>:<artifact>:1.11.1-ext.1`. 예: `org.thisisthepy.compose.ui:ui:1.11.1-ext.1` 과 그 타깃 `ui-desktop`, `ui-linuxx64`, `ui-mingwx64`, `ui-macosarm64` |
| Material 3 | `org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1` |
| JetBrains 가 `org.jetbrains.androidx.*` 로 내는 라이브러리 | `org.thisisthepy.compose.androidx.<library>`. 예: `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime-compose:2.11.0-beta01-ext.1` |
| runtime, `runtime-saveable`, annotation, collection | `org.thisisthepy.compose.runtime:runtime:1.11.1-ext.1` 등. JetBrains 의 것과 같이 클래스 없이 `androidx.compose.runtime` 을 가리키는 스텁입니다 |
| 창 모듈 | `org.thisisthepy.compose.window:<module>:0.1.0` |
| `mingwX64` 용 skiko | `org.jetbrains.skiko:skiko-mingwx64:0.144.6`. skiko 루트 메타데이터에 이 타깃을 더합니다 |
| AWT 스위치를 단 skiko-awt | `org.jetbrains.skiko:skiko-awt:0.144.6.1`. `extended/skiko/build-skiko-awt.sh` 가 만듭니다 |

버전은 `<upstream version>-ext.<N>` 입니다. 앞은 JetBrains 가 같은 소스를 낸 버전이고, `N` 은 그
버전에 대한 이 포크의 릴리스 횟수입니다. 릴리스가 아닌 로컬 빌드는 `-Pextended.publication.dev=true`
를 주고 `-ext.<N>-dev` 로 배포합니다. 접미사가 없는 버전은 거부합니다.

skiko 는 JetBrains 그룹을 유지합니다. 모든 Compose 모듈이 skiko 를 그 그룹으로 찾기 때문입니다.
그래서 패치한 skiko-awt 는 자기 버전 `0.144.6.1` 을 받습니다. 이 버전은 Gradle 에서도 Maven 에서도
JetBrains 의 `0.144.6` 보다 높게 정렬되므로, 이것을 지정한 쪽은 Compose 가 `0.144.6` 을 끌어와도 이것을
받습니다. 네이티브는 JetBrains 의 `0.144.6` 런타임 jar 입니다.

아직 공개 저장소에 올린 것은 없습니다. 사용하는 쪽은 `mavenLocal()` 을 맨 앞에 둡니다.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}
```

```kotlin
// build.gradle.kts, Kotlin Multiplatform 프로젝트
kotlin {
    linuxX64()
    sourceSets.commonMain.dependencies {
        implementation("org.thisisthepy.compose.foundation:foundation:1.11.1-ext.1")
        implementation("org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1")
    }
}
```

[compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README_ko.md)
의 Gradle 플러그인은 별칭(`compose.foundation`, `compose.desktop.currentOs` 등)을 이 좌표로 풉니다.

치르는 값: 서드파티 Compose 라이브러리는 `org.jetbrains.compose` 에 맞춰 빌드되어 있습니다. 이 포크와
함께 쓰면 JetBrains 의 Compose UI 가 한 벌 더 들어오고, 그룹이 달라 어떤 리졸버도 둘을 합치지
못합니다. 이 포크와 함께 쓰는 것은 이 포크에 맞춰 빌드해야 합니다.
