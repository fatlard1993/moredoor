#!/usr/bin/env python3
"""Generate every blockstate, model, item definition, loot table and recipe this mod needs.

There are 22 materials and three variants of each, in doors and trapdoors, which
is 132 blocks. Hand-writing their assets would be about fifteen hundred files
that all say the same thing in slightly different words, and the first typo would
be invisible until somebody placed that one door.

So they are generated from one description of what a variant is, and the vanilla
model shapes are reused rather than redrawn: a door is vanilla's door with our
textures on it, which means a resource pack that moves vanilla's geometry moves
ours too.

Usage: python3 generate_assets.py
"""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
NS = "more-doors-justfatlard"
ASSETS = os.path.join(HERE, "src/main/resources/assets", NS)
DATA = os.path.join(HERE, "src/main/resources/data", NS)

# Kept in step with DoorMaterials.java by hand, which is the one duplication here: the mod reads
# these off the block registry at runtime and a build script cannot.
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "pale_oak", "poplar",
         "mangrove", "cherry", "bamboo", "crimson", "warped"]
MATERIALS = WOODS

# prefix -> whether it is the one that shuts properly, with no opening in it
VARIANTS = {"": True, "classic_": False, "glass_": False, "barred_": False,
            "full_glass_": False, "full_barred_": False}

DOOR_HALVES = ["bottom", "top"]
DOOR_HINGES = ["left", "right"]
DOOR_OPEN = ["", "_open"]

# Vanilla's own door blockstate, which every door in the game shares the shape of.
DOOR_TURNS = {"east": 0, "south": 90, "west": 180, "north": 270}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def door_models(name):
    """The eight shapes a door is drawn in, each vanilla's wearing our two textures."""
    for half in DOOR_HALVES:
        for hinge in DOOR_HINGES:
            for openness in DOOR_OPEN:
                write("%s/models/block/%s_%s_%s%s.json" % (ASSETS, name, half, hinge, openness), {
                    "parent": "minecraft:block/door_%s_%s%s" % (half, hinge, openness),
                    "textures": {
                        "bottom": "%s:block/%s_bottom" % (NS, name),
                        "top": "%s:block/%s_top" % (NS, name),
                    },
                })


def door_blockstate(name):
    """Facing, half, hinge and open: the same thirty-two vanilla writes out."""
    variants = {}
    for facing, turn in DOOR_TURNS.items():
        for half in DOOR_HALVES:
            for hinge in DOOR_HINGES:
                for openness in DOOR_OPEN:
                    key = "facing=%s,half=%s,hinge=%s,open=%s" % (
                        facing, "lower" if half == "bottom" else "upper", hinge,
                        "true" if openness else "false")

                    # An open door swings a quarter turn, and which way depends on its hinge.
                    swing = 0
                    if openness:
                        swing = 90 if hinge == "left" else 270

                    entry = {"model": "%s:block/%s_%s_%s%s" % (NS, name, half, hinge, openness)}
                    y = (turn + swing) % 360
                    if y:
                        entry["y"] = y
                    variants[key] = entry
    write("%s/blockstates/%s.json" % (ASSETS, name), {"variants": variants})


def trapdoor_models(name):
    for shape in ["bottom", "top", "open"]:
        write("%s/models/block/%s_%s.json" % (ASSETS, name, shape), {
            "parent": "minecraft:block/template_orientable_trapdoor_%s" % shape,
            "textures": {"texture": "%s:block/%s" % (NS, name)},
        })


def trapdoor_blockstate(name):
    variants = {}
    for facing, turn in {"north": 0, "south": 180, "east": 90, "west": 270}.items():
        for half in ["bottom", "top"]:
            for openness in ["true", "false"]:
                shape = "open" if openness == "true" else half
                entry = {"model": "%s:block/%s_%s" % (NS, name, shape)}
                y = turn
                if openness == "true" and half == "top":
                    entry["x"] = 180
                    y = (turn + 180) % 360
                if y:
                    entry["y"] = y
                variants["facing=%s,half=%s,open=%s" % (facing, half, openness)] = entry
    write("%s/blockstates/%s.json" % (ASSETS, name), {"variants": variants})


def item(name, model):
    write("%s/models/item/%s.json" % (ASSETS, name), model)
    write("%s/items/%s.json" % (ASSETS, name),
          {"model": {"type": "minecraft:model", "model": "%s:item/%s" % (NS, name)}})


def loot(name):
    """A door drops itself; the two halves are one drop, which vanilla handles by the half check."""
    write("%s/loot_table/blocks/%s.json" % (DATA, name), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1.0,
            "condition": {"type": "minecraft:survives_explosion"},
            "entries": [{"type": "minecraft:item", "name": "%s:%s" % (NS, name)}],
        }],
        "random_sequence": "%s:blocks/%s" % (NS, name),
    })


def build():
    made = 0
    for material in MATERIALS:
        for prefix in VARIANTS:
            door = "%s_%sdoor" % (material, prefix)
            trap = "%s_%strapdoor" % (material, prefix)

            door_models(door)
            door_blockstate(door)
            item(door, {"parent": "minecraft:item/generated",
                        "textures": {"layer0": "%s:item/%s" % (NS, door)}})
            loot(door)

            trapdoor_models(trap)
            trapdoor_blockstate(trap)
            item(trap, {"parent": "%s:block/%s_bottom" % (NS, trap)})
            loot(trap)
            made += 2
    print("generated assets for %d blocks across %d materials" % (made, len(MATERIALS)))


if __name__ == "__main__":
    build()
