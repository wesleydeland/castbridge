#!/usr/bin/env python3
"""Sanitize store screenshots before publication.

Replaces real LAN IP addresses and personal room names with generic staged
values. Store listings are public, so no real device names, addresses or room
names may appear. Geometry is measured from the 1220x2712 captures of this app
build (3x density) and verified by eye against the crops.

Usage: redact-store-shots.py <source-dir> <dest-dir>
"""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

# App palette — matches app/src/main/res/values/colors.xml and the UI constants
# in MainActivity.java (verified against pixel samples of the real screenshots).
CARD = (23, 28, 36)      # #171C24  card / input-field surface
BG = (14, 18, 24)        # #0E1218  app background
TEXT = (242, 245, 248)   # #F2F5F8  primary text
ACCENT = (91, 157, 255)  # #5B9DFF  discovered-device rows

FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# Generic replacements. Deliberately unlike anything in the original captures:
# the source rows named real rooms of the developer's own network, so neutral
# names are used here to make it obvious the listing is staged, not a live network.
GEN_IP_FIELD = "192.168.1.50"
GEN_ROWS = (("Living room speaker", "192.168.1.50"),
            ("Bedroom speaker", "192.168.1.51"))

# Measured geometry (source: 1220x2712 captures, confirmed visually).
# Rows are given as (x0, y0, x1, y1) bands; see the note in main() for how they
# were derived. The originals contained real device names and addresses and are
# intentionally NOT reproduced here.
IP_FIELD = (116, 475, 1105, 596)      # the SPEAKER IP text field
ROW1 = (100, 822, 1120, 867)          # first discovered-device row
ROW2 = (100, 943, 1120, 978)          # second discovered-device row


def _font(path, size):
    return ImageFont.truetype(path, size)


def _fill_field(draw, box):
    """Repaint the input field with its own surface colour and rounded corners."""
    draw.rounded_rectangle(box, radius=36, fill=CARD)


def redact_main(draw):
    """Screenshot 1: only the IP field carries private data."""
    _fill_field(draw, IP_FIELD)
    f = _font(FONT_REG, 44)
    # vertically centre the replacement text in the field
    ty = IP_FIELD[1] + (IP_FIELD[3] - IP_FIELD[1]) // 2 - 26
    draw.text((IP_FIELD[0] + 44, ty), GEN_IP_FIELD, font=f, fill=TEXT)


def redact_discovery(draw):
    """Screenshot 2: the IP field plus both discovered-device rows."""
    _fill_field(draw, IP_FIELD)
    f = _font(FONT_REG, 44)
    ty = IP_FIELD[1] + (IP_FIELD[3] - IP_FIELD[1]) // 2 - 26
    draw.text((IP_FIELD[0] + 44, ty), GEN_IP_FIELD, font=f, fill=TEXT)

    fb = _font(FONT_BOLD, 40)
    for (x0, y0, x1, y1), (room, ip) in zip((ROW1, ROW2), GEN_ROWS):
        # rows sit directly on the card background; repaint that strip, then
        # redraw the label in the app's accent colour.
        draw.rectangle((x0 - 24, y0 - 14, x1 + 24, y1 + 14), fill=CARD)
        draw.text((x0, y0 - 4), f"{room}  \u2014  {ip}", font=fb, fill=ACCENT)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)

    jobs = {
        "1-main.png": redact_main,
        "2-discovery.png": redact_discovery,
        "3-disclosure.png": lambda d: None,   # consent dialog — no private data
        "4-about.png": lambda d: None,        # about/licenses — no private data
    }
    for name, fn in jobs.items():
        p = src / name
        if not p.exists():
            print("skip (missing):", name)
            continue
        im = Image.open(p).convert("RGB")
        fn(ImageDraw.Draw(im))
        out = dst / name
        im.save(out, "PNG", optimize=True)
        print("wrote", out)


if __name__ == "__main__":
    main()