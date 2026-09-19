#!/usr/bin/env python3
"""Renders the screens with the current theme values, light and dark.

The colours live in ui/theme/Theme.kt; they are read from there rather than
repeated here, so this cannot quietly disagree with what the app ships.
"""
from __future__ import annotations

import io
import pathlib
import re

import cairosvg
from PIL import Image, ImageDraw

THEME = pathlib.Path("app/src/main/kotlin/org/filezilla/android/ui/theme/Theme.kt")
DRAWABLE = pathlib.Path("app/src/main/res/drawable")


def block_colours(text: str, header: str) -> dict[str, str]:
    """The `name = Color(0xFF……)` pairs inside one declaration."""
    start = text.index(header)
    depth, i = 0, text.index("(", start)
    while True:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    body = text[start:i]
    found = dict(re.findall(r"(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", body))
    # Roles written as one of the named constants rather than a literal.
    constants = dict(re.findall(r"private val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", text))
    for role, ref in re.findall(r"(\w+)\s*=\s*(\w+)\s*,", body):
        if ref in constants:
            found[role] = constants[ref]
    return {k: "#" + v.upper() for k, v in found.items()}


def scheme(colours: str, status: str) -> dict[str, str]:
    """One theme: its colour roles and its status colours together."""
    text = THEME.read_text()
    return {**block_colours(text, colours), **block_colours(text, status)}


def rgb(h: str) -> tuple[int, int, int]:
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def art(name: str, px: int) -> Image.Image:
    xml = (DRAWABLE / f"{name}.xml").read_text()
    paths = re.findall(r'<path\s+android:fillColor="(#[0-9a-fA-F]{6})"\s+android:pathData="([^"]+)"', xml)
    body = "".join(f'<path fill="{c}" d="{d}"/>' for c, d in paths)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{body}</svg>'
    return Image.open(io.BytesIO(
        cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px)
    )).convert("RGBA")


def chip(icon: str, box: int, px: int, radius: int) -> Image.Image:
    out = Image.new("RGBA", (box, box), (0, 0, 0, 0))
    mask = Image.new("L", (box, box), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, box - 1, box - 1], radius=radius, fill=255)
    out.paste(Image.new("RGBA", (box, box), rgb("#E8EEF5") + (255,)), (0, 0), mask)
    a = art(icon, px)
    out.paste(a, ((box - px) // 2, (box - px) // 2), a)
    return out


def screen(c: dict[str, str], label: str, selecting: bool) -> Image.Image:
    W, H = 360, 440
    im = Image.new("RGB", (W, H), rgb(c["surface"]))
    d = ImageDraw.Draw(im)

    bar = c["primaryContainer"] if selecting else c["surface"]
    ink = c["onPrimaryContainer"] if selecting else c["onSurface"]
    d.rectangle([0, 0, W, 76], fill=rgb(bar))
    d.text((16, 32), "2개 선택됨" if selecting else "전송 목록", fill=rgb(ink))
    for x in (W - 104, W - 70, W - 36):
        d.rounded_rectangle([x, 30, x + 20, 46], radius=4, outline=rgb(ink), width=2)

    rows = [("One.Night.Only.mkv", "전송 중 · 5.5 MB/s", c["running"], 0.42),
            ("The.Christophers.srt", "완료", c["done"], 1.0),
            ("Spider-Man.smi", "일시정지됨", c["paused"], 0.26),
            ("Enfrentados.srt", "실패", c["failed"], 0.11)]
    y = 92
    for title, sub, accent, frac in rows:
        d.rounded_rectangle([12, y, W - 12, y + 70], radius=14, fill=rgb(c["background"]))
        ch = chip("ic_flat_file", 40, 24, 11)
        im.paste(ch, (24, y + 15), ch)
        d.text((76, y + 16), title, fill=rgb(c["onSurface"]))
        d.ellipse([76, y + 40, 85, y + 49], fill=rgb(accent))
        d.text((93, y + 39), sub, fill=rgb(c["onSurfaceVariant"]))
        d.rounded_rectangle([76, y + 58, W - 28, y + 62], radius=2, fill=rgb(c["progressTrack"]))
        d.rounded_rectangle([76, y + 58, 76 + int((W - 104) * frac), y + 62], radius=2, fill=rgb(accent))
        y += 80

    by = H - 72
    # Material fills the navigation bar with surfaceContainer, which this
    # theme does not set explicitly, so the approximation is noted rather
    # than presented as exact.
    d.rectangle([0, by, W, H], fill=rgb("#EAEEF3" if c["surface"] == "#F4F6F9" else "#1B2229"))
    tabs = [("ic_flat_server", "서버"), ("ic_flat_folder", "파일"),
            ("ic_flat_transfers", "전송"), ("ic_flat_log", "로그")]
    step = W // 4
    for i, (icon, lab) in enumerate(tabs):
        cx = i * step + step // 2
        if i == 2:
            d.rounded_rectangle([cx - 27, by + 10, cx + 27, by + 40], radius=15, fill=rgb("#E8EEF5"))
            d.text((cx - len(lab) * 6, by + 45), lab, fill=rgb(c["onSurfaceVariant"]))
        a = art(icon, 24)
        im.paste(a, (cx - 12, by + 13), a)

    d.rectangle([0, 0, W - 1, H - 1], outline=(190, 194, 200))
    d.text((8, H - 15), label, fill=(120, 124, 130))
    return im


def main() -> None:
    light = scheme("private val LightColors", "private val LightStatus")
    dark = scheme("private val DarkColors", "private val DarkStatus")
    shots = [screen(light, "light", False), screen(light, "light · 선택 모드", True),
             screen(dark, "dark", False), screen(dark, "dark · 선택 모드", True)]
    sheet = Image.new("RGB", (sum(s.width + 8 for s in shots) + 8, shots[0].height + 8), (205, 209, 215))
    x = 8
    for s in shots:
        sheet.paste(s, (x, 4))
        x += s.width + 8
    sheet.save("/tmp/theme-preview.png")
    print("wrote /tmp/theme-preview.png")


if __name__ == "__main__":
    main()
