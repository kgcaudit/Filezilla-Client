#!/usr/bin/env python3
"""Renders the drawables the way Android will, so they can be looked at.

Reading the XML is not the same as seeing it. These are icons; the only test
that means anything is what they look like at the size they are shown, on the
backgrounds they are shown on -- and, for the launcher, through the mask the
system actually cuts it with.
"""
from __future__ import annotations

import io
import pathlib
import re
import sys

import cairosvg
from PIL import Image, ImageDraw

DRAWABLE = pathlib.Path("app/src/main/res/drawable")


def to_svg(xml: str) -> str:
    paths = re.findall(
        r'<path\s+android:fillColor="(#[0-9a-fA-F]{6})"\s+android:pathData="([^"]+)"',
        xml,
    )
    body = "".join(f'<path fill="{c}" d="{d}"/>' for c, d in paths)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{body}</svg>'


def render(name: str, px: int) -> Image.Image:
    svg = to_svg((DRAWABLE / f"{name}.xml").read_text())
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def main() -> None:
    names = sys.argv[1:] or [
        "ic_flat_server", "ic_flat_folder", "ic_flat_file",
        "ic_flat_transfers", "ic_flat_log", "ic_flat_download", "ic_flat_upload",
    ]
    # 24dp and 40dp at xxhdpi, which is what the tab bar and the row chips use.
    rows = [("24dp icon (72px)", 72), ("40dp chip (120px)", 120)]
    cell = 150
    sheet = Image.new("RGB", (len(names) * cell, sum(r[1] for r in rows) + 90), (244, 246, 249))
    d = ImageDraw.Draw(sheet)
    y = 8
    for label, px in rows:
        d.text((8, y), label, fill=(30, 30, 30))
        y += 18
        for i, n in enumerate(names):
            im = render(n, px)
            sheet.paste(im, (i * cell + (cell - px) // 2, y), im)
        y += px + 12
    for i, n in enumerate(names):
        d.text((i * cell + 6, y), n.replace("ic_flat_", ""), fill=(60, 60, 60))
    sheet.save("/tmp/icon-preview.png")
    print("wrote /tmp/icon-preview.png")


if __name__ == "__main__":
    main()
