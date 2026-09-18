"""FTPS test server for the integration tests, mimicking vsftpd's
`require_ssl_reuse=YES`.

The data connection must resume the TLS session negotiated on the control
connection, or the transfer is rejected -- the same rule enforced by vsftpd
(require_ssl_reuse=YES, the default), ProFTPD (mod_tls SessionReuse) and
FileZilla Server.

Resumption is detected with OpenSSL's own SSL_session_reused(), reached
through pyOpenSSL's cffi bindings, so the verdict is the real server-side
answer rather than an inference.

Env:
  FTPS_PORT           control port            (default 2121)
  FTPS_PASV_PORTS     passive port range      (default 2230-2240)
  REQUIRE_SSL_REUSE   1 = enforce reuse       (default 1)
  TLS_MAX             "1.2" or "1.3"          (default 1.2)
"""

import os
import sys
import logging

from OpenSSL import SSL
from OpenSSL._util import lib as _sslib

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.servers import FTPServer
from pyftpdlib.handlers.ftps.control import TLS_FTPHandler
from pyftpdlib.handlers.ftps.data import TLS_DTPHandler

HERE = os.path.dirname(os.path.abspath(__file__))

REQUIRE_SSL_REUSE = os.environ.get("REQUIRE_SSL_REUSE", "1") == "1"
# Acknowledge REST with 350 but then ignore the offset, which is how servers
# with the 2 GB / 4 GB offset bug behave. The engine is supposed to catch this
# with its one-byte probe rather than trusting the 350.
IGNORE_REST = os.environ.get("IGNORE_REST", "0") == "1"
# Cut the data connection after this many bytes, for the first DROP_TIMES
# transfers, reproducing a connection that dies partway -- the ordinary case
# on a phone that changes network or loses signal.
DROP_AFTER_BYTES = int(os.environ.get("DROP_AFTER_BYTES", "0"))
DROP_TIMES = int(os.environ.get("DROP_TIMES", "0"))

_drops_remaining = DROP_TIMES
TLS_MAX = os.environ.get("TLS_MAX", "1.2")
PORT = int(os.environ.get("FTPS_PORT", "2121"))
PASV_LO, PASV_HI = (
    int(x) for x in os.environ.get("FTPS_PASV_PORTS", "2230-2240").split("-")
)
ROOT = os.environ.get("FTPS_ROOT", os.path.join(HERE, "ftproot"))


def session_reused(conn):
    """True if this TLS connection resumed a previously established session."""
    return bool(_sslib.SSL_session_reused(conn._ssl))


def note(msg):
    print(f"[server] {msg}", file=sys.stderr, flush=True)


class ReuseCheckingDTPHandler(TLS_DTPHandler):
    """Rejects a data connection whose TLS session was not resumed, and
    optionally cuts the connection partway through a transfer."""

    def __init__(self, sock, cmd_channel):
        super().__init__(sock, cmd_channel)
        self._sent_bytes = 0
        self._dropping = False

    def send(self, data):
        global _drops_remaining
        if DROP_AFTER_BYTES <= 0 or _drops_remaining <= 0 or self._dropping:
            return super().send(data)

        remaining = DROP_AFTER_BYTES - self._sent_bytes
        if remaining <= 0:
            _drops_remaining -= 1
            self._dropping = True
            note(
                f"cutting data connection after {self._sent_bytes} bytes "
                f"({_drops_remaining} drop(s) left)"
            )
            self.close()
            return 0
        if len(data) > remaining:
            data = data[:remaining]
        sent = super().send(data)
        self._sent_bytes += sent
        return sent

    def handle_ssl_established(self):
        super().handle_ssl_established()
        conn = self.socket
        reused = session_reused(conn)
        note(
            f"data conn: tls={conn.get_protocol_version_name()} "
            f"cipher={conn.get_cipher_name()} session_reused={reused}"
        )
        if REQUIRE_SSL_REUSE and not reused:
            note("REJECT: data connection did not reuse the control session")
            self.cmd_channel.respond("522 SSL session reuse required.")
            self.close()


class Handler(TLS_FTPHandler):
    dtp_handler = ReuseCheckingDTPHandler

    def ftp_REST(self, line):
        super().ftp_REST(line)
        if IGNORE_REST and self._restart_position:
            note(f"pretending to honour REST {self._restart_position}, actually ignoring it")
            self._restart_position = 0

    def handle_ssl_established(self):
        super().handle_ssl_established()
        note(
            f"ctrl conn: tls={self.socket.get_protocol_version_name()} "
            f"cipher={self.socket.get_cipher_name()}"
        )


def build_ssl_context():
    ctx = SSL.Context(SSL.TLS_SERVER_METHOD)
    cert_dir = os.environ.get("FTPS_CERT_DIR", HERE)
    ctx.use_certificate_chain_file(os.path.join(cert_dir, "cert.pem"))
    ctx.use_privatekey_file(os.path.join(cert_dir, "key.pem"))
    ctx.set_options(SSL.OP_NO_SSLv2 | SSL.OP_NO_SSLv3 | SSL.OP_NO_TLSv1 | SSL.OP_NO_TLSv1_1)
    if os.environ.get("NO_TICKET", "1") == "1":
        # Classic session-ID resumption, as vsftpd/ProFTPD use by default.
        ctx.set_options(SSL.OP_NO_TICKET)
    if TLS_MAX == "1.2":
        ctx.set_options(SSL.OP_NO_TLSv1_3)
    # OpenSSL refuses to resume server side unless a session id context is set.
    ctx.set_session_id(b"fz-android-spike")
    ctx.set_session_cache_mode(SSL.SESS_CACHE_SERVER)
    return ctx


def main():
    logging.basicConfig(level=logging.INFO, format="[pyftpdlib] %(message)s")

    authorizer = DummyAuthorizer()
    os.makedirs(ROOT, exist_ok=True)
    authorizer.add_user("test", "test", ROOT, perm="elradfmwMT")

    Handler.authorizer = authorizer
    Handler.ssl_context = build_ssl_context()
    Handler.tls_control_required = True
    Handler.tls_data_required = True
    Handler.passive_ports = list(range(PASV_LO, PASV_HI + 1))
    Handler.masquerade_address = "127.0.0.1"

    server = FTPServer(("127.0.0.1", PORT), Handler)
    note(
        f"listening on 127.0.0.1:{PORT} "
        f"require_ssl_reuse={REQUIRE_SSL_REUSE} tls_max={TLS_MAX} "
        f"ignore_rest={IGNORE_REST} drop_after={DROP_AFTER_BYTES}x{DROP_TIMES} "
        f"root={ROOT}"
    )
    # Readiness marker the test harness waits for; stdout, not stderr.
    print(f"READY {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
