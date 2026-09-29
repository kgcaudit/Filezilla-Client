# 폴더 동기화(미러링) — 설계 브리프 (전용 세션용)

OLO Explorer에 **단방향 폴더 미러링**을 추가한다. 두 창(왼쪽/오른쪽) 중 한쪽을
원본(source), 다른 쪽을 대상(target)으로 삼아, **바뀐 것만** 옮겨 대상을 원본과
같게 만든다. 파일을 지우는 파괴적 동작이 포함되므로 **안전이 최우선**: 반드시
"스캔 → 미리보기 → 사용자 확인 → 실행" 순서, 삭제는 기본 꺼짐.

- 저장소/브랜치: `kgcaudit/filezilla-client`, 작업 브랜치 **`claude/folder-sync`**
  (베이스: 현재 `claude/android-ftp-resume-ns458o` 최신 — FTP/FTPS/SFTP 모두 포함)
- 기존 앱(FTP/FTPS/SFTP·전송 큐·로컬 조작)을 **절대 회귀시키지 말 것**.

## 방향·범위 (v1)
- **창→창** 미러. 사용자가 "이 창(원본) → 반대 창(대상)"을 명시적으로 고른다.
- 재귀. 조합: 로컬↔로컬, 로컬↔서버, 서버↔로컬, 서버↔서버(가능하면; 어려우면 v1은
  로컬↔서버/서버↔로컬/로컬↔로컬만, 서버↔서버는 명확히 "미지원" 안내).
- 비교 규칙: **상대경로 기준**. 대상에 없으면 복사, 있으면 (크기가 다르거나 양쪽
  mtime을 알 때 원본이 더 최신이면) 복사, 같으면 건너뜀. mtime을 한쪽만 알면
  크기만으로 비교.
- **삭제(대상에만 있는 항목 제거)는 옵션이며 기본 OFF.** 켜면 미리보기에 빨간색으로
  명확히 표시.

## 재사용 지점 (반드시 먼저 읽기)
- `ui/DeepSearch.kt` — 양쪽 공통 재귀 walk 패턴(BFS, MAX_DEPTH/MAX_FOLDERS 캡, 링크
  건너뜀, `RemoteLister` 추상화, 취소). **스캔(전체 열거)** 은 이걸 본떠서 만들되,
  needle 매칭이 아니라 **모든 파일/폴더를 상대경로+크기+mtime+isDir로 수집**하도록.
- `ui/FolderDownload.kt` — `RemoteLister` 정의처. 로컬은 `LocalFileSource().list`,
  서버는 `session.changeDirectory/list`로 같은 인터페이스를 만든다(MainViewModel의
  `walkFor` 참고).
- 전송 실행: `TransferManager.enqueueDownload(...)` / `enqueueUpload(...)`(재개·덮어쓰기
  인자 있음), 로컬은 `LocalOperations.copy/createDirectory/delete`, 서버 삭제·mkdir는
  브라우즈 세션(`FtpSession`/`RemoteSession`의 `createDirectory/deleteFile/
  removeDirectory`). `MainViewModel`의 `queueUploads`/`downloadHeld`/`planDownload`가
  폴더 전송을 어떻게 큐에 넣는지 참고.
- 진행/취소 UI: 압축·합치기가 쓰는 `ArchiveBusy`/`archiveOutcome` 패턴 또는 전송 큐.

## 권장 설계
1. **순수 엔진(테스트 가능)** `files/SyncDiff.kt`
   - 입력: 원본/대상 각각 `Map<상대경로, Meta(isDir,size,mtimeMillis?)>` + 옵션
     (deleteExtras).
   - 출력: 정렬된 `List<SyncAction>` — `MakeDir(rel)`(얕→깊), `Copy(rel)`,
     `Delete(rel,isDir)`(깊→얕, deleteExtras일 때만). 동일 항목은 Skip.
   - "다름" 규칙 위와 같음. **철저히 단위 테스트**(추가/변경/삭제/동일/중첩/충돌).
2. **스캔** `SyncScan` — `RemoteLister`로 한쪽 트리를 위 Map으로 열거(로컬·서버 공통,
   캡·링크·숨김 규칙은 DeepSearch와 동일). 테스트는 인메모리 lister로.
3. **MainViewModel**: `prepareSync(sourcePane, targetPane, deleteExtras)` → 양쪽 스캔·
   diff → `SyncState`(원본/대상 라벨, 액션 목록, 복사/삭제/건너뜀 개수) 노출.
   `runSync(state)` → mkdir(대상) → 파일별 전송(조합에 맞는 enqueue/copy) → (옵션)삭제.
   전송은 큐 재사용, mkdir/삭제는 순서 보장(디렉터리 먼저, 삭제는 나중·깊은 것부터).
4. **UI**: 진입점(예: 창 헤더 오버플로 또는 저장소 시트에 "동기화"), **미리보기
   대화상자**(원본→대상, 개수 요약, 스크롤 목록에 복사/삭제 표시, "대상에만 있는 항목
   삭제" 체크박스 기본 해제, 실행 버튼). 실행 중 진행 표시·취소.

## 안전 규칙 (필수)
- 삭제 기본 OFF, 켜면 미리보기에서 삭제 대상이 명확·강조. **원본↔대상 폴더가 겹치거나
  한쪽이 다른 쪽의 하위이면 거부**(자기 자신을 지우거나 무한 확장 방지).
- 전송은 덮어쓰기 규칙을 명시(원본이 정답이므로 대상 덮어쓰기). 실패 항목은 건너뛰고
  요약에 보고, 도중 취소 가능. 삭제는 전송이 끝난 뒤에만.
- 링크는 따라가지 않음(DeepSearch와 동일). 깊이/폴더수 캡 준수.

## 지켜야 할 가드레일
- 각 단계 `:core-ftp:test`·`:app:testReleaseUnitTest` 통과. FTP/FTPS/SFTP 회귀 없음.
- WordingTest(문구 중복은 allowed 등재, 금지어 지우기/디렉터리/내려받기, %1$s 뒤 바뀌는
  조사 금지), IconMeaningTest, DialogShellTest(OloDialog/OloTextField 셸), StringResourceTest
  (values·values-ko 동시+포맷 인자 일치), DownloadEntryPointTest(move/copy 위치).
- 새 UI 문구는 values·values-ko 동시, 우리말 기본. `allWarningsAsErrors`(미사용
  import 금지), minSdk 26.
- `claude/folder-sync`에만 커밋·푸시. PR은 사용자가 요청할 때만. 릴리스 APK는 기존
  키스토어로 서명. 진행 보고는 우리말. 큰 방향 전환은 사용자에게 먼저 확인.

## 완료 기준
두 창(로컬↔서버 포함)에서 원본→대상 미러가 미리보기대로 정확히 동작(복사/건너뜀,
옵션 삭제), 파괴적 실수 방지장치가 동작하고, 기존 기능·프로토콜은 회귀 없음.
