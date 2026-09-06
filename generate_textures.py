#!/usr/bin/env python3
"""Generate every door and trapdoor texture: three variants of twenty-two materials.

Built from each material's own stock - planks for a wood, the metal block for a
metal - rather than by editing vanilla's door textures, which is what makes the
three variants look like three variants of the same door rather than twenty-two
unrelated pictures. Vanilla is inconsistent here by history: oak has one window,
spruce another, acacia a slot. Drawing them all from one description is the point.

Glass and bars are cut from the vanilla glass and iron-bars textures, so a
resource pack that changes those changes these.

Pure stdlib PNG reader and writer (zlib + struct) so it runs without Pillow.
Deterministic: re-running produces identical bytes.

Usage: python3 generate_textures.py [path/to/minecraft.jar]
"""

import glob
import os
import struct
import sys
import zipfile
import zlib
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
BLOCK = os.path.join(HERE, "src/main/resources/assets/more-doors-justfatlard/textures/block")
ITEM = os.path.join(HERE, "src/main/resources/assets/more-doors-justfatlard/textures/item")

CLEAR = (0, 0, 0, 0)
_JAR = None


def minecraft_version():
    """The version this mod targets, so the sprite is cut from the same jar the
    mod is built against rather than whatever happens to be cached."""
    path = os.path.join(HERE, "gradle.properties")
    if not os.path.exists(path):
        return None
    for line in open(path):
        key, sep, value = line.partition("=")
        if sep and key.strip() == "minecraft_version":
            return value.strip()
    return None


def find_jar():
    """Loom caches the remapped Minecraft jars after a build; that is where the
    vanilla art comes from. Override with an argument or $MINECRAFT_JAR."""
    global _JAR
    if _JAR:
        return _JAR
    if len(sys.argv) > 1:
        _JAR = sys.argv[1]
        return _JAR
    if os.environ.get("MINECRAFT_JAR"):
        _JAR = os.environ["MINECRAFT_JAR"]
        return _JAR
    cache = os.path.expanduser("~/.gradle/caches/fabric-loom")
    names = ("minecraft-merged.jar", "minecraft-client.jar")
    found = []
    version = minecraft_version()
    if version:
        for name in names:
            found += glob.glob(os.path.join(cache, version, name))
    if not found:
        for name in names:
            found += glob.glob(os.path.join(cache, "*", name))
    if not found:
        sys.exit("no cached Minecraft jar found: build the mod once, "
                 "or pass a jar path as the first argument")
    _JAR = max(found, key=os.path.getmtime)
    return _JAR


def vanilla(name):
    """Read assets/minecraft/textures/<name> out of the vanilla jar."""
    with zipfile.ZipFile(find_jar()) as jar:
        return decode_png(jar.read("assets/minecraft/textures/" + name))


