# Heretic: no saw sweep particles; Bloodlinking slams on contact and bounces at once

## What I found first
- **The sweep particles** didn't come from the throw (that's only a sound). They came from saws in flight: when a saw hit an entity, and when it cut a cobweb.
- **Bloodlinking:**
  - **The sequence:** launch (1.35), dive toward the block you look at 14 ticks later, then wait to touch the ground (up to 40 ticks) and slam there.
  - **A bounce:** set 0.95 upward and dived again 8 ticks later.
  - **Only players were hit.** Mobs took nothing, so the zombie test would have shown nothing.
  - **The rule:** a slam bounced if any player had been linked so far and slams were left.
  - **Fall damage** wasn't blocked.
- **Controls:** main-hand right-click is Bloodlinking, and left-click into the air (or off-hand right-click) throws saws. The Heretic's GemType description, and the simulator, had them the other way round. The simulator is fixed.

## What changed
- **Bloodsaws:** no more `SWEEP_ATTACK` particles, on hits or cobwebs. Every other saw visual and sound is unchanged, and so are Spore and the passives.
- **Bloodlinking:**
  - **The slam on contact:** the tick your hitbox newly overlaps a living entity's, rising or diving, the slam happens right there, centred on where you touched. The space you moved through that tick counts, so a fast dive can't skip a body.
    - Someone you're already overlapping when a flight starts doesn't count until you've parted, so a bounce doesn't slam the same body again at once.
    - Otherwise it still slams on landing (or after 40 ticks).
  - **The slam** hits every living entity in the radius, players and mobs, armour pierced (×2 on bleeding targets through Hemorrhage, as before). Players are bloodlinked up to `max-victims`, as before.
  - **Hit anyone:** you're launched straight back up that same tick (`bounce-launch-y`), then dive again after 8 ticks, as bounces did.
  - **Hit nobody:** the old rule (bounce only if someone is already linked).
  - **Slam count:** every slam counts toward `max-slams`.
  - **Fall damage:** none for the holder during Bloodlinking or for 2 s after it ends.
- **Stronger** (`mythics.heretic.bloodlinking`, with comments):
  - `launch-y` 1.35 → 1.9, `damage` 9 → 11, `max-victims` 2 → 4;
  - new `bounce-launch-y` (1.9);
  - `cooldown-seconds` 100, `radius` 4 and `max-slams` 3 are unchanged.
  - Bounce launch is also in `/mythicconfig`.
  - **Your server's config:** values still on the old defaults (1.35, 9, 2) are moved to the new ones once, at the next start. Anything you changed by hand is left alone, and the console lists what changed.

## Files changed
- `MythicListener.java`: the sweep particles removed; Bloodlinking's flight (contact check, slam at the contact point, instant bounce, end, no fall damage) and cleanup on quit; the bounce launch in the tuner.
- `HereticMath.java` (new): the bounce rule, the swept hitbox and the contact point, unit-tested.
- `SoulboundGemsPlugin.java`: the one-time balance update for the new defaults.
- `config.yml`: the new defaults and `bounce-launch-y`, with comments.
- `BloodlinkBounceTest.java` (new), `BalanceConfigTest.java` (Bloodlinking damage now 11), `CHANGELOG.md`.
- The simulator: the Heretic's buttons swapped to match the plugin, plus the new Bloodlinking.

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 104 unit tests pass (6 new):
  - a hit bounces at once;
  - a miss keeps the old rule;
  - `max-slams` is respected;
  - a dive that passes through a body counts as a touch, centred inside it;
  - the new defaults;
  - no sweep particles left in the Heretic code.
- **Not tested:** nothing was seen in game.

### In-game test plan
1. Throw saws: no sweep particles.
2. Bloodlinking into a group of zombies: you bounce straight back up the moment you hit them, up to 3 slams.
3. Bloodlinking straight into a zombie in mid-air: it slams on contact without reaching the ground.
4. Bloodlinking onto empty ground: it works as before.
5. No fall damage at the end.

# Auratus hook drawn the right way round

