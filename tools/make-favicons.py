#!/usr/bin/env python3
"""
Regenerate the browser-tab icons from assets/logo.png.

Why a script instead of hand-editing the PNGs:

  The tab icons are the one set of assets on this site that every visitor sees
  before they read a single word, and they are also the easiest to get wrong
  silently: a bad downscale or a stray background pixel is invisible in a file
  listing and obvious in a browser. Generating them from the 1254x1254 master
  with one documented set of rules means the result is reproducible and
  reviewable, and regenerating after a logo change is a single command.

What "monochrome and transparent" means here, precisely:

  Every pixel that survives gets the SAME RGB value; only the alpha channel
  varies. The silhouette therefore comes straight from the master's alpha
  channel, so the mark keeps its exact antialiased edges and no colour, tint or
  background tile is introduced. Anything the master left clear stays fully
  clear, so the icon is genuinely transparent rather than white-on-white.

  The old icons were the opposite: a near-black #111114 rounded tile filled
  84-93% of each PNG, and apple-touch-icon.png was 100% opaque. That is a
  coloured icon with a background, not a monochrome one.

Usage:
  tools/make-favicons.py                       # near-black glyph, the default
  tools/make-favicons.py --color '#ffffff'     # white glyph for dark chrome
  tools/make-favicons.py --apple-touch transparent

Note on apple-touch-icon.png: it is deliberately NOT transparent by default.
iOS does not honour transparency there - it composites the image onto its own
opaque backing and applies the rounded mask itself, so a transparent
apple-touch-icon renders as a plain black square on the home screen. That is
the documented platform behaviour, not a guess. Pass --apple-touch transparent
only if you have verified it on a real device.
"""

import argparse
import io
import os
import struct
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: python3 -m pip install Pillow")

# The site's own background colour, so the glyph matches the page it sits on.
# See website/styles.css --bg-dark.
DEFAULT_COLOR = "#07080d"

# Fraction of the tile the mark should fill. Favicons read best when the mark
# nearly fills the tile; the master canvas is 88.9% mark, so this keeps the
# proportions the logo already uses.
FILL = 0.88

# The master mark is a thin, mostly-antialiased glyph (mean alpha 82 of 255).
# Downscaled to 16px it loses contrast, so the alpha ramp is stretched before
# resampling. This only moves coverage, never the silhouette's shape, and it is
# skipped for the large sizes where there is no legibility problem.
SMALL_SIZES = (16, 32)


def parse_color(value):
    v = value.strip().lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    if len(v) != 6:
        raise argparse.ArgumentTypeError(f"expected #rgb or #rrggbb, got {value!r}")
    try:
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))
    except ValueError:
        raise argparse.ArgumentTypeError(f"not valid hex: {value!r}")


def stretch_alpha(alpha, size):
    """Widen the alpha ramp so the thin mark survives a downscale."""
    if size not in SMALL_SIZES:
        return alpha
    lo, hi = 40, 215          # below lo -> clear, above hi -> solid
    span = max(hi - lo, 1)
    lut = []
    for i in range(256):
        n = (i - lo) / span
        n = 0.0 if n < 0 else (1.0 if n > 1 else n)
        lut.append(int(round((n ** 0.85) * 255)))   # gamma < 1 thickens slightly
    return alpha.point(lut)


