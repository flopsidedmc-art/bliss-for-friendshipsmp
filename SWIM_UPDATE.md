# Temporal Divinity — air-swimming movement rework

Movement-only patch to the existing 1.3.0-temporal implementation (Paper 1.21.11, Java 21). The plugin was not rewritten: one new 100-line class, edits to `TemporalDomain.java`, config defaults and docs. Nothing else in the plugin's main source changed.

**Status: compiled and unit-tested, NOT played.** No Minecraft server or client was run. See "What was and was not tested" before trusting the feel.

## What changed

| Area | Before | Now |
|---|---|---|
| Caster movement | Native creative-style flight (`setFlying(true)` + fly speed) | Air-swimming: gravity off, a per-tick controller (`AirSwim`) steers the caster from their real key input |
| Opponent movement | Gravity off, Y velocity pinned to the drift each tick, sideways velocity left as-is | Same suspension, plus sideways momentum is damped every tick and flight/glide/gravity flags are re-asserted every tick |
| Elytra | Any captured player could start gliding and leave the boundary, ending the session | Gliding is cancelled for everyone in a domain |
| Flight toggle | Caster could toggle native flight | Flight toggle is cancelled for everyone in a domain |
| Caster vs boundary | Flying past `boundary-radius` ended the caster's own domain | Soft wall: outward speed fades over the last 15% of the radius, so the caster stops inside it |
| Default duration | 5 seconds | 8 seconds (as specified in the brief) |
| Drift lower bound | 0.005 blocks/tick | 0.035 while `allow-flight=false` in server.properties, else 0.005 (see trade-offs) |

Unchanged: target selection (every eligible untrusted survival/adventure player within `capture-radius`, nearest first, up to `max-opponents`), all exclusions, the void world, no floor/platform, the recovery journal and its file format, quit/death/respawn/shutdown/restart/failed-teleport handling, fall-damage protection, damage rules, cooldowns, charge, HUD, commands, item controls, every other gem, and every resource-pack asset.

## How the caster swims

| Input | Result |
|---|---|
| W / S | Swim toward / away from where you are **looking**, including up and down |
| A / D | Strafe left / right (horizontal) |
| Jump | Rise |
| Sneak | Dive. Ignored until sneak has been released once, because Divinity is cast with sneak held |
| Sprint (key or double-tap W) | Forward surge, 1.5x |
| No keys | Glide to a complete stop in about a second (roughly 2.3 blocks from full speed) |

Why this reads as swimming rather than creative flight: thrust follows the full look direction instead of staying flat, speed eases in over about a second instead of snapping, turns curve because momentum is kept, releasing the keys glides rather than brakes, and a gentle 0.8-second stroke rhythm varies speed by ±10% while actively swimming.

Default top speed is 7.6 blocks/s (11.4 sprinting). A captured opponent can creep at most about 0.5 blocks/s sideways and cannot rise at all.

## How it works

- `AirSwim.java` (new) is pure maths with no Bukkit entity access. Each domain session owns one instance, created for the caster only. It keeps its own velocity state rather than reading the server's player velocity, which is not a faithful simulation for players.
- `TemporalDomain.tick()` reads the caster's keys with `Player#getCurrentInput()` (real Paper API, present in the 1.21.11 dependency; no packet library and no new runtime dependency), calls `AirSwim.step(...)` and sends the result with `Player#setVelocity`. No teleports are used for movement.
- The caster has gravity off and `allowFlight` on but `flying` off. `allowFlight` is there only so vanilla's anti-hover kick cannot fire; actually starting flight is cancelled.
- Opponents never get a controller and their input is never read. Each tick their velocity is replaced with: sideways = half of what it was, vertical = minus the drift.
- Every property touched (allowFlight, flying, gravity, collidable, hurt window, attack-speed modifier, slow modifiers) is restored by the existing `restoreFlags` from the existing journaled snapshot. The rework modifies no property that was not already saved and restored.

## New and changed settings

All under `mythics.relay.temporal.divinity`. Existing live configs get the new keys merged on startup; **an existing `duration-seconds: 5` in a live config is not overwritten**, so set it to 8 by hand if you want the new default.

