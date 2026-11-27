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
| Tuned In meter | `assets/blissgems/textures/icons/relay_tuned.png`, `relay_tuned_c1.png` … `relay_tuned_c5.png` | target reticle around a small standing player (redrawn inside only) on `U+E042`, `U+E058`–`U+E05C` |
| Lore eye symbol | `assets/blissgems/textures/font/lore_eye.png` (11x7, white and greys) | font glyph `U+E045`, before TEMPORAL DIVINITY in the Relay lore |
| Hotbar HUD font | `assets/blissgems/font/hud_low.json` | Rift icon and vanilla letters drawn beside the hotbar, plus spacing characters U+E400-E418 |
| Time Dilate clock | `temporal_clock.json`, `temporal_hand.json` | lines 1.5x thicker, rebuilt from `tools/pack/source` |
| Time Dilate colours | `temporal_brown/bronze/gold.png` | now #B0703A / #8C5530 / #F0C48C |
| The Revenant (mace) | `assets/blissgems/items/revenant_mace.json`, `models/item/revenant_mace.json`, `textures/item/revenant_mace.png` (16x16, supplied) | item model `blissgems:revenant_mace`, parent `minecraft:item/handheld_mace` |
| Revenant HUD icons | `assets/blissgems/textures/icons/revenant_plunge.png`, `revenant_rising.png`, `revenant_undertow.png` (33x20) | font glyphs `U+E046` Plunge, `U+E047` Rising, `U+E048` Undertow |
| Finale pedestals | `assets/blissgems/items/finale_pedestal_auratus.json`, `finale_pedestal_heretic.json` and their models (vanilla blackstone, gold and redstone-block textures) | item models `blissgems:finale_pedestal_auratus` / `_heretic`, about 1.9 blocks tall |
| Finale seal | `assets/blissgems/textures/item/finale_seal.png` (128x128), model `finale_seal` | a flat clock face on the ground under the merge, scaled to 7 blocks |

Rebuild with `tools/pack/rift_frames.py` then `tools/pack/relay_pack_assets.py`. The display items are plain paper with an `item_model`, so no custom model data numbers are used.

## Known, not fixed

The screen-flash images (`textures/overlay/gold_*.png`, `red_*.png`, `brown_flash.png`) are 512x288 and 1024x576. Minecraft font glyphs are limited to 256x256 pixels, so these may show as empty boxes instead of a flash. They were supplied this way and have been left alone.

## Upgrader, Trader and Bottle of Energy

`assets/blissgems/items/upgrader.json`, `trader.json` and `energy_bottle.json` point at the main pack's `blissgems:item/upgrader`, `trader` and `energy_bottle` models. Those models and their textures are copied in too. The plugin sets the item's `item_model` to `blissgems:upgrader`, `blissgems:trader` or `blissgems:energy_bottle`, and restyles older copies when a player joins or uses one.

Not verified in a Minecraft client.

## The Lost Halo

Merged from the "glowing gems" pack (Relay-Temporal-Pack-glowing-gems_4.zip), which added only these files; everything else in this zip is as before (the special items and the repurpose overlays included).

| What | Files | Used as |
|---|---|---|
| Halo Fragments 1 to 5 | `assets/blissgems/items/halo/fragment_1.json` … `fragment_5.json`, `models/item/halo/`, `textures/item/halo/fragment_<n>.png` (animated, `frametime` 2) | the `item_model` of Halo Fragment n: `blissgems:halo/fragment_<n>` (`/halo give`, and the floating fragment on a reliquary) |
| Halo Reliquary | `assets/blissgems/items/halo/reliquary.json`, `models/item/halo/reliquary.json` (vanilla chiseled quartz, quartz pillar, gold and raw gold textures) | `blissgems:halo/reliquary`, shown one block in size by an item display over the shrine's barrier block (`/halo reliquary <1-5>`) |

Not verified in a Minecraft client.
