#!/usr/bin/env python3
"""Give every door in the game actual depth, by replacing the eight shapes they all share.

Vanilla's door is one flat slab three pixels thick with a picture painted on it.
These are the same eight models with the picture built instead: a frame standing
proud of a recessed panel, and a handle that sticks out far enough to cast a
shadow.

Overriding vanilla's own eight is what makes this reach every door at once - the
twenty-two the game ships and the sixty-six this mod adds, because ours parent
these too. One shape, every material, and a resource pack that retextures doors
still retextures all of them.

Usage: python3 generate_door_models.py
"""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src/main/resources/assets/minecraft/models/block")

# A door leaf lies along z, three pixels deep in x. The wide faces are therefore
# east and west, and the sheet is painted across them with u running along z and
# v down y - exactly as vanilla lays it out.
THICK = 3.0
# The panel is recessed a pixel from each face, and the frame is what is left around it.
PANEL_INSET = 1.0
FRAME = 3.0
# Where the sheet paints the handle: these columns, on the rows either side of the
# seam between the two halves.
HANDLE_U = (11.0, 14.0)
HANDLE_PROUD = 1.0
# How much of the sheet's edge is frame. The thin faces - the door's edges, and the tops and
# bottoms of its frame - read only this much of it. They used to read three columns, and on a
# glass or barred sheet, whose frame is two, the third was window: a stripe of glass or bars up
# every edge of the door. The tops of the stiles read a sixteen-pixel column squashed into a
# three-pixel square. Frame all round is what a frame looks like from the side.
EDGE = 2.0


def face(texture, u1, v1, u2, v2, cull=None, rotation=None):
    f = {"uv": [u1, v1, u2, v2], "texture": texture}
    if cull:
        f["cullface"] = cull
    if rotation:
        f["rotation"] = rotation
    return f


def box(texture, x1, x2, y1, y2, z1, z2, flip, edge_uv=None):
    """One box of door, textured the way vanilla textures a door.

    The wide faces read the sheet at the box's own place on it. Vanilla flips the
    sheet for a right-hung door and again for an open one, so the handle stays on
    the swinging edge whichever way the leaf is turned; `flip` is that. The thin
    faces read the sheet's own edge columns, unless `edge_uv` says otherwise.

    A face is culled by the neighbouring block only when it actually lies on the
    block boundary. Culling an inner face - the side of a stile that looks into
    the recess - would cut a hole in the door whenever it stands beside a wall.
    """
    v1, v2 = 16 - y2, 16 - y1
    # u at z1 and at z2.
    ua, ub = (16 - z1, 16 - z2) if flip else (z1, z2)

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


def leaf(texture, half, flip):
    """One door leaf: a recessed panel, a frame around it, and a handle on the swinging edge."""
    # The panel: the full sheet, set back a pixel from both faces.
    elements = [broad(box(texture, PANEL_INSET, THICK - PANEL_INSET, 0.0, 16.0, 0.0, 16.0, flip))]

    # The frame: two stiles and two rails at full thickness, so the panel sits in a rebate.
    elements.append(box(texture, 0.0, THICK, 0.0, 16.0, 0.0, FRAME, flip))
    elements.append(box(texture, 0.0, THICK, 0.0, 16.0, 16.0 - FRAME, 16.0, flip))
    elements.append(box(texture, 0.0, THICK, 0.0, FRAME, FRAME, 16.0 - FRAME, flip))
    elements.append(box(texture, 0.0, THICK, 16.0 - FRAME, 16.0, FRAME, 16.0 - FRAME, flip))

    # The handle, where the sheet paints it, standing a pixel proud of both faces. It sits
    # on the seam, so each half carries its own part of it: oak paints the knob on the top
    # sheet's last two rows with its shadow on the bottom's first, dark oak one row up from
    # that, and this covers both.
    z1, z2 = (16 - HANDLE_U[1], 16 - HANDLE_U[0]) if flip else HANDLE_U
    y1, y2 = (15.0, 16.0) if half == "bottom" else (0.0, 2.0)
    elements.append(box(texture, -HANDLE_PROUD, THICK + HANDLE_PROUD, y1, y2, z1, z2, flip,
                        edge_uv=HANDLE_U))
    return seamless(elements, half)


def broad(panel):
    """A panel with only its two broad faces.

    Its four thin faces lie in the planes of the frame's own - the stiles' ends, the rails' top
    and bottom - and two faces in one plane flicker against each other. Where there is no frame
    piece they lie on a seam instead. Either way nobody ever sees them.
    """
    for side in ("north", "south", "up", "down"):
        panel["faces"].pop(side, None)
    return panel


def seamless(elements, half, low_stile=True, high_stile=True):
    """Drop the faces that meet another face of the same door.

    At the seam the bottom half's top faces lie against the top half's bottom faces, and where
    one leaf joins another their end faces lie against each other. On a solid sheet they hide
    one another; on a sheet with holes in it - bars, glass - each draws the odd pixel of the
    other's rows as a hairline across the door. Nothing is lost by leaving them out.
    """
    for e in elements:
        faces = e["faces"]
        if half == "bottom" and e["to"][1] == 16.0:
            faces.pop("up", None)
        if half == "top" and e["from"][1] == 0.0:
            faces.pop("down", None)
        if not low_stile and e["from"][2] == 0.0:
            faces.pop("north", None)
        if not high_stile and e["to"][2] == 16.0:
            faces.pop("south", None)
    return elements


def build(half, hinge, openness):
    texture = "#bottom" if half == "bottom" else "#top"
    return {
        "ambientocclusion": False,
        "textures": {"particle": texture},
        "elements": leaf(texture, half, (hinge == "right") != (openness == "_open")),
    }


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    made = 0
    for half in ("bottom", "top"):
        for hinge in ("left", "right"):
            for openness in ("", "_open"):
                name = "door_%s_%s%s" % (half, hinge, openness)
                with open(os.path.join(OUT, name + ".json"), "w") as f:
                    json.dump(build(half, hinge, openness), f, indent=2)
                    f.write("\n")
                made += 1
    print("wrote %d door models (vanilla's own eight, rebuilt with depth)" % made)
