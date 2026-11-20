# Gem sounds

Every sound a gem plays, from `config.yml` (`sounds.<gem>.<ability>`). Each ability has one sound and at most one quiet layer on the same tick. To change or mute one, edit its `event`, `volume`, `pitch` or `enabled` there and run `/reload` (or restart). `GemSoundsTest` checks the rules on every build:

- every event is a real 1.21.11 sound;
- pitch stays within 0.5 to 2.0;
- no Elder Guardian curse, Wither spawn, lightning thunder, portal travel or XP orb is used;
- no sound is shared between gems except these, which the spec lists:
  - `ITEM_MACE_SMASH_GROUND_HEAVY` (Strength, Heretic, Auratus)
  - `ITEM_TRIDENT_RETURN` (Speed, Auratus)
  - `ENTITY_WARDEN_SONIC_BOOM` (Flux, Auratus)
  - `ENTITY_ENDERMAN_TELEPORT` (Astra, Relay)
  - `BLOCK_BEACON_DEACTIVATE` (gem disable, Relay)

"You only" means only that player hears it; everyone else's sounds are heard by anyone nearby. Everything plays in the Players sound category.

**Pack sounds:** an `event` can also be a pack sound such as `blissgems:auratus.parry`. It then replaces the vanilla sound. Give it a `fallback: {event: ..., volume: ..., pitch: ...}` for when `sounds.custom-enabled` is false.

**Relay's custom sounds:** Rewind and Time Dilate play your `rewind.ogg` and `time_dilate.ogg` (`blissgems:relay.rewind` and `blissgems:relay.time_dilate`). When `sounds.custom-enabled` is false, they fall back to the vanilla sounds listed below.

