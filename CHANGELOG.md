# Item provenance + Bounty Hunter rework

## What I found first
- **ItemProvenanceListener (before):**
  - **No owners:** it wrote a lore line, "STOLEN by X" (red), "GIFTED by X" (yellow) or "STORED by X" (blue), and kept only a name and a kind in the PDC. There was no owner UUID, so a renamed player broke the link, and bounties matched names in the lore text.
  - **"STOLEN by X" named the victim,** so "by" really meant "from".
  - **Lines never cleared:** an item given back to its owner kept its STOLEN or GIFTED line forever.
  - **Container takes:** taking an item out became STOLEN only if a "STORED by" line was on it.
  - **Missing item types:** it tracked swords, axes, pickaxes, shovels, hoes, armour, bows, crossbows, tridents, maces, shields and elytra, but not shears, fishing rods, flint and steel or brushes.
  - **Not excluded:** the plugin's own items (the Revenant, the Prismatic Edge, the finale kit) could pick up lines.
  - **A real bug:** it treated *any* hopper-type inventory as a container, the bounty offering included.
    - **What happened:** putting a stolen item into the offering rewrote its line to "STORED by <you>". The offering then read your own name and refused it.
    - **Result:** the provenance half of the old offering could never work; only the target's player head did.
- **The old Bounty Hunter:**
  - **What it already had:** activation was already Strength in the off hand + right-click. That opened a 54-slot "Bounty Board" of heads (no pages, so players past 54 were cut off). A head opened a hopper ("Offer for <name>").
  - **The offering:** on close it was valid if it held the target's head or an item whose lore line named them. Nothing was used up; every item came back. The 35 s cooldown started then.
  - **The tracking:** it then lasted forever:
    - the compass pointed at them;
    - a CRIT particle line toward them, which everyone nearby could see;
    - the target was told "You feel like you are being watched..." and got "Your hunter is nearby" / "Someone is watching you…" on their action bar.
  - **`/bliss track <player>`** set a bounty for free, with no offering.
  - **A player head in the off hand** stopped Strength's main-hand right-click (Chad Strength).
- **SmallCaps:**
  - `of()` writes small caps.
  - `isHistoryLine` keeps the old history lines out of small caps, and gem lore carries them over when it's rebuilt.
- **Strength config:**
  - `cooldowns.bounty: 35` (fallback `cooldowns-seconds.tier2-ability`), the `strength.bounty-hunter` sound and the HUD icon.
  - There was no `bounty:` section.
- **Strength's other powers** (Nullify, Chad Strength, Frailer, Bloodthorns, the passive) are untouched.

## What changed
### Item provenance (`provenance:`)
- **Tracked:**
  - all armour (elytra, shields and the turtle shell too);
  - swords, spears (all seven, wooden to netherite and copper), axes, bows, crossbows, tridents and maces;
  - pickaxes, shovels, hoes, shears, fishing rods, flint and steel and brushes.
- **Never tracked:** gems, mythics, the Revenant's mace, the Prismatic Edge and the finale kit.
- **One owner, who never changes,** and one status, kept in the item's PDC: `prov_owner` (UUID), `prov_name` (their name at the time) and `prov_type` (the status). The old keys are reused, so nothing new is needed on disk.
- **The rule** (applied whenever an item reaches a player):
  - **Nobody owns it yet:** it becomes theirs, ORIGINAL. That covers crafted, bought, looted, fished, mob drops, and any item from before this update.
  - **The owner gets it:** ORIGINAL again.
  - **The owner dropped it alive (Q, or dragged out of the inventory)** and someone else picks it up: GIFTED.
  - **Anyone else gets the owner's ORIGINAL item** any other way (their death drop, a chest, a frame, an armour stand, a pot): STOLEN.
  - **Passed on again:** it keeps its status and owner (stolen from Moon stays stolen from Moon).
- **How it's applied:** at pickups, and to everything in your inventory after clicks, closing a container, joining, armour stands, frames and pots. So no single way of taking an item can be missed.
- **STORED** is only the display while an item sits in a real container: a chest, barrel, shulker box, ender chest, hopper, dispenser, dropper, pot, frame or armour stand. It's set when you open or use one, or when a hopper picks it up. Plugin menus never count.
- **Keeping provenance through changes:**
  - **Anvils, smithing (diamond to netherite), enchanting and grindstones** keep it: they keep the item's data.
  - **A crafting-grid repair** (two worn tools), dyed leather and a shield with a banner keep the first item's provenance.
  - **Hoppers and droppers** moving items change nothing.
