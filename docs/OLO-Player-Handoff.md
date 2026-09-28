# OLO Player — 인수인계 문서 (미디어 재생 지식 이전용)

이 문서는 **OLO Explorer**(이 저장소)의 동영상·음악 재생 기능을 별도 앱
**OLO Player**로 떼어내 고도화하려는 다른 세션이 그대로 이어받을 수 있도록,
재사용 가능한 코드·구조·의존성·그리고 만들면서 얻은 교훈을 한곳에 정리한 것이다.

- 대상 저장소: `kgcaudit/filezilla-client`
- 기준 브랜치: `claude/android-ftp-resume-ns458o`
- 미디어 엔진: **AndroidX media3 (ExoPlayer) 1.5.1**
- 언어/UI: Kotlin + Jetpack Compose (Material3), minSdk 26 / targetSdk 35

---

## 1. 한눈에 보는 구조

재생은 **화면**과 **엔진**을 분리한 "서비스 + 컨트롤러" 모델이다.

```
  Compose 화면 (MediaViewerScreen.kt)
        │  MediaController (media3-session)
        ▼
  PlaybackService : MediaSessionService   ← 백그라운드에서 ExoPlayer 소유
        │
        ├─ 재생 알림 + 잠금화면 컨트롤 (media3가 자동 생성, 버튼만 필터)
        ├─ 오디오 포커스 / 헤드폰 뽑힘 시 일시정지
        ├─ 슬립 타이머 (커스텀 SessionCommand)
        └─ 곡/영상에 따라 컨트롤 세트 교체 (건너뛰기 유무)
```

핵심 원칙: **플레이어(ExoPlayer)는 화면이 아니라 서비스가 소유한다.**
예전엔 뷰어 안에서 만들고 뷰어를 닫으면 해제해서, 앱이 백그라운드로 가는 순간
소리가 끊기고 알림도 없었다. 서비스로 옮기니 화면을 꺼도 소리가 이어지고
media3가 알림·잠금화면 컨트롤을 공짜로 얹어 준다.

---

## 2. 그대로 가져갈 파일 (재사용 코어)

아래 네 파일이 재생 기능의 몸통이다. 앱 나머지(FTP·탐색기)와의 결합이 거의 없어
거의 그대로 새 앱에 복사할 수 있다.

| 파일 | 역할 | 외부 결합 |
|---|---|---|
| `app/src/main/kotlin/org/filezilla/android/playback/PlaybackService.kt` | 재생 엔진(서비스). ExoPlayer 소유, 알림/포커스/노이즈/슬립타이머/컨트롤세트 | media3, guava만 |
| `app/src/main/kotlin/org/filezilla/android/playback/SubtitleBundle.kt` | 자막 트랙을 MediaController→세션으로 넘길 때 metadata extras에 담고 푸는 코덱 | media3만 |
| `app/src/main/kotlin/org/filezilla/android/ui/SamiSubtitles.kt` | SAMI(.smi) → WebVTT(.vtt) 변환, MS949/UTF-8 자동판별 | 없음(순수 Kotlin) |
| `app/src/main/kotlin/org/filezilla/android/ui/MediaViewerScreen.kt` (2,700줄) | 화면 전체: 음악 플레이어·가사·영상 플레이어·설정 시트·제스처·슬립타이머 UI | R, AppPreferences, PlaybackService, SubtitleBundle, TextFiles, `looksMedia/looksVideo` |

### 부분만 떼어올 것 (헬퍼/상태/설정)

새 앱에 맞게 다시 심거나 복사할 작은 조각들:

- **파일 종류 판별** — `ui/FileKind.kt`의 `looksMedia()`, `looksVideo()`, 오디오
  확장자 집합. PlaybackService의 `AUDIO_EXTENSIONS`
  (`mp3, flac, wav, aac, ogg, m4a, wma, opus`)와 반드시 일치시킬 것.
- **재생 상태(뷰모델)** — `MainViewModel`의 `MediaViewer(items: List<File>, index: Int)`
  와 열기 함수(`openLocalMedia`/`openMediaFolder`/`openCachedMedia`): "한 폴더의
  같은 종류 미디어를 재생목록으로 묶어 연다"는 규칙. 새 앱에선 파일 하나 → 폴더의
  형제 파일로 재생목록을 구성.
