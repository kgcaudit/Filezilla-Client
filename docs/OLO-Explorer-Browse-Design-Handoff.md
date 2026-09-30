# OLO Explorer — 탐색기 디자인 구성 인수인계 (OLO Player용)

목적: **OLO Player가 로컬·서버를 탐색하는 화면의 "디자인 구성"을 OLO Explorer와
일관되게** 만들도록, 화면 레이아웃·경로 표현·파일 목록 표현·디자인 토큰·타일
매핑을 정리한다. 이 문서는 *생김새와 상호작용 모델* 을 다룬다. 연결/프로토콜/전송
코드는 자매 문서 `OLO-Explorer-Navigation-Handoff.md`를 본다.

기술 스택: Kotlin + Jetpack Compose + Material 3, 테마 이름 `OloTheme`(클레이 오렌지
브랜드). 아래 파일 경로는 모두 이 저장소(`kgcaudit/filezilla-client`)
`app/src/main/` 기준.

> OLO Player는 듀얼 페인·파일조작(복사/삭제 등)이 필요 없다. **그대로 베끼지
> 말고**, 아래의 *토큰·타일·행 표현·경로(빵부스러기) 방식* 을 가져와 플레이어에
> 맞는 단일 목록 화면으로 재구성하라. "무엇을 반드시 지키고(§7) 무엇은 버려도
> 되는지"를 끝에 명시했다.

---

## 1. 큰 그림 (레이아웃)

- OLO Explorer는 **듀얼 페인**(둘 다 로컬/서버 아무거나) 이다. 폰에서는
  `HorizontalPager`로 한 번에 한 페인, 위에 `PaneTabs`(TabRow)로 전환. 넓은 화면은
  `Row { weight(1f), weight(1f) }`로 나란히.
- **파일 화면에는 앱 상단바가 없다.** 가장자리까지(edge-to-edge) 쓰고, 상태바는
  투명·표면색 상속. 상단바(TopAppBar)는 보조 화면(사이트/큐/로그/휴지통 등)에만
  둔다. 상단바를 쓸 때 `containerColor = surface`(브랜드색 아님), 제목
  `onSurface`/SemiBold, 아이콘 `onSurfaceVariant`.
- 한 페인의 구성: `Column { PaneHeader ; (로딩 중 LinearProgressIndicator) ; body }`,
  body = 빈 상태 / 권한 게이트 / `BrowseScreen`(실제 목록).
- 선택·붙여넣기 바는 페인 하단(페이저 밖)에 둔다.

**OLO Player 권고**: 페인·페이저·탭·붙여넣기 바는 버린다. **단일 목록 화면 하나**
(상단 헤더 + 목록 + 필요 시 하단 미니 플레이어)로 재구성. 단, 아래 §2 헤더와 §3
행 표현·§4 토큰·§5 타일은 그대로 가져온다.

파일: `ui/FilePanes.kt`, `ui/MainActivity.kt`

---

## 2. 경로 표현 (폴더 트리가 아니라 "빵부스러기")

**핵심: 폴더 트리(tree)는 없다.** 위치는 페인 헤더 한 줄로 표현한다.
파일: `ui/PaneChrome.kt`(`PaneHeader`), `ui/Breadcrumbs.kt`

헤더 = `Surface(color = surface, tonalElevation = 2.dp)` 안 `Row` 하나, 좌→우:
1. **소스 버튼**(IconButton): 현재 소스를 두 톤 타일 글리프로 —
   `ic_tile_server`(서버) / `ic_tile_phone`(로컬) / `ic_tile_folder`(빈), 22.dp,
   `tint = Color.Unspecified`, 옆에 16.dp `ArrowDropDown` 셰브런. 탭 → 소스 시트.
   (햄버거 아님.)
2. **빵부스러기 경로 바**(`BreadcrumbBar`), `weight(1f)`. 경로가 비면 페인 제목만.
3. **필터/검색 토글**(Search 아이콘; 열리면 `primary` 틴트).
4. **오버플로 ⋮**.