def decode_png(data):
    """Minimal PNG reader: no interlacing, every colour type and bit depth
    vanilla actually ships. Returns rows of RGBA tuples."""
    pos = 8
    idat = b""
    width = height = depth = ctype = None
    palette = trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "interlaced PNG not supported"
        elif tag == b"PLTE":
            palette = body
        elif tag == b"tRNS":
            trns = body
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    stride = (width * channels * depth + 7) // 8
    step = max(1, (channels * depth) // 8)
    raw = zlib.decompress(idat)
    out = bytearray(stride * height)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(step, stride):
                line[i] = (line[i] + line[i - step]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                b = prev[i]
                c = prev[i - step] if i >= step else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    pixels = []
    if depth < 8:
        per = 8 // depth
        mask = (1 << depth) - 1
        for y in range(height):
            base = y * stride
            row = []
            for x in range(width):
                i = x * channels
                value = (out[base + i // per] >> (8 - depth * (i % per + 1))) & mask
                if ctype == 3:
                    r, g, b = palette[value * 3:value * 3 + 3]
                    a = trns[value] if trns and value < len(trns) else 255
                    row.append((r, g, b, a))
                else:
                    v = value * 255 // mask
                    row.append((v, v, v, 255))
            pixels.append(row)
        return pixels

    for y in range(height):
        base = y * stride
        row = []
        for x in range(width):
            i = base + x * channels
            if ctype == 6:
                row.append(tuple(out[i:i + 4]))
            elif ctype == 2:
                row.append((out[i], out[i + 1], out[i + 2], 255))
            elif ctype == 4:
                row.append((out[i], out[i], out[i], out[i + 1]))
            elif ctype == 0:
                row.append((out[i], out[i], out[i], 255))
            else:
                r, g, b = palette[out[i] * 3:out[i] * 3 + 3]
                a = trns[out[i]] if trns and out[i] < len(trns) else 255
                row.append((r, g, b, a))
        pixels.append(row)
    return pixels


def write_png(path, pixels):
    """pixels: rows of RGBA tuples."""
    height = len(pixels)
    width = len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in pixels)

    def chunk(tag, body):
        c = tag + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote %s (%dx%d)" % (path, width, height))




WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "pale_oak", "poplar",
         "mangrove", "cherry", "bamboo", "crimson", "warped"]

# Which vanilla door each variant borrows its shape from.
#
# Redrawing these by hand would mean thirteen woods of hand-pixelled panelling that
# never quite sits right next to the vanilla block beside it. Vanilla already drew
# both shapes, so the shapes are taken as they are and only the colour is changed.
SOLID_SHAPE = "dark_oak"   # four panels, no opening: the default every wood makes
CLASSIC_SHAPE = "oak"      # two-by-two windows: the shape oak and iron have always had

# "" is the default solid door, so it needs no prefix in the block id. The wood's own vanilla face
# is not here because it is not ours to draw - it stays vanilla's block, cut to on the stonecutter.
VARIANTS = ("", "classic_", "glass_", "barred_", "full_glass_", "full_barred_")

# The opening each variant cuts, as (left, right, top, bottom) per half - top half then bottom.
# None means that half stays solid.
#
# Vanilla's window is four small panes with a wooden cross between them. That cross is right for a
# door with holes in it and wrong for a glazed one: nobody puts four postage stamps of glass in a
# door and leaves the timber standing between them. So the cross comes out and the four become one.
WINDOW = ((2, 12, 2, 11), None)
FULL = ((2, 12, 2, 15), (2, 12, 0, 12))

# Clear air between the top of an opening and the first cross-brace in it.
BRACE_GAP = 2

OPENINGS = {
    "glass_": WINDOW,
    "barred_": WINDOW,
    "full_glass_": FULL,
    "full_barred_": FULL,
}

# A trapdoor is one face, so it gets one opening rather than a pair.
TRAP_OPENINGS = {
    "glass_": ((2, 12, 2, 12),),
    "barred_": ((2, 12, 2, 12),),
    "full_glass_": ((1, 13, 1, 13),),
    "full_barred_": ((1, 13, 1, 13),),
}


def planks_for(material):
    """The texture a wood's colour is read from."""
    return "block/%s_planks.png" % material


def luminance(px):
    return 0.299 * px[0] + 0.587 * px[1] + 0.114 * px[2]


def hue_sat(px):
    """Hue in degrees and saturation in 0..1, for telling wood from ironmongery."""
    r, g, b = (c / 255.0 for c in px[:3])
    high, low = max(r, g, b), min(r, g, b)
    span = high - low
    if span < 1e-6:
        return 0.0, 0.0
    if high == r:
        hue = 60 * (((g - b) / span) % 6)
    elif high == g:
        hue = 60 * ((b - r) / span + 2)
    else:
        hue = 60 * ((r - g) / span + 4)
    return hue, (span / high if high else 0.0)


def tones(pixels, keep=5):
    counts = Counter(px for row in pixels for px in row if px[3])
    return sorted((px for px, _ in counts.most_common(keep)),
                  key=luminance)


def blend(a, b, weight):
    """Mix two colours, keeping full alpha."""
    return tuple(int(a[i] + (b[i] - a[i]) * weight) for i in range(3)) + (255,)


def ramp_of(planks):
    """A wood's colours darkest to lightest, stretched to a door's range.

    A door is shaded past what its planks contain - the frame throws shadows darker
    than any board and catches highlights brighter - so the plank palette alone
    would flatten every door it was used on. Extending it at both ends gives the
    shading somewhere to go.
    """
    palette = tones(planks, keep=8)
    if not palette:
        return [(128, 128, 128, 255)]

    return ([blend(palette[0], (0, 0, 0, 255), 0.45)] + list(palette)
            + [blend(palette[-1], (255, 255, 255, 255), 0.25)])


def sample_ramp(ramp, position):
    """Read the ramp at a fractional position, interpolating between its stops."""
    if len(ramp) == 1:
        return ramp[0]

    at = max(0.0, min(1.0, position)) * (len(ramp) - 1)
    low = int(at)
    high = min(low + 1, len(ramp) - 1)
    return blend(ramp[low], ramp[high], at - low)


def is_ironmongery(px, wood_hue):
    """Whether a pixel is hardware rather than timber.

    Handles, hinges and bands are not made of the door, so they must not be
    recoloured with it - a spruce door's iron banding is iron on every wood, and a
    brass handle stays brass. Grey gives the metal away; so does a hue that has
    wandered too far from the wood it is sitting on.
    """
    hue, sat = hue_sat(px)
    if sat < 0.12:
        return True
    # No timber is this saturated; brass is, and a brass handle's darker pixel sits close
    # enough to a wood's hue to pass the test below.
    if sat > 0.9:
        return True

    gap = abs(hue - wood_hue)
    return min(gap, 360 - gap) > 20 and sat > 0.20


def recolour(face, source_planks, target_planks):
    """Repaint one wood's door in another wood's colours.

    Straight hue rotation would drag the shadows and highlights along with it and
    come out looking dyed. Mapping by brightness instead - darkest source pixel to
    darkest target colour, lightest to lightest - keeps the shading the vanilla
    artist put there and swaps only the timber underneath it.
    """
    ramp = ramp_of(target_planks)
    wood_hue = hue_sat(tones(source_planks, keep=1)[0])[0]

    # The range is the timber's, read between its second and ninety-eighth percentiles. One
    # stray bright pixel - a handle's edge, a highlight - used to set the top of the range on
    # its own, and with dark oak's it pushed every board into the bottom quarter, so every
    # solid door came out a third too dark.
    lit = sorted(luminance(px) for row in face for px in row if px[3] and not is_ironmongery(px, wood_hue))
    if not lit:
        return face
    low, high = lit[int(0.02 * (len(lit) - 1))], lit[int(0.98 * (len(lit) - 1))]
    span = (high - low) or 1.0

    for y, row in enumerate(face):
        for x, px in enumerate(row):
            if not px[3] or is_ironmongery(px, wood_hue):
                continue
            row[x] = sample_ramp(ramp, (luminance(px) - low) / span)
    return face


def cut_opening(face, rect):
    """Take the wood out of a rectangle, cross and all."""
    left, right, top, bottom = rect
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            face[y][x] = CLEAR
    return face


def glaze(face, rect, glass, above=0, total=None):
    """Fit a pane to the opening.

    Vanilla's glass is a border round an empty middle, so cropping it to a hole lands entirely
    inside the empty part and glazes nothing. Scaling it puts its edge back on the edge of the
    opening, where a pane's edge belongs.

    A door that is glazed the whole way down is one pane, not two stacked, so the two halves are
    scaled as though they were one tall opening - {@code above} says how much of it is already
    above this half. Glazing each half on its own puts a pane edge across the middle of the door
    and the illusion of one sheet is gone.
    """
    left, right, top, bottom = rect
    width, height = right - left + 1, bottom - top + 1
    total = total or height
    source_h, source_w = len(glass), len(glass[0])

    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            sx = (x - left) * (source_w - 1) // max(1, width - 1)
            sy = (above + y - top) * (source_h - 1) // max(1, total - 1)
            face[y][x] = glass[sy][sx]
    return face


def bar_columns(bars):
    """Where the uprights are in the source, as (first column, width, pitch).

    Read rather than assumed, so a resource pack that draws its bars somewhere else still gets
    centred properly. The top row is sampled because it is above the first cross-brace, and a row
    with a brace in it reads as one bar the whole way across.
    """
    row = bars[0]
    runs, start = [], None
    for x, px in enumerate(row):
        if px[3] and start is None:
            start = x
        elif not px[3] and start is not None:
            runs.append((start, x - start))
            start = None
    if start is not None:
        runs.append((start, len(row) - start))
    if not runs:
        return 0, 2, 5

    pitch = runs[1][0] - runs[0][0] if len(runs) > 1 else runs[0][1]
    return runs[0][0], runs[0][1], pitch


def first_brace(bars):
    """The row the source's first cross-brace starts on.

    A brace row is simply a row with more metal in it than the uprights alone account for, so it
    is counted rather than looked up - a resource pack that braces its bars at different heights
    still gets measured correctly.
    """
    uprights = sum(1 for px in bars[0] if px[3])

    for y, row in enumerate(bars):
        if sum(1 for px in row if px[3]) > uprights:
            return y
    return 0


def bar_up(face, rect, bars, drop=0):
    """Vanilla's own bars, cropped rather than redrawn, and centred in the opening.

    Iron bars are two pixels wide on a five-pixel pitch, with cross-braces at matching intervals -
    all of which is what makes them read as iron bars rather than as stripes. Redrawing them at
    some other spacing loses exactly that, so the texture is read at its native scale and only slid
    sideways.

    How far it slides is worked out from the opening: as many whole bars as fit at the source's own
    pitch, placed so the leftover falls outside them rather than all against one edge. A grille
    pushed up against one side of its hole is the tell that it was cropped rather than fitted.

    The same goes vertically. A cross-brace sitting flush against the top of the opening reads as
    part of the frame rather than as part of the grille, so the crop is dropped far enough to leave
    clear air above the first one. {@code drop} is worked out once for the whole door and passed to
    every half, which is what keeps the bars of a full-height door lined up across the join.
    """
    left, right, top, bottom = rect
    source_h, source_w = len(bars), len(bars[0])
    first, width, pitch = bar_columns(bars)

    opening = right - left + 1
    count = max(1, (opening - width) // pitch + 1)
    span = width + (count - 1) * pitch
    start = left + (opening - span) // 2

    # Land a bar's leading edge on `start`, and let the crop carry the braces with it.
    offset = first - start

    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            face[y][x] = bars[(y + drop) % source_h][(x + offset) % source_w]
    return face


def faces_for(material, variant, shapes, glass, bars):
    """Every face of one door: the shape it borrows, wearing this wood's colour."""
    source = SOLID_SHAPE if variant == "" else CLASSIC_SHAPE
    openings = (TRAP_OPENINGS if len(shapes[source]) == 1 else OPENINGS).get(variant)

    # A wood that already owns the shape gets it untouched. Recolouring dark oak into dark oak is
    # a round trip through a quantised ramp, and it comes back very slightly not itself - which is
    # visible precisely where it matters most, side by side with the vanilla block it copies.
    if material == source:
        made = [[row[:] for row in part] for part in shapes[source]]
    else:
        target_planks = vanilla(planks_for(material))[:16]
        source_planks = vanilla(planks_for(source))[:16]
        made = [recolour([row[:] for row in part], source_planks, target_planks)
                for part in shapes[source]]

    if not openings:
        return made

    # How tall the opening is across every half it passes through, so a pane that runs the height
    # of the door is scaled as the single sheet it is.
    total = sum(r[3] - r[2] + 1 for r in openings if r is not None)

    # Air above the first cross-brace, measured from the top of the whole opening so both halves
    # of a full-height door stay in step.
    opening_top = next(r[2] for r in openings if r is not None)
    drop = first_brace(bars) - opening_top - BRACE_GAP

    above = 0
    for face, rect in zip(made, openings):
        if rect is None:
            continue
        cut_opening(face, rect)
        if "glass" in variant:
            glaze(face, rect, glass, above, total)
        else:
            bar_up(face, rect, bars, drop)
        above += rect[3] - rect[2] + 1
    return made


def door_item(top, bottom):
    """The door as carried: both halves shrunk into one sprite at vanilla's size.

    Vanilla draws a door item ten wide and fourteen tall, two rows down from the top. The
    whole face is sampled into that, so the frame's outer edges survive; taking the middle
    columns instead cut the stiles off both sides.
    """
    sprite = [[CLEAR] * 16 for _ in range(16)]
    face = list(top) + list(bottom)
    for r in range(14):
        row = face[min(31, int((r + 0.5) * 32 / 14))]
        for c in range(10):
            sprite[2 + r][3 + c] = row[min(15, int((c + 0.5) * 16 / 10))]
    return sprite


def build():
    glass = vanilla("block/glass.png")[:16]
    bars = vanilla("block/iron_bars.png")[:16]

    # Vanilla's own artwork, read once: top half, bottom half, trapdoor.
    doors = {w: (vanilla("block/%s_door_top.png" % w)[:16],
                 vanilla("block/%s_door_bottom.png" % w)[:16]) for w in WOODS}
    traps = {w: (vanilla("block/%s_trapdoor.png" % w)[:16],) for w in WOODS}

    made = 0
    for material in WOODS:
        for variant in VARIANTS:
            door = "%s_%sdoor" % (material, variant)
            trap = "%s_%strapdoor" % (material, variant)

            top, bottom = faces_for(material, variant, doors, glass, bars)
            write_png(os.path.join(BLOCK, door + "_top.png"), top)
            write_png(os.path.join(BLOCK, door + "_bottom.png"), bottom)
            write_png(os.path.join(ITEM, door + ".png"), door_item(top, bottom))

            face, = faces_for(material, variant, traps, glass, bars)
            write_png(os.path.join(BLOCK, trap + ".png"), face)
            made += 4
    print("wrote %d textures" % made)


if __name__ == "__main__":
    build()