def build_tile(master, size, color):
    """One square tile: mark centred, flat colour, transparent background."""
    bbox = master.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("master logo.png is fully transparent; nothing to draw")
    mark = master.crop(bbox)

    inner = max(1, round(size * FILL))
    w, h = mark.size
    scale = inner / max(w, h)
    tw, th = max(1, round(w * scale)), max(1, round(h * scale))

    # Reduce on the alpha channel alone. Compositing RGBA directly lets dark
    # transparent pixels bleed into the edges and leaves grey fringes.
    a = stretch_alpha(mark.getchannel("A"), size).resize((tw, th), Image.LANCZOS)

    # The background is the glyph colour at alpha 0, not black at alpha 0.
    # That keeps every pixel in the file the same RGB, so the image stays
    # monochrome even after a later RGBA downscale. This matters for the .ico:
    # Pillow generates its 16/32/48 frames by resizing the base image, and with
    # a black background those frames came out 47 near-black shades instead of
    # one. Fully transparent pixels are not visible either way.
    tile = Image.new("RGBA", (size, size), color + (0,))
    tile.paste(color + (255,), ((size - tw) // 2, (size - th) // 2), a)
    return tile


def write_ico(frames, path):
    """Write a single .ico containing one PNG frame per given size.

    Pillow's own ICO writer resamples every frame whose size differs from the
    base image, and that resample turned a monochrome icon into 33-55 distinct
    near-black shades; only the frame matching the base size came out exact.
    The container format is a 6-byte header plus a 16-byte directory entry per
    frame, so the frames are assembled here directly and every one of them is
    the tile that was generated for that size, unmodified.

    PNG-encoded frames rather than BMP/DIB: supported by every browser that
    still consults a .ico at all, and it keeps the alpha channel that a palette
    frame would flatten.
    """
    entries, blobs = [], []
    offset = 6 + 16 * len(frames)
    for size, im in frames:
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        data = buf.getvalue()
        entries.append(struct.pack(
            "<BBBBHHII",
            size % 256,        # 0 means 256; every size used here is smaller
            size % 256,
            0,                 # colour count: 0 = no palette table
            0,                 # reserved
            1,                 # colour planes
            32,                # bits per pixel
            len(data),
            offset,
        ))
        blobs.append(data)
        offset += len(data)

    with open(path, "wb") as fh:
        fh.write(struct.pack("<HHH", 0, 1, len(frames)))
        for entry in entries:
            fh.write(entry)
        for blob in blobs:
            fh.write(blob)


def report(path, size_expected=None):
    im = Image.open(path)
    rgba = im.convert("RGBA")
    # Pixels are read via tobytes(); getdata() is removed in Pillow 14.
    raw = rgba.tobytes()
    px = [tuple(raw[i:i + 4]) for i in range(0, len(raw), 4)]
    total = len(px)
    clear = sum(1 for p in px if p[3] == 0)
    cols = {p[:3] for p in px if p[3] > 0}
    corners = [px[0], px[im.size[0] - 1], px[-1]]
    size_txt = f"{im.size[0]}x{im.size[1]}"
    if size_expected and im.size != (size_expected, size_expected):
        size_txt += f"  *** EXPECTED {size_expected}x{size_expected} ***"
    print(
        f"  {os.path.basename(path):<24} {size_txt:<10} "
        f"clear {clear / total * 100:5.1f}%  distinct colours {len(cols)}  "
        f"corners {'transparent' if all(c[3] == 0 for c in corners) else 'OPAQUE'}"
    )
    if len(cols) > 1:
        print(f"      *** NOT MONOCHROME: {sorted(cols)[:5]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--color", type=parse_color, default=parse_color(DEFAULT_COLOR),
                    help=f"flat glyph colour (default {DEFAULT_COLOR})")
    ap.add_argument("--src", default=None, help="master image (default assets/logo.png)")
    ap.add_argument("--apple-touch", choices=["keep", "transparent", "opaque"],
                    default="keep",
                    help="apple-touch-icon.png: leave as-is, strip its background, "
                         "or repaint it opaque (default keep)")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(os.path.dirname(here), "website", "assets")
    src = args.src or os.path.join(assets, "logo.png")
    if not os.path.isfile(src):
        sys.exit(f"master not found: {src}")

    master = Image.open(src).convert("RGBA")
    print(f"master: {src}  {master.size[0]}x{master.size[1]}")
    print(f"glyph:  #{args.color[0]:02x}{args.color[1]:02x}{args.color[2]:02x}  "
          f"fill {FILL * 100:.0f}%  alpha stretch on {SMALL_SIZES}\n")

    # Largest tile first, then derive the .ico from the same master so the
    # Windows/legacy icon cannot drift from the PNGs.
    for size, name in ((64, "favicon.png"), (48, "favicon-48.png"),
                       (32, "favicon-32.png"), (16, "favicon-16.png")):
        out = os.path.join(assets, name)
        build_tile(master, size, args.color).save(out, "PNG", optimize=True)
        print(f"wrote {name}")

    ico = os.path.join(assets, "favicon.ico")
    write_ico([(s, build_tile(master, s, args.color)) for s in (16, 32, 48)], ico)
    print("wrote favicon.ico (16, 32, 48)")

    if args.apple_touch != "keep":
        at = os.path.join(assets, "apple-touch-icon.png")
        im = Image.open(at).convert("RGBA")
        if args.apple_touch == "transparent":
            # Only the near-black backing is removed. The mark is near-white
            # (#fdfcfc) and the backing #111114, so a distance test separates
            # them cleanly; the tolerance is kept low to protect the glyph.
            px = im.load()
            w, h = im.size
            removed = 0
            for y in range(h):
                for x in range(w):
                    r, g, b, a = px[x, y]
                    if a and abs(r - 0x11) <= 12 and abs(g - 0x11) <= 12 and abs(b - 0x14) <= 12:
                        px[x, y] = (r, g, b, 0)
                        removed += 1
            im.save(at, "PNG", optimize=True)
            print(f"wrote apple-touch-icon.png (cleared {removed / (w * h) * 100:.1f}% backing)")
        else:
            im.convert("RGB").save(at, "PNG", optimize=True)
            print("wrote apple-touch-icon.png (flattened opaque)")

    print("\nverification:")
    for name, expect in (("favicon-16.png", 16), ("favicon-32.png", 32),
                         ("favicon-48.png", 48), ("favicon.png", 64),
                         ("apple-touch-icon.png", 180)):
        report(os.path.join(assets, name), expect)
    report(ico)


if __name__ == "__main__":
    main()
