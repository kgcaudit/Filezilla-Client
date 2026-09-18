#!/bin/sh
# Builds the Python environment and test certificate the integration tests
# need. Run once; the integration tests skip themselves if it has not been run.
set -eu
cd "$(dirname "$0")"

python3 -m venv venv
./venv/bin/pip install --quiet --upgrade pip
./venv/bin/pip install --quiet pyftpdlib pyopenssl

if [ ! -f cert.pem ]; then
    openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 3650 \
        -nodes -subj "/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
fi

echo "Test server ready. Run: ./gradlew :core-ftp:test"