**빵부스러기**(`Breadcrumbs.kt`):
- `data class Crumb(label, path)`. `breadcrumbs(path, rootPath, rootLabel)`가 볼륨/
  서버 루트("내부 저장소" 등)에서 시작해 세그먼트마다 한 조각씩.
- 가로 스크롤 `Row`, `spacedBy(2.dp)`, **`reverseScrolling = true`**(긴 경로는 끝—
  현재 폴더가 보이도록 정렬, 루트는 왼쪽으로 밀림).
- 구분자는 글자 "`/`"(labelLarge, `outlineVariant` 색 — 컨트롤 아님).
- 각 조각 `labelLarge`. **마지막 조각**은 `SemiBold` + `onSurface`, 클릭 불가.
  **이전 조각**들은 `Normal` + `primary` 색, `RoundedCornerShape(6.dp)` 클립,
  클릭 가능(그 폴더로 이동), 패딩 h6/v4.
- **"위로"** 전용 버튼은 없다. 상위 조각 탭 또는 시스템 뒤로가기로 올라간다.

**소스 시트**(`ui/StorageSheet.kt`) = `ModalBottomSheet`(가장자리 드로어 아님 —
페이저 스와이프와 충돌하므로). 라벨 붙은 섹션들의 스크롤 `Column`:
- **저장소**: 볼륨별 `PlaceRow`(내부→`ic_tile_phone`, SD→`ic_tile_sdcard`,
  바로가기→`ic_tile_folder`), 부제 = 여유/총량 + `CapacityBar`(4.dp 높이
  LinearProgressIndicator, `primary` 채움/`surfaceVariant` 트랙, 3.dp 코너).
- **서버**: 헤딩 끝에 "+"(사이트 추가). `PlaceRow` = `ic_tile_server`(secondary),
  제목 name/host, 부제 `user@host`.
- **즐겨찾기**: `ic_tile_bookmark`(tertiary), 끝에 Close로 제거.
- **도구**: 2×2 `ToolCell` 그리드 — 최근(`ic_tile_recents`)/전송(`ic_tile_transfers`)/
  로그(`ic_tile_document`)/휴지통(`ic_tile_trash`, `error`).
- `SectionLabel` = `labelLarge`/SemiBold/`primary`, 시작 패딩 20.dp.
- 타일 크기: `PlaceRow` 38.dp/코너 11.dp, `ToolCell` 34.dp/코너 10.dp.

**OLO Player 권고**: 빵부스러기 바(§2)와 소스 시트를 **그대로** 가져온다(서버/폴더
전환·경로 이동에 그대로 맞음). 도구 섹션은 플레이어에 맞게 (재생목록 등으로)
바꿔도 됨. 폴더 트리를 새로 만들지 말 것 — 빵부스러기가 이 앱의 확정된 방식이다.

---

## 3. 파일 목록 행 표현 (가장 중요)

파일: `ui/BrowseScreen.kt`. 네 가지 보기 모드: **LIST / GRID / GALLERY / COMPACT**.

### 리스트 행 (`EntryRow`) — 기본 표현
`Row(fillMaxWidth)`, 배경 = 선택 시 `primaryContainer` 아니면 `surface`, `clickable`,
패딩 **가로 12.dp · 세로 10.dp**(고밀도 5.dp), `verticalAlignment = CenterVertically`.
행 아래 `HorizontalDivider`(`outlineVariant`). 좌→우:

1. **체크박스** — 선택 모드에서만.
2. **아이콘/타일**(40.dp, 고밀도 30.dp): `kindOf(name, isDirectory)` → `FileKind`,
   채움색 `colourFor(kind)`(§5). **로컬** 썸네일 가능 종류(이미지/영상/음악)는
   `EntryThumb`(실제 디코드 썸네일, `Crop`, 코너 클립). 서버 행은 항상 종류 타일.
   타일 탭 = 선택 토글(다중선택 빠른 진입).
