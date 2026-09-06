#!/usr/bin/env python3
"""Generate the jamb models: a door with a fence post where a fence meets it.

A fence arm runs down the middle of its block and stops at the boundary; a closed door is a
panel against the far edge of its block. Between them is a gap the width of half a block,
and it shows. The jamb is what a carpenter would put there: a post at the door block's edge
where the arm arrives, and two short rails from that post across to the panel. Each half of
the door gets the same piece, and the client shows the upper one only where a fence stands
beside the upper half too, so the jamb is as tall as the fence and no taller.

The post and rails are textured the way vanilla's fence post and arms are, from the same
strips of the plank sheet, so a jamb standing next to a fence of the same wood is the fence
continued rather than a different-looking piece of the same planks.

One model per door, per half, per hinge, per open state, per side jambed (left, right, both,
looking the way the door faces), per facing: Pandorical's client picks them from the
neighbours at render time, with no blockstate rotation to lean on, so the door's own elements
are inlined from this mod's door templates and turned by the rotation vanilla's blockstate
gives that state. The jamb wears the planks of the door's wood. Deterministic.
Usage: python3 generate_door_jambs.py [jar]
"""
import glob
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "src/main/resources/assets/minecraft/models/block")

# Post four square at the block's edge on the fence side, rails two wide at the fence's rail
# heights from the post across to the panel. Model space is facing east, closed: panel at x 0..3.
POST = (6, 10, 0, 16, 0, 4)
RAILS = ((3, 6, 6, 9, 1, 3), (3, 6, 12, 15, 1, 3))
FACING_TURNS = {"east": 0, "south": 1, "west": 2, "north": 3}
FACES = ["north", "east", "south", "west"]

# Doors that get a jamb, and what the jamb is made of.
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "pale_oak", "poplar", "crimson", "warped"]


def jamb_texture(door):
    if door == "iron_door":
        return "minecraft:block/iron_block"
    return "minecraft:block/" + door[:-len("_door")] + "_planks"


def minecraft_version():
    for line in open(os.path.join(HERE, "gradle.properties")):
        key, sep, value = line.partition("=")
        if sep and key.strip() == "minecraft_version":
            return value.strip()


def find_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    cache = os.path.expanduser("~/.gradle/caches/fabric-loom")
    found = []
    for name in ("minecraft-client.jar", "minecraft-merged.jar"):
        found += glob.glob(os.path.join(cache, minecraft_version() or "*", name))
    if not found:
        sys.exit("no cached Minecraft jar found: build once, or pass a jar path")
    return max(found, key=os.path.getmtime)


def cube(texture, box, uvs):
    """A box with each face reading its own strip of the sheet, the way vanilla's fence does."""
    x0, x1, y0, y1, z0, z1 = box
    return {"from": [x0, y0, z0], "to": [x1, y1, z1],
            "faces": {f: {"uv": list(uvs[f]), "texture": texture}
                      for f in ("north", "south", "east", "west", "up", "down")}}


# Vanilla's fence post reads the middle strip of the sheet on every side and a square of it
# on the ends; its arms read a run along a plank, each bar at its own rows. The same here,
# with the rails running along x rather than z.
POST_UVS = {f: (6, 0, 10, 16) for f in ("north", "south", "east", "west")} | {"up": (6, 6, 10, 10), "down": (6, 6, 10, 10)}


def rail_uvs(rows):
    v0, v1 = rows
    return {"north": (0, v0, 3, v1), "south": (0, v0, 3, v1),
            "east": (7, v0, 9, v1), "west": (7, v0, 9, v1),
            "up": (3, 7, 6, 9), "down": (3, 7, 6, 9)}


RAIL_UVS = (rail_uvs((7, 10)), rail_uvs((1, 4)))


def rotate(elements, quarter_turns):
    """Clockwise about the block centre, the way a blockstate y rotation turns a model."""
    out = json.loads(json.dumps(elements))
    for _ in range(quarter_turns):
        for e in out:
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            e["from"] = [16 - z1, y0, x0]
            e["to"] = [16 - z0, y1, x1]
            e["faces"] = {(FACES[(FACES.index(k) + 1) % 4] if k in FACES else k): v for k, v in e["faces"].items()}
            for face in e["faces"].values():
                if "cullface" in face and face["cullface"] in FACES:
                    face["cullface"] = FACES[(FACES.index(face["cullface"]) + 1) % 4]
    return out


def jamb(side):
    boxes = [cube("#jamb", POST, POST_UVS)]
    boxes += [cube("#jamb", r, uvs) for r, uvs in zip(RAILS, RAIL_UVS)]
    if side == "right":
        # mirror across the block: z -> 16 - z
        for e in boxes:
            z0, z1 = e["from"][2], e["to"][2]
            e["from"][2], e["to"][2] = 16 - z1, 16 - z0
    return boxes


def main():
    with zipfile.ZipFile(find_jar()) as jar:
        templates = {name: json.load(open(os.path.join(MODELS, name + ".json")))["elements"]
                     for name in ("door_bottom_left", "door_bottom_left_open", "door_bottom_right", "door_bottom_right_open",
                                  "door_top_left", "door_top_left_open", "door_top_right", "door_top_right_open")}
        count = 0
        for door in [w + "_door" for w in WOODS] + ["iron_door"]:
            variants = json.loads(jar.read("assets/minecraft/blockstates/%s.json" % door))["variants"]
            textures = json.loads(jar.read("assets/minecraft/models/block/%s_bottom_left.json" % door))["textures"]
            for key, variant in variants.items():
                props = dict(kv.split("=") for kv in key.split(","))
                half, hinge, is_open, facing = props["half"], props["hinge"], props["open"] == "true", props["facing"]
                template = "door_" + ("bottom" if half == "lower" else "top") + "_" + hinge + ("_open" if is_open else "")
                door_elements = rotate(templates[template], (variant.get("y", 0) // 90) % 4)
                for side in ("left", "right", "both"):
                    sides = ("left", "right") if side == "both" else (side,)
                    jambs = []
                    for s in sides:
                        jambs += rotate(jamb(s), FACING_TURNS[facing])
                    model = {"ambientocclusion": False,
                             "textures": {"bottom": textures["bottom"], "top": textures["top"],
                                          "jamb": jamb_texture(door), "particle": textures["bottom"]},
                             "elements": door_elements + jambs}
                    name = "%s_%s_%s%s_jamb_%s_%s" % (door, half, hinge, "_open" if is_open else "", side, facing)
                    with open(os.path.join(MODELS, name + ".json"), "w") as f:
                        json.dump(model, f, separators=(",", ":"))
                    count += 1
        print("wrote %d jamb models" % count)


if __name__ == "__main__":
    main()
