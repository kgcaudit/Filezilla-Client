#!/usr/bin/env python3
"""Turns the flat icon artwork into Android vector drawables.

The artwork arrives as 512px flat PNGs. It is traced rather than redrawn: the
shapes are solid colour with no gradients or strokes, so a trace reproduces
them exactly (verified against the originals) while giving one small file that
stays sharp at any size -- no density buckets, no 5 copies of every icon.

Colours are remapped on the way through. The artwork's blue is brighter and
lighter than this app's Ocean, and side by side they read as two different
palettes. Every fill is snapped to the nearest colour in PALETTE, which is
taken from the app's own theme, so the icons look like part of the app rather
than like something pasted into it.

Usage:  python3 tools/icons/build_icons.py <artwork-dir>
"""
from __future__ import annotations

import pathlib
import re
import sys

import vtracer

# Where the drawables land.
OUT = pathlib.Path("app/src/main/res/drawable")

# How the artwork's colours become this app's.
#
# Written out rather than matched by a distance function. The artwork uses a
# dozen exact colours, not a range, so the whole mapping fits here and can be
# read and argued with. Every distance metric tried got something wrong and
# quietly: by RGB the status lights' yellow-green lands nearer amber than
# green, so every light on the server went amber and it stopped saying "some
# of these are fine"; weighting hue instead let a near-white pink beat a grey
# on saturation, and the file's lines turned pink. A table cannot surprise us.
#
# The right-hand side is the app's own palette, from ui/theme/Theme.kt.
RECOLOUR = {
    # The artwork's blue is brighter and lighter than Ocean; side by side they
    # read as two palettes, which is the whole reason for this pass.
    "#5095FF": "#0B5FA5",  # primary
    "#DAEBFF": "#D5E7F9",  # primaryContainer
    "#CADEFF": "#D5E7F9",
    "#80B0F0": "#7FB0DE",
    "#EEF6FE": "#D5E7F9",  # the magnifier's lens

    # Greys carry most of the artwork. They go to the theme's neutrals.
    "#70706F": "#44505C",  # onSurfaceVariant
    "#707070": "#44505C",
    "#747B84": "#44505C",
    "#737A83": "#44505C",
    "#607080": "#44505C",
    "#E6E6E6": "#E2E8EF",  # surfaceVariant
    "#E7E7E7": "#E2E8EF",
    "#E6EBEE": "#E2E8EF",
    "#C0C0D0": "#C6CFD8",  # outlineVariant
    "#F0F0F0": "#F1F4F8",

    # Status colours, in the app's families but lightened: these are fills,
    # and the theme's versions are chosen to be read as text.
    "#83C901": "#2E9E68",  # the done green
    "#FDA16E": "#D98A2B",  # the paused amber
    "#F0C030": "#D98A2B",
    "#F57C75": "#C9564F",  # the failed red
    "#F07070": "#C9564F",
    "#FCDBDC": "#F4DDDB",
    "#FBD8DA": "#F4DDDB",
    "#FBDADC": "#F4DDDB",
    "#70D0B0": "#2E9E68",
    "#FFC738": "#D98A2B",
    "#FFFEFD": "#F1F4F8",
    "#FFFFFF": "#FFFFFF",
}

# Which artwork file becomes which drawable. Only what the app actually shows:
# the icons that say what a thing IS. Controls stay as Material glyphs, which
# tint with their state -- a fixed multi-colour icon cannot.
MAPPING = {
    "088.서버.png": "ic_flat_server",
    "012.폴더,저장소.png": "ic_flat_folder",
    "001.문서,글.png": "ic_flat_file",
    "025.검색.png": "ic_flat_search",
    "117.주의,강조.png": "ic_flat_warning",
}

TRACE = dict(colormode="color", mode="polygon", filter_speckle=8,
             color_precision=6, path_precision=3)


def recolour(hex_colour: str) -> str:
    """The app's colour for one of the artwork's.

    Unknown colours stop the build rather than being approximated. A colour
    this table has not seen means the artwork changed, and guessing at it is
    how a palette drifts: the icons would still build, and nobody would see
    the wrong shade until it shipped.
    """
    known = RECOLOUR.get(hex_colour.upper())
    if known is None:
        raise SystemExit(
            f"unmapped artwork colour {hex_colour}; add it to RECOLOUR in {__file__}"
        )
    return known


PATH = re.compile(
    r'<path\s+d="(?P<d>[^"]+)"\s+fill="(?P<fill>#[0-9a-fA-F]{6})"'
    r'(?:\s+transform="translate\((?P<tx>-?[\d.]+),(?P<ty>-?[\d.]+)\)")?\s*/>'
)


def bake(data: str, tx: float, ty: float) -> str:
    """Folds a translate into the path's own coordinates.

    The tracer puts each path at the origin and moves it with a transform.
    A vector drawable's path has no transform -- only a group does -- so the
    offset is applied to the numbers instead, which keeps the output one flat
    list of paths. Safe because the tracer emits nothing but absolute M, L and
    Z; the caller refuses anything else, because a curve or a relative command
    would need the pen position tracked rather than each pair shifted.
    """
    tokens = re.findall(r"[A-Za-z]|-?\d*\.?\d+", data)
    out: list[str] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.isalpha():
            out.append(token.upper())
            index += 1
            continue
        x = float(token) + tx
        y = float(tokens[index + 1]) + ty
        out.append(f"{x:g},{y:g}")
        index += 2
    # "M12,4 L20,4" rather than "M 12,4 L 20,4": the same path, fewer bytes.
    return re.sub(r"([A-Z]) ", r"\1", " ".join(out)).strip()


def to_vector_drawable(svg: str, name: str) -> str:
    if re.search(r'\sd="[^"]*[CcSsQqTtAaHhVvmlz]', svg):
        raise SystemExit(f"{name}: the trace used a command bake() cannot offset")
    paths = [
        (m.group("fill"), bake(m.group("d"), float(m.group("tx") or 0), float(m.group("ty") or 0)))
        for m in PATH.finditer(svg)
    ]
    if not paths:
        raise SystemExit(f"{name}: the trace produced no paths")

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by tools/icons/build_icons.py. Do not edit by hand. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="512"',
        '    android:viewportHeight="512">',
    ]
    for fill, data in paths:
        lines.append("    <path")
        lines.append(f'        android:fillColor="{recolour(fill)}"')
        lines.append(f'        android:pathData="{data}" />')
    lines.append("</vector>")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    artwork = pathlib.Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)

    for source, name in MAPPING.items():
        src = artwork / source
        if not src.is_file():
            raise SystemExit(f"missing artwork: {src}")
        svg = pathlib.Path(f"/tmp/{name}.svg")
        vtracer.convert_image_to_svg_py(str(src), str(svg), **TRACE)
        xml = to_vector_drawable(svg.read_text(), name)
        (OUT / f"{name}.xml").write_text(xml)
        print(f"{name}.xml  {len(xml):>6} bytes  {xml.count('<path'):>2} paths")


if __name__ == "__main__":
    main()
