"""Draw desktop/packaging/kone.ico: the KOne mark in ink on a tile of paper.

The mark is the preview's (docs/windows/preview.html, and Glyphs.mark in the app): straight
lines only, its two inner shapes cut out of the mountain. Run again if the mark changes:

    pip install pillow
    python desktop/tools/app_icon.py
"""

import os

from PIL import Image, ImageDraw

INK = (28, 38, 34, 255)
PAPER = (240, 242, 245, 255)

# The mark on its 108-unit square, already shifted up 4 as the preview draws it.
MOUNTAIN = [(12, 86), (27, 68), (33, 62), (43, 59), (49, 47), (53, 41), (55, 34), (60, 29), (64, 21), (68, 24), (70, 25),
            (74, 36), (76, 39), (78, 45), (82, 46), (88, 58), (95, 69), (105, 86), (74, 83), (52, 82), (34, 84)]
CUTS = [
    [(66, 27), (69, 45), (82, 72), (86, 81), (73, 65), (65, 39)],
    [(52, 48), (55, 55), (58, 71), (66, 79), (51, 66), (47, 60)],
]
FLAG = [(52, 37), (55, 37), (55, 35), (56, 35), (56, 37), (58, 37), (58, 40), (56, 40), (56, 39), (55, 39), (55, 41), (52, 41)]


def tile(size: int) -> Image.Image:
    scale = 8  # drawn large, then shrunk, for smooth edges
    big = size * scale
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.22, fill=PAPER)
    # The mark (x 12..105, y 21..86) centred, four fifths of the tile wide.
    k = big * 0.80 / 93
    dx = (big - 93 * k) / 2 - 12 * k
    dy = (big - 65 * k) / 2 - 21 * k

    def at(points):
        return [(dx + x * k, dy + y * k) for x, y in points]

    draw.polygon(at(MOUNTAIN), fill=INK)
    for cut in CUTS:
        draw.polygon(at(cut), fill=PAPER)
    draw.polygon(at(FLAG), fill=INK)
    return image.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "packaging", "kone.ico")
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    tile(256).save(out, sizes=[(s, s) for s in sizes], append_images=[tile(s) for s in sizes[:-1]])
    tile(256).save(os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "kone.png"))
