# FileZilla-Client for Android

An Android FTP/FTPS client whose goal is that **connecting to a server and
resuming an interrupted transfer work correctly, every time** — including the
awkward cases that break most mobile FTP clients.

The transfer logic is ported from
[FileZilla Client 3.71.1](https://filezilla-project.org/) (Tim Kosse,
GPL-3.0-or-later), specifically `src/engine/ftp/` and `src/engine/controlsocket.cpp`.

## Status

| Phase | Scope | State |
|---|---|---|
| S | FTPS data-channel TLS session resumption spike | **done** — see [`spikes/ftps-session-reuse/`](spikes/ftps-session-reuse/) |
| 0 | Project skeleton, module split | **done** |
| 1 | FTP/FTPS protocol core, listing, directory operations | **done** |
| 2 | Resume engine | **done** — verified against a live server, including a server that fakes `REST` |
| 3 | Background transfers, persistence, network-change recovery | **done** — Room-backed journal, foreground service, SAF storage |
| 4 | UI | **done** — sites, remote browser, transfer queue, message log |
| 5 | Server compatibility matrix | |

## Why the transfer logic is ported rather than written fresh

FileZilla's resume path is not "send `REST` and hope". It is six layered
defences, each of which exists because some real server needed it:

1. `SIZE` / `MLSD` for the remote size, compared against the local partial file.
2. `REST <offset>` + `RETR`, with a hard failure if the server does not answer
   `REST` with 2xx/3xx — the transfer never proceeds on an ignored offset.
3. A live probe for servers that mishandle offsets past 2 GB / 4 GB: `REST
   (size-1)` + `RETR` must return **exactly one byte**. The verdict is cached
   per host (`resume2GBbug` / `resume4GBbug`).
4. When resume is impossible but the sizes already match, the transfer is
   completed rather than restarted.
5. Uploads use `REST`+`STOR` when the server advertises `REST STREAM` in
   `FEAT`, and fall back to `APPE` otherwise.
6. The data connection must resume the control connection's TLS session, or
   servers with `require_ssl_reuse` reject the transfer.

Reimplementing these from a description is how subtle corruption bugs get
introduced, so the state machines are transliterated from the original.

## Architecture

```
:core-ftp    pure Kotlin/JVM — protocol, resume engine, transfer journal,
             resume safety. No Android APIs.
             (integration-tested against a real FTPS server)
:app         Android — Room journal, SAF storage, foreground service, Compose UI
```

Keeping the engine free of Android APIs is what makes it testable on a plain
JVM against a live server, which is where the resume behaviour is actually
verified.

The split is drawn so that **everything that decides anything is in
`:core-ftp`**. Whether a journalled offset is still safe to resume from is
`ResumeSafety`; applying that decision while keeping the journal current is
`JournalledTransfer`; both are tested against a live server. `:app` supplies
the four things that genuinely need Android and re-decides none of it:

| `:app` supplies | Why it cannot live in `:core-ftp` |
|---|---|
| `RoomTransferJournal` | The journal has to survive the process being killed, and SQLite is what does that on Android. |
| `PartialFiles` + `SafStorage` | Resume needs positioned writes, which a Storage Access Framework document may not support — so partial files are written to app-private storage and copied into the user's folder once complete. |
| `TransferService` | Android freezes a backgrounded process within minutes; a `dataSync` foreground service is what keeps a large transfer running. |
| `NetworkGate` | Only the platform knows the phone has no signal at all. It extends a wait rather than shortening one: how soon it is reasonable to hit a server again stays the engine's decision. |

## Building

`:core-ftp` builds and tests with a stock JDK 17+ and Gradle:

```sh
./gradlew :core-ftp:test
```

The integration tests drive a real FTPS server. Set it up once:

```sh
core-ftp/src/test/resources/ftps-server/setup.sh
```

Without it those tests skip themselves and the unit tests still run.

`:app` needs the Android SDK. With `ANDROID_HOME` set (or `sdk.dir` in
`local.properties`) it joins the build automatically:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Without an SDK the settings script leaves `:app` out, so `:core-ftp` still
builds and tests on a plain JDK.

### What has and has not been run

`:core-ftp`'s 95 tests pass, the integration ones against a live FTPS server.
`:app`'s 19 unit tests pass on the JVM under Robolectric, and cover the pieces
whose failure would be silent — that a `TransferRecord` survives the round trip
through Room with its offset and fingerprint intact, that a `RUNNING` row left
by a killed process is still resumable, and that the queue gives up rather than
re-running a failing transfer forever.

The APK builds, but **it has not been run on a device or an emulator**: there
is none in the environment it was written in. So the wiring the unit tests
cover is verified and the on-screen behaviour is not.

### Known gap

Site passwords are stored unencrypted in the app's private database. That is
the same exposure as FileZilla's own `sitemanager.xml` and is private to the
app on a non-rooted device, but it is not protected against a device backup or
a rooted phone. Moving it behind the Android keystore is its own piece of work.

## Licence

GPL-3.0-or-later, inherited from FileZilla Client. See [LICENSE](LICENSE).

FileZilla Client is Copyright (C) 2004-2026 Tim Kosse.
