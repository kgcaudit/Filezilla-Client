# Icon package

The app's identity icons — the ones that say what a thing *is* — are built
from the supplied flat artwork. Controls keep Material glyphs, because a
control has to tint with its state and multi-coloured artwork cannot.

## What is here

| Script | Does |
| --- | --- |
| `build_icons.py` | Traces the artwork to vector drawables, recolouring as it goes |
| `authored.py` | Draws the icons the artwork does not contain, and the launcher |
| `preview.py` | Renders the drawables at the sizes they are shown at |
| `screen_preview.py` | Puts them in the rows and the tab bar, light and dark |
| `launcher_preview.py` | Cuts the launcher with the masks the system uses |

## Rebuilding

```
pip install vtracer cairosvg pillow
python3 tools/icons/build_icons.py <artwork-dir>
python3 tools/icons/authored.py
python3 tools/icons/screen_preview.py   # then look at it
```

The drawables are generated, so they are not edited by hand — a change goes
into the script that produced them, or it is lost the next time anyone runs it.

## The decisions behind it

**Vector, not PNG.** The artwork is solid colour with no gradients, so a trace
reproduces it exactly while giving one 1–2 KB file that stays sharp at any
size. Shipping PNGs would mean five density buckets of every icon.

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

**Unmapped colours stop the build.** A colour the table has not seen means the
artwork changed. Approximating it is how a palette drifts: the icons would
still build, and nobody would see the wrong shade until it shipped.
