# Pre-launch audit: Soulbound Gems 1.3.0 + Pantheon 2.1.0 (Solstice)

Paper 1.21.11 (also 26.2). Both plugins compile with Maven against paper-api 1.21.11:
- **Soulbound Gems:** 292 tests pass (11 new).
- **Pantheon:** 18 tests pass. It has no code changes.

**Nothing was run on a server.** Everything here is from reading the code.

**How it was read:**
- **Read in full:** the systems that can lose items or trap players. That's the main class, gem death/respawn/drop/storage, Astral Projection and Unbounded, land changes (LandWorks), the Gold Gem's deaths, joins, quits and switching, the energy manager, Wealth's Pockets, and the Pantheon events' death and shutdown paths.
- **Searched instead:** about 26,600 lines across 80 classes. These were checked with targeted searches: every event handler, task, file, team, entity spawn, game-mode change, teleport, `setHealth(0)`, action-bar writer, permission and config read. I read the code around every hit.
- **Not checked line by line:** the full control map (every click combination of every gem).

Ranking: **launch blockers** (crash, dupe, lost gem, stuck player, data loss) → **gameplay bugs** → **polish**.

---

## 1. System map

### Soulbound Gems (dev.soulbound)

| System | Listeners / tasks | Files on disk | Teams / entities | Talks to |
|---|---|---|---|---|
| **Gems core** (GemListener, GemManager, EnergyStage, HudStatus) | interact, damage, death/respawn, drop, inventory guards; gem tick every `min(passives.tick-seconds*20, actionbar.update-ticks)` = 10 ticks; Astra daggers every tick | `trust.yml`, `respawn-gems.yml`, `pockets.yml` (new) | Astra dagger / fireball / arrow ItemDisplays (non-persistent), Drift horse, Blur clones | every gem and mythic, Gold Gem, Bounty, Flux, Amplification, Pantheon (trust via public API) |
| **Pristine stages** (EnergyManager) | kill (any entity), death; called from the gem tick | `energy.yml` (on disable only) | – | chat only (no cooldown effect: `cooldownFactor` is never called) |
| **Mythics** (MythicListener, AuratusGem, RevenantMace, RelayGem + TemporalDomain/TemporalFlow/RelayRift, LooseMythics, Prophet) | 1-tick tasks for Relay, Auratus, Revenant; LooseMythics every 5 ticks | `revenant.yml`, `relay-rift.yml`, `relay-domain-recovery.yml`, `prophet.yml` | Bloodsaw armour stands + displays, Auratus chains/halos, Rift planes, Temporal clock, loose mythic items | Finale, capture points, Prophet ↔ containers |
| **Gold Gem** (GoldGem, GoldMath, GoldCommand) | swap-hands, interact, death (MONITOR), join, quit; per-scene and per-ritual tasks | `gold.yml` | team `sg_gold_marked` (red), glow, red tab name; Mannequin dummy (tests), scene ItemDisplay | GemListener (cooldown cap, HUD), Pantheon (repurpose = holder's kill) |
| **Astra spectator** (AstralSpectate, SpectatorSessions, MannequinBody) | 1-tick task; quit/death/world/teleport/portal/spectate guards | `astral-restore.yml` (written at start and end) | Mannequin or zombie body (non-persistent) | GemListener (gem disable) |
| **Land** (LandWorks, CrispLand, VortexDrain, LandLedger) | block guards (flow, piston, explode, decay...) | `land-restore.yml` (saved at cast and every 20 steps; restored on enable) | – | captures, Rift, Finale (keep-out areas) |
| **Events** (Turmoil, Finale, CaptureManager, Pedestal, ShadowStalker, BountyHunt) | 1-tick tasks while running | `turmoil.yml`, `finale-border.yml`, stash per player, `capture-points.yml`, `pedestal.yml` | Finale displays, capture TextDisplays and boss bars | Finale reads **Pantheon's** `points.yml` + `config.yml` |
| **Combat rules** (RuleCore, CombatRules, ItemRules, MaceRules) | 10-tick task; item, craft, eat, throw, glide, chat and death-message handlers | `maces.yml` | tab un-listing while invisible | the name scramble used everywhere |
| **Names / provenance / others** | – | `names.yml`, `starter-gems.yml`, `progression-data.yml`, `assessment-data.yml`, `builds/` | portal obsidian marker stands (persistent on purpose) | – |

### Pantheon (dev.flopsided.pantheon)

| System | Listeners / tasks | Files | Talks to |
|---|---|---|---|
| Duels, Teams, Command, Motion, Signal | damage/death/move/quit per event; countdown and match tasks | `points.yml`, `arenas.yml`, `signal.yml`, `stash/<uuid>.yml` | – |
| **Solstice** (Solstice, SolsticeStore, SolsticeBoard, SolsticeCmd, SoulboundLink) | hit/respawn/death/join/quit; a single 1 s task (board, weekly post, pruning) | `solstice.yml`, `solstice-kills.log` | Soulbound **trust** (public API) and **shownName** (reflection); Pantheon points via `PointsStore.onChange` |

The two plugins only touch each other in three places:
- **Pantheon → Soulbound:** Pantheon reads Soulbound's trust list and name scramble (both read-only).
- **Soulbound → Pantheon:** the Finale reads Pantheon's `points.yml` to find the leader.
- **Shared deaths:** both plugins react to the same `PlayerDeathEvent`.

**Pantheon does not block any gem power during its events, and Soulbound doesn't know when a Pantheon event is running.** This is the biggest design gap (see section 4).

---

## 2. Checklist

### Launch blockers

| # | Result | Where | Why |
|---|---|---|---|
| L1 | **BUG → fixed** | `GemListener.java:3453-3527` | **Gem duplication.** The gem was kept for respawn at NORMAL priority. If something later turned keepInventory on (Pantheon's Command trial and Motion runs do, at NORMAL/HIGH), the player kept the gem **and** got a copy on respawn (added, or dropped on the ground for anyone). A held mythic was also taken out of the hand and then deleted with the cleared drops. |
| L2 | **BUG → fixed** | `GemListener.java:3612-3712` | **Gems in containers.** Only clicks *inside* a chest were checked. Shift-click from your inventory, number keys, the offhand key and dragging all put gems into chests and shulkers. Bundles, item frames, allays, armour-stand hands and decorated pots weren't guarded at all, so a gem could be stored, carried in a bundle (then dropped or stored) or stolen from a frame. |
| L3 | **BUG → fixed** | `GemListener.java:3658-3676` | **Gems crafted away.** Gems are plain vanilla items underneath, and nothing stopped them being used as ingredients. Tier 1 is an amethyst shard (spyglass, tinted glass, trims), Tier 2 a prismarine shard, Auratus a gold ingot (nuggets, blocks, trims, beacon payment, cleric trades, anvil repair of gold gear) and Heretic magenta dye (looms, dyeing). Crafting with one **deleted the gem**. |
| L4 | **BUG → fixed** | `GemListener.java:1870-1900` | **Wealth's Pockets lost everything on restart.** It was a 9-slot inventory kept only in memory, so every restart, `/reload` or crash wiped what players had stored. |
| L5 | **BUG → fixed** | `GemListener.java:1971-1992` | **Thunderstep through walls / into the ground.** It moved you 3 blocks the way you look and only checked the block at your feet: walls up to 2 thick could be passed (out of arenas, into bases), and looking down left you inside the ground. |
| L6 | **RISK (decide)** | Pantheon events + `GoldGem.java:822`, `RelayGem.java:269`, `RelayRift.java:419`, `GemListener.java:1971` | **Gem powers inside Pantheon events.** Nothing is blocked. Teleports (Rewind, Rift, Thunderstep, Revenant) can leave a duel or a parkour course. Astral Projection (spectator) during a duel start is pulled back to the body. The Gold Gem repurpose kills with `setHealth(0)`, bypassing Duels/Teams' "cancel the lethal hit", so the duel doesn't see the knockout. Movement powers trivialise Motion. See D1. |
| L7 | OK | `AstralSpectate.java` | Spectator safety: every exit goes through `end()` once; there's a restore record on disk for logouts and crashes, plus handling for death, world change, portals, spectator teleports and disable. **Only Astra ever changes a game mode.** |
| L8 | OK | `GoldGem.java:877-945` | Gold Gem holder death releases every socketed or held gem to its owner (`pending` on disk if offline); quitting mid-repurpose finishes it; the ritual is cancelled on quit or death. |
| L9 | OK | Pantheon `CommandPantheon` | The Command trial stashes the follower's whole inventory (gem included) to disk before clearing it, and restores it on end, shutdown and next join. |
| L10 | OK | `LandWorks.java:120-170` | Land changes are saved at cast time, restored on disable and again on the next enable after a crash (worlds not loaded are kept for later). |

### A. Events vs gems

| Item | Result | Where | Why |
|---|---|---|---|
| Kill points during a Pantheon event | OK | `Solstice.java` (`inEvent` before scoring) | Killer *or* victim in a running Duels/Teams/Command/Motion event → no points, no log line. Duels and Teams never produce real deaths anyway. |
| Land vs protected places | RISK | `LandWorks.java:174` | Crisp and Vortex keep out of running capture points, the Rift and the finale arena, and only change **natural** blocks. They don't know Pantheon arenas, the spawn board, the Gold Gem ritual site or the pedestal; natural blocks there can change (and are restored by their timer). D2. |
| Land when an event starts mid-effect | RISK | `CaptureManager.riftBlocked` | A capture point only protects while running; land changed just before a start stays changed until it cools (≤ the effect's time). |
| Deaths in Pantheon Command / Motion | RISK (decide) | `GemListener.onDeath`, `GoldGem.onHolderDeath`, `RevenantMace.onDeath` | These are real deaths (keepInventory), so gem energy −1, the Gold Gem sheds every socket, the Revenant shatters and bounties end, all for falling in parkour. D3. |
| Gem disables during events | OK | `GemListener.applyGemDisable` | Time-based; no event start/end clears or extends them. |
| Unbounded rider and events | RISK | `AstralSpectate.java:254-290` | Someone riding inside a duel fighter (Unbounded) comes out *behind* them, inside the arena. |
| Teleports out of events | RISK | see L6 | Not stuck, but players can leave events. |

### B. Combat rules

| Item | Result | Where | Why |
|---|---|---|---|
| Rules inside events | OK (by design) | `RuleCore.java:31` | Rules apply in every world unless `rules.worlds.<world>: false`. Set that for a dedicated event world if you want events rule-free. |
| Gem abilities caught by the rules | OK | `ItemRules`, `CombatRules` | The rules act on items (drinking, throwing, brewing, crafting, riptide tridents, wind charge items, gliding toggles), never on effects a gem applies with `addPotionEffect`. The Golden Drill uses `startRiptideAttack`, not a trident. |
| Name scramble: chat, death messages, bounties, capture text, Gold Gem | OK | `CombatRules.java:302,331`, `BountyHunt`, `CaptureManager:437,487`, `GoldGem` | Uses `shownName`. Admins with `soulbound.rules.see-invisible` see real names. |
| Name scramble: Revenant "shattered at the hands of" | **BUG → fixed** | `RevenantMace.java:766` | It used the real killer name. |
| Name scramble: Solstice betrayal/bounty | OK | Pantheon `Solstice.java` | Scrambled; the log keeps real names. |
| Name scramble: Pantheon Teams "eliminated" feed, Finale/Revenant holder announcements | RISK | `TeamsPantheon.registerKill`, `Finale.java:1067,1470`, `RevenantMace.java:718,899` | Real names. The holder announcements are probably meant to reveal (everyone hunts the holder). D4. |

### C. Season systems

| Item | Result | Where | Why |
|---|---|---|---|
| Matrix, repeat cooldown, spawn protection, environmental credit, trusted modes, streaks, score sum | OK | `SolsticeMath` + 13 tests | Unchanged since 2.1.0. |
| Same-IP check behind a proxy | RISK | `Solstice.ip()` | Behind Velocity/Bungee **without IP forwarding**, everyone shares one IP and every kill scores 0. Check your proxy forwards real IPs, or set `solstice.kill-points.same-ip-check: false`. |
| Board duplicates | OK | `SolsticeBoard` | The displays are never saved with the chunk, leftovers are cleared on enable and it's rebuilt when the chunk reloads. |
| Weekly post | OK | `SolsticeMath.weeklyDue` | Once per date, survives restarts (`last-weekly`). |
| Soulbound missing, disabled, or loaded second | OK | `SoulboundLink` | Checked each call (`isEnabled`); missing → nobody trusted, real names, one warning at most. Pantheon soft-depends, so Soulbound loads first. |
| Kills during Soulbound events (Finale battle, capture fights, Revenant boss) | RISK (decide) | `Solstice.onDeath` | They score kill points. D5. |
| Finale picks "the Pantheon leader" | RISK (decide) | `Finale.java:300` | It reads Pantheon points only (best 4 of 5, eligibility), matching `/pantheon board`, **not** the Solstice season score. D6. |
| Kill-point action bar | RISK (polish) | `Solstice.onDeath`, `GemListener.java:589` | The gem HUD redraws every 10 ticks, so "+3 kill points" shows for at most half a second. D7. |

### D. Shared things

| Item | Result | Where | Why |
|---|---|---|---|
| Scoreboard teams | OK | `BlurClones` (entities only), `GoldGem:490-526` (`sg_gold_marked`) | Pantheon uses no scoreboard teams. Gold marks remember the old team and put it back. |
| Gold mark's previous team after a restart | RISK | `GoldGem.prevTeams` | In memory only; a restart while marked loses the player's previous team (only matters if another plugin uses teams). |
| Glow | OK | `GoldGem.unmarkVisuals` | Only the Gold Gem sets a player's glow flag; Blur clones and displays glow on their own entities. |
| Action-bar writers | RISK (polish) | 15 classes | The gem HUD (every 10 ticks) overwrites one-off messages: combat tag "In combat", rule refusals, Solstice kill points, Pantheon Motion/Teams bars. Only the tracking alert has a hold (`trackAlertUntil`). D7. |
| Controls | NOT CHECKABLE (in full) | – | Checked the known overlaps: Gold Gem sneak+F (only with the Gold Gem in your main hand; F alone swaps as normal), Flux Q vs drop, Astra/Wealth right-click on entities (still run when the new frame/allay guard cancels the click). The Command trial's F key is used by a follower whose inventory is stashed (no gem). |
| Drop on the tick of death | OK | `GemListener.onDrop`, `LooseMythics` | Gems can't be dropped (except Auratus/Heretic, protected on the ground: no despawn, no damage, no hoppers, no mobs). |
| Creative middle-click | RISK | – | Any creative op can clone gems. Admins only. |
| Gem placement in the 2x2 crafting grid, smithing, looms, trades, beacons | **fixed** | L3 | – |
| Death with two gems | RISK | `GemListener.java:3470` | Only the first non-mythic gem is kept for respawn; a second one is deleted. Normally impossible (one gem each), but `/bliss give` can create it. |

### E. Cleanup and persistence

| Item | Result | Where | Why |
|---|---|---|---|
| Bloodsaw armour stands | **BUG → fixed** | `MythicListener.java:199,224` | Saved with the chunk; a stop mid-throw left invisible stands forever. Now not persistent, and removed on disable. |
| Astra Drift horse | **BUG → fixed** | `GemListener.java:2425-2440` | Saved with the chunk (a stop within its 5 s left a tame saddled horse), and killing it dropped a free saddle. |
| Other spawned entities | OK | Auratus, Capture, Finale, Gold, Rift, Temporal, Astral, Blur | All `setPersistent(false)` and/or removed in `shutdown()`. The portal obsidian markers are persistent on purpose. |
| Player references after logout | OK | all classes | Every field map is keyed by UUID; `Player` objects only live in local variables. |
| Tasks | OK | – | Bukkit cancels every plugin task on disable; each system with a running effect also cleans its state in `shutdown()`. |
| Pristine-stage save | **BUG → fixed** / RISK | `EnergyManager.java:113-120` | A value that dropped to 0 was never cleared on disk and came back after a restart (fixed). It still saves only on disable, so a crash loses stage changes since start (low value: see D8). |
| Plugin disable mid-event | OK | `SoulboundGemsPlugin.onDisable` | Astral first, then land, blessings, captures, gem state, Revenant, Relay, Auratus, Finale, Turmoil, overlay, bounties, Amplification, Flux, Gold Gem. Pantheon: Command trial ends (inventory back), Motion runs cancel, Teams cancels, Duels stops its tasks (fighters stay where they are). |
| Bounty offering open during a crash | RISK | `BountyHunt` | Items in an open offering menu are lost in a crash (given back on a normal close or stop). |

### F. Performance (estimates at 10 players)

| What | Cost | Note |
|---|---|---|
| Gem tick (every 10 ticks): HUD, passives, per player | low-medium | Builds a HUD string per player every half second; fine at 10. |
| Relay tick (every tick, all players every 2 ticks) | low | Reads the main and offhand items for everyone every 2 ticks; restyles only when changed. |
| Astral, Capture, Flux, Auratus, Revenant, Finale, Turmoil (every tick) | low when idle | Each returns early when nothing is running. |
| Particles | medium during effects | RelayRift (forced particles, visible far), SporeFountain, Finale, Auratus chains. Only while running. |
| Disk writes on the main thread during play | low | Small YAML writes on change: trust, respawn gems, Gold Gem (every switch), astral record, land (per cast), Pockets (each close), Revenant (every 5 s in a fight), Pantheon points, `solstice.yml` + the kill log (per kill). None in a per-tick loop. |

Nothing needs changing at 10 players. If you grow: move the Solstice log and `solstice.yml` writes to `runTaskAsynchronously`, and save `gold.yml` less often than every switch.

### G. Versions

| Item | Result | Where | Why |
|---|---|---|---|
| Mannequin (1.21.9+) | OK | `MannequinBody.available()` | Falls back to a zombie (body), a vex (Blur), an armour stand (Gold test dummy). |
| `item_model`, display entities, `startRiptideAttack`, list/unlist player | OK | – | Present on 1.21.11 and 26.2. |
| Experimental data-component API (Relay item clean-up) | **RISK → fixed** | `GemManager.java:555-570` | If 26.x changes it, the Relay tick would throw every 2 ticks. It now logs once and skips. |
| Sound names | OK | `GemSounds.unknown` | Unknown sounds are logged once at start-up and play silent. |
| Particle names | NOT CHECKABLE | – | A renamed `Particle` constant on a future version would fail at class load; nothing to catch it. Test on 26.2 (plan step 9). |

### H. Admin side

| Item | Result | Where | Why |
|---|---|---|---|
| `/mythicconfig` | **BUG → fixed** | `SoulboundGemsPlugin.java:204` | Declared in plugin.yml but never registered: it only printed its usage. Now opens the tuner (`/mythicconfig [heretic\|auratus]`). |
| `/stalker` | RISK (decide) | plugin.yml, `ShadowStalkerListener.java:83` | Open to **every player** (`bliss.player`): anyone can start a 30 s red trail to anyone online, with no item, cooldown or cost. D9. |
| Console use | OK | all commands | Player-only commands say so; no unchecked casts. |
| Config keys read but missing from config.yml | **fixed** | `config.yml` | 19 keys (`energy.stage-cooldown-factor`, `mythics.relay.enabled`, all `prismatic-edge.*`, all `shadow-stalker.*`) worked on code defaults but couldn't be seen or tuned. They are now listed with the **same** values (no balance change) and copied into existing configs on start. A new test fails if a read key is ever missing again. |
| Config keys never read | NOT CHECKED (fully) | – | Many keys are read through prefix helpers (`cfgD("projection.seconds")` → `astra.projection.seconds`), so an automatic check gives false alarms. |

---

## 3. What I fixed

All in **Soulbound Gems**; Pantheon is unchanged.

| Fix | Files | Tested in code | Needs in-game check |
|---|---|---|---|
| L1 keepInventory dupe and lost held mythic: a MONITOR handler cancels the respawn copy when the inventory was kept, and puts a removed mythic back | `GemListener` | `AuditFixesTest.keepInventoryNeverGivesASecondGem` | yes (plan 1) |
| **Death energy loss never applied.** The respawn copy was taken *before* the −1, so dying cost nothing. The copy now loses it too (only when the gem was held, as before) | `GemListener` | `deathCostsEnergyOnTheGemThatComesBack` | yes (plan 2) |
| L2 containers: shift-click, number keys, offhand key, drag, bundles, item frames, allays, armour stands, pots; also crafters and furnaces. Our own menus are skipped (no false "can't be stored" message) | `GemListener` | `gemsStayOutOf…` | yes (plan 3) |
| L3 crafting, smithing, looms, trades, beacons, anvil material: no gem (mythics included) can be used up | `GemListener` | same test | yes (plan 3) |
| L4 Pockets saved to `pockets.yml` on every close and on disable | `GemListener` | `pocketsAreSaved` | yes (plan 4) |
| L5 Thunderstep stops before anything solid | `GemListener` | `thunderstepNeverEndsInAWall` | yes (plan 5) |
| Bloodsaw stands and Drift horse never saved; the horse drops nothing; saws removed on disable | `GemListener`, `MythicListener`, `SoulboundGemsPlugin` | `nothingLeftBehind` | – |
| Pristine stages: the held-gem gain now runs once a minute as designed (it ran every 10 ticks, so a stage every 5 s), and 0 is cleared on disk | `EnergyManager` | `pristineStagesTickOnceAMinute` | yes (plan 7) |
| Revenant killer name scrambled when invisible | `RevenantMace` | `invisibleKillerScrambled` | – |
| `/mythicconfig` registered with tab completion | `SoulboundGemsPlugin`, `plugin.yml` | `mythicConfigHasAnExecutor` | – |
| Experimental API call logs once instead of throwing | `GemManager` | `experimentalApiLogsOnce` | 26.2 |
| 19 config keys listed | `config.yml` | `everyReadKeyIsInTheConfig` | – |

Two older tests were adjusted, not weakened:
- **`ProphetTest`:** now checks the new `bound()` helper, which still exempts Auratus and Heretic.
- **`LifeTest`:** I reordered `shutdown()` so hearts are restored first, as that test requires.

---

## 4. What I need you to decide

| # | Question | My suggestion |
|---|---|---|
| D1 | **Gem powers during Pantheon events.** Block all of them, only teleports and movement, or nothing? | Block for event participants: teleports (Rewind, Rift, Thunderstep, Revenant moves), Astral Projection/Unbounded, the Gold Gem repurpose, and movement powers during Motion. Cleanest: Pantheon exposes `inEvent(Player)` and Soulbound asks it before a power (a soft link like the trust one). |
| D2 | **Land keep-out areas.** Should Crisp/Vortex stay out of Pantheon arenas, the spawn board, the ritual site and the pedestal? | Add `land.protected` boxes in config (or a radius around spawn), plus the ritual site and pedestal automatically. |
| D3 | **Deaths in Pantheon Command/Motion.** Should they cost gem energy, shatter the Gold Gem's sockets, shatter the Revenant, end bounties? | No: skip Soulbound death effects for event participants (needs D1's link). |
| D4 | Should the Finale/Revenant holder announcements and Pantheon's Teams feed scramble invisible players? | Leave the holder announcements real (they're a call to hunt); scramble the Teams feed. |
| D5 | Should kills during the Finale battle, capture fights or the Revenant boss give kill points? | Probably no for the Finale and the boss; yes for captures. |
| D6 | Should the Finale pick the **Solstice season leader** (Pantheon + kills) instead of the Pantheon-only leader? | Your call. The season winner is decided by Solstice now, so probably yes. |
| D7 | One-off action-bar messages are hidden by the gem HUD within half a second. Add a short "hold" (1.5 s) that the HUD respects, usable by Solstice, combat tag and rules? | Yes: a small public `holdActionBar(Player, ms)` on Soulbound, called by Pantheon the same way as the trust check. |
| D8 | **The Pristine-stage system** (EnergyManager) only sends chat messages ("your FLUX GEM is now at Pristine +3") that contradict the gem's real energy bar, and never changes cooldowns. Keep, hook into cooldowns, or remove? | Remove its chat messages, or remove it entirely. |
| D9 | `/stalker` is open to every player with no cost or cooldown. Admin-only, item-gated or a cooldown? | Admin-only (`bliss.admin`) until it has an item. |
| D10 | Death with two standard gems keeps only one. Keep all? | Yes, but only matters if admins hand out extra gems. |

---

## 5. Commands and permissions (for LuckPerms)

### Soulbound Gems

| Command | Permission | Default | Notes |
|---|---|---|---|
| `/bliss info\|withdraw\|track\|trust` | `bliss.player` | everyone | |
| `/bliss give\|setenergy\|giveitem\|menu\|config\|pedestal\|build` | `bliss.admin` | op | hidden from non-admins in tab completion |
| `/progression`, `/givegem`, `/blissop`, `/mythicconfig` | `bliss.admin` | op | |
| `/stalker <player>\|stop` | `bliss.player` | everyone | see D9 |
| `/turmoil ...` | `soulbound.turmoil` | op | `soulbound.turmoil` also exempts from the Finale "everyone at spawn" check |
| `/revenant ...` | `soulbound.revenant` | op | |
| `/capture ...` | `soulbound.capture` | op | |
| `/rules ...` | `soulbound.rules.admin` | op | |
| (see invisible players' real names) | `soulbound.rules.see-invisible` | op | |
| `/gems rename\|color\|names\|info` | `soulbound.admin.names` | op | |
| `/gem nickname` | none | everyone | colour codes need `soulbound.nickname.color` (op) |
| `/goldgem give\|remove\|ritual set` | `soulbound.gold.admin` | op | |
| `/goldgem mark\|unmark\|marks\|switch` | the Gold Gem holder | – | |
| `/goldgem socket\|unsocket\|awaken\|unawaken\|testrepurpose\|testsnap` | `soulbound.admin` | op | test commands |

### Pantheon

| Command | Permission | Default |
|---|---|---|
| `/pantheon board\|help`, `/pantheon duels join\|leave` (`/pt`) | none | everyone |
| every other `/pantheon ...`, `/pcommand ...` | `pantheon.admin` | op |
| `/standings [player]` | none | everyone |
| `/standings board set\|remove` | `pantheon.admin` | op |
| `/solstice tier\|points\|standings winner\|announce` | `pantheon.admin` | op |

---

## 6. In-game test plan (worst first)

1. **Dupe check (L1).** Start a Pantheon Motion run (or Command trial) with your gem in your inventory and die once inside it. After respawning you must have **exactly one** gem. Repeat while holding Auratus: it must still be yours (in hand), not gone.
2. **Death energy.** Outside events, hold your gem at energy 5 and die: you respawn with **4**. With the gem in your inventory (not held): energy is unchanged (as before).
3. **Containers and crafting (L2, L3).** With a chest, shulker, crafter and furnace open, try to get a gem in by: click, shift-click, number key, F key, drag. All are refused. Try a bundle, an item frame, an allay, an armour stand with arms and a decorated pot: all are refused. Put a gem in a crafting table with copper (spyglass), a smithing table (trim), a loom (Heretic as dye), a villager trade, a beacon and an anvil with a golden sword plus Auratus: no result, nothing used up. Renaming a gem in an anvil still works.
4. **Pockets (L4).** Put items in Wealth's Pockets, close, restart the server: they're still there. Also try with Pockets open during `/stop`.
5. **Thunderstep (L5).** Face a 1- and a 2-block wall within 3 blocks and use it: you stop in front. Look straight down and use it: you stay on the ground, not inside it.
6. **Astral safety.** Start Astral Projection and: log out; `/stop` the server; die (body hit); change worlds by portal; get teleported by `/tp`. Each time you end in survival at your body or the right spot.
7. **Pristine stages.** Kill a mob, then hold a gem: no "Pristine +N" chat message every few seconds (at most one a minute).
8. **Leftovers.** Throw Heretic Bloodsaws and ride Dimensional Drift, then `/stop` within 2 seconds. After restart, no invisible armour stand or tame horse is left there. Kill a Drift horse: no saddle drops.
9. **26.2.** Start the jar on 26.2 and watch the console for errors at enable and while holding the Relay.
10. **Solstice behind your proxy.** Two different players on different connections: a normal kill scores (if every kill says "same IP", see section 2C).
11. **`/mythicconfig auratus`** opens the tuner.