- **The line:** one small-caps line at the bottom of the lore, no italics, replaced (never doubled) whenever the status changes.
  - **ORIGINAL:** "ᴏʀɪɢɪɴᴀʟ" (#D8C3A0) "·" Moon
  - **STOLEN:** "ꜱᴛᴏʟᴇɴ" (#E04848) "ꜰʀᴏᴍ" Moon
  - **GIFTED:** "ɢɪꜰᴛᴇᴅ" (#FFC846) "ʙʏ" Moon
  - **STORED:** "ꜱᴛᴏʀᴇᴅ" (#8FD3FF) "·" Moon
  - **Colours:** the joining word is gray #9A9A9A and the name white.
  - **Config:** words, joiners and colours are all under `provenance:`; `show-line: false` keeps tracking without the line; `enabled: false` turns it all off.
  - **The owner's name** is written as it's spelled (`name-small-caps: true` to change that).
- **Old items:** their "STOLEN by X" lines are removed, and they become ORIGINAL for the next player who holds them.

### Bounty Hunter (`bounty:`)
- **The board:** Strength in your off hand + right-click (off cooldown) opens a 54-slot "Bounty Board".
  - **The heads:** every online player except you, sorted by name. Hover shows their name.
  - **Pages:** with more than 45, arrows on the bottom row and "Page n/m".
  - **Another dimension** shows "ɪɴ ᴀɴᴏᴛʜᴇʀ ʀᴇᴀʟᴍ".
  - **An invisible player** is a faceless head with a scrambled name (rule 16), except for see-invisible admins.
- **The offering:** clicking a head opens a 5-slot hopper, "Offer to the hunt: <name>".
  - **What counts:** items stolen from or gifted by that player, by owner UUID. ORIGINAL items and items linked to anyone else don't count.
  - **On close:** each counting item is used up for 1 minute (`seconds-per-item` 60), up to `max-seconds` (300) left at once. Only the items needed are used; the rest come back, at your feet if your inventory is full.
  - **Messages:** "The hunt begins: 3 minutes." Offering again on the same target adds time: "The hunt grows: 4 minutes left."
  - **Nothing counted:** nothing happens and there's no cooldown, with a line saying why.
  - **The 35 s cooldown** starts when a hunt begins, with the existing sound.
  - **Closing it any other way** (logout, death, teleport, another menu, the plugin stopping) gives everything back. On death the items drop with yours, or stay with keep-inventory.
- **The hunt:**
  - **The thread:** only the hunter sees two thin intertwined DUST strands, #111111 and #8B0000, refreshed every 5 ticks.
    - **Where it runs:** from 1.5 blocks ahead of you toward the target, 14 blocks long (`trail-length`), curving and swaying slightly like a thread. It ends at the target if they're closer.
  - **In another dimension:** no thread, and "ᴛʜᴇʏ ᴡᴀʟᴋ ᴀɴᴏᴛʜᴇʀ ʀᴇᴀʟᴍ" while the timer keeps running.
  - **The time left** is on the action bar: at the end of the gem, mythic or Revenant bar, or on its own otherwise.
  - **When it ends:**
    - when the time runs out ("ᴛʜᴇ ʜᴜɴᴛ ɪꜱ ᴏᴠᴇʀ");
    - when the target logs out (the time is lost) or dies;
    - when the hunter logs out.
  - **The target isn't told** (`notify-target: false`); `true` sends them "ʏᴏᴜ ꜰᴇᴇʟ ᴡᴀᴛᴄʜᴇᴅ".
  - **Removed:** the compass, the CRIT line, and the target's messages.
- **Other changes:**
  - **`/bliss track <player>`** opens the same offering (on the same cooldown) instead of a free bounty.
  - **`/bliss info`** says who you're hunting.
  - **The nearest-player hint** you get holding Strength is unchanged, and is skipped while you're hunting.
  - **Chad Strength** now also works with a player head in your off hand.
- **Cleanup:** a hunt is held only by player ids, and logouts, deaths and the plugin stopping end everything. Nothing lingers.

## Files
- **New:**
  - `ProvenanceMath.java`: the statuses, the rule, what counts for a bounty, the line text;
  - `BountyMath.java`: the offering, the pages, the thread;
  - `BountyHunt.java`: the board, the offering, the hunts, the thread, the action bar, cleanup;
  - `ProvenanceTest.java` and `BountyTest.java`.
- **Changed:**
  - `ItemProvenanceListener.java`: rewritten on the same keys (owners, the rule, STORED, the new line, exclusions, more item types);
  - `GemListener.java`: the old board, offering, compass, line and head checks are removed; it routes to BountyHunt, starts the cooldown and adds the hunt to the gem bar; the quit handler is renamed `onLeave`;
  - `MythicListener.java` and `RevenantMace.java`: the hunt's time on their action bars;
  - `BlissCommand.java`: `/bliss track` and `/bliss info`;
  - `SoulboundGemsPlugin.java`: wiring and shutdown;
  - `SmallCaps.java`: a comment;
  - `config.yml`: `provenance:` and `bounty:`;
  - `LifeTest.java`: follows the renamed quit handler.

## Tested
- Compiles against paper-api 1.21.11, and all 211 unit tests pass (24 new). They cover:
  - **What's tracked:** the full list, nothing else (not horse or wolf armour, totems, heads...), and the game items excluded.
  - **Status changes:**
    - crafted is ORIGINAL;
    - the owner's death drop is STOLEN;
    - a Q drop picked up by a friend is GIFTED;
    - someone else taking it out of a chest is STOLEN;
    - back to the owner (stolen or gifted) is ORIGINAL with the original name;
    - passed on keeps STOLEN or GIFTED and the owner;
    - items from before become the next holder's.
  - **The offering:**
    - only that player's stolen or gifted items count;
    - a minute each, at most 5 minutes;
    - adding time uses only what's needed;
    - at the limit, everything comes back.
  - **The lore line:**
    - the small-caps text for all four statuses;
    - found again so it's never doubled, with other lore left alone;
    - the three colours, no italics, and the config defaults.
  - **The thread:** it starts 1.5 blocks ahead and is about 14 blocks long, the two strands wind round each other, a close target is where it ends, and the bend is slight.
  - **Pages of 45.**
  - **The wiring:** the new board, offering, messages, particles and cleanup; the old head-and-compass version is gone.

## Not tested
- **Nothing was run in game:**
  - the board, its pages and heads;
  - the offering hopper and its close reasons;
  - how the thread looks;
  - the action bars;
  - the real pickup, container, frame, stand and pot events;
  - smithing, anvil and grindstone results keeping the line;
  - how the line looks in the inventory.
- **Smithing, anvils and grindstones** keeping provenance relies on the game copying the item's data (it does in 1.21). It isn't seen running here.

## In-game test plan
1. **Craft a sword:** "ᴏʀɪɢɪɴᴀʟ" in beige, "·" in gray, your name in white.
2. **Containers:** put it in your chest: it shows "ꜱᴛᴏʀᴇᴅ" in light blue. Take it out yourself: original again. A friend takes it: "ꜱᴛᴏʟᴇɴ ꜰʀᴏᴍ you" in red. They give it back: original again.
3. **Gifts:** drop it with Q and a friend picks it up: "ɢɪꜰᴛᴇᴅ ʙʏ you" in gold.
4. **Death drops:** kill a friend and take their armour: stolen from them.
5. **The board:** Strength gem in off hand + right-click shows every online head. Click a friend and put in 2 of their stolen items and 1 random item: 2 minutes of trail, and the random item comes back.
6. **The trail:** follow the black and red thread to them. They go to the Nether: "They walk another realm".
7. **Smithing:** netherite-upgrade a stolen chestplate: still stolen.

# Combat rules module

## What I found first
- **The trust list** (`GemListener.isTrusted`) only decides who gem abilities hit. Plain hits between trusted players aren't blocked, so sparring with a friend counts as a fight.
- **The Revenant:** its mace is a mythic (`RevenantMace.isLiveItem`), so the mace rules never count or touch it. `rules.exempt-revenant` (default false) can exempt its holder from every rule.
- **Gem abilities don't fire the item events these rules use.** Abilities launch projectiles and deal damage in code, never through a player throwing a pearl or wind charge, drinking or eating. Two spots needed care, and both are handled:
  - **A gem hit while holding a mace:** the glide rule only takes away real smash damage (vanilla's `mace_smash` damage type), so a gem hit is never reduced.
  - **Another plugin's revive:** the totem rule only counts real totems.
- **Pantheon** is a separate plugin; this plugin only reads its `points.yml`. Its kill-point messages can't be scrambled from here.
- **Capture** messages name the leader and winner; they now use the scrambled name. Bounty messages get it in the Bounty Hunter rework (next delivery).
- **Finale and Revenant announcements** name players as story beats ("THE SIGNAL HAS CHOSEN ...", "The Revenant rises. ..."). I left them as they are.
- **Existing overlaps:**
  - **Potions and tipped arrows:** progression 1–2 already removes them from inventories; this module bans the harmful ones at every progression.
  - **The finale kit** gives 64 + 64 golden apples, exactly the 128 cap.
  - **`/blissop mace`** (the "Heirloom Mace") still works; that mace gets a hidden id and counts once a player holds it.
- **No config helper class exists;** the plugin reads `getConfig()` directly, and so do the rules. New keys reach an existing config.yml on the next start (copy defaults), as before.
- **SmallCaps.of** writes every refusal and the In combat / Out of combat bar.

## What changed
- **A new `rules:` section in config.yml.**
  - **Switches:** every rule has its own `enabled`.
  - **`rules.worlds`:** set a world to `false` to turn every rule off there (for event worlds).
  - **Exempt from the carry caps and bans:** ops and creative players (and spectators).
  - **Gem and mythic abilities are never affected.**
- **Messages:**
  - **Refusals** go on the action bar, in red small caps, at most once every 2 s per rule per player ("ᴛɪᴘᴘᴇᴅ ᴀʀʀᴏᴡꜱ ᴀʀᴇ ʙᴀɴɴᴇᴅ", "3/3 ᴇɴᴄʜᴀɴᴛᴇᴅ ɢᴀᴘᴘʟᴇꜱ ᴛʜɪꜱ ꜰɪɢʜᴛ").
  - **Cooldowns** use the vanilla item cooldown overlay, and never shorten a longer cooldown something else set.
- **The combat tag (`combat`):**
  - **What starts a fight:** hurting or being hurt by a player: directly, by projectile, by their lit TNT, their tamed pet or their lingering cloud.
  - **When it ends:** after `tag-seconds` (15) with no player damage either way.
  - **The action bar** shows "ɪɴ ᴄᴏᴍʙᴀᴛ" when it starts and "ᴏᴜᴛ ᴏꜰ ᴄᴏᴍʙᴀᴛ" when it ends, nothing in between.
  - **The per-fight counters** reset when the fight ends, on death and on logout.
- **The 16 rules:**
  1. **Enchanted golden apples:** 3 per fight; the 4th is refused and not eaten. No limit outside a fight.
  2. **Golden apples:** you can carry at most 128.
     - **Counted:** the inventory, off hand, cursor and bundles. Enchanted golden apples don't count.
     - **Pickups:** you pick up only what fits; the rest stays on the ground.
     - **Over the cap** after a container take goes back into that container. Otherwise (crafting, anything else) it drops at your feet.
     - **Where it's taken from:** the cursor first, the off hand last.
  3. **Totems:** one totem saves you per fight. A second doesn't activate, so you die. Carrying more is fine.
  4. **Tipped arrows** are banned. They can't be crafted (by hand or in a crafter), shot (bows, crossbows, dispensers), picked up or bought (villager trades are removed). Old ones become normal arrows, same amount, when touched: clicked, picked up, held, on join, or when a container is opened. That includes a loaded crossbow.
  5. **Debuff potions** are banned: any potion with an effect on `rules.debuff-potions.effects`.
     - **The list:** the 15 from your prompt (Weakness, Slowness, Poison, Harming, Wither, Blindness, Darkness, Nausea, Mining Fatigue, Hunger, Levitation, Infested, Oozing, Weaving, Wind Charged).
     - **Brewing** one gives water. They can't be thrown, drunk or bought, and old ones become water bottles when touched.
     - **Positive potions are untouched.**
     - **Turtle Master** has Slowness, so it's banned too. Take `slowness` off the list if you want to keep it.
  6. **Cobwebs:** carry at most 128, handled like golden apples.
  7. **Ender pearls:** a 7 s cooldown in a fight (vanilla's otherwise).
  8. **Wind charges:** a 5 s cooldown, always.
  9. **Chorus fruit:** a 7 s cooldown in a fight.
  10. **Maces:** only `max-on-server` (3) can exist.
      - **Hidden ids:** every mace gets one, recorded in `maces.yml`.
      - **Maces from before the rule** get an id and count as soon as a survival player holds one, picks one up or joins with one. A mace in the creative menu doesn't count until a survival player has it.
      - **Race mode** (default): crafting is open until 3 exist, and each craft is announced to everyone ("ᴀ ᴍᴀᴄᴇ ʜᴀꜱ ʙᴇᴇɴ ꜰᴏʀɢᴇᴅ (1/3)"). After that the recipe gives nothing, with a message.
        - **Shift-click crafting** is refused, so each mace is counted.
        - **Crafters** (the block) count too.
      - **Event mode:** no crafting; `/rules mace give <player>` still counts toward the 3.
      - **A lost mace** (burnt in lava or fire, blown up, fallen into the void, or despawned) is marked destroyed. With `destroyed-frees-slot: true` its slot opens again.
      - **Enchant caps:** Density at most III and Breach at most II, lowered at the enchanting table, on the anvil, and when the mace hits.
      - **No smash damage** while gliding or within 2 s after gliding; the hit does plain mace damage. Vanilla already blocks the smash while gliding; the 2 s after landing is new.
  11. **End crystals** don't hurt players; they still work for the dragon. Block damage: `break-blocks`.
  12. **Bed and respawn anchor explosions** don't hurt players. Block damage: `break-blocks`.
  13. **TNT minecarts** can't be crafted (by hand or in a crafter), placed (by hand or by a dispenser) or used on rails.
  14. **Riptide:** an 8 s cooldown in a fight. The cooldown is on tridents, so it also covers throwing one.
  15. **Elytra:** no gliding for 5 s after taking damage from a player.
  16. **Invisibility hides your name.** It covers a potion, or anything that makes you invisible, gem abilities included.
      - **Death messages:** an invisible killer or victim is an obfuscated scramble of 4–10 letters, and "using [item]" is removed.
      - **Chat:** only the name is scrambled, never the message (`chat-name`).
      - **Capture:** the boss bar leader and the win title and message are scrambled.
      - **The tab list** is left alone (`tab: false`); `true` hides them from it while invisible.
      - **Who sees the real name:** players with `soulbound.rules.see-invisible` (ops by default) in chat, death messages and the capture win. The console log always does.
- **`/rules`** (`soulbound.rules.admin`, ops by default, with tab completion):
  - `status`: every rule, on or off, with its numbers, how many are in a fight, and the mace count;
  - `reload`: reads config.yml again;
  - `toggle <rule>`: turns one rule on or off and saves;
  - `mace give <player>` and `mace list` (each mace's id, who was last seen with it, and how it was made).

## Files
- **New:**
  - `RuleMath.java`: the parts that need no server (fights and their counters, carry caps, the debuff check, mace modes, the smash window, the scramble, the rule list);
  - `RuleCore.java`: what every rule shares (on or off, per world, exemptions, refusal messages);
  - `CombatRules.java`: the combat tag, gapples, totems, the cooldowns, elytra, crystals, beds and anchors, TNT minecarts, invisible names;
  - `ItemRules.java`: carry caps, tipped arrows and debuff potions;
  - `MaceRules.java`: the mace count, modes, ids, enchant caps and the smash rule;
  - `RulesCommand.java`: `/rules`;
  - `RulesTest.java`.
- **Changed:**
  - `SoulboundGemsPlugin.java`: creates and registers the module, `/rules`, and the scrambled-name helpers; puts the tab list back on disable;
  - `CaptureManager.java`: the leader and winner names go through the scramble;
  - `plugin.yml`: `/rules`, `soulbound.rules.admin` and `soulbound.rules.see-invisible`;
  - `config.yml`: `rules:`.

## Tested
- Compiles against paper-api 1.21.11, and all 187 unit tests pass (15 new). They cover:
  - **The combat tag:** a fight lasts 15 s after the last hit, each hit pushes the end back, In combat / Out of combat show once, and players' fights are separate.
  - **The per-fight counters:** 3 gapples then refused (a refused one isn't counted), no limit outside a fight, and a fresh count in the next fight. One totem per fight, and both counters reset on death or logout.
  - **Carry caps:** what fits, a third stack refused, the excess, and the order extra is taken in (off hand last).
  - **The debuff check:** namespaced keys, any case, Turtle Master, positive potions untouched, and all 15 effects from the prompt on the list.
  - **Maces:** race and event modes, the 4th blocked, a give counting, the enchant caps and the smash window.
  - **Invisible names:** the scramble is 4–10 letters (every length comes up), and the death message drops the weapon and scrambles an invisible killer or victim but not the other.
  - **The wiring:** every rule has its own `enabled` with the prompt's defaults, every switch is read, the listeners and `/rules` are registered, the permissions exist, capture uses the scramble, the Revenant's mace is excluded, and lost maces are caught.
- **A bug caught by the existing tests:** the carry count read the inventory in a way that throws on empty slots. Fixed before delivery.

## Not tested
- **Nothing was run in game.** Every event handler is untested on a real server:
  - pickups, container clicks and drags, crafting, brewing, villager trades, dispensers, crossbows, the conversions;
  - the cooldown overlays, the totem cancel, the glide cancel;
  - crystal, bed and anchor explosions;
  - the mace announcements, ids and `maces.yml`, the anvil and table caps, the smash damage;
  - death message and chat scrambles, the tab list option, `/rules`.

## In-game test plan
1. **Gapples:** fight a friend; eat 3 enchanted gapples fine, the 4th is refused. After 15 s out of combat you can eat 3 again.
2. **Totems:** pop a totem, then take lethal damage again in the same fight: you die.
3. **Carry caps:** try to pick up a third stack of golden apples or cobwebs: refused.
4. **Bans:** craft or shoot a tipped arrow, brew a Weakness potion, throw a Harming potion: all blocked. Old ones turn into arrows / water bottles.
5. **Cooldowns:** pearl twice in a fight: 7 s cooldown. Wind charge: 5 s. Chorus in a fight: 7 s.
6. **Maces:** craft 3; each is announced and the 4th is blocked. Enchant Density V: it's reduced to III. Mace-smash while gliding: no smash damage.
7. **Explosions:** blow up an end crystal, a bed in the Nether and an anchor in the Overworld next to a friend: no damage to them.
8. **Elytra:** take a hit, then try to glide right away: you can't for 5 s.
9. **Invisibility:** drink Invisibility and kill a friend: the death message shows a scrambled name and no weapon. Chat while invisible: your name is scrambled and the message is normal. An admin with see-invisible sees your real name.

# Renameable gems and player nicknames

## What I found first
- **Gem names and colours** came from fixed fields on GemType (`display`, `color`), used in 64 places: item names, chat, the `/bliss` menu and config titles, capture, finale and energy messages, `/bliss` and `/blissop` replies.
- **Ability names** were hardcoded in about 200 player-visible strings across 17 files: the "◆ Used Crisp." lines, hit and cooldown messages, the Heretic, Auratus and Relay messages, rift and finale texts, and the tuners' labels.
- **Each gem's lore** is written by hand, with uppercase names and icons ("🔥 FIREBALL 🔥").
- **No separate in-game "Auratus page" exists.** The Auratus text players see is its item lore and its messages, which are covered here. AURATUS.md is a doc only.
- **Tier names** appeared in the `/bliss` menu ("Tier I" / "Tier II"), and as "Tier 2" in the lore.
- **Refreshing:** gems already rebuilt their lore when a version stamp changed; that's reused here.
- **Upgrades and repairs** change the same item, so a nickname stored on it survives them. The Trader makes a new gem, so the nickname correctly doesn't follow.
- **No `/gems` or `/gem` command existed, and nothing touched anvils.**

## What changed
- **`names.yml`** (in the plugin folder) holds every gem's name, colour and ability names, the tier names, and `max-length` (32).
  - It's created with the current names, so nothing changes until someone edits it.
  - It covers the mythics (Heretic, Auratus, Relay) and named passives (Phasing, Autosmelt, Hemorrhage, Feathered Fall, Tuned In ...).
  - Generic stat passives ("Speed", "Strength", "Luck", "Fire Resistance", "Enchants Sharpness") aren't in it: they're descriptions, and swapping those words would change unrelated text.
  - The ids (FIRE, crisp, ...) never change, so configs and saves keep working.
- **What uses the names:**
  - gem item names and lore (the lore heading takes the new name and colour);
  - the `/bliss` menu (tier labels too) and `/bliss config`;
  - the mythic tuners, every chat message, title and action-bar text that names a gem or ability;
  - capture, finale, Prophet, Rift and death/drop messages;
  - `/bliss` and `/blissop` replies.
- **How the swap works:**
  - Gem names and colours are looked up where they're used.
  - Ability names in messages and lore go through one pass that swaps a renamed name, as written and in CAPITALS, including the old spellings ("Unfortunate", "Aegis", "Time Dilation").
  - It works on whole words only, and only for names that were changed.
  - With an untouched `names.yml`, every item, message and menu is exactly as before (tested).
- **Names may use MiniMessage (`<gold>`) or `&` colour codes.** Clicks, hovers, inserts, fonts and newlines are stripped. A name can be at most `max-length` letters (colour codes don't count). A coloured name inside a line is followed by that line's own colour again.
- **Commands** (`soulbound.admin.names`, ops by default, with tab completion):
  - `/gems rename <gem> <name...>` and `/gems rename <gem>.<ability> <name...>` (an ability id, or its current name with `_` for spaces);
  - `/gems color <gem> <#hex or chat colour name>`;
  - `/gems names reset [gem]` (one gem, or everything including the tiers);
  - `/gems names refresh` (re-reads `names.yml` after editing it by hand);
  - `/gems info <player>` (their gems, tiers and nicknames).
  - Every change saves `names.yml` and rebuilds every online player's gems at once.
- **Gems also pick up the current names** on join and on a hotbar switch.
- **Nicknames:**
  - **Setting one:** rename your gem on an anvil, or `/gem nickname <name...>`. `/gem nickname clear` removes it, and `/gem nickname clear <player>` (admin) removes theirs.
  - **Shown as** `"Sunny" (Fire)` (`nicknames.format`), with the server's gem name.
  - **Stored on the gem,** so it stays through death and keep-on-death, upgrades, repairs and restarts, and doesn't move to a different gem.
  - **The rules (`nicknames:`):**
    - `enabled`;
    - `max-length` 16;
    - `allowed` characters (letters, numbers, spaces, basic punctuation);
    - no colour codes unless you have `soulbound.nickname.color`;
    - `blocked-words`, checked anywhere in the name, any case, even split up with dots or spaces;
    - `cooldown-minutes` 10 (admins aren't held to it).
  - **When a name is refused,** you get a clear message (on the action bar at the anvil).
  - **An anvil rename** costs 1 level. A blank or unchanged name gives no result, so a gem can never lose its name.
  - **Mythics** can be nicknamed (`mythic-nicknames`), and the nickname is wiped when one changes hands (picked up, on join, or on a hotbar switch by someone else).

## Files
- **New:**
  - `GemNames.java`: `names.yml`, lookups, the swap pass, cleaning, colours;
  - `Nicknames.java`: the rules, the format, the cooldown;
  - `NamesCommand.java`: `/gems` and `/gem`;
  - `GemNameListener.java`: refresh on join and hotbar switch, anvil nicknames, the mythic wipe;
  - `GemNamesTest.java`.
- **Changed:**
  - `GemType.java`: `shown()` and `tint()`;
  - `GemManager.java`: item names, lore through the names, the refresh stamp, nicknames on the item;
  - `SoulboundGemsPlugin.java`: loads `names.yml` before any gem is made, and wires the commands and listener;
  - `plugin.yml`: the two commands and two permissions;
  - `config.yml`: `nicknames:`;
  - `MenuGUI.java`: the tier names;
  - Messages, titles and labels routed through the names: `GemListener`, `MythicListener`, `AuratusGem`, `RelayGem`, `RelayRift`, `Finale`, `LooseMythics`, `CaptureManager`, `CaptureCommand`, `ConfigGUI`, `EnergyManager`, `BlissCommand`, `BlissOpCommand`, `TurmoilCommand`.
- **Left out on purpose:**
  - `/bliss give`'s tab completion keeps the ids it parses;
  - the Temporal domain's ownership marker file;
  - console-only logs.

## Tested
- Compiles against paper-api 1.21.11, and 172 unit tests pass. They cover:
  - **Name lookup:** the defaults (exactly the old names and colours, Relay's hex included), and the fallbacks for unknown abilities and tiers.
  - **Untouched names:** text comes back exactly as given.
  - **Renaming a gem:** in messages and CAPS headings; "Fire Resistance" is left alone; a mythic name too.
  - **Recolouring.**
  - **Renaming an ability:**
    - plain, after a colour code, in CAPS, and the old spellings;
    - longest names first, whole words only;
    - a coloured name restoring the line's colour.
  - **Cleaning:** clicks stripped, colours kept, no newlines, the length cap.
  - **Tiers, and reset:** for one gem and for everything.
  - **`names.yml`:** created with the defaults, and a round trip.
  - **Nickname validation:** length, blocked words (even split up), characters, colour codes with and without the permission, turned off, a clear message.
  - **The format and the cooldown,** and the config defaults.
  - **The wiring:** every lore list goes through the names, item names use them, every "Used ..." line goes through them, more than 100 messages and labels are routed, and the commands are registered.

## Not tested
- Nothing was run in game:
  - the anvil (PrepareAnvilEvent, the result, the cost);
  - the commands and tab completion;
  - how renamed and recoloured names look in lore, menus and the action bar;
  - the refresh on join and hotbar switch;
  - the mythic nickname wipe.

## In-game test plan
1. **`/gems rename fire Solaris` and `/gems rename fire.crisp Scorch`:** the item, action bar, menus and messages all say Solaris / Scorch right away.
2. **`/gems color fire #FF8A2A`:** the colour changes everywhere.
3. **Rename your gem on an anvil to "Sunny":** it shows "Sunny" (Solaris). Die, upgrade, restart: the nickname stays.
4. **Refusals:** try a blocked word and a 30-character name; each is refused with a clear message. Try again within 10 minutes: refused with the time left.
5. **`/gems names reset`:** everything is back to the defaults.
6. **Mythic nicknames:** nickname Auratus, drop it, let a friend pick it up: the nickname is gone.

# Life revamp

## What I found first
- **Circle of Life:**
  - The steal took 5 hearts from **every** player within 12 blocks, trusted friends included, not just the one you clicked.
  - With nobody around it only mended your armour, which every cast also did (20 durability per piece).
- **The heart locks** (Heart Drainer, the steal) had two bugs:
  - **Bonuses could become permanent:** they saved your current max health (including any bonus) and later wrote it back as your *base*. A blessed player who got Heart Drained would have kept the +4 hearts for good.
  - **Logging out lost hearts:** if you logged out during a lock, your hearts never came back.
- **Vitality Vortex:**
  - It turned logs into gray concrete and leaves and flowers into light gray concrete within 8 blocks, **permanently**.
  - With fewer than 5 of those blocks it failed and refunded the cooldown.
  - Its buffs lasted 20–60 s and its Resistance level (0 to 2) grew with the count.
- **Unchanged:** the passive (Regeneration, Absorption II, Saturation, Wither removed), Heart Drainer, and every cooldown (Circle 120 s, Vortex 60 s).
- **The sound rules** said Life has "no amethyst". The blessing uses the amethyst chime as you asked; the comment is updated, and the sound test allows the chime to be shared with the Relay.

## What changed
- **Circle of Life has two versions, with one shared cooldown:**
  - **The steal:** an untrusted player in front of you (within 12 blocks) means the steal, as before. It now never takes from your trusted players.
  - **The blessing:** anything else (the air, a block, nobody, a friend).
    - **The ring:** about 5 s of pink rings flat on the ground (radius `life.circle-of-life.blessing.radius`, 6), slowly turning, with hearts, a few green sparkles and a soft white shimmer rising at the edge. The sound is `sounds.life.blessing`.
    - **Who's blessed:** you and every trusted player in the ring when it's cast. Untrusted players get nothing.
    - **What they get:**
      - +4 hearts (max health +8) for `seconds` (30);
      - full hunger and saturation;
      - Regeneration II for `regeneration-seconds` (10).
    - **The hearts can't stick:** they're one keyed max-health modifier that's never saved, so they can't stack. They come off when the time's up, and on death, respawn, logout, rejoin, plugin reload and server stop. A restart can't keep them.
  - **Armour mending** still happens on both versions.
- **Heart locks:** they now read and restore your own base max health, so a bonus is never baked in. A logout or server stop mid-lock gives the hearts back at once. Heart Drainer works exactly as before.
- **Vitality Vortex works anywhere:** it always gives its buffs, with the same formula as before, counted on the blocks it drains. A bare spot still gives 20 s with Resistance I.
- **Vitality Vortex drains the land** (`life.vitality-vortex`):
  - **The patches:** in `drain-radius` (5), a patchy `drain-chance` (0.45) share of eligible blocks turns into weighted dead blocks (`dead-blocks`): the five dead coral blocks, andesite, tuff, gravel, coarse dirt and cobblestone.
  - **Plants and leaves:** plants on them wither (a dead bush, or gone; both halves of a tall plant). Crops on farmland wither on their own. Drained leaves vanish.
  - **The spread:** outward over a second, with green-to-grey motes flowing from the drained blocks into you.
  - **Underground** (no sky, a ceiling within 6 blocks): a sphere, so the floor, walls and ceiling turn grey.
  - **The same filter as Crisp:** only natural blocks; never block entities, anything built, bedrock, capture points, Rift blocked areas or the finale's arena.
  - **Liquids:** it never touches a block next to water or lava, so it never opens a way for a liquid. Gravel is only placed on solid ground, so nothing it places can fall.
  - **Regrowing:** after `restore-after-seconds` (30) it grows back outward over about 1.5 s, exactly as it was, with green sparkles and `sounds.life.regrow`. A spot a player broke or changed is left as it is.
  - **Drops:** breaking a drained block drops its dead version.
  - **Saved:** pending restores are saved, so a restart or crash puts the land back on start.
  - **`drain: false`** turns the land effect off.
- **The shared land engine:** Crisp and the Vortex now share one land engine (no second copy), and Crisp works exactly as before. `LandWorks` holds:
  - the ledger (`LandLedger`, renamed from `CrispLedger`);
  - saving, to `land-restore.yml` (it also reads the last version's `crisp-restore.yml`);
  - the natural-blocks filter and the places it keeps out of;
  - the guards (pistons, explosions, endermen, buckets, liquid flow, fire spread, leaf decay).
- **Lore and guide:** they explain both Circles of Life and the Vortex (lore version 9).

## Files
- **New:**
  - `LandWorks.java`: the shared engine;
  - `LandMath.java`: the shared filter, plants, patches, underground check;
  - `VortexDrain.java`;
  - `LifeBlessing.java`;
  - `LifeMath.java`: the Circle choice, who's blessed, the hearts timer;
  - `LifeTest.java`.
- **Renamed:** `CrispLedger.java` → `LandLedger.java`.
- **Changed:**
  - `CrispLand.java`: now built on LandWorks, same behaviour;
  - `CrispMath.java`: pits only, the rest moved to LandMath;
  - `GemListener.java`: the Circle of Life routing, the steal skipping trusted players, the blessing, the Vortex, and heart locks on the base value restored on logout and stop;
  - `SoulboundGemsPlugin.java`: wiring;
  - `GemType.java` and `GemManager.java`: lore and guide text;
  - `config.yml`: the `life:` section, two sounds, the life sound comment;
  - `FireTest.java`: the shared names;
  - `GemSoundsTest.java`: the amethyst chime.

## Tested
- Compiles against paper-api 1.21.11, and 162 unit tests pass. They cover:
  - **The blessing target choice:** an untrusted player in front gives the steal; a friend in front, or nobody, gives the blessing.
  - **Trusted-only buffs:** you and your trusted players once each; strangers get nothing; it's your own trust list that counts.
  - **The extra hearts:** a second blessing refreshes the timer, never +8; they come off when the time's up and on death, logout and rejoin; never taken twice. The modifier is unsaved and replaced, every cleanup path calls it, and the plugin start and stop clear it.
  - **Heart locks:** on the base value, given back on logout and stop, and the steal skips friends.
  - **The eligible-block filter:** naturals and leaves yes; placed leaves, chests, beds, doors, redstone, signs, banners, heads, bedrock, planks, farmland, ores and block entities no; flowers, grass, ferns, crops and tall plants wither.
  - **The Vortex:** it always works, with its buffs; the drain skips blocks holding back liquids and never places gravel that can fall.
  - **The config.**
  - All the Fire and Crisp tests still pass on the shared engine.

## Not tested
- Nothing was run in game. Unverified:
  - the ring and motes;
  - the hearts on a real player;
  - the drain and regrow in a real forest, desert and cave;
  - the restart restore.

## In-game test plan
1. **Steal:** right-click a player: steal 5 hearts as before (a trusted friend standing nearby keeps theirs).
2. **Blessing:**
   - right-click the air with a trusted friend and an untrusted one nearby;
   - a pink ring appears; you and your friend get +4 hearts, full hunger and Regen II; the untrusted player gets nothing;
   - after 30 s the hearts go away;
   - die during it: no leftover hearts. Log out and back in during it: none either.
3. **Vortex in a forest, a desert and a stone cave:** it works everywhere; the ground around you turns patchy dead coral, andesite and gravel, with particles flowing into you.
4. **Vortex in a cave:** the floor, walls and ceiling around you all turn patchy grey (a sphere), and it all grows back after 30 s.
5. **Vortex next to a chest, a door and a bed:** they're untouched.
6. **Regrowth:**
   - wait 30 s after a Vortex: the ground regrows outward with green particles, exactly as it was;
   - break one dead block before then: that spot stays broken;
   - restart the server mid-drain: the land is restored on start.
7. **Heart Drainer on a blessed player:** after both wear off, their max health is exactly what it was.

# Fire revamp

## What I found first
- **Fireball cooldown:** 180 s in the code fallback, `cooldowns.fireball` and the config comment. No lore mentions the time.
- **Fireball charge:** a fixed time worked out from how long you'd held it: a full charge in 3 s, or 1.5 s on fire blocks (obsidian and crying obsidian were on that list too). It then decayed after 8 s off fire blocks.
- **Crisp's land change was permanent, with no restore and no safety checks:**
  - It used a radius of 6 at Tier 1 and 8 at Tier 2.
  - It converted the block *under* the top block (the dirt under the grass), so you mostly couldn't see it.
  - Its fire and lava never appeared: their check for an empty spot could never pass.
  - Nothing stopped it converting chests or anything built.
- **Kept unchanged:** the damage (4 plus 5 s of fire to untrusted players and mobs), the smelting of drops, Cozy Campfire, Meteor Shower and the passives.
- **No Life revamp code exists,** so the land change, restore and safety code is new (CrispLedger can be reused for Life later).

## What changed
- **Fireball cooldown 180 → 120 s**, in the config and the code fallback. A server still on 180 is moved to 120 once (balance version 5).
- **Fireball charge:**
  - **Full charge:** `fire.fireball.charge-seconds` (20). Standing on or in a block from `fire.fireball.fire-blocks` it charges twice as fast, a full charge in `charge-seconds-on-fire` (10).
  - **The fire-block list:** netherrack, magma, every blackstone (`*BLACKSTONE*`), basalt and its variants, soul sand and soil, both nyliums, both campfires, fire and lava. Crisp's blocks all count. Obsidian is no longer on it.
  - **How it charges:** the charge grows every 2 ticks, so stepping off slows it again from where it is and never resets it.
  - **While boosted:** the bar text turns a brighter orange with " x2", and a few flames rise from your feet. Boss bars have no orange colour, so the bar itself stays yellow.
  - **The 8 s decay is gone:** with a 20 s charge it would drain before it ever filled.
  - **Unchanged:** the charge still sets the explosion power and the model size.
- **Crisp: the land catches fire** (`fire.crisp`, with comments):
  - **Patches:** in `radius` (5), a `chance` (0.45) share of eligible blocks turns into Nether blocks from `blocks` (weighted). They form random clumps, each its own block type, never a solid disc, and different every cast.
  - **The spread:** outward from you over a second, with flames and lava bursting up.
  - **Fire:** on some new netherrack (`fire-chance` 0.15), only where fire can sit. It never spreads or lights anything.
  - **Lava pits:** up to `lava-pits-max` (2), each with `lava-pit-chance` (0.5).
    - **Shape:** 1-deep, 1 to 3 across (one block, a 2×2 or a plus).
    - **Where:** on the floor only, solid on every side and below so it can't flow, and never next to or under water.
    - **Never under anyone:** not within a block of the caster's feet, and never under anyone standing, checked as the pit forms.
    - **Distance from the edge:** at least 2 blocks inside it, away from water flowing in.
  - **Underground** (no sky, and a solid ceiling within 6 blocks; leaves don't count, so standing under a tree isn't a cave):
    - **The area is a sphere** of the same radius: the exposed floor, walls and ceiling turn.
    - **Pits stay on the floor,** and lava drips from the ceiling for the whole duration.
  - **Evaporation (`evaporate`):**
    - **At the cast:** all water in the area goes at once, with steam and a hiss (`sounds.fire.crisp-evaporate`): sources, flowing water, waterlogged blocks, seagrass and kelp.
    - **While it lasts:** water flowing in from outside steams away every 4 ticks.
    - **On cooling,** it all comes back exactly as it was.
    - **Exceptions:** water touching lava is never removed (no lava can flow into it), and a waterlogged chest or sign keeps its water.
  - **Cooling:** after `restore-after-seconds` (30), every changed block goes back to exactly what it was, over about 1.5 s with steam and a soft hiss (`sounds.fire.crisp-cool`).
    - **The order:** fire out first, then the pits, then the land, the plants, and the water last.
    - **A spot a player broke or replaced** is left as it is now.
  - **Plants:** a single plant on a block that turns (grass, flowers) is cleared and comes back with it. A block under a double plant, a torch, a slab and so on is left alone.
  - **Only natural ground changes:** grass, dirt, coarse and rooted dirt, podzol, mycelium, moss, mud, sand, red sand, gravel, clay, stone, granite, diorite, andesite, deepslate, tuff, calcite, dripstone, snow blocks, logs (not stripped) and leaves that grew there.
  - **Never:** anything with a block entity, anything built, bedrock, ores, a running capture point, a Rift blocked world or area (or the Temporal domain), or the running finale's arena.
  - **While it lasts:**
    - breaking a Crisp block drops nothing;
    - its lava can't be picked up with a bucket and never flows;
    - pistons, explosions and endermen leave its blocks alone;
    - leaves near it don't decay (a log turned to netherrack would otherwise make them fall for good).
  - **Saved:** pending restores go in `crisp-restore.yml`, so a restart (or a crash) puts the land back on start. A normal stop cools everything at once.
  - **`land: false`** turns the land effect off. Crisp still hurts and smelts as before.
- **Guide text:** Crisp's line says it cools back.

## Files
- **New:**
  - `CrispMath.java`: the block filter, patches, pits, the underground check;
  - `CrispLedger.java`: what changed and the exact restore, saved as text;
  - `CrispLand.java`: the cast, spread, evaporation, dripping, cooling, saving and guards;
  - `FireballCharge.java`: the charge and the fire-block list;
  - `FireTest.java`.
- **Changed:**
  - `GemListener.java`: the new fireball charge and boss bar, the cooldown fallback, and Crisp calling the land effect (its old permanent land code removed);
  - `SoulboundGemsPlugin.java`: wiring, and balance version 5 (the fireball cooldown);
  - `RelayRift.java`: `configuredBlock`, the blocked worlds and areas without the finale's seal;
  - `RelayGem.java`: a `rift()` accessor;
  - `Finale.java`: `inArea`;
  - `GemType.java`: Crisp's guide text;
  - `config.yml`: `fire.fireball` charge keys, `fire.crisp`, two sounds, the fireball cooldown and comment.

## Tested
- Compiles against paper-api 1.21.11, and 154 unit tests pass. They cover:
  - **The fireball cooldown:** 120 in the config, the fallback and the migration.
  - **The charge:** exactly 20 s, or 10 s on fire; stepping off at half carries on from half; never over full.
  - **The fire-block list:** every listed block, including blackstone variants; grass, stone and obsidian are not on it; every Crisp block is.
  - **The eligible-block filter:** naturals yes; chests, beds, doors, redstone, signs, banners, heads, bedrock, planks, cobble, ores and stripped logs no; placed leaves no; any block entity no.
  - **The underground check:** no sky plus a ceiling within 6 blocks.
  - **The patches:** the exact share, never all of them, different per cast, and clumped.
  - **Lava pits:** on the floor only, enclosed, solid below, never at or beside the caster's feet, never under someone standing, never next to water or under water flowing in.
  - **The restore:** the exact block data back; a player-changed spot left; the order fire → pit → land → water; nothing twice; saved entries read back the same.

## Not tested
- Nothing was run in game. Everything that needs a world is unverified:
  - how the patches, spread, fire, pits, dripping lava, steam and cooling look;
  - the underground sphere in a real cave;
  - evaporation and keeping a real lake dry;
  - the restart restore;
  - the boss bar x2 and the flames.

## In-game test plan
1. **Fireball:**
   - the cooldown is 120 s;
   - charging on grass takes 20 s to full;
   - on netherrack or magma it takes 10 s and the bar shows x2;
   - stepping off halfway slows it again without resetting.
2. **Fireball on Crisp's blocks:** cast Crisp, then charge a fireball standing on the Nether blocks: 10 s.
3. **Crisp in a plains biome, three times:**
   - random Nether patches, some fire, sometimes one or two small lava pits, never under you;
   - after 30 s it cools back with steam, exactly as before.
4. **Crisp in a cave:**
   - the floor, walls and ceiling turn to Nether blocks;
   - lava drips from the ceiling;
   - pits only on the floor;
   - it all cools back.
5. **Crisp next to a chest and a bed:** they're untouched.
6. **Crisp at the edge of a lake:**
   - the water in the area steams away;
   - it stays dry while it lasts, and water from outside doesn't refill it;
   - when it cools, the lake is back exactly as it was.
7. **Restart the server during Crisp:** the land is restored on start.
8. **Also check:**
   - break a Crisp block: nothing drops;
   - try to bucket a pit: you can't;
   - Crisp's fire doesn't spread to grass or trees.

# Astra revamp

## What I found first
- **Controls (Tier 2, gem in the main hand):**
  - left-click Daggers;
  - right-click air or a block: Projection;
  - right-click a creature: Astral Void;
  - hitting something: Unbounded;
  - off-hand right-click: Drift.
- **Drift:** Tier 1 was the ghost horse; Tier 2 was Speed V and Jump III. Both lasted 5 s, with a 15 s cooldown.
- **Daggers** always circled in front of you while the Tier 2 gem was held, snapped into place every tick. Each click threw a hidden arrow carrying the dagger model, and the 90 s cooldown started after the 5th.
- **Projection:**
  - Invisibility and flight (not spectator) for up to 60 s, leaving a zombie body.
  - It already sent you back to the body at the end.
  - Ghost hits disabled the victim's gem for 10 s at half damage.
  - **Its real cooldown is 20 s,** not 120: `projection` isn't in the cooldown table, so it falls back to `cooldowns-seconds.power`. It's kept at 20 s, and the config comment is fixed.
- **Unbounded:** on a hit, you rode the creature as an invisible passenger for up to 15 s, with 30 s between uses. Leaving disabled your own gem for 30 s. Nothing happened to the target.
- **Void:** a 120 s cooldown; it disabled untrusted players' gems within 10 blocks for 20 s, centred on the creature you clicked.
- **Phasing:** a 10% (Tier 1) or 15% (Tier 2) dodge, with portal particles.
- **Mannequin** is in paper-api 1.21.11 (and 26.2).

## What changed
- **Controls (Tier 2, main hand):**
  - left-click Daggers;
  - right-click a player: Unbounded;
  - right-click anywhere else: Astral Void;
  - sneak + right-click: Astral Projection (nothing else uses it, so there's no clash);
  - off-hand right-click: Drift, unchanged.
  - A right-click on a player can arrive as two clicks in the same tick; only the first counts.
- **Lore, guide text and menu:** the gem lore now shows each control, and the guide blurbs are rewritten. Existing gems rebuild their lore once (lore version 8).
- **Dimensional Drift:** the ghost horse at both tiers. The Tier 2 speed surge is gone. Same 5 s and 15 s cooldown.
- **Daggers:**
  - **None until you use them.** The first left-click makes 5 appear in a fan behind your shoulders, tips forward, growing in over 4 ticks, and throws the first at once. Each click after throws the next one from behind you, at what's under your crosshair (same speed and damage).
  - **Following you:** the waiting daggers follow you smoothly and turn with you. They glide to you every 2 ticks, smoothed by the client, and their turning is an interpolated pose.
    - **Why not mount them:** a player carrying passengers can't be teleported by plugin abilities, so Rift, Rewind or the finale would silently fail.
  - **The linger timer:** `astra.daggers.linger-seconds` (8) without a throw, and the rest fade out and the cooldown starts, as if all 5 were thrown. The cooldown still starts after the 5th.
  - **Cleanup:** death, logout, world change, gem swap, projection and plugin disable all clear the waiting daggers and start the cooldown, so swapping can't reset a volley.
- **Astral Projection:**
  - **Spectator** for `projection.seconds` (10). Your body is left exactly where you stood: a Mannequin with your skin and armour (copies), or on a server without Mannequin, a no-AI silent zombie with your head and armour.
  - **The body** can't burn, despawn, drop anything or be pushed, isn't saved to the world, and hangs in place even mid-air.
  - **Limits:** you can't go past `max-distance` (48) from your body (pulled back to the edge), can't possess anything, and can't use portals or the spectator teleport menu.
  - **Tagging:** left-click a player within `tag-range` (4) to tag them.
    - Their gem locks for `tag-lock-seconds` (6) through the existing gem disable.
    - They get the spook: `sounds.astra.tag-spook` (Vex charge plus quiet cave ambience, only they hear it), a 1.5 s Darkness pulse, and purple soul particles at their face, which only they see.
    - Each player is tagged once per projection, at most `max-tags` (3) times in all.
    - They're never told who did it: their message says "something unseen".
    - Trusted players can't be tagged, as with Astral Void.
  - **Hits on the body come to you** (`body-takes-hits`): attacks, arrows and explosions only, never a fall. One that would kill you ends the projection first and lands on you in your body.
  - **The end:** time up, sneak, or a lethal body hit puts you back at the body's exact spot and facing, in your old game mode, and the body goes.
- **Unbounded:**
  - **Entering:** right-click a player. You go into spectator with your camera inside them for `unbounded.seconds` (4). They hear a faint whisper (`unbounded-whisper`).
  - **Leaving:** sneak is the only early way out. Clicking someone else or the teleport menu does nothing.
  - **Coming out:** just behind them, facing their back (the nearest safe spot if that's in a wall or over lava), in your old game mode. If they die, log out or change worlds, it ends at once and you come out where they were.
  - **Kept as before:** the 30 s between uses, and leaving disables your own gem for 30 s. It still does nothing to the target.
- **Never left in spectator:**
  - **One path out:** every way out goes through one place that restores your game mode and spot exactly once. That covers time up, sneak, a body hit, the target leaving, logout, death, world change, someone else changing your game mode, plugin disable and server stop.
  - **The restore record:** a record in `astral-restore.yml` is written when you go out. A logout restores you on the spot and again when you rejoin, and a crash restores you when you rejoin. A record never restores anyone to spectator.
- **Astral Void:**
  - Right-click anywhere that isn't a player: the same mass gem disable as before.
  - It's now centred on you (it was centred on the creature you clicked).
  - It no longer says "on cooldown" in chat: right-clicking the air is common.
- **Phasing:** when you dodge, a ring of amethyst-purple and white dust and portal particles flashes at your waist, and you vanish for `phasing.vanish-ticks` (8) ticks.
  - **How:** invisibility, plus client-side equipment changes that hide your armour and held items on other players' screens only. The real ones are sent back afterwards, and your items are never touched.
  - Same dodge chances and Soul Healing.
- **New sounds** (`sounds.astra`): `daggers-summon` (amethyst resonate, soft), `projection-end`, `unbounded-enter`, `unbounded-exit`, `unbounded-whisper`, `tag-spook`, `phasing` (a quiet, high Enderman teleport). The sound test allows the amethyst resonate to be shared with the Auratus Aegis, since you asked for it.
- **Removed:**
  - the old orbiting daggers;
  - Unbounded on hit and its passenger ride;
  - the invisible flying projection, its "ghost hits" and its right-click return;
  - the Tier 2 Drift surge.

## Files
- **New:**
  - `AstraMath.java`: controls, fan, linger, leash;
  - `SpectatorSessions.java`: every end path;
  - `AstralSpectate.java`: Projection, Unbounded, body, tagging, safety;
  - `MannequinBody.java`: the Mannequin, isolated for servers without it;
  - `AstraTest.java`.
- **Changed:**
  - `GemListener.java`: the routing, daggers, Drift, Phasing, Void, HUD; the old Unbounded and Projection removed.
  - `GemType.java` and `GemManager.java`: lore and guide text, lore version 8.
  - `SoulboundGemsPlugin.java`: wiring, with the restore done first on disable.
  - `config.yml`: `astra.daggers`, `astra.projection`, `astra.unbounded`, `astra.phasing`, the new sounds, and the cooldown comment.
  - `AstraDaggerTest.java`: the new volley.
  - `GemSoundsTest.java`: the shared resonate.

## Tested
- Compiles against paper-api 1.21.11, and 144 unit tests pass. They cover:
  - **Control routing:** left, right on a player, right elsewhere, sneak.
  - **The linger timer:** 7.999 s vs 8 s, and a throw restarting it.
  - **The fan:** behind your shoulders, symmetric, turning with your yaw.
  - **The leash:** inside it is left alone; past 48 you're pulled to 47.5 along your path.
  - **Every end path,** for both kinds:
    - it restores the old game mode exactly once;
    - Projection always goes back to the body's spot and facing;
    - Unbounded comes out behind the target, or where they were if they're gone;
    - each path is wired to its event, plugin disable restores first, and a record never restores spectator.
  - **Drift and the config:** Drift has no Tier 2 branch, and all the config values are in.

## Not tested
- Nothing was run in game. Everything that needs a real server or client is unverified:
  - how the Mannequin and the zombie body look;
  - spectator behaviour (the possess click being cancelled and turned into a tag, camera lock-in, portals, the teleport menu);
  - the daggers' glide and fade;
  - Phasing's hidden equipment;
  - the restore after a real logout or crash.

## In-game test plan
1. **Drift** at Tier 1 and Tier 2: both give the ghost horse.
2. **Daggers:** left-click and 5 fan out behind you, one throws per click. Wait 8 s with some left: they fade and the cooldown starts. Swap off the gem mid-volley: they go and the cooldown starts.
3. **Projection:** sneak + right-click.
   - You're in spectator, and your body stays behind with your skin and armour.
   - Fly past 48 blocks: you're pulled back. Hit the body: you take the damage. When it ends you snap back to the body.
   - Left-click a player: their gem locks for 6 s and they get the spook (sound, darkness pulse, particles). A 4th tag, or tagging the same player twice, does nothing.
4. **Unbounded:** right-click a player. You see through their eyes for 4 s, then pop out behind them. Sneak to leave early.
5. **Void:** right-click the air: Astral Void as before, around you.
6. **Phasing:** take hits until you dodge: a purple ring, and you vanish (armour and items too) for a moment.
7. **Safety:** log out mid-projection and mid-Unbounded, then rejoin: you're back in survival at the right spot. Also try `/stop` mid-projection, and `/kill` on the body.

# Next batch: pedestal follow-ups, the Spore fountain, the Tier 2 bar, the fireball, droppable mythics and the Prophet

## What I found first
- **Pedestal:**
  - The Q release and the "gems can't be dropped" rule already checked `PedestalDrop.owns` (through `Finale.claimsDrop`).
  - The one-time id did stay on a gem that wasn't placed, but only in these cases: the finale ended (or was stopped) before the next tick, it couldn't be taken back, or the player logged off that tick. Too far and spammed Q never stamp it. Ending the finale cleared the waiting drops before their tick, so that case always left the id.
- **Heretic 1–3 were already done** in the last batch: no `SWEEP_ATTACK` anywhere, contact slams with an instant bounce, and 1.9 / 11 / 4 plus `bounce-launch-y`. BloodlinkBounceTest covers them. Nothing changed there.
- **Spore today:** a burst of crimson spores and dust when it spawns, then a thin dust layer every 4 ticks.
- **Action bar:**
  - The config key is `actionbar.icons` (not `hud.icons`).
  - Tier 2 showed left, right, then the ability, and the ability always used the Tier 1 crystal.
  - Flux showed all four entries at every tier.
  - The bar is redrawn every 2 ticks from the gem's tier, so an upgrade switches it at once.
- **Fireball: not done before.** Nothing in the plugin resized it. It's a vanilla small fireball the whole flight (it only looks smaller as it flies away), and the charge only changed its explosion, so it had no charged size to keep.
- **Mythics:**
  - `GemListener.onDrop` refused every gem drop, and `onChestStore` kept gems out of 7 container types.
  - Nothing protected a mythic on the ground (despawn, lava, cactus, void, hoppers, mobs).
  - Nothing stops a player picking one up.
  - F hauled a hooked creature and otherwise was the normal swap, so there's no clash.

## What changed
### Part 1: pedestal drop
- **A gem that goes back keeps no id.** Whenever a stamped gem isn't placed (the finale ended or was stopped, it couldn't be taken back), the id comes off wherever it is: any slot, the cursor, an open inventory or the ground.
- **A player who logged off that tick** has it removed when they join. During the drop phase, any stray id (the gem changed hands that tick, say) is swept off every second.
- **Ending the finale no longer forgets the waiting drops,** so each one still settles.

### Part 2: the Spore blood fountain (visual only; the field, bleed and timings are unchanged)
- **The column:** dark blood red dust (#7A0010, #B0101C, sizes 1.4–2.0) rises from the ground at the centre to `pillar-height` (18, kept within 15–20). Dust doesn't move by itself, so it's drawn a little higher each tick; that makes it flow upward and read as one solid column.
- **A heartbeat every `pulse-seconds`:** for 4 ticks the column surges brighter (#D01A28), thicker and with more particles. `sounds.heretic.spore-pulse` plays (Warden heartbeat, 0.6, 0.8).
- **The canopy:** rings out to `canopy-radius` (5) that arc up from the column and fall away, with a sine ripple rolling outward.
- **Blood rain** across the field: falling redstone-block dust and a few small dark drops. Half start at the canopy, and the rest are already on the way down, so the whole height rains.
- **Under a ceiling,** the column stops just under it and the canopy spreads there, rippling downward.
- **Limits:** at most `particles-per-tick` (100) per field, sent to players within 64 blocks. Nothing is drawn after the tick the field ends.
- **Config:** `fountain: false` brings back the old particles.

### Part 3: the normal gem bar
- **Tier 2 order:** left-click power, the ability, then right-click power (Fire: fireball, crisp, campfire). Every entry is built with its own icon and timer before it's placed, so timers, Ready! and … stay on their icon.
- **Tier 1** shows the ability only. That now includes Flux, which used to show everything at Tier 1. Tier 2 Flux keeps beam, burst, watts, overdrive.
- **Tier 2 crystals:** new `actionbar.icons.<GEM>.ability-tier2` with the pack's Tier 2 crystals. A config without it uses those glyphs.

### Part 4: the fireball
- **The model:** a fire charge sized by the charge it's fired with (`fire.fireball.model`, 0.75 at the weakest to 1.5 full). It's set once at launch and is the same size for the whole flight.
- **The vanilla fireball** is hidden under it so there aren't two, and a little smoke follows it.
- **Unchanged:** damage, speed, range, explosion and cooldown.
- **Config:** `model.enabled: false` brings back the plain fireball.

### Part 5: Auratus and Heretic aren't soulbound (Relay and the Revenant mace unchanged)
- **Dropping:** Q drops them and anyone can pick them up. They still drop on death.
- **On the ground** they never despawn, glow, and can't be destroyed. Lava, fire, cactus and the void send them back to whoever last had them (into their inventory), or to the main world's spawn if that player is offline, dead or full. Explosions just miss.
- **Hoppers and mobs** can't take them.
- **The finale pedestal drop works as before.** During the finale the two gems only go onto their pedestals.
- **The Auratus chain release moved to F:** F while a chain is out and nothing is hooked (a block, nothing yet, or flying to the hook) lets go. F with a creature hooked still hauls. Q drops the gem, and a chain that's out goes with it.
- **The Prophet:**
  - **When he acts:** one tick after a player puts Auratus or Heretic into any container, it's pulled back out to them (at their feet if they're full) with a short particle trail. This covers chests, ender chests, shulkers, barrels, hoppers, droppers, dispensers, crafters, furnaces, bundles (in a container or in your own inventory), item frames, armor stands, allays, decorated pots, and chest carts, boats and animals.
  - **Each time:** the screen dims (`dim`), the bell rings (`sounds.prophet.refuse`, only for you), and he says the next line in chat after the pack's eye glyph.
  - **The 9th line ("Fine.")** lets it stay. The 10th pulls it out anyway and makes you glow for 30 s. Then the count starts again at 1.
  - **The count is saved per player** in `prophet.yml`. If your last line was more than 7 days ago, the next try gets "We have done this before." and then the lines carry on.
  - **At most one line every 2 s.** Tries in between are still pulled out but don't count, so the lines always come in order.
  - **Only gems that are newly inside something count,** so a gem he let stay isn't pulled out by an unrelated click.
  - **Not counted:** a hopper or mob taking a gem off the ground (it isn't a player putting it in); that's just blocked.
- **Lore** updated to match the controls: Auratus "swap hands ... (or lets go)", and the Heretic's saws and Bloodlinking were the wrong way round.

### Files
- **Changed:**
  - `Finale.java`: id removal, the stray-id sweep, the join cleanup.
  - `PedestalDrop.java`: settle removes the id; waiting drops survive the finale ending.
  - `HudStatus.java`: bar order and crystal choice.
  - `GemListener.java`:
    - the bar order, the Tier 2 crystal and Flux Tier 1;
    - Auratus and Heretic out of the drop and chest rules;
    - the fireball model.
  - `MythicListener.java`: starts the Spore fountain.
  - `AuratusGem.java`: the Q release removed, F lets go, a drop puts the chain away.
  - `GemType.java`: lore.
  - `SoulboundGemsPlugin.java`: wires the new listeners.
  - `config.yml`:
    - `actionbar.icons.*.ability-tier2`;
    - `mythics.heretic.spore.visuals`;
    - `fire.fireball.model`;
    - `prophet:`;
    - `sounds.heretic.spore-pulse` and `sounds.prophet.refuse`;
    - comments.
  - Docs: AURATUS.md, SOUNDS.md.
- **New:** `LooseMythics.java` (the ground rules), `Prophet.java`, `ProphetMath.java`, `SporeFountain.java`, `FireballLook.java`.
- **Tests:**
  - new: TierTwoBarTest, ProphetTest, SporeFountainTest, FireballLookTest;
  - updated: FinaleDropTest (the id removal, droppable mythics) and GemSoundsTest (the Prophet isn't a gem family).

### Tested
- Compiles against paper-api 1.21.11, and 136 unit tests pass. They cover:
  - **Pedestal ids:** removed when the finale ends, when spammed, and when the gem is out of reach; never stamped when too far.
  - **Drops:** every listener order, with the new rules.
  - **Bloodlinking** (from before): a hit bounces at once, a miss keeps the old rule, max-slams respected.
  - **The bar:** the Tier 2 order, timers staying on their icon, and the crystal choice for all 8 gems, including a config without ability-tier2.
  - **The Prophet:** 10 lines in order, keep on 9, glow on 10, round again, the 2-second limit, the 7-day welcome-back, and the dialogue in the config.
  - **The fountain:** height kept in 15–20, the ceiling cap, about 1 pulse a second, never over the particle budget, and the ripple rolling outward.
  - **The fireball:** size from the charge, set once.

### Not tested
- Nothing was run in game. These depend on the real server and client: the particles and how the fountain looks, the Darkness dim, the hidden fireball and its model, the item frame, armor stand, allay and pot paths, and the lava, cactus and void rescues.
- **Known limit:** a gem the Prophet let stay in a container is invisible to `/capture start`'s "the reward already exists" check (it looks at players and the ground).

### In-game test plan
1. **Finale:**
   - drop Auratus and Heretic at their pedestals: each ends up only on the pedestal;
   - Q at the pedestal doesn't release a chain (a chain that was out is put away once the gem is on the pedestal, since you no longer hold it);
   - walk away and drop: you're told to bring it to its pedestal, and the gem stays with you with no id (`/data get entity @s Inventory` shows no `finale_drop`).
2. **Saws:** throw Heretic saws: no sweep particles.
3. **Bloodlinking into a group of zombies:** you bounce straight back up the moment you hit them, up to 3 slams, and take no fall damage at the end.
4. **Bloodlinking into a zombie in mid-air:** it slams on contact.
5. **Spore:**
   - an 18-block pillar pulsing with a heartbeat, a rippling top, and blood rain over the field;
   - in a cave it stops at the ceiling;
   - everything stops when the field ends.
6. **The bar:** a Tier 2 Fire gem shows fireball, the Tier 2 crystal, campfire. Upgrade a Tier 1 gem: the bar switches.
7. **Fireball:** fire a full-charge fireball: same size the whole flight, bigger than a weak one.
8. **Dropping:**
   - drop Auratus and hand it to another player;
   - leave one on the ground for 10 minutes: it's still there and glowing;
   - throw one in lava or the void: it comes back;
   - with a chain out and nothing hooked, F releases it; with a creature hooked, F hauls.
9. **The Prophet:**
   - put Auratus in a chest 10 times, 2 s or more apart: each line appears in order, the 9th lets it stay, the 10th pulls it out and makes you glow;
   - try a shulker, a hopper and an item frame too;
   - spam-click it in: it's pulled out but the lines don't skip.

# Finale: dropping a mythic on its pedestal no longer duplicates it

## What I found
- **The cause:** a cancelled drop is given back to the player when the event ends. `Finale.onDrop` (normal priority) didn't check whether the drop was cancelled. It removed the dropped item and placed the gem, and the server then gave the gem back. With the default `protection.prevent-drop: true`, `GemListener.onDrop` (also normal priority) cancels every gem drop. So **every** pedestal drop duplicated, whichever of the two ran first.
- **The Q release had its own bug:** `AuratusGem.onRelease` checked that you were holding the Auratus, but Q takes the item out of your hand before the drop event fires. Q with the Auratus in your main hand never released the chain; it only worked with the Auratus in your off hand.
- **The timeout fallback** ("The Signal" pulling the gems) was already safe: it removes the gem first and only then places it. It could still run while a drop was being handled, so it now skips that gem.
- **Gems have no unique id** (only type, tier, energy and kind), so the fix stamps a one-time id onto the dropped gem.

## What changed
- **A pedestal drop is the finale's alone.** A merge gem dropped near its pedestal before it's placed: the Auratus Q release and the "gems can't be dropped" rule both step aside (`Finale.claimsDrop`).
- **The finale handles it at HIGHEST priority, cancelled or not:**
  1. It always cancels the drop, so the server gives the gem back however the other listeners acted.
  2. It stamps that gem with a one-time id.
  3. On the next tick it removes exactly the stack carrying that id, from any inventory slot (hotbar, storage, armour, off hand), the cursor, an open inventory or the ground.
  4. Only then does it put the gem on the pedestal. If it can't find the stamped gem, it doesn't place it.
- **The one exception: a full inventory.** If you drop from the cursor or the inventory screen with no free slot, the server has nowhere to give a cancelled drop back and deletes it. In that case (and only if the stamped gem is found nowhere), it's placed, so it isn't lost.
- **Spamming Q:** while a drop is being handled, more drops of that gem are just cancelled. A gem can't be placed twice (`place` refuses a second time).
- **A chain that's out when the Auratus goes on the pedestal** is put away, as Q would.
- **The Q release works from the main hand** again: the Auratus being dropped counts as held. Outside the finale, Q with a chain out still releases it, and the gem still can't be dropped.
- **Files:** Finale.java, AuratusGem.java, GemListener.java, new PedestalDrop.java, new FinaleDropTest.java.
- **Tested:** compiles against paper-api 1.21.11. 118 unit tests pass, 12 of them new (FinaleDropTest). They run a copy of Paper's drop flow with the real PedestalDrop logic:
  - in all 24 orders of the four drop listeners, with and without another plugin cancelling first or un-cancelling afterwards;
  - Auratus and Heretic, the off hand, any slot, a full inventory, spammed Q, too far from the pedestal, outside the finale, and the finale ending before the next tick.
- **Not tested in game.**

# Bloodlinking: the dive is one strong push the way you look

This replaces the steerable dive from the previous jar.

- **One push, no steering:** when the dive starts, your velocity is set once along your yaw and pitch. After that, gravity and drag take over, so turning mid-air does nothing.
- **Stronger:** `dive-speed` 1.3 → 2.0. It's in `/mythicconfig` as "Bloodlink Dive Push".
- **It always comes down:** if you look less than about 20° down (level or up), your yaw is kept and the push tilts 20° down (`dive-min-down: 0.35`). Straight up pushes straight down.
- **Before, it pushed toward the block you were looking at** (the same direction, unless you looked at the sky). Now it always uses your look direction.
- **It lands a little short of your crosshair:** one push can't fight air drag the whole way. From a full launch (about 17 blocks up) looking 45° down, the crosshair is about 17 blocks out. You land about 10 blocks out at 2.0, and about 7.5 at the old 1.3. Raise `dive-speed` for more reach.
- **Your server's config:** a `dive-speed` still on 1.3 becomes 2.0 once, at the next start. The `dive-follows-look` key from the previous jar is removed.
- **Unchanged:** contact slams, bounces (each bounce's dive is the same push), damage, radius, max slams, the 40-tick limit and no fall damage.
- **Files:** MythicListener.java (beginBloodlinkDive; steering removed; tuner entry), SoulboundGemsPlugin.java (balance version 4), config.yml, BloodlinkBounceTest.java (theDiveIsOnePushNotSteered, the dive-speed default), and HereticMath.java (diveDirection, from the previous change).
- **Tested:** compiles, 106 unit tests pass. **Not tested in game.**

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
