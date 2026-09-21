#!/usr/bin/env python3
"""Builds the .egg fixtures.

Written from unegg's own structures (lib/alegg/eggstruct.h, eggoper.cpp)
rather than by re-packing an ALZip archive, so that a fixture can hold
something ALZip would not easily produce on demand -- a file split into
two blocks, a name that is only correct as UTF-8, a method this app does
not read.  Real .egg files from ALZip are the other half of the check and
sit beside these.
"""
import struct
import zlib


EGG = 0x41474745
SPLIT = 0x24F5A262
SOLID = 0x24E5A060
FILE = 0x0A8590E3
FILENAME = 0x0A8591AC
COMMENT = 0x04C63672
WINDOWS_FILE_INFO = 0x2C86950B
ENCRYPT = 0x08D1470F
BLOCK = 0x02B50C13
END = 0x08E28222

STORE, DEFLATE, BZIP2, AZO, LZMA = 0, 1, 2, 3, 4
ZIPCRYPTO, AES128, AES256 = 0, 1, 2
DIRECTORY = 0x80

# 2020-06-01 12:34:56 UTC as a Windows FILETIME.


def filetime(epoch_seconds):
    return (epoch_seconds + 11644473600) * 10_000_000


def sig(value):
    return struct.pack('<I', value)


def extra(payload):
    """A chunk body that carries its own length: flag 0, two-byte size."""
    return struct.pack('<BH', 0, len(payload)) + payload


class Crypto:
    """PKZIP's traditional cipher, encrypting."""

    def __init__(self, password):
        self.keys = [0x12345678, 0x23456789, 0x34567890]
        for byte in password.encode('cp949'):
            self.update(byte)

    def update(self, byte):
        self.keys[0] = zlib.crc32(bytes([byte]), self.keys[0] ^ 0xFFFFFFFF) ^ 0xFFFFFFFF
        self.keys[1] = (self.keys[1] + (self.keys[0] & 0xFF)) & 0xFFFFFFFF
        self.keys[1] = (self.keys[1] * 134775813 + 1) & 0xFFFFFFFF
        top = (self.keys[1] >> 24) & 0xFF
        self.keys[2] = zlib.crc32(bytes([top]), self.keys[2] ^ 0xFFFFFFFF) ^ 0xFFFFFFFF

    def byte(self):
        temp = (self.keys[2] | 2) & 0xFFFF
        return ((temp * (temp ^ 1)) >> 8) & 0xFF

    def encrypt(self, data):
        out = bytearray()
        for plain in data:
            out.append(plain ^ self.byte())
            self.update(plain)
        return bytes(out)


def block(method, content):
    if method == DEFLATE:
        deflater = zlib.compressobj(9, zlib.DEFLATED, -15)
        packed = deflater.compress(content) + deflater.flush()
    elif method == STORE:
        packed = content
    else:
        packed = content  # a method this app does not read; the bytes do not matter
    return packed


POSIX_FILE_INFO = 0x1EE922E5

CODE_PAGE = 0x08      # bit 4 of a filename's flag byte
RELATIVE_PATH = 0x10  # bit 5


def name_header(name, locale=None, parent=None):
    """A filename header.

    `locale` set writes the name in a code page instead of UTF-8, with
    the locale number in front of it; `parent` set writes it relative to
    another entry's id.  unegg refuses both -- its extra-field reader
    throws on any flag byte other than 0 or 1 -- so these are exactly the
    fixtures that tell the specification and that reader apart.
    """
    flags = 0
    body = b''
    if locale is not None:
        flags |= CODE_PAGE
        body += struct.pack('<H', locale)
    if parent is not None:
        flags |= RELATIVE_PATH
        body += struct.pack('<I', parent)
    body += name.encode('cp949' if locale is not None else 'utf-8')
    return sig(FILENAME) + struct.pack('<BH', flags, len(body)) + body


def entry(index, name, content=b'', method=DEFLATE, directory=False,
          password=None, blocks=None, modified=1590997000, locale=None,
          parent=None, posix=None, no_blocks=False):
    """One file chunk. `blocks` splits the content into several blocks."""
    out = sig(FILE) + struct.pack('<IQ', index, 0 if directory else len(content))
    out += name_header(name, locale=locale, parent=parent)
    attributes = DIRECTORY if directory else 0
    if posix is not None:
        out += sig(POSIX_FILE_INFO) + extra(
            struct.pack('<IIIQ', posix, 0, 0, modified))
    else:
        out += sig(WINDOWS_FILE_INFO) + extra(struct.pack('<QB', filetime(modified), attributes))

    crypto = None
    if password is not None:
        crc = zlib.crc32(content) & 0xFFFFFFFF
        crypto = Crypto(password)
        verify = bytes(range(11)) + bytes([(crc >> 24) & 0xFF])
        body = struct.pack('<B', ZIPCRYPTO) + crypto.encrypt(verify) + struct.pack('<I', crc)
        out += sig(ENCRYPT) + extra(body)

    out += sig(END)

    if not directory and not no_blocks:
        pieces = blocks if blocks else [content]
        for piece in pieces:
            packed = block(method, piece)
            if crypto is not None:
                packed = crypto.encrypt(packed)
            out += sig(BLOCK) + struct.pack('<BBIII', method, method, len(piece),
                                            len(packed), zlib.crc32(piece) & 0xFFFFFFFF)
            out += sig(END) + packed
    return out


