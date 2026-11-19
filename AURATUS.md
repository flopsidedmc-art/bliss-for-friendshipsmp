# Auratus, rebuilt

**Status: compiled and unit-tested, NOT played.** No Minecraft server or client was run. Everything below about how it looks and plays in game is the design, not an observation.

The same powers (chains, Aegis, slam, Flagpole and the passives), rebuilt underneath, on the **old Auratus controls** (with the Aegis on plain right-click) and the **old action bar icons, with no words**. Hidden combos stay hidden. Feathered Fall is exactly as it was.

## Controls (the old ones)

| Gem in **main hand** | Power |
|---|---|
| Left-click | **Venerated Perforators** (chains), 2 charges |
| Right-click | **Echoing Aegis** (parry) |
| Sneak + left-click, 4+ blocks up in the air | **Gilded Slam** (sneak + chain) |
| Left + right-click together (within 0.1 s) | **Flagpole** (after a chain hook) |

| Gem in **off hand** | Power |
|---|---|
| Right-click | Chains |
| Sneak + right-click | Aegis on the ground; **Gilded Slam** when 4+ blocks up |

| While your **hook is in an enemy** (either hand) | What happens |
|---|---|
| Chain button again (left-click, or right-click with the gem in the off hand) | **You fly to them** |
| Swap hands (F) | **They're hauled to you** |
| Nothing | **Neither**: the hook already did its damage and slides out after 6 s |

Notes on the controls:

- **The slam replaces the old look-straight-down trigger.** Sneak + chain lower than 4 blocks is just a chain, and the slam costs no chain charge.
- **Off hand:** your main hand's own right-click still wins, so bows, food, pearls and placing blocks work normally.
- **Doors and chests** still open with a plain right-click.
- **Melee range:** a left-click on an enemy within melee reach is just a hit; it never wastes a chain.
- **Plain right-click with the gem in the main hand** used to do a long-reach poke and the Aegis needed sneak. Now right-click is the Aegis. The poke is covered by Hauling Strike's +1 reach on every normal hit.

## Powers

1. **Venerated Perforators.** Throw a chain up to 40 blocks.
   - **2 charges**, each recharging on its own 8-second timer.
   - **Block:** you reel in on a straight line that ramps up to about 22 blocks a second, and keep some momentum when you arrive. Landing ends it.
   - **Enemy: the hook stays in them.** It bites straight away: 9 HP (4.5 hearts unarmored) and 2 s of Angels Grasp, once per hook. Then you choose:
     - **chain button again** reels you to them (no extra charge), and they're tugged toward you when you arrive;
     - **swap hands (F)** hauls them to you at about 20 blocks a second, lifting them off the ground so they don't drag;
     - **do nothing** and the hook just did the damage. It slides out after 6 s.
   - **The hook lets go early** if a wall gets between you, if they get more than 44 blocks away, or if you slam. Flagpole also pulls it out as they go up. The swap-hands key never swaps your items while a hook is in.
   - **Walls snap the chain:** a block between you and the hook point (for a block, an anchor or an enemy) for 2 ticks in a row snaps it with a clank, instead of pinning you against the wall.
   - **Friends, animals and villagers:** a ride, never a hit.
   - **Missing costs nothing:** pointing at nothing, or at something in melee range, uses no charge. A total miss gives a quiet chain rattle.
   - **Re-chaining:** throwing again mid-reel to a block replaces the chain, so you can chain-hop.
   - **Time Dilate:** a field slows the reel, as before.
2. **Echoing Aegis.** A golden halo snaps around your waist for **1 second**.
   - Any melee hit or projectile in that second is **blocked**.
   - The attacker takes **half** of it back, is pushed away with Slowness II for 1.5 s, and loses 30 durability on their weapon.
   - **First parry only:** you heal 2.5 hearts, get Speed II for 3 s, and lose all harmful effects (Divine Purity). An **Echo Anchor** (a gold ring) appears up to 8 blocks above you for 5 s, and a chain thrown toward it latches on.
   - **Cooldown:** 15 s, or 10 s if you parried something.
   - **Only attacks are blocked:** not falls, fire or potions.
3. **Gilded Slam.** Sneak + chain while at least 4 blocks up.
   - A chain shoots to the ground where you're looking (within 24 blocks), or straight down, and yanks you into it.
   - A gold ring grows on the landing spot as you fall.
   - **Impact:**
     - everyone within 5 blocks takes 18 HP at the centre, falling to 9 at the edge;
     - they're popped up and out, and get Angels Grasp;
     - you see a gold shockwave and hear a heavy smash and a bell;
     - you take no fall damage.
   - **No cooldown, like the old slam.** Getting back up costs chain charges. `slam.cooldown-seconds` adds one; while it runs, sneak + chain in the air shows "Slam ready in Xs".
