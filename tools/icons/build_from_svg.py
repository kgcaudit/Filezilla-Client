#!/usr/bin/env python3
"""Turns the icon pack's SVG sources into Android vector drawables.

This replaces the tracing the drawables were built by. The artwork first
arrived as 512px PNGs, so it had to be traced; the SVG sources are the same
drawings with their real geometry, at the 24dp grid they were drawn on. A
trace of a raster is an approximation that happens to be a very good one --
the shapes are flat colour, so it reproduced them closely -- but it is still
an approximation, and it produced files ten times the size for the privilege.

Nothing is redrawn here. An Android vector's pathData takes SVG path syntax
as it stands, so every path crosses over verbatim; the only things this does
are recolour, flatten what Android would need extra machinery for, and drop
clips that clip nothing.

Usage:  python3 tools/icons/build_from_svg.py
"""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

SVG = pathlib.Path("tools/icons/svg")
OUT = pathlib.Path("app/src/main/res/drawable")
NS = "{http://www.w3.org/2000/svg}"

# How the pack's colours become this app's.
#
# Written out rather than computed, for the reason the traced build gave and
# still holds: every distance metric tried got something wrong quietly. By RGB
# the server's yellow-green lands nearer amber than green, and every light on
# it went amber -- so it stopped saying "some of these are fine".
#
# The right-hand side is the app's own palette, from ui/theme/Theme.kt. It was
# a blue one; the brand is Claude's clay now, so the pack's blues come out warm
# and the greys that carry most of these drawings warm with them -- a cool grey
# under a clay accent reads as a mistake rather than as a choice.
RECOLOUR = {
    # The pack's blue is brighter and lighter than Ocean; side by side they
    # read as two palettes, which is the whole reason for this pass.
    "#5096FF": "#C5613F",  # primary
    "#6BA6FF": "#C5613F",
    "#5585CD": "#A34E31",
    "#88BFFF": "#E0A088",
    # Deeper than primaryContainer, which is where this started. The pack
    # draws a folder as a clay tab over a pale front panel, and the panel is
    # the bigger of the two shapes -- at container lightness on a white chip
    # it went to a ghost, so the folder read as a stripe with nothing under
    # it. This still sits well clear of the clay above it.
    "#CBDFFF": "#F0C9B6",
    "#DBECFF": "#F0C9B6",
    "#B8D3FB": "#F0C9B6",
    # The phone's screen, which the pack draws as a lilac-to-pink wash. Sent
    # to this app's own blues: a screen is the one part of that icon anybody
    # reads, and in two colours from nowhere else in the app it read as a
    # sticker rather than as part of the set.
    "#C6BFF7": "#E0A088",
    "#FEDFEE": "#F0C9B6",
    # The pale greys the pack uses for paper and for the body of an object.
    # Left near where the pack put them, which is a shade off white, because
    # the artwork is drawn for a white background -- see FlatIconChip, which
    # is now white for this reason. The traced build sent these to
    # surfaceVariant instead, a shade off the chip of the day, and the page of
    # the file icon disappeared into it: every file in every listing was drawn
    # as three floating bars that read as a list icon.
    "#EFF7FF": "#FBF2EC",
    "#E7ECEF": "#F0EAE1",
    "#F0F0F0": "#F3EFE9",
    "#E8E8E8": "#EAE3D9",
    "#E2E2E2": "#EAE3D9",
    "#E6EAEE": "#EAE3D9",
    "#E1E6EA": "#EAE3D9",
    "#EFE8E1": "#EAE3D9",
    "white": "#FFFFFF",
    "#FFFFFF": "#FFFFFF",
    "#FFFEFD": "#FFFFFF",

    # Greys carry most of the pack. They go to the theme's neutrals.
    "#737A83": "#574D45",  # onSurfaceVariant
    "#70706F": "#574D45",
    "#707070": "#574D45",
    "#747B84": "#574D45",
    "#607080": "#574D45",
    "#6B758F": "#574D45",
    "#808080": "#574D45",
    "#999999": "#8B7F74",  # outline
    "#333333": "#1D1A16",  # onSurface
    "#A0A6AB": "#A99E92",
    "#C7CED5": "#D6CCC1",  # outlineVariant
    "#C0C0D0": "#D6CCC1",
    "#CCCCCC": "#D6CCC1",
    "#C7C7C7": "#D6CCC1",
    "#9C9188": "#8E8377",

    # Status colours, in the app's families but lightened: these are fills,
    # and the theme's versions are chosen to be read as text.
    "#84CA00": "#2E9E68",  # the done green
    "#83C901": "#2E9E68",
    "#70D0B0": "#2E9E68",
    "#7AD7B5": "#2E9E68",
    "#91E2C5": "#8FD9BC",
    "#FFC738": "#9C7A0C",  # the paused amber
    "#F0C030": "#9C7A0C",
    "#FDA16E": "#9C7A0C",
    "#FFA36F": "#9C7A0C",
    "#FFBD81": "#E0C98C",
    "#FFE457": "#DCC155",
    "#FFF8B8": "#F0E6BC",
    "#FBF3DF": "#F0E6BC",
    "#F57C75": "#A32017",  # the failed red
    "#F07070": "#A32017",
    "#F4868F": "#A32017",
    "#FCDBDC": "#F4DDDB",
    "#FCDBDD": "#F4DDDB",
    "#FBD8DA": "#F4DDDB",
    "#FBDADC": "#F4DDDB",

    # The few the pack uses for things this app has no use for, kept so a
    # file that happens to contain one still builds.
    "#48AFBA": "#3E8E98",
    "#B3EAED": "#BFDDE0",
    "#9889F8": "#7A6FD0",
    "#E3DFFE": "#DEDAF6",
    "#D2D7FC": "#DEDAF6",
    "#D2C5B8": "#C9BFB4",
}

