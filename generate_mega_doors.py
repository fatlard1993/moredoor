#!/usr/bin/env python3
"""A bank of doors drawn as one door.

Doors of one kind, hung the same way and standing in a full rectangle, are one door here, and a
door has one frame: stiles down its outer edges, rails along its top and bottom, a handle at the
swinging edge of its bottom row. Every leaf inside the rectangle keeps only the parts of the frame
that lie on the rectangle's edge and stretches the sheet's interior across the rest, so the whole
reads as one large door built to the opening rather than a grid of small ones.

The eight ordinary models are the starting point. A leaf's model here is the same panel, frame
and handle with pieces left out by position, named for what it keeps:

  door_mega_<bottom|top>_<left|right>[_open]_<flags>

  l  a stile on the low-z edge        r  a stile on the high-z edge
  t  the top rail (top half only)     b  the bottom rail (bottom half only)
  h  the handle (bottom half only)    x  none of these

Pandorical's client picks one per leaf from the rectangle it finds, turned by vanilla's own
blockstate rotation. The geometry is shared: every door's own copy is one line naming its sheets -
the originals for the knob, and the handle-free copies from generate_plain_sheets.py for the
leaf - generated here for every door the game ships and every one this mod adds. Deterministic.

Usage: python3 generate_mega_doors.py [jar]
"""
import itertools
import json
import os
import re
import zipfile

from generate_door_models import (EDGE, FRAME, HANDLE_PROUD, HANDLE_U, PANEL_INSET, THICK, broad, face,
                                  full_kind, seamless)
from generate_door_jambs import find_jar

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "src/main/resources/assets")
MOD_ID = "moredoor-justfatlard"
TEMPLATES = os.path.join(ASSETS, "minecraft/models/block")
# The full doors' templates: no rails anywhere, the middle one included, since their sheets paint
# none. Bars run the sheet's whole height through every leaf. Glass paints the pane's own border
# on its outer rows, so where one leaf meets the next those rows are left out, as a rail's are.
FULL_TEMPLATES = os.path.join(ASSETS, MOD_ID, "models/block")
FULL_PREFIXES = {"barred": "template_full_", "glass": "template_full_glass_"}

# The sheet's interior, between the stiles, which is what stretches across a leaf that has none.
INNER = (FRAME, 16.0 - FRAME)


def box(texture, x1, x2, y1, y2, z1, z2, u1, u2, v1, v2, flip, edge_uv=None):
    """A box whose wide faces read the sheet between u1..u2 across z1..z2 and v1..v2 down y."""
    ua, ub = (16 - u1, 16 - u2) if flip else (u1, u2)
    eu1, eu2 = edge_uv if edge_uv else (0.0, EDGE)
    lo, hi = min(ua, ub), max(ua, ub)
    return {
        "from": [x1, y1, z1],
        "to": [x2, y2, z2],
        "faces": {
            "west": face(texture, ua, v1, ub, v2, "west" if x1 == 0 else None),
            "east": face(texture, ub, v1, ua, v2, "east" if x2 == 16 else None),
            "north": face(texture, eu2, v1, eu1, v2, "north" if z1 == 0 else None),
            "south": face(texture, eu1, v1, eu2, v2, "south" if z2 == 16 else None),
            "up": face(texture, eu1, lo, eu2, hi, "up" if y2 == 16 else None, 90),
            "down": face(texture, eu1, lo, eu2, hi, "down" if y1 == 0 else None, 90),
        },
    }


def spans(low_stile, high_stile):
    """How z maps onto the sheet's u for this leaf: (z1, z2, u1, u2) pieces, edge to edge."""
    pieces = []
    if low_stile:
        pieces.append((0.0, FRAME, 0.0, FRAME))
    inner_z = (FRAME if low_stile else 0.0, 16.0 - FRAME if high_stile else 16.0)
    pieces.append((inner_z[0], inner_z[1], INNER[0], INNER[1]))
    if high_stile:
        pieces.append((16.0 - FRAME, 16.0, 16.0 - FRAME, 16.0))
    return pieces


