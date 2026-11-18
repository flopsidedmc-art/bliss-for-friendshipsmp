# The Revenant (Soul Mace boss weapon)

**Status: compiled and unit-tested, NOT played.** No Minecraft server or client was run. Everything below about how it looks and plays in game is the design, not something I watched happen.

## What it is

- A vanilla mace with the item model `blissgems:revenant_mace` (your 16x16 texture), named **The Revenant** in teal, unbreakable, with small-caps lore listing its passives and powers.
- **Not a gem.** It carries its own tag (`soulbound-gems:revenant_id`), not the gem tag, so the Trader, the Upgrader, energy and gem disables never see it.
- **Admin only.** It can't be crafted. `/revenant give <player>` is the only way to get it (permission `soulbound.revenant`, ops by default). It isn't tied to any admin: anyone can be given it.
- **Only one.** Each give records an id and a holder. A mace only works if it has the current id *and* is in the holder's hands. A creative copy or an old mace is inert, and its lore gets "ɪɴᴇʀᴛ: the soul is not in this one".
- **It never leaves its holder.** It can't be dropped, put in a chest, ender chest, bundle, item frame, armor stand or crafting grid, or handed to an allay. It vanishes on death and on logout. It never drops, even with keepInventory.

## Controls (Revenant in main hand)

| Input | Power |
|---|---|
| Right-click | **Plunge**: 2 charges, each recharging on its own 12 s timer |
| Sneak + right-click | **Rising**: 25 s cooldown |
| Sneak + left-click | **Undertow**: 30 s cooldown |
| Plain left-click | An ordinary mace hit |

Because right-click is Plunge, the holder can't open doors or chests with the mace in their main hand. Swap to another slot first.

## Powers

1. **Plunge.** He leaps about 12 blocks up, drifting toward where he's looking, then slams down on that spot (up to 16 blocks away, or the ground under it).
   - Every enemy within 4 blocks of the impact takes the base hit (6) plus the vanilla mace fall bonus (+4 per block for the first 3, +2 per block to 8, then +1). Damage falls to half at the edge of the circle.
   - **No hit can go over 28 HP (14 hearts)**, from any height. That is before armor. Through full Prot IV diamond, a centre hit is about 2.5 hearts.
   - Enemies are knocked outward, never upward, and get Abyssal Weight.
   - Teal dust and sculk souls, a ring on the ground, and the heavy mace smash sound.
   - The landing spot is moved inside the world border. If it is lava he lands on the nearest safe block within 3. If there is nowhere safe he doesn't leap and the charge isn't used. Water is fine.
2. **Rising.** He sinks into the ground for 1 second, rooted, with block particles and the Warden dig sound. Then he rises behind the nearest enemy *player* within 20 blocks, facing them (Warden emerge sound).
   - He never comes up inside a block or inside the target.
   - If the target left, died or changed world while he sank, he picks whoever is nearest now.
   - If there's no safe spot behind anyone, he rises where he stood and gets half the cooldown back.
   - Players inside Temporal Divinity's void world can't be targeted.
3. **Undertow.** Every enemy within 8 blocks is pulled to roughly melee range. It does no damage. You get conduit and heartbeat sounds, and three rings of sculk souls moving inward.

**Enemies** are hostile mobs, plus players in survival or adventure who aren't trusted either way. Trusted players, creative and spectator players and armor stands are never hit.

**Passives:**

- **Abyssal Weight:** anything his mace hits can't jump, or be launched upward, for 2 seconds. There's no potion icon or particles; it uses the jump attribute.
- **Soul-Bound Fall:** no fall damage while the mace is in either hand.

## Boss mode

- `/revenant boss start` needs the mace to have been given first. The fight starts with:
  - **120 HP (60 hearts)**, full health, all charges and cooldowns ready;
  - knockback resistance 0.6;
  - a boss bar for everyone within 100 blocks. The title is teal text. The bar itself is blue, since boss bars can't be teal.
- **Phase 2** at half health:
  - "The Revenant remembers falling.", a sculk shrieker sound, and "— Phase 2" on the bar;
  - Plunge recharges 30% faster;
  - the finale's red sky for 10 seconds (`phase2-sky`). This only happens if the finale isn't running and its sky isn't already on.
- **Death shatters him.** A teal burst, the Warden death and sonic boom sounds, and "The Revenant has shattered at the hands of X". The mace is destroyed, the stats come off and the fight is over.
- `/revenant boss stop` ends everything quietly with no shatter. It removes the stats, the boss bar and the mace, and also works on a paused fight.

### Logout, pause and return

