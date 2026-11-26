package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Gold Gem: sockets, marks, the repurpose, the release, the cooldown cap, the ritual and Snap. */
class GoldTest {
    private static final UUID HOLDER = UUID.randomUUID(), MESSY = UUID.randomUUID(), ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static GoldMath.State lifeCore() { return new GoldMath.State(HOLDER, "Holder", "LIFE", "core-bytes"); }

    // ------------------------------------------------------------------ the look

    @Test void thePacksLooks() {
        assertEquals("gold/gold_gem_01010000", GoldMath.model(List.of("LIFE", "FIRE"), false), "core Life plus Fire");
        assertEquals("gold/gold_gem_00010000", GoldMath.model(List.of("LIFE"), false), "core only");
        assertEquals("gold/gold_gem_11111111", GoldMath.model(GoldMath.ORDER, false));
        assertEquals("gold/gold_gem_awakened", GoldMath.model(GoldMath.ORDER, true));
    }

    // ------------------------------------------------------------------ sockets

    @Test void oneOfEachTypeEightInAll() {
        GoldMath.State s = lifeCore();
        assertFalse(s.canSocket("LIFE"), "not the core's type");
        assertEquals(GoldMath.Taken.SOCKETED, s.take("FIRE", MESSY, "Messy", "fire"));
        assertFalse(s.canSocket("FIRE"), "not a type already held");
        assertEquals(GoldMath.Taken.HELD, s.take("FIRE", ALEX, "Alex", "fire2"), "a duplicate is held (destroyed for now), not socketed");
        assertEquals(List.of("LIFE", "FIRE"), s.types());
        for (String t : List.of("ASTRA", "FLUX", "PUFF", "SPEED", "STRENGTH", "WEALTH")) assertEquals(GoldMath.Taken.SOCKETED, s.take(t, UUID.randomUUID(), "x", t));
        assertEquals(8, s.types().size());
        assertTrue(s.full());
        assertFalse(s.canSocket("NONSENSE"));
        assertFalse(s.canSocket("AURATUS"), "mythics never go in");
    }

    @Test void theCycleAndGoldMode() {
        GoldMath.State s = lifeCore();
        assertEquals("LIFE", s.active);
        assertEquals("LIFE", s.next(), "the core alone");
        s.take("FIRE", MESSY, "Messy", "fire");
        s.take("ASTRA", ALEX, "Alex", "astra");
        assertEquals(List.of("LIFE", "ASTRA", "FIRE"), s.cycle(), "the core first, then the pack's order");
        assertEquals("ASTRA", s.next());
        s.active = "FIRE";
        assertEquals("LIFE", s.next(), "round again");
        s.awakened = true;
        assertEquals("GOLD", s.next(), "awakened: GOLD joins the cycle");
        s.active = "GOLD";
        assertTrue(s.goldMode());
    }

    // ------------------------------------------------------------------ marks

    @Test void markRules() {
        GoldMath.State s = lifeCore();
        assertEquals(GoldMath.MarkResult.NOT_TIER_2, GoldMath.canMark(s, MESSY, 1, 3), "Tier 1: refused");
        assertEquals(GoldMath.MarkResult.NOT_TIER_2, GoldMath.canMark(s, MESSY, 0, 3), "no gem: refused");
        assertEquals(GoldMath.MarkResult.OK, GoldMath.canMark(s, MESSY, 2, 3));
        assertEquals(GoldMath.MarkResult.SELF, GoldMath.canMark(s, HOLDER, 2, 3));
        s.marks.put(MESSY, 0L);
        assertEquals(GoldMath.MarkResult.ALREADY, GoldMath.canMark(s, MESSY, 2, 3));
        s.marks.put(ALEX, 0L);
        s.marks.put(SAM, 0L);
        assertEquals(GoldMath.MarkResult.FULL, GoldMath.canMark(s, UUID.randomUUID(), 2, 3), "3 slots");
        long lock = 30 * 60_000L;
        assertEquals(lock - 1000, GoldMath.unmarkWait(0, 1000, lock), "can't come off for 30 minutes");
        assertEquals(0, GoldMath.unmarkWait(0, lock, lock));
    }

