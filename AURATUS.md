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
| Swap hands (F), while a chain is out and nothing is hooked | **Let go**: the hook reels back. Before it bites anything the charge comes back; after, it's spent. Flying to the hook: you let go and keep your momentum |
| Q (drop key) | Drops the gem: Auratus isn't soulbound any more and can be handed to someone. A chain that's out goes with it |

| Gem in **off hand** | What it does |
|---|---|
| Right-click | Throw a chain, or, while a hook is in, go to the hook |
| Sneak + right-click | Aegis on the ground; Gilded Slam when 4+ blocks up |
| Swap hands (F), while a hook is in a creature | Haul them to you |
| Swap hands (F), while a chain is out and nothing is hooked | Let go: the hook reels back. Before it bites anything the charge comes back; after, it's spent |
| Q (drop key) | Drops the gem. A chain that's out goes with it |

### The combos (hidden, not in the lore)

| Sequence | Result |
|---|---|
| Hook an enemy → **F** (pull them in) → **Aegis** → nothing else | **Flagpole**: they're hoisted 5 blocks and held 2.5 s |
| Hook an enemy → **F** → **Aegis** → **chain again** within 0.75 s | **The launch**: thrown straight up about 30 blocks with a hit equal to the chain, shield and slam together (19.8 HP through full diamond); with the chain and shield first, the combo kills from full health |

The Aegis "catches" them when its shield (3 blocks ahead, where you look) touches an enemy you hooked or pulled in the last 5 seconds. Pulling with F is the easy way to get them there: the haul stops them about 2.5 blocks in front of you. Flagpole and the launch share one 25-second cooldown (not shown on the action bar, which only has the chain and Aegis).

Notes on the controls:

- **Doors and chests** still open with a plain right-click.
- **Off hand:** your main hand's own right-click still wins, so bows, food, pearls and placing blocks work normally.
- **Melee range:** a left-click on an enemy within melee reach is just a hit; it never wastes a chain.
- **F swaps your items normally** unless a hook is in a creature.

## Powers

1. **Venerated Perforators.** Throw a chain up to 40 blocks.
   - **2 charges**, each recharging on its own 8-second timer.
   - **It hooks whatever it hits and stays:** an enemy, any other creature, a block or the Echo Anchor.
     - **An enemy takes the hit straight away:** 5 HP (2.5 hearts) through full diamond, 14.5 unarmoured, and 2 s of Angels Grasp, once per hook.
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
   - **The shield hits** any enemy at it in its first few ticks: 8 HP (4 hearts) through full diamond, 20 unarmoured. Once each.
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
   - It deals **one hit that is the whole combo**: through full diamond, exactly what the chain, the shield and the slam's centre deal there together (19.8 HP; 35.7 raw). It's worked out from those three settings, so retuning any of them moves it. It isn't capped.
   - It needs no chain charge.

## Damage against armour

