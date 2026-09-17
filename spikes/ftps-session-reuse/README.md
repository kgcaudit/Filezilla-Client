# Phase S — FTPS data-channel TLS session resumption

## Question

Many FTPS servers refuse a data connection that does not resume the TLS
session negotiated on the control connection — vsftpd (`require_ssl_reuse=YES`,
which is the **default**), ProFTPD's `mod_tls`, and FileZilla Server all do
this. FileZilla enforces the same rule from the client side and aborts the
transfer when the data connection fails to resume
(`src/engine/ftp/transfersocket.cpp:220-256`).

Java's `SSLSocket` keys its client session cache on host **and port**, and the
data connection uses a different port from the control connection. So the
question that had to be settled before committing to a Kotlin/JVM engine was:

> Can a JSSE/Conscrypt client resume the control session on the data channel
> without reflection into JDK internals?

## Answer: yes

Two things are needed together:

1. Pass the **control** host and port to
   `SSLSocketFactory.createSocket(socket, host, port, autoClose)`.
2. Wrap the connected data socket so that it *also reports* the control
   endpoint from `getPort()` / `getInetAddress()` (see `SessionHintSocket`).

Step 2 is the part that is easy to miss. JSSE's `BaseSSLSocketImpl.getPort()`
delegates to the wrapped socket, so the cache lookup is keyed on the **data**
port and misses the control session even though the correct host/port were
passed to `createSocket`. With only step 1, the handshake is a full one and the
server rejects the transfer.

Conscrypt (Android) honours the `createSocket` arguments directly, so the
wrapper is redundant there but harmless — one code path works on both.

## Measured results

Test server: `ftps_server.py` (pyftpdlib + pyOpenSSL). It reports OpenSSL's own
`SSL_session_reused()` for each data connection and rejects with `522` when the
session was not resumed, mirroring vsftpd.

| # | Scenario | Client session id | Server `SSL_session_reused` | Outcome |
|---|---|---|---|---|
| 1 | TLS 1.2, hint applied | same as control | `True` | `226`, 1048576 bytes |
| 2 | TLS 1.2, `--no-reuse` (control) | differs | `False` | `522` rejected |
| 3 | TLS 1.2, hint + `REST 500000` | same as control | `True` | `226`, 548576 bytes |
| 4 | TLS 1.3, hint applied | **differs** | `True` | `226`, 1048576 bytes |

Scenario 2 is the negative control: it proves the server really is enforcing
the rule, so scenarios 1/3/4 are not passing by accident.

## Implementation notes carried into the engine

- **Do not detect resumption by comparing session ids.** Scenario 4 shows TLS
  1.3 resumption produces a *new* session id, so an id comparison reports a
  false negative. Java exposes no public equivalent of GnuTLS's
  `resumed_session()`, which is what FileZilla checks. The engine therefore
  attempts reuse unconditionally and treats the server's `522` as the signal,
  recording a per-host `tls_resumption` capability the way
  `servercapabilities.h` does.
- Session tickets alone were not enough on JDK 21: with the server issuing
  tickets and an empty `ServerHello` session id, the JDK logged
  `Ignore impact of unsupported extension: session_ticket` and started a full
  handshake. Classic session-id resumption (`SSL_OP_NO_TICKET` on the server,
  which is what vsftpd does) resumed correctly. TLS 1.3 resumed via PSK
  tickets without trouble. Real servers vary, so the engine must not assume
  either mechanism.

## Reproducing

```sh
python3 -m venv venv && ./venv/bin/pip install pyftpdlib pyopenssl
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 \
    -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
mkdir -p ftproot && python3 -c "open('ftproot/big.bin','wb').write(bytes(range(256))*4096)"

REQUIRE_SSL_REUSE=1 TLS_MAX=1.2 NO_TICKET=1 ./venv/bin/python ftps_server.py &

javac -d classes java/*.java
java -cp classes FtpsReuseSpike 127.0.0.1 2121 test test big.bin             # expect OK
java -cp classes FtpsReuseSpike 127.0.0.1 2121 test test big.bin --no-reuse  # expect 522
java -cp classes FtpsReuseSpike 127.0.0.1 2121 test test big.bin --rest 500000
```