# Which source becomes which drawable.
#
# The pack is a set of things rather than of controls, and that is the line:
# an icon here says what something IS -- a file, a server, the phone itself --
# or names an action a whole bar is given over to. Small controls that have to
# tint with their state stay as Material glyphs, because multi-coloured
# artwork cannot tint.
MAPPING = {
    "012.폴더,저장소": "ic_flat_folder",
    "088.서버": "ic_flat_server",
    "025.검색": "ic_flat_search",
    "117.주의,강조": "ic_flat_warning",
    # The sites list, where whether a login travels in the clear is the one
    # thing about a server worth saying before you tap it.
    "069.인증,보호": "ic_flat_secure",
}

# Deliberately not here: copy, paste, delete, rename.
#
# The bar across the bottom of a selection was the obvious next place to use
# the pack, and the pack cannot furnish it. There is no scissors in it, so
# cut would stay a Material glyph while everything beside it became artwork --
# and a bar of five icons in one language and one in another looks more
# careless than a bar of six that match. The line holds: the pack says what
# things ARE, Material draws the controls.

# The same sources again, as white glyphs for a filled tile.
#
# A row's icon was a small drawing on a pale chip, and at 24dp inside a 40dp
# chip the drawing is a third of the tile: legible if you look, not if you
# scan. The tile is the icon now -- a solid colour with the glyph cut out of
# it in white -- which is what every file manager does and what the user asked
# for.
#
# The pack's own drawings are the source of the shapes. Colour cannot survive
# the trip, so each fill is read as figure or ground by its lightness: the
# dark strokes a designer drew as the subject become solid white, the pale
# fills they drew as the body become white at reduced alpha. That is a rule
# about how these icons are built rather than a judgement about each one.
TILES = {
    "012.폴더,저장소": "ic_tile_folder",
    "001.문서,글": "ic_tile_document",
    "045.상자": "ic_tile_archive",
    "100.카메라,사진촬영": "ic_tile_image",
    "099.동영상": "ic_tile_video",
    "039.사운드": "ic_tile_audio",
    # Knockouts: see INVERTED.
    "078.개발,코딩": "ic_tile_code",
    "072.챗봇": "ic_tile_app",
    "088.서버": "ic_tile_server",
    "079.신형 스마트폰": "ic_tile_phone",
    "095.메모리카드": "ic_tile_sdcard",
    "070.잠금,숨김": "ic_tile_locked",
    # For the empty screen a filter leaves behind.
    "025.검색": "ic_tile_search",
}

# The drawings whose subject is cut *out* of a dark shape rather than drawn
# on a pale one -- a dark box with a white "</>" through it, a dark robot head
# with white eyes, a dark memory card with pale contacts. The lightness rule
# reads those exactly backwards and hands back a solid white blob, so for
# these three it is turned round: the dark body becomes the part-way white and
# the marks on it become solid.
INVERTED = {"ic_tile_code", "ic_tile_app", "ic_tile_sdcard"}

# Below this lightness a fill is the subject and comes out solid.
FIGURE_BELOW = 0.62

# What the ground becomes. Enough to read as a shape, little enough that the
# subject still reads as the subject.
GROUND_ALPHA = 0.55

HEADER = '<!-- Generated by tools/icons/build_from_svg.py from {src}. Do not edit by hand. -->'


def lightness(colour: str) -> float:
    """Perceived lightness, 0 to 1, for deciding figure from ground."""
    r, g, b = channels(colour)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def whitened(fill: str, invert: bool = False) -> str:
    """One of the pack's colours as white, solid or part-way."""
    figure = lightness(fill) < FIGURE_BELOW
    if invert:
        figure = not figure
    if figure:
        return "#FFFFFFFF"
    return f"#{int(GROUND_ALPHA * 255):02X}FFFFFF"


def recolour(fill: str) -> str:
    """The app's colour for one of the pack's.

    An unknown colour stops the build rather than being approximated. A colour
    this table has not seen means the artwork changed, and guessing at it is
    how a palette drifts: the icons would still build, and nobody would see
    the wrong shade until it shipped.
    """
    key = fill if fill.startswith("#") else fill.lower()
    known = RECOLOUR.get(key.upper() if key.startswith("#") else key)
    if known is None:
        raise SystemExit(f"unmapped colour {fill!r}; add it to RECOLOUR")
    return known


