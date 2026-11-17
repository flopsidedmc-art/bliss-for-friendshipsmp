# Relay: Rift (quick travel) and the Time Dilate restyle

**Status: compiled and unit-tested, NOT played.** No Minecraft server or client was run. Everything below about how it looks and plays in game is the design, not an observation.

Built on this repository's plugin (1.3.0-temporal plus this week's changes), not the "v9" the prompt describes; you confirmed that is fine. In this plugin the Relay controls are: left-click Rewind, right-click Time Dilate, sneak + right-click Temporal Divinity. Sneak + left-click was unused, so Rift takes it and nothing else moves.

## Controls

| Input (Relay in main hand) | Result |
|---|---|
| Left-click | Rewind (unchanged) |
| Right-click | Time Dilate (unchanged) |
| Sneak + right-click | Temporal Divinity (unchanged) |
| **Sneak + left-click** | **Rift menu** |

Sneak + left-click never fires Rewind or Dilate: the click goes to the menu, and Rewind already ignores sneaking swings.

## How it plays

1. **Menu.** Four buttons: Travel to A, Travel to B, Set A here, Set B here. Each shows the anchor's world and block coordinates, plus a status clock in the middle (Ready, recharging, or unstable in combat).
2. **Anchors** belong to the player, not the gem. They are saved in `plugins/SoulboundGems/relay-rift.yml` and survive restarts. Setting one fails inside Temporal Divinity's void world, any world in `blocked-worlds`, any box in `blocked-areas`, and while the finale runs. If there is no safe ground near the spot, it is still saved, with a warning.
3. **Channel (5s).** A crack tears open in front of you over half a second, then grows to full size as the channel completes, with a rising sound.
   - Any damage, moving more than 1.5 blocks, death, logout or letting go of the Relay breaks it and costs a **10-second** cooldown.
   - It can't start within 15 seconds of hitting, or being hit by, another player: "Rift unstable: in combat (Xs)".
4. **Jump.** You land on the nearest safe block within 5 blocks of the anchor. If there is none, or another plugin blocks the teleport, the rift collapses and **no cooldown** is used.
5. **Others follow.** The rift stays open where you stood for 4 seconds. Any player who walks into it (trusted or not, no special case) arrives at the same anchor 1 second later, offset so nobody lands inside anyone.
6. **Arrival.** Everyone who arrives gets 1 second of fall-damage protection and nothing else. Players within 64 blocks see a brown flash and column of particles and hear a sound, and a half-size rift opens next to you for 1 second.
7. **Cooldown:** 300 seconds after a completed jump, separate from every other Relay cooldown, persisted across restarts.
8. **HUD:** the Rift icon and its timer sit just right of the hotbar; the rest of the Relay HUD stays on the action bar exactly where it was. See "Rift HUD slot" below.

**My decision on cancelled channels: a 10-second cooldown, not none.** With no cooldown, a holder who gets hit can restart the instant the attacker lets up, and can spam fake rifts as a visual and sound decoy. Set `cancel-cooldown-seconds: 0` for none. Rifts that fail for safety reasons, or that the finale shuts down, never cost a cooldown.

## The look

- **Tear:** one flat display, 2.5 × 6 blocks, full brightness, with a brown Glowing outline set by the display's own glow colour (`#B0703A`, no scoreboard team).
  - It shows six frames (the approved ones), swapped every 2 ticks, with a slight width jitter.
  - About once a second it stutters: a dropped frame, a 0.9–1.06 size jolt and a crackle. The timing is random each cast, and it never shrinks below 90%.
- **Light spill:** a second flat, full-bright display on the ground under it, a soft brown oval.
- **Dust:** six brown motes drifting in at any time, about 3 particles a tick.
- **Closing:** snaps shut over 5 ticks with a crack and a puff of dust.
- **Limits:** at most 2 displays per rift plus 1 for the arrival rift. They are never saved to the world, so a crash or restart cannot leave one behind.
- **Without the pack:** set `display-models: false` and the tear is drawn in dust instead.

## Rift HUD slot

