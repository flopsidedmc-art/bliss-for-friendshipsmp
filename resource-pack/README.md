# Relay temporal resource pack

`Relay-Temporal-Pack.zip` is an **overlay**, not a complete pack. It only works together with the main Bliss / Soulbound Gems pack:

- either enable both and put the Relay overlay **above** the main pack,
- or merge it into the main pack (copy every file across; the overlay's `font/default.json` and the two `repeating_command_block.json` files are supersets of the main pack's and replace them; keep the main pack's `pack.mcmeta`).

Loaded on its own, its font file points at 68 icon textures that only exist in the main pack.

## Texture path fix

The three clock material textures (`temporal_brown.png`, `temporal_bronze.png`, `temporal_gold.png`) were moved from `assets/blissgems/textures/effect/` to `assets/blissgems/textures/item/`, and the `temporal_clock` / `temporal_hand` models now reference `blissgems:item/temporal_*`. Minecraft only loads model textures from the `item/` and `block/` folders, so the old location would have rendered as the purple-and-black missing texture. Pixels, colours, geometry and model IDs are unchanged.

Not verified in a Minecraft client.

## Known, not fixed

The screen-flash images (`textures/overlay/gold_*.png`, `red_*.png`, `brown_flash.png`) are 512x288 and 1024x576. Minecraft font glyphs are limited to 256x256 pixels, so these may show as empty boxes instead of a flash. They were supplied this way and have been left alone.