def flatten_gradient(root: ET.Element, ref: str) -> str:
    """One colour for a gradient Android would need a nested tag to express.

    The pack uses three, all of them a shade of one hue across a shape a few
    millimetres wide, where the gradient is invisible. The midpoint of the
    stops says the same thing in a fill.
    """
    ident = ref[len("url(#"):-1]
    for grad in root.iter(f"{NS}linearGradient"):
        if grad.get("id") != ident:
            continue
        stops = [s.get("stop-color") for s in grad.iter(f"{NS}stop")]
        stops = [s for s in stops if s]
        if not stops:
            break
        # Each end goes through the table before they are averaged, so the
        # table only ever sees colours the pack actually uses. Averaging
        # first invents a colour that is in neither palette, and then asks
        # what the app's version of it is.
        return mid(recolour(stops[0]), recolour(stops[-1]))
    raise SystemExit(f"gradient {ident} has no usable stops")


def mid(first: str, last: str) -> str:
    a = channels(first)
    b = channels(last)
    return "#" + "".join(f"{(x + y) // 2:02X}" for x, y in zip(a, b))


def channels(colour: str) -> tuple[int, int, int]:
    if colour == "white":
        return (255, 255, 255)
    value = colour.lstrip("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def rect_path(el: ET.Element) -> str:
    """A rect as path data, since Android vectors have only paths."""
    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    w = float(el.get("width", 0))
    h = float(el.get("height", 0))
    return f"M{x},{y} h{w} v{h} h{-w} Z"


def covers_viewport(el: ET.Element) -> bool:
    """Whether a clip shape is the whole 24x24 canvas, and so clips nothing."""
    if el.tag != f"{NS}rect":
        return False
    return (
        float(el.get("x", 0)) <= 0
        and float(el.get("y", 0)) <= 0
        and float(el.get("width", 0)) >= 24
        and float(el.get("height", 0)) >= 24
    )


def shapes(
    root: ET.Element,
    white: bool = False,
    invert: bool = False,
) -> list[tuple[str, str, bool]]:
    """Every drawn shape as (pathData, fill, evenOdd), in painting order.

    The fills come out already in the app's palette. Recolouring here rather
    than at the point of writing is what keeps [recolour] seeing only colours
    the pack itself uses: a flattened gradient is already an app colour, and
    asking the table for the app's version of one of its own answers fails.

    Groups are walked through rather than kept. The only groups in the pack
    carry a clip-path, and every one of those clips is the full canvas -- a
    habit of the drawing tool, not a decision -- so keeping them would mean
    emitting machinery that does nothing.
    """
    out: list[tuple[str, str, bool]] = []

    def walk(node: ET.Element) -> None:
        for el in node:
            if el.tag == f"{NS}g":
                clip = el.get("clip-path")
                if clip:
                    ident = clip[len("url(#"):-1]
                    for candidate in root.iter(f"{NS}clipPath"):
                        if candidate.get("id") != ident:
                            continue
                        for shape in candidate:
                            if not covers_viewport(shape):
                                raise SystemExit(f"{ident} clips something real")
                walk(el)
                continue
            if el.tag == f"{NS}defs":
                continue
            fill = el.get("fill")
            if not fill or fill == "none":
                continue
            if fill.startswith("url("):
                fill = flatten_gradient(root, fill)
            elif white:
                fill = whitened(fill, invert)
            else:
                fill = recolour(fill)
            even_odd = el.get("fill-rule") == "evenodd"
            if el.tag == f"{NS}path":
                out.append((el.get("d", ""), fill, even_odd))
            elif el.tag == f"{NS}rect":
                out.append((rect_path(el), fill, even_odd))

    walk(root)
    return out


def drawable(source: pathlib.Path, white: bool = False, invert: bool = False) -> str:
    root = ET.parse(source).getroot()
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        HEADER.format(src=source.name),
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]
    for data, fill, even_odd in shapes(root, white, invert):
        lines.append("    <path")
        lines.append(f'        android:fillColor="{fill}"')
        if even_odd:
            lines.append('        android:fillType="evenOdd"')
        lines.append(f'        android:pathData="{data}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def main() -> int:
    if not SVG.is_dir():
        raise SystemExit(f"no sources at {SVG}")
    OUT.mkdir(parents=True, exist_ok=True)
    for stem, name in sorted(MAPPING.items(), key=lambda kv: kv[1]):
        source = SVG / f"{stem}.svg"
        if not source.exists():
            raise SystemExit(f"missing source {source}")
        (OUT / f"{name}.xml").write_text(drawable(source), encoding="utf-8")
        print(f"{name}.xml  <-  {source.name}")

    for stem, name in sorted(TILES.items(), key=lambda kv: kv[1]):
        source = SVG / f"{stem}.svg"
        if not source.exists():
            raise SystemExit(f"missing source {source}")
        (OUT / f"{name}.xml").write_text(
            drawable(source, white=True, invert=name in INVERTED),
            encoding="utf-8",
        )
        print(f"{name}.xml  <-  {source.name}  (white)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
