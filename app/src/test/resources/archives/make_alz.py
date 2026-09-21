"""Writes ALZ files from the format as unalz describes it.

Only for testing. If the real unalz unpacks what this produces, then the
format was read correctly -- which is the only check available until a
file made by ALZip itself turns up.
"""
import struct, zlib, os, sys

SIG_FILE   = 0x015A4C41
SIG_LOCAL  = 0x015A4C42
SIG_END    = 0x025A4C43

STORE, BZIP2, DEFLATE = 0, 1, 2

CRC_TABLE = []
for n in range(256):
    c = n
    for _ in range(8):
        c = (c >> 1) ^ 0xEDB88320 if c & 1 else c >> 1
    CRC_TABLE.append(c)

class ZipCrypto:
    def __init__(self, password: bytes):
        self.keys = [305419896, 591751049, 878082192]
        for b in password:
            self.update(b)

    def update(self, b):
        k = self.keys
        k[0] = (CRC_TABLE[(k[0] ^ b) & 0xFF] ^ (k[0] >> 8)) & 0xFFFFFFFF
        k[1] = (k[1] + (k[0] & 0xFF)) & 0xFFFFFFFF
        k[1] = (k[1] * 134775813 + 1) & 0xFFFFFFFF
        k[2] = (CRC_TABLE[(k[2] ^ (k[1] >> 24)) & 0xFF] ^ (k[2] >> 8)) & 0xFFFFFFFF

    def stream_byte(self):
        t = (self.keys[2] | 2) & 0xFFFF
        return ((t * (t ^ 1)) >> 8) & 0xFF

    def encrypt(self, data: bytes) -> bytes:
        out = bytearray()
        for plain in data:
            out.append(plain ^ self.stream_byte())
            self.update(plain)
        return bytes(out)


def dos_time(year=2024, month=3, day=15, hour=13, minute=45, second=20):
    return ((year - 1980) << 25) | (month << 21) | (day << 16) | \
           (hour << 11) | (minute << 5) | (second // 2)


def entry(name, data, method=DEFLATE, is_dir=False, password=None):
    raw = name.encode("cp949")
    attribute = 0x10 if is_dir else 0x20

    if is_dir:
        # A directory carries no sizes at all, which is what a zero size
        # width means.
        head = struct.pack("<IHBIBB", SIG_LOCAL, len(raw), attribute, dos_time(), 0, 0)
        return head + raw

    if method == DEFLATE:
        body = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS).compress(data)
        body += zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS).flush()
        c = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
        body = c.compress(data) + c.flush()
    else:
        body = data

    crc = zlib.crc32(data) & 0xFFFFFFFF
    descriptor = 0x40  # four-byte size fields
    crypto_header = b""
    if password:
        descriptor |= 0x01
        crypto = ZipCrypto(password.encode("cp949"))
        # Eleven filler bytes, then the check byte: the CRC's top byte.
        header = bytes(range(11)) + bytes([(crc >> 24) & 0xFF])
        crypto_header = crypto.encrypt(header)
        body = crypto.encrypt(body)

    head = struct.pack("<IHBIBB", SIG_LOCAL, len(raw), attribute, dos_time(), descriptor, 0)
    head += struct.pack("<BBI", method, 0, crc)
    head += struct.pack("<II", len(body) + len(crypto_header) - len(crypto_header), len(data))
    return head + raw + crypto_header + body


def write(path, entries):
    out = struct.pack("<II", SIG_FILE, 0)
    for e in entries:
        out += e
    out += struct.pack("<I", SIG_END)
    open(path, "wb").write(out)


if __name__ == "__main__":
    os.makedirs("/tmp/claude-0/alz/out", exist_ok=True)
    d = "/tmp/claude-0/alz"

    write(f"{d}/plain.alz", [
        entry("hello.txt", b"hello from alz\n", STORE),
        entry("deflated.txt", b"repeat " * 500, DEFLATE),
    ])
    write(f"{d}/korean.alz", [
        entry("한글파일.txt", "한글 내용입니다\n".encode("utf-8"), DEFLATE),
        entry("사진", b"", is_dir=True),
        entry("사진/여름 휴가.jpg", b"\xff\xd8\xff" + b"x" * 200, DEFLATE),
        entry("보고서(최종).hwp", b"hwp body", STORE),
    ])
    write(f"{d}/secret.alz", [
        entry("secret.txt", b"password protected content\n", DEFLATE, password="bimil"),
    ])
    for f in ("plain.alz", "korean.alz", "secret.alz"):
        print(f"{f}: {os.path.getsize(d + '/' + f)} bytes")
