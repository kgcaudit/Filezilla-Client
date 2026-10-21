# 릴리스 노트 · Release Notes — OLO Explorer

사용자 입장에서 달라진 점을 버전별로 정리합니다(최신이 위). 각 항목은
한국어와 영어를 함께 싣습니다. 버전 이름은 `0.1.<커밋 수>` 규칙으로 자동
매겨지므로, 아래 번호는 그 기능이 처음 들어간 빌드를 가리킵니다.

User-facing changes by version (newest first). Each section is given in Korean
and then English. Version names follow `0.1.<commit count>`, so a number below
marks the build a feature first shipped in.

> 설치 메모: 0.1.60 이후 빌드는 모두 같은 서명 키로 서명되어 서로 덮어쓰기
> 설치됩니다. 그 이전(서명 키가 다른) 빌드에서 올라올 때는 한 번만 삭제 후
> 설치해야 할 수 있습니다.
>
> Install note: builds from 0.1.60 on share one signing key and install over
> each other. Coming from an earlier build signed with a different key, you may
> need to uninstall once first.

---

## 0.1.85 — 서버 탐색 속도 개선 · Faster server browsing

**한국어**
- **잠깐 보다가 다시 이동할 때 느린 재접속을 줄였습니다.** 서버 탐색에
  쓰는 연결을 유휴 상태로 유지하는 시간을 1분에서 3분으로 늘렸습니다.
  폴더를 열어 두고 잠깐 읽은 뒤 다른 폴더로 가도 기존 접속을 재사용하므로,
  느린 서버에서 매번 다시 로그인하느라 기다리는 일이 줄어듭니다. (연달아
  이동할 때는 전부터 접속을 재사용해 이미 빨랐습니다. 특정 폴더 첫 진입이
  느린 것은 서버 쪽 지연이라 앱에서 줄일 수 없습니다.)

**English**
- **Fewer slow reconnects when you come back after a pause.** The idle
  connection used for browsing a server is now kept for three minutes instead
  of one. Open a folder, read for a moment, then move on, and the app reuses
  the existing login rather than making you wait for a fresh one on a slow
  server. (Back-to-back navigation already reused the connection and was fast.
  A slow first entry into some folders is server-side latency the app cannot
  shorten.)

## 0.1.80–0.1.82 — 로그 저장·탐색 다듬기 · Log export & browsing polish

**한국어**
- **접속 로그를 파일로 저장.** 접속 로그 화면 우상단의 저장 버튼으로 로그를
  텍스트 파일로 내보냅니다. 앱 버전·기기·시각이 머리글로 붙어, 나중에 문제가
  생겼을 때 그대로 공유해 원인을 확인할 수 있습니다.
- **폴더에 들어가면 목록 최상단부터.** 이전 폴더에서 아래로 스크롤했더라도,
  새 폴더는 맨 위 항목부터 열립니다.
- **"다시 연결하는 중" 표시 완화.** 느린 국제선 연결에서 데이터가 몇 초
  멈추기만 해도 바로 "다시 연결하는 중"으로 바뀌던 것을, 실제로 끊김에
  가까워졌을 때(읽기 타임아웃 직전)만 표시하도록 했습니다. 실제 전송
  복원력은 그대로입니다.

**English**
- **Save the connection log to a file.** The save button at the top of the log
  screen exports the log as a text file, headed with the app version, device
  and time — so when something goes wrong later, you can share it to track
  down the cause.
- **Folders open at the top.** Even if you scrolled down the previous folder, a
  new one opens from its first row.
- **Calmer "reconnecting" label.** A few seconds of silence on a slow
  international link no longer flips the card straight to "reconnecting" — it
  now says so only when a stall is genuinely close to a dropped connection
  (near the read timeout). The actual transfer resilience is unchanged.

## 0.1.79 — 수정: 다운로드 버튼 앱 종료 · Fix: download-button crash

**한국어**
- **서버에서 다운로드 버튼을 누르면 앱이 꺼지던 문제를 고쳤습니다(Android
  13 이상).** 앱 잠금(지문)에 쓰이는 라이브러리가 끌어오던 오래된 구성요소가
  최신 구성요소와 충돌해, 다운로드 시 알림 권한을 요청하는 순간 앱이
  종료됐습니다. 라이브러리 버전을 맞춰 해결했습니다.

**English**
- **Fixed the app closing when you tapped download on a server (Android 13+).**
  An old component pulled in by the app-lock (fingerprint) library clashed with
  a newer one, so the app crashed the moment a download asked for notification
  permission. Aligning the library versions fixes it.

## 0.1.78 — 크래시 원인 기록 · Crash reporting

