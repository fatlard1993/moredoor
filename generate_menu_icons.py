#!/usr/bin/env python3
"""The eight pictures on the door menu: a leaf hung from one of the four corners of its block, seen
from above with the player at the bottom, and a leaf sliding left, right, up or down. Drawn at 22x22, the face of a 24px button inside its edge, in white
with a dark shadow so they read on the button's grey; the lit copies are the pressed one's yellow."""
from PIL import Image, ImageDraw

SIZE = 22
OUT = "src/main/resources/assets/more-doors-justfatlard/textures/gui/sprites/swing/%s.png"
WHITE, LIT, SHADOW = (255, 255, 255, 255), (255, 255, 128, 255), (32, 32, 32, 200)


def block(draw, colour):
    """The block from above, faintly, so a leaf is seen to be on one edge of it."""
    faint = colour[:3] + (90,)
    for i in range(1, 21):
        for x, y in ((i, 1), (i, 20), (1, i), (20, i)):
            draw.point((x, y), faint)


def corner(draw, colour, far, right):
    """The block from above, the player at the bottom of the picture: a leaf shut along its near
    or far edge, the corner it hangs from marked, and where it stands open dashed up the side."""
    def P(x, y):
        return (SIZE - 1 - x if right else x, SIZE - 1 - y if far else y)
    block(draw, colour)
    for x in range(2, 20):                 # shut, along the near edge
        draw.point(P(x, 17), colour); draw.point(P(x, 18), colour)
    for y in range(2, 16):                 # open, dashed up the hinge side
        if (y // 2) % 2 == 0:
            draw.point(P(2, y), colour); draw.point(P(3, y), colour)
    for x in range(1, 5):                  # the hinge corner
        for y in range(16, 20):
            draw.point(P(x, y), colour)


def face_flip(draw, colour):
    """A leaf on the near edge, the far edge it could be on dashed, and the arrow both ways."""
    block(draw, colour)
    for x in range(2, 20):
        draw.point((x, 17), colour); draw.point((x, 18), colour)
        if (x // 2) % 2 == 0:
            draw.point((x, 3), colour); draw.point((x, 4), colour)
    for y in range(7, 15):
        draw.point((10, y), colour); draw.point((11, y), colour)
    for i in range(3):
        for x in range(10 - i, 12 + i):
            draw.point((x, 6 + i), colour); draw.point((x, 15 - i), colour)


def slide(draw, colour, direction):
    """A leaf standing in the doorway and a straight arrow the way it goes."""
    leaf = [(12, 4, 18, 17), (3, 4, 9, 17), (4, 12, 17, 18), (4, 3, 17, 9)][direction]
    draw.rectangle(leaf, outline=colour, width=2)
    if direction < 2:                      # left, right
        y1, y2 = 10, 11
        xs = range(2, 10) if direction == 0 else range(12, 20)
        for x in xs:
            draw.point((x, y1), colour); draw.point((x, y2), colour)
        tip = 2 if direction == 0 else 19
        step = 1 if direction == 0 else -1
        for i in range(4):
            for y in (10 - i, 11 + i):
                draw.point((tip + step * i, y), colour)
    else:                                  # up, down
        x1, x2 = 10, 11
        ys = range(2, 10) if direction == 2 else range(12, 20)
        for y in ys:
            draw.point((x1, y), colour); draw.point((x2, y), colour)
        tip = 2 if direction == 2 else 19
        step = 1 if direction == 2 else -1
        for i in range(4):
            for x in (10 - i, 11 + i):
                draw.point((x, tip + step * i), colour)


def icon(name, paint):
    for suffix, colour in (("", WHITE), ("_lit", LIT)):
        image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        paint(ImageDraw.Draw(shadow), SHADOW)
        image.alpha_composite(shadow.transform(shadow.size, Image.AFFINE, (1, 0, -1, 0, 1, -1)))
        paint(ImageDraw.Draw(image), colour)
        image.save(OUT % (name + suffix))


icon("face_flip", face_flip)
for far in (False, True):
    for right in (False, True):
        icon("hinge_%s_%s" % ("far" if far else "near", "right" if right else "left"),
             lambda d, c, far=far, right=right: corner(d, c, far, right))
for i, name in enumerate(("slide_left", "slide_right", "slide_up", "slide_down")):
    icon(name, lambda d, c, i=i: slide(d, c, i))
print("wrote 18 menu icons")