- **기억(SharedPreferences)** — `AppPreferences`에서:
  - `mediaPosition(key)/setMediaPosition` — 파일별 마지막 위치(ms), 최근
    `MAX_REMEMBERED_MEDIA = 300`개만 LRU로 보관(`KEY_MEDIA_POSITION`, `KEY_MEDIA_KEYS`).
  - `subtitleChoice/setSubtitleChoice` — 파일별 마지막 자막 선택(`KEY_MEDIA_SUBTITLE`).
  - `subtitleScale/subtitleColor/setSubtitleStyle` — 자막 크기(화면 대비 비율)와 색,
    모든 영상에 공통 적용(`KEY_SUBTITLE_SCALE`, `KEY_SUBTITLE_COLOR`).
- **텍스트 디코딩** — `viewer/TextFiles.decode(bytes)`는 LRC 가사를 읽을 때 문자셋
  자동판별용으로만 쓰인다. 새 앱에선 간단히 UTF-8/CP949 판별 함수로 대체 가능.

---

## 3. 의존성 (build.gradle)

`gradle/libs.versions.toml`:
```toml
media3 = "1.5.1"
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-ui        = { module = "androidx.media3:media3-ui",        version.ref = "media3" }
media3-session   = { module = "androidx.media3:media3-session",   version.ref = "media3" }
```
`app/build.gradle.kts`:
```kotlin
implementation(libs.media3.exoplayer)
implementation(libs.media3.ui)      // PlayerView(자막 렌더)용
implementation(libs.media3.session) // MediaSession/MediaController/알림
// guava(ListenableFuture, ImmutableList)는 media3-session이 끌어온다.
```
그 외 Compose(BOM), Material3, Material Icons Extended가 화면에 필요하다.

---

## 4. AndroidManifest 필수 선언

