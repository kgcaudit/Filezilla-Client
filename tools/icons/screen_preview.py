#!/usr/bin/env python3
"""Composes the icons into the screens that show them, light and dark.

An icon looked at on its own tells you nothing about whether it works. What
matters is the tab bar it sits in and the row it sits beside, in both themes --
particularly the dark one, where the pale chip these icons need is the whole
design decision and has to be seen to be judged.
"""
from __future__ import annotations

import io
import pathlib
import re

import cairosvg
from PIL import Image, ImageDraw

DRAWABLE = pathlib.Path("app/src/main/res/drawable")
CHIP = (0xE8, 0xEE, 0xF5)

THEMES = {
    "light": dict(bg=(0xF4, 0xF6, 0xF9), card=(0xFF, 0xFF, 0xFF),
                  text=(0x16, 0x1C, 0x22), dim=(0x44, 0x50, 0x5C),
                  bar=(0xE7, 0xEC, 0xF2), pill=CHIP),
    "dark": dict(bg=(0x11, 0x16, 0x1B), card=(0x1A, 0x21, 0x28),
                 text=(0xE2, 0xE6, 0xEA), dim=(0xBF, 0xC8, 0xD1),
                 bar=(0x19, 0x20, 0x27), pill=CHIP),
}


def render(name: str, px: int) -> Image.Image:
    xml = (DRAWABLE / f"{name}.xml").read_text()
    paths = re.findall(
        r'<path\s+android:fillColor="(#[0-9a-fA-F]{6})"\s+android:pathData="([^"]+)"', xml
    )
    body = "".join(f'<path fill="{c}" d="{d}"/>' for c, d in paths)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{body}</svg>'
    return Image.open(io.BytesIO(
        cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px)
    )).convert("RGBA")


def chip(icon: str, box: int, icon_px: int, radius: int) -> Image.Image:
    out = Image.new("RGBA", (box, box), (0, 0, 0, 0))
    mask = Image.new("L", (box, box), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, box - 1, box - 1], radius=radius, fill=255)
    plate = Image.new("RGBA", (box, box), CHIP + (255,))
    out.paste(plate, (0, 0), mask)
    art = render(icon, icon_px)
    out.paste(art, ((box - icon_px) // 2, (box - icon_px) // 2), art)
    return out


def screen(theme: str) -> Image.Image:
    t = THEMES[theme]
    W, H = 460, 400
    im = Image.new("RGB", (W, H), t["bg"])
    d = ImageDraw.Draw(im)
    d.text((16, 12), f"{theme}  —  sites, then files", fill=t["text"])

    rows = [("ic_flat_server", "skynhanul.ipdisk.co.kr", "sangsu"),
            ("ic_flat_folder", "Vision", "폴더"),
            ("ic_flat_file", "One.Night.Only.2026.mkv", "1.9 GB")]
    y = 38
    for icon, title, sub in rows:
        d.rounded_rectangle([12, y, W - 12, y + 62], radius=14, fill=t["card"])
        c = chip(icon, 44, 26, 12)
        im.paste(c, (26, y + 9), c)
        d.text((84, y + 14), title, fill=t["text"])
        d.text((84, y + 34), sub, fill=t["dim"])
        y += 74

    # The tab bar.
    bar_y = H - 76
    d.rectangle([0, bar_y, W, H], fill=t["bar"])
    tabs = [("ic_flat_server", "서버"), ("ic_flat_folder", "파일"),
            ("ic_flat_transfers", "전송"), ("ic_flat_log", "로그")]
    step = W // len(tabs)
    for i, (icon, label) in enumerate(tabs):
        cx = i * step + step // 2
        if i == 2:  # the selected one, with its indicator
            d.rounded_rectangle([cx - 32, bar_y + 8, cx + 32, bar_y + 40], radius=16, fill=t["pill"])
        art = render(icon, 26)
        im.paste(art, (cx - 13, bar_y + 11), art)
        d.text((cx - 12, bar_y + 46), label, fill=t["dim"])
    return im


def main() -> None:
    light, dark = screen("light"), screen("dark")
    sheet = Image.new("RGB", (light.width + dark.width + 12, light.height), (210, 214, 220))
    sheet.paste(light, (0, 0))
    sheet.paste(dark, (light.width + 12, 0))
    sheet.save("/tmp/screen-preview.png")
    print("wrote /tmp/screen-preview.png")


if __name__ == "__main__":
    main()
