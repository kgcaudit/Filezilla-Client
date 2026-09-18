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
| 0 | Project skeleton, module split | in progress |
| 1 | FTP/FTPS protocol core, listing, directory operations | **done** |
| 2 | Resume engine | **done** — verified against a live server, including a server that fakes `REST` |
| 3 | Background transfers, persistence, network-change recovery | reconnect-and-resume, the transfer journal and resume safety are done and verified; the foreground service, Room store and SAF storage need the Android module |
| 4 | UI | |
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
:core-ftp    pure Kotlin/JVM — protocol, resume engine, no Android APIs
             (integration-tested against a real FTPS server)
:app         Android — UI, foreground service, storage, persistence
```

Keeping the engine free of Android APIs is what makes it testable on a plain
JVM against a live server, which is where the resume behaviour is actually
verified.

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

`:app` needs the Android SDK and Android Studio.

## Licence

GPL-3.0-or-later, inherited from FileZilla Client. See [LICENSE](LICENSE).

FileZilla Client is Copyright (C) 2004-2026 Tim Kosse.
