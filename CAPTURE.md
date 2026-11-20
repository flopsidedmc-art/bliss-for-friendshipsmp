# Capture points

Capture points are how players win the two mythics. Week 1's capture awards the Heretic and week 2's awards the Auratus. Mythics already drop on death, so the winners become targets afterwards.

A capture point is a box. While a capture runs, standing in it fills your own timer, and the first player to fill it wins.

## Commands

`/capture` needs `soulbound.capture` (ops by default). Every argument tab-completes.

| Command | What it does |
|---|---|
| `/capture create <name> <x1> <y1> <z1> <x2> <y2> <z2> [color]` | Makes a point. Coordinates work like `/fill`: whole numbers, or `~`, `~5`, `~-3` relative to you. Tab completion offers the block you're looking at. The corners can be in any order and both are included. |
| `/capture color <name> <color>` | A hex code (`#B0703A`) or a chat colour name (`gold`, `aqua`, `light_purple` ...). It changes live, even mid-capture. |
| `/capture start <name> [hold-seconds] [reward] [force]` | Starts a capture. `hold-seconds` is one player's total time needed to win (default 120). `reward` is `heretic`, `auratus`, `relay` or `none` (default none). |
| `/capture stop <name>` | Ends it with no winner. |
| `/capture list` | Every point and whether it's running. |
| `/capture info <name>` | Its box, colour and, while running, the top five times. |
| `/capture remove <name>` | Deletes it (stopping it first if it's running). |
| `/capture tp <name>` | Takes you to the middle of its floor. |

Some limits:
- A box can be at most 64 × 32 × 64 blocks (`capture.max-size`).
- A name must be new, and can use letters, digits, `-` and `_`.
- The console measures `~` from the main world's spawn.

If the reward mythic already exists, `start` refuses and says where it is. Add `force` at the end to start anyway. It checks:
- online players' inventories and ender chests;
- dropped items in loaded chunks.

It can't see offline players' inventories.

Points are saved in `plugins/SoulboundGems/capture-points.yml` and survive restarts. A capture that was running when the server stopped is not resumed. It's stopped with no winner, the console logs it, and the next admin to join is told.

## Rules

- **On the point:** you're standing in the box, alive, in survival or adventure.
- **Can't capture:**
  - spectators;
  - vanished players (any vanish plugin that sets the standard `vanished` flag);
  - the Revenant holder.
- **Alone on the point:** your timer counts up.
- **Contested:** two or more players are on the point and at least two of them aren't trusted with each other. Nobody's timer moves.
- **Trusted players are one side:** two players count as trusted if either has trusted the other (`/bliss trust`), the same rule the gems use. All of them gain time.
- **Leaving or dying keeps your time.** You just stop gaining it.
- **The win:** the first to reach the hold time wins. If several reach it on the same update, the one with the most time wins.
  - The winner gets the reward (dropped at their feet if their inventory is full).
  - Everyone gets a title and a chat message.
  - The point's visuals burst, then turn off.
- **Rifts:** you can't set a Rift anchor in a running point, travel to an anchor inside one, or open a rift while standing in one.

## What players see

- **Boss bar:** everyone within 64 blocks sees `<point>: <leader> <time>/<hold>`, or `<point>: CONTESTED`. The bar fills with the leader's progress.
  - The text is in the point's colour.
  - The bar itself uses the nearest of the game's seven bar colours (gold and brown show as yellow).
- **Action bar:** players on the point see their own time (`⚑ 42/120`).
  - If you're holding a gem or the Revenant, it's added to the end of that HUD instead of replacing it.
- **Sounds** (`sounds.capture` in config.yml):
  - a soft beacon click when you step on;
  - a quiet bass note at most every 2 s while contested;
  - the challenge-complete toast for the winner.
- **Glowing floor:**
  - Flat coloured text displays lie 0.02 above the ground, tiled over the box at full brightness. There are at most 32 tiles, bigger on bigger boxes.
  - Each tile sits on the highest ground under it.
  - The floor breathes slowly between 35% and 55% opacity over 3 s. The client does the fading (display interpolation), so the server sends nothing per tick.
  - It glows only while a capture runs, unless `capture.glow-when-idle` is true.
- **Bands of light:** while a capture runs, a thin band (0.15 × 1.5 blocks, always facing you) appears at a random spot on the edges every 0.4 s. It rises 4 blocks over 2 s while fading out, then it's removed. There are at most 24 per point.
- **Dust:** `capture.particles: true` adds coloured dust along the edges, for players who can't see displays well.

Every display is removed when a capture stops, is won or is removed, when its chunk unloads, and when the plugin is disabled or the server stops. The displays are never saved to the world.

## Settings (`capture:` in config.yml)

| Key | Default | What it does |
|---|---|---|
| `hold-seconds` | 120 | default hold time for `/capture start` |
| `max-size` | `{x: 64, y: 32, z: 64}` | biggest box `/capture create` accepts |
| `bossbar-radius` | 64 | who sees the boss bar |
| `glow-when-idle` | false | the floor glows whenever the point exists |
| `particles` | false | coloured dust along the edges |
| `floor.alpha-min`, `floor.alpha-max` | 0.35, 0.55 | the floor's opacity range |
| `floor.pulse-seconds` | 3.0 | one breath, dim to bright and back |
| `floor.max-displays` | 32 | floor tiles per point |
| `bands.interval-seconds` | 0.4 | how often a band appears |
| `bands.height` | 4.0 | how far it rises |
| `bands.lifetime-seconds` | 2.0 | how long it takes to rise and fade |
| `bands.max` | 24 | bands alive at once per point |
| `bands.width`, `bands.length`, `bands.alpha` | 0.15, 1.5, 0.7 | the band's size and starting opacity |

## In-game test plan

1. `/bliss menu` and `/bliss config`: every gem, mythic and action button works, Back works, and the 3- and 4-setting pages are centred.
2. `/capture create test ~-5 ~-1 ~-5 ~5 ~3 ~5 gold`, then `/capture color test #B0703A`. The floor doesn't glow yet, because it only glows while running (or with `glow-when-idle: true`). Start the point (step 3) and change the colour while it runs: the floor changes colour.
3. `/capture start test 30 heretic`. Bands rise from the edges and fade, and the boss bar shows. If the Heretic already exists you're told where; add `force`.
4. One player holds it: their time goes up. Two untrusted players: CONTESTED, and nobody gains. Two trusted players: both gain.
5. Leave and come back: your time is kept. Die on the point: your time is kept.
6. The first to 30 s wins the Heretic: a burst, a title and a message. Every display is gone afterwards (`/kill @e[type=text_display,distance=..20]` kills nothing).
7. Try to set a Rift anchor inside a running point: refused.
8. Restart the server while a capture runs: the point still exists, and the capture was stopped cleanly (console warning, and a message to the first admin who joins).
9. Three players on the point for 2 minutes: watch `/tps` and the console.
