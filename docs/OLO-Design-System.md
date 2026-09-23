# OLO 디자인 시스템 — 이식용 스펙

> 이 문서 하나로 다른 Android/Jetpack Compose 앱에서 동일한 GUI 컨셉을 재현할 수 있다.
> **사용법**: 새 Claude Code 세션에 이 파일을 통째로 붙여넣고 "이 스펙대로 `ui/theme/Theme.kt`를 만들어 줘"라고 요청하면 된다. 아래 코드 블록은 그대로 복사해 넣어도 동작한다.

---

## 0. 한 줄 요약
따뜻한 **클레이 오렌지** 브랜드색 + **아이보리 웜톤 중립색**. Material3 위에 올리되 **Dynamic Color는 쓰지 않고**(고정·측정된 팔레트), 코너는 Material보다 **타이트**하게, 헤드라인은 **작게**. 색은 "다르기만 한 색"과 "의미를 가진 색"을 분리한다.

## 1. 핵심 원칙 (이게 컨셉의 본질)
1. **Dynamic Color 금지.** 벽지에서 색을 따오면 회색 위 회색이 되어 정보 대비가 무너진다. 팔레트는 고정하고 대비는 직접 고른다.
2. **모든 color role을 채운다.** 비워두면 Material 기본값(보라/라벤더)이 샌다 — 다이얼로그·메뉴·시트가 갑자기 보라색이 되는 버그의 원인. `surfaceContainerHigh`까지 전부 지정한다.
3. **중립색은 웜톤(아이보리).** 따뜻한 브랜드색을 차가운 회색 위에 얹으면 "실수처럼" 보인다. neutral도 green>blue 쪽으로.
4. **에러색은 브랜드와 색상(hue)으로 구분.** 클레이(주황)와 Material 기본 에러(주황빨강)는 이웃이라 삭제/취소 버튼이 같은 색이 된다 → 에러를 **크림슨**으로 당겨 hue+무게로 구분.
5. **의미색 분리.** 상태색(진행/완료/실패)·파일 타일색은 Material role이 아니라 별도 데이터 클래스로 둔다. "같은 앰버 = 항상 일시정지".
6. **대비는 측정한다.** 흰 글자가 올라가는 채도 높은 색은 WCAG AA(4.5:1) 확인. (원 클레이 #C5613F는 4.07:1이라 5% 어둡게 조정함.)

## 2. 색 토큰 (Design Tokens)

### 2.1 시드 색
```
Clay        #B95B3B   // 브랜드 (라이트 primary)
ClayLight   #E8A183   // 브랜드 (다크 primary / inversePrimary)
Stone       #6E5C50   // 보조 웜 그레이 (라이트 secondary)
StoneLight  #D6C3B4   // 보조 (다크 secondary)
Teal(3차)   #3E7F80   // tertiary
Crimson     #A50E2E   // error (라이트)
```

### 2.2 라이트 ColorScheme
```
primary               #B95B3B    onPrimary              #FFFFFF
primaryContainer      #F6E0D6    onPrimaryContainer     #4A1E0C
inversePrimary        #E8A183
secondary             #6E5C50    onSecondary            #FFFFFF
secondaryContainer    #EFE6DE    onSecondaryContainer   #2A211B
tertiary              #3E7F80    onTertiary             #FFFFFF
tertiaryContainer     #CDE5E4    onTertiaryContainer    #0A2C2C
background            #F7F4EF    onBackground           #1D1A16
surface               #F7F4EF    onSurface              #1D1A16
surfaceVariant        #ECE5DC    onSurfaceVariant       #574D45
surfaceTint           #B95B3B
surfaceBright         #FBF9F5    surfaceDim             #DFD8CC
surfaceContainerLowest#FFFFFF    surfaceContainerLow    #FCFAF6
surfaceContainer      #F3EFE8    surfaceContainerHigh   #EDE8DF
surfaceContainerHighest #E7E1D6
inverseSurface        #35302A    inverseOnSurface       #F5F1EA
outline               #8B7F74    outlineVariant         #D6CCC1
scrim                 #000000
error                 #A50E2E    onError                #FFFFFF
errorContainer        #F8DCDA    onErrorContainer       #3B100B
```

### 2.3 다크 ColorScheme
```
primary               #E8A183    onPrimary              #4A1E0C
primaryContainer      #8A3E22    onPrimaryContainer     #FBE0D4
inversePrimary        #B95B3B
secondary             #D6C3B4    onSecondary            #2A211B
secondaryContainer    #52443A    onSecondaryContainer   #EFE0D4
tertiary              #86CFCF    onTertiary             #08292A
tertiaryContainer     #2A5455    onTertiaryContainer    #CDE5E4
background            #181613    onBackground           #E8E3DA
surface               #181613    onSurface              #E8E3DA
surfaceVariant        #49423A    onSurfaceVariant       #CFC5B9
surfaceTint           #E8A183
surfaceBright         #3D362E    surfaceDim             #141210
surfaceContainerLowest#0F0E0B    surfaceContainerLow    #1C1A16
surfaceContainer      #211E1A    surfaceContainerHigh   #2B2723
surfaceContainerHighest #363029
inverseSurface        #E8E3DA    inverseOnSurface       #181613
outline               #978C80    outlineVariant         #49423A
scrim                 #000000
error                 #FFB0BE    onError                #5E001C
errorContainer        #7E2A24    onErrorContainer       #FFDAD3
```

### 2.4 의미색 — 상태 (StatusColors, Material role 아님)
| 역할 | 라이트 | 다크 | 비고 |
|---|---|---|---|
| running(진행) | #1273BE | #7FC0F5 | 브랜드(따뜻)와 반대쪽 쿨블루 |
| waiting(대기) | #7A6F65 | #A79C90 | |
| paused(일시정지) | #9C7A0C | #E0BE52 | 브랜드와 안 겹치게 더 노랗게 |
| done(완료) | #1B7F4B | #6FD79B | |
| failed(실패) | #9E0C2B | #FFB0BE | |
| progressTrack | #DCD3C6 | #39434D | 진행바 빈 부분은 "보이게" |

### 2.5 의미색 — 파일 타일 (TileColors, 9색, hue로 구분)
| 종류 | 라이트 | 다크 | 다크는 흰 글자용으로 **더 밝게** |
|---|---|---|---|
| folder | #B95B3B | #D1734F | |
| archive | #8A6A3B | #B08A54 | |
| image | #2E8B6B | #3FA383 | |
| video | #6A5A9E | #8A7AC0 | |
| audio | #B04A6A | #C96B88 | |
| document | #55606B | #6E7A86 | |
| code | #3E7F80 | #55A0A1 | |
| app | #4C7A3E | #69985A | |
| other | #7A7168 | #938A80 | |

## 3. 모양 (Shapes) — Material보다 한 단계 타이트
```
extraSmall  6dp    small 10dp    medium 14dp    large 18dp    extraLarge 20dp
```
> Material 기본 다이얼로그는 28dp라 10~12dp 행·칩 위에 얹으면 "다른 앱"처럼 보인다. large 쪽을 당겨 한 몸처럼.

## 4. 타이포 — 헤드라인만 축소 (본문은 Material 유지)
```
headlineLarge  28sp / line 34    headlineMedium 24sp / 30
headlineSmall  20sp / 26         titleLarge     19sp / 25
```
> 나머지 스케일은 Material 기본값이 이미 맞다. 헤드라인이 너무 커서 삭제 확인 다이얼로그가 주변보다 2배 큰 글자로 뜨는 걸 방지.

## 5. 그대로 복사 가능한 Theme.kt 골격
```kotlin
@Composable
fun OloTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 앱바가 브랜드색이 아니라 surface라, 시스템 아이콘은 테마를 따른다.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,  // §2.2 / §2.3
        shapes = OloShapes,          // §3
        typography = OloTypography,  // §4
        content = content,
    )
}

// 의미색은 MaterialTheme 확장 프로퍼티로 노출 (배경 명도로 라이트/다크 판별)
val MaterialTheme.status: StatusColors @Composable get() =
    if (colorScheme.background.luminanceIsDark()) DarkStatus else LightStatus
val MaterialTheme.tiles: TileColors @Composable get() =
    if (colorScheme.background.luminanceIsDark()) DarkTiles else LightTiles

private fun Color.luminanceIsDark() = (red*0.299f + green*0.587f + blue*0.114f) < 0.5f
```
`LightColors`/`DarkColors`는 `lightColorScheme(...)`/`darkColorScheme(...)`에 §2.2/§2.3의 **모든 role을 빠짐없이** 채워 만든다(원칙 #2). `StatusColors`/`TileColors`는 위 표를 필드로 갖는 data class.

## 6. 공용 컴포넌트 규칙 (얇게, 일관되게)
- **OloDialog**: Material `AlertDialog` 래퍼. `title`(name) + 선택적 `detail`(한 문장, 결과 위주) + `content` + `action`(버튼). 취소 라벨 기본 제공. 모든 확인/질문은 이걸 통해서만 → 화면마다 다이얼로그가 제각각이 되는 걸 방지.
- **OloTextField**: Material `OutlinedTextField` 단일 래퍼. `singleLine=true` 기본, 공용 높이·패딩. **모든 화면은 이 필드로만 입력**받는다(테스트로 강제) → 폼 안에서 높이가 6개는 이렇고 3개는 저런 불일치 방지.
- 규칙: 새 컴포넌트는 색·모양을 직접 고르지 말고 `MaterialTheme.colorScheme`/`.status`/`.tiles`/`.shapes`에서 가져온다.

## 7. 품질 가드(권장)
- **대비 테스트**: 흰 글자가 올라가는 색(primary, tab indicator, FAB 아이콘, 선택칩)은 4.5:1 이상을 단위테스트로 고정.
- **누락 role 테스트**: ColorScheme의 모든 role이 지정됐는지 검사(보라색 누출 방지).
- **공용 컴포넌트 강제**: 소스 스캔으로 `TextField(`/`AlertDialog(` 직접 사용 금지, `Olo*`만 허용.

---
_원본: OLO Explorer 앱의 `app/src/main/kotlin/.../ui/theme/Theme.kt`. 이 스펙은 그 파일에서 추출한 이식용 요약이다._
