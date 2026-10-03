# compose-multiplatform-core-extended

[JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)
를 thisisthepy 가 포크한 저장소입니다. 작업은 `extended` 브랜치에 있으며, JetBrains
`release/1.11` 의 `73ac849` ("Copy Jetpack Compose 1.11.2") 위에 쌓여 있습니다. 저장소 루트는
업스트림 그대로 둡니다. 포크가 더한 것은 Compose 모듈 안의 플랫폼 소스 세트이거나 `extended/`
아래의 스크립트입니다.

English: [README.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README.md)

## 더한 것

| 항목 | 상태 | 위치 |
|---|---|---|
| Compose UI 모듈의 `linuxX64`, `linuxArm64` 타깃 | 구현 | 모듈 소스 세트 |
| Compose UI 모듈의 `mingwX64` 타깃과 그에 맞춘 skiko | 구현 | 모듈 소스 세트, [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| macOS 의 네이티브 텍스트 컨텍스트 메뉴 (`NSMenu`) | 구현 | `foundation` |
| GraalVM native image 용 정적 아카이브로 묶은 skiko JVM 네이티브 (macOS arm64, Linux x64, Windows x64) | 구현 | [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| Windows 정적 아카이브에 컴파일해 넣은 Skia 의 ICU 데이터 | 구현 | `extended/skiko/embedded_icu.cpp` |
| Windows 용 Compose 창 프로시저 (캡션, 제목 표시줄, 아이콘) | 계획 | 브랜치 [`feature/windows-window-chrome`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/feature/windows-window-chrome), [DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26) |
| `org.thisisthepy.compose` 좌표 | 계획 | 브랜치 [`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/chore/thisisthepy-coordinates) |
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

### Windows 창 크롬 (계획)

`feature/windows-window-chrome` 브랜치는 Windows 용 Compose 창 프로시저를 정적 skiko 아카이브에
더합니다. 이것이 있으면 콘텐츠가 캡션 아래까지 들어가 제목 표시줄을 직접 그리고, 테두리 없는 창이 어느
가장자리에서든 최소 크기를 지키며, 소유자 없는 창에 애플리케이션 아이콘이 붙습니다. 아직 `extended` 에
병합되지 않았습니다.

### 디자인 시스템 (계획)

디자인 시스템은 이 포크의 라이브러리로 들어올 예정이며,
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

### Linux 또는 macOS 용 Compose 모듈

모듈마다 배포가 두 개 필요합니다. 하나는 klib 을 담은 타깃 자체의 배포이고, 하나는 어떤 타깃이 있는지
알려 주는 루트 배포입니다. 루트가 없으면 사용하는 쪽은 모듈이 그 플랫폼을 지원하지 않는다는 답을 받습니다.

```sh
./gradlew --no-daemon --no-configuration-cache \
    -Pjetbrains.publication.version.COMPOSE=1.11.1 \
    -Pjetbrains.publication.version.COMPOSE_MATERIAL3=1.11.0-alpha07 \
    :compose:ui:ui:publishLinuxX64PublicationToMavenLocal \
    :compose:ui:ui:publishKotlinMultiplatformPublicationToMavenLocal
```

애플리케이션이 그리는 데 쓰는 모듈마다 두 태스크를 반복합니다. `linuxX64` 에서는
`compose:animation:animation`, `animation-core`, `compose:foundation:foundation`,
`foundation-layout`, `compose:material:material-ripple`, `compose:material3:material3`,
`compose:ui:ui`, `ui-backhandler`, `ui-geometry`, `ui-graphics`, `ui-text`,
`ui-tooling-preview`, `ui-unit`, `ui-util` 입니다. `macosArm64` 에서는
`publishMacosArm64PublicationToMavenLocal` 을 씁니다. 거기서는 `compose:foundation:foundation` 과
`compose:ui:ui` 만 JetBrains 빌드와 다릅니다. Material 2 의 navigation, adaptive 계열, navigation
suite 는 Linux 용으로 빌드할 수 없습니다. 각각 Linux 변형이 없는 배포 아티팩트에 의존하기 때문입니다.

[compose-rust 의 `build-compose.sh`](https://github.com/DarkPyonix/compose-rust/blob/develop/renderer/scripts/build-compose.sh)
가 포크의 고정 커밋에 대해 바로 이 과정을 실행합니다.

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

지금 포크는 JetBrains 자신의 그룹과, 대신하는 버전 그대로 배포합니다. 그래서 로컬 저장소를 먼저 읽는
빌드는 패치한 모듈을 가져가고, 나머지는 JetBrains 에서 받습니다.

| 대상 | 좌표 |
|---|---|
| Compose 모듈 | `org.jetbrains.compose.<group>:<artifact>:1.11.1`. 예: `org.jetbrains.compose.ui:ui:1.11.1` 과 그 타깃 `ui-linuxx64`, `ui-mingwx64`, `ui-macosarm64` |
| Material 3 (위 속성을 줄 때) | `org.jetbrains.compose.material3:material3:1.11.0-alpha07` |
| `mingwX64` 용 skiko | `org.jetbrains.skiko:skiko-mingwx64:0.144.6`. skiko 루트 메타데이터에 이 타깃을 더합니다 |

`jetbrains.publication.version.COMPOSE=1.11.1` 은 `gradle.properties` 에 있습니다. 아직 공개 저장소에
올린 것은 없습니다. 사용하는 쪽은 `mavenLocal()` 을 맨 앞에 둡니다.

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
        implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
        implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    }
}
```

**상태: 계획.**
[`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/chore/thisisthepy-coordinates)
브랜치가 모든 것을 `org.thisisthepy.compose` 로 옮깁니다. JetBrains 가 빌드하지 않은
`org.jetbrains.compose` 아티팩트는 진짜와 구별할 수 없기 때문입니다. 그룹은
`org.thisisthepy.compose.<group>` (`org.jetbrains.androidx` 계열은
`org.thisisthepy.compose.androidx.<library>`) 이 되고, 버전은 `<upstream version>-ext.<N>` 이 됩니다.
예를 들어 `org.thisisthepy.compose.ui:ui:1.11.1-ext.1` 입니다. skiko 는 `org.jetbrains.skiko` 를
유지합니다. 모든 좌표는 그 브랜치의
[`extended/COORDINATES.md`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/chore/thisisthepy-coordinates/extended/COORDINATES.md)
에 있습니다.
