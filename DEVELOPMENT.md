# More Doors - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients
need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and
`fabric.mod.json` (Java).

## Art

`generate_icon.py`, `generate_textures.py`, `generate_door_models.py`, `generate_plain_sheets.py`,
`generate_mega_doors.py`, `generate_trapdoor_models.py`, `generate_gate_models.py`,
`generate_door_jambs.py`, `generate_recipes.py` and `generate_assets.py`
cut the mod's art and data out of the vanilla jar. All are deterministic; re-run
them after a Minecraft version bump.