4. **Flagpole.** While your hook is in an enemy (or within 5 seconds of it), click left and right together.
   - They're hoisted 5 blocks up (less under a ceiling) and held there for 2.5 s, inside a spiral of gold.
   - Hits still push them, but they can't run off, and they can't pearl away.
   - Then they drop.
   - **Cooldown:** 25 s.
   - **Combo order:** if left lands first, you also fly to them, like the old combo. If right lands first, the Aegis it started is taken back, cooldown included.

## Passives (gem in either hand)

- **Feathered Fall:** 90% less fall damage. Unchanged.
- **Hauling Strike:**
  - +1 block of melee reach;
  - full-strength hits pull the target toward you instead of knocking them away;
  - a full-strength hit on a raised shield drops it and jams it for 5 s.
- **Angels Grasp:** slam and Flagpole victims can't use an ender pearl, wind charge, chorus fruit or trident for 4 s; a chain hook does it for only 2 s, so two charges can't lock someone down. Gold "shackles" show at their feet.
- **Divine Purity:** harmful effects on you last half as long, and a parry clears them.

## Action bar (the old icons, no words)

The main pack's own chain and Aegis icons (`⊵ ⊴`) with no words, like every gem's bar now:

- **Ready:** the icon is bright.
- **Recharging:** it's dimmed, with a timer.
- **Active:** it's followed by "…".
- **Chains** stay bright while a charge is left, with the next refill timer in darker blue. They show "…" during a slam.
- **Gold symbols**, only while they matter: `⚑ 20s` (Flagpole cooldown), `▲ 4s` (Echo Anchor up) and `▼ 3s` (only if you give the slam a cooldown).
- **"Not now"** (Flagpole with no hook, slam on cooldown) is a soft click only you hear. No text.

## What changed from the old Auratus

| Old | Now |
|---|---|
| Hauling Strike fired on a 50% random chance; plain right-click did a long-reach poke | Every full-strength hit hauls; real +1 reach on every hit |
| Angels Grasp: a 5% random pull, or armor durability (marked "an educated guess") | Hooked enemies can't escape for 4 s |
| Divine Purity was in the lore but did nothing | Debuffs last half as long; a parry cleanses |
| Aegis blocked *all* damage (falls, fire), reflected 100%, healed twice and posted a chat message | Blocks attacks only, reflects 50%, rewards once, no chat |
| A chain charge was spent before checking you hit anything | Misses are free |
| Slam: look steeply down while chaining, land, sneak within 3 s, 2-tick aim, dash, fall 4+ blocks | Sneak + chain in the air: one dive, done |
| Slam: 1.5 hearts flat across 6 blocks | 9 hearts at the centre to 4.5 at the edge (unarmored) |
| Flagpole: Levitation V for 5 s (about 40 blocks up) with your camera locked | 5 blocks up for 2.5 s; you can look around. Same left + right combo |
| Off-hand sneak + right-click fired the chain *and* the Aegis | One or the other |
| Haste II while crouching (not in the lore) | Removed |
| The slam ring sat below the ground at full size | It lies on the ground |

## Damage

Chains and slam now use the same `ability-damage` scaling as the standard gems (x3, capped at 40 HP per hit).

| Hit | Unarmored | Full Prot IV diamond |
|---|---|---|
| Chain arrival (old) | 2 hearts | 0.2 hearts |
| Chain arrival (new) | 4.5 hearts | 0.5 hearts |
| Slam (old, anywhere) | 1.5 hearts | 0.1 hearts |
| Slam centre (new) | 9 hearts | 1.2 hearts |
| Slam edge (new) | 4.5 hearts | 0.5 hearts |
| Aegis reflect | half the parried hit | half the parried hit |

The chain links, hook, slam ring and Aegis halo are models already in your main pack (echo shard models 12348, 12349, 12345 and 12346). Nothing new is needed in the pack for Auratus.

## Settings

All under `mythics.auratus` in config.yml, and in `/mythicconfig` (Auratus page).

**Your config is upgraded once.** Some old keys meant something else: `hauling-strike-reach: 4.0` was your *total* reach, and the new `hauling-strike-extra-reach` is *added* reach. So on the first start with this version:

- a config that still has the old Auratus section gets the new section;
- Feathered Fall's multiplier and the chain model settings are kept;
- the console says so.

| Section | Keys (defaults) |
|---|---|
| `passives` | `feathered-fall-damage-multiplier` 0.10, `hauling-strike-extra-reach` 1.0, `hauling-strike-pull` 0.3, `hauling-strike-shield-ticks` 100, `angels-grasp-seconds` 4, `angels-grasp-chain-seconds` 2, `divine-purity-multiplier` 0.5 |
| `chain` | `charges` 2, `recharge-seconds` 8, `range` 40, `min-entity-distance` 3.5, `reel-speed` 1.1, `ramp-ticks` 4, `max-reel-ticks` 40, `arrival-momentum` 0.45, `hit-damage` 3.0, `hook-seconds` 6, `haul-speed` 1.0, `haul-max-ticks` 30, `show-models` true, `link-spacing` 1.0, `link-scale` 1.0 |
| `aegis` | `cooldown-seconds` 15, `success-cooldown-seconds` 10, `window-ms` 1000, `reflect` 0.5, `attacker-slow-seconds` 1.5, `weapon-durability-loss` 30, `heal-hp` 5.0, `speed-seconds` 3, `anchor-height` 8, `anchor-seconds` 5, `anchor-cone-degrees` 35 |
| `slam` | `cooldown-seconds` 0, `min-height` 4, `range` 24, `dive-speed` 1.6, `radius` 5, `damage-center` 6.0, `damage-edge` 3.0, `launch` 0.45 |
| `flagpole` | `combo-ms` 100, `cooldown-seconds` 25, `hook-memory-seconds` 5, `range` 24, `height` 5, `hold-seconds` 2.5 |

