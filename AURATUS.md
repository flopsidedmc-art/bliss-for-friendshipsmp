# Auratus, rebuilt

**Status: compiled and unit-tested, NOT played.** No Minecraft server or client was run. Everything below about how it looks and plays in game is the design, not an observation.

The same powers (chains, Aegis, slam, Flagpole and the passives), rebuilt underneath, with the **old action bar icons and no words**. Feathered Fall is exactly as it was.

## Controls

| Gem in **main hand** | What it does |
|---|---|
| Left-click | Throw a chain (2 charges). It hooks what it hits and stays |
| Right-click | **Echoing Aegis**, or, while a hook is in, **go to the hook** |
| Sneak + right-click | Aegis, even while a hook is in |
| Sneak + left-click, 4+ blocks up | **Gilded Slam** (sneak + chain in the air) |
| Swap hands (F), while a hook is in a creature | **Haul them to you** |

| Gem in **off hand** | What it does |
|---|---|
| Right-click | Throw a chain, or, while a hook is in, go to the hook |
| Sneak + right-click | Aegis on the ground; Gilded Slam when 4+ blocks up |
| Swap hands (F), while a hook is in a creature | Haul them to you |

### The combos (hidden, not in the lore)

| Sequence | Result |
|---|---|
| Hook an enemy → **F** (pull them in) → **Aegis** → nothing else | **Flagpole**: they're hoisted 5 blocks and held 2.5 s |
| Hook an enemy → **F** → **Aegis** → **chain again** within 0.75 s | **The launch**: thrown straight up about 30 blocks with a hit that kills a full-health player in full Prot IV diamond |

The Aegis "catches" them when its shield (3 blocks ahead, where you look) touches an enemy you hooked or pulled in the last 5 seconds. Pulling with F is the easy way to get them there: the haul stops them about 2.5 blocks in front of you. Flagpole and the launch share one 25-second cooldown, shown as `⚑` on the action bar.

Notes on the controls:

- **Doors and chests** still open with a plain right-click.
- **Off hand:** your main hand's own right-click still wins, so bows, food, pearls and placing blocks work normally.
- **Melee range:** a left-click on an enemy within melee reach is just a hit; it never wastes a chain.
- **F swaps your items normally** unless a hook is in a creature.

## Powers

1. **Venerated Perforators.** Throw a chain up to 40 blocks.
   - **2 charges**, each recharging on its own 8-second timer.
   - **It hooks whatever it hits and stays:** an enemy, any other creature, a block or the Echo Anchor.
     - **An enemy takes the hit straight away:** 9 HP (4.5 hearts unarmored, about half a heart through Prot IV diamond) and 2 s of Angels Grasp, once per hook.
     - **Other creatures** (friends, animals, villagers) are never hurt.
   - **While the hook is in:**
     - **Right-click** reels you there on a straight line, ramping up to about 22 blocks a second. You keep some momentum when you arrive, and landing ends it. Arriving at an enemy tugs them toward you.
     - **Swap hands (F)** hauls the creature to you at about 20 blocks a second. It lifts them off the ground so they don't drag, and stops them about 2.5 blocks in front of you.
     - **Do nothing** and it slides out after 6 s. With an enemy, the hit is already done.
   - **The hook lets go early** if a wall gets between you (it snaps with a clank), if they get more than 44 blocks away, or if you slam.
   - **Walls snap every chain:** a block between you and the hook point for 2 ticks in a row snaps it, so you're never pinned against a wall.
   - **Missing costs nothing:** pointing at nothing, or at something in melee range, uses no charge. A total miss gives a quiet chain rattle.
   - **Re-throwing:** throwing a new chain while one is in replaces it.
   - **Time Dilate:** a field slows the reel and the haul.
