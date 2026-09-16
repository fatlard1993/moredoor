#!/usr/bin/env python3
"""Generate the joined fence gate models: a pair of gates drawn as one wide gate.

Vanilla's gate is a frame with a post at each end and a latch pair in the middle. Two of
them side by side are four posts in a row and two latches, which is two gates. Joined, the
post on the shared side goes, the bars run to the edge, and the latch goes with them: the
left gate keeps its left post and the right gate its right, and between them is one clear
span. Open, each half swings from its own remaining post as a leaf the full width of the
gate, so the pair opens outward from the middle.

One model per gate type, per side joined (left, right, both for a gate between two others, or
none for a gate that is only stacked), stacked on another gate or not - stacked, the posts run
down to meet the gate below, so a tall gate has posts the whole way - per open and in-wall
state, per facing: the models are chosen by Pandorical's
client from the neighbours at render time and cannot lean on a blockstate's rotation, so the
rotation is baked into each file. All of it is derived from vanilla's own templates and
textures, read out of the jar. Deterministic. Usage: python3 generate_gate_models.py [jar]
"""
import glob
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src/main/resources/assets/minecraft/models/block")

POST_NEG = 0    # the post on the x=0 side of the template
POST_POS = 14   # the post on the x=14..16 side
JOINS = {"left": ("pos",), "right": ("neg",), "both": ("neg", "pos"), "none": ()}
# A gate's posts stand from y=5; stacked on another gate they reach down to meet its posts.
POST_FOOT = 5
# facing -> quarter turns clockwise from above, vanilla's own blockstate rotations
FACINGS = {"south": 0, "west": 1, "north": 2, "east": 3}


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
    edge and the middle latch goes. Open, the leaf that swung from a dropped post is gone and
    the one from the kept post runs the full width."""
    kept = []
    for element in template["elements"]:
        x0, x1 = element["from"][0], element["to"][0]
        is_post = x1 - x0 == 2 and (x0 == POST_NEG or x0 == POST_POS) and element["from"][2] == 7
        side = "neg" if x0 == POST_NEG else "pos"
        if is_post and side in drop:
            continue
        if not is_open and not drop:
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
        else:
            leaf_side = "neg" if x0 < 8 else "pos"
            if leaf_side in drop:
                continue  # the stub that swung from the missing post
            e = json.loads(json.dumps(element))
            if element["from"][2] >= 9:  # the swung parts: run them the full width
                # A model may not reach past 32 on any axis, so a leaf between two others,
                # which would run 30 from its post, is cut at what fits: 23. Past that it was
                # not loading at all, and the gate showed as nothing.
                span = 14 if len(drop) == 1 else 23
                if element["from"][2] == 13:  # the end post of the stub
                    e["from"][2] = 9 + span - 2
                    e["to"][2] = 9 + span
                else:
                    e["to"][2] = 9 + span
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
        x0, x1 = e["from"][0], e["to"][0]
        if x1 - x0 == 2 and e["from"][1] == POST_FOOT and e["from"][2] == 7:
            e["from"][1] = 0
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
        x0, x1 = element["from"][0], element["to"][0]
        is_post = x1 - x0 == 2 and (x0 == POST_NEG or x0 == POST_POS) and element["from"][2] == 7
        if is_post:
            kept.append(element)
            continue
        leaf_side = "neg" if x0 < 8 else "pos"
        if leaf_side == other:
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


# The gate's own left is its counter-clockwise side looking the way it faces, which in the
# template is the post at x=14; its right is the post at x=0.
SWINGS = {"left": "pos", "right": "neg"}


def rotate(model, quarter_turns):
    """Turn the whole model clockwise about the block centre, a quarter turn at a time. Faces
    keep their uvs: every gate texture is a plank and does not care which way it is up."""
    faces = ["north", "east", "south", "west"]
    out = json.loads(json.dumps(model))
    for _ in range(quarter_turns):
        for e in out["elements"]:
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            # clockwise from above: (x, z) -> (16 - z, x)
            e["from"] = [16 - z1, y0, x0]
            e["to"] = [16 - z0, y1, x1]
            e["faces"] = {(faces[(faces.index(k) + 1) % 4] if k in faces else k): v for k, v in e["faces"].items()}
    return out


def main():
    with zipfile.ZipFile(find_jar()) as jar:
        templates = {
            "": load(jar, "models/block/template_fence_gate.json"),
            "_open": load(jar, "models/block/template_fence_gate_open.json"),
            "_wall": load(jar, "models/block/template_fence_gate_wall.json"),
            "_wall_open": load(jar, "models/block/template_fence_gate_wall_open.json"),
        }
        count = 0
        for gate, texture in gates(jar):
            for state, template in templates.items():
                for join, drop in JOINS.items():
                    base = joined(template, drop, state.endswith("_open"))
                    for on_another in (False, True):
                        # Joined to nothing beside and nothing below is vanilla's own model.
                        if join == "none" and not on_another:
                            continue
                        built = stacked(base) if on_another else base
                        for facing, turns in FACINGS.items():
                            model = rotate(built, turns)
                            model["textures"] = {"particle": texture, "texture": texture}
                            name = gate + state + "_join_" + join + ("_stacked" if on_another else "") + "_" + facing
                            with open(os.path.join(OUT, name + ".json"), "w") as f:
                                json.dump(model, f, separators=(",", ":"))
                            count += 1
            for state in ("_open", "_wall_open"):
                for side, post in SWINGS.items():
                    base = single_leaf(templates[state], post)
                    for on_another in (False, True):
                        built = stacked(base) if on_another else base
                        for facing, turns in FACINGS.items():
                            model = rotate(built, turns)
                            model["textures"] = {"particle": texture, "texture": texture}
                            name = gate + state + "_swing_" + side + ("_stacked" if on_another else "") + "_" + facing
                            with open(os.path.join(OUT, name + ".json"), "w") as f:
                                json.dump(model, f, separators=(",", ":"))
                            count += 1
        print("wrote %d joined gate models" % count)


if __name__ == "__main__":
    main()
