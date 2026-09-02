#!/usr/bin/env python3
"""Generate every recipe this mod needs.

The shape of a door is decided at the crafting table by what you leave out of it.
Six planks is a door with no opening; take one out of the middle and you get the
opening; put glass or bars where you took it from and the opening is filled. One
pattern, four outcomes, and nothing to look up.

The plain six-plank recipe is written at vanilla's own recipe id, which replaces
it. That is deliberate: the default door a wood makes should be the one that
actually shuts, and vanilla's own face stays reachable on the stonecutter rather
than staying the only thing the wood can be.

Usage: python3 generate_recipes.py
"""

import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
NS = "more-doors-justfatlard"
DATA = os.path.join(HERE, "src/main/resources/data")

WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "pale_oak", "poplar",
         "mangrove", "cherry", "bamboo", "crimson", "warped"]

# What goes in the hole. One of them goes in the middle; three of them down one side glaze or bar
# the door the whole way down instead.
FILLS = {
    "classic_": None,
    "glass_": "minecraft:glass_pane",
    "barred_": "minecraft:iron_bars",
}

FULL_FILLS = {
    "full_glass_": "minecraft:glass_pane",
    "full_barred_": "minecraft:iron_bars",
}


def write(namespace, folder, name, data):
    path = os.path.join(DATA, namespace, folder, name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")


def shaped(pattern, keys, result, count=1, category="building"):
    return {"type": "minecraft:crafting_shaped", "category": category,
            "pattern": pattern, "key": keys,
            "result": {"id": result, "count": count}}


def tag(identifier):
    """A filename-safe name for one ingredient, keeping its namespace."""
    namespace, path = identifier.split(":")
    return ("mc" if namespace == "minecraft" else "md") + "_" + path


def cut(source, result):
    return {"type": "minecraft:stonecutting",
            "ingredient": source, "result": {"id": result, "count": 1}}


def build():
    made = 0
    for wood in WOODS:
        planks = "minecraft:%s_planks" % wood
        solid_door = "%s:%s_door" % (NS, wood)
        solid_trap = "%s:%s_trapdoor" % (NS, wood)

        # The plain recipe, written over vanilla's so the default door is the solid one.
        write("minecraft", "recipe", "%s_door" % wood,
              shaped(["##", "##", "##"], {"#": planks}, solid_door, count=3, category="redstone"))
        write("minecraft", "recipe", "%s_trapdoor" % wood,
              shaped(["###", "###"], {"#": planks}, solid_trap, count=2, category="redstone"))
        made += 2

        for prefix, fill in FILLS.items():
            door = "%s:%s_%sdoor" % (NS, wood, prefix)
            trap = "%s:%s_%strapdoor" % (NS, wood, prefix)

            # Leave the middle out, and put the filling back in its place.
            keys = {"#": planks} if fill is None else {"#": planks, "o": fill}
            middle = " " if fill is None else "o"

            write(NS, "recipe", "%s_%sdoor" % (wood, prefix),
                  shaped(["##", middle + "#", "##"], keys, door, count=3, category="redstone"))
            write(NS, "recipe", "%s_%strapdoor" % (wood, prefix),
                  shaped(["###", "#" + middle + "#"], keys, trap, count=2, category="redstone"))
            made += 2

        # Three down one side, and the opening runs the height of the door.
        for prefix, fill in FULL_FILLS.items():
            keys = {"#": planks, "o": fill}
            write(NS, "recipe", "%s_%sdoor" % (wood, prefix),
                  shaped(["#o", "#o", "#o"], keys, "%s:%s_%sdoor" % (NS, wood, prefix),
                         count=3, category="redstone"))
            write(NS, "recipe", "%s_%strapdoor" % (wood, prefix),
                  shaped(["###", "ooo"], keys, "%s:%s_%strapdoor" % (NS, wood, prefix),
                         count=2, category="redstone"))
            made += 2

        # The stonecutter carries a wood between the faces that cost no extra material,
        # vanilla's own among them - which is how a vanilla-faced door is still had at all
        # once the plain recipe stops making one.
        faces = [solid_door, "%s:%s_classic_door" % (NS, wood), "minecraft:%s_door" % wood]
        traps = [solid_trap, "%s:%s_classic_trapdoor" % (NS, wood), "minecraft:%s_trapdoor" % wood]

        for group, kind in ((faces, "door"), (traps, "trapdoor")):
            for source in group:
                for result in group:
                    if source == result:
                        continue
                    # Tagged by namespace: vanilla's door and ours share a path, and a name
                    # built from the path alone would have one direction overwrite the other.
                    name = "cut_%s_to_%s" % (tag(source), tag(result))
                    write(NS, "recipe", name, cut(source, result))
                    made += 1

    print("generated %d recipes across %d woods" % (made, len(WOODS)))


if __name__ == "__main__":
    build()