- If the holder logs out mid-fight, the mace vanishes and the fight **pauses**.
  - The bar stays up for everyone online, reading "The Revenant has vanished…".
  - His health, phase and Plunge/Rising/Undertow timers are saved to `plugins/SoulboundGems/revenant.yml`.
- When the **same player rejoins**, there's a 5-second warning ("The soul returns to X…") with particles and heartbeats. Then the mace comes back with the saved health, and the fight goes on. Logging out never heals him.
- `/revenant give <player>` during a pause resumes the fight in that player's hands, with the saved health. `/revenant give <player> fresh` restarts it at full health with everything ready.
- Outside a fight (given, not started), logout or death just removes the mace. Give it again to bring it back.
- **Server stop or crash:** a running fight becomes paused with its health saved. On the next start, the holder's rejoin hands it back.

## The Relay counters him

- **Time Dilate:** the field slows him like anyone else (its normal 10%). Plunge's leap and slam are scaled by the field, and **Rising can't be cast** while he stands in one ("Time holds you here…"). The HUD shows Rising as "Slowed".
- **Temporal Divinity never takes him to the void.** Everyone else it catches goes as usual. He is **suspended** instead:
  - he's frozen up to 3 blocks above where he stood (less if there's a ceiling);
  - it lasts 5 seconds, or 3 in phase 2;
  - he can't walk, jump, fly, teleport (ender pearls, chorus fruit) or use any power;
  - his knockback resistance is 0, so hits push him through the air;
  - everyone can damage him;
  - brown clock particles turn around him, with a time-stop sound and "The Revenant is caught in the moment before the fall", and the bar reads "— SUSPENDED".
  - When it ends he drifts down gently with no fall damage, and can't be suspended again for 30 seconds.
  - If he is the only one Divinity catches, it still succeeds, costs the full Tuned In bar and goes on cooldown. The caster stays in the normal world.
  - Death, logout or server stop during a suspension puts everything back (gravity, flight, knockback). Even after a crash, his movement is restored when he rejoins.
- **Rift:** he can't open a rift, and can't follow anyone through one.
- The Revenant holder can't cast Divinity (he can't hold the Relay usefully anyway, since a mace in the main hand overrides gem input).

## HUD

While he holds the mace, the action bar shows his powers instead of the gem HUD, for example `▼ 2/2   ▲ Ready   ◎ 12s`. The icons are pack glyphs, 33x20, matching the Relay icons: dark iron frame with teal art.

| Codepoint | Icon | File |
|---|---|---|
| `U+E046` | Plunge (arrow into cracked ground) | `assets/blissgems/textures/icons/revenant_plunge.png` |
| `U+E047` | Rising (arrow up out of the ground) | `assets/blissgems/textures/icons/revenant_rising.png` |
| `U+E048` | Undertow (rings pulling in) | `assets/blissgems/textures/icons/revenant_undertow.png` |

`mythics.revenant.hud.use-glyphs: false` shows the words instead, for players without the pack.

## Commands

| Command | Does |
|---|---|
| `/revenant give <player>` | Gives the mace (moves it from the old holder outside a fight; resumes a paused fight) |
| `/revenant give <player> fresh` | Same, but a full-health restart |
| `/revenant boss start` / `stop` | Starts or ends the fight |
| `/revenant status` | State, holder, phase, saved health |

## Settings (`mythics.revenant` in config.yml)

`enabled`; `plunge.charges` (2), `recharge-seconds` (12), `leap-height` (12), `leap-forward-speed` (0.35), `range` (16), `slam-speed` (1.8), `radius` (4), `base-damage` (6), `damage-cap` (28), `knockback` (1.0); `rising.cooldown-seconds` (25), `range` (20), `sink-ticks` (20); `undertow.cooldown-seconds` (30), `radius` (8), `strength` (1.4); `abyssal-weight-seconds` (2); `boss.max-health` (120), `knockback-resistance` (0.6), `bar-radius` (100), `phase2-plunge-recharge` (0.7); `phase2-sky` (true), `phase2-sky-seconds` (10); `divinity-suspend-seconds` (5), `divinity-suspend-seconds-phase2` (3), `divinity-immunity-seconds` (30); `return-delay-seconds` (5); `hud.use-glyphs`, `plunge-glyph`, `rising-glyph`, `undertow-glyph`.

## Decisions I made

- **Uniqueness by id + holder, not by hunting copies.** A creative middle-click copies everything, including the id. So the plugin decides who the holder is, and copies are left inert, not deleted.
- **The cap covers every hit,** melee smashes included, not just Plunge. Otherwise a long drop with an ordinary mace swing would skip it.
- **Abyssal Weight uses the jump-strength attribute** (plus clamping upward speed), not a potion, so it has no icon and no particles.
- **Rising targets players only,** so it doesn't send him behind a zombie in a PvP fight.
- **Starting a fight heals him to full** and readies every power.