**한국어**
- **앱이 예기치 않게 종료되면 그 원인을 기록합니다.** 다음에 앱을 열면 접속
  로그 화면 맨 위에 직전 종료의 원인이 표시되고, 위 저장 기능으로 공유할 수
  있습니다. 시스템의 종료 안내 등 동작은 그대로이며, 기록만 더해집니다.

**English**
- **Records why the app closed, if it ever does unexpectedly.** The next time
  you open it, the reason for the last crash appears at the top of the
  connection log, ready to export with the save feature above. The system's own
  behavior is unchanged; this only adds a record.

## 0.1.73–0.1.77 — 안정성 점검·보강 · Stability audit & hardening

**한국어**
- **전반 코드 점검.** 다운로드 중 파일 핸들 누수, 연결 테스트의 정확성,
  일부 파일명(`+` 포함)의 디코딩 등을 바로잡았습니다.
- **앱이 꺼지던 여러 지점 보강.** 미처리 예외가 앱을 강제 종료시키던
  경로들(포그라운드 서비스 시작, 열 수 있는 앱이 없는 인텐트, 저장소 권한
  회수, 전송 큐잉, 휴지통 등)을 감싸, 드문 오류 상황에서도 크래시 대신
  안내나 무시로 처리합니다.
- **빠른 스크롤 배지 다듬기.** 긴 목록을 드래그할 때 뜨는 글자 배지를,
  글자 폭에 따라 늘어나던 투박한 사각형에서 고정 크기의 둥근 배지로
  바꿨습니다.

**English**
- **A broad code audit.** Fixed a file-handle leak during downloads, the
  accuracy of the connection test, and the decoding of some filenames (those
  with `+`).
- **Hardened several spots where the app could close.** Paths where an uncaught
  error force-closed the app (starting the foreground service, intents with no
  app to handle them, a revoked storage grant, transfer queuing, the trash, and
  more) are now wrapped, so a rare error is handled with a message or a no-op
  instead of a crash.
- **Refined the fast-scroll badge.** The letter badge shown while dragging a
  long list is now a fixed-size rounded badge instead of a blunt rectangle that
  stretched with the letter.

## 0.1.70 — 내부 안정화 · Internal stabilization

**한국어**
- **아카이브 뷰어 테스트 플레이크 제거(내부).** 백그라운드 작업 디스패처를
  주입 가능하게 바꿔, 종단 테스트가 실제 스레드풀 타이밍에 흔들리지 않고
  결정적으로 돌도록 했습니다. 앱 동작은 그대로입니다.
- **수정:** SFTP 호스트 키 수락 문구를 앱 표준 어휘("서버 키")로 통일.

**English**
- **Fixed a flaky archive-viewer test (internal).** The background-work
  dispatcher is now injectable, so end-to-end tests finish deterministically
  instead of racing a real thread pool. No change to how the app behaves.
- **Fix:** the SFTP host-key acceptance wording now uses the app's standard
  Korean term ("서버 키").

## 0.1.67–0.1.68 — 연결 테스트 · Test connection

**한국어**
- **사이트 편집기에 "연결 테스트" 버튼.** 저장하기 전에 설정을 서버에 바로
  시험해 봅니다. 공유 이름·비밀번호·HTTP/HTTPS 선택이 맞는지 가장 빨리
  확인하는 길이며, 실패 사유는 접속 화면과 똑같은 문구로 보여 줍니다.
- **자가서명 서버 바로 수락.** 집 서버처럼 인증서·SSH 키가 공인되지 않은
  경우, 테스트 결과에서 바로 "이 인증서/서버 키를 수락하고 다시 시도"할 수
  있습니다. 처음 만나는 경우에만 한 번에 수락되고, 이전에 수락한 것과
  **달라진** 경우(위험 신호)는 그대로 경고로 남깁니다.

**English**
- **A "Test connection" button in the site editor.** Try the settings against
  the server before saving them — the fastest way to catch a wrong share name,
  password or HTTP/HTTPS choice. Failures read in the same words the browsing
  screen uses.
- **Accept a self-signed server on the spot.** For a home server whose
  certificate or SSH key is vouched for by nobody, the test result lets you
  "accept this certificate / host key and retry" right there. Only a first
  meeting is accepted in one tap; a certificate or key that *changed* from one
  accepted before (a warning sign) is left as a plain failure to look at.

## 0.1.66 — 수정: WebDAV·SMB 파일 조작 · Fix: WebDAV/SMB file operations

**한국어**
- WebDAV·SMB에서 **붙여넣기·이동·폴더(트리) 삭제, 그리고 동기화의 원격
  삭제**가 절대 경로를 잘못 다뤄 실패하던 문제를 고쳤습니다. 이제 FTP·SFTP와
  동일하게 동작합니다.

