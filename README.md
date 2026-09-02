# More Doors

Doors with depth, doors that lock, doors that swing together, and the same three variants for every
material in the game.

## Doors Are Not Flat

Vanilla's door is one slab three pixels thick with a picture painted on it. These are the same eight
shapes rebuilt: a frame standing proud of a recessed panel, and a handle that sticks out far enough
to cast a shadow.

**Every door in the game gets it**, not only this mod's. The eight shapes are vanilla's own, served
above vanilla's copy, and every door - the twenty-two the game ships and the sixty-six here - is
drawn from them. A resource pack that retextures doors still retextures all of them.

## Six Doors Per Wood, And Vanilla's Beside Them

Vanilla is inconsistent here and always has been. An oak door has windows, a spruce door has iron
banding, an acacia door has slats, a cherry door has blossom cutouts. Which one you get is a fact
about the tree rather than a choice, so a builder who wants a solid oak door and a glazed cherry one
cannot have either.

So the shape and the wood are separated. Every wood offers:

| variant | shape | recipe |
| --- | --- | --- |
| **solid** | dark oak's four panels, in this wood | six planks - the ordinary recipe |
| **classic** | oak and iron's windowed shape | five planks, middle left out |
| **glass** | one window, glazed | five planks and a glass pane in the gap |
| **barred** | one window, barred | five planks and iron bars in the gap |
| **full glass** | glazed the whole way down | three planks and three panes, side by side |
| **full barred** | barred the whole way down | three planks and three iron bars, side by side |

The shape of a door is decided by what you leave out of it. Six planks is a door with no opening;
take one out of the middle and you get the opening; put glass or bars where you took it from and the
opening is filled. Put three down one side instead and it runs the height of the door.

**Solid is the default**, and the plain six-plank recipe is written over vanilla's own to make it so.
A door is a thing you cannot see through - that is most of the point of a door - so the shape that
shuts properly is the one you get without asking for anything else.

**Where a wood already owns a shape, it keeps it exactly.** Dark oak's solid door and oak's classic
door are pixel-for-pixel the vanilla blocks, not near-misses - which matters precisely because those
are the two that will be stood next to the originals.

**The wood's own vanilla face is still there**, and it is still vanilla's block: a copy would
duplicate vanilla for eleven woods and duplicate two of the variants above for the other two, and it
would gain nothing, because the locking, the door banks and the three-dimensional models in this mod
all reach vanilla's doors already. The stonecutter cuts a wood between its solid, classic and vanilla
faces in any direction, which is how a vanilla-faced door is still had once the plain recipe stops
making one.

### Where the artwork comes from

None of it is drawn by hand. Vanilla drew both shapes already, so each is taken as it is and only the
colour is changed - mapped by brightness, darkest source pixel to darkest colour in the target wood,
so the shading the vanilla artist put there survives and only the timber underneath it swaps.
Handles, hinges and banding are left alone: a brass handle is brass on every wood, and iron banding
is iron.

**The glazed variants cut the cross out.** Vanilla's window is four small panes with a wooden cross
between them, which is right for a door with holes in it and wrong for a glazed one - nobody puts
four postage stamps of glass in a door and leaves the timber standing between them. The cross comes
out and the four become one. A door glazed the whole way down is scaled as a single sheet across
both halves, too, because two panes stacked leave an edge across the middle of the door.

**The bars are vanilla's, cropped rather than redrawn.** Iron bars are two pixels wide on a
five-pixel pitch with cross-braces at matching intervals, and all of that is what makes them read as
iron bars rather than as stripes. The texture is read at its native scale and only nudged sideways,
by the one pixel that lands a bar against the edge of the opening instead of half a bar.

**Woods only.** A metal door is not a wood door in a different colour - iron opens on redstone and
copper oxidises, and neither behaviour survives being restyled - so the metals keep their vanilla
doors untouched.

## Locked Doors

Any door plus an iron ingot gives a locked one: visually identical, and it opens for its owner and
nobody else. The owner can let others in with `/doors allow <player>` while looking at it, take that
back with `/doors deny`, and `/doors unlock` gives up the lock entirely. Operators are always let
through.

**A bank locks as a whole**, because half a locked gate is a gate: the unlocked leaf beside the
locked one is simply the way in. And a locked door cannot be broken by anyone but its owner - without
that the lock is a suggestion, and anybody refused at the handle just takes it off its hinges.

## Doors That Swing Together

A double door is two doors everyone already treats as one, and vanilla makes you open both. Widen
that and a gatehouse is a bank of six that swing at once.

Connected means touching side to side, facing the same way, **and the same block**. That last one
matters: an oak door beside an iron one is two doors that happen to be adjacent, and swinging the
iron one because somebody opened the oak one would be a way through a locked gate.

And a bank does not flip: it swings. Six doors all changing their `open` state on the same tick is
six doors doing the same thing at the same moment, which is not what a gate looks like. So the whole
bank is lifted out of the world, handed over as **one moving object**, turned about its hinge post,
and set back down open. The blocks are described relative to the hinge, which is the entire trick -
a structure turns about its own origin, so putting the origin on the hinge is what makes the turn
read as a swing rather than a slide. Big-boats does the same thing about a ship's helm.

**The bigger it is, the slower it goes.** Not a flourish - the far edge of a wide gate has much
further to travel than the far edge of a narrow one, so turning both in the same time means the big
gate's edge is whipping along several times faster. A heavy thing that whips is the one cue that
reads as fake however good the model is, so instead the edge is held to a constant speed and the
clock stretches to match. A double door claps shut in half a second; a six-wide gatehouse takes a
second and a half; anything grander is capped at two and a half, past which weight turns into a
wait. The measure is width out from the hinge, not door count - stacking a bank three doors high
adds nothing to how far the edge travels, so it swings like the double door it is.

A single door does not get this. Vanilla's instant swing already looks right for one leaf, and a
production costs more than it buys.

While it swings the doorway is genuinely open - the real blocks are gone for those ten ticks and
what you see is the moving picture. Walking through a gate a tick before it finishes is a smaller
lie than a gate that looks open and stops you.

## Doors Connect To Fences

A fence line running into a gate used to stop a block short, with a post standing on its own beside
the doorway. Fences and walls now treat a door as something to connect to, so a fenced enclosure
with a gate in it reads as one continuous line.

## Turning A Door In Place

Shift-right-click a door with an axe to cycle its facing without breaking it. Getting a door's
orientation right otherwise means breaking it and replacing it until it lands the way you wanted,
which for a locked door means losing the lock. Vanilla iron doors take a pickaxe instead, because
that is what you would reach for.

## Pandorical

More Doors registers its door blocks and the depth models through Pandorical's content sync, and a
bank of doors swinging together is drawn as one moving structure through Pandorical's structures.

**The Pandorical mod must be installed client-side** to see the depth, the locks' models and the
swing. Without it the doors still open, lock and swing together, but a client sees them as flat
vanilla doors and a bank opens in one step.

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients
need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and
`fabric.mod.json` (Java).

## Art

`generate_icon.py`, `generate_textures.py`, `generate_door_models.py`, `generate_recipes.py` and
`generate_assets.py` cut the mod's art and data out of the vanilla jar. All are deterministic; re-run
them after a Minecraft version bump.

## License

MIT, see [LICENSE](LICENSE).