2. **Echoing Aegis.** For **1 second** you parry, and a golden shield (the pack's parry halo) stands **3 blocks ahead where you look**, facing you. It appears closer if a wall is nearer.
   - **The shield hits** any enemy at it in its first few ticks: 15 HP, 7.5 hearts unarmored, about 1 heart through Prot IV diamond. Once each.
   - **The parry:**
     - any melee hit or projectile on you in that second is blocked;
     - the attacker takes half of it back, is pushed away with Slowness II for 1.5 s, and loses 30 durability on their weapon.
   - **First parry only:** you heal 2.5 hearts, get Speed II for 3 s, and lose all harmful effects (Divine Purity). An Echo Anchor (a gold ring) appears up to 8 blocks above you for 5 s, and a chain thrown toward it hooks it.
   - **Cooldown:** 15 s, or 10 s if you parried something.
   - **Only attacks are blocked:** not falls, fire or potions.
3. **Gilded Slam.** Sneak + chain while at least 4 blocks up.
   - A chain shoots to the ground where you're looking (within 24 blocks), or straight down, and yanks you into it. A gold ring grows on the landing spot.
   - **Impact:** everyone within 5 blocks takes 18 HP at the centre, falling to 9 at the edge. They're popped up and out and get Angels Grasp, and you take no fall damage.
   - **No cooldown**, like the old slam; getting back up costs chain charges. Lower than 4 blocks, sneak + chain is just a chain.
4. **Flagpole** (combo).
   - **Trigger:** pull an enemy in, catch them with the Aegis, then do nothing else.
   - While caught, they're pinned at the shield for 0.75 s.
   - Then they're hoisted 5 blocks up (less under a ceiling) and held there for 2.5 s inside a spiral of gold. Hits still push them, but they can't run off or pearl away.
5. **The launch** (combo). Same as Flagpole, but chain again during that 0.75 s.
   - No Flagpole. Instead a chain lashes out and throws them **straight up about 30 blocks**.
   - It deals **one 72-damage hit**. That hit is raw player damage, straight through: not scaled, not capped.
   - It needs no chain charge.

## The launch against diamond armor

You asked for this one to be able to one-shot players in diamond armor. Against **full diamond with Protection IV on every piece** (armor 20, toughness 8, Protection 16), using vanilla 1.21's armor formula:

| Hit | Through Prot IV diamond |
|---|---|
| Chain hook | 0.9 HP |
| Aegis shield | 1.9 HP |
| **Launch** | **21.8 HP**: kills from full health (20) on its own |
| **Whole combo** | **24.6 HP**: also gets through a golden apple's 4 absorption hearts |
| Then the fall from ~30 blocks | about 9.7 more (5.4 with Feather Falling IV) |

- **What it doesn't beat:** a totem still saves them, and Resistance cuts it. Full Prot IV netherite takes about 20.1 from the launch: a kill from full health, just, with no golden-apple margin.
- **Unarmored:** it's overkill.
- **The unit test:** `AuratusMathTest.theLaunchComboKillsFullProt4Diamond` checks these numbers, so retuning can't quietly break the one-shot. The test caught that 70 was 0.002 HP short of beating a golden apple, which is why it's 72.

## Passives (gem in either hand)

- **Feathered Fall:** 90% less fall damage. Unchanged.
- **Hauling Strike:**
  - +1 block of melee reach;
  - full-strength hits pull the target toward you instead of knocking them away;
  - a full-strength hit on a raised shield drops it and jams it for 5 s.
- **Angels Grasp:** slam, Flagpole and launch victims can't use an ender pearl, wind charge, chorus fruit or trident for 4 s. A chain hook does it for only 2 s, so two charges can't lock someone down. Gold "shackles" show at their feet.
- **Divine Purity:** harmful effects on you last half as long, and a parry clears them.

## Action bar (the old icons, no words)

The main pack's chain and Aegis icons (`⊵ ⊴`) with no words, like every gem's bar:

- **Ready:** the icon is bright.
- **Recharging:** it's dimmed, with a timer.
- **Active:** it's followed by "…". The chain icon shows "…" while a hook is in or during a slam.
- **Chains** stay bright while a charge is left, with the next refill timer in darker blue.
- **Gold symbols**, only while they matter: `⚑ 20s` (Flagpole/launch cooldown), `▲ 4s` (Echo Anchor up) and `▼ 3s` (only if you give the slam a cooldown).

## Damage overview

Chains, the Aegis shield and the slam use the same `ability-damage` scaling as the standard gems (x3, capped at 40 HP per hit). The launch is the one exception.

| Hit | Unarmored | Full Prot IV diamond |
|---|---|---|
| Chain hook | 4.5 hearts | 0.5 hearts |
| Aegis shield | 7.5 hearts | 0.9 hearts |
| Slam centre / edge | 9 / 4.5 hearts | 1.2 / 0.5 hearts |
| Launch | 36 hearts | 10.9 hearts (a kill) |
| Aegis reflect | half the parried hit | half the parried hit |

## Settings

All under `mythics.auratus` in config.yml, and in `/mythicconfig` (Auratus page). A config that still has the pre-rebuild Auratus section gets the new one once (Feathered Fall and the chain model settings are kept).

| Section | Keys (defaults) |
|---|---|
| `passives` | `feathered-fall-damage-multiplier` 0.10, `hauling-strike-extra-reach` 1.0, `hauling-strike-pull` 0.3, `hauling-strike-shield-ticks` 100, `angels-grasp-seconds` 4, `angels-grasp-chain-seconds` 2, `divine-purity-multiplier` 0.5 |
| `chain` | `charges` 2, `recharge-seconds` 8, `range` 40, `min-entity-distance` 3.5, `reel-speed` 1.1, `ramp-ticks` 4, `max-reel-ticks` 40, `arrival-momentum` 0.45, `hit-damage` 3.0, `hook-seconds` 6, `haul-speed` 1.0, `haul-max-ticks` 30, `show-models` true, `link-spacing` 1.0, `link-scale` 1.0 |
| `aegis` | `cooldown-seconds` 15, `success-cooldown-seconds` 10, `window-ms` 1000, `distance` 3.0, `strike-radius` 1.3, `strike-damage` 5.0, `strike-ticks` 6, `reflect` 0.5, `attacker-slow-seconds` 1.5, `weapon-durability-loss` 30, `heal-hp` 5.0, `speed-seconds` 3, `anchor-height` 8, `anchor-seconds` 5, `anchor-cone-degrees` 35 |
| `slam` | `cooldown-seconds` 0, `min-height` 4, `range` 24, `dive-speed` 1.6, `radius` 5, `damage-center` 6.0, `damage-edge` 3.0, `launch` 0.45 |
| `flagpole` | `cooldown-seconds` 25 (Flagpole and the launch), `hook-memory-seconds` 5, `window-ms` 750, `finisher-damage` 72 (raw), `finisher-launch-height` 30, `height` 5, `hold-seconds` 2.5 |

## Files

**New**

- `AuratusGem.java`: the whole gem: controls, hooks, the Aegis shield, the combos, the passives, visuals, the action bar, cleanup and the one-time config upgrade.
- `AuratusMath.java`: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity, the Aegis cooldown, and vanilla's armor formula for the diamond check.
- `Charges.java`: independent charges, shared with the Revenant.
- `HudStatus.java`: the word-free action bar shared by every gem.
- `AuratusMathTest.java` (9 tests) and `HudStatusTest.java` (4).

**Changed**

- `MythicListener.java`: the old Auratus code is removed (about 800 lines); clicks and the action bar go to `AuratusGem`; the tuner lists the new settings.
- `GemListener.java`: `abilityHit` for mythic powers; the word-free action bar.
- `ChargePools.java`, `RelayGem.java`, `RelayRift.java`, `RevenantMace.java`: the word-free action bar.
- `GemManager.java`: Auratus lore, lore version 7.
- `GemType.java`: the Auratus description.
- `SoulboundGemsPlugin.java`: wiring, tick, shutdown.
- `config.yml`: the `mythics.auratus` section.

## In-game test plan

You need a second player (or a friend) in full Prot IV diamond, the new JAR and the merged pack.

1. `/blissop mythic auratus`. The action bar shows the old chain and Aegis icons, bright, no words.
2. Left-click a far wall: a chain hooks it and stays. Right-click: you fly there. Hook it again and do nothing: it slides out after 6 s. Hook it and walk behind a pillar: it snaps.
3. Left-click the other player from 10 blocks: the hook bites (about half a heart through their armor; no pearls for 2 s) and stays. Right-click: you fly to them.
4. Hook them again and press F: they're hauled to about 2.5 blocks in front of you. Your hotbar doesn't swap.
5. Right-click right after: the gold shield appears 3 blocks ahead, hits them (about 1 heart through armor) and pins them. Do nothing: after 0.75 s they're hoisted for 2.5 s. The bar shows `⚑ 25s`.
6. After the cooldown, hook → F → right-click → left-click quickly: they're launched about 30 blocks up and **die from full health in full Prot IV diamond**. Try again after they eat a golden apple: they still die.
7. Right-click with nobody near: you get a fizzle and a 15 s cooldown. Right-click as they hit you: the hit is blocked and you heal. A gold ring appears above you; throw a chain at it, then right-click to fly up to it.
8. Chain up 6+ blocks, sneak + left-click: you slam with no fall damage. 2 blocks up: just a chain.
9. Full-strength hits pull the target in. Hit a raised shield: it drops for 5 s. Drink Slowness while holding the gem: it lasts half as long.
10. Gem in the off hand with a sword: right-click throws or goes, sneak + right-click parries, F hauls. With a bow in the main hand, right-click draws the bow.

## What was tested and what wasn't

**Tested here:**

- The plugin compiles against Paper 1.21.11.
- 47 unit tests pass. They include:
  - the launch combo against full Prot IV diamond, golden apple included;
  - vanilla's armor formula;
  - the launch height;
  - slam falloff, the reel ramp, the anchor cone, Flagpole's hover, Divine Purity, the Aegis cooldown and chain charges;
  - the word-free action bar.
- The pack font still points at existing files, including the old Auratus icons.

**Not tested:** everything in game. That includes:

- whether the shield's 3-block spot and 1.3-block reach catch a hauled player reliably;
- how the haul (F) feels at high ping;
- whether the wall snap triggers on uneven ground (a hole's edge can count as a wall);
- how the halo looks standing up;
- the launch's real damage in game (Paper's damage path should match vanilla's formula, but other plugins or effects can change it);
- the config upgrade on your real config file.