## Files

**New**

- `src/main/java/dev/soulbound/RevenantMace.java`: the item, powers, passives, boss fight, pause/return, Divinity suspension, HUD, bar, commands, protections, save file.
- `src/main/java/dev/soulbound/RevenantMath.java`: damage and cap, leap height, independent charges, the spot behind a target, border clamping (no Bukkit, so it can be tested).
- `src/test/java/dev/soulbound/RevenantMathTest.java`: 8 tests.
- Pack: `assets/blissgems/items/revenant_mace.json`, `models/item/revenant_mace.json` (parent `item/handheld_mace`), `textures/item/revenant_mace.png`, `textures/icons/revenant_plunge|rising|undertow.png`. Your texture is also kept in `tools/pack/source/revenant_mace.png`.

**Changed**

- `TemporalDomain.java`: Divinity suspends the Revenant instead of taking him; it succeeds when he's the only one caught.
- `RelayGem.java`: a caster whose Divinity only suspended him keeps their Rewind history and Dilate field.
- `RelayRift.java`: the Revenant can't open or follow rifts; `standable` is shared.
- `Finale.java`: `skyPulse(seconds)`, a short red sky for phase 2.
- `GemManager.java`: lore styling shared with the mace.
- `GemListener.java`, `MythicListener.java`: stay off his action bar and ignore gem input while he holds the mace.
- `HudText.java`: widths of the new icons.
- `SoulboundGemsPlugin.java`: wiring, tick, shutdown.
- `plugin.yml`: `/revenant` and `soulbound.revenant`.
- `config.yml`: `mythics.revenant`.
- `default.json` (pack font): the three icons.
- `tools/pack/relay_pack_assets.py`: builds the mace model and icons.

## In-game test plan

You need two accounts (or a friend), the server with the new JAR, and the merged pack.

1. `/revenant give <you>`. The mace shows your texture with teal lore. Try to drop it, put it in a chest, a bundle and an item frame: none of it works. Middle-click a copy in creative: the copy says "inert" and does nothing.
2. Right-click twice. You get two Plunges, then nothing until the first charge is back 12 s after it was used. You take no fall damage. Look at lava: it lands beside it. Look out past the world border: it lands inside.
3. Have the second player stand at the landing spot, naked and then in Prot IV diamond. Check they never take more than 14 hearts, even after you pillar up high and plunge. They can't jump for 2 seconds after any hit.
4. Sneak + right-click near them. You sink for 1 s, then rise behind them, facing them. Try it with them in a 1-wide tunnel: you rise in place and the cooldown shows about 13 s (half of 25).
5. Sneak + left-click with mobs and the player within 8 blocks: they're all pulled in, with no damage.
6. `/revenant boss start`. You have 60 hearts and a boss bar shows within 100 blocks. Take damage to below half: you see the phase 2 message, the red sky for 10 s, and faster Plunge recharge.
7. Second player with the Relay: cast Time Dilate on you. Rising is refused and Plunge is slow. Then cast Divinity: you stay in the normal world, frozen up in the air for 3–5 s with clock particles, and the bar says SUSPENDED. Hits push you; ender pearls fail. You drift down with no fall damage. A second Divinity within 30 s doesn't suspend you.
8. Log out mid-fight. The bar says "The Revenant has vanished…". Log back in: 5 s warning, then the mace returns at the same health. Try `/revenant give <you> fresh` for full health.
9. Stop the server mid-fight and start it again. Rejoin: the fight resumes with the saved health and you're not stuck floating.
10. Die to the second player. You see the shatter burst, the sounds and the message; the mace is gone and the bar disappears. Then give it again and run `/revenant boss stop`: everything is removed quietly.

## What was tested and what wasn't

**Tested here:**

- The plugin compiles against Paper 1.21.11.
- 34 unit tests pass, 8 of them new:
  - the smash bonus and 28-HP cap from any height, and falloff to half at the edge;
  - leap height from launch speed;
  - two charges recharging independently, surviving a resize and a save/load;
  - the spot behind a target and facing it;
  - landing points clamped inside the world border.
- The pack JSON parses, and every file the font and models point to exists.

**Not tested:** everything in game. That includes:

- how Plunge feels and where it lands;
- Rising's spot choice on real terrain;
- Undertow's pull strength;
- the boss bar, the phase 2 sky and the shatter;
- logout/rejoin and restart behaviour;
- the Divinity suspension;
- whether the icons sit right on the action bar;
- whether your texture looks right in hand with the `handheld_mace` parent.