    // ------------------------------------------------------------------ the repurpose

    @Test void theRepurposeTakesTheGemAndNoNewOneComes() throws Exception {
        assertTrue(GoldMath.credited(true, 0, 10_000, 10_000), "the killing blow itself");
        assertTrue(GoldMath.credited(false, 5_000, 14_000, 10_000), "anything within 10 s of the holder's hit");
        assertFalse(GoldMath.credited(false, 1_000, 14_000, 10_000), "longer ago: a normal death");
        assertFalse(GoldMath.credited(false, 0, 14_000, 10_000));
        GoldMath.State s = lifeCore();
        s.take("FIRE", MESSY, "Messy", "messy-fire");
        assertTrue(s.hasGemOf(MESSY), "Messy's gem is in the Gold Gem: no new one");
        assertFalse(s.hasGemOf(ALEX));
        String g = read("GoldGem.java");
        assertTrue(g.contains("v.getInventory().setItem(slot, null); } // torn out"), "the gem leaves their inventory, so it can't drop or come back on respawn");
        assertTrue(g.contains("v.setHealth(0); // a normal death"), "then they die normally");
        assertTrue(g.contains("e.setCancelled(true); return; } // effectively dead"), "nothing reaches them during it");
        assertTrue(g.contains("public void onSceneTotem(EntityResurrectEvent e)"), "no totem saves them");
        assertTrue(g.contains("if (sc != null) finish(sc, true);"), "logging out finishes it");
        assertTrue(read("StarterGem.java").contains("given.contains(p.getUniqueId())"), "no starter gem a second time");
    }

    // ------------------------------------------------------------------ the release

    @Test void theHolderDyingSendsEveryGemHome() throws Exception {
        GoldMath.State s = lifeCore();
        s.take("FIRE", MESSY, "Messy", "messy-fire");
        s.take("FIRE", ALEX, "Alex", "alex-fire"); // held
        s.take("ASTRA", SAM, "Sam", "sam-astra");
        s.awakened = true;
        s.active = "GOLD";
        s.marks.put(UUID.randomUUID(), 0L);
        List<GoldMath.Socket> back = s.release(false);
        assertEquals(3, back.size(), "socketed and held, every one");
        Set<UUID> owners = new HashSet<>();
        for (GoldMath.Socket b : back) owners.add(b.owner());
        assertEquals(Set.of(MESSY, ALEX, SAM), owners);
        assertEquals(List.of("LIFE"), s.types(), "core only");
        assertFalse(s.awakened, "no longer awakened");
        assertEquals("LIFE", s.active);
        assertTrue(s.marks.isEmpty(), "marks go (keep-marks-on-death false)");
        assertFalse(s.hasGemOf(MESSY));
        GoldMath.State k = lifeCore();
        k.marks.put(MESSY, 0L);
        k.release(true);
        assertFalse(k.marks.isEmpty(), "keep-marks-on-death true keeps them");
        String g = read("GoldGem.java");
        assertTrue(g.contains("} else pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);"), "an offline owner gets it when they next join");
        assertTrue(g.contains("List<String> mine = pending.remove(p.getUniqueId());"));
    }

    // ------------------------------------------------------------------ cooldowns, the ritual, Snap

    @Test void theCooldownCap() throws Exception {
        assertEquals(170_000, GoldMath.cap(240_000, 170_000), "Wealth's 240 s Amplification: 170 on the Gold Gem");
        assertEquals(30_000, GoldMath.cap(30_000, 170_000), "shorter ones are untouched");
        assertEquals(240_000, GoldMath.cap(240_000, 0), "0: no cap");
        String gl = read("GemListener.java");
        assertTrue(gl.contains("System.currentTimeMillis() + capped(p, durationMs)"), "every cooldown goes through the cap");
        assertFalse(gl.contains("currentTimeMillis() + cooldownMs("), "none set around it");
    }

