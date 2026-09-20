#!/usr/bin/env python3
"""Renders the launcher icon the way each launcher will cut it.

An adaptive icon is two layers on a canvas a third larger than what is shown,
and the system cuts it to whatever shape the launcher uses. Looking at the
foreground on its own says nothing about whether the result survives a circle.
"""
from __future__ import annotations

import io
import pathlib
import re

import cairosvg
from PIL import Image, ImageDraw

DRAWABLE = pathlib.Path("app/src/main/res/drawable")


def as_rgb(colour: str) -> str:
    """#AARRGGBB or #RRGGBB, as the #RRGGBB an SVG wants."""
    return "#" + colour[3:] if len(colour) == 9 else colour


def as_alpha(colour: str) -> str:
    return f"{int(colour[1:3], 16) / 255:.3f}" if len(colour) == 9 else "1"
# The system shows the middle 72 of the 108dp canvas; the rest is cropped.
CANVAS, SHOWN = 432, 288


def layer(name: str) -> Image.Image:
    """Re-draws one layer of the adaptive icon as SVG, gradients and all.

    The background is a gradient now, which a vector drawable can only write
    as a nested attribute -- so a regex for fillColor alone quietly rendered
    nothing and the preview showed a transparent ground.
    """
    xml = (DRAWABLE / f"{name}.xml").read_text()
    body, defs = "", ""
    for i, block in enumerate(re.findall(r"<path\b.*?(?:/>|</path>)", xml, re.S)):
        data = re.search(r'android:pathData="([^"]+)"', block).group(1)
        flat = re.search(r'android:fillColor="(#[0-9a-fA-F]{6,8})"', block)
        if flat:
            body += f'<path fill="{as_rgb(flat.group(1))}" fill-opacity="{as_alpha(flat.group(1))}" d="{data}"/>'
            continue
        start = re.search(r'android:startColor="(#[0-9a-fA-F]{6,8})"', block).group(1)
        end = re.search(r'android:endColor="(#[0-9a-fA-F]{6,8})"', block).group(1)
        defs += (
            f'<linearGradient id="g{i}" x1="0" y1="0" x2="512" y2="512" '
            f'gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{as_rgb(start)}"/>'
            f'<stop offset="1" stop-color="{as_rgb(end)}"/></linearGradient>'
        )
        body += f'<path fill="url(#g{i})" d="{data}"/>'
    body = f"<defs>{defs}</defs>" + body
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{body}</svg>'
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=CANVAS, output_height=CANVAS)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def masked(shape: str) -> Image.Image:
    full = Image.alpha_composite(layer("ic_launcher_background"), layer("ic_launcher_foreground"))
    inset = (CANVAS - SHOWN) // 2
    shown = full.crop((inset, inset, inset + SHOWN, inset + SHOWN))
    mask = Image.new("L", (SHOWN, SHOWN), 0)
    d = ImageDraw.Draw(mask)
    if shape == "circle":
        d.ellipse([0, 0, SHOWN - 1, SHOWN - 1], fill=255)
    elif shape == "squircle":
        d.rounded_rectangle([0, 0, SHOWN - 1, SHOWN - 1], radius=SHOWN // 3, fill=255)
    else:
        d.rounded_rectangle([0, 0, SHOWN - 1, SHOWN - 1], radius=SHOWN // 8, fill=255)
    out = Image.new("RGBA", (SHOWN, SHOWN), (0, 0, 0, 0))
    out.paste(shown, (0, 0), mask)
    return out


def main() -> None:
    shapes = ["circle", "squircle", "rounded"]
    sizes = [144, 96, 48]  # as a launcher, in a list, and in the status bar
    pad = 20
    width = sum(sizes) * len(shapes) + pad * (len(sizes) * len(shapes) + 1)
    sheet = Image.new("RGB", (width, 144 + 46), (238, 240, 244))
    d = ImageDraw.Draw(sheet)
    x = pad
    for shape in shapes:
        for size in sizes:
            sheet.paste(masked(shape).resize((size, size), Image.LANCZOS), (x, 30 + (144 - size)), 
                        masked(shape).resize((size, size), Image.LANCZOS))
            d.text((x, 12), f"{shape[:4]} {size}", fill=(40, 40, 40))
            x += size + pad
    sheet.save("/tmp/launcher-preview.png")
    print("wrote /tmp/launcher-preview.png")


if __name__ == "__main__":
    main()
