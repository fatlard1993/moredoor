#!/usr/bin/env python3
"""Generate the fence gate models Pandorical's client picks by a gate's surroundings.

Vanilla's gate is a frame with a post at each end and a latch pair in the middle. Two of
them side by side are four posts in a row and two latches, which is two gates. Joined, the
post on the shared side goes, the bars run to the edge, and the latch goes with them: the
left gate keeps its left post and the right gate its right, and between them is one clear
span. Stacked on another gate, the posts run down to meet the gate below, so a tall gate has
posts the whole way.

Open, a gate hung from one post is one leaf (swing). A wide gate opens by moving its blocks out
along the leaf, the way the mega doors do, and every block draws its own piece of it (wide): the
hinge block its post and the start of the leaf, the blocks swung out a length of rail each, and
the last the tip with the leaf's end post.

Names, left and right always the gate's own, looking the way it faces:

  <gate>[_wall]_join_<left|right|both>[_stacked]   shut, joined on that side (its post gone)
  <gate>[_wall]_join_none_stacked                  shut, only stacked
  <gate>[_wall]_open_join_none_stacked             open and stacked, nothing beside it
  <gate>[_wall]_open_swing_<left|right>[_stacked]  open, one leaf from that post
  <gate>[_wall]_open_wide_<left|right>_<piece>[_stacked]
      piece: hinge (post and the leaf's first 7), hinge_tip (post and a whole 14 leaf),
             seg (a block of rail), tip (rail to 23 and the end post)

Every model faces south, as vanilla's templates do; the client turns it to the gate's facing.
All of it is derived from vanilla's own templates and textures, read out of the jar, and the
script removes the gate models it no longer writes. Deterministic.
Usage: python3 generate_gate_models.py [jar]
"""
import glob
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src/main/resources/assets/minecraft/models/block")

POST_NEG = 0    # the post on the x=0 side of the template
POST_POS = 14   # the post on the x=14..16 side
JOINS = {"left": ("pos",), "right": ("neg",), "both": ("neg", "pos"), "none": ()}
# A gate's posts stand from y=5 (2 in a wall); stacked on another gate they reach down to meet its posts.
POST_FOOT = 5
# The open template's leaf: rails from the post's face at z=9, ending in a post at z=13..15.
LEAF_START = 9
LEAF_END_POST = 13
# Every gate model this script writes, so the ones it stops writing can be found and removed.
OWN = re.compile(r".*_fence_gate(_wall)?(_open)?_(join|swing|wide|post|leaf)_.*\.json$")


def minecraft_version():
    for line in open(os.path.join(HERE, "gradle.properties")):
        key, sep, value = line.partition("=")
        if sep and key.strip() == "minecraft_version":
            return value.strip()
    return None


def find_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    cache = os.path.expanduser("~/.gradle/caches/fabric-loom")
    found = []
    version = minecraft_version()
    for name in ("minecraft-client.jar", "minecraft-merged.jar"):
        if version:
            found += glob.glob(os.path.join(cache, version, name))
    if not found:
        for name in ("minecraft-client.jar", "minecraft-merged.jar"):
            found += glob.glob(os.path.join(cache, "*", name))
    if not found:
        sys.exit("no cached Minecraft jar found: build once, or pass a jar path")
    return max(found, key=os.path.getmtime)


def load(jar, path):
    return json.loads(jar.read("assets/minecraft/" + path).decode())


def gates(jar):
    """Every fence gate the jar has a blockstate for, with the texture its model names."""
    for name in jar.namelist():
        if name.startswith("assets/minecraft/blockstates/") and name.endswith("_fence_gate.json"):
            gate = os.path.basename(name)[:-5]
            model = load(jar, "models/block/" + gate + ".json")
            yield gate, model["textures"]["texture"]


def joined(template, drop, is_open):
    """The template with the posts on the dropped sides gone. Closed, the bars run to that
    edge and the middle latch goes. Open, only an unjoined gate comes here, and it is the
    template as it is."""
    kept = []
    for element in template["elements"]:
        x0, x1 = element["from"][0], element["to"][0]
        if is_post(element) and post_side(element) in drop:
            continue
        if is_open or not drop:
            pass  # joined to nothing beside: vanilla's own latch and bars stay
        elif not is_open:
            if 6 <= x0 < 10 and x1 - x0 == 2:
                continue  # the latch pair
            if x1 - x0 == 4:
                # The bars: one clear span per height, edge to edge on a dropped side. The
                # template has two per height either side of the latch; the first becomes the
                # span and the second is dropped.
                if x0 != 2:
                    continue
                e = json.loads(json.dumps(element))
                e["from"][0] = 0 if "neg" in drop else 2
                e["to"][0] = 16 if "pos" in drop else 14
                kept.append(e)
                continue
        kept.append(element)
    out = json.loads(json.dumps(template))
    out["elements"] = kept
    return out


# A gate's bars sit at 6 and 12 with the frame's foot at 5. Stacked, the upper gate keeps that
# rhythm going: bars at 2 and 8 in its own block are 18 and 24 in the world, three clear
# between every pair the whole way up. Left where they were, the two gates read as two
# gates: a bar at 15 met a bar at 22 across a gap twice the others.
STACK_SHIFT = 4


