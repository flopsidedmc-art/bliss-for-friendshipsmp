# Relay temporal resource pack

`Relay-Temporal-Pack.zip` is an **overlay**, not a complete pack. It only works together with the main Bliss / Soulbound Gems pack:

- either enable both and put the Relay overlay **above** the main pack,
- or merge it into the main pack (copy every file across; the overlay's `font/default.json` and the two `repeating_command_block.json` files are supersets of the main pack's and replace them; keep the main pack's `pack.mcmeta`).

Loaded on its own, its font file points at 68 icon textures that only exist in the main pack.

## Texture path fix

The three clock material textures (`temporal_brown.png`, `temporal_bronze.png`, `temporal_gold.png`) were moved from `assets/blissgems/textures/effect/` to `assets/blissgems/textures/item/`, and the `temporal_clock` / `temporal_hand` models now reference `blissgems:item/temporal_*`. Minecraft only loads model textures from the `item/` and `block/` folders, so the old location would have rendered as the purple-and-black missing texture. Pixels, colours, geometry and model IDs are unchanged.

Not verified in a Minecraft client.

## Rift and Time Dilate restyle

| Asset | Path | Used as |
|---|---|---|
| Rift frames | `assets/blissgems/textures/item/rift_0.png` to `rift_5.png` (40x96) | item models `blissgems:rift_0` to `blissgems:rift_5`, flat vertical planes |
| Rift light spill | `assets/blissgems/textures/item/rift_light.png` (32x32) | item model `blissgems:rift_light`, flat on the ground |
| Rift HUD icon | `assets/blissgems/textures/icons/relay_rift.png` (33x20) | font glyph `U+E043` (`mythics.relay.hud.rift-glyph`) |
| Hotbar HUD font | `assets/blissgems/font/hud_low.json` | Rift icon and vanilla letters drawn beside the hotbar, plus spacing characters U+E400-E418 |
| Time Dilate clock | `temporal_clock.json`, `temporal_hand.json` | lines 1.5x thicker, rebuilt from `tools/pack/source` |
| Time Dilate colours | `temporal_brown/bronze/gold.png` | now #B0703A / #8C5530 / #F0C48C |

Rebuild with `tools/pack/rift_frames.py` then `tools/pack/relay_pack_assets.py`. The display items are plain paper with an `item_model`, so no custom model data numbers are used.

## Known, not fixed

The screen-flash images (`textures/overlay/gold_*.png`, `red_*.png`, `brown_flash.png`) are 512x288 and 1024x576. Minecraft font glyphs are limited to 256x256 pixels, so these may show as empty boxes instead of a flash. They were supplied this way and have been left alone.