3. **이름 + 상세 Column**(`weight(1f)`, 시작 패딩 12.dp):
   - 이름: `bodyLarge`, **폴더는 Medium / 파일은 Normal** 굵기, 한 줄 말줄임.
     (폴더/파일 1차 구분 = 굵기 + 타일 색 + 폴더 글리프.)
   - 상세 줄: `bodySmall`/`onSurfaceVariant`, `[크기, 항목수, 수정시각]`을
     `"  ·  "`(공백2·점·공백2)로 이어 붙임. 크기는 파일만, 항목수는 **로컬** 폴더만
     (서버 폴더는 라운드트립 비용 때문에 표시 안 함), 시각 = 수정시각.
   - **COMPACT**는 상세 줄 생략(이름만).
4. **받기 버튼** — `!isLocal && !isDirectory`(=서버의 파일)일 때만 `Download`
   IconButton(`primary`). 로컬은 탭이 곧 열기라 버튼 없음.
5. **오버플로 ⋮**: 열기/폴더 받기/체크섬/속성/이름변경/권한변경/삭제 등 문맥별.

**행 상호작용**: 선택 모드면 토글, 폴더면 열기, 파일이면 `onOpenFile`(로컬은 열기,
서버는 캐시 받아 보기) — 받기 경로와 분리.

**링크/권한 배지**는 행에 안 그린다(속성 대화상자에만). 링크 폴더는 항목수 계산 제외.

### 그리드 (`GridTile`)
`LazyVerticalGrid(Adaptive(minSize = 108.dp))`, contentPadding 8.dp. 셀 = `Column`
(코너 12.dp): 48.dp 아이콘 블록(코너 14.dp; 선택 시 체크 칩, 아니면 썸네일 또는
타일+26.dp 글리프) + 이름(`bodySmall`, 최대 2줄) + 크기(`labelSmall`).

### 갤러리 (`GalleryCell`)
`LazyVerticalGrid(Fixed(3))`, contentPadding 2.dp. 셀 = `aspectRatio(1f)`,
`RoundedCornerShape(10.dp)`, 큰 썸네일(320px, `Crop`) 또는 중앙 글리프. 이름 없음.
`combinedClickable`(탭=열기/선택, 롱프레스=선택 시작). 선택 시 `primary` 35% 스크림
+ 흰 `CheckCircle`.

### 리스트 부가
- 행 수가 많으면 우측 `FastScroller` 레일, 이름 정렬 시 A–Z 인덱스.
- 푸터: 폴더 요약(폴더/파일 개수), `bodySmall`/`onSurfaceVariant`.
- `PullToRefreshBox` 당겨서 새로고침.

### 선택 UI
- 진입: 오버플로 "선택", 행 타일/체크박스 탭, 그리드/갤러리 롱프레스.
- **`SelectionBar`**(하단, `ui/FileActions.kt`): `Surface(secondaryContainer,
  tonalElevation 3.dp)` — Close → 개수(`labelLarge`) → 가로 스크롤 아이콘 액션들.
- **`PasteBar`**: `Surface(primaryContainer)` — 설명 텍스트 + 라벨 붙은 Button.

**OLO Player 권고**: **LIST 행(EntryRow)과 GALLERY**를 가져온다(미디어 브라우징에
갤러리가 특히 잘 맞음). GRID/COMPACT/선택바/붙여넣기바는 선택. 미디어 파일만
눈에 띄게(비미디어는 흐리게/숨김) 하고, 파일 탭 → 재생으로 잇는다(받기 버튼 대신
재생 흐름). 행의 **아이콘·이름 굵기·상세 줄·구분자 규칙**은 그대로 유지.

---

## 4. 디자인 토큰 (전부 한 파일)

파일: `ui/theme/Theme.kt`(Color/Type/Tiles 분리 없이 통합).

**브랜드 기본색**
- `Clay = 0xFFB95B3B`(클레이 오렌지; 흰 글자가 WCAG AA 통과하도록 충분히 진하게).
- `ClayLight = 0xFFE8A183`(다크 primary / 라이트 inversePrimary).
- `Stone = 0xFF6E5C50`, `StoneLight = 0xFFD6C3B4`(따뜻한 중립 secondary).