def egg(*files, solid=False):
    out = sig(EGG) + struct.pack('<HII', 0x0100, 0x20150101, 0)
    if solid:
        out += sig(SOLID) + extra(b'')
    out += sig(END)
    for one in files:
        out += one
    out += sig(END)
    return out


def write(name, data):
    with open(name, 'wb') as handle:
        handle.write(data)
    print('%s  %d bytes' % (name, len(data)))


HELLO = b'hello from an egg\n' * 40
NOTES = b'the quick brown fox jumps over the lazy dog\n' * 30

write('plain.egg', egg(
    entry(0, 'hello.txt', HELLO, DEFLATE),
    entry(1, 'stored.txt', b'not compressed at all\n', STORE),
))

write('korean.egg', egg(
    entry(0, '한글이름.txt', '내용입니다\n'.encode('utf-8') * 20, DEFLATE),
))

write('secret.egg', egg(
    entry(0, 'secret.txt', NOTES, DEFLATE, password='alzip'),
))

write('folder.egg', egg(
    entry(0, 'papers', directory=True),
    entry(1, 'papers/inside.txt', HELLO, DEFLATE),
))

# One file whose bytes arrive as two separately compressed blocks --
# the thing a zip or an alz cannot do, and the reason open() joins.
write('multiblock.egg', egg(
    entry(0, 'long.txt', HELLO + NOTES, DEFLATE, blocks=[HELLO, NOTES]),
))

write('azo.egg', egg(
    entry(0, 'squeezed.txt', HELLO, AZO),
    entry(1, 'readable.txt', NOTES, DEFLATE),
))

# The name written in CP949 with the locale field saying so, which is
# what the specification allows and unegg gives up on.
write('korean_cp949.egg', egg(
    entry(0, '\ud55c\uae00\uc774\ub984.txt', '\ub0b4\uc6a9\uc785\ub2c8\ub2e4\n'.encode('utf-8') * 20,
          DEFLATE, locale=949),
))

# A name stored relative to another entry, by that entry's id.
write('relative.egg', egg(
    entry(7, 'papers', directory=True),
    entry(8, 'inside.txt', HELLO, DEFLATE, parent=7),
))

# Posix file information instead of Windows: the directory is in the
# mode bits, and the time is seconds rather than FILETIME ticks.
write('posix.egg', egg(
    entry(0, 'scripts', posix=0o040755, directory=True, no_blocks=True),
    entry(1, 'scripts/run.sh', NOTES, DEFLATE, posix=0o100644),
))

# Solid: one block holds both files' bytes end to end, and only the last
# file header is followed by it.
write('solid.egg', egg(
    entry(0, 'first.txt', b'first file\n', no_blocks=True),
    entry(1, 'second.txt', b'second file\n', STORE, blocks=[b'first file\nsecond file\n']),
    solid=True,
))

# A chunk this reader has never heard of, sitting where it is allowed to
# sit. The specification asks readers to step over it; a reader that
# stops here would fail on every archive a later ALZip writes.
UNKNOWN = 0x11223344
write('unknown_chunk.egg',
      sig(EGG) + struct.pack('<HII', 0x0100, 0x20150101, 0)
      + sig(UNKNOWN) + extra(b'from a later version')
      + sig(END)
      + entry(0, 'hello.txt', HELLO, DEFLATE)
      + sig(END))


def hexed(text):
    return bytes(int(token, 16) for token in text.split())


# Section 3, "General File Archive", exactly as the published
# specification prints it.  Assembled field by field so every line here
# can be read straight off the page.  This one owes nothing to the rest
# of this file: if the writer above and the reader in the app share a
# misunderstanding, this is the fixture that does not.
write('spec_example.egg', b''.join([
    hexed('45 47 47 41'), hexed('00 01'), hexed('01 00 00 00'), hexed('00 00 00 00'),
    hexed('22 82 E2 08'),
    hexed('E3 90 85 0A'), hexed('00 00 00 00'), hexed('05 00 00 00 00 00 00 00'),
    hexed('AC 91 85 0A'), hexed('00'), hexed('09 00'), b'hello.txt',
    hexed('0B 95 86 2C'), hexed('00'), hexed('09 00'),
    hexed('23 C9 A3 4F 63 FB C7 01'), hexed('00'),
    hexed('22 82 E2 08'),
    hexed('13 0C B5 02'), hexed('00'), hexed('00'),
    hexed('05 00 00 00'), hexed('05 00 00 00'), hexed('86 A6 10 36'),
    hexed('22 82 E2 08'),
    b'hello',
    hexed('22 82 E2 08'),
]))