def stacked(model):
    """The model as the upper storey of a tall gate: posts run down to the floor to meet the
    gate below, the bars and latch carried down to keep the gate's rhythm, and the latch run
    a pixel past the floor so it meets the latch below without a seam."""
    out = json.loads(json.dumps(model))
    for e in out["elements"]:
        if is_post(e):
            e["from"][1] -= POST_FOOT
            continue
        e["from"][1] -= STACK_SHIFT
        e["to"][1] -= STACK_SHIFT
        if e["to"][1] - e["from"][1] == 9:  # a latch post, or the end post of a swung leaf
            e["from"][1] = -1
    return out


def single_leaf(template, hinge):
    """The open template as one leaf hung from one post: both posts kept, the stub that
    swung from the other post gone, and the kept one run the width of the gate."""
    other = "pos" if hinge == "neg" else "neg"
    kept = []
    for element in template["elements"]:
        if is_post(element):
            kept.append(element)
            continue
        if leaf_side(element) == other:
            continue
        e = json.loads(json.dumps(element))
        if element["from"][2] == 13:
            e["from"][2] = 9 + 14 - 2
            e["to"][2] = 9 + 14
        else:
            e["to"][2] = 9 + 14
        kept.append(e)
    out = json.loads(json.dumps(template))
    out["elements"] = kept
    return out


def is_post(element):
    x0, x1 = element["from"][0], element["to"][0]
    return x1 - x0 == 2 and x0 in (POST_NEG, POST_POS) and element["from"][2] == 7


def post_side(element):
    return "neg" if element["from"][0] == POST_NEG else "pos"


def leaf_side(element):
    return "neg" if element["from"][0] < 8 else "pos"


# A wide gate's pieces: (kept post, where the rails start and end, whether the end post is drawn).
# The leaf of a gate swung out over m blocks runs from the post to 16m + 7, so the last block's
# rail reaches 23, seven past its own square, as a single gate's hung leaf does.
WIDE_PIECES = {
    "hinge": (True, LEAF_START, 16, False),
    "hinge_tip": (True, LEAF_START, 23, True),
    "seg": (False, 0, 16, False),
    "tip": (False, 0, 23, True),
}


def wide_piece(template, post, piece):
    """One block's share of a wide gate's leaf, which swings from this side's post."""
    keep_post, start, end, tipped = WIDE_PIECES[piece]
    kept = []
    for element in template["elements"]:
        if is_post(element):
            if keep_post and post_side(element) == post:
                kept.append(element)
            continue
        if element["from"][2] < LEAF_START or leaf_side(element) != post:
            continue
        e = json.loads(json.dumps(element))
        if element["from"][2] == LEAF_END_POST:
            if not tipped:
                continue
            e["from"][2] = end - 2
            e["to"][2] = end
        else:
            e["from"][2] = start
            e["to"][2] = end
        kept.append(e)
    out = json.loads(json.dumps(template))
    out["elements"] = kept
    return out


# The gate's own left is its counter-clockwise side looking the way it faces, which in the
# template is the post at x=14; its right is the post at x=0.
SWINGS = {"left": "pos", "right": "neg"}


def prune():
    """Remove every gate model this script writes, so none it has stopped writing is left."""
    for name in os.listdir(OUT):
        if OWN.match(name):
            os.remove(os.path.join(OUT, name))


def write(name, model, texture):
    model["textures"] = {"particle": texture, "texture": texture}
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(model, f, separators=(",", ":"))


def main():
    with zipfile.ZipFile(find_jar()) as jar:
        templates = {
            "": load(jar, "models/block/template_fence_gate.json"),
            "_open": load(jar, "models/block/template_fence_gate_open.json"),
            "_wall": load(jar, "models/block/template_fence_gate_wall.json"),
            "_wall_open": load(jar, "models/block/template_fence_gate_wall_open.json"),
        }
        prune()
        models = {}
        for state, template in templates.items():
            is_open = state.endswith("_open")
            for join, drop in JOINS.items():
                # Open beside another, a gate is a wide one, drawn from the pieces below.
                if is_open and join != "none":
                    continue
                base = joined(template, drop, is_open)
                for on_another in (False, True):
                    # Joined to nothing beside and nothing below is vanilla's own model.
                    if join == "none" and not on_another:
                        continue
                    models[state + "_join_" + join + ("_stacked" if on_another else "")] = (
                        stacked(base) if on_another else base)
        for state in ("_open", "_wall_open"):
            for side, post in SWINGS.items():
                pieces = {"_swing_" + side: single_leaf(templates[state], post)}
                for piece in WIDE_PIECES:
                    pieces["_wide_%s_%s" % (side, piece)] = wide_piece(templates[state], post, piece)
                for kind, base in pieces.items():
                    for on_another in (False, True):
                        models[state + kind + ("_stacked" if on_another else "")] = (
                            stacked(base) if on_another else base)
        count = 0
        for gate, texture in gates(jar):
            for suffix, model in models.items():
                write(gate + suffix, json.loads(json.dumps(model)), texture)
                count += 1
        print("wrote %d fence gate models" % count)


if __name__ == "__main__":
    main()
