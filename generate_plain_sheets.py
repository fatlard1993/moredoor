#!/usr/bin/env python3
"""Every door's sheet without its handle, for the leaves of a large door that have none.

Written under the door's own namespace, because this mod's solid oak door is also called
oak_door and would otherwise write over vanilla's.

A door sheet paints its handle on, at the swinging edge just under the seam. A large door has
one handle, at that place on its bottom row, and every other leaf's sheet has to be handle-free
or the door grows a knob per leaf. So: the handle's pixels - metal by their grey or their brass,
and the shadow they cast - are found in the rows around the seam on the swinging side and filled
in from the wood beside them in the same row, which follows the rails and panels that run
across a door. A handle is found by what it is not - a colour the hinge side of that row never
uses - so grey, brass, lighter wood and darker wood are all caught. Deterministic.

Usage: python3 generate_plain_sheets.py [jar]
"""
import json
import os
import zipfile

from PIL import Image

from generate_door_jambs import find_jar

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "src/main/resources/assets")
MOD_ID = "more-doors-justfatlard"
OUT = os.path.join(ASSETS, MOD_ID, "textures/block/plain")

# Where a handle can be on a sheet drawn hinge-left: the swinging side, the rows about the seam.
# Not the last column, which is the stile's edge and often shaded on one side only.
ZONE_COLS = range(9, 15)
ZONE_ROWS = range(12, 20)   # rows 12-15 of the top sheet, then 0-3 of the bottom, stacked
# The half of a row that never holds a handle, and so says what that row's wood looks like.
WOOD_COLS = range(1, 8)
# How far a colour may sit from the row's wood and still be wood: shading, not a fitting.
TOLERANCE = 24


def apart(a, b):
    return sum((a[i] - b[i]) ** 2 for i in range(3)) ** 0.5


def plain(top, bottom):
    """The two sheets, stacked, with the handle gone; None if there was none to take off.

    A handle is whatever sits on the swinging side, about the seam, in a colour that the same
    row's hinge side never uses. Grey, brass, a darker or a lighter wood: the rule does not care
    what a handle is made of, only that the door's own wood is not it. The rail and panel that
    run across the row are kept by filling from the left along that row.
    """
    sheet = [[top.getpixel((x, y)) for x in range(16)] for y in range(16)]
    sheet += [[bottom.getpixel((x, y)) for x in range(16)] for y in range(16)]

    marked = set()
    for y in ZONE_ROWS:
        wood = [sheet[y][x] for x in WOOD_COLS if sheet[y][x][3]]
        if not wood:
            continue
        for x in ZONE_COLS:
            px = sheet[y][x]
            if px[3] and all(apart(px, w) > TOLERANCE for w in wood):
                marked.add((x, y))
    if not marked:
        return None

    for (x, y) in sorted(marked):
        fill = x - 1
        while fill >= 1 and ((fill, y) in marked or not sheet[y][fill][3]):
            fill -= 1
        if fill >= 1:
            sheet[y][x] = sheet[y][fill]

    out_top = Image.new("RGBA", (16, 16))
    out_bottom = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            out_top.putpixel((x, y), sheet[y][x])
            out_bottom.putpixel((x, y), sheet[16 + y][x])
    return out_top, out_bottom


def main():
    os.makedirs(OUT, exist_ok=True)
    sheets = []
    with zipfile.ZipFile(find_jar()) as jar:
        for entry in jar.namelist():
            if entry.startswith("assets/minecraft/blockstates/") and entry.endswith("_door.json"):
                door = entry[len("assets/minecraft/blockstates/"):-len(".json")]
                variants = json.loads(jar.read(entry))["variants"]
                first = next(iter(variants.values()))
                model_id = (first[0] if isinstance(first, list) else first)["model"].split(":", 1)[-1]
                textures = json.loads(jar.read("assets/minecraft/models/%s.json" % model_id))["textures"]
                top = Image.open(jar.open("assets/minecraft/textures/%s.png" % textures["top"].split(":", 1)[-1])).convert("RGBA")
                bottom = Image.open(jar.open("assets/minecraft/textures/%s.png" % textures["bottom"].split(":", 1)[-1])).convert("RGBA")
                sheets.append(("minecraft/" + door, top, bottom))
    own = os.path.join(ASSETS, MOD_ID, "textures/block")
    for file in sorted(os.listdir(own)):
        if file.endswith("_door_top.png"):
            door = file[:-len("_top.png")]
            sheets.append((MOD_ID + "/" + door, Image.open(os.path.join(own, file)).convert("RGBA"),
                           Image.open(os.path.join(own, door + "_bottom.png")).convert("RGBA")))

    made = kept = 0
    for door, top, bottom in sheets:
        result = plain(top, bottom)
        if result is None:
            result = (top, bottom)
            kept += 1
        else:
            made += 1
        os.makedirs(os.path.dirname(os.path.join(OUT, door)), exist_ok=True)
        result[0].save(os.path.join(OUT, door + "_top.png"))
        result[1].save(os.path.join(OUT, door + "_bottom.png"))
    print("wrote plain sheets for %d doors (%d had a handle to take off, %d had none to find)" % (len(sheets), made, kept))


if __name__ == "__main__":
    main()