**라이트 스킴 주요 역할**
- primary `Clay` / onPrimary 흰색 / primaryContainer `0xFFF6E0D6` /
  onPrimaryContainer `0xFF4A1E0C`.
- secondary `Stone`, secondaryContainer `0xFFEFE6DE`.
- tertiary 틸 `0xFF3E7F80`, tertiaryContainer `0xFFCDE5E4`.
- **아이보리 중립**: background/surface `0xFFF7F4EF`, onSurface `0xFF1D1A16`,
  surfaceVariant `0xFFECE5DC`, onSurfaceVariant `0xFF574D45`, surfaceTint `Clay`.
- surface 사다리(보라 방지): surfaceBright `0xFFFBF9F5`, surfaceDim `0xFFDFD8CC`,
  containerLowest `0xFFFFFFFF`, containerLow `0xFFFCFAF6`, container `0xFFF3EFE8`,
  containerHigh `0xFFEDE8DF`, containerHighest `0xFFE7E1D6`.
- outline `0xFF8B7F74`, outlineVariant `0xFFD6CCC1`.
- **error = 크림슨** `0xFFA50E2E`(클레이와 안 헷갈리게 빨강 너머로), onError 흰색,
  errorContainer `0xFFF8DCDA`, onErrorContainer `0xFF3B100B`.

**다크 스킴**: primary `ClayLight`, background/surface `0xFF181613`, onSurface
`0xFFE8E3DA`, surfaceVariant `0xFF49423A`, error `0xFFFFB0BE`(+ 같은 사다리/컨테이너).

**셰이프**(`OloShapes`, Material보다 타이트): extraSmall 6 / small 10 / medium 14 /
large 18 / extraLarge 20 (dp). 대화상자도 28→10~12로 당김.

**타이포**(`OloTypography`): Material 기본에서 상단만 낮춤 — headlineLarge 28/34,
headlineMedium 24/30, headlineSmall 20/26, titleLarge 19/25. 본문은 기본 유지.
행 이름 `bodyLarge`, 상세 `bodySmall`, 빵부스러기/탭 `labelLarge`, 섹션 라벨
`labelLarge`/SemiBold.

**커스텀 Material 확장(둘 다 `MaterialTheme`의 확장 프로퍼티, 라이트/다크 자동):**

- **`MaterialTheme.status` → `StatusColors`**: `running/waiting/paused/done/failed/
  progressTrack`. 라이트: running `0xFF1273BE`(찬 파랑—따뜻한 브랜드 반대), waiting
  `0xFF7A6F65`, paused `0xFF9C7A0C`(앰버), done `0xFF1B7F4B`(초록), failed
  `0xFF9E0C2B`(크림슨), track `0xFFDCD3C6`. 다크는 밝은 변형. (전송 상태·체크섬
  일치/불일치에 사용 — 플레이어에선 다운로드/버퍼링 상태에 재사용 가능.)

- **`MaterialTheme.tiles` → `TileColors`**: 파일 종류별 타일 팔레트.
  `folder/archive/image/video/audio/document/code/app/other`.
  - 라이트: folder `Clay`, archive `0xFF8A6A3B`(오커), image `0xFF2E8B6B`(초록),
    video `0xFF6A5A9E`(보라), audio `0xFFB04A6A`(로즈), document `0xFF55606B`(슬레이트),
    code `0xFF3E7F80`(틸), app `0xFF4C7A3E`(초록), other `0xFF7A7168`(따뜻한 회색).
    **밝기가 아니라 색상(hue)** 으로 ~9개 타일을 구분.
  - 다크: 흰 글리프를 얹으므로 **일부러 더 밝은** 버전 — folder `0xFFD1734F`, archive
    `0xFFB08A54`, image `0xFF3FA383`, video `0xFF8A7AC0`, audio `0xFFC96B88`,
    document `0xFF6E7A86`, code `0xFF55A0A1`, app `0xFF69985A`, other `0xFF938A80`.