def rows(half, top_rail, bottom_rail):
    """Which sheet rows the panel shows down its 16 pixels: the rail rows only where the rail is."""
    v1 = 0.0 if (half == "top" and top_rail) or half == "bottom" else FRAME
    v2 = 16.0 if (half == "bottom" and bottom_rail) or half == "top" else 16.0 - FRAME
    if half == "top" and not top_rail:
        v1, v2 = FRAME, 16.0
    if half == "bottom" and not bottom_rail:
        v1, v2 = 0.0, 16.0 - FRAME
    return v1, v2


def leaf(half, flip, low_stile, high_stile, top_rail, bottom_rail, handle, full=None):
    # The leaf is drawn from the sheet without its painted handle: a large door has one handle,
    # the knob below, and every leaf's sheet would otherwise show one. Only the knob reads the
    # original, which is where the handle's own pixels are.
    texture = "#bottom_plain" if half == "bottom" else "#top_plain"
    v1, v2 = (0.0, 16.0) if full == "barred" else rows(half, top_rail, bottom_rail)
    elements = []

    # The panel: recessed, the interior of the sheet stretched across whatever width is not stile.
    # A full door has no rail over the panel's end on the rectangle's outer edge, so it is closed.
    outer = ("up",) if half == "top" and top_rail else ("down",) if half == "bottom" and bottom_rail else ()
    for z1, z2, u1, u2 in spans(low_stile, high_stile):
        keep = outer if full and (u1, u2) == INNER else ()
        elements.append(broad(box(texture, PANEL_INSET, THICK - PANEL_INSET, 0.0, 16.0, z1, z2,
                                  u1, u2, v1, v2, flip), keep=keep))

    # Stiles, only on the rectangle's edges.
    if low_stile:
        elements.append(box(texture, 0.0, THICK, 0.0, 16.0, 0.0, FRAME, 0.0, FRAME, v1, v2, flip))
    if high_stile:
        elements.append(box(texture, 0.0, THICK, 0.0, 16.0, 16.0 - FRAME, 16.0,
                            16.0 - FRAME, 16.0, v1, v2, flip))

    # Rails: the middle one always, the outer one only on the rectangle's edge. Each runs
    # between whatever stiles this leaf has, reading its own rows of the sheet.
    rail_z = (FRAME if low_stile else 0.0, 16.0 - FRAME if high_stile else 16.0)
    rails = []
    if not full and half == "top":
        rails.append((0.0, FRAME))                       # the middle rail's upper part
        if top_rail:
            rails.append((16.0 - FRAME, 16.0))
    elif not full:
        rails.append((16.0 - FRAME, 16.0))               # the middle rail's lower part
        if bottom_rail:
            rails.append((0.0, FRAME))
    for y1, y2 in rails:
        for z1, z2, u1, u2 in spans(low_stile, high_stile):
            za, zb = max(z1, rail_z[0]), min(z2, rail_z[1])
            if zb <= za:
                continue
            # The rail reads the sheet across its own span, the same stretch the panel uses.
            ua = u1 + (u2 - u1) * (za - z1) / (z2 - z1)
            ub = u1 + (u2 - u1) * (zb - z1) / (z2 - z1)
            elements.append(box(texture, 0.0, THICK, y1, y2, za, zb, ua, ub, 16 - y2, 16 - y1, flip))

    # The handle, on the bottom row only, standing up into the block above so it is one piece:
    # its lower pixel from the bottom sheet, the rest from the top sheet's last rows.
    if handle:
        z1, z2 = (16 - HANDLE_U[1], 16 - HANDLE_U[0]) if flip else HANDLE_U
        # The box flips the sheet again for a flipped leaf, so it is handed the mirrored
        # columns and lands back on the handle's own. Handed the handle's columns outright it
        # sampled the hinge side of the sheet: plain wood where the knob should be.
        u1, u2 = (16 - HANDLE_U[1], 16 - HANDLE_U[0]) if flip else HANDLE_U
        elements.append(box("#bottom", -HANDLE_PROUD, THICK + HANDLE_PROUD, 15.0, 16.0, z1, z2,
                            u1, u2, 0.0, 1.0, flip, edge_uv=HANDLE_U))
        elements.append(box("#top", -HANDLE_PROUD, THICK + HANDLE_PROUD, 16.0, 18.0, z1, z2,
                            u1, u2, 14.0, 16.0, flip, edge_uv=HANDLE_U))
    return seamless(elements, half, low_stile, high_stile)


