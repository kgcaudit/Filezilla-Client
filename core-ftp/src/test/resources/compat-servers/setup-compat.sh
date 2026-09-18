#!/bin/sh
# Installs the third-party FTP servers the Phase 5 compatibility matrix is
# measured against, and the login account they authenticate.
#
# Must run as root: both servers bind a listening socket and check the password
# against the system account database. ServerCompatibilityTest skips itself
# when this has not been run, so a checkout without it still builds and tests.
set -eu

if [ "$(id -u)" != "0" ]; then
    echo "setup-compat.sh needs root: the servers authenticate system accounts." >&2
    exit 1
fi

USER=fztest
HOME_DIR=/srv/$USER

echo "Installing vsftpd..."
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq vsftpd

# Pure-FTPd is extracted rather than installed: it and vsftpd both provide
# `ftp-server` and conflict, so apt will not hold both. Only the binary is
# wanted, not the service.
echo "Extracting Pure-FTPd..."
if [ ! -x /opt/pure-ftpd/usr/sbin/pure-ftpd ]; then
    tmp=$(mktemp -d)
    (cd "$tmp" && apt-get download pure-ftpd pure-ftpd-common >/dev/null 2>&1)
    mkdir -p /opt/pure-ftpd
    for deb in "$tmp"/pure-ftpd*.deb; do dpkg -x "$deb" /opt/pure-ftpd; done
    rm -rf "$tmp"
fi

echo "Creating the $USER login..."
id "$USER" >/dev/null 2>&1 || useradd -m -d "$HOME_DIR" "$USER"
# vsftpd's PAM stack includes pam_shells, which rejects a login whose shell is
# not listed in /etc/shells -- /usr/sbin/nologin is refused there, so the
# account gets a real one.
usermod -s /bin/sh -d "$HOME_DIR" "$USER"
echo "$USER:fztest" | chpasswd
mkdir -p "$HOME_DIR" /var/run/vsftpd/empty
chown "$USER:" "$HOME_DIR"

# The servers reuse the certificate the Python test server already generates.
CERT_DIR=$(cd "$(dirname "$0")/../ftps-server" && pwd)
if [ ! -f "$CERT_DIR/cert.pem" ]; then
    echo "Run ../ftps-server/setup.sh first; it generates the certificate." >&2
    exit 1
fi

echo "Ready. Run: ./gradlew :core-ftp:test --tests '*ServerCompatibilityTest*'"
