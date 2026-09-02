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

# The door occupies the first three pixels of its block, hinge edge at x=0.
THICK = 3.0
# The panel is recessed a pixel from each face, and the frame is what is left around it.
PANEL_INSET = 1.0
FRAME = 3.0


def face(texture, u1, v1, u2, v2, cull=None):
    f = {"uv": [u1, v1, u2, v2], "texture": texture}
    if cull:
        f["cullface"] = cull
    return f


def slab(texture, x1, z1, x2, z2, y1=0.0, y2=16.0, cull=True):
    """One box of door, textured as vanilla textures a door: the wide faces read the sheet."""
    return {
        "from": [x1, y1, z1],
        "to": [x2, y2, z2],
        "faces": {
            "north": face(texture, 16 - z2, 16 - y2, 16 - z1, 16 - y1, "north" if cull else None),
            "south": face(texture, z1, 16 - y2, z2, 16 - y1, "south" if cull else None),
            "west": face(texture, x1, 16 - y2, x2, 16 - y1, "west" if cull else None),
            "east": face(texture, 16 - x2, 16 - y2, 16 - x1, 16 - y1, "east" if cull else None),
            "up": face(texture, x1, z1, x2, z2),
            "down": face(texture, x1, 16 - z2, x2, 16 - z1),
        },
    }


def leaf(texture, mirrored):
    """One door leaf: a recessed panel, a frame around it, and a handle on the swinging edge."""
    # The panel: the full sheet, set back a pixel from both faces.
    elements = [slab(texture, PANEL_INSET, 0.0, THICK - PANEL_INSET, 16.0, cull=False)]

    # The frame: four bars at full thickness around the edge, so the panel sits in a rebate.
    elements.append(slab(texture, 0.0, 0.0, THICK, FRAME))
    elements.append(slab(texture, 0.0, 16.0 - FRAME, THICK, 16.0))
    elements.append(slab(texture, 0.0, FRAME, THICK, 16.0 - FRAME, 0.0, FRAME))
    elements.append(slab(texture, 0.0, FRAME, THICK, 16.0 - FRAME, 16.0 - FRAME, 16.0))

    # The handle, on the edge away from the hinge, standing a pixel proud of the frame.
    handle_z = 12.0 if not mirrored else 1.0
    elements.append({
        "from": [-1.0, 8.0, handle_z],
        "to": [THICK + 1.0, 10.0, handle_z + 3.0],
        "faces": {d: face(texture, 6, 6, 9, 9) for d in
                  ("north", "south", "west", "east", "up", "down")},
    })
    return elements


def build(half, hinge, openness):
    texture = "#bottom" if half == "bottom" else "#top"
    model = {
        "ambientocclusion": False,
        "textures": {"particle": texture},
        "elements": leaf(texture, hinge == "right"),
    }
    return model


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