def flags_name(low_stile, high_stile, top_rail, bottom_rail, handle):
    name = "".join(c for c, on in (("l", low_stile), ("r", high_stile), ("t", top_rail),
                                   ("b", bottom_rail), ("h", handle)) if on)
    return name or "x"


def combos(half):
    for low, high in itertools.product((False, True), repeat=2):
        if half == "top":
            for top in (False, True):
                yield low, high, top, False, False
        else:
            for bottom, handle in itertools.product((False, True), repeat=2):
                yield low, high, False, bottom, handle


def write(path, model):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(model, f, separators=(",", ":"))
        f.write("\n")


def prune():
    """Remove the full doors' mega templates, under any name they have had, before writing."""
    for file in os.listdir(FULL_TEMPLATES):
        if re.match(r"(template_)?full_(glass_)?door_mega_.*\.json$", file):
            os.remove(os.path.join(FULL_TEMPLATES, file))


def main():
    prune()
    templates = 0
    names = []
    for half in ("bottom", "top"):
        for hinge in ("left", "right"):
            for openness in ("", "_open"):
                flip = (hinge == "right") != (openness == "_open")
                for low, high, top, bottom, handle in combos(half):
                    name = "door_mega_%s_%s%s_%s" % (half, hinge, openness,
                                                     flags_name(low, high, top, bottom, handle))
                    texture = "#bottom_plain" if half == "bottom" else "#top_plain"
                    families = [(TEMPLATES, "", None)] + [
                        (FULL_TEMPLATES, prefix, kind) for kind, prefix in FULL_PREFIXES.items()]
                    for folder, prefix, kind in families:
                        write(os.path.join(folder, prefix + name + ".json"), {
                            "ambientocclusion": False,
                            "textures": {"particle": texture},
                            "elements": leaf(half, flip, low, high, top, bottom, handle, kind)})
                        templates += 1
                    names.append(name)

    # Every door's own copies: one line each, naming its sheets.
    doors = []
    with zipfile.ZipFile(find_jar()) as jar:
        for entry in jar.namelist():
            if entry.startswith("assets/minecraft/blockstates/") and entry.endswith("_door.json"):
                door = entry[len("assets/minecraft/blockstates/"):-len(".json")]
                # Through the blockstate, not by name: a waxed copper door has no models of its
                # own and points at the plain copper door's.
                variants = json.loads(jar.read(entry))["variants"]
                first = next(iter(variants.values()))
                model_id = (first[0] if isinstance(first, list) else first)["model"].split(":", 1)[-1]
                model = json.loads(jar.read("assets/minecraft/models/%s.json" % model_id))
                doors.append(("minecraft", door, model["textures"]))
    own = os.path.join(ASSETS, MOD_ID, "models/block")
    for file in sorted(os.listdir(own)):
        if file.endswith("_door_bottom_left.json") and not file.startswith("template_"):
            door = file[:-len("_bottom_left.json")]
            model = json.load(open(os.path.join(own, file)))
            doors.append((MOD_ID, door, model["textures"]))

    copies = 0
    for namespace, door, textures in doors:
        kind = full_kind(door)
        template = "%s:block/%s" % (MOD_ID, FULL_PREFIXES[kind]) if kind else "minecraft:block/"
        for name in names:
            plain = "%s:block/plain/%s/%s" % (MOD_ID, namespace, door)
            write(os.path.join(ASSETS, namespace, "models/block", door + name[len("door"):] + ".json"),
                  {"parent": template + name,
                   "textures": {"bottom": textures["bottom"], "top": textures["top"],
                                "bottom_plain": plain + "_bottom", "top_plain": plain + "_top"}})
            copies += 1
    print("wrote %d mega door templates and %d copies for %d doors" % (templates, copies, len(doors)))


if __name__ == "__main__":
    main()