Every Auratus hit (the chain hook, the Aegis shield, Gilded Slam, the launch and the Aegis reflect) is cut by **armour points and toughness as normal** (vanilla's formula), but **Protection never counts**, nor Blast or Projectile Protection. Resistance and totems still work.

The numbers are raw HP in config (not multiplied by `ability-damage.multiplier`), set against **full unenchanted diamond** (20 armour, 8 toughness), with vanilla 1.21's formula: damage x (1 - clamp(armour - damage / (2 + toughness / 4), armour / 5, 20) / 25).

| Hit | Unarmoured | Full iron | Full diamond | Full netherite |
|---|---|---|---|---|
| Chain hook (14.495) | 14.5 HP | 10.0 HP | **5.0 HP** (2.5 hearts) | 4.6 HP |
| Aegis shield (20) | 20.0 HP | 16.0 HP | **8.0 HP** (4 hearts) | 7.2 HP |
| Slam centre (18, as before) | 18.0 HP | 13.7 HP | 6.8 HP | 6.2 HP |
| Slam edge (9, as before) | 9.0 HP | 5.2 HP | 2.6 HP | 2.5 HP |
| **Launch** (35.7, worked out) | 35.7 HP | 31.4 HP | **19.8 HP** = chain + shield + slam centre | 17.3 HP |
| Aegis reflect | half the parried hit, through their armour | | | |

- **The whole launch combo through full diamond:** chain 5 + shield 8 + launch 19.8 = **32.8 HP**, then the fall from ~30 blocks. The launch on its own no longer quite kills from full health (19.8 of 20); the combo does, with room for a golden apple's absorption.
- **The unit tests** (`AuratusMathTest`) check these numbers.

## Passives (gem in either hand)

- **Feathered Fall:** 90% less fall damage. Unchanged.
- **Hauling Strike:**
  - +1 block of melee reach;
  - full-strength hits pull the target toward you instead of knocking them away;
  - a full-strength hit on a raised shield drops it and jams it for 5 s.
- **Angels Grasp:** slam, Flagpole and launch victims can't use an ender pearl, wind charge, chorus fruit or trident for 4 s. A chain hook does it for only 2 s, so two charges can't lock someone down. Gold "shackles" show at their feet.
- **Divine Purity:** harmful effects on you last half as long, and a parry clears them.

## Action bar

Just the main pack's chain and Aegis icons (`⊵ ⊴`), no names:

- **Ready:** the icon is bright, with "Ready!". Chains say "Ready!" while at least one charge is left.
- **Recharging:** the icon is dimmed, with a timer.
- **Active:** the icon is followed by "…" (a hook is in, a slam, or the Aegis is up).

## Damage overview

See "Damage against armour" above: raw HP, armour counts, Protection doesn't. The chain, shield and slam still have the `ability-damage` cap (40 HP a hit); the launch doesn't.

## Settings

All under `mythics.auratus` in config.yml, and in `/mythicconfig` (Auratus page). A config that still has the pre-rebuild Auratus section gets the new one once (Feathered Fall and the chain model settings are kept).

| Section | Keys (defaults) |
|---|---|
| `passives` | `feathered-fall-damage-multiplier` 0.10, `hauling-strike-extra-reach` 1.0, `hauling-strike-pull` 0.3, `hauling-strike-shield-ticks` 100, `angels-grasp-seconds` 4, `angels-grasp-chain-seconds` 2, `divine-purity-multiplier` 0.5 |
| `chain` | `charges` 2, `recharge-seconds` 8, `range` 40, `min-entity-distance` 3.5, `reel-speed` 1.1, `ramp-ticks` 4, `max-reel-ticks` 40, `arrival-momentum` 0.45, `hit-damage` 14.495 (raw HP), `hook-seconds` 6, `haul-speed` 1.0, `haul-max-ticks` 30, `show-models` true, `link-scale` 1.0 |
| `aegis` | `cooldown-seconds` 15, `success-cooldown-seconds` 10, `window-ms` 1000, `distance` 3.0, `strike-radius` 1.3, `strike-damage` 20.0 (raw HP), `strike-ticks` 6, `reflect` 0.5, `attacker-slow-seconds` 1.5, `weapon-durability-loss` 30, `heal-hp` 5.0, `speed-seconds` 3, `anchor-height` 8, `anchor-seconds` 5, `anchor-cone-degrees` 35 |
| `slam` | `cooldown-seconds` 0, `min-height` 4, `range` 24, `dive-speed` 1.6, `radius` 5, `damage-center` 18.0, `damage-edge` 9.0 (raw HP), `launch` 0.45 |
| `flagpole` | `cooldown-seconds` 25 (Flagpole and the launch), `hook-memory-seconds` 5, `window-ms` 750, `finisher-launch-height` 30, `height` 5, `hold-seconds` 2.5, `ceiling-cap` false (true: stop below a ceiling) |
| `visuals` | `slam-burst` true (white sphere), `slam-sparks` true, `slam-spark-count` 30, `launch-burst` true (gold sphere), `flagpole-burst` true (gold sphere) , `chain-travel` true, `chain-travel-ticks` 5, `chain-reel-in` true, `chain-reel-ticks` 4 |
| `release` | `refund-before-hit` true |

## Files

**New**

- `AuratusGem.java`: the whole gem: controls, hooks, the Aegis shield, the combos, the passives, visuals, the action bar, cleanup and the one-time config upgrade.
- `AuratusMath.java`: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity, the Aegis cooldown, and vanilla's armor formula for the diamond check.
- `Charges.java`: independent charges, shared with the Revenant.
- `HudStatus.java`: the action bar style shared by every gem (icon, "Ready!" or a timer, no names).
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

You need a second player (or a friend) in full diamond (Protection makes no difference now), the new JAR and the merged pack.

1. `/blissop mythic auratus`. The action bar shows the chain and Aegis icons, each with "Ready!".
2. Left-click a far wall: a chain hooks it and stays. Right-click: you fly there. Hook it again and do nothing: it slides out after 6 s. Hook it and walk behind a pillar: it snaps.
3. Left-click the other player from 10 blocks: the hook bites (2.5 hearts through full diamond; no pearls for 2 s) and stays. Right-click: you fly to them.
4. Hook them again and press F: they're hauled to about 2.5 blocks in front of you. Your hotbar doesn't swap.
5. Right-click right after: the gold shield appears 3 blocks ahead, hits them (4 hearts through full diamond) and pins them. Do nothing: after 0.75 s they're hoisted for 2.5 s.
6. After the cooldown, hook → F → right-click → left-click quickly: they're launched about 30 blocks up and **die from full health in full diamond** (chain + shield + launch is 32.8 HP). Try again after they eat a golden apple: they still die.
7. Right-click with nobody near: you get a fizzle and a 15 s cooldown. Right-click as they hit you: the hit is blocked and you heal. A gold ring appears above you; throw a chain at it, then right-click to fly up to it.
8. Chain up 6+ blocks, sneak + left-click: you slam with no fall damage. 2 blocks up: just a chain.
9. Full-strength hits pull the target in. Hit a raised shield: it drops for 5 s. Drink Slowness while holding the gem: it lasts half as long.
10. Gem in the off hand with a sword: right-click throws or goes, sneak + right-click parries, F hauls. With a bow in the main hand, right-click draws the bow.

## What was tested and what wasn't

**Tested here:**

- The plugin compiles against Paper 1.21.11.
- 47 unit tests pass. They include:
  - the combo against full diamond, golden apple included;
  - vanilla's armor formula;
  - the launch height;
  - slam falloff, the reel ramp, the anchor cone, Flagpole's hover, Divine Purity, the Aegis cooldown and chain charges;
  - the action bar format.
- The pack font still points at existing files, including the old Auratus icons.

**Not tested:** everything in game. That includes:

- whether the shield's 3-block spot and 1.3-block reach catch a hauled player reliably;
- how the haul (F) feels at high ping;
- whether the wall snap triggers on uneven ground (a hole's edge can count as a wall);
- how the halo looks standing up;
- the launch's real damage in game (Paper's damage path should match vanilla's formula, but other plugins or effects can change it);
- the config upgrade on your real config file.
