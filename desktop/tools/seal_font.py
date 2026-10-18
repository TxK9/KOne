"""Cut KOne Seal, the serif the seals are written in, out of Noto Serif CJK SC.

This optional legacy asset builder retains the upstream font provenance. The English UI uses
Latin system serif fallbacks; regenerated subsets use the English monograms below:

    pip install fonttools
    python desktop/tools/seal_font.py /path/to/NotoSerifCJK-Bold.ttc /path/to/NotoSerifCJK-Regular.ttc

The collections come from https://github.com/notofonts/noto-cjk (or Debian's fonts-noto-cjk);
index 2 in each is Simplified Chinese. The result is renamed, as the font's licence asks of a
modified version, and its licence is desktop/src/main/resources/fonts/OFL.txt.
"""

import os
import sys

from fontTools import subset
from fontTools.ttLib import TTCollection

# Weather seal, then the painting seals (InkMountains names).
SEAL = "SM!ZLGHR"
# The inscriptions beside the painting seals (InkMountains mottos).
MOTTO = "LIBERTYGATEYOSEMITERAINIER"
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "fonts")


def cut(collection: str, text: str, style: str) -> None:
    font = TTCollection(collection).fonts[2]
    options = subset.Options()
    options.layout_features = []
    options.name_IDs = ["*"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(text=text)
    subsetter.subset(font)
    name = font["name"]
    name.names = [r for r in name.names if r.nameID in (0, 2, 5, 7, 8, 9, 11, 12, 13, 14)]
    for nid, value in ((1, "KOne Seal"), (2, style), (3, f"KOneSeal-{style}"), (4, f"KOne Seal {style}"), (6, f"KOneSeal-{style}")):
        name.setName(value, nid, 3, 1, 0x409)
        name.setName(value, nid, 1, 0, 0)
    font.save(os.path.join(OUT, f"KOneSeal-{style}.otf"))


if __name__ == "__main__":
    cut(sys.argv[1], SEAL, "Bold")
    cut(sys.argv[2], MOTTO, "Regular")
