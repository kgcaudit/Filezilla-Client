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
  STALL_AFTER_BYTES   go silent after N bytes (default 0, never)
  STALL_TIMES         how many may stall      (default 0, all of them)
  FTPS_ENCODING       wire encoding for paths (default utf8)
"""

import os
import select
import sys
import time
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
# Drop "REST STREAM" from FEAT, as servers that only support appending do.
# The engine then has to resume an upload with APPE instead of REST+STOR --
# one of the six defences the README lists, and the only one no real server in
# the compatibility matrix exercises, since all three advertise REST STREAM.
NO_REST_STREAM = os.environ.get("NO_REST_STREAM", "0") == "1"
DROP_AFTER_BYTES = int(os.environ.get("DROP_AFTER_BYTES", "0"))
# Bytes per second on the data channel, 0 for unlimited. A test that has to
# interrupt a transfer partway needs the transfer to last long enough to be
# interrupted: over loopback a few megabytes are gone in a fraction of a
# second, and the interruption arrives after the file is already complete.
THROTTLE_BYTES = int(os.environ.get("THROTTLE_BYTES", "0"))
DROP_TIMES = int(os.environ.get("DROP_TIMES", "0"))
# Stop sending after this many bytes while leaving the connection open, 0 to
# never. Not the same fault as DROP_AFTER_BYTES and the harder one: a cut
# connection reports an error, whereas a phone whose network has gone gets no
# error, no end of file and nothing else either -- the read simply never
# returns. That is what makes a transfer look frozen, and what a stop has to
# be able to interrupt.
STALL_AFTER_BYTES = int(os.environ.get("STALL_AFTER_BYTES", "0"))
# How many data connections may stall, 0 for all of them. A test that expects
# the client to notice and reconnect needs the connection after the stall to
# work, or it cannot tell a recovery from a second failure.
STALL_TIMES = int(os.environ.get("STALL_TIMES", "0"))

# What filenames are encoded as on the control channel. A great many NAS
# boxes sold in Korea and Japan store and speak a legacy encoding rather than
# UTF-8, and pyftpdlib leaves UTF8 out of FEAT when this is not utf8 -- which
# is exactly the server the per-site encoding setting exists for. Nothing
# tested it until this existed.
ENCODING = os.environ.get("FTPS_ENCODING", "utf8")

_drops_remaining = DROP_TIMES
_stalls_remaining = STALL_TIMES
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
        self._throttle_started = None
        self._throttle_sent = 0
        self._sent_bytes = 0
        self._dropping = False
        self._stalling = False

    def _pace(self, count):
        """Holds the data channel to THROTTLE_BYTES per second.

        Done by sleeping here rather than through pyftpdlib's
        ThrottledDTPHandler, which the TLS data handler does not inherit from.
        The sleep stalls the whole server, which is acceptable only because the
        tests that ask for a throttle run one transfer at a time -- the point is
        to make a transfer last long enough to interrupt, not to model a slow
        network faithfully.
        """
        if THROTTLE_BYTES <= 0 or count <= 0:
            return
        if self._throttle_started is None:
            self._throttle_started = time.monotonic()
        self._throttle_sent += count
        owed = self._throttle_sent / THROTTLE_BYTES - (time.monotonic() - self._throttle_started)
        if owed > 0:
            time.sleep(min(owed, 0.25))

    def _should_stall(self):
        global _stalls_remaining
        if STALL_AFTER_BYTES <= 0 or self._sent_bytes < STALL_AFTER_BYTES:
            return False
        # Once a connection has gone silent it stays silent: the fault being
        # modelled is a network that went, and it does not come back on the
        # same socket.
        if self._stalling:
            return True
        if STALL_TIMES > 0:
            if _stalls_remaining <= 0:
                return False
            _stalls_remaining -= 1
        self._stalling = True
        note(f"stalling data connection after {self._sent_bytes} bytes")
        return True

    def _client_hung_up(self):
        """True once the client has closed a connection we stopped sending on.

        Needed because a stalled handler never touches the socket, so nothing
        else would notice. Left unnoticed it would sleep in the poll loop for
        ever -- and this server is single-threaded, so that hangs the control
        channel too and the client's reconnect never gets a reply.
        """
        try:
            if not select.select([self.socket], [], [], 0)[0]:
                return False
            return not self.socket.recv(1)
        except SSL.WantReadError:
            return False
        except (SSL.ZeroReturnError, SSL.SysCallError, SSL.Error, OSError):
            return True

    def send(self, data):
        global _drops_remaining
        if self._should_stall():
            if self._client_hung_up():
                note("stalled data connection was closed by the client")
                self.close()
                return 0
            # Slept rather than spun, since nothing here is in a hurry and the
            # poll loop would otherwise burn a core for the length of the test.
            time.sleep(0.05)
            return 0

        if DROP_AFTER_BYTES <= 0 or _drops_remaining <= 0 or self._dropping:
            sent = super().send(data)
            self._sent_bytes += sent
            self._pace(sent)
            return sent

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
        self._pace(sent)
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
    # Applied to every path on the control channel, in both directions.
    encoding = ENCODING
    # Names that cannot be represented are an error rather than a silent
    # substitution: a test that saw "???.mp3" and passed would be worse
    # than one that failed.
    unicode_errors = "strict"

    def ftp_FEAT(self, line):
        if not NO_REST_STREAM:
            return super().ftp_FEAT(line)
        # pyftpdlib appends "REST STREAM" unconditionally, so the reply is
        # rebuilt rather than filtered. REST itself still works: the point is
        # a server that does not advertise the capability, not one that has
        # lost the command -- download resume must stay measurable.
        feats = [
            "UTF8", "TVFS", "EPRT", "EPSV", "MDTM", "MFMT", "SIZE",
            "MLST type*;size*;modify*;", "MLSD",
        ]
        note("FEAT without REST STREAM, forcing the APPE path for uploads")
        self.push("211-Features supported:\r\n")
        for feat in feats:
            self.push(" %s\r\n" % feat)
        self.respond("211 End FEAT.")

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
        f"stall_after={STALL_AFTER_BYTES}x{STALL_TIMES} "
        f"encoding={ENCODING} "
        f"throttle={THROTTLE_BYTES} "
        f"root={ROOT}"
    )
    # Readiness marker the test harness waits for; stdout, not stderr.
    print(f"READY {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
