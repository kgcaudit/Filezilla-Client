#!/usr/bin/env bash
#
# Real WebDAV and SMB servers for developing and verifying the clients against.
#
# The integration tests (WebDavSmokeTest, SmbSmokeTest) start these themselves
# in throwaway containers and skip when Docker is not there. This script is the
# same thing by hand: bring the servers up once and leave them running while
# writing a client against them, point a real app build at them, or watch their
# logs when something disagrees.
#
#   ./test-servers.sh up       # start both, print how to reach them
#   ./test-servers.sh down     # stop and remove both
#   ./test-servers.sh status   # what is running
#   ./test-servers.sh logs <webdav|smb>
#
# Needs Docker. In a container where the daemon is not already running,
# `sudo dockerd &` first (that is all the test harness needs too).

set -euo pipefail

WEBDAV_NAME=olo-test-webdav
SMB_NAME=olo-test-smb
WEBDAV_PORT=18080     # -> 80 in the container
SMB_PORT=11445        # -> 445 in the container
USER=olo
PASS=secret
SHARE=share

ensure_daemon() {
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not running. Start it with: sudo dockerd >/tmp/dockerd.log 2>&1 &" >&2
    exit 1
  fi
}

up() {
  ensure_daemon
  docker rm -f "$WEBDAV_NAME" "$SMB_NAME" >/dev/null 2>&1 || true

  docker run -d --name "$WEBDAV_NAME" -p "$WEBDAV_PORT:80" \
    -e USERNAME="$USER" -e PASSWORD="$PASS" bytemark/webdav >/dev/null

  docker run -d --name "$SMB_NAME" -p "$SMB_PORT:445" \
    -e USERID=0 -e GROUPID=0 dperson/samba -p \
    -u "$USER;$PASS" -s "$SHARE;/share;yes;no;no;$USER" -w WORKGROUP >/dev/null

  cat <<EOF
WebDAV  http://127.0.0.1:$WEBDAV_PORT/   user $USER / $PASS
SMB     //127.0.0.1:$SMB_PORT/$SHARE     user $USER / $PASS
Both are up. 'down' when finished.
EOF
}

down() {
  docker rm -f "$WEBDAV_NAME" "$SMB_NAME" >/dev/null 2>&1 || true
  echo "stopped"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) docker ps --filter "name=$WEBDAV_NAME" --filter "name=$SMB_NAME" ;;
  logs) docker logs "${2:-$WEBDAV_NAME}" ;;
  *) echo "usage: $0 {up|down|status|logs <webdav|smb>}" >&2; exit 2 ;;
esac