권한:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
서비스(액션으로 media3가 찾으므로 exported + intent-filter 필수):
```xml
<service
    android:name=".playback.PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

---

## 5. 지금 이미 되는 기능 목록

새 앱이 "무엇을 물려받는가"의 체크리스트.

**공통/엔진**
- 백그라운드 재생 + 재생 알림 + 잠금화면 컨트롤
- 오디오 포커스 처리, 헤드폰 뽑히면 자동 일시정지(`setHandleAudioBecomingNoisy`)
- 이어보기/이어듣기(파일별 위치 저장·복원)
- 슬립 타이머(분 단위 설정/취소/남은시간 조회, 서비스가 카운트 → 화면 꺼도 동작)
- 곡↔영상 자동 구분으로 컨트롤 세트 교체(곡: 이전/다음 곡, 영상: 10초 되감기/빨리감기)

**영상 플레이어**
- 트랙 선택 시트: 자막(내장+외부)·오디오 트랙·재생 속도·반복·(재생목록)셔플
- 사이드카 자막 자동 탐색: 영상 옆 같은 이름의 `.srt/.smi/.vtt/.ass` 등을 찾아 붙임
- SAMI(.smi) 지원: media3가 못 읽는 SAMI를 .vtt로 변환(캐시), MS949/UTF-8 자동판별
- 자막 크기·색 설정(모든 영상 공통)
- 화면 제스처: 좌/우 세로 스와이프 = 밝기/볼륨, 가로 스와이프 = 탐색, 핀치 = 화면 채움/맞춤(종횡비)
- 화면 방향 잠금(현재 방향 고정)

**음악 플레이어**
- 앨범 커버·태그(제목/아티스트) 읽기, 흐린 커버 배경
- 재생목록 시트, LRC 가사(싱크) 표시
- 이전/다음 곡, 반복/한곡반복, 셔플

---

## 6. 만들며 얻은 교훈 (그대로 지킬 것)

새 세션이 다시 밟지 말아야 할 지뢰들. 대부분 해당 파일 주석에 근거가 남아 있다.

1. **플레이어는 서비스가 소유.** 화면에서 만들면 백그라운드에서 죽고 알림이 없다.
2. **MediaController는 MediaItem의 뼈대만 넘긴다.** uri와 자막 설정이 전송 중
   사라진다 → uri는 requestMetadata에서, 자막은 metadata extras(`SubtitleBundle`)에
   담아 서비스의 `onAddMediaItems`에서 복원. 이미 온전한 아이템은 건드리지 말 것
   (안 그러면 있던 자막을 지운다).
3. **자막 파싱은 기본값(추출 중 파싱)을 끄지 말 것.** 끄면 깨진 자막 하나가 영상
   전체를 죽인다. 기본값이면 자막 로드 실패가 비치명적(영상은 그대로 재생).
4. **SAMI + MS949.** 한국 영화 자막은 SAMI가 흔하고 media3는 못 읽는다 → .vtt로
   변환. 인코딩은 UTF-8을 엄격 디코딩으로 먼저 시도하고 실패하면 MS949로.
5. **곡과 영상은 컨트롤이 다르다.** 영상은 파일 간 건너뛰기를 빼고(10초 이동만),
   곡은 이전/다음 곡을 유지. `applyCommandsFor`가 재생 항목이 바뀔 때마다 세션의
   available commands를 교체 → 알림/잠금화면 버튼이 종류에 맞게 그려진다.
6. **알림 버튼 필터.** `DefaultMediaNotificationProvider`를 상속해 재생/일시정지와
   (곡일 때만) 이전/다음만 남긴다. 영상은 command 자체가 없어 필터가 지울 것도 없다.
7. **슬립 타이머는 서비스에서 elapsedRealtime로.** 벽시계가 바뀌어도 안 흔들리고,
   앱이 백그라운드·화면 꺼짐이어도 소리를 멈춘다. 커스텀 SessionCommand로 화면에서
   설정/취소/조회.
8. **onTaskRemoved:** 재생 중이 아니면(또는 일시정지·빈 큐) `stopSelf()`로 유령
   서비스/알림을 남기지 않는다. 재생 중이면 스와이프로 앱을 닫아도 소리는 살린다.
9. **위치 저장은 LRU 상한(300개).** 무한히 쌓지 않는다.

---

## 7. 새 앱으로 이식하는 절차 (권장)

새 세션이 빈 안드로이드 프로젝트에서 시작한다고 가정한다.

1. **참조 저장소 클론**(읽기용):
   `git clone <이 저장소> ref-explorer` 후 브랜치 체크아웃.
2. **코어 4파일 복사**: `playback/PlaybackService.kt`, `playback/SubtitleBundle.kt`,
   `ui/SamiSubtitles.kt`, `ui/MediaViewerScreen.kt`.
   ```bash
   FILES="app/src/main/kotlin/org/filezilla/android/playback/PlaybackService.kt \
          app/src/main/kotlin/org/filezilla/android/playback/SubtitleBundle.kt \
          app/src/main/kotlin/org/filezilla/android/ui/SamiSubtitles.kt \
          app/src/main/kotlin/org/filezilla/android/ui/MediaViewerScreen.kt"
   ```
3. **패키지명 치환**: `org.filezilla.android.*` → 새 앱 패키지. 결합 지점만 바꾸면 됨
   (`R`, `AppPreferences`, `TextFiles`, `looksMedia/looksVideo`).
4. **작은 조각 이식**: 5·2절의 `MediaViewer` 상태, 미디어 prefs, 파일종류 판별,
   LRC용 텍스트 디코딩을 새 앱에 심는다(뷰모델/설정 클래스는 새로 얇게 작성 권장).
5. **의존성·Manifest 반영**(3·4절 그대로).
6. **엔트리 배선**: 파일 하나를 탭 → 폴더의 형제로 재생목록 구성 → `MediaViewerScreen`
   호출. 서버 스트리밍이 필요 없으면 로컬 `File`만으로 충분.
7. **빌드·기동 확인**: 재생·알림·백그라운드·자막·슬립타이머 순으로 스모크 테스트.

> 결합이 적어 1~2일 내 "재생되는 골격"이 나오고, 이후 8절 로드맵으로 고도화.

---

## 8. 타 세션에 넘기는 구체적 방법

지식 이전 방법을 상황별로. **B안(참조 저장소 + create_session)** 을 권장.

### A. 새 저장소를 씨앗으로 (완전 독립 시작)
1. GitHub에 새 저장소 `OLO-Player` 생성.
2. 이 문서와 코어 4파일을 새 저장소에 커밋(패키지명은 새 앱 기준으로).
3. 새 세션을 **그 저장소를 소스로** 열고, 첫 지시에 "이 저장소의
   `docs/OLO-Player-Handoff.md`를 읽고 OLO Player 앱을 시작하라"고 준다.
- 장점: 처음부터 새 앱의 git 이력이 깨끗함. 단점: 씨앗 커밋을 먼저 만들어야 함.

### B. 이 저장소를 참조로 (권장, 지금 바로 가능)
- 이 문서가 이미 이 저장소 `docs/OLO-Player-Handoff.md`에 커밋됨. 코어 파일도 여기 있음.
- **Claude Code Remote `create_session`** 도구로 형제 세션을 만들 때
  `source_url = 이 저장소`, `source_revision = claude/android-ftp-resume-ns458o`,
  `prompt = "docs/OLO-Player-Handoff.md를 먼저 정독하고, 2·7절대로 미디어 코어를
  새 앱 골격으로 추출해 OLO Player를 시작하라. 우리말로 보고."` 로 지시.
