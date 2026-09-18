# Server compatibility

What the engine was observed to do against each server, not what the RFCs say
it should. Every row is produced by `ServerCompatibilityTest`, which drives the
real engine through the real protocol and **asserts** each result — a server
that breaks resume fails the build rather than quietly becoming a footnote.

Regenerate with:

```sh
core-ftp/src/test/resources/compat-servers/setup-compat.sh   # once, as root
./gradlew :core-ftp:test --tests '*ServerCompatibilityTest*'
```

The table below is a snapshot of `core-ftp/build/reports/server-compatibility.md`.

| Server | Version | `MLSD` | `MFMT` | `REST STREAM` | Resumed upload uses | `UTF8` | `EPSV` | Listing parsed | Times in listing | Resumed download | Resumed upload | mkdir/rename/rmdir | Non-ASCII names |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Pure-FTPd | 1.0.50 | yes | yes | yes | `REST`+`STOR` | yes | yes | yes (2) | yes | yes | yes | yes | yes |
| pyftpdlib | 1.5.x | yes | yes | yes | `REST`+`STOR` | yes | yes | yes (2) | yes | yes | yes | yes | yes |
| pyftpdlib (no `REST STREAM`) | 1.5.x | yes | yes | not advertised | `APPE` | yes | yes | yes (2) | yes | yes | yes | yes | yes |
| vsftpd | 3.0.5 | not advertised | not advertised | yes | `REST`+`STOR` | not advertised | yes | yes (2) | yes | yes | yes | yes | yes |

"not advertised" means the server's `FEAT` reply did not mention the feature.
That is not the same as the server refusing it — vsftpd answers non-ASCII
filenames correctly without advertising `UTF8`, and the engine treats anything
other than an explicit `yes` as a reason to take the older path.

## What each server is here to prove

**vsftpd** is the one that justifies Phase S. Its `require_ssl_reuse` is on by
default, and a client whose data connection does not resume the control
connection's TLS session gets

```
522 SSL connection failed: session reuse required
```

— not for transfers alone, but for listing a directory. A client without
session resumption cannot use such a server at all. Python's own `ftplib`
fails here; the engine does not.

vsftpd also advertises no `MLSD`, so it is the only real server in the matrix
that exercises the `LIST` path end to end: output meant for people, put through
a parser that has to guess at it. That parser is where a listing silently turns
into nothing, so the test asserts entries were actually produced rather than
that the command merely succeeded.

**Pure-FTPd** takes the other branch of every one of those decisions — `MLSD`,
`MFMT` and `REST STREAM` all advertised and all honoured — which is what makes
it worth running: the two servers between them cover both sides.

**pyftpdlib** is the scriptable one the rest of the integration tests use. It
can be told to fake `REST`, drop a connection mid-transfer, or withhold a
capability, which is how the awkward cases get tested at all.

**pyftpdlib without `REST STREAM`** is the only configured row, and is marked
as such. All three real servers advertise `REST STREAM`, so the `APPE` fallback
— the fifth of the six defences in the README — would never run against a live
server otherwise. The row exists so that it does. The check is byte for byte,
not just the status code: an `APPE` that appends at the wrong offset still
reports success, and the result is a corrupt file of exactly the right size.

## What this does not cover

- **FileZilla Server, IIS, and the commercial Windows servers.** They are where
  the strangest behaviour lives, and none of them run here.
- **The 2 GB / 4 GB resume bug.** It is tested (`LargeFileResumeTest`) against a
  server told to fake `REST`, because no server in this matrix has the bug.
- **Active mode (`PORT`/`EPRT`).** Every row is passive.
- **Plain FTP.** Every row is FTPS explicit; the engine supports plain and
  implicit TLS, but neither is measured here.
