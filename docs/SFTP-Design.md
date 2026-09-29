# SFTP 지원 — 설계 브리프 (전용 세션용)

OLO Explorer에 **SFTP(SSH File Transfer)** 를 FTP/FTPS와 나란히 추가한다. 지금
스택은 전부 FTP 전용이므로, SFTP는 "FTP의 변형"이 아니라 **같은 앱 표면 뒤에
놓이는 두 번째 프로토콜**로 구현한다. 기존 FTP 앱을 절대 깨뜨리지 않는 것이 최우선.

- 대상 저장소/브랜치: `kgcaudit/filezilla-client`, 작업 브랜치 **`claude/sftp`**
  (베이스: `claude/android-ftp-resume-ns458o` — 최신 기능이 모두 있음)
- SSH 라이브러리: **`com.github.mwiede:jsch`** (유지보수되는 JSch 포크, 순수 Java,
  최신 알고리즘 지원, Android/JVM 모두 동작). core-ftp가 순수 JVM 모듈이라 SFTP
  엔진을 거기 두면 통합 테스트가 쉽다.

## 지금의 seam (반드시 먼저 읽을 것)

- `app/.../transfer/FtpSession.kt` — 브라우즈 표면: `connect / currentDirectory /
  changeDirectory / changeToParent / list / createDirectory / removeDirectory /
  deleteFile / rename / changeMode / fingerprint / close`. **이 표면이 SFTP가
  구현해야 할 계약이다.**
- `app/.../transfer/WorkerConnection.kt`, `transfer/TransferManager.kt` — 연결
  풀과 큐. `browse{}`(BrowseConnections.withSession), `fetchForViewing`,
  `runUpload`, `fingerprint`가 모두 `WorkerConnection`→`FtpControlConnection`→
  `FtpSession/operations/engine`와 `ResilientTransfer.download/upload`를 직접
  만든다. 여기서 프로토콜 분기가 필요하다.
- `core-ftp/.../protocol/FtpSettings.kt` — `FtpSecurity { PLAIN, EXPLICIT_TLS,
  IMPLICIT_TLS }`. SFTP는 여기 섞지 말 것(TLS 개념과 무관).
- `app/.../data/SiteEntity.kt` — Room 엔티티. `security`, `transferMode`,
  `pinnedCertificate`, `port` 등. SFTP용 필드 추가는 **Room 마이그레이션** 필요.
- `app/.../ui/SiteEditor.kt` — 서버 추가/편집 폼. 프로토콜 선택 UI가 들어갈 곳.
- 인증서 신뢰 흐름: `CertificateUi.kt` + `pinnedCertificate` — **SFTP 호스트키
  신뢰(known-hosts)를 이걸 그대로 본떠서** 만든다(처음 보는/바뀐 호스트키 → 확인
  대화상자 → 지문 고정).

## 권장 구조: Transport 추상화

`TransferManager`에 프로토콜 if/else가 번지지 않도록, 얇은 인터페이스를 도입:

```
interface RemoteTransport {
    fun browseSession(site): RemoteSession           // FtpSession의 표면과 동일
    fun download(site, remotePath, into, offset, abort, progress)
    fun upload(site, remotePath, from, overwrite, abort, progress)
    fun fingerprint(site, remotePath): RemoteFingerprint
}
```
- `FtpTransport` = 기존 코드를 그대로 감싼다(동작 변화 0).
- `SftpTransport` = jsch `Session`+`ChannelSftp`로 구현(`ls/get/put/rename/rm/
  mkdir/chmod/stat`, `get`의 skip=offset로 이어받기, `SftpProgressMonitor`로 진행/
  취소). 사이트의 프로토콜로 둘 중 하나를 고른다.

`SiteEntity`에 `protocol` 컬럼(문자열, 기본 `"FTP"`)을 추가하고 마이그레이션.
`"SFTP"`면 SftpTransport, 아니면 기존 FtpSecurity로 FtpTransport.

## 단계 (각 단계 = 빌드/테스트 통과 + 커밋)

1. **엔진**: jsch 의존성 추가, core-ftp에 `SftpEngine`(연결(비번 인증)·list·stat·
   get·put·rename·rm·mkdir·chmod·이어받기·진행/취소). 인메모리 SSHD로 통합 테스트
   (`org.apache.sshd:sshd-sftp` 테스트 전용). 호스트키 지문 계산 포함.
2. **Transport 추상화**: `RemoteTransport`/`RemoteSession` 도출, `FtpTransport`로
   기존 경로 무변화 래핑(회귀 없음 확인), `SftpTransport` 브라우즈부 연결.
3. **브라우즈 배선**: SFTP 사이트에서 목록/이동/mkdir/rename/삭제/권한이 동작
   하도록 `BrowseConnections`/`TransferManager` 분기.
4. **전송**: 큐 다운로드/업로드/이어받기/진행/취소, `fetchForViewing`, 서버
   텍스트 저장-되돌리기까지 SFTP 경로.
5. **신뢰/UI/DB**: 호스트키 확인 대화상자(인증서 UI 본뜸), SiteEditor에 프로토콜
   선택(FTP/FTPS/SFTP)·기본 포트 22, Room 마이그레이션. (선택) 개인키 인증.

## 지켜야 할 것 (기존 앱 보호)

- **FTP 회귀 금지.** FtpTransport는 기존 동작 그대로. 각 단계에서
  `./gradlew :app:testReleaseUnitTest` 와 `:core-ftp:test` 전부 통과.
- **가드레일 테스트**: WordingTest(문구 중복은 allowed에 등재, 금지어
  지우기/디렉터리/내려받기, %1$s 뒤 바뀌는 조사 금지), IconMeaningTest(아이콘+문구
  매핑), DialogShellTest(OloDialog/OloTextField 셸 사용 파일 목록), StringResourceTest
  (values/values-ko 동시 + 포맷 인자 일치), DownloadEntryPointTest(LocalOperations.
  move/copy 위치). 새 UI 문구는 **values·values-ko 동시** 추가, 우리말 기본.
- `allWarningsAsErrors = true` — 미사용 import/deprecation은 빌드 실패. minSdk 26.
- 보안: 호스트키를 반드시 검증(모르는/바뀐 키는 사용자 확인 없이는 접속 금지).
  비밀번호·키는 기존 `PasswordCipher` 방식으로 저장, 로그에 남기지 말 것.
- 브랜치 `claude/sftp`에만 커밋·푸시. PR은 사용자가 명시적으로 요청할 때만.
  릴리스 APK 검증은 기존 키스토어(`olo-explorer.jks`, gitignored)로 서명.
- 진행 보고는 우리말. 큰 방향 전환(별도 모듈 분리 등)은 사용자에게 먼저 확인.

## 완료 기준

SFTP 사이트를 추가→접속(호스트키 확인)→목록/이동→다운로드·업로드·이름변경·삭제·
권한·이어받기가 동작하고, 뷰어(텍스트/이미지/미디어/PDF)와 큐가 FTP와 동일하게
동작한다. FTP/FTPS는 회귀 없이 그대로.
