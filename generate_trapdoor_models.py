#!/usr/bin/env python3
"""A rectangle of trapdoors drawn as one trapdoor.

A trapdoor sheet is a frame round a panel. Laid side by side, every tile shows its own frame and
the rectangle reads as tiles. Here a tile keeps only the sides of the frame that lie on the
rectangle's edge, and stretches the sheet's interior across the rest, so a two-by-three hatch
reads as one hatch built to the hole. The same for a rectangle of open trapdoors standing
against a wall, which is a shutter.

Named for the state and the sides kept, in the sheet's own terms:

  trapdoor_mega_<bottom|top|open>_<flags>     l: u=0 side   r: u=16 side   t: v=0 side   b: v=16 side

Lying flat, u runs east and v runs south; standing open, u runs along the wall and v runs down
from the top. Pandorical's client picks one per tile from the rectangle it finds and turns the
open ones for their facing. The geometry is shared; each trapdoor's copy is one line naming its
sheet. Deterministic. Usage: python3 generate_trapdoor_models.py [jar]
"""
import itertools
import json
import os
import zipfile

from generate_door_jambs import find_jar

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "src/main/resources/assets")
MOD_ID = "more-doors-justfatlard"
TEMPLATES = os.path.join(ASSETS, "minecraft/models/block")

FRAME = 2.0
THICK = 3.0


def pieces(low, high):
    """How a tile's 16 pixels map onto the sheet along one axis: (a0, a1, s0, s1) pieces."""
    out = []
    if low:
        out.append((0.0, FRAME, 0.0, FRAME))
    inner = (FRAME if low else 0.0, 16.0 - FRAME if high else 16.0)
    out.append((inner[0], inner[1], FRAME, 16.0 - FRAME))
    if high:
        out.append((16.0 - FRAME, 16.0, 16.0 - FRAME, 16.0))
    return out


def face(u1, v1, u2, v2, cull=None, rotation=None):
    f = {"uv": [u1, v1, u2, v2], "texture": "#texture"}
    if cull:
        f["cullface"] = cull
    if rotation:
        f["rotation"] = rotation
    return f


def tile(state, l, r, t, b):
    """The sub-boxes of one tile, each face reading its piece of the sheet the way vanilla's
    template reads the whole."""
    elements = []
    for (a0, a1, u0, u1) in pieces(l, r):
        for (b0, b1, v0, v1) in pieces(t, b):
            faces = {}
            if state == "open":
                # Standing at the block's south edge, facing north as vanilla's template does:
                # u along x, v down from the top, the slab three thick in z.
                y0, y1 = 16.0 - b1, 16.0 - b0
                box = {"from": [a0, y0, 13.0], "to": [a1, y1, 16.0]}
                faces["north"] = face(u0, v1, u1, v0)
                faces["south"] = face(u0, v1, u1, v0, "south")
                if a0 == 0.0:
                    faces["west"] = face(y0, 0.0, y1, THICK, "west", 90)
                if a1 == 16.0:
                    faces["east"] = face(y0, THICK, y1, 0.0, "east", 90)
                if y0 == 0.0:
                    faces["down"] = face(a0, 0.0, a1, THICK, "down")
                if y1 == 16.0:
                    faces["up"] = face(a0, THICK, a1, 0.0, "up")
            else:
                # Lying flat at the bottom or top of the block: u along x, v along z.
                y0, y1 = (0.0, THICK) if state == "bottom" else (16.0 - THICK, 16.0)
                box = {"from": [a0, y0, b0], "to": [a1, y1, b1]}
                faces["down"] = face(u0, v0, u1, v1, "down" if y0 == 0.0 else None)
                faces["up"] = face(u0, 16.0 - v0, u1, 16.0 - v1, "up" if y1 == 16.0 else None)
                if b0 == 0.0:
                    faces["north"] = face(a0, 0.0, a1, THICK, "north")
                if b1 == 16.0:
                    faces["south"] = face(a0, 0.0, a1, THICK, "south")
                if a0 == 0.0:
                    faces["west"] = face(b0, 0.0, b1, THICK, "west")
                if a1 == 16.0:
                    faces["east"] = face(b0, 0.0, b1, THICK, "east")
            box["faces"] = faces
            elements.append(box)
    return elements


def flags_name(l, r, t, b):
    return "".join(c for c, on in (("l", l), ("r", r), ("t", t), ("b", b)) if on) or "x"


def write(path, model):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(model, f, separators=(",", ":"))
        f.write("\n")


def main():
    names = []
    for state in ("bottom", "top", "open"):
        for l, r, t, b in itertools.product((False, True), repeat=4):
            name = "trapdoor_mega_%s_%s" % (state, flags_name(l, r, t, b))
            write(os.path.join(TEMPLATES, name + ".json"),
                  {"parent": "block/thin_block" if state != "open" else None,
                   "textures": {"particle": "#texture"}, "elements": tile(state, l, r, t, b)})
            names.append(name)
    # A null parent is not a parent; take it back out where it was not wanted.
    for name in names:
        path = os.path.join(TEMPLATES, name + ".json")
        model = json.load(open(path))
        if model.get("parent") is None:
            model.pop("parent", None)
            write(path, model)

    trapdoors = []
    with zipfile.ZipFile(find_jar()) as jar:
        for entry in jar.namelist():
            if entry.startswith("assets/minecraft/blockstates/") and entry.endswith("_trapdoor.json"):
                trapdoor = entry[len("assets/minecraft/blockstates/"):-len(".json")]
                variants = json.loads(jar.read(entry))["variants"]
                first = next(iter(variants.values()))
                model_id = (first[0] if isinstance(first, list) else first)["model"].split(":", 1)[-1]
                model = json.loads(jar.read("assets/minecraft/models/%s.json" % model_id))
                trapdoors.append(("minecraft", trapdoor, model["textures"]["texture"]))
    own = os.path.join(ASSETS, MOD_ID, "models/block")
    for file in sorted(os.listdir(own)):
        if file.endswith("_trapdoor_bottom.json"):
            trapdoor = file[:-len("_bottom.json")]
            model = json.load(open(os.path.join(own, file)))
            trapdoors.append((MOD_ID, trapdoor, model["textures"]["texture"]))

    copies = 0
    for namespace, trapdoor, texture in trapdoors:
        for name in names:
            write(os.path.join(ASSETS, namespace, "models/block", trapdoor + name[len("trapdoor"):] + ".json"),
                  {"parent": "minecraft:block/" + name, "textures": {"texture": texture}})
            copies += 1
    print("wrote %d trapdoor templates and %d copies for %d trapdoors" % (len(names), copies, len(trapdoors)))


if __name__ == "__main__":
    main()