## Files

**New**

- `src/main/java/dev/soulbound/AuratusGem.java`: the whole gem: controls, the four powers, the passives, visuals, the action bar, cleanup and the one-time config upgrade.
- `src/main/java/dev/soulbound/AuratusMath.java`: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity and the Aegis cooldown (no Bukkit, so they can be tested).
- `src/main/java/dev/soulbound/Charges.java`: independent charges, moved out of the Revenant's code so both can share it.
- `src/test/java/dev/soulbound/AuratusMathTest.java`: 7 tests.

**Changed**

- `MythicListener.java`: the old Auratus code is removed (about 800 lines). Its clicks and action bar now go to `AuratusGem`, and the tuner lists the new settings.
- `GemListener.java`: `abilityHit` lets mythic powers use the standard ability-damage scaling.
- `GemManager.java`: Auratus lore with the passives explained and the two powers' buttons (hidden combos stay hidden). Lore version 7, so existing gems update their lore once.
- `HudStatus.java` (new, with `HudStatusTest`): the word-free action-bar style shared by every gem.
- `GemType.java`: the Auratus description.
- `SoulboundGemsPlugin.java`: wiring, tick, shutdown.
- `RevenantMace.java`, `RevenantMath.java`, `RevenantMathTest.java`: use the shared `Charges`. No behaviour change.
- `config.yml`: the new `mythics.auratus` section.

## In-game test plan

You need a second player (or a friend), the new JAR and the merged pack.

1. `/blissop mythic auratus`. The action bar shows just the old chain and Aegis icons, bright, with no words.
2. Left-click a far wall: you're reeled to it. Click the sky: no charge is used and you hear a rattle. Throw both chains: the chain icon dims with a timer, and brightens again 8 s after the first.
3. Left-click the other player from 10 blocks: the hook bites (about 4.5 hearts unarmored, less in armor; no pearls for 2 s) and a chain stays between you. Do nothing: it lets go after 6 s. Hook them again and press F: they're hauled to you. Hook them again and left-click: you fly to them. Hook them and step behind a wall: the chain snaps.
4. Hook them, then click left and right together: they float 5 blocks up for 2.5 s in a gold spiral, then drop. The bar shows `⚑ 25s`. Try right-then-left: no Aegis cooldown is used.
5. Right-click as they hit you: the hit is blocked, they take half back and are slowed, and you heal with Speed II. A gold ring appears above you and the bar shows `▲ 5s`. Throw a chain toward it: you latch on. The Aegis comes back in about 10 s.
6. Right-click with nobody hitting you: you get a fizzle and a 15 s cooldown (the Aegis icon dims).
7. Chain up a wall to 6+ blocks, look at the ground nearby and sneak + left-click: you dive, a ring grows below, and the impact hurts players within 5 blocks with no fall damage to you. Do it again right away: no cooldown. Sneak + left-click 2 blocks up: just a normal chain.
8. Hit with full-strength swings: the target is pulled toward you, not knocked away. Hit a raised shield: it drops and can't be raised for 5 s. Hit from 4 blocks away: it lands.
9. Drink a Slowness potion while holding the gem: it lasts half as long.
10. Gem in the off hand with a sword: right-click throws a chain, sneak + right-click parries on the ground, and sneak + right-click 5 blocks up slams. With a bow in the main hand, right-click draws the bow.

## What was tested and what wasn't

**Tested here:**

- The plugin compiles against Paper 1.21.11.
- 41 unit tests pass, 7 of them for Auratus:
  - slam falloff and radius;
  - the reel ramp;
  - the anchor cone;
  - Flagpole's hover;
  - Divine Purity durations, including infinite effects;
  - the Aegis cooldown after a parry;
  - independent chain charges.
- The pack font still points at existing files, including the old Auratus icons.

**Not tested:** everything in game. That includes:

- how the reel and slam feel;
- whether the 0.1 s left + right window is comfortable (`flagpole.combo-ms` changes it);
- whether the pack models (links, hook, halo, slam ring) sit and turn right;
- the Hauling Strike pull, which uses Paper's knockback event;
- how the hook haul (F) feels on a player at high ping, and whether 6 s is the right hold;
- the wall snap on uneven ground (a hole's edge can count as a wall);
- the shield drop;
- Divine Purity on potions from other plugins;
- the off-hand rules with every item;
- the config upgrade on your real config file.