**English**
- Fixed **paste, move, folder (tree) delete, and sync's remote delete** failing
  on WebDAV and SMB because absolute paths were handled wrongly. They now behave
  the same as on FTP and SFTP.

## 0.1.65 — SMB 공유·도메인 입력 개선 · Clearer SMB share & domain

**한국어**
- SMB 사이트에서 **공유 이름**과 **도메인**을 각각 전용 칸에 입력합니다
  (예전처럼 경로 첫 부분이나 `DOMAIN\사용자`로 욱여넣지 않아도 됩니다).
- 기존에 저장해 둔 SMB 사이트는 그대로 동작합니다(자동 이관, 데이터 보존).

**English**
- An SMB site now takes its **share** and **domain** in fields of their own (no
  more packing the share into the path or the domain into `DOMAIN\user`).
- SMB sites saved before this keep working (migrated automatically, nothing
  lost).

## 0.1.64 — 읽을 수 있는 연결 오류 · Readable connection errors

**한국어**
- SMB·SFTP 접속 실패가 원문 상태 코드 대신 **무엇이 잘못됐고 어떻게 할지**
  알려 주는 문구로 바뀌었습니다(예: "로그인이 거부되었습니다", "공유를 찾을
  수 없습니다"). 원문은 근거로 아래에 그대로 남습니다.

**English**
- SMB and SFTP connection failures now say **what went wrong and what to do**
  instead of a raw status code (e.g. "Login rejected", "Share not found"). The
  raw text is kept underneath as evidence.

## 0.1.60–0.1.63 — 새 프로토콜: WebDAV와 SMB · New protocols: WebDAV and SMB

**한국어**
- **WebDAV 지원**(Nextcloud 등 HTTP/HTTPS 서버). 탐색·업로드·다운로드·이어받기.
- **SMB 지원**(Windows 공유·NAS). SMB 2.0.2부터 **3.1.1까지**, SMB3의
  **서명과 암호화**를 포함합니다. 안드로이드에서 안전하게 동작하도록 암호화를
  직접 구현했고, 암호화를 강제하는 공유에도 연결됩니다.
- 두 프로토콜 모두 **실제 서버로 검증**했습니다.

**English**
- **WebDAV support** (Nextcloud and other HTTP/HTTPS servers): browse, upload,
  download, resume.
- **SMB support** (Windows shares and NAS): SMB 2.0.2 up to **3.1.1**, including
  SMB3 **signing and encryption**. The encryption is implemented in a way that
  runs safely on Android, and shares that require encryption connect too.
- Both protocols were **verified against real servers**.

## 0.1.53–0.1.55 — 보안·공유·동기화 · Security, sharing, sync

**한국어**
- **양방향 폴더 동기화(미러링).** 로컬과 원격 폴더를 맞춥니다(스캔 → 미리보기
  → 확인 → 실행, 삭제는 기본 꺼짐). 이제 FTP·SFTP·WebDAV·SMB 모두에서 됩니다.
- **다른 앱에서 공유로 받은 파일 업로드.** 사진·문서를 OLO Explorer로 공유해
  바로 서버에 올립니다.
- **앱 잠금(생체 인증)과 암호화 백업·복원**, 그리고 **설정 화면**.

**English**
- **Two-way folder sync (mirroring).** Bring a local and a remote folder into
  line (scan → preview → confirm → run; deletion off by default). Now works over
  FTP, SFTP, WebDAV and SMB.
- **Upload files shared from other apps.** Share a photo or document to OLO
  Explorer and send it straight to a server.
- **App lock (biometric) and encrypted backup/restore**, plus a **settings
  screen**.

## 0.1.49–0.1.51 — 이미지 뷰어 확장 · Image viewer formats

**한국어**
- **SVG·svgz(gzip SVG)** 열기, **AVIF·WBMP** 지원, **움직이는 GIF·WebP** 재생.

**English**
- Open **SVG and svgz (gzip SVG)**, support **AVIF and WBMP**, and play
  **animated GIF and WebP**.

## 그 밖의 수정 · Other fixes

**한국어**
- 메뉴에서 들어간 화면에서 뒤로가기가 메뉴로 돌아오도록 흐름 정리.
- 최근 기록·휴지통·목록의 중복 키로 인한 크래시 수정.
- 접속 시작 위치 정리와 작업 완료 후 목록 자동 새로고침.

**English**
- Back now returns to the menu from a screen opened out of it.
- Fixed crashes from duplicate keys in recents, trash and listings.
- Tidied where a connection starts, and auto-refresh of the listing after a job
  finishes.
