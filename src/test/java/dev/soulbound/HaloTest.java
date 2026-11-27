package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Lost Halo (events.halo): fragment ids and copies, finds, the hidden holder, the claim, drops, reliquaries. */
class HaloTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static String section(String s, String from, String to) {
        int a = s.indexOf(from);
        assertTrue(a >= 0, from);
        int b = s.indexOf(to, a + from.length());
        return s.substring(a, b < 0 ? s.length() : b);
    }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    // ------------------------------------------------------------------ ids and copies

    @Test void onlyThisHuntsRegisteredFragmentsCount() {
        Map<String, Integer> reg = Map.of("aaa", 1, "bbb", 2);
        assertTrue(HaloMath.valid(new HaloMath.Fragment(1, "aaa", 3), reg, 3));
        assertFalse(HaloMath.valid(new HaloMath.Fragment(1, "aaa", 2), reg, 3), "an older hunt's fragment is void");
        assertFalse(HaloMath.valid(new HaloMath.Fragment(2, "aaa", 3), reg, 3), "the number has to match its id");
        assertFalse(HaloMath.valid(new HaloMath.Fragment(1, "zzz", 3), reg, 3), "never registered (a forged item)");
        assertFalse(HaloMath.valid(null, reg, 3));
        assertFalse(HaloMath.valid(new HaloMath.Fragment(1, null, 3), reg, 3));
    }

    @Test void copiesAreDeletedAndCountOnce() {
        Map<String, Integer> reg = Map.of("aaa", 1, "bbb", 2, "ccc", 3);
        List<HaloMath.Fragment> held = List.of(new HaloMath.Fragment(1, "aaa", 1), new HaloMath.Fragment(1, "aaa", 1),
                new HaloMath.Fragment(2, "bbb", 1), new HaloMath.Fragment(3, "ccc", 0), new HaloMath.Fragment(3, "ccc", 1));
        assertEquals(List.of(1, 3), HaloMath.toDelete(held, reg, 1, Set.of()), "the second copy and the void one go");
        assertEquals(Set.of("aaa", "bbb", "ccc"), HaloMath.distinct(held, reg, 1), "a copy counts once");
        assertEquals(List.of(1, 2, 3), HaloMath.toDelete(held, reg, 1, Set.of("bbb")), "someone else online really has bbb: this one is a copy");
    }

    @Test void newIdsNeverRepeat() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 2000; i++) assertTrue(ids.add(HaloMath.newId()));
    }

    // ------------------------------------------------------------------ finds, the claim

    @Test void aFindIsAnnouncedOnceAndOnlyWhileHunting() {
        Set<String> found = new LinkedHashSet<>();
        assertFalse(HaloMath.newlyFound(false, found, "aaa"), "not before the hunt starts");
        assertTrue(found.isEmpty());
        assertTrue(HaloMath.newlyFound(true, found, "aaa"));
        assertFalse(HaloMath.newlyFound(true, found, "aaa"), "picking it up again is no new find");
        assertTrue(HaloMath.newlyFound(true, found, "bbb"));
        assertEquals("(2/5)", HaloMath.count(found.size(), 5));
    }

    @Test void threeDistinctFragmentsClaim() {
        assertFalse(HaloMath.claims(true, 2, 3));
        assertTrue(HaloMath.claims(true, 3, 3));
        assertFalse(HaloMath.claims(false, 5, 3), "no claim without a running hunt");
        assertTrue(HaloMath.claims(true, 1, 0), "needed is at least 1");
        assertEquals(3, config().getInt("events.halo.fragments-needed"));
    }

    @Test void theFindNeverNamesTheHolder() throws Exception {
        String h = read("HaloHunt.java");
        String announce = section(h, "if (HaloMath.newlyFound(active, found, id)) {", "if (changed) save();");
        assertFalse(announce.contains("getName()") || announce.contains("shownName"), "the find message never says who");
        assertTrue(announce.contains("HaloMath.count(found.size(), registered.size())"));
        assertTrue(read("HaloCommand.java").contains("if (!sender.hasPermission(HaloHunt.ADMIN))"), "only admins see /halo status");
        assertTrue(h.contains("if (p.getGameMode() == GameMode.CREATIVE) continue; // an admin handling them never \"finds\" one"));
    }

    @Test void theClaimScene() throws Exception {
        String h = read("HaloHunt.java");
        String claim = section(h, "private void startClaim(Player p) {", "private void finishClaim(boolean aborted) {");
        assertTrue(claim.contains("bells();") && claim.contains("plugin.gold().flashAll("), "bells and the gold flash for everyone");
        assertTrue(claim.contains("plugin.shownName(p)"), "the title scrambles an invisible name");
        assertTrue(claim.contains("AuratusGem.hangingChain(") && claim.contains("for (int i = 0; i < 4; i++)"), "four chains");
        assertTrue(claim.contains("if (h.getLocation().distanceSquared(c.spot) > 0.04) h.teleport(c.spot); // held in place"));
        assertFalse(claim.contains("setInvulnerable"), "no Invulnerable flag a crash could leave on");
        assertTrue(h.contains("else if (claim != null && e.getEntity().getUniqueId().equals(claim.player)) e.setCancelled(true);"), "unhittable");
        String finish = section(h, "private void finishClaim(boolean aborted) {", "boolean active()");
        assertTrue(finish.contains("plugin.gems().createMythic(GemType.AURATUS)") && finish.contains("awarded = true;") && finish.contains("endHunt();"));
        assertTrue(section(h, "private void endHunt() {", "private void purge()").contains("generation++"), "every other fragment is void");
        assertTrue(h.contains("if (claim != null) finishClaim(false); // a restart mid-scene"), "a restart never loses the Auratus");
    }

    // ------------------------------------------------------------------ guards, drops, the ground

    @Test void fragmentsCantBeKeptSafe() throws Exception {
        String h = read("HaloHunt.java");
        assertTrue(h.contains("public void onDrop(PlayerDropItemEvent e)") && section(h, "public void onDrop(", "}").contains("e.setCancelled(true);"));
        assertTrue(h.contains("boolean bundle = isBundle(cursor) && isFragment(cur) || isBundle(cur) && isFragment(cursor);"));
        assertTrue(h.contains("boolean guarded = GemListener.storage(top) && !mayStore(p) || GemListener.workstation(top);"), "containers and workstations");
        assertTrue(h.contains("return p.hasPermission(ADMIN) && (!active || p.getGameMode() == GameMode.CREATIVE);"), "only admins hide them");
        for (String s : List.of("onHopper(InventoryPickupItemEvent", "onHopperMove(InventoryMoveItemEvent", "onToEntity(", "onToStand(", "onToBlock(",
                "onCraft(", "onSmith(", "onAnvil(", "onStoreDrag(")) assertTrue(h.contains(s), s);
        assertTrue(read("GemListener.java").contains("m == Material.DECORATED_POT || m.name().endsWith(\"_SHELF\")"), "shelves take items from your hand too");
        assertTrue(read("HereticGames.java").contains("if (HaloHunt.isFragment(it)) return \"You can't bring a Halo fragment.\";"), "not into the tournament's stash");
        assertTrue(read("GamesKits.java").contains("plugin.gems().isGem(it) || HaloHunt.isFragment(it)"), "never into a kit");
    }

    @Test void deathAndLogoutDropThemProtected() throws Exception {
        String h = read("HaloHunt.java");
        assertTrue(section(h, "public void onDeath(PlayerDeathEvent e) {", "@EventHandler").contains("dropAll(p, p.getLocation());"), "kept inventories drop them too");
        assertTrue(section(h, "public void onQuit(PlayerQuitEvent e) {", "private void dropAll").contains("dropAll(p, p.getLocation());"));
        String protect = section(h, "private void protect(Item it) {", "@EventHandler");
        assertTrue(protect.contains("it.setUnlimitedLifetime(true);") && protect.contains("it.setInvulnerable(true);"));
        assertTrue(h.contains("m.setDamageResistant(org.bukkit.tag.DamageTypeTags.IS_FIRE);"), "lava and fire: it floats like netherite");
        assertTrue(h.contains("e.getCause() != EntityRemoveEvent.Cause.OUT_OF_WORLD") && h.contains("w.dropItem(w.getSpawnLocation(), copy)"), "the void: world spawn");
        assertTrue(h.contains("HaloMath.inVoid(it.getLocation().getY(), w.getMinHeight())"));
        assertTrue(HaloMath.inVoid(-69, -64) && !HaloMath.inVoid(-60, -64));
        assertTrue(h.contains("public void onDespawn(ItemDespawnEvent e)"));
        assertFalse(HaloMath.beam(59 * 20, 60));
        assertTrue(HaloMath.beam(60 * 20, 60), "a faint beam only after a minute on the ground");
        assertTrue(protect.contains("it.setGlowing(false);"), "no glow");
    }

    // ------------------------------------------------------------------ reliquaries

    @Test void reliquariesAreSavedRebuiltAndNeverDoubled() throws Exception {
        String h = read("HaloHunt.java");
        String spawn = section(h, "private void spawn(Reliquary r) {", "private static void setUp(");
        assertTrue(spawn.contains("|| spawned(r)) return;") && spawn.contains("despawn(r); // anything half there goes first"), "never two of anything");
        assertTrue(h.contains("d.setPersistent(false); // never saved with the chunk"), "rebuilt from halo.yml, not the chunk");
        assertTrue(spawn.contains("i.setPersistent(false);"), "the click box too");
        assertTrue(spawn.contains("new NamespacedKey(\"blissgems\", \"halo/reliquary\")"));
        assertTrue(spawn.contains("new Vector3f(0.7f, 0.7f, 0.7f)"), "the fragment at about 0.7");
        assertTrue(h.contains("for (Reliquary r : reliquaries.values()) spawn(r); // a restart"), "rebuilt on enable");
        assertTrue(section(h, "public void onChunk(ChunkLoadEvent e) {", "@EventHandler").contains("spawn(r);"), "and as chunks load");
        String save = section(h, "private void save() {", "// ------");
        assertTrue(save.contains("\"reliquaries.\" + r.n + \".\"") && save.contains("y.set(k + \"x\", r.x)"), "saved");
        String take = section(h, "public void onTake(PlayerInteractEntityEvent e) {", "/** The shrine's barrier");
        assertTrue(take.contains("removeReliquary(r, false);") && take.contains("\"halo.chime\"") && take.contains("\"halo.crumble\""));
        assertTrue(take.contains("Material.CHISELED_QUARTZ_BLOCK") && take.contains("Material.GOLD_BLOCK"), "gold and quartz crumbling");
        String anim = section(h, "private void animate(Reliquary r) {", "/** Right-clicking");
        assertTrue(anim.contains("Particle.WAX_OFF") && anim.contains("Particle.END_ROD") && anim.contains("cfgI(\"sparkle-range\", 8)"));
        assertFalse(anim.contains("setGlowing"), "no glow, no beam");
        for (String s : List.of("onBreak(BlockBreakEvent", "onPush(", "onPull(", "onBlast(", "onBlockBlast(")) assertTrue(h.contains(s), "unbreakable, unmovable: " + s);
        assertTrue(read("SoulboundGemsPlugin.java").contains("if (halo != null) halo.shutdown();"));
    }

    @Test void startRefusesWhileAnAuratusExists() throws Exception {
        String h = read("HaloHunt.java");
        String why = section(h, "private String auratusExists() {", "private boolean isAuratus(");
        assertTrue(why.contains("if (awarded)") && why.contains("getEnderChest()") && why.contains("getEntitiesByClass(Item.class)"));
        assertTrue(section(h, "String startHunt(boolean force) {", "/** Why /halo start").contains("if (!force) {"));
        assertTrue(h.contains("overlay().fadeOut(p, cfg(\"sky-style\", \"gold\"), 4, cfgI(\"sky-ticks\", 60))"), "the golden sky");
    }

    @Test void configCommandAndSounds() throws Exception {
        YamlConfiguration c = config();
        assertTrue(c.getBoolean("events.halo.enabled"));
        assertEquals(60, c.getInt("events.halo.beam-after-seconds"));
        assertEquals(8, c.getInt("events.halo.sparkle-range"));
        for (String s : List.of("bell", "found", "chime", "crumble")) assertNotNull(c.getString("sounds.halo." + s + ".event"), s);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        assertEquals("soulbound.halo.admin", y.getString("commands.halo.permission"));
        assertEquals("op", y.getString("permissions.soulbound.halo.admin.default"));
    }
}
