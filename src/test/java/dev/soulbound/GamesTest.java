package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Heretic's Games (events.heretic-games): rounds, points, curses, rests, roulette, the stash, the duel, kits. */
class GamesTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static String section(String s, String from, String to) {
        int a = s.indexOf(from);
        assertTrue(a >= 0, from);
        int b = s.indexOf(to, a + from.length());
        return s.substring(a, b < 0 ? s.length() : b);
    }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4), E = new UUID(0, 5);

    // ------------------------------------------------------------------ a round's placings and points

    @Test void roundPlacingsStandingFirstThenTheLastToFall() {
        List<UUID> order = GamesMath.roundOrder(List.of(A), List.of(D, C, B), Map.of(A, 12.0), Map.of());
        assertEquals(List.of(A, B, C, D), order, "the survivor, then whoever fell last");
        List<UUID> timeUp = GamesMath.roundOrder(List.of(A, B), List.of(C), Map.of(A, 6.0, B, 14.0), Map.of());
        assertEquals(List.of(B, A, C), timeUp, "time up: the standing players by health");
        List<UUID> tied = GamesMath.roundOrder(List.of(A, B), List.of(), Map.of(A, 10.0, B, 10.0), Map.of(A, 1, B, 3));
        assertEquals(List.of(B, A), tied, "same health: more kills this round");
    }

    @Test void placeAndSeasonPoints() {
        List<Integer> round = List.of(5, 3, 2), season = List.of(10, 6, 4);
        assertEquals(5, GamesMath.placePoints(round, 1));
        assertEquals(3, GamesMath.placePoints(round, 2));
        assertEquals(2, GamesMath.placePoints(round, 3));
        assertEquals(0, GamesMath.placePoints(round, 4), "fourth gets nothing for the place");
        assertEquals(0, GamesMath.placePoints(round, 0));
        assertEquals(10, GamesMath.seasonPoints(season, 1, 1));
        assertEquals(6, GamesMath.seasonPoints(season, 1, 2));
        assertEquals(4, GamesMath.seasonPoints(season, 1, 3));
        assertEquals(1, GamesMath.seasonPoints(season, 1, 4), "every other finisher");
        assertEquals(1, GamesMath.seasonPoints(season, 1, 12));
        YamlConfiguration c = config();
        assertEquals(List.of(5, 3, 2), c.getIntegerList("events.heretic-games.round-points"));
        assertEquals(List.of(10, 6, 4), c.getIntegerList("events.heretic-games.season-points"));
        assertEquals(1, c.getInt("events.heretic-games.kill-points"));
        assertEquals("heretic-games", c.getString("events.heretic-games.season-track"));
    }

    @Test void roundFlowInTheSource() throws Exception {
        String g = read("HereticGames.java");
        String end = section(g, "private void endRound() {", "/** Healed, fed and cleansed");
        assertTrue(end.contains("GamesMath.roundOrder(") && end.contains("GamesMath.placePoints(table, i + 1)"));
        assertTrue(end.contains("cleanUp(p);"), "kit items are taken back at the round's end");
        assertTrue(end.contains("afterRest = round < rounds() ? GamesMath.Phase.TITLE : GamesMath.Phase.DUEL_TITLE;"), "the last round leads to the Blood Duel");
        assertTrue(section(g, "private void roundSecond() {", "/** Placings").contains("if (alive.size() <= 1 || left <= 0) endRound();"), "one left, or time up");
        assertTrue(section(g, "private UUID credit(UUID victim) {", "/** Out for the round").contains("cfg().getInt(\"kill-points\", 1)"), "1 per kill");
        String fall = section(g, "private void fall(Player p) {", "/** A lethal hit never kills");
        assertTrue(fall.contains("p.setGameMode(GameMode.SPECTATOR);"), "out for the round: a spectator");
        assertTrue(section(g, "private void bounds() {", "/** Our own teleports").contains("GamesMath.inside(l.getX(), l.getY(), l.getZ(), arena.box, 0)"), "kept inside the arena");
        assertEquals(5, config().getInt("events.heretic-games.rounds"));
        assertEquals(180, config().getInt("events.heretic-games.round-seconds"));
        assertEquals(15, config().getInt("events.heretic-games.intermission-seconds"));
        assertEquals(4, config().getInt("events.heretic-games.min-players"));
    }

    // ------------------------------------------------------------------ rests: nothing hurts

    @Test void onlyRoundsAndTheDuelHurt() throws Exception {
        for (GamesMath.Phase p : GamesMath.Phase.values()) {
            boolean fight = p == GamesMath.Phase.ROUND || p == GamesMath.Phase.DUEL;
            assertEquals(fight, p.fighting(), p.name());
            if (p != GamesMath.Phase.IDLE) assertEquals(!fight, p.resting(), p.name());
        }
        assertTrue(GamesMath.Phase.INTERMISSION.resting() && GamesMath.Phase.TITLE.resting() && GamesMath.Phase.FINISH.resting());
        String g = read("HereticGames.java");
        String dmg = section(g, "public void onDamage(EntityDamageEvent e) {", "private static boolean hasTotem(");
        assertTrue(dmg.contains("if (!phase.fighting() || !alive.contains(p.getUniqueId())) { e.setCancelled(true); return; }"), "no damage of any kind between fights");
        assertTrue(dmg.contains("if (e.getFinalDamage() < p.getHealth()) return;") && dmg.contains("e.setCancelled(true);\n        fall(p);"), "a lethal hit never kills");
        String rest = section(g, "private void restSecond() {", "private static boolean isBad(");
        assertTrue(rest.contains("refresh(p);") && rest.contains("isBad(ef)"), "healed, fed, cleansed");
        assertTrue(g.contains("return phase.resting() ? \"heretic-games-rest\" : \"heretic-games\";"));
        YamlConfiguration c = config();
        assertEquals(List.of("teleport", "astral", "repurpose", "movement", "pockets"), c.getStringList("event-blocked-powers.heretic-games-rest"),
                "no gem power that moves anyone between fights");
        assertEquals(List.of("astral", "repurpose", "pockets"), c.getStringList("event-blocked-powers.heretic-games"), "no repurpose all tournament");
        String eb = read("EventBlock.java");
        assertTrue(eb.contains("return games != null && blocked(games, List.of(), c.getStringList(\"event-blocked-powers.\" + games), power);"), "'all' doesn't apply");
        assertTrue(EventBlock.blocked("heretic-games-rest", List.of(), List.of("movement"), "dash"));
        assertFalse(EventBlock.blocked("heretic-games", List.of(), List.of("astral", "repurpose"), "dash"), "dash is fine in a round");
        assertTrue(read("GemListener.java").contains("if (plugin.eventRefuses(p, \"pockets\")) return;"), "Pockets can't reach past the stash");
    }

    // ------------------------------------------------------------------ curses

    @Test void noCurseTwiceInARow() {
        List<String> all = new ArrayList<>(HereticCurses.NAMES.keySet());
        Random r = new Random(7);
        String last = null;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String next = GamesMath.nextCurse(all, last, r);
            assertNotEquals(last, next, "round " + i);
            seen.add(next);
            last = next;
        }
        assertEquals(new HashSet<>(all), seen, "every curse comes up");
        assertEquals("swap", GamesMath.nextCurse(List.of("swap"), "swap", r), "a lone curse can repeat");
        assertNull(GamesMath.nextCurse(List.of(), null, r));
        assertEquals(10, all.size());
    }

    @Test void everyCurseIsItsOwnClassAndConfigurable() throws Exception {
        YamlConfiguration c = config();
        for (String id : HereticCurses.NAMES.keySet()) {
            HereticCurses.Curse k = HereticCurses.make(id);
            assertNotNull(k, id);
            assertEquals(id, k.id);
            assertTrue(c.getBoolean("events.heretic-games.curses." + id + ".enabled"), id + " is in config.yml");
            assertFalse(k.hint().isBlank(), id);
        }
        assertEquals(c.getConfigurationSection("events.heretic-games.curses").getKeys(false), HereticCurses.NAMES.keySet());
        assertTrue(HereticCurses.make("one-shot").replacesKit(), "one shot ignores the kit");
        assertTrue(HereticCurses.make("no-sprint").noSprint());
        assertTrue(HereticCurses.make("reverse-knockback").reverseKnockback());
        assertTrue(HereticCurses.make("fists-only").noProjectiles());
        assertTrue(HereticCurses.make("bloodthirst").noRegen());
        String k = read("HereticCurses.java");
        assertTrue(k.contains("@Override void off(HereticGames g, Player p) {} // the lava stays for everyone else"), "one fall doesn't drain the lava");
        assertTrue(k.contains("if (!bl.getType().isAir() || g.plugin().land().owns(bl)) continue;"), "lava fills air only");
        assertTrue(k.contains("g.plugin().land().record(job, LandLedger.Phase.PIT, bl, Material.LAVA);") && k.contains("g.plugin().land().save(); // in the ledger"),
                "crash-safe: in the LandWorks ledger");
        assertTrue(k.contains("PotionEffectType.DARKNESS") && k.contains("GamesMath.heartbeatVolume(best, range)"));
        assertTrue(k.contains("PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, level - 1"), "Strength II");
        assertEquals(6, config().getInt("events.heretic-games.curses.rising-lava.rise-height"));
    }

    @Test void curseNumbers() {
        assertEquals(-1, GamesMath.lavaLayer(19, 20, 6));
        assertEquals(0, GamesMath.lavaLayer(20, 20, 6), "the bottom layer first");
        assertEquals(1, GamesMath.lavaLayer(40, 20, 6));
        assertEquals(5, GamesMath.lavaLayer(500, 20, 6), "never above rise-height");
        assertEquals(10.0, GamesMath.bloodthirst(10.0, 2000, 6000, 1.0, 1.0), "a recent hit: no bleed");
        assertEquals(9.0, GamesMath.bloodthirst(10.0, 7000, 6000, 1.0, 1.0));
        assertEquals(1.0, GamesMath.bloodthirst(1.5, 7000, 6000, 1.0, 1.0), "never below half a heart");
        assertEquals(0.0, GamesMath.heartbeatVolume(30, 24));
        assertTrue(GamesMath.heartbeatVolume(2, 24) > GamesMath.heartbeatVolume(20, 24), "louder closer");
        assertTrue(GamesMath.heartbeatNow(1, 3, 24) && !GamesMath.heartbeatNow(1, 20, 24) && GamesMath.heartbeatNow(2, 20, 24), "faster closer");
        Random r = new Random(3);
        for (int n = 2; n < 12; n++) for (int t = 0; t < 50; t++) {
            int[] to = GamesMath.derangement(n, r);
            Set<Integer> spots = new HashSet<>();
            for (int i = 0; i < n; i++) { assertNotEquals(i, to[i], "Swap: nobody stays put"); spots.add(to[i]); }
            assertEquals(n, spots.size(), "everyone gets a spot");
        }
    }

    // ------------------------------------------------------------------ Gem Roulette

    @Test void gemRouletteRestoresTheRealGemAndCooldowns() throws Exception {
        String g = read("HereticGames.java");
        String spin = section(g, "void roulette(Player p) {", "/** Their own gem back");
        assertTrue(spin.indexOf("saveStash(p.getUniqueId(), s); // on disk before the gem leaves") < spin.indexOf("inv.setItem(slot, g);"), "on disk first");
        assertTrue(spin.contains("plugin.listener().cooldownSnapshot(p.getUniqueId())"));
        assertTrue(spin.contains("if (standardGem(c[i]))"), "only the standard gem");
        assertTrue(g.contains("return plugin.gems().isGem(it) && !plugin.gems().isMythic(it) && !plugin.gems().isGold(it) && !isRoulette(it);"), "never the Gold Gem");
        String back = section(g, "void rouletteRestore(Player p) {", "// ------");
        assertTrue(back.contains("if (isRoulette(c[i])) { inv.setItem(i, null);") && back.contains("plugin.listener().restoreCooldowns(p.getUniqueId(), s.cooldowns)"));
        assertTrue(read("HereticCurses.java").contains("@Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) g.rouletteRestore(p); }"));
        String gl = read("GemListener.java");
        assertTrue(gl.contains("Map<String, Long> cooldownSnapshot(UUID id) {") && gl.contains("void restoreCooldowns(UUID id, Map<String, Long> snapshot) {"));
        String restore = section(g, "private void restore(Player p, boolean teleport) {", "/** Whatever the tournament");
        assertTrue(restore.contains("if (s.realGem != null) {"), "a crash mid-roulette: the real gem comes back from the stash file");
    }

    // ------------------------------------------------------------------ the stash

    @Test void theWholeInventoryIsStashedAndComesBack() throws Exception {
        String g = read("HereticGames.java");
        String join = section(g, "String join(Player p) {", "/** What stays with you");
        assertTrue(join.indexOf("if (!stash(p))") < join.indexOf("inv.setItem(i, null)"), "saved to disk before anything is taken");
        assertTrue(g.contains("private boolean kept(ItemStack it) { return plugin.gems().isGem(it) && !plugin.gems().isMythic(it); }"),
                "the standard gem and the Gold Gem stay, mythics are stashed");
        String restore = section(g, "private void restore(Player p, boolean teleport) {", "/** Whatever the tournament");
        assertTrue(restore.indexOf("cleanUp(p);") < restore.indexOf("inv.clear();"), "kit items and roulette gems out first");
        for (String s : List.of("p.setGameMode(s.mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : s.mode);", "p.addPotionEffect(ef)", "p.setLevel(s.level);",
                "if (f.exists() && !f.delete())")) assertTrue(restore.contains(s), s);
        assertTrue(section(g, "private void drop(Player p, String why) {", "// ------").contains("restore(p, true);"), "leave and logout");
        assertTrue(section(g, "void cancel(String why) {", "private void reset()").contains("restore(p, true);"), "stop");
        assertTrue(section(g, "private void end() {", "/** Stopped").contains("restore(p, true);"), "the end");
        assertTrue(section(g, "public void onJoin(PlayerJoinEvent e) {", "}\n}").contains("if (stashFile(p.getUniqueId()).exists()) {"), "a crash: on the next join");
        String start = section(g, "void start() {", "void shutdown()");
        assertTrue(start.contains("cancelled") && start.contains("restore(p, true)"), "a restart cancels it cleanly");
        assertTrue(read("SoulboundGemsPlugin.java").contains("if (games != null) games.shutdown();"));
        String c = section(g, "private void cleanUp(Player p) {", "private void writeLive()");
        assertTrue(c.contains("GamesKits.isKitItem(c[i]) || isRoulette(c[i])") && c.contains("HereticCurses.clearModifiers(p);"), "nothing lingers");
    }

    @Test void inASoulboundEvent() throws Exception {
        String m = read("SoulboundGemsPlugin.java");
        assertTrue(m.contains("|| revenant != null && revenant.inBossFight(p) || games != null && games.playing(p);"), "no Pantheon kill points");
        assertTrue(m.contains("boolean inPantheonEvent(Player p) { return pantheon != null && pantheon.inEvent(p) || games != null && games.playing(p); }"),
                "no energy loss, no gem drops, no Gold Gem release, no Revenant shatter (the death handlers check it)");
        String g = read("HereticGames.java");
        String death = section(g, "public void onDeath(PlayerDeathEvent e) {", "@EventHandler");
        assertTrue(death.contains("e.setKeepInventory(true);") && death.contains("e.getDrops().clear();"), "nothing drops");
        assertTrue(section(g, "String startGames(boolean force) {", "/** Why there's already").contains("String h = hereticExists();"), "refused while a Heretic exists");
    }

    // ------------------------------------------------------------------ the Blood Duel

    @Test void bloodDuelTieBreak() {
        Map<UUID, Integer> pts = new HashMap<>(Map.of(A, 20, B, 14, C, 14, D, 3)), kills = new HashMap<>(Map.of(A, 1, B, 2, C, 5, D, 0));
        assertEquals(List.of(A, C), GamesMath.duelPair(List.of(A, B, C, D), pts, kills, new Random(1)), "tied for second: more kills");
        kills.put(B, 5);
        Set<UUID> seconds = new HashSet<>();
        Random one = new Random(42); // the games keep one Random, as here
        for (int s = 0; s < 200; s++) {
            List<UUID> pair = GamesMath.duelPair(List.of(A, B, C, D), pts, kills, one);
            assertEquals(A, pair.get(0), "first is never left to chance");
            seconds.add(pair.get(1));
        }
        assertEquals(Set.of(B, C), seconds, "a true tie: a coin flip");
        assertEquals(List.of(A), GamesMath.duelPair(List.of(A), pts, kills, new Random(1)));
        assertEquals(B, GamesMath.duelTimeout(A, 4, B, 9, pts, new Random(1)), "time up: more health");
        assertEquals(A, GamesMath.duelTimeout(A, 9, D, 9, pts, new Random(1)), "same health: more points");
        assertEquals(List.of(C, A, E, D), GamesMath.finalOrder(C, A, List.of(A, E, D)), "the duel's winner and loser, then the rest");
    }

    @Test void theFinish() throws Exception {
        String g = read("HereticGames.java");
        String fin = section(g, "private void finish(UUID w, UUID loser) {", "private void finishTick()");
        assertTrue(fin.contains("SporeFountain.start(plugin, liftFrom, 3.0,"), "the blood fountain, visuals only");
        assertTrue(fin.contains("has been chosen by the Heretic") && fin.contains("plugin.shownNameComponent(wp)"));
        assertTrue(fin.contains("for (Player o : Bukkit.getOnlinePlayers()) o.showTitle(t);"), "for everyone");
        String tick = section(g, "private void finishTick() {", "/** The end:");
        assertTrue(tick.contains("cfg().getDouble(\"finish-lift\", 3.0)") && tick.contains("\"heretic-games.heartbeat\""), "lifted 3 blocks; a heartbeat for everyone");
        String end = section(g, "private void end() {", "/** Stopped");
        assertTrue(end.indexOf("restore(p, true)") < end.indexOf("createMythic(GemType.HERETIC)"), "inventories back first, then the Heretic in their hand");
        assertTrue(end.contains("plugin.pantheon().awardEventPoints(order.get(i), track, sp)"), "season points through the Pantheon link");
        assertTrue(read("PantheonLink.java").contains("call(\"awardEventPoints\", new Class<?>[] {UUID.class, String.class, int.class}, id, track, points);"));
        assertTrue(read("SporeFountain.java").contains("void stop() { if (task != null) task.cancel(); }"));
    }

    // ------------------------------------------------------------------ the arena, the kits

    @Test void arenaMath() {
        int[] box = GamesMath.box(10, 70, 10, 0, 60, 0);
        assertArrayEquals(new int[] {0, 60, 0, 10, 70, 10}, box);
        assertTrue(GamesMath.inside(5.5, 61, 5.5, box, 0));
        assertTrue(GamesMath.inside(10.9, 70.9, 10.9, box, 0), "the top corner block counts");
        assertFalse(GamesMath.inside(11.1, 65, 5, box, 0));
        assertArrayEquals(new double[] {-1.5, 5.0}, GamesMath.pushOut(0.5, 5.0, box, 1.5), "out the nearest side");
        assertArrayEquals(new double[] {5.0, 12.5}, GamesMath.pushOut(5.0, 10.8, box, 1.5));
        assertFalse(GamesMath.inside(-1.5, 65, 5, box, 0.5), "pushed out lands outside the margin");
        assertEquals("2:05", GamesMath.clock(125));
        assertEquals("0:00", GamesMath.clock(-3));
    }

    @Test void theEditorIsLaidOutLikeAnInventory() {
        assertEquals(GamesMath.HELMET, GamesMath.editorToInventory(0));
        assertEquals(GamesMath.CHEST, GamesMath.editorToInventory(1));
        assertEquals(GamesMath.LEGS, GamesMath.editorToInventory(2));
        assertEquals(GamesMath.BOOTS, GamesMath.editorToInventory(3));
        assertEquals(GamesMath.OFFHAND, GamesMath.editorToInventory(4));
        assertEquals(0, GamesMath.editorToInventory(36), "the hotbar is the fifth row");
        assertEquals(9, GamesMath.editorToInventory(9), "the main rows sit where they do in your inventory");
        for (int s : new int[] {5, 6, 7, 8, 45, 49, 53}) assertEquals(-1, GamesMath.editorToInventory(s), "a button: " + s);
        Set<Integer> covered = new HashSet<>();
        for (int s = 0; s < 54; s++) {
            int i = GamesMath.editorToInventory(s);
            if (i < 0) continue;
            assertEquals(s, GamesMath.inventoryToEditor(i), "round trip " + s);
            covered.add(i);
        }
        assertEquals(41, covered.size(), "every inventory slot: 36 + armour + offhand");
        assertEquals("my-kit_2", GamesMath.kitId("  My Kit_2!! "));
        assertNull(GamesMath.kitId("!!!"));
        assertEquals(16, GamesMath.kitId("abcdefghijklmnopqrstuvwxyz").length());
    }

    @Test void kitMenuHoldsCopiesOnly() throws Exception {
        String k = read("GamesKits.java");
        String click = section(k, "private void editClick(Player p, EditMenu h, InventoryClickEvent e) {", "private void button(");
        assertTrue(click.contains("if (raw < 0 || raw >= top) { // your own inventory, or outside the window\n            e.setCancelled(true);"), "your inventory never changes");
        assertTrue(click.contains("p.setItemOnCursor(cursor != null && !cursor.getType().isAir() || it == null ? null : it.clone());"), "a copy on the cursor");
        assertTrue(click.contains("default -> e.setCancelled(true); // double-click collect, drops, the offhand key"));
        assertTrue(section(k, "public void onClose(InventoryCloseEvent e) {", "@EventHandler").contains("p.setItemOnCursor(null);"), "the cursor is cleared on close");
        assertTrue(section(k, "public void onDrag(InventoryDragEvent e) {", "/** The cursor").contains("if (raw >= top || GamesMath.editorToInventory(raw) < 0) { e.setCancelled(true); return; }"));
        assertTrue(k.contains("if (e.getClick() == ClickType.SHIFT_RIGHT) { openConfirm(p, k.id); return; }"), "shift-right-click deletes, after asking");
        assertTrue(k.contains("if (e.isRightClick()) { k.enabled = !k.enabled; save(); openList(p); return; }"), "right-click turns it on or off");
        assertTrue(k.contains("naming.put(p.getUniqueId(), System.currentTimeMillis());"), "new kit: a name in chat");
        assertTrue(k.contains("if (!file.exists()) { defaults(); save(); } // the six kits, written once"));
        for (String id : List.of("\"archer\"", "\"trident\"", "\"mace\"", "\"axe\"", "\"naked\"", "\"sword\"")) assertTrue(k.contains("put(" + id), id);
        assertTrue(k.contains("trident.addEnchantment(Enchantment.RIPTIDE, 3);"));
        assertTrue(GemListener.class.getDeclaredMethod("pluginMenu", org.bukkit.inventory.Inventory.class) != null);
    }

    @Test void kitItemsGetTheRuleExceptionsAndNeverLeave() throws Exception {
        assertTrue(read("MaceRules.java").contains("if (GamesKits.isKitItem(it)) { capEnchants(it); continue; } // a Heretic's Games kit mace: capped, never counted or tracked"));
        assertTrue(read("CombatRules.java").contains("&& !GamesKits.isKitItem(e.getItem())) // a Heretic's Games kit trident: no fight cooldown"));
        String g = read("HereticGames.java");
        String rip = section(g, "public void onRiptide(io.papermc.paper.event.player.PlayerStopUsingItemEvent e) {", "// ------");
        assertTrue(rip.contains("p.startRiptideAttack(20, 8.0f, it);") && rip.contains("p.isInWaterOrRain()"), "riptide anywhere");
        assertTrue(g.contains("public void onDrop(PlayerDropItemEvent e) { if (playing(e.getPlayer())) e.setCancelled(true); }"));
        assertTrue(g.contains("if (GamesKits.isKitItem(it) || isRoulette(it)) { e.setCancelled(true); e.getItem().remove(); }"), "nobody else keeps one");
        assertTrue(section(g, "public void onOpen(InventoryOpenEvent e) {", "@EventHandler").contains("e.setCancelled(true);"), "no chests mid-tournament");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        assertEquals("true", y.getString("permissions.soulbound.hgames.play.default"));
        assertEquals("op", y.getString("permissions.soulbound.hgames.admin.default"));
        assertNotNull(y.getString("commands.hgames.usage"));
        for (String s : List.of("open", "round", "fall", "heartbeat", "lava", "swap", "roulette", "duel"))
            assertNotNull(config().getString("sounds.heretic-games." + s + ".event"), s);
    }
}