- 새 세션은 클론 즉시 이 문서와 실제 코드를 함께 보게 되어 맥락 손실이 가장 적다.

### C. 문서 텍스트 붙여넣기 (가장 단순)
- 이 파일 전체를 복사해 새 대화 첫 메시지에 붙여넣고 "이 인수인계대로 OLO Player를
  시작하라"고 지시. 코어 파일이 필요하면 B처럼 저장소를 붙이거나 파일을 첨부.
- 장점: 도구 불필요. 단점: 실제 코드가 같이 안 가면 새 세션이 재작성해야 함.

### D. 지금 이 세션이 형제 세션을 스폰 (원하면 대신 해줌)
- 요청하면 `create_session`으로 위 B안 세션을 바로 띄우고 첫 지시까지 넣어 준다.
  (컨테이너/세션이 실제로 생성되는 작업이라 명시적으로 요청할 때만 실행)

---

## 9. OLO Player 고도화 로드맵 제안 (참고)

물려받은 토대 위에 붙일 만한 것들, 대략 난이도 순:

- **네트워크 스트리밍**: FTP/HTTP URL을 캐시 없이 바로 재생(media3 DataSource).
  지금은 "받아서 로컬 재생"까지만 되어 있음.
- **재생목록 관리**: 사용자 정의 큐, 순서 편집, 대기열 추가/저장.
- **PIP(Picture-in-Picture)** 영상, 배경 미니플레이어.
- **제스처·단축 커스터마이즈**, 좌우 손잡이 전환, 더블탭 스킵 초.
- **이퀄라이저/오디오 효과**(AudioEffect), 재생 속도 프리셋.
- **Chromecast / 외부 출력**, 다중 오디오/자막 트랙 다운로드.
- **자막 고도화**: 지연(offset) 조정, ASS 스타일, 인코딩 수동 지정.
- **폴더/라이브러리 스캔**, 최근 재생, 즐겨찾기.

---

## 부록: 코어 파일 원문 위치

- `app/src/main/kotlin/org/filezilla/android/playback/PlaybackService.kt`
- `app/src/main/kotlin/org/filezilla/android/playback/SubtitleBundle.kt`
- `app/src/main/kotlin/org/filezilla/android/ui/SamiSubtitles.kt`
- `app/src/main/kotlin/org/filezilla/android/ui/MediaViewerScreen.kt`
- 상태/설정: `ui/MainViewModel.kt`(MediaViewer 및 open* 함수),
  `data/AppPreferences.kt`(media/subtitle prefs), `ui/FileKind.kt`(looks* 판별)

각 파일 상단·함수 주석에 "왜 이렇게 했는지"가 상세히 남아 있으니, 이식 전 반드시
해당 주석을 함께 읽을 것.
