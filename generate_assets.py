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
MINECRAFT_DATA = os.path.join(HERE, "src/main/resources/data/minecraft")

# Kept in step with DoorMaterials.java by hand, which is the one duplication here: the mod reads
# these off the block registry at runtime and a build script cannot.
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "pale_oak", "poplar",
         "mangrove", "cherry", "bamboo", "crimson", "warped"]
MATERIALS = WOODS

# prefix -> whether it is the one that shuts properly, with no opening in it
VARIANTS = {"": True, "classic_": False, "glass_": False, "barred_": False,
            "full_glass_": False, "full_barred_": False}

# A trapdoor is too short for a window to be anything but the whole of it, so the full-height
# glass and bars were the same trapdoor twice. Retired: still registered, because some are
# already built into the world and carried about, but made no more, and each drops the one it
# duplicated when it is broken, so the world trades them in by itself.
RETIRED_TRAPDOORS = {"full_glass_": "glass_", "full_barred_": "barred_"}

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


def loot(name, drops=None):
    """A block drops itself. A door is two blocks and one drop: only its lower half pays out.

    The shape is the game's own for its doors: one condition object per pool or entry, and the
    half read with match_block. A conditions list, the older shape, loads without complaint and
    without effect, and a door that way drops one per half.
    """
    entry = {"type": "minecraft:item", "name": "%s:%s" % (NS, drops or name)}
    if name.endswith("_door"):
        entry = {"type": "minecraft:item",
                 "condition": {"type": "minecraft:match_block", "blocks": "%s:%s" % (NS, name),
                               "state": {"half": "lower"}},
                 "name": "%s:%s" % (NS, name)}
    write("%s/loot_table/blocks/%s.json" % (DATA, name), {
        "type": "minecraft:block",
        "pools": [{
            "condition": {"type": "minecraft:survives_explosion"},
            "entries": [entry],
            "rolls": 1,
        }],
        "random_sequence": "%s:blocks/%s" % (NS, name),
    })


def tags(names):
    """The game's own tags, so its doors and trapdoors are doors and trapdoors to the game too:
    an axe is the tool for them, and anything that looks for a door - a villager, a zombie, a
    command - finds them."""
    doors = [n for n in names if n.endswith("_door")]
    trapdoors = [n for n in names if n.endswith("_trapdoor")]
    for kind in ("block", "item"):
        for tag, values in (("doors", doors), ("wooden_doors", doors),
                            ("trapdoors", trapdoors), ("wooden_trapdoors", trapdoors)):
            write("%s/tags/%s/%s.json" % (MINECRAFT_DATA, kind, tag),
                  {"replace": False, "values": ["%s:%s" % (NS, n) for n in values]})
    write("%s/tags/block/mineable/axe.json" % MINECRAFT_DATA,
          {"replace": False, "values": ["%s:%s" % (NS, n) for n in names]})


def advancement(name):
    """The recipe's unlock: the game lays a recipe out for a player only once they know it, and
    a recipe learns itself when its ingredients are first held. Without this file that never
    happens, and the recipe book shows the door and refuses to place it."""
    recipe_path = "%s/recipe/%s.json" % (DATA, name)
    if not os.path.exists(recipe_path):
        return
    with open(recipe_path) as f:
        recipe = json.load(f)
    recipe_id = "%s:%s" % (NS, name)
    criteria = {"has_the_recipe": {"conditions": {"recipes": recipe_id},
                                   "trigger": "minecraft:recipe_unlocked"}}
    for item in sorted({v if isinstance(v, str) else v.get("item", "") for v in recipe.get("key", {}).values()}):
        if not item:
            continue
        criteria["has_" + item.split(":")[-1]] = {"conditions": {"items": [{"items": item}]},
                                                  "trigger": "minecraft:inventory_changed"}
    write("%s/advancement/recipes/%s/%s.json" % (DATA, recipe.get("category", "misc"), name), {
        "parent": "minecraft:recipes/root",
        "criteria": criteria,
        "requirements": [list(criteria.keys())],
        "rewards": {"recipes": [recipe_id]},
    })


def build():
    made = 0
    names = []
    for material in MATERIALS:
        for prefix in VARIANTS:
            door = "%s_%sdoor" % (material, prefix)
            trap = "%s_%strapdoor" % (material, prefix)
            names += [door, trap]

            door_models(door)
            door_blockstate(door)
            item(door, {"parent": "minecraft:item/generated",
                        "textures": {"layer0": "%s:item/%s" % (NS, door)}})
            loot(door)
            advancement(door)

            trapdoor_models(trap)
            trapdoor_blockstate(trap)
            item(trap, {"parent": "%s:block/%s_bottom" % (NS, trap)})
            if prefix in RETIRED_TRAPDOORS:
                loot(trap, "%s_%strapdoor" % (material, RETIRED_TRAPDOORS[prefix]))
            else:
                loot(trap)
                advancement(trap)
            made += 2
    tags(names)
    print("generated assets for %d blocks across %d materials" % (made, len(MATERIALS)))


if __name__ == "__main__":
    build()
