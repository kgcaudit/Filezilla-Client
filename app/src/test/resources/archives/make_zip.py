"""Writes the two kinds of zip that exist in Korea.

The modern one names its entries in UTF-8 and sets bit 11 to say so. The
one Korean Windows has been making for twenty years names them in CP949
and sets nothing -- a reader is on its own. Python's zipfile re-encodes a
non-ASCII name as UTF-8 and sets the flag, which is precisely the file
not wanted here, so the bytes are written by hand.
"""
import struct, zlib, os, sys

def write_zip(path, entries, encoding, utf8_flag):
    local, central = b"", b""
    offset = 0
    for name, data, deflate in entries:
        raw = name.encode(encoding)
        if deflate:
            c = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
            body, method = c.compress(data) + c.flush(), 8
        else:
            body, method = data, 0
        crc = zlib.crc32(data) & 0xFFFFFFFF
        flag = 0x800 if utf8_flag else 0
        head = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flag, method, 0, 0,
                           crc, len(body), len(data), len(raw), 0) + raw
        local += head + body
        central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, flag, method, 0, 0,
                               crc, len(body), len(data), len(raw), 0, 0, 0, 0,
                               0x10 if name.endswith("/") else 0, offset) + raw
        offset += len(head) + len(body)
    end = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries),
                      len(central), len(local), 0)
    open(path, "wb").write(local + central + end)

if __name__ == "__main__":
    out = os.path.dirname(os.path.abspath(__file__))
    names = [
        ("한글파일.txt", "한글 내용입니다\n".encode("utf-8"), True),
        ("사진/", b"", False),
        ("사진/여름 휴가.jpg", b"\xff\xd8\xff" + b"x" * 200, True),
        ("보고서(최종).hwp", b"hwp body", False),
    ]
    write_zip(f"{out}/korean_cp949.zip", names, "cp949", utf8_flag=False)
    write_zip(f"{out}/korean_utf8.zip", names, "utf-8", utf8_flag=True)
    write_zip(f"{out}/plain.zip", [
        ("hello.txt", b"hello from zip\n", False),
        ("deflated.txt", b"repeat " * 500, True),
    ], "ascii", utf8_flag=False)
    for f in ("korean_cp949.zip", "korean_utf8.zip", "plain.zip"):
        raw = open(f"{out}/{f}", "rb").read()
        print(f"  {f}: {len(raw)} bytes, UTF-8 flag {'on' if struct.unpack('<H', raw[6:8])[0] & 0x800 else 'off'}")


def make_zip64():
    """A hand-built Zip64 archive: the tail a zip over 4 GB has, without
    the gigabytes. The ordinary end record carries the 0xFFFFFFFF
    placeholder for the central-directory offset, and the real offset is
    in the Zip64 end record that the locator points at. A reader that
    stops at the ordinary record reads the placeholder as an offset and
    calls the central directory out of bounds -- a big comic zip that
    opened onto nothing."""
    import struct, zlib
    name = b'page.txt'
    content = b'hello from a zip64 archive\n' * 3
    packer = zlib.compressobj(9, zlib.DEFLATED, -15)
    data = packer.compress(content) + packer.flush()
    crc = zlib.crc32(content) & 0xFFFFFFFF

    out = bytearray()
    lfh_off = len(out)
    out += struct.pack('<IHHHHHIIIHH', 0x04034B50, 20, 0, 8, 0, 0,
                       crc, len(data), len(content), len(name), 0)
    out += name + data

    cd_off = len(out)
    out += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014B50, 20, 20, 0, 8, 0, 0,
                       crc, len(data), len(content), len(name), 0, 0, 0, 0, 0, lfh_off)
    out += name
    cd_size = len(out) - cd_off

    z64 = len(out)
    out += struct.pack('<IQHHIIQQQQ', 0x06064B50, 44, 45, 45, 0, 0, 1, 1, cd_size, cd_off)
    out += struct.pack('<IIQI', 0x07064B50, 0, z64, 1)
    out += struct.pack('<IHHHHIIH', 0x06054B50, 0, 0, 0xFFFF, 0xFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0)

    with open('zip64.zip', 'wb') as handle:
        handle.write(out)
    print('zip64.zip  %d bytes' % len(out))


make_zip64()