**테마 진입**: `OloTheme`가 스킴/셰이프/타이포 + 상태바 아이콘 극성 배선.
**Material You 동적색 안 씀**(의도적 거부).

---

## 5. 파일 종류 → 아이콘/타일 매핑

**종류 분류**(`ui/FileKind.kt`): `enum FileKind(@DrawableRes glyph)`.
FOLDER→`ic_tile_folder`, ARCHIVE→`ic_tile_archive`, COMIC→`ic_tile_comic`,
IMAGE→`ic_tile_image`, VIDEO→`ic_tile_video`, AUDIO→`ic_tile_audio`,
DOCUMENT→`ic_tile_document`, CODE→`ic_tile_code`, APP→`ic_tile_app`,
OTHER→`ic_tile_document`. `kindOf(name, isDirectory)`: 폴더면 FOLDER, 아니면 소문자
확장자를 `BY_EXTENSION`에서 조회, 없으면 OTHER.

`BY_EXTENSION`(발췌):
- ARCHIVE: `zip rar 7z tar gz bz2 xz tgz iso alz egg a00 a01`
- COMIC: `cbz cbr cb7 cbt`
- IMAGE: `jpg jpeg png gif webp bmp heic heif tiff tif svg`
- VIDEO: `mkv mp4 avi mov wmv flv webm m4v mpg mpeg ts m2ts`
- AUDIO: `mp3 flac wav aac ogg m4a wma opus`
- DOCUMENT: `pdf epub doc docx xls xlsx ppt pptx txt md rtf odt hwp srt smi ass vtt sub`
- CODE: `kt java py js ts json xml yml yaml html css sh c cpp h rs go rb php`
- APP: `apk aab apks xapk`
- 보조 판별: `looksMedia / looksPdf / looksEpub / looksVideo`.

**종류 → 색**(`ui/FlatIcon.kt`, `colourFor(kind)`): 각 `FileKind`를
`MaterialTheme.tiles` 필드로. COMIC은 ARCHIVE 색 재사용(펼친 책 글리프로 구분),
OTHER→`tiles.other`.

**타일 렌더 프리미티브**(`FlatIcon.kt`):
- `TileIcon(glyph, colour, size=40.dp, cornerRadius=12.dp, glyphTint=Unspecified)`:
  `Box` 크기 지정 → `clip(RoundedCornerShape)` → `background(colour)` → 중앙 `Icon`을
  `size*0.58f`, `tint = Unspecified`(두 톤 흰 아트워크 보존).
- `FileTile(kind, colour)` = `TileIcon(kind.glyph, ...)`.
- `EntryThumb`(`ui/Thumbnails.kt`): 로컬 미디어 실제 썸네일 디코드, 로딩/없음 시
  `FileTile` 폴백.

**드로어블**: `res/drawable/ic_tile_*.xml`(24dp `<vector>`). 전 세트: alert, app,
archive, audio, bookmark, code, comic, document, folder, image, locked, phone,
recents, sdcard, search, server, transfers, trash, video. **두 톤 흰색 아트워크**
(주 형태 `#FFFFFFFF`, 뒤 디테일은 반투명 흰색 예 `#8CFFFFFF`) — 그래서 색 타일 위에
`tint = Unspecified`로 그린다. `tools/icons/build_from_svg.py`로 생성("손대지 말 것").

---

## 6. 가져가는 절차 (권고)

1. **참조 붙이기**: OLO Player 세션에서 `add_repo kgcaudit/filezilla-client`(read) 후
   이 문서와 §끝 파일 목록을 정독. (통째 복사보다 원본 참조가 최신 유지에 유리.)
2. **토큰 이식(최우선)**: `theme/Theme.kt`의 색·셰이프·타이포·`tiles`·`status`
   확장을 플레이어 패키지로 옮긴다. 이게 "같은 앱처럼 보이게" 하는 뼈대다.