| Gem | Ability | Event | Volume | Pitch | Heard by |
|---|---|---|---|---|---|
| All gems | not-ready | `BLOCK_NOTE_BLOCK_BASS` | 1.0 | 0.5 | you only |
| All gems | gem-disabled | `BLOCK_BEACON_DEACTIVATE` | 0.8 | 0.8 | you only |
| Strength | chad-strength | `ENTITY_RAVAGER_ROAR` | 0.9 | 1.2 | everyone near |
| Strength | chad-strength (+ layer) | `ITEM_MACE_SMASH_GROUND_HEAVY` | 1.0 | 0.6 | everyone near |
| Strength | chad-fourth-hit | `ITEM_MACE_SMASH_GROUND` | 1.0 | 1.2 | everyone near |
| Strength | nullify | `BLOCK_CONDUIT_DEACTIVATE` | 1.0 | 0.6 | everyone near |
| Strength | nullify (+ layer) | `ENTITY_ILLUSIONER_CAST_SPELL` | 0.7 | 0.8 | everyone near |
| Strength | bounty-hunter | `ENTITY_RAVAGER_AMBIENT` | 0.8 | 0.7 | you only |
| Fire | fireball-charge | `ENTITY_BLAZE_BURN` | 0.8 | 1.0 | everyone near |
| Fire | fireball-launch | `ENTITY_GHAST_SHOOT` | 0.9 | 0.8 | everyone near |
| Fire | fireball-hit (off by default) | `ENTITY_GENERIC_EXPLODE` | 0.6 | 1.2 | everyone near |
| Fire | cozy-campfire | `BLOCK_CAMPFIRE_CRACKLE` | 1.0 | 1.0 | everyone near |
| Fire | cozy-campfire (+ layer) | `ITEM_FIRECHARGE_USE` | 0.8 | 0.8 | everyone near |
| Fire | crisp | `ITEM_FIRECHARGE_USE` | 0.9 | 1.4 | everyone near |
| Fire | crisp (+ layer) | `BLOCK_FIRE_EXTINGUISH` | 0.8 | 1.0 | everyone near |
| Speed | blur | `ITEM_TRIDENT_RIPTIDE_1` | 1.0 | 1.6 | everyone near |
| Speed | speedy-storm | `ENTITY_LIGHTNING_BOLT_IMPACT` | 0.9 | 1.4 | everyone near |
| Speed | speedy-storm (+ layer) | `BLOCK_BEACON_POWER_SELECT` | 0.5 | 1.8 | everyone near |
| Speed | storm-bolt | `ENTITY_LIGHTNING_BOLT_IMPACT` | 0.5 | 1.8 | everyone near |
| Speed | thunderstep | `ENTITY_LIGHTNING_BOLT_IMPACT` | 0.5 | 2.0 | everyone near |
| Speed | thunderstep (+ layer) | `ITEM_TRIDENT_RETURN` | 0.9 | 1.8 | everyone near |
| Speed | speed-boost | `BLOCK_COPPER_BULB_TURN_ON` | 0.8 | 1.6 | everyone near |
| Puff | double-jump | `ENTITY_WIND_CHARGE_WIND_BURST` | 1.0 | 1.3 | everyone near |
| Puff | breezy-bash | `ENTITY_BREEZE_WIND_BURST` | 1.0 | 0.8 | everyone near |
| Puff | dash | `ENTITY_BREEZE_SHOOT` | 0.9 | 1.0 | everyone near |
| Puff | dash (+ layer) | `ENTITY_PHANTOM_FLAP` | 0.8 | 1.6 | everyone near |
| Astra | daggers | `ITEM_TRIDENT_THROW` | 0.9 | 1.6 | everyone near |
| Astra | daggers (+ layer) | `BLOCK_AMETHYST_BLOCK_HIT` | 1.0 | 1.5 | everyone near |
| Astra | projection | `BLOCK_RESPAWN_ANCHOR_CHARGE` | 1.0 | 1.4 | everyone near |
| Astra | dimensional-drift | `BLOCK_END_PORTAL_FRAME_FILL` | 1.0 | 1.2 | everyone near |
| Astra | dimensional-drift (+ layer) | `ENTITY_ENDERMAN_TELEPORT` | 0.7 | 1.5 | everyone near |
| Astra | astral-void | `BLOCK_AMETHYST_BLOCK_HIT` | 1.0 | 0.6 | everyone near |
| Flux | energy-beam | `ENTITY_GUARDIAN_ATTACK` | 0.9 | 1.4 | everyone near |
| Flux | kinetic-burst | `ENTITY_WARDEN_SONIC_BOOM` | 0.5 | 1.6 | everyone near |
| Flux | overdrive | `BLOCK_BEACON_ACTIVATE` | 0.9 | 1.0 | everyone near |
| Flux | overdrive (+ layer) | `BLOCK_CONDUIT_ACTIVATE` | 0.8 | 1.0 | everyone near |
| Flux | ingot-to-watts | `BLOCK_CONDUIT_AMBIENT_SHORT` | 0.8 | 1.6 | you only |
| Wealth | pockets | `BLOCK_ENDER_CHEST_OPEN` | 1.0 | 1.3 | everyone near |
| Wealth | item-lock | `BLOCK_CHEST_LOCKED` | 1.0 | 1.0 | everyone near |
| Wealth | item-lock (+ layer) | `BLOCK_IRON_TRAPDOOR_CLOSE` | 0.8 | 0.7 | everyone near |
| Wealth | rich-rush | `BLOCK_VAULT_INSERT_ITEM` | 1.0 | 1.0 | everyone near |
| Wealth | rich-rush (+ layer) | `BLOCK_CHAIN_STEP` | 0.6 | 2.0 | everyone near |
| Wealth | amplification | `BLOCK_ENCHANTMENT_TABLE_USE` | 1.0 | 1.2 | everyone near |
| Life | heart-drainer | `BLOCK_AZALEA_LEAVES_BREAK` | 0.9 | 1.0 | everyone near |
| Life | heart-drainer (+ layer) | `ITEM_BONE_MEAL_USE` | 0.9 | 1.2 | everyone near |
| Life | circle-of-life | `ITEM_BONE_MEAL_USE` | 0.9 | 1.0 | everyone near |
| Life | circle-of-life (+ layer) | `BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES` | 0.7 | 1.3 | everyone near |
| Life | vitality-vortex | `ENTITY_EVOKER_CAST_SPELL` | 0.9 | 1.4 | everyone near |
| Life | vitality-vortex (+ layer) | `BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE` | 0.7 | 1.0 | everyone near |
| Life | siphon | `ITEM_BONE_MEAL_USE` | 0.9 | 0.8 | everyone near |
| Heretic | bloodsaws | `BLOCK_GRINDSTONE_USE` | 1.0 | 1.4 | everyone near |
| Heretic | bloodsaws (+ layer) | `ENTITY_PLAYER_ATTACK_SWEEP` | 0.9 | 0.6 | everyone near |
| Heretic | bloodsaw-hit | `ENTITY_PLAYER_ATTACK_SWEEP` | 0.8 | 0.6 | everyone near |
| Heretic | spore | `BLOCK_SPORE_BLOSSOM_BREAK` | 1.0 | 1.0 | everyone near |
| Heretic | spore (+ layer) | `BLOCK_SCULK_SPREAD` | 1.0 | 1.0 | everyone near |
| Heretic | bloodlinking | `ENTITY_WARDEN_HEARTBEAT` | 1.2 | 1.0 | everyone near |
| Heretic | bloodlink-slam | `ITEM_MACE_SMASH_GROUND_HEAVY` | 1.0 | 0.9 | everyone near |
| Auratus | chain-throw | `BLOCK_CHAIN_PLACE` | 0.8 | 1.2 | everyone near |
| Auratus | chain-hook-enemy | `BLOCK_CHAIN_PLACE` | 1.3 | 0.7 | everyone near |
| Auratus | fly-to-hook | `BLOCK_CHAIN_HIT` | 0.8 | 1.4 | everyone near |
| Auratus | reel-arrive | `ITEM_ARMOR_EQUIP_CHAIN` | 0.7 | 1.3 | everyone near |
| Auratus | tug | `BLOCK_CHAIN_BREAK` | 1.0 | 0.9 | everyone near |
| Auratus | haul | `BLOCK_CHAIN_HIT` | 1.2 | 0.6 | everyone near |
| Auratus | hook-slides-out | `BLOCK_CHAIN_FALL` | 0.6 | 1.0 | everyone near |
| Auratus | snap | `BLOCK_CHAIN_BREAK` | 1.0 | 1.3 | everyone near |
| Auratus | not-ready | `BLOCK_CHAIN_FALL` | 0.5 | 1.6 | you only |
| Auratus | aegis | `ITEM_TRIDENT_RETURN` | 1.0 | 1.5 | everyone near |
| Auratus | aegis (+ layer) | `BLOCK_AMETHYST_BLOCK_RESONATE` | 1.0 | 1.3 | everyone near |
| Auratus | aegis-strike | `BLOCK_BELL_USE` | 0.9 | 1.2 | everyone near |
| Auratus | aegis-catch | `BLOCK_BELL_USE` | 1.0 | 0.7 | everyone near |
| Auratus | aegis-catch (+ layer) | `BLOCK_CHAIN_PLACE` | 0.8 | 0.6 | everyone near |
| Auratus | aegis-fizzle | `BLOCK_CHAIN_FALL` | 0.4 | 0.8 | you only |
| Auratus | parry | `ITEM_SHIELD_BLOCK` | 1.0 | 1.3 | everyone near |
| Auratus | parry (+ layer) | `BLOCK_BELL_USE` | 1.0 | 1.6 | everyone near |
| Auratus | slam-start | `BLOCK_CHAIN_PLACE` | 1.0 | 0.5 | everyone near |
| Auratus | gilded-slam | `ITEM_MACE_SMASH_GROUND_HEAVY` | 1.0 | 0.8 | everyone near |
| Auratus | gilded-slam (+ layer) | `ENTITY_FIREWORK_ROCKET_LARGE_BLAST` | 0.8 | 1.0 | everyone near |
| Auratus | flagpole | `BLOCK_CHAIN_PLACE` | 1.2 | 0.6 | everyone near |
| Auratus | flagpole (+ layer) | `BLOCK_BELL_USE` | 0.6 | 0.8 | everyone near |
| Auratus | flagpole-release | `BLOCK_CHAIN_BREAK` | 0.8 | 0.9 | everyone near |
| Auratus | launch | `ENTITY_FIREWORK_ROCKET_LARGE_BLAST` | 1.0 | 1.0 | everyone near |
| Auratus | launch (+ layer) | `ENTITY_WARDEN_SONIC_BOOM` | 0.3 | 2.0 | everyone near |
| Auratus | shield-jam | `ITEM_SHIELD_BREAK` | 0.8 | 1.2 | everyone near |
| Relay | rewind | `blissgems:relay.rewind` | 1.0 | 1.0 | everyone near |
| Relay | rewind (fallback, pack sounds off) | `BLOCK_BEACON_DEACTIVATE` | 0.8 | 1.4 | everyone near |
| Relay | time-dilate | `blissgems:relay.time_dilate` | 1.0 | 1.0 | everyone near |
| Relay | time-dilate (fallback, pack sounds off) | `BLOCK_BEACON_AMBIENT` | 0.9 | 0.5 | everyone near |
| Relay | divinity | `BLOCK_END_PORTAL_SPAWN` | 0.4 | 0.7 | everyone near |
| Relay | divinity (+ layer) | `BLOCK_BELL_RESONATE` | 0.8 | 0.6 | everyone near |
| Relay | rift-menu | `BLOCK_BELL_RESONATE` | 0.4 | 1.5 | you only |
| Relay | rift-set-anchor | `BLOCK_RESPAWN_ANCHOR_SET_SPAWN` | 0.8 | 0.8 | you only |
| Relay | rift-channel | `BLOCK_PORTAL_TRIGGER` | 0.4 | 1.6 | everyone near |
| Relay | rift-close | `BLOCK_GLASS_BREAK` | 0.5 | 0.55 | everyone near |
| Relay | rift-arrival | `ENTITY_ENDERMAN_TELEPORT` | 0.9 | 0.8 | everyone near |
| Relay | rift-arrival (+ layer) | `BLOCK_RESPAWN_ANCHOR_DEPLETE` | 0.8 | 1.0 | everyone near |
| Relay | tuned-tag | `BLOCK_SCULK_SENSOR_CLICKING` | 1.0 | 1.6 | you only |
| Relay | tuned-full | `BLOCK_AMETHYST_BLOCK_CHIME` | 1.0 | 1.0 | you only |
| Relay | tuned-full (+ layer) | `BLOCK_NOTE_BLOCK_BIT` | 0.6 | 2.0 | you only |
| Not a gem | pedestal-activate | `BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE` | 0.8 | 1.0 | everyone near |

## Not in this table

- **The fireball explosion:** the game's own boom. It can't be turned down, so `fire.fireball-hit` is off to avoid a second boom.
- **The gold slam firework:** it makes its own large blast. While `mythics.auratus.slam.firework` is on, `gilded-slam`'s layer is skipped so it isn't doubled.
- **The Revenant:** a boss weapon, not a gem, so its sounds were left as they were.
- **Other items:** the upgrader, trader and repair-kit items, the Prismatic Edge weapon, the energy level-up, the menus, the turmoil and the finale (which keeps its lightning).