- **The cause:** item displays draw their model turned 180° about Y (vanilla's item display renderer does it). The plain chain strands are symmetric, so it never showed on them. The hook (chain_end, custom model data 12349) has its claw at one end, so it was drawn backwards.
- **Why the earlier fix didn't take:** `drawChain` built its own copy of the hook's pose instead of using the shared one.
- **The fix:** one `hookPose()` now turns the hook display 180° about its own Y (`HOOK_TURN`, the transformation's right rotation), cancelling the renderer's turn. The claw sits on the target and the hook's own links trail back toward the hand.
  - `drawChain`, the flight out and the reel-in all use it. The reel-in keeps the hook's rotation as it flies back.
  - The strands aren't turned; the hook and the first strand still share a link exactly, so the chain joins in step.
- **Your pack:** this draws the model exactly as it was built in Blockbench, so the claw faces' original rotations are the right ones. Undo the texture flip: up/down back to 180, east/west back to 270.
- **Test:** `HookOrientationTest` works out positions the way the client draws an item display, its 180° turn included. It checks:
  - the claw's point is on the target and the hook's links run back toward the hand, in 5 directions;
  - without the turn the claw would point back;
  - the hook and the first strand share a link exactly;
  - the flight's travel offset doesn't change the facing.
- **Files changed:** `AuratusGem.java` (`HOOK_TURN`, `chainRotation`, `chainLift`, `hookPose`, `strandPose`, the layout and `drawChain` all on the shared poses), `HookOrientationTest.java` (new), `CHANGELOG.md`.
- **Tested:** it compiles against paper-api 1.21.11, and all 98 unit tests pass. **Not tested:** nothing was seen in game.

# New brown haze frames for the finale overlay

- **The new frames:** the five frames you sent are now the brown (`gold`) style's camera-overlay frames, `gold_0..4`, played in the order sent. Frames 0 and 2 are the same image, so the loop runs A, B, A, C, D.
- **Fades:** `gold_fade_1..4` were remade from the new first frame (20/40/60/80%).
- **Frame counts per style:** new `finale.overlay.frame-count` (`{gold: 5, red: 6}`), so a style can have any number of frames. The red frames and the Turmoil's red haze are unchanged.
- **`gold_5.png`** is now a copy of the new first frame. It's only kept so the old E105 font glyph still has a texture.
- **The pack:** `Relay-Temporal-Pack.zip` rebuilt (93 files).
- **Files changed:**
  - `Finale.java` (frame count per style), `Turmoil.java` (red frame count from the same setting);
  - `config.yml`, `CameraOverlayTest.java` (checks every frame the setting asks for exists in the pack);
  - the pack's textures and README, and `CHANGELOG.md`.
- **Tested:** it compiles against paper-api 1.21.11, and all 94 unit tests pass. **Not tested:** nothing was seen in game.

# Finale and Turmoil overlays: camera overlays instead of titles

- **Why they were invisible:** the haze was a title glyph (E100-E105 / E110-E115, font height 288). Titles are drawn at the GUI scale ×4, so the image came out 2-3× larger than the screen, and the haze, which is only at the image's edges, was off-screen. A title's size can't suit every GUI scale.
- **Now a camera overlay**, the way a carved pumpkin's blur is drawn:
  - **The fake helmet:** each player is sent a fake helmet only they see (`sendEquipmentChange` for their own head). It's paper with an equippable HEAD component, `camera_overlay` set to the pack's `blissgems:overlay/gold_0..5` (or `red_0..5`), and no `asset_id`, so no helmet model is drawn, even in third person.
  - **The real helmet is never touched.** It's sent back on stop, end, death, respawn, world change and plugin disable; logging out needs nothing.
  - **If the real helmet changes** during the overlay (put on, taken off, damaged), the overlay goes back on the next tick.
  - **Camera overlays fill the screen at any GUI scale.** They show in first person only, as vanilla does.
- **Animation:** the next frame's helmet every `overlay.frame-ticks`. Frames pause during the brown flash, as before.
- **Fades:** camera overlays have no alpha setting, so a fade steps through fainter frames. The pack now has `gold_fade_1..4` and `red_fade_1..4` (20/40/60/80% of frame 0, made by `tools/pack/overlay_fades.py`).
  - It fades in over `fade-in-ticks` and out over `fade-out-ticks` (the sky resets at once; only the overlay fades).
  - The Revenant's red pulse fades out red.
  - New `finale.overlay.fade-steps` (4) is the number of fade frames: 0 means no fade, for an older pack without them.
- **Turmoil's red haze** (`turmoil.redsky.overlay`) uses the same camera overlay with the red frames.
- **Unchanged:**
  - `overlay.enabled`, `overlay.style`, `frame-ticks`, and the per-player overlay toggle (`/turmoil overlay`), which now gives that player their real helmet back at once;
  - the brown flash, still a title, which works.

## Files changed
- `CameraOverlay.java` (new): the fake-helmet overlay for one player at a time. It handles show, clear and clear-all, the real helmet's return on death, respawn and world change, re-showing after a real helmet change, and forgetting on logout.
- `Finale.java`: the sky's overlay as camera-overlay frames, the stepped fade-in, the fade-out task after the sky stops, and cleanup on shutdown.
- `FinaleMath.java`: `fadeInLevel()` and `fadeOutLevel()`.
- `Turmoil.java`: the red haze as a camera overlay, cleared on stop and on the per-player toggle; unused title imports removed.
- `SoulboundGemsPlugin.java`: creates and registers the overlay, and gives everyone their real helmet back on disable.
- `config.yml`: `finale.overlay.fade-steps` (4), plus comments.
- `resource-pack/overlay`: 8 fade textures and a README note. `Relay-Temporal-Pack.zip` rebuilt from it (93 files).
- `tools/pack/overlay_fades.py` (new), `CameraOverlayTest.java` (new), `CHANGELOG.md`.

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 94 unit tests pass (3 new):
  - the fade timing;
  - every overlay texture the plugin can ask for exists in the pack (a missing one would paint the screen magenta);
  - neither Finale nor Turmoil draws the overlay as a title any more.
- **Not tested:** nothing was seen in game. Things to check:
  - the haze at a few GUI scales;
  - the fades;
  - putting a helmet on and off mid-finale;
  - death and respawn during the finale;
  - `/turmoil overlay` off and on.

# Auratus: let go of the chain, the chain flies out and reels in; mythic HUD numbers

## What I found first
- **The chain:** it was instant. `aim()` picked the target, a charge was spent, and `hook()` landed the hook the same tick, with damage, sounds and the tether. A throw that found nothing already cost no charge.
- **The mythic HUD:** it already showed seconds while a single ability recharged. Abilities with charges (Auratus chains, Heretic saws, Relay Rewind) only said "Ready!" while any charge was left: no count, and no timer for the one recharging.
- **The fireball:** nothing in the plugin changes its size. It's vanilla's small (blaze) fireball, which the game always draws at one fixed size. No display entity, scale, per-tick update or resource-pack model touches it, and its charge only sets the explosion power. **Not changed:** what looks like shrinking is most likely perspective, since it leaves from your eyes and races away.

## What changed
- **Letting go (Q, the drop key, while a chain is out):**
  - **Why Q:** dropping the Auratus is already blocked (`protection.prevent-drop`), and every other button is taken: clicks, sneak-clicks and F.
  - **Before it bites anything** (still flying, or what it was flying at died or left), the charge comes back (`mythics.auratus.release.refund-before-hit`).
  - **After it bit a player, mob, block or the Echo Anchor,** the charge is spent as normal.
  - **While you're flying to the hook,** you let go and keep your momentum.
  - **Off-hand edge case:** Q only fires when the main hand holds something, so a player with the Auratus in the off hand and an empty main hand can't release.
- **The chain flies out** (`visuals.chain-travel`, `chain-travel-ticks` 5):
  - The hook leaves your hand and flies to its target, pointing at it the whole way, with the links appearing behind it as it passes.
  - The bite (damage, Angels Grasp, the clink, the hit particles) happens when it arrives; the throw sound plays when you fire.
  - **A moving target:** the hook follows it and still lands on it. The target and range are unchanged (`aim()` still chooses).
  - **Buttons pressed mid-flight:** right-click (go there) and F (haul) are queued for the landing, so they still mean what they did instead of casting the Aegis.
  - **Throwing again or slamming** while one is flying refunds the one that hit nothing.
- **The chain reels in** (`visuals.chain-reel-in`, `chain-reel-ticks` 4): when you let go, or when the hook slides out after 6 s, the hook flies back to your hand and each link disappears as it passes. Snapping on a wall is still instant.
- **How it's drawn:** by display interpolation, recorded like any other entity data, so it plays back smoothly in Replay Mod and Flashback.
  - **The hook:** its translation is set once to the landing spot, with a duration. It's set again only if the target moves.
  - **The links:** spawned once each where they'll lie, and never moved during the flight.
  - **The reel-in:** the hook's way back is one move, set once.
- **Going to the hook** now keeps the same chain on screen while you fly, instead of clearing and respawning it.
- **Mythic HUD numbers** (`mythics.hud.show-numbers`, true):
  - **Abilities with charges** show what's left and the seconds until the next one: `Ready! 1/2 6s`, or `6s 0/2` when none are left. That's the Auratus chains (also `… 1/2` while a hook is out), the Heretic's saws and Relay's Rewind.
  - **Single cooldowns** keep showing their seconds.
  - **Relay** keeps all three of its entries, each with its own timer.
  - **Off:** icons only, dimmed until ready.
  - **Style:** colours and layout unchanged.
- **Cleanup:** every flying or reeling model goes on release, logout, death, world change and plugin disable.
- **Unchanged:** damage and every cooldown length.

## Files changed
- `AuratusGem.java`:
  - the flight, landing, release (Q), reel-in and cleanup;
  - right-click and F queued in flight;
  - the shared chain layout (`layout()`);
  - fly-to-hook keeps the chain;
  - the HUD numbers.
- `Charges.java`: `refundLatest()` and `nextRecharge()`.
- `HudStatus.java`: number-aware `entry()` and `charges()`.
- `AuratusMath.java`: `hookPassed()` (when a link appears) and `passTick()` (when a link goes on reel-in).
- `MythicListener.java`, `RelayGem.java`: the Heretic and Relay HUD use the numbers setting.
- `config.yml`: `mythics.hud.show-numbers`, `mythics.auratus.release.refund-before-hit`, and four `mythics.auratus.visuals.chain-*` keys.
- `AURATUS.md`, `CHANGELOG.md`, `ChainFlightTest.java` (new).

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 91 unit tests pass (6 new): the refund, next-recharge timing, links appearing from the hand outward, link removal timing, HUD formats with numbers on and off, and the settings.
- **Not tested:** nothing was seen in game or in a recording.
  - **To check:** the flight and reel-in look, the hand-off from the flying hook to the attached chain, and Replay Mod and Flashback playback.

# Divinity void world: clearer ownership

- **Why it was refused:** on a test server the void world folder existed, but `plugins/SoulboundGems/temporal-world-soulbound_temporal_void.marker` didn't (the plugin folder was reset or not copied). The plugin rightly refused to adopt an unmarked world, and Divinity stayed off.
- **A second marker:** the plugin now also writes one inside the world folder (`soulbound-temporal-divinity.owner`), so resetting the plugin folder no longer orphans the world. Either marker counts.
- **A clearer error:** when an unmarked folder is found, the error now says what to do. If it's the old void world, stop the server and delete the folder (it's recreated in moments); if it's a real world, set `mythics.relay.temporal.divinity.world` to another name.
- Compiles against paper-api 1.21.11. All unit tests pass. Not run on a server.

# Two crash fixes: the Divinity void world and progression checks

## Temporal Divinity froze the server (watchdog kill)
- **Cause:** the void world `soulbound_temporal_void` was created the first time anyone used Divinity, on the main thread. Its generator never said where spawn is, so the server generated chunk after empty chunk looking for somewhere to put it, then prepared the spawn area. It took 101 s, and the watchdog killed the server.
- **Fix:** the world is now created or loaded once, when the plugin starts. Divinity never creates or generates a world mid-game.
  - The world is made with `keepSpawnLoaded(TriState.FALSE)`, so no spawn area is kept loaded.
  - The void generator gives a fixed spawn point (`getFixedSpawnLocation`), so there's no search.
  - The spawn chunk radius game rule is set to 0 where the server still has it. Minecraft 1.21.9 removed spawn chunks and that rule, so on 1.21.11 there's nothing to set.
- **Arena chunks:** every arena's chunks (5 × 5 per session slot) are generated in the background at startup. A cast only loads them, holds them with plugin chunk tickets while the session runs, and releases them when it ends. Nothing else in that world is kept loaded.
- **If the world can't be prepared** (for example, a folder of that name not made by this plugin), the console says why and Divinity stays off until a restart. Before, it would have tried again mid-game.
- **Your server already has the world** from the crash; it's simply loaded at startup now. The console logs how long that took ("Temporal Divinity void world ... ready in N ms").

## Progression check crashed on empty slots
- **Cause:** `ProgressionManager.enforceProgression` built `List.of(inventory contents)`. Empty slots are null, and `List.of` throws a NullPointerException on null.
- **Fix:** it now collects only the filled slots (`present()`, which skips nulls).
- **Test:** `ProgressionInventoryTest` checks null slots are skipped. It also fails if any code builds a `List.of` from inventory contents again.

## Files changed
- `TemporalDomain.java`: `prepareWorld()` at construction (plugin enable), `VoidGenerator` with a fixed spawn, and `getSky()` no longer creates anything.
- `ProgressionManager.java`: `present()`, and the null-safe inventory walk.
- `ProgressionInventoryTest.java` (new), `CHANGELOG.md`.

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 85 unit tests pass (2 new).
- **Not tested:** nothing was run on a server. Things to check in game:
  - the startup log line and its time;
  - the first Divinity cast (no freeze);
  - `/tps` during a cast;
  - joining with empty inventory slots while progression limits apply.

# Auratus firework bursts and the Flagpole under a ceiling

## What changed
- **One helper for every burst:** `burst(at, colour, fade)`, a large-ball firework detonated at once. It's still the plugin's only firework, and it's tagged so its damage is cancelled.
- **Gilded Slam:**
  - **The sphere:** it's now white, fading to a cold pale blue (#DDE6FF), at a random spot 1.5-3 blocks from the landing and 0.5-2.5 up. It moves to a new spot every slam, and to 1 block above the landing if the random spot is inside a block.
  - **The sparks:** 30 firework sparks (`visuals.slam-spark-count`). Each starts from its own random point within 0.8 × the slam radius, 0.2 above the ground, with its own direction. They're spread over the first 10 ticks, so they shoot up a few at a time and drift back down. Everyone nearby sees them.
- **The launch:** a gold sphere (#FFC846 to #FFF0B4) where the victim stood, chest height. Its own blast replaces the launch's blast sound; the sonic-boom layer still plays.
- **Flagpole:**
  - **The sphere:** a gold sphere on the victim as they're hoisted. It adds a firework blast to the chain and bell sound.
  - **Under a ceiling:** the hold no longer stops below a ceiling. It always aims for the full height (5), so in a 2-high tunnel the victim flies up into the ceiling and stays pinned there for the hold.
  - **Same movement as before:** gravity off and upward speed every tick, never a teleport. Fall distance resets every tick, and the normal release applies.
  - **Never stuck in a block:** a victim who is already inside a block when the hold starts is released at once, and so is one who ends up in a block mid-hold. `flagpole.ceiling-cap: true` brings back the old stop-below-the-ceiling behaviour.
- **Settings** (`mythics.auratus.visuals`, all true):
  - `slam-burst`, `slam-sparks`, `slam-spark-count` (30), `launch-burst`, `flagpole-burst`;
  - plus `flagpole.ceiling-cap` (false).
  - The old `slam.firework` switch is gone from the shipped config. If your server's config still has `slam.firework: false`, the slam sphere stays off.
  - The `/mythicconfig` Auratus page has no on/off switches (only numbers), so these aren't added there.
- **Unchanged:** damage, timing, cooldowns and controls.

## Files changed
- `AuratusGem.java`:
  - `slamFirework()` became `burst()`;
  - new `randomNear()`, `sparks()` and the stuck-in-a-block check;
  - the slam, launch and Flagpole bursts;
  - the Flagpole height change;
  - the spark task is cancelled on disable.
- `AuratusMath.java`: `randomOffset()`, the random spot's maths, unit-tested.
- `GemSounds.java`: `playLayer()`, so the launch can play only its sonic boom while the burst makes the blast.
- `config.yml`: `mythics.auratus.visuals` and `flagpole.ceiling-cap`; `slam.firework` removed.
- `AURATUS.md`, `SOUNDS.md`, `CHANGELOG.md`.
- `FireworkSafetyTest.java` (new):
  - fails if any code other than `burst()` spawns a firework, or if `burst()` stops tagging it;
  - checks the random spot stays in its ring, and the new settings.

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 83 unit tests pass (3 new).
- **Not tested:** nothing was seen in game. Unchecked:
  - how the sparks look;
  - the bursts' placement;
  - the Flagpole in a tunnel;
  - Paper 26.2.

### In-game test plan
1. Gilded Slam on a group: a white sphere bursts near the landing, and about 30 sparks shoot up over half a second and drift down. Slam three times: they land differently each time, and the sphere is never inside a wall. Nobody takes damage.
2. The launch: the victim flies up and a gold sphere bursts where they stood.
3. Flagpole: a gold sphere bursts on the victim as they're hoisted.
4. Flagpole in a 2-high tunnel and a 3-high cave: the victim flies up and is pinned against the ceiling for the full hold, with no fall or suffocation damage when released.
5. Turn each `visuals` toggle off and reload: only that effect stops.
6. Stand near any burst with 1 heart left: no damage.

# Abilities hit harder: they pierce armour, and the weakest went up

Gem abilities barely scratched Protection IV diamond (a Bloodsaw did 0.2 hearts). Simply raising the numbers would have made them one-shot players in iron or no armour, so ability hits now **pierce armour points**, like magic. Protection and Resistance still reduce them, and shields don't block them. A hit now lands about the same on everyone, and Prot IV takes 64% off. The weakest ones also got bigger numbers.

| Ability | Before: hearts through Prot IV diamond | Now | Now: no armour or plain iron |
|---|---|---|---|
| Crisp (15 s) | 0.7 | 2.2 | 6 |
| Breezy Bash (60 s) | 1.2 | 3.2 | 9 |
| Dash (40 s), 3 hits | 1.4 | 4.9 | 13.5 |
| Kinetic Burst, full charge | 2.8 | 5.6 | 15.5 |
| Energy Beam, 60 watts | 2.5 | 5.1 | 14.2 |
| Bloodsaw (5 → **7**), bleeding ×2 | 0.2 / 0.5 | 1.3 / 2.5 | 3.5 / 7 |
| Bloodlinking slam (6 → **9**), ×3 | 0.3 each | 1.6 each | 4.5 each |
| Astra dagger (now **2.5 × 3 = 7.5**), ×5 a volley | 0.9 each | 1.35 each | 3.75 each |
| Blur clone hit (now **1 × 3 = 3**, at most once a second per clone) | 0.2 | 0.54 | 1.5 |

- **Unchanged:**
  - The Auratus and the Revenant: their hits still go through armour, because they were tuned with it.
  - The fireball's blast (still up to about 5 hearts through Prot IV diamond at full charge).
  - Melee and fall damage.
- **Settings:**
  - `ability-damage.pierce-armor` (true). Set it to false for the old behaviour.
  - `abilities.blur.clone-damage` (1.0).
  - `astra.dagger-hit-damage` (2.5). It replaces `astra.dagger-damage`, which was the arrow's base damage and is no longer used.
  - `mythics.heretic.bloodsaws.damage` (7) and `bloodlinking.damage` (9).
- **Your server's config:** on the next start, if the Heretic numbers are still the old defaults (5 and 6) they become 7 and 9, once. Numbers you changed are left alone, and the console says what changed.
- **How the hits work now:**
  - Dagger hits replace the arrow's own damage. They still disable the target's gem for 8 s and make them glow for 10 s, as the spectral arrow did.
  - Blur clones no longer deal vex damage of their own.
- **Bleeding does no damage by itself.** It only doubles the Heretic's damage (Hemorrhage).
- **Tests:** `BalanceConfigTest` checks the new keys and that the Auratus still doesn't pierce. 80 unit tests pass. Not seen in game.

# Astra Daggers fixed, and the Relay's description

- **The cooldown bug:** every left-click restarted the 90 s cooldown, so only one of the five hovering daggers could ever be fired. Now each left-click fires one (0.3 s apart), the cooldown starts after the fifth, and all five come back when it ends. Switching items doesn't refill a half-used volley. The old 3-second per-dagger respawn is gone (the volley replaces it).
- **They look like daggers now:**
  - The hovering daggers use the pack's Astra dagger model (`blissgems:custom/astra_dagger`, custom model data 12350) instead of the Spirit Dagger.
  - A fired dagger flies as that model, tip first along its path.
  - The arrow that does the flying and the hit is hidden from everyone.
  - When it hits a wall it sticks there for a second, then goes.
  - Like the chains, the hovering daggers no longer turn with the way you face.
- **The gem-disable on hit never worked:** the dagger arrow had no shooter, and the hit code only disables the target's gem (8 s) when the shooter is a player. It now has one.
- **No more free spectral arrows:** dagger arrows can't be picked up, and they're removed after landing.
- **Disabling the plugin** removes the hovering and flying dagger models.
- **`astra.dagger-damage` comment fixed:** it's the arrow's base damage. Minecraft multiplies arrow damage by speed, so 5 at speed 3 is about 15 (7.5 hearts) before armour, not 2.5 hearts. The damage itself is unchanged.
- **Relay description:** the `GemType` text said Rewind was right-click and Time Dilation left-click, and it still described the removed off-hand compass and Time Lock. It now matches the code:
  - Rewind is left-click and Time Dilate is right-click.
  - Temporal Divinity is sneak + right-click and Rift is sneak + left-click.
  - Tuned In needs a full bar.

  The Relay's item lore was already right.
- **Tests:** `AstraDaggerTest` fails if a left-click starts the dagger cooldown before the volley is spent, and checks that the flying dagger points along its flight. 78 unit tests pass. Not seen in game.

# Menu layout and capture points

## Menus
- **`/bliss menu`:**
  - The top and bottom rows are black glass, with a "Soulbound Gems" title at slot 4.
  - Tier 1 gems are on row 1 and Tier 2 gems on row 2, each split four and four by a label.
  - Mythics are at 38, 40 and 42 under a "Mythics" label.
  - The five actions are centred on the bottom row (slots 47-51, named constants the click handler uses too).
- **`/bliss config`:**
  - The hub has the same frame, with all eight gems on row 2, Heretic at 39 and Auratus at 41.
  - On each gem's page, the settings are centred on slot 22 for any count (`settingSlot()`, which the click handler uses too). Back stays at 49.
- **Labels** do nothing when clicked. `/mythicconfig` is unchanged.
- Your `MenuGUI.java` and `ConfigGUI.java` were exactly the repo's files plus `menu-layout.patch`, so they went in as sent. The only change: `settingSlot()` is package-visible, so it can be tested.

## Capture points (new, see CAPTURE.md)
- **Commands:** `/capture create|color|start|stop|list|info|remove|tp`, with `/fill`-style coordinates and tab completion everywhere. Points are saved in `capture-points.yml`. A capture running at a restart is stopped and reported, not resumed.
- **Rules:**
  - Alone on the point, you gain time. Untrusted players together contest it. Trusted players are one side and all gain.
  - Your time is kept when you leave or die.
  - Spectators, vanished players and the Revenant holder can't capture.
- **The win:** first to the hold time gets the mythic reward (refused at start if that mythic already exists, unless `force`), plus a title, a chat message and a burst.
- **HUD:**
  - A boss bar within 64 blocks, in the point's colour.
  - Your own time on the action bar, added to the end of the gem, mythic and Revenant HUDs when one is showing.
  - Sounds under `sounds.capture`.
- **Visuals:**
  - Flat coloured text displays tile the floor (at most 32) and pulse through interpolation.
  - Bands of light rise and fade from the edges (at most 24).
  - Optional coloured dust.
  - Everything is removed on stop, win, remove, chunk unload, plugin disable and server stop.
- **Rifts:** no anchoring in, travelling to, or opening from a running point.

## Files changed
- `MenuGUI.java`, `ConfigGUI.java`: the new layout (your files).
- `CaptureMath.java` (new): coordinates, the box, floor tiles, the hold timer, colours. No server needed, so it's unit-tested.
- `CaptureManager.java` (new): points, saving, the rules, HUD and visuals.
- `CaptureCommand.java` (new): `/capture` and its tab completion.
- `SoulboundGemsPlugin.java`: starts and stops capture points, and registers the command.
- `plugin.yml`: the `capture` command and the `soulbound.capture` permission (op).
- `RelayRift.java`: rifts are blocked in running capture points (in the existing blocked-spot check, plus the spot you cast from).
- `RevenantMace.java`: `isHolder()` is package-visible, so capture points can exclude the Revenant. The Revenant HUD shows your capture time.
- `GemListener.java`, `MythicListener.java`: the gem and mythic HUDs show your capture time at the end.
- `config.yml`: the `capture:` section and `sounds.capture` (step-on, contested, win).
- `GemSoundsTest.java`: `capture` is skipped like `other` in the shared-sound check (it isn't a gem).
- `SOUNDS.md`, `CAPTURE.md` (new), `CHANGELOG.md`.
- Tests (new): `MenuLayoutTest` (`settingSlot()` for 1 to 5 settings, including 3 and 4; no two buttons on one slot), `CaptureMathTest` (`/fill` coordinates absolute, `~`, `~5`, `~-3`; box normalising and the size limit; floor tiles; hold, contested and trusted timer logic; colours).

## Tested and not tested
- **Tested:** it compiles against paper-api 1.21.11, and all 76 unit tests pass (15 of them new).
- **Not tested:** nothing was run on a server or seen in game. Especially unchecked:
  - the text-display tile size and centring (the standard 1-space trick, scale 8 × 4 per block);
  - the interpolated pulse and the bands rising;
  - the boss bar;
  - tab completion with spaces;
  - Paper 26.2.

Use the test plan in CAPTURE.md.

# Trader: no mythics, and your config

- **A mythic can't be traded:** using a Trader while holding the Heretic, Auratus or Relay says "Mythic gems can't be traded." and the Trader isn't used up.
- **A Trader never makes a mythic:** the new gem comes from `GemType.tradePool`, which is every standard gem except the one you hold. Before, the pool was every gem type, mythics included.
- **TraderTest** checks the pool, and also reads GemListener's `case "trader"`. It fails if the mythic check goes, if the pool goes back to `GemType.values()`, or if the Trader would be used up before the check.
- **config.yml is your current one.** Compared with the old bundled copy:
  - Revenant `boss.max-health` is 60 (30 hearts). The code default and REVENANT.md now say 60 too.
  - The brown finale flash is on, holding 20 ticks and fading over 60.
  - Heretic `bloodsaw-hit` is the sweep sound.
  - Relay Rewind and Time Dilate fall back to one vanilla sound each, with no layer (SOUNDS.md updated).
  - Your comments are in (`blocked-areas` TODO, the overlay style note, the fade keys).
  - One exception: `chain.link-spacing` stays out, since the chain fix made it unused.
- 61 unit tests pass. Not played in game.

# Auratus chain drawn where it really is

- **It points the right way:** the chain pieces were spawned facing the way you faced, and a display model turns by its own facing before its pose. The chain was only right facing south (facing north it pointed backward). Every Auratus model now spawns facing nowhere. The same fix stands the Aegis shield square to you and keeps the Echo Anchor ring level; before, they turned with you as well.
- **The hook's point is on the real hook point:** the hook model was centred there, so its claw drew about 1.2 blocks past it. On a block hook the point now reaches the block's face.
- **It's on the line:** the chain model sits half a block below its centre; it's lifted back up.
- **It starts at your hand:** the hand holding the gem (main or off hand, left-handed players too), not your chest.
- **The pieces join end to end:** each piece is a strand of links about 3 blocks long. They were placed every block, three deep with the links out of step. Now each strand follows the last 7 links on, so the links carry on in step. The last one is pulled in whole links so the chain ends at your hand, not behind you. A short chain is just the hook, scaled to fit.
- **`chain.link-spacing` is gone** (spacing comes from the model now). A leftover line in your config is ignored. `link-scale` still sizes the links.
- **Tests:** `AuratusMathTest` checks the layout for every chain length from 0.5 to 45 blocks: the hook's point on the tip, the chain ending at the hand, no gaps, links in step, nothing on the hook's claw. 58 unit tests pass. Not seen in game.

# Relay sounds boosted to vanilla level

- `rewind.ogg` and `time_dilate.ogg` are peak-normalised to about -1 dB, where vanilla sounds sit, with a little headroom so nothing clips. It's a straight volume increase (Rewind +8 dB, Time Dilate +12.7 dB), so the sound itself is the same.
- Still mono Ogg Vorbis at 48 kHz, same length.
- Your untouched originals are in `tools/pack/source/sounds/original/`.
- Pack only; the plugin is unchanged.

# Relay: your Rewind and Time Dilate sounds

- **Your sound files are in the pack:** `rewind.ogg` and `time_dilate.ogg` are at `assets/blissgems/sounds/relay/` and registered in `sounds.json` as `relay.rewind` ("Time rewinds") and `relay.time_dilate` ("Time slows").
  - Both are mono Ogg Vorbis at 48 kHz, so they fade with distance like any other sound.
  - Rewind is 3.6 s and Time Dilate is 4.0 s. They're kept exactly as sent. Copies live in `tools/pack/source/sounds/` so the pack script rebuilds them.
- **The config now plays them:** `sounds.relay.rewind` and `sounds.relay.time-dilate` use `blissgems:relay.rewind` and `blissgems:relay.time_dilate`.
  - With `sounds.custom-enabled: false` they fall back to the spec's vanilla pairs (beacon deactivate and chorus fruit; beacon ambient and bell resonate).
  - Each pack sound plays on its own, with no extra vanilla layer.
- **Tests:** `GemSoundsTest` also checks the fallbacks, and that Relay uses its pack sounds. 57 unit tests pass. Not played in game.

# Heretic pedestal in red

- The Heretic's finale pedestal has red trim (the vanilla redstone block texture) instead of amethyst. Its floating label, guide beam and arrow text are red too. The old block pedestals (`finale.pedestal.custom-model: false`) use a redstone block.
- 56 unit tests pass. Not run in a game.

# Sound overhaul; finale pedestals on the ground, settable, and with custom models

## Sounds (your spec)

- **Every gem sound now comes from `config.yml`:** `sounds.<gem>.<ability>` with `event`, `volume`, `pitch`, `enabled`, and at most one `layer`.
  - 71 abilities, 99 sound parts in all. The full table is in SOUNDS.md.
  - `sounds.custom-enabled` and `sounds.volume` stay; `sounds.enabled-events` is gone.
  - Unknown sound names are listed in the console at startup.
- **Your table, as written:** Strength, Fire, Speed, Puff, Astra, Flux, Nullify, Wealth, Life, Heretic, Auratus, Relay and gem disable.
- **Removed everywhere:** Elder Guardian curse, Wither spawn (Astral Void and the heart pedestal), lightning thunder (Speedy Storm and Energy Beam), portal travel (Divinity) and the XP orb.
- **Speedy Storm** no longer strikes real lightning, which made world-wide thunder for every bolt. Its bolts are now jagged particle lines from a cloud puff to a spark burst, with a low storm cloud over the area, and the bolts make no thunder.
- **Gilded Slam** bursts a gold firework (large ball, #FFC846 fading to #FFF0B4). It's tagged so it never hurts anyone. It makes its own large-blast sound, so the configured layer is skipped while it's on (`mythics.auratus.slam.firework`).
- **Auratus** is down to one sound plus at most one layer for every moment. A chain hook used to play 5 sounds, the launch 4, Flagpole 3 and the parry 3.
- **Pack sounds replace instead of layering.** Put `blissgems:...` as an event (your `auratus.parry` included) and it plays instead of the vanilla one.
- **Sounds that didn't exist before:** Blur, Projection, the fireball's burn (repeating every 1.5 s while charging), Tuned In tag (on a newly tagged player) and Tuned In full.
- **My picks where your table had none:**

  | Ability | Sound |
  |---|---|
  | Bounty Hunter | ravager ambient |
  | Chad's 4th hit | mace smash |
  | Speed tier-2 click | copper bulb |
  | Astral Void cast | low amethyst hit |
  | Life Siphon | bone meal |
  | Flux ingot pickup | short conduit hum |
  | Rift menu | quiet bell |
  | Rift set anchor | respawn anchor set-spawn |
  | Heart pedestal | trial spawner ominous activate |
  | Auratus extras | chains and bells only |

- **Rewind and Time Dilate** use your fallback sounds until `rewind.ogg` and `time_dilate.ogg` arrive, because they weren't attached.
- **Left as they were:** the fireball explosion's own boom, the Revenant, and non-gem sounds (items, menus, the finale).

## Finale

- **Pedestals stand on the ground.** They were built at the spawn point's height 6 blocks either side, so they floated wherever the ground was lower. Now each one drops to the ground below its spot, or climbs out if the spot is inside a hill.
- **`/turmoil pedestal 1` and `/turmoil pedestal 2`** set where the Auratus and Heretic pedestals stand: wherever you're standing, on the ground. `/turmoil pedestal` shows both, and `/turmoil pedestal clear` goes back to automatic. The spots are saved in `turmoil.yml` with the finale center.
- **The gems orbit, merge and form the Relay between the two pedestals,** wherever they are, easing up out of their cradles.
- **Custom models:**
  - **Pedestals:** blackstone with gold (Auratus) or red (Heretic) trim and a four-prong cradle the gem floats over. They're built from vanilla block textures. `finale.pedestal.custom-model: false` brings back the old stacked blocks for players without the pack.
  - **Clock seal:** a golden clock face, 7 blocks across, on the ground under the merge. It grows in as the gems lift, spins faster as they close in, kicks outward when they collide, and bursts into dust when the Relay forms (`finale.seal`).

## Files

- **New:**
  - `GemSounds.java` and `GemSoundsTest.java`
  - `FinaleMath.java` and `FinaleMathTest.java`
  - `SOUNDS.md`
  - pack models `finale_pedestal_auratus`, `finale_pedestal_heretic`, `finale_seal` (plus the seal texture)
- **Removed:** `Sfx.java`, replaced by `GemSounds`.
- **Changed:**
  - `GemListener` (standard gems, Storm bolts)
  - `MythicListener` (Heretic)
  - `AuratusGem` (sounds, slam firework)
  - `RelayGem`, `RelayRift`, `TemporalFlow`, `TemporalDomain` (Relay)
  - `Pedestal` (heart pedestal)
  - `SoulboundGemsPlugin` (startup sound check)
  - `Finale`, `TurmoilCommand`, `plugin.yml` (pedestals, seal, command)
  - `config.yml` (the sounds section, `finale.pedestal.custom-model`, `finale.seal`)
  - `tools/pack/relay_pack_assets.py` (the models)

## Tested / not tested

- **Tested here:**
  - It compiles against Paper 1.21.11, and 56 unit tests pass (9 new).
  - **Sound rules (GemSoundsTest):** every configured sound name exists in 1.21.11, pitches are within 0.5 to 2.0, no banned sound is used, at most one layer, and nothing is shared between gems except the five pairs the spec lists.
  - **Finale placement (FinaleMathTest):** pedestals drop to the ground, climb out of hills, stay on platforms and keep their height over the void; the merge point and orbit sit between the pedestals.
  - The pack JSON parses and the new models' references resolve.
- **Not tested (nothing was run in a game):**
  - how any of the sounds actually sound or how loud they feel;
  - the particle storm bolts;
  - the slam firework, and that it never hurts anyone;
  - the pedestal models' look and size;
  - the seal;
  - the reworked merge path;
  - `/turmoil pedestal`.

# Action bars say "Ready!" again; Auratus shows just chain and Aegis

- **Every gem's action bar** (and the Revenant's) is back to "icon Ready!" when an ability is ready. Ability and gem names stay gone.
  - Recharging is still a dimmed icon with a timer; active is still "…".
  - Abilities with charges say "Ready!" while at least one is left.
  - The Rift slot by the hotbar says "Ready!" too.
- **Auratus:** the bar is just the chain and Aegis icons. The ⚑ Flagpole/launch cooldown and ▲ Echo Anchor symbols are gone.
- The simulator page shows the same.
- 47 unit tests pass. Not run in a game.

# Auratus: hooks on everything, right-click to go, the Aegis shield, Flagpole and the launch

- **Chains hook whatever they hit and stay:** enemies (who take the hit at once), other creatures, blocks and the Echo Anchor. **Right-click goes there** in either hand; sneak + right-click still parries while hooked. **F** hauls a hooked creature to you. Left alone, the hook slides out after 6 s.
- **Aegis:** the gold parry halo now stands 3 blocks ahead where you look, facing you. It hits any enemy there (15 HP, about 1 heart through Prot IV diamond) as well as parrying.
- **Flagpole:** hook → F (pull them in) → Aegis catches them → nothing else → they're hoisted. The left + right click combo is gone.
- **The launch:** same, but chain again within 0.75 s. No Flagpole: they're thrown about 30 blocks straight up with one raw 72-damage hit.
  - That's about 21.8 HP through full Prot IV diamond, a kill from full health.
  - The whole combo is 24.6 HP, enough to beat a golden apple. A unit test checks it with vanilla's armor formula; 70 turned out 0.002 HP short, so it's 72.
  - Flagpole and the launch share the 25 s cooldown.
- **New settings:** `aegis.distance`, `aegis.strike-radius`, `aegis.strike-damage`, `aegis.strike-ticks`, `flagpole.window-ms`, `flagpole.finisher-damage`, `flagpole.finisher-launch-height`.
- **Removed settings:** `flagpole.combo-ms` and `flagpole.range`.
- 47 unit tests pass. Not run in a game.

# Auratus chains: the hook stays in, walls snap it, shorter grasp

- **The hook stays in an enemy.** A chain that hits an enemy bites straight away (the 4.5-heart hit, once per hook) and stays in them. Then you choose:
  - **chain button again:** fly to them (no extra charge);
  - **swap hands (F):** haul them to you;
  - **nothing:** the hit is done, and the hook slides out after 6 s (`chain.hook-seconds`).
  - It lets go early if a wall gets between you, if they get out of range, on a slam, or when Flagpole lifts them. F never swaps your items while a hook is in.
  - Before, a chain always pulled you to them and hit on arrival.
- **Walls snap the chain.** A block between you and the hook point for 2 ticks snaps it with a clank, instead of pinning you against the wall for up to 2 s. This covers chains to blocks, anchors and enemies.
- **Angels Grasp from a chain hook is 2 s** (`passives.angels-grasp-chain-seconds`). Slam and Flagpole keep 4 s. Two charges can no longer keep someone pearl-locked.
- **Flagpole** works while the hook is in (or 5 s after). A right-then-left combo no longer wastes the Aegis: it's taken back, cooldown included.
- The new settings are in `/mythicconfig` too. 45 unit tests pass. Not run in a game.

# No words on any action bar; Auratus' Aegis on right-click

- **Every gem's action bar is now icons and timers only:** standard gems, Flux, Heretic, Auratus, the Relay (including the Rift slot by the hotbar), and the Revenant's too. There's no "Ready!", "Active...", gem name, "Slowed", "Suspended" or "x/y Ready!" any more.
  - **Ready:** the icon, bright.
  - **Recharging:** the icon dimmed, with a timer.
  - **Active:** the icon followed by "…".
  - **Abilities with charges** (Speedy Storm, Bloodsaws, chains, Rewind, Plunge): bright while one is left, with the next refill timer in darker blue; dimmed with a timer when none are.
  - **Flux Kinetic Burst** shows its charge as a dimmed icon with a %. Watts stay a number.
  - **Rift:** an orange timer while unstable in combat, dark while the finale seals it.
  - **The Revenant:** Rising goes dark inside Time Dilate, and a suspension shows an hourglass and its timer.
  - Shared in `HudStatus.java`, with 4 unit tests.
- **Auratus:**
  - The Aegis is plain right-click with the gem in your main hand. The off hand is unchanged: right-click chains, sneak + right-click Aegis (or the slam in the air). Lore version 7.
  - The action bar is the old chain and Aegis icons with no words. Flagpole and slam cooldowns and the Echo Anchor show as small gold ⚑, ▼ and ▲ symbols only while they matter.
  - Hints that used to flash text ("Hook someone first", "Slam ready in…") are now a soft click only you hear. So is the Revenant's "Suspended…" message.
- **Unchanged (not part of the HUD):** one-off messages such as the Bounty Hunter tracking lines, "GEM DISABLED", the finale's pedestal instructions and the Prismatic Edge weapon's own bar.
- 45 unit tests pass. Not run in a game.

# Auratus: the old controls and action bar are back

- **Controls:** left-click chains, sneak + right-click Aegis, left + right-click together Flagpole, as before. Off hand: right-click chains, sneak + right-click Aegis. Hidden combos stay hidden; the lore lists only the two powers.
- **Gilded Slam is sneak + chain while 4+ blocks up** (off hand: sneak + right-click in the air). It costs no chain charge and has no cooldown, like the old slam. Lower down, sneak + chain is just a chain. `slam.cooldown-seconds` can add a cooldown.
- **Flagpole's combo window** is `flagpole.combo-ms` (100, as before). It needs a chain hook in the last 5 s.
- **Plain right-click with the gem in the main hand does nothing.** The old long-reach poke is covered by Hauling Strike's +1 reach on every hit.
- **Action bar:** the old `⊵ Auratus Ready! ➜ ⊴ Ready!` with the main pack's icons. Flagpole's cooldown and the Echo Anchor are added only while they matter.
- **Pack:** the four new gold icons (U+E049-E04C) are removed from the pack and the font.
- Lore version 6. 41 unit tests pass. Not run in a game.

# Auratus, rebuilt

Same powers, rebuilt from scratch: chains, Aegis, slam, Flagpole and the passives, with one clean button each, new visuals and sounds, HUD icons and new damage numbers. Feathered Fall is unchanged. Details, damage table, test plan and decisions: AURATUS.md.

**Not tested in game.** It compiles, and 41 unit tests pass (7 new: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity durations, Aegis cooldown, chain charges). The pack JSON and its files were checked. Nothing was played or seen on a client.

- **Controls.** Main hand: left-click chains, right-click Aegis, sneak + right-click (in the air) Gilded Slam, sneak + left-click Flagpole. Off hand: right-click chains, sneak + right-click Aegis, and the main hand's own uses (bows, food, blocks) win. Doors and chests still open. No random procs, no hidden two-button combos.
- **Chains:** 2 independent charges (8 s each); misses are free; chain-hop by throwing again mid-reel. Reaching an enemy hits them for 4.5 hearts unarmored.
- **Aegis:** 1 s window. It blocks attacks only (it used to block falls and fire too) and reflects half (it was all). It rewards once: heal, Speed II, a cleanse and an Echo Anchor to chain to. 10 s cooldown after a parry, 15 s after a miss.
- **Slam:** one button in the air instead of the look-down/land/sneak/aim/dash sequence. 9 hearts at the centre to 4.5 at the edge, unarmored. The gold ring now lies on the ground instead of under it.
- **Flagpole:** sneak + left-click within 5 s of a chain hook. They're lifted 5 blocks for 2.5 s with their camera free; it used to be Levitation V for 5 s with the camera locked.
- **Passives:**
  - Hauling Strike: real +1 reach; full hits pull in and drop shields.
  - Angels Grasp: no pearls, wind charges, chorus or tridents for 4 s after your chain, slam or Flagpole hits.
  - Divine Purity: debuffs last half as long. It did nothing before.
  - Haste II while crouching is gone.
- **Damage:** chains and slam use the standard `ability-damage` scaling and cap.
- **Config:** a config with the old Auratus section gets the new one on first start (some keys changed meaning). Feathered Fall and the chain model settings are kept. `/mythicconfig` lists the new settings.
- **Lore and HUD:** the lore lists the controls (lore version 5, so existing gems update). The HUD shows four new gold icons, `U+E049`-`U+E04C`, with each timer.

# The Revenant: soul mace boss weapon

Full details, test plan and decisions: REVENANT.md.

**Not tested in game.** It compiles, and 34 unit tests pass (8 new: smash damage and the 14-heart cap, falloff, leap height, independent Plunge charges, behind-target spot, world-border clamp). The pack JSON and every file it points to were checked. Nothing below was played or seen on a client.

- New admin-only boss weapon: `/revenant give <player> [fresh]`, `/revenant boss start|stop`, `/revenant status` (permission `soulbound.revenant`, ops). A mace with your texture (`blissgems:revenant_mace`), teal name and lore. Not a gem, so the Trader, Upgrader, energy and disables ignore it.
- One at a time: an id plus a holder; copies are inert. It can't be dropped or stored, never drops, and vanishes on death and logout.
- Plunge (right-click, 2 charges at 12 s each), Rising (sneak + right-click, 25 s), Undertow (sneak + left-click, 30 s). Passives: Abyssal Weight (hits stop jumping for 2 s) and no fall damage. Every Revenant hit is capped at 28 HP (14 hearts) before armor.
- Boss mode: 60 hearts, knockback resistance 0.6, a boss bar within 100 blocks. Phase 2 at half health (faster Plunge, 10 s red sky). He shatters on death. `boss stop` ends it quietly.
- Logout mid-fight pauses it. The bar reads "The Revenant has vanished…", and health, phase and timers are saved. The same player rejoining gets the mace back after a 5 s warning. `give ... fresh` restarts at full health.
- Relay counters:
  - Time Dilate slows Plunge and blocks Rising.
  - Temporal Divinity never takes him to the void. It suspends him 3 blocks up for 5 s (3 s in phase 2), with no movement, no powers and no knockback resistance; then he gets a gentle drop and 30 s immunity.
  - He can't open or follow rifts.
- Pack: mace item definition and model (`handheld_mace` parent), your texture, and HUD icons `U+E046` Plunge, `U+E047` Rising, `U+E048` Undertow (33x20, iron frame, teal art).

# Tagged players get a ring; Tuned In icon is a target

- Players you've tagged with Tuned In (30 seconds after you hit them) get a slowly turning ring of Relay-brown dust at their feet while you hold the Relay, within 48 blocks. Only you see it by default; invisible players get none. `mythics.relay.passives.tag-ring`, `tag-ring-color` (`#FFFFFF` for white) and `tag-ring-everyone`.
- The Tuned In meter icon is now a target reticle around a small standing player (head, shoulders, body), matching the rings: each fifth of charge lights one quarter of the ring, and full makes the whole icon bright. Replaces the short-lived signal-wave version. Same codepoints, border and side bar.
- 26 unit tests pass. Not run in a game.

# Rewind uses half the bar; Tuned In gets a signal icon

- Rewind still needs a full Tuned In bar but now uses only half of it (`temporal.charge.rewind-share`, 0.5), so after a Rewind the next power is 5 hits away. The other powers still empty it.
- The Tuned In meter icon is now radio signal waves instead of a compass: each fifth of charge lights one more wave and full makes the whole icon bright. Same six frames and codepoints (U+E042, U+E058-E05C), so the HUD and the hotbar slot pick it up with no code change. Only the inside of each icon was redrawn; the frame and side bar are unchanged.
- Tuned In never pointed at anyone in this plugin (that compass behaviour was removed in the temporal rework); it is the charge meter plus the 30-second tag that adds 5% damage against players you hit.

# Tuned In must be full to use a Relay power

- `temporal.charge.full-to-use` (new, default true): Rewind, Time Dilate, Temporal Divinity and Rift each need a full Tuned In bar and empty it when they succeed. A power that fails (no safe spot, cancelled teleport, broken Rift channel) costs nothing. Cooldowns still apply on top. false restores the per-power costs.
- `temporal.charge.hits-to-fill` (new, default 10): hits on players needed to fill the bar from empty (10 charge each). Being hit still adds `received` (4), so mutual fights fill it a little faster. 0 restores `hit`.
- New settings, so they apply to existing configs without editing the old `hit`, `dilate-cost` and `rewind-cost` values.
- Trying a power without a full bar says "Tuned In isn't full yet (x/100)" at most every 2 seconds; for Rewind only on a click into the air, not on every punch with the gem.
- Rift checks the bar before channelling and empties it only when the jump succeeds.
- 26 unit tests pass. Not run in a game.

# Temporal Divinity in the Relay colour

- Temporal Divinity's lore line and its "needs a nearby opponent" message use the Relay's antique gold (#B89358) like Rewind, Time Dilate and Rift, instead of pink.
- Temporal Divinity's lore line gets an eye symbol, so all four Relay powers have one. It is a pack glyph, `U+E045` (`assets/blissgems/textures/font/lore_eye.png`, 11x7), drawn in white and greys so the lore colour tints it. Lore version 4. (Briefly `◬`, which rendered smaller than the other symbols.)
- Gem lore now carries a version; bumping it (now 2) makes existing gems rebuild their lore once, so lore text changes reach gems people already have.

# Lore: small caps for capital letters only

- `lore.small-caps` now picks a style: `capitals` (new default: only letters that were capitals become small caps, "RELAY GEM" -> "ʀᴇʟᴀʏ ɢᴇᴍ", "Tuned In" -> "ᴛuned ɪn"), `all` (every letter, the previous behaviour) or `off`. `true`/`false` in an existing config mean `capitals`/`off`.
- Existing gems are no longer converted text-to-text: their lore is rebuilt from the gem's own data in the configured style, keeping item-history lines, and the gem remembers the style it was written in. So changing the setting later, including back from `all`, comes out exactly right.
- 26 unit tests pass. Not run in a game; the look was checked on a tooltip mock-up drawn with vanilla's font sheets.

# Small-caps gem lore; Tuned In beside the hotbar

- Gem lore (every standard and mythic gem) is written in small caps: every letter, e.g. "ᴛᴜɴᴇᴅ ɪɴ". Colour codes, symbols and digits are kept, and so are item-history lines ("STOLEN by Name"), which bounty tracking and item history read back. Existing gems switch over when held or when their owner joins. `lore.small-caps` (true).
- The Relay migration that adds the Rift line now matches words in either lettering, so converted lore never gets the line twice.
- The Tuned In meter moved from the action bar to just after the Rift slot, right of the hotbar (`mythics.relay.hud.tuned-position`). There, the Rift's in-combat status is just its orange timer, to stay on screen.
- New `SmallCaps.java` and `SmallCapsTest`. 24 unit tests pass. Not run in a game.

# Icons-only Relay HUD, Divinity icon, Dilate mini domain

- Relay action bar: with the pack, no ability names and no Tuned In numbers, just each icon and its timer. Temporal Divinity gets its own icon (`U+E044`, an eye in a triangle) and Tuned In shows only its meter. Text labels come back when `temporal.hud.use-glyphs` is false.
- Time Dilate: the clock and the field's centre sit 3 blocks below the caster, or on the floor if closer (`dilate.clock-depth`).
- Time Dilate: the caster air-swims while airborne inside their own field, with the same controller and settings as Temporal Divinity and kept inside by a soft wall (`dilate.swim`). Flags are restored afterwards, also after a crash, and a swim ending mid-air gives no fall damage until landing.
- Compiled; 19 unit tests pass. Not run in a game.

# Rift HUD slot beside the hotbar

- The Rift icon and its timer moved from the action bar to just right of the hotbar, using a new pack font `blissgems:hud_low` and invisible spacing characters. The rest of the Relay HUD is unchanged and doesn't move.
- `mythics.relay.hud.rift-position` (`hotbar` or `inline`) and `rift-hotbar-offset` (nudge in GUI pixels). Without the pack it falls back to an inline text slot.
- New `HudText.java` (vanilla font widths, measured) and `HudTextTest`. 19 unit tests pass. Placement checked on a mock-up only, not in a real client.

# Relay Rift (quick travel) and Time Dilate restyle

- New `RelayRift.java`: a fourth Relay power on sneak + left-click. Two anchors per player (saved in `relay-rift.yml`), a 5-second channel broken by damage or moving, a 15-second combat tag, a rift that stays open 4 seconds for anyone to follow, safe landing within 5 blocks, 1 second of fall protection, a 300-second cooldown and a 10-second cooldown for a broken channel. Disabled during the finale and switchable with `mythics.relay.rift.enabled`.
- Rift visuals: the approved six-frame brown tear on a full-bright display with a brown Glowing outline, a ground light spill, drifting dust, random stutters, a half-size arrival rift and an arrival flash visible within 64 blocks. HUD slot with glyph `U+E043`.
- Time Dilate: clock lines 1.5x thicker, recoloured to glowing brown, and given a brown Glowing outline. Gameplay unchanged.
- Rewind, Time Dilate, Temporal Divinity, Tuned In, Quickened and Clean Signal unchanged.
- Compiled; 15 unit tests pass. Not run in a game. Full details, settings, files changed and the test plan: RIFT.md.

# Standard-gem ability damage scaled for armored targets

- `GemListener.java`: the flat damage of Crisp, Breezy Bash (both forms), Dash, Kinetic Burst and Energy Beam is multiplied by `ability-damage.multiplier` (default 3). One ability hit is limited to `ability-damage.cap` (default 40 HP before armor) after the holder's on-hit bonuses such as Overdrive and Ground.
- Puff Dash: anyone the caster passes is now hurt once a second, 3 HP a second for 3 seconds (`abilities.dash.damage-per-second`, `damage-seconds`), scaled by the multiplier like the others. It used to deal 5 on three consecutive ticks, of which only the first ever landed, and only checked the first three ticks of the dash; it now sweeps the whole dash.
- Not changed: Fireball (already has `fire.fireball.damage-multiplier`), Astra daggers, Blur clones, Meteor Shower, percentage bonuses (Strength, Frailer, Chad, Overdrive), and all three mythic gems.
- Compiled only; not run in a game.

# Relay attunement kit — the player's own gear, maxed

- `Finale.java`: the chosen player no longer has their inventory emptied and replaced with a netherite loaner set. They keep everything, and each piece of their own armor, weapons and tools is swapped in place for a temporary copy raised to Protection IV, Sharpness V, Efficiency V, Power V, Unbreaking III and Mending, wherever that enchantment fits the item. Protection and Sharpness replace rival enchantments (Blast Protection, Smite); other clashes are skipped. Gems and the plugin's special items are not touched.
- The originals are saved to disk as before and returned to their own slots when the finale ends, so armor goes straight back on.
- Temporary copies are removed from ender chests as well as inventories at the end, from anyone who logs back in afterwards, and from any container the first time it is opened after the finale.
- `config.yml`: `finale.kit.own-gear` (default `true`; `false` restores the old netherite loaner) and `finale.kit.food`.
- Compiled only; not run in a game.

# Gem action bar icons and separate cooldowns

- `GemListener.java`: the standard-gem action bar now shows one entry per ability, each with that ability's own icon from the Bliss Pack font (fireball, campfire, dagger, projection, heart, rose, fist, arrow, bolt, muscle, giga, clover, money, beam, domain) and the gem's crystal for its offhand ability. It previously showed the same gem crystal twice. Tier 2 gems show left-click power, right-click power and ability; Tier 1 gems show the ability only. The Flux bar no longer repeats the generic tier icon.
- `GemListener.java`: every ability now has its own cooldown. Before, all powers of all gems shared one timer per player and all abilities shared another, so using Nullify also locked Chad Strength, and using one gem's power locked every other gem's. The per-ability durations under `cooldowns:` are unchanged and are now what each ability actually waits.
- `GemListener.java`: Fireball's cooldown is now enforced. It was displayed but never checked, so the Fireball could be fired again immediately.
- `GemListener.java`: a Kinetic Burst that fails for lack of charge no longer costs its cooldown (the code cleared the wrong timer).
- Speed gem: Speedy Storm now holds 3 charges, each recharging on its own (`abilities.storm.charges`, recharge time from `cooldowns.storm`); Thunderstep has an ordinary cooldown (`cooldowns.thunderstep`). These were the other way round.
- Speed gem: Blur is now the left-click power, shown on the bar with the injection icon and its own cooldown (`cooldowns.blur`, 15s as before). It is melee only: it triggers when you hit something with the Speed gem itself in your main hand. A sword hit with the gem in the offhand no longer triggers it. Added to `/config`.
- Speed gem: Blur no longer has a fixed 12-second length. It stays active while the caster keeps hitting the target with the gem in the main hand within `abilities.blur.combo-window-seconds` (3s), and ends when the target hits the caster back or that window lapses. Its cooldown now starts when it ends. The closing 1-heart slash is removed. Blur clones can no longer target or damage anyone but the Blur target, and are not saved across restarts.
- `ChargePools.java`: reading a pool for a player who had never used the ability threw a NullPointerException; fixed.
- `config.yml`: `actionbar.glyphs` replaced by `actionbar.icons` (`left`, `right`, `ability` per gem). The old `glyphs` keys in an existing config are ignored.
- Compiled only; not run in a game.

# Fire gem Fireball — bossbar and explosion

- `GemListener.java`: while a Fireball is charging, a bossbar shows the charge (grey "too weak" below 15%, gold while charging or fading, red at full). It is removed on release, quit or death; quitting or dying now also drops the pending charge.
- `GemListener.java`: a launched Fireball now explodes where it lands. Power scales with charge from `explosion-power-min` to `explosion-power-max`. The caster takes no damage from their own blast, matching the existing no-self-damage rule. Previously the code set a blast size on a projectile type that never explodes, so nothing happened.
- `GemListener.java`: blast damage is dealt by the plugin using the vanilla explosion curve (distance falloff, reduced behind cover), not by the explosion itself, so it does not shrink on Easy or vanish on Peaceful. The explosion still handles blocks, fire and knockback, and nobody is damaged twice.
- `config.yml`: new `fire.fireball` section (`charge-bossbar`, `explode`, `explosion-power-min`, `explosion-power-max`, `damage-multiplier`, `break-blocks`, `set-fire`).
- Charge timing, cooldown, controls and the direct-hit behaviour are unchanged. Compiled only; not run in a game.

# Relay temporal rework — 1.3.0-temporal

**Temporal Divinity movement rework:** the caster now air-swims instead of using native flight; captured opponents stay suspended and cannot fly, glide or steer. Exactly what changed:

- Added `AirSwim.java`: caster-only, per-tick swimming controller driven by real key input (`Player#getCurrentInput()`), with eased acceleration, glide-to-stop, look-direction thrust, a mild stroke rhythm and a soft wall inside the domain boundary.
- `TemporalDomain.java`: caster enters with gravity off and no native flight; opponents' sideways momentum is damped each tick; flight, gravity and glide state is re-asserted each tick; flight toggling and elytra gliding are cancelled for domain members; drift has a 0.035 floor while the server disallows flight; `boundary-radius` clamp applied consistently.
- `config.yml`: `duration-seconds` default 5 → 8; `flight-speed` removed; added `swim-speed`, `swim-sprint-multiplier`, `swim-acceleration`, `swim-drag`, `swim-stroke`, `enemy-horizontal-damping`.
- Added `divinity.allow-solo` (default `false`): a testing aid that lets the caster open the domain with no opponent in range. See "Testing alone" in SWIM_UPDATE.md.
- `TemporalFlow.java`: Time Dilate clock no longer tilts. The ring displays were spawned with the caster's look direction, so looking up or down while casting slanted them; they now always spawn level.
- `pom.xml`: test-scoped JUnit 5 for the 9 new `AirSwim` unit tests. Not shaded; no new runtime dependency.
- No other Java class, no command, no item control, no cooldown/charge/HUD logic and no resource-pack asset changed.

Compiled and unit-tested only; no Minecraft server or client was run. See SWIM_UPDATE.md for controls, settings, trade-offs and the list of what is untested.

**Antique palette revision:** Time Dilate now uses dark brown #5A3A1C, bronze #92613B and restrained warm gold #C99855. Geometry, animations and gameplay are unchanged. See PALETTE_UPDATE.md for the reviewed recordings, exact changes and pack-only installation.

Built from the attached Relay-Source.zip, Relay-Flash-Pack.zip, and separate Finale.java. This is an integrated test build for Paper 1.21.11 / Java 21. It has compiled successfully with the real Paper Maven dependency. No Minecraft server/client session or multiplayer test was run. Maven reports “No tests to run”; compilation is not gameplay validation.

## Controls

| Input | Result |
|---|---|
| Main-hand Relay + right-click | Time Dilate |
| Main-hand Relay + sneak + right-click | Temporal Divinity if charge/cooldown/targets permit |
| Main-hand Relay + ordinary left-click | Rewind |
| Sword or another item + offhand Relay | Ordinary item input; Relay passives and charge still operate |
| F / offhand hold | No Relay active ability; F swaps normally |

All three powers have separate cooldown state. Rewind retains two independently recharging slots. The HUD keeps existing Rewind/Dilation/charge glyphs, cyan timers, red Active text, and uses a text Divinity label. There are no arrow/bar separators or redundant Relay name. Disable temporal.hud.use-glyphs for text labels.

## Audit and implementation

- RelayGem.java: replaced manual Tuned In and direct-hit Time Lock; retained Quickened, Clean Signal integration, tagged-target damage bonus, existing five-second Rewind health/location history, debuff cleanse, two charges, 20-second recharge, and existing rewind effects. Rewind spends only after a successful clear-space teleport, cannot cross worlds or escape a domain, and ignores sword clicks and sneaking.
- MythicListener.java: routes Relay separately from other mythics. Bloodsaw translation/spin/drag use one time-scale value from the canonical velocity; reflections retain canonical speed. Bloodsaw lifetime and drag expiry remain the original real-time semantics. Auratus grapple pull, carry momentum and dive/dash launch respect time scale; flight timeout uses accumulated scaled steps. Cross-world grapple tasks stop cleanly. This source uses a rendered tether and player pull, not an independent flying harpoon simulation; no nonexistent harpoon entity was invented.
- TemporalFlow.java (new): per-tick field/domain motion service. Overlaps take the lowest scale, never multiply scales. Per-entity keyed movement, jump, attack and gravity modifiers are removed on exit; momentum is capped rather than multiplied repeatedly. Slow field melee cadence is enforced on accepted damage. Projectile motion/gravity state is restored on exit, chunk unload, shutdown and entity load after restart.
- TemporalDomain.java (new): bounded simultaneous sessions in a plugin-owned void world, legal altitude, no arena/platform, air-swimming for the caster (originally native flight; see SWIM_UPDATE.md), downward drift for enemies, reduced attack recharge with damage cap/interval and hurt-window control. Snapshot journal is written before entry; partial failure rolls back. Session exit, disconnect, death, mode changes, respawn, startup recovery, chunk tickets and world-unload protection are handled. Domain death drops/XP are moved to the original world after existing death filters; manual item drops are blocked during the session.
- SoulboundGemsPlugin.java: independent 1-tick Relay loop; existing gem/HUD loop unchanged. Shutdown cleanup and default-config merge added. Other mythic movement loops retain their existing update frequency; this deliberately avoids changing their normal-speed balance globally.
- GemManager.java: old consumable hold component removed, controls/lore migrated without changing item PDC or custom model data (Relay remains 12365).
- Config: all new temporal settings are under mythics.relay.temporal. Existing live config is preserved and missing bundled defaults are merged/saved. Legacy tuned/time-lock/dilation keys may remain in old configs but no longer drive the replaced abilities.
- Resource pack: adds blissgems:temporal_clock and blissgems:temporal_hand item definitions/models, with broken brown/bronze rings and gold-accented hands. Two displays per field, rapid start, deceleration and accelerating exit. Existing assets/font glyph registration are untouched. Particle fallback is available. Added missing pack.mcmeta for 1.21.11 (75.0).
- Finale.java: used the separately attached revision, preserving its removed gold dome, flash-at-formation timing, no shovel, 64 golden apples and 64 golden carrots. Corrected its nonexistent BLOCK_AMETHYST_CLUSTER_CHIME constant to BLOCK_AMETHYST_BLOCK_CHIME.

## Proposed balance defaults

These are starting values, not confirmed balance: charge 100, +8 dealt / +4 received, final damage >=0.5 HP, shared 400 ms gain spacing, no mobs/self/trusted/same-scoreboard-team gains, and recent input/look activity from both PvP participants. Decay is off by default (15-second grace if enabled). Charge resets on death, persists on logout/restart by default. Cooldowns also persist in player PDC, preventing relog resets. Normal autosave determines crash durability of recent gains; a successful domain activation explicitly saves charge/cooldown.

Time Dilate: 8 seconds, radius 10, scale target 0.10, 65-second cooldown. Temporal Divinity: 8 seconds (originally 5), capture radius 10, scale target 0.15, downward drift 0.06 blocks/tick, 90-second cooldown, 5x attack-speed modifier, minimum 4 ticks between caster hits, 0.5 damage multiplier, maximum 6 HP before armor. Only successful domain entry consumes the configured required charge. Dilate/Rewind have configurable charge costs, default zero.

Charge does not increase inside a domain. AFK/repeated-hit checks reduce simple farming; they cannot prove two cooperating players are genuinely fighting. Explicit mob charging can be enabled. Returning from a domain does not heal, rewind inventories, or undo damage/death.

## Engine limits and known tradeoffs

- This is not an entity tick-rate controller. AI decisions, animation, redstone, potion timers and all vanilla simulation do not run at 10%/15%. Player movement uses attributes and velocity caps, so latency, sprinting, water, knockback, gliding, anti-cheat and other plugins can change the feel. Domain drift is deliberately constant downward motion, not a full scaled-gravity simulation.
- Vanilla projectile motion is an approximation: canonical gravity/drag is integrated, native gravity is temporarily disabled, redirects retain the new direction, and embedded arrows are never relaunched. Homing/accelerating projectiles, fluid drag, tridents/loyalty, fireworks, portals and plugins that also set velocity need targeted testing. Stored projectile position is not rewound.
- Existing Bloodsaw update cadence is retained (the original shared plugin task), so its animation is less smooth than a true 20 Hz projectile. Lifetimes remain real time, not extended tenfold. Auratus's tether appears immediately in this codebase; only pull, dive/launch movement and its pull-step budget are slowed. Slam combo windows remain their original wall-clock timers.
- A domain cancels the caster's existing Dilate field after successful entry. Participants cannot enter another domain. The domain scale has precedence; fields left in the original world continue independently for other players. Successful return is followed by ordinary field evaluation again.
- The sky uses a normal-environment empty world at midday. Chunk-loading transitions, vanilla sky/horizon/fog and client render distance remain visible; no claim of a seamless custom dimension or exact footage recreation is made. The initial rework had no reference video; the subsequent palette revision reviewed the two attached recordings without changing geometry or mechanics. No behind-target repositioning or speculative chaining was added.
- Air left-click is observed through Bukkit interact/arm-swing events, with a successful-activation deduplication guard. An arm swing is not proof of a unique physical mouse click: client animation, block mining and other plugins can affect these events. No packet library is required. Block interaction DENY is respected; pre-cancelled vanilla air events cannot always be distinguished from another plugin's cancellation. Main-hand-only active controls avoid offhand packet duplication and preserve sword use.
- Return records are atomic YAML snapshots, separate from ordinary player autosaves. If the original world/position is unavailable, the configured fallback world's spawn is used. Fall distance is captured but intentionally cleared, with configurable grace, to prevent transition fall damage. If another plugin cancels BOTH normal and fallback returns, the journal is retained and return is retried; the pending player is protected from damage. An administrator must remove that teleport restriction. Corrupt recovery journals disable new domains rather than overwriting evidence.
- Field limits, particle fallback frequency, domain/session/player limits and durations are configurable with hard safety bounds. Source does localized queries; one startup projectile recovery pass is the only whole-world entity query added.
- This release does not overhaul the existing /config GUI. New settings are editable in config.yml; existing non-Relay settings/UI remain intact.

## Installation

1. Stop the server. Keep a backup of the existing plugin/config/worlds.
2. Replace the old Soulbound Gems JAR with soulbound-gems-1.3.0-temporal.jar; keep only one Soulbound Gems JAR in plugins.
3. Keep the existing config. Startup adds missing defaults under mythics.relay.temporal; review those values before PvP testing.
4. Load Relay-Temporal-Pack.zip ABOVE your existing Bliss pack. This remains an overlay, not a complete replacement for that pack. Without it set temporal.dilate.display-models=false and temporal.hud.use-glyphs=false.
5. Start Paper 1.21.11 on Java 21. The dedicated world is created on the first valid Divinity attempt. Do not point its name at an existing unrelated world.
6. For a local rebuild: mvn clean package. Output is target/soulbound-gems-1.3.0-temporal.jar. No new runtime plugin dependency was added.

## Multiplayer acceptance checklist — NOT RUN

Use a disposable test server with the real pack and normal protection/anti-cheat plugins. Record server logs and test both low and high latency.

| Test | Expected result |
|---|---|
| Two survival players; sword + offhand Relay | Sword combat works, valid damage charges, no Rewind/Divinity from ordinary sword use |
| Main-hand Relay; click/sneak combinations; air/block/entity swings | Exactly the intended power, no offhand duplication; sneak-left does not Rewind |
| Cancelled, zero-damage, self, friendly, stationary AFK and rapid hits | No unintended charge; mobs give none by default |
| Full meter, no opponent, cooldown, cancelled entry, failed world load | No charge consumed; any already-moved participant restored |
| Rewind at full/partial history and obstructed historical position | Original health/cleanse semantics; obstructed/cancelled teleport spends nothing |
| One field; enter/leave; two overlapping fields; owner/trusted exemption | Slow begins/ends; overlap is min(scale), not 0.01; no stale modifiers |
| Arrows/snowballs/potions, embedded arrows, gravity-disabled shot | No exponential decay; gravity restored; embedded arrow stays embedded |
| Projectile crosses boundary, ricochets, chunk unloads, stop/restart | Direction/speed restoration and no duplicated entities or stuck gravity |
| Heretic Bloodsaw wall/floor ricochets and dragging | Canonical reflection retained, scaled travel, original lifetime/one-hit rules |
| Auratus entity/block tether, pull expiry, slam/dash, target changes world | Scaled pull, visual cleanup, no distance-across-world exception |
| Divinity with 2, 3 and 9 players; distinct simultaneous sessions | Open sky, caster air-swimming, enemy downward drift with no steering, spatial/damage isolation |
| Domain spam attacks, invulnerability frames and damage cap | Configured interval and scaling; no unbounded instant-hit stack |
| End midair; logout caster/opponent; death caster/opponent | Others returned; original movement/flight flags restored; no transition fall hit |
| Death drops including Relay and finale kit; respawn | Drops in original world, kit filters preserved, no duplicated inventory or lost gem |
| Stop/restart during domain; join while recovery pending | Original/fallback return, no permanent flight, journal cleared only after recovery |
| Return-world unavailable; both return teleports cancelled | Fallback or retained retry record; administrator can resolve blocking plugin |
| Reentry, portals, spectators, outside attacks, item dropping/building | No domain infiltration/escape or inventory exploitation |
| Resource pack on/off; field expiry; logout and shutdown | Clock/ring visibility, text/particle fallback, no leftover displays |
| Existing standard gems, Auratus/Heretic, Turmoil, finale | Unrelated abilities/events still operate; supplied finale edits preserved |

## Validation performed

- Real Maven clean package against io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT on Java 21: successful.
- Maven test phase: no test suite in this project; no automated gameplay tests ran.
- Resource/config/archive structure and preservation checks: see VALIDATION.txt in this bundle.
- Multiplayer, Minecraft client rendering, server startup/world generation, crash recovery, performance profiling and anti-cheat compatibility: unverified.

Official API references consulted:
- https://jd.papermc.io/paper/1.21.11/org/bukkit/WorldCreator.html
- https://jd.papermc.io/paper/1.21.11/org/bukkit/generator/ChunkGenerator.html
- https://jd.papermc.io/paper/1.21.11/org/bukkit/event/player/PlayerAnimationEvent.html
- https://jd.papermc.io/paper/1.21.11/org/bukkit/event/player/PlayerInteractEvent.html
- https://docs.papermc.io/paper/dev/scheduler/
- https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11