3. **타일 이식**: `FileKind.kt` + `FlatIcon.kt`(`TileIcon`/`FileTile`/`colourFor`) +
   `res/drawable/ic_tile_*.xml`. 미디어 위주라 image/video/audio/folder 타일이 핵심.
4. **경로 표현 이식**: `Breadcrumbs.kt` + `PaneHeader`의 소스버튼/빵부스러기/필터
   토글을 단일 화면 헤더로 재구성. `StorageSheet`(소스 시트)도 서버/폴더 선택에 그대로.
5. **행 표현 이식**: `EntryRow`(리스트) + `GalleryCell`(갤러리). 미디어면 재생,
   비미디어면 흐리게/숨김. 서버 파일은 받기 버튼 대신 재생 흐름으로.

---

## 7. 반드시 지킬 것 / 버려도 되는 것

**지킨다(정체성):**
- 클레이 오렌지 `#B95B3B` primary + 아이보리 중립 `#F7F4EF`. **Material You 동적색
  금지.**
- 타이트한 코너(6/10/14/18/20). 대화상자도 28 아니라 10~12.
- **색상(hue)으로 구분되는 종류별 타일**(9색) + 두 톤 흰 글리프 + `tint =
  Unspecified`. 타일을 단색 회색 아이콘으로 바꾸지 말 것.
- 행: 타일 + `bodyLarge` 이름(폴더 Medium/파일 Normal) + `bodySmall` 상세 줄(`  ·  `
  구분) + 하단 `HorizontalDivider`.
- 위치는 **빵부스러기**(트리 아님), `reverseScrolling`으로 현재 폴더가 보이게.
- 로컬 미디어는 **실제 썸네일**, 서버는 종류 타일.
- 문구는 우리말 기본(휴지통 쪽 금지어 규칙은 플레이어엔 해당 없음이나 톤은 유지).

**버려도 된다(탐색기 전용):**
- 듀얼 페인·페이저·페인 탭, 붙여넣기 바, 파일조작(복사/이동/이름변경/압축/체크섬 등)
  선택 액션.
- 도구 섹션의 전송/로그/휴지통(플레이어는 재생목록 등으로 대체).
- GRID/COMPACT는 선택(미디어엔 LIST + GALLERY면 충분).

---

## 파일 색인 (전부 `app/src/main/` 아래)
- `ui/FilePanes.kt` — 듀얼 페인/페이저/탭/바 호스팅.
- `ui/PaneChrome.kt` — `PaneHeader`, 소스 버튼, `BreadcrumbBar`, `CapacityBar`.
- `ui/Breadcrumbs.kt` — `Crumb` + 트레일 빌더.
- `ui/BrowseScreen.kt` — LIST/GRID/GALLERY/COMPACT, `EntryRow`/`GridTile`/`GalleryCell`.
- `ui/BrowseOptions.kt` — `SortKey`/`ViewMode`/`BrowseOptions`/`BrowseListing.arrange`.
- `ui/BrowseMenus.kt` — `BrowseOverflow`(⋮), `ViewOptionsDialog`, `PropertiesDialog`.
- `ui/StorageSheet.kt` — 소스/저장소/서버/즐겨찾기/도구 시트; `PlaceRow`/`ToolCell`.
- `ui/FileActions.kt` — `NewThingFab`/`SelectionBar`/`PasteBar`.
- `ui/FileKind.kt` — 확장자 → `FileKind` 매핑.
- `ui/FlatIcon.kt` — `TileIcon`/`FileTile`/`colourFor`.
- `ui/Thumbnails.kt` — `EntryThumb` 실제 썸네일.
- `ui/OloTextField.kt` — 48.dp 컴팩트 텍스트필드.
- `ui/MainActivity.kt` — Scaffold, 보조화면 TopAppBar, edge-to-edge 파일 화면.
- `ui/theme/Theme.kt` — 모든 토큰(색·`tiles`·`status`·셰이프·타이포·`OloTheme`).
- `res/drawable/ic_tile_*.xml` — 두 톤 흰 타일 글리프.
