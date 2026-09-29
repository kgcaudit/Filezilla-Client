# OLO Explorer — 탐색(폴더·파일·서버) 인수인계 (OLO Player용)

목표: **OLO Player가 로컬·FTP·FTPS·SFTP를 탐색해, 서버/폴더에서 동영상·음악을
직접 열어 재생**하게 한다. 파일 관리(복사·삭제·이름변경 등)는 범위 밖 — 탐색과
"열기"까지만. 이 문서는 OLO Explorer(이 저장소 `kgcaudit/filezilla-client`)의
탐색 스택을 어떻게 떼어 OLO Player로 가져오는지 정리한다.

## 큰 그림: 연결 → 목록 → 미디어 열기

```
  SiteEntity(서버 설정, Room)        StorageRoot(로컬 볼륨)
        │                                  │
        ▼                                  ▼
  TransferManager.browse(site){ session -> ... }   LocalFileSource().list(path)
        │  session: RemoteSession (FtpSession / SftpSession)
        │    connect / changeDirectory / list -> List<DirectoryEntry>
        ▼
  DirectoryEntry(name,isDirectory,size,time,isLink,permissions)  ← 목록 모델
        │  (미디어 파일 탭)
        ▼
  로컬: 그 File을 바로 재생 (미디어 인수인계의 MediaViewer/PlaybackService)
  서버: TransferManager.fetchForViewing(site, remotePath, cacheFile, ...) 로
        캐시에 받은 뒤 그 File을 재생 (OLO Explorer의 viewOnServer→openCachedMedia 흐름)
```

핵심: **재생 엔진은 이미 OLO Player에 있다**(미디어 인수인계로 이식됨). 여기서
가져오는 건 "그 File을 어디서 얻느냐" — 로컬 목록과 서버 연결/목록/받기다.

## 그대로 가져갈 것

### 1) `:core-ftp` 모듈 통째로 (권장)
`core-ftp/src/main/kotlin/org/filezilla/ftp/**` — 순수 JVM, 안드로이드 의존 없음.
- `protocol/` FtpControlConnection, FtpSettings, FtpSecurity(PLAIN/EXPLICIT_TLS/IMPLICIT_TLS)
- `transfer/` ResilientTransfer(download/upload, 재개), ControlConnections
- `listing/` DirectoryEntry 및 목록 파서
- `sftp/` SftpEngine, SftpSettings, SshHostKey, SftpResilientTransfer (jsch mwiede 포크)
- `io/` asTransferReader/Writer
모듈째 복사하고 `settings.gradle.kts`에 include, 의존성(`gradle/libs.versions.toml`의
jsch)만 맞추면 된다. 프로토콜·전송 로직을 다시 짜지 말 것.

### 2) 앱 전송/세션 래퍼 (app/.../transfer)
- `TransferManager.kt` — 이 목표에 쓰는 것만: `browse(site){session->}`(목록),
  `fetchForViewing(site, remotePath, into, abort, progress)`(미디어를 캐시에 받기),
  그리고 호스트키/인증서 신뢰 흐름. 큐(enqueueUpload/Download)는 재생 목적엔
  불필요하니 빼도 됨.
- `RemoteSession.kt`(공통 계약) + `FtpSession.kt` / `SftpSession.kt`,
  `WorkerConnection.kt`, `BrowseConnections.kt` — 연결 풀과 세션.
- 신뢰: `HostKeyUi.kt`/`CertificateUi.kt`(SSH 호스트키·TLS 인증서 확인 대화상자),
  핀은 SiteEntity에 저장.

### 3) 데이터 (app/.../data)
- `SiteEntity.kt`(host/port/user/security/protocol/pinnedCertificate/pinnedHostKey/
  initialPath 등) + Room `AppDatabase.kt` + `PasswordCipher.kt`(자격증명 암호화 저장).
  플레이어는 "서버 몇 개 저장 + 접속"만 필요하니 사이트 편집 UI는 최소화 가능.

