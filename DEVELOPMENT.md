# Moredoor - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`), which include
Pandorical. Connecting clients need nothing, and Pandorical to see the models and
use the menus. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and
`fabric.mod.json` (Java).

## Art

`generate_icon.py`, `generate_textures.py`, `generate_door_models.py`, `generate_plain_sheets.py`,
`generate_mega_doors.py`, `generate_trapdoor_models.py`, `generate_gate_models.py`,
`generate_door_jambs.py`, `generate_recipes.py` and `generate_assets.py`
cut the mod's art and data out of the vanilla jar. `generate_menu_icons.py` draws the pictures on
the door, trapdoor and gate menus. All are deterministic; re-run them after a Minecraft version
bump. Most only add and overwrite; `generate_gate_models.py`, `generate_door_models.py` and
`generate_mega_doors.py` also remove the models of theirs they no longer write, so a renamed or
dropped model leaves nothing behind.

### Where the artwork comes from

None of it is drawn by hand. Vanilla drew both shapes already, so each is taken as it is and only the colour is changed - mapped by brightness, darkest source pixel to darkest colour in the target wood, so the shading the vanilla artist put there survives and only the timber underneath it swaps. Handles, hinges and banding are left alone: a brass handle is brass on every wood, and iron banding is iron.

**The glazed variants cut the cross out.** Vanilla's window is four small panes with a wooden cross between them, which is right for a door with holes in it and wrong for a glazed one. The cross comes out and the four become one. A door glazed the whole way down is scaled as a single sheet across both halves, because two panes stacked leave an edge across the middle of the door.

**The bars are vanilla's, cropped rather than redrawn.** Iron bars are two pixels wide on a five-pixel pitch with cross-braces at matching intervals, and all of that is what makes them read as iron bars rather than as stripes. The texture is read at its native scale and only nudged sideways, by the one pixel that lands a bar against the edge of the opening instead of half a bar.

**Depth for every door.** The eight door shapes are vanilla's own model names, rebuilt with depth and served above vanilla's copy through Pandorical, so vanilla's doors get the depth too and a resource pack's door retexture still reaches every one.

**Banks drawn as one door.** Every leaf but the one with the handle is drawn from a copy of its sheet with the painted handle taken off - found by being the one thing on the swinging side that the hinge side of the same row never shows, so grey, brass, lighter wood and darker wood all count - and filled from the wood beside it. Stiles run down the rectangle's edges and rails along its top and bottom; inside, the sheet's interior is stretched across each leaf and its panels repeat.

**Fence jambs** are in the planks of the door's wood and cut from the same strips of them a fence is, so beside a fence of that wood a jamb reads as the fence carried on.

### Legacy blocks

The full-glass and full-barred trapdoors are still registered so ones already placed keep working, but have no crafting recipe: the stonecutter turns one into the glass or barred trapdoor it duplicated, and breaking one drops that trapdoor.