The Rift icon and its status (Ready, a countdown, Active, Sealed, or an orange timer while you're in combat), then the Tuned In meter, are drawn just right of the hotbar, vertically centred on it, at any GUI scale. The rest of the Relay HUD is icons and timers only.

- **How:** it is still part of the action bar. That part uses the pack font `blissgems:hud_low`, which draws the same icon and letters lower, and invisible spacing characters move the pen there and back. So the action bar keeps its width and the rest of the Relay HUD doesn't move.
- **Widths:** placing it needs the pixel width of the text before it. The plugin uses vanilla's letter widths, measured from Minecraft's own font sheet (your pack doesn't replace it), and your Relay icons' widths (`HudText.java`).
  - If you change the icons, or a pack replaces the default font, the slot can drift by a few pixels. Fix that with `rift-hotbar-offset`.
- **Can overlap:** the offhand slot of left-handed players (it sits on the right for them), and the attack indicator when it is set to "Hotbar". Nudge it with `rift-hotbar-offset`, or set `rift-position: inline` to put it back on the action bar.
- **Narrow windows:** the slots reach about 200 GUI pixels right of centre. In a small window at a large GUI scale (for example 1024×768 at GUI scale 3) the end can run off the screen; a smaller GUI scale or `inline` fixes it.
- **Without the pack**, or with `temporal.hud.use-glyphs: false`, both fall back to inline text ("Rift", "Tuned In 60/100").

## Time Dilate restyle

The same clock and animation, plus a mini domain for the caster:

- **Lines are 1.5× thicker.** Every line grew 1/8 of a model unit on each side. The model also went from 492 to 334 pieces.
- **Colours:** brown `#5A3A1C` → `#B0703A`, bronze `#92613B` → `#8C5530`, gold `#C99855` → `#F0C48C`.
- **Glow:** both clock displays get the brown Glowing outline. Turn it off with `temporal.dilate.glow: false`.
- **Particle fallback:** uses the same three colours.
- **Lower clock:** the clock, and the centre of the slowing field, goes 3 blocks below you, or on the floor if that's closer, so it is never buried in the ground. Standing on the ground it is where it always was. `temporal.dilate.clock-depth`.
- **Air-swimming:** while you're in the air inside your own field you swim, exactly as in Temporal Divinity, with the same controls and `swim-*` settings. A soft wall keeps you inside the field. Landing on something puts you back to walking, and jumping starts swimming again. Only the caster swims; everyone else in the field is still slowed. Turn it off with `temporal.dilate.swim: false`.
  - When the field ends, or you're pulled into a domain mid-air, gravity and flight go back to what they were, and you take no fall damage until you land (`landing-grace-ticks`, 10s).
  - Your original flags are also saved on the player, so a crash mid-swim can't leave anyone without gravity; they're restored when you rejoin.

The Glowing outline, like the vanilla Glowing effect, **shows through walls** for anyone within the display's view range. That applies to both the clock and the rift.

## Settings

All under `mythics.relay.rift`. Existing configs get them added on startup.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | false switches Rift off without a rebuild; the HUD slot disappears |
| `cooldown-seconds` | 300 | after a completed jump |
| `channel-seconds` | 5 | |
| `cancel-distance` | 1.5 | blocks you may drift during the channel |
| `cancel-cooldown-seconds` | 10 | after a channel broken by damage, moving, death, logout or letting go |
| `combat-tag-seconds` | 15 | |
| `open-seconds` | 4 | follow window |
| `follow-delay-ticks` | 20 | how long after stepping in a follower arrives |
| `arrival-protection-ticks` | 20 | fall-damage protection only |
| `safe-search-radius` | 5 | blocks around the anchor (max 8) |
| `flash-radius` | 64 | who sees the arrival particles |
| `blocked-worlds` | `[]` | e.g. `[world_nether]` |
| `blocked-areas` | `[]` | boxes, e.g. `- {name: the abyss, world: world, x1: -200, z1: -200, x2: 200, z2: 200}` |
| `width`, `height` | 2.5, 6.0 | rift size in blocks |
| `glow-color` | `#B0703A` | |
| `display-models` | true | false draws the rift in dust |
| `particles` | true | drifting dust |

Elsewhere:
- `mythics.relay.hud.rift-glyph`: `\uE043`. The text fallback is "Rift" when `temporal.hud.use-glyphs` is false.
- `mythics.relay.hud.divinity-glyph`: `\uE044`. The text fallback is "Divinity".
- `mythics.relay.hud.rift-position` and `tuned-position`: `hotbar` (default) or `inline`.
- `mythics.relay.hud.rift-hotbar-offset`: `0`. Nudges the hotbar slots left (negative) or right (positive), in GUI pixels.
- `mythics.relay.temporal.dilate.glow` (true) and `glow-color`.

**The abyss and the Pantheon arenas** don't exist in this plugin, so they can't be detected automatically. Add them under `blocked-areas` (or their worlds under `blocked-worlds`). Blocked spots can't be set as anchors or travelled to.

## Pack assets

| Asset | Path | Referenced as |
|---|---|---|
| Rift frames | `assets/blissgems/textures/item/rift_0.png` … `rift_5.png` (40×96) | item models `blissgems:rift_0` … `blissgems:rift_5` |
| Light spill | `assets/blissgems/textures/item/rift_light.png` (32×32) | item model `blissgems:rift_light` |
| HUD icon | `assets/blissgems/textures/icons/relay_rift.png` (33×20) | font glyph `U+E043` |
| Divinity HUD icon | `assets/blissgems/textures/icons/relay_divinity.png` (33×20) | font glyph `U+E044` |
| Hotbar HUD font | `assets/blissgems/font/hud_low.json` | `U+E043` and the Tuned In frames (`U+E042`, `U+E058`–`U+E05C`) at ascent -44, vanilla letters at ascent -50, spacing `U+E400`–`U+E408` (+1…+256) and `U+E410`–`U+E418` (−1…−256) |
| Dilate clock | `models/item/temporal_clock.json`, `temporal_hand.json` | thicker lines |
| Dilate colours | `textures/item/temporal_brown/bronze/gold.png` | new palette |

- **No custom model data:** the displays are paper with an `item_model`.
- **HUD icon size:** your other Relay icons are 33×20, not 18×18 as the prompt said, so the Rift icon matches them.
- **Rebuilding:** the overlay source now lives unpacked in `resource-pack/overlay/`, and `tools/pack/` rebuilds every new asset.

## Files changed and why

| File | Why |
|---|---|
| `RelayRift.java` (new) | The whole Rift: menu, anchors and their file, channel, follow window, safe landing, combat tag, fall protection, visuals, cleanup |
| `RelayGem.java` | Sneak + left-click opens Rift; ticks it every tick; shuts it down; Rift slot beside the hotbar (`hudComponent`) |
| `HudText.java` (new) | Pixel widths of HUD text and the spacing characters, to place the Rift slot |
| `MythicListener.java` | Sends the Relay HUD as a component so the hotbar slot keeps its font |
| `GemManager.java` | "✦ RIFT [Sneak + left-click gem]" in the Relay's lore; existing Relay items get the line once |
| `TemporalFlow.java` | Brown Glowing outline on the clock; new particle-fallback colours; clock 3 blocks below; caster air-swimming in their own field, with flag restore and landing grace |
| `TemporalDomain.java` | Ends a Dilate swim before the return snapshot, so a captured swimmer gets their real flags back |
| `AirSwim.java` | `seed()`, so a swim that starts from a jump keeps its momentum |
| `config.yml` | `mythics.relay.rift` section, `hud.rift-glyph`, `rift-position`, `rift-hotbar-offset`, `temporal.dilate.glow` and `glow-color` |
| `RelayRiftTest.java`, `HudTextTest.java` (new) | Unit tests for the follow hitbox, landing search order, colour parsing, HUD widths and spacing |
| `resource-pack/` | New rift, light and icon assets, font glyph, thicker recoloured clock; overlay kept unpacked |
| `tools/pack/` (new) | Scripts that generate those assets |

## In-game test plan (not yet run)

Use two accounts (or a friend). Set `cooldown-seconds: 20` while testing, and remember to set it back. Thirteen checks:

1. **Two players following.** Set A somewhere, walk away, channel to A. While the rift is open, the second player walks into it.
   - Expect: they arrive at A about 1 second later, beside you, not inside you.
   - Walk in after 4 seconds: nothing happens.
2. **Cancel by damage.** Have the other player hit you, or take fall or fire damage, mid-channel.
   - Expect: "Rift broken: you were hurt", the tear snaps shut, and a 10s cooldown on the HUD.
3. **Cancel by movement.** Walk 2 blocks mid-channel, then repeat but only jump in place.
   - Expect: walking breaks it ("you moved"); jumping does not.
4. **Combat tag.** Hit the other player, then open Rift.
   - Expect: "Rift unstable: in combat (Xs)" and an "Unstable" HUD slot. Works again after 15s.
5. **Blocked areas.** Add your world to `blocked-areas` (or `blocked-worlds`), `/blissop reloadconfig`, try Set A.
   - Expect: refused with the area's name. Travelling to an anchor already inside it is refused too.
6. **Full inventory.** Fill your inventory and use the menu.
   - Expect: no difference (the menu is its own window and gives no items); buttons can't be taken out with clicks, shift-clicks or drags.
7. **Logout mid-channel.** Log out during the channel and log back in.
   - Expect: no rift left behind, and a 10s cooldown.
   - Also: log out the holder while the rift is open; a player who already stepped in still arrives.
8. **Restart with saved anchors.** Set A and B, stop and start the server, open the menu.
   - Expect: both anchors are still listed, and any cooldown carries over.
9. **Finale lockout.** Start the finale (`/turmoil finale ...`) with a rift channelling and one open.
   - Expect: both collapse with no cooldown, the menu says sealed, and anchors can't be set until it ends.
10. **Safety.** Set an anchor on a 1-block pillar over a ravine, then break the ground under it before travelling.
    - Expect: you land on the nearest safe block within 5, or "no safe ground", with no cooldown used.
11. **Visuals.** Check day and night:
    - the tear glows brown, stutters about once a second, and dust drifts in;
    - the light spill sits on the ground;
    - the arrival flash is visible from about 60 blocks;
    - the Dilate clock is thicker, brighter and outlined.
12. **HUD slot.** Hold the Relay at a few GUI scales and window sizes.
    - Expect: the Rift icon sits just right of the hotbar, centred on it, and the rest of the Relay HUD doesn't move as timers change.
    - If it is a few pixels off, adjust `rift-hotbar-offset`.
13. **Dilate mini domain.** Cast Time Dilate while standing, then while jumping off something high.
    - Expect: on the ground the clock is at your feet; in the air it is 3 blocks below you, or on the floor.
    - In the air you swim and can't leave the field. Landing makes you walk; jumping makes you swim.
    - When it ends mid-air you fall without fall damage. Afterwards you have normal gravity and can't fly.
    - Others in the field are still slowed and can't swim.

## What was and was not tested

| Kind | Result |
|---|---|
| Code inspection | `RelayGem`, `MythicListener`, `TemporalFlow`, `TemporalDomain`, `Finale`, `GemManager` read; every Relay input path checked so sneak + left-click reaches only Rift |
| Compilation | `mvn clean package` succeeds against `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` on Java 21 |
| Unit tests | 19 pass: 6 for Rift (follow hitbox from several facings, landing search order and range, colour parsing), 4 for the HUD widths and spacing, and the 9 existing swimming tests |
| HUD placement | Checked against a drawn mock-up using vanilla's layout constants, font sheet and your icons; not seen in a real client |
| Pack checks | Every JSON parses; every new item model and texture resolves; models stay inside the legal -16..32 range; one new font glyph, no duplicates; the merged full pack carries the same files |
| **Live gameplay** | **None.** Not run on a server or client: the feel, visuals, glow, multiplayer following, restart persistence and the finale lockout are all unverified |