    @Test void theRitual() {
        assertEquals(GoldMath.Ritual.GOING, GoldMath.ritual(true, true, 3, 6, 100, 600));
        assertEquals(GoldMath.Ritual.DONE, GoldMath.ritual(true, true, 3, 6, 600, 600), "30 s: awakened");
        assertEquals(GoldMath.Ritual.CANCELLED, GoldMath.ritual(true, true, 7, 6, 100, 600), "leaving the site");
        assertEquals(GoldMath.Ritual.CANCELLED, GoldMath.ritual(true, false, 3, 6, 100, 600), "dying");
        assertEquals(GoldMath.Ritual.CANCELLED, GoldMath.ritual(false, true, 3, 6, 100, 600), "logging out");
    }

    @Test void snapPicksThreeRandomNeverTheHolder() {
        List<UUID> online = new java.util.ArrayList<>();
        online.add(HOLDER);
        for (int i = 0; i < 10; i++) online.add(UUID.randomUUID());
        Random r = new Random(3);
        Set<UUID> seen = new HashSet<>();
        for (int run = 0; run < 200; run++) {
            List<UUID> t = GoldMath.snapTargets(online, HOLDER, Set.of(), 3, r);
            assertEquals(3, t.size());
            assertFalse(t.contains(HOLDER), "never the holder");
            assertEquals(3, new HashSet<>(t).size(), "three different players");
            seen.addAll(t);
        }
        assertEquals(10, seen.size(), "random: everyone else gets picked sometimes");
        assertEquals(1, GoldMath.snapTargets(List.of(HOLDER, MESSY), HOLDER, Set.of(), 3, r).size(), "fewer online: as many as there are");
        assertTrue(GoldMath.snapTargets(List.of(HOLDER, MESSY), HOLDER, Set.of(MESSY), 3, r).isEmpty(), "exclude-trusted");
    }

    // ------------------------------------------------------------------ the wiring

    @Test void itsWiredIn() throws Exception {
        String gl = read("GemListener.java"), gm = read("GemManager.java"), plugin = read("SoulboundGemsPlugin.java");
        assertTrue(gl.contains("plugin.gold().handleInteract(e)"));
        assertTrue(gl.contains("plugin.gold().hud(p, gem)"));
        assertTrue(gm.contains("if (!isGem(item) || isMythic(item) || isGold(item)) return; // the Gold Gem runs at full energy"));
        assertTrue(gm.contains("if (!isGem(item) || isGold(item)) return; // the Gold Gem keeps its item_model"));
        assertTrue(plugin.contains("gold = new GoldGem(this)") && plugin.contains("gold.shutdown()"));
        assertTrue(Files.readString(Path.of("src/main/resources/plugin.yml")).contains("\n  goldgem:\n"));
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(1, c.getInt("gold.max-on-server"));
        assertEquals(3, c.getInt("gold.marks.slots"));
        assertEquals(30, c.getInt("gold.marks.lock-minutes"));
        assertEquals(5, c.getInt("gold.repurpose.seconds"));
        assertEquals(170, c.getInt("gold.max-cooldown-seconds"));
        assertEquals(30, c.getInt("gold.ritual.seconds"));
        assertEquals(8.0, c.getDouble("gold.drill.damage"), 1e-9);
        String g = read("GoldGem.java");
        assertTrue(g.contains("if (!p.isSneaking() || !gems().isGold(e.getOffHandItem()) || stateOf(p) == null) return;"), "F alone swaps as normal");
        assertTrue(g.contains("p.startRiptideAttack(20,"), "the real Riptide spin");
        assertTrue(g.contains("strikeLightningEffect("), "cosmetic lightning");
        assertTrue(g.contains("AuratusGem.stillChain("), "the Auratus chains");
    }
}
