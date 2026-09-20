# Icon package

The app's identity icons — the ones that say what a thing *is* — are built
from the supplied flat artwork. Controls keep Material glyphs, because a
control has to tint with its state and multi-coloured artwork cannot.

## What is here

| Script | Does |
| --- | --- |
| `build_from_svg.py` | Converts the pack's SVG sources to vector drawables, recolouring as it goes |
| `authored.py` | Draws the icons the artwork does not contain, and the launcher |
| `preview.py` | Renders the drawables at the sizes they are shown at |
| `screen_preview.py` | Puts them in the rows, the tab bar and the empty screens, light and dark |
| `launcher_preview.py` | Cuts the launcher with the masks the system uses |
| `theme_preview.py` | Renders the screens from the theme's own values, light and dark |

## Rebuilding

```
pip install cairosvg pillow
python3 tools/icons/build_from_svg.py
python3 tools/icons/authored.py
python3 tools/icons/screen_preview.py   # then look at it
```

The drawables are generated, so they are not edited by hand — a change goes
into the script that produced them, or it is lost the next time anyone runs it.

## The decisions behind it

**The tile is the icon.** A row's icon was a 24dp drawing on a 40dp pale
chip -- a third of the tile, legible if you looked at it and not if you
scanned past it, and the same drawing for every file whatever the file was. A
tile is a solid colour with the shape cut out of it in white: the colour says
what kind of thing the row is before the name is read. `build_from_svg.py`
emits both sets from the same sources; `TILES` is the white one.

**Figure and ground by lightness.** Colour cannot survive the trip to a
white-on-colour tile, so each fill is read as subject or body by how light the
designer drew it: dark strokes become solid white, pale fills become white at
55%. That keeps a document's page distinct from the lines on it. Three of the
drawings are knockouts -- a dark box with a white `</>` through it, a dark
robot head with white eyes, a dark memory card with pale contacts -- and the
rule reads those exactly backwards, so `INVERTED` turns it round for them.

**White behind the artwork.** The pack is drawn for a white background, and
`FlatIconChip` is white for that reason. It was a pale blue-grey at first,
within a shade of the file icon's own page: the page vanished into the chip
and every file in every listing was drawn as three floating bars that read as
a list icon. Darkening the chip cured that one and spoiled the copy icon the
same way, because the chip's darkness was never the problem.

**What the pack is not used for.** The bar across the bottom of a selection
looks like the obvious next place for it, and the pack cannot furnish it:
there is no scissors, so cut would stay a Material glyph beside five pieces of
artwork. A bar of six icons that match beats five that match and one that does
not.

**From the SVG sources, not from a trace.** The artwork first arrived as
512px PNGs, so the drawables were traced from them; `tools/icons/svg/` now
holds the pack's own SVGs, which are the same drawings with their real
geometry on the 24dp grid they were drawn on. An Android vector's `pathData`
takes SVG path syntax as it stands, so nothing is redrawn — every path crosses
over verbatim. The files are a third the size of the traced ones and exact
rather than very close.

**Vector, not PNG.** One 1 KB file that stays sharp at any size. Shipping
PNGs would mean five density buckets of every icon.

**Recoloured to the app's palette.** The artwork's blue is brighter and
lighter than this app's Ocean; side by side they read as two palettes. Every
fill is mapped in `RECOLOUR`, written out rather than computed — every
distance metric tried got something wrong quietly. By RGB the status lights'
yellow-green lands nearer amber than green, and every light on the server
went amber, so the icon stopped saying "some of these are fine".

**A pale chip, in both themes.** Multi-coloured artwork cannot be tinted for
the background it lands on: against a dark surface its greys and deep blues
go. Every artwork icon sits on `FlatIconChip` instead, including the selected
tab, whose indicator is that same chip — on the dark theme's own indicator the
artwork's deep blue sat on deeper blue and the selected tab was the hardest
one to make out.

**The error mark keeps no chip.** It is a solid amber disc, so it carries
its own background and reads on both the light and the dark error card --
checked against both. Tinting it to the card's colour would erase it.

**Unmapped colours stop the build.** A colour the table has not seen means the
artwork changed. Approximating it is how a palette drifts: the icons would
still build, and nobody would see the wrong shade until it shipped.