| Key | Default | Meaning |
|---|---|---|
| `swim-speed` | 0.38 | Caster top speed, blocks/tick (0.05–1.0) |
| `swim-sprint-multiplier` | 1.5 | Forward sprint (1–2) |
| `swim-acceleration` | 0.14 | Share of the gap to the wanted velocity closed per tick (0.02–1). Lower is heavier |
| `swim-drag` | 0.86 | Speed kept per tick with no input (0–0.98). Lower stops sooner |
| `swim-stroke` | 0.10 | Stroke surge (0–0.4). 0 gives perfectly even speed |
| `enemy-horizontal-damping` | 0.5 | Share of an opponent's sideways momentum removed per tick (0–1) |
| `duration-seconds` | 8 (was 5) | |
| `flight-speed` | removed | No longer read. A leftover key in an old config is harmless |

## Testing alone

Divinity normally refuses to open without an opponent in range. To try the swimming on your own, set these under `mythics.relay.temporal` in the plugin's `config.yml`, then restart the server or run `/blissop reloadconfig`:

| Key | Test value | Normal value |
|---|---|---|
| `divinity.allow-solo` | `true` | `false` |
| `divinity.required-charge` | `1` | `100` |
| `charge.allow-mobs` | `true` | `false` |

Give yourself the gem with `/blissop mythic relay`, hold it in your main hand, punch any mob once to gain charge, then sneak + right-click. Set the three values back before real play. A solo domain has nobody to capture, so it tests the caster side only.

## Trade-offs and known limits

- **Input lag equals ping.** The caster's movement is server-driven, so each key press takes one round trip to take effect. This is inherent to swimming without native flight on a vanilla client. It should feel fine at normal pings and floaty at high ping; raising `swim-acceleration` makes it snappier.
- **No swimming animation.** A vanilla client decides every player's body pose itself from flags it re-checks each tick (in water or not), so a plugin cannot make a player in open air render in the swim pose through the Bukkit API. A forced-pose option was written and removed during this work for that reason: it would only have shrunk the caster's server-side hitbox. The swimming impression comes from the motion.
- **Opponents can still creep.** Vanilla air control lets a client add about 0.02 blocks/tick of its own between velocity updates. That is at most roughly 4 blocks over an 8-second domain, sideways only. Removing it entirely would need the opponents mounted on an entity or packet-level control.
- **Caster ignores knockback while swimming.** The controller overwrites velocity every tick, so hits and other abilities' launches do not move the caster inside the domain.
- **Puff double jump.** The existing Puff handler still fires if a Puff-holding caster double-taps jump in a domain: its cooldown is spent and the launch is immediately overridden. `GemListener` was deliberately left untouched; say if you want a one-line guard added there.
- **Drift floor.** With `allow-flight=false`, vanilla kicks a non-flying player who stays level for 80 ticks. The drift is therefore held at 0.035 blocks/tick or more on such servers. The default 0.06 is unaffected.
- **Boundary and config sanity.** `boundary-radius` is now clamped to 8–64 in the end-of-session check as well as in the time-scale region (it was previously clamped in only one of the two).

## What was and was not tested

| Kind | Result |
|---|---|
| Code inspection | Done for `TemporalDomain`, `TemporalFlow`, `RelayGem`, plugin wiring, config, and every other flight/gravity/glide call in the plugin (`GemListener` Puff and Astral Projection) |
| Compilation | `mvn clean package` succeeds against the real `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` on Java 21. Log: `SWIM_BUILD.log` |
| Automated tests | 9 JUnit tests of the `AirSwim` maths pass (acceleration curve, full stop, 3D direction, sneak-from-cast, sprint, turning, stroke bounds, soft wall from 40 headings, clamping/NaN). They assume the client follows each velocity exactly |
| Static checks | See `SWIM_VALIDATION.txt` |
| **Live gameplay** | **None.** No server was started and no client connected |

Not verified in a running game, in rough order of risk:

1. How the swimming actually feels on a real client, at low and high ping, and whether the stroke rhythm reads well or should be turned down.
2. That opponents stay suspended and sink smoothly with no visible jitter from the per-tick velocity updates.
3. That nobody is kicked for flying on a server with `allow-flight=false` across a full 8-second domain.
4. Return to the original location with gravity/flight restored, for caster and opponents, including quit, death, respawn, shutdown, restart recovery and cancelled teleports. This code path is unchanged apart from the caster now also having gravity restored, but it has still never been run.
5. Elytra, flight-toggle and Astral Projection attempts inside a domain.
6. Two domains open at once.
7. Interaction with anti-cheat or movement plugins, which commonly flag sustained velocity-driven air movement.
8. Everything already listed as unrun in `CHANGELOG.md`.

## Install

Replace the old JAR with `built-plugin/soulbound-gems-1.3.0-temporal.jar` (same file name and version string as before; check the hash in `SHA256SUMS.txt`). The resource pack is unchanged and does not need redistributing.