### 4) 로컬·경로 유틸 (app/.../files)
- `FilePath.kt`(경로 조작), `LocalFileSource`/`LocalWalk`(로컬 목록·재귀),
  `StorageRoot`/볼륨 열거, minSdk 26 저장소 접근(MANAGE_EXTERNAL_STORAGE).
- 재귀 열거가 필요하면 `ui/DeepSearch.kt`의 `RemoteLister` 패턴(로컬·서버 공통).

### 5) 참고할 흐름 (app/.../ui/MainViewModel.kt)
- 창/소스 모델: `PaneSource`(Local/Remote/Empty), `open/openPath/loadRemote/
  showSite/showLocalAt` — 목록 네비게이션.
- 서버 미디어 열기: `viewOnServer(id, entry)` → `offerToOpen` → `readyToOpen`,
  그리고 `openCachedMedia(file)`/`openMediaFolder(file)` — **이 경로가 정확히
  "서버 파일 탭 → 캐시로 받기 → 재생"**이다. 플레이어에선 이걸 그대로 본떠서
  목록 탭 시 미디어면 fetchForViewing→재생으로 잇는다.
- 목록 UI는 `BrowseScreen.kt`/`FilePanes.kt` 참고하되, 플레이어 디자인에 맞춰
  가볍게 새로 그리는 편이 낫다(듀얼 페인·파일조작 UI는 불필요).

## 이식 절차 (권장)
1. **참조 저장소 붙이기**: OLO Player 세션에서 `add_repo kgcaudit/filezilla-client`(read)
   후 이 문서와 위 파일들을 읽는다. (통째 복사보다 원본 참조가 최신 유지에 유리)
2. `:core-ftp` 모듈을 OLO Player 프로젝트로 복사·include, jsch 의존성 추가.
3. `transfer/`의 RemoteSession+Ftp/SftpSession+WorkerConnection+BrowseConnections와
   TransferManager의 browse/fetchForViewing만 이식(패키지명 `org.olo.player.*`로).
   신뢰 대화상자(HostKeyUi/CertificateUi)도 함께.
4. `data/`의 SiteEntity+AppDatabase+PasswordCipher 이식(서버 저장/접속용).
5. 로컬은 `LocalFileSource`로, 서버는 browse로 목록을 만들고, **미디어 파일 탭 시**
   로컬은 바로 재생, 서버는 fetchForViewing→캐시 File 재생으로 잇는다(비미디어는 무시).
6. 최소 UI: 서버 목록/추가, 폴더 목록 화면, 미디어만 눈에 띄게. 재생은 기존
   MediaViewer/PlaybackService로.

## 지켜야 할 것(교훈)
- **보안**: 호스트키(SFTP)·인증서(FTPS)는 반드시 검증 — 모르는/바뀐 키는 사용자
  확인 전 접속 금지(OLO Explorer의 StrictHostKeyChecking + VerifyingHostKeyRepository,
  인증서 핀닝 그대로). 자격증명은 PasswordCipher로 저장, 로그 금지.
- **서버 파일은 스트림이 아니라 캐시로 받아 재생**(1차). 스트리밍(media3 DataSource로
  직접 재생)은 이후 고도화 항목.
- core-ftp는 순수 JVM이라 인메모리 FTP/SSHD로 통합 테스트 가능 — 회귀 방지에 활용.
- `allWarningsAsErrors` 관례(미사용 import 금지), 문구는 우리말 기본.

## 넘기는 구체적 방법 (요약)
1. 이 문서가 이 저장소에 커밋됨(dev 브랜치). 코어 코드도 여기 있음.
2. OLO Player 세션에 "`kgcaudit/filezilla-client`를 read로 add_repo하고 이 문서를
   정독한 뒤, 위 절차대로 탐색+서버 미디어 열기를 이식하라"고 지시하면 된다.
   (부모 세션이 SendMessage로 그 지시를 보낼 수 있다.)
