package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/** The Life revamp: which Circle of Life, the trusted-only blessing, its hearts never stacking or staying, and the drain. */
class LifeTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    @Test void stealOnlyWithAnEnemyInFront() {
        assertEquals(LifeMath.Circle.STEAL, LifeMath.circle(true, false), "an untrusted player in front of you: the steal, as before");
        assertEquals(LifeMath.Circle.BLESSING, LifeMath.circle(true, true), "a friend in front of you: the blessing");
        assertEquals(LifeMath.Circle.BLESSING, LifeMath.circle(false, false), "the air, a block, nobody: the blessing");
    }

    @Test void onlyYouAndTrustedPlayersAreBlessed() {
        String me = "me", friend = "friend", stranger = "stranger", fan = "fan";
        BiPredicate<String, String> trusts = (a, b) -> a.equals("me") && Set.of("friend", "fan").contains(b);
        List<String> got = LifeMath.blessed(me, List.of(me, friend, stranger, fan, friend), trusts);
        assertEquals(List.of(me, friend, fan), got, "you, then your trusted players once each; the untrusted one gets nothing");
        assertEquals(List.of(me), LifeMath.blessed(me, List.of(stranger), trusts), "alone with a stranger: just you");
        BiPredicate<String, String> theyTrustMe = (a, b) -> a.equals("friend") && b.equals("me");
        assertEquals(List.of(me), LifeMath.blessed(me, List.of(friend), theyTrustMe), "it's your trust list that counts");
    }

    @Test void theExtraHeartsNeverStackOrStay() {
        LifeMath.Hearts h = new LifeMath.Hearts();
        UUID a = UUID.randomUUID();
        assertFalse(h.give(a, 0, 30_000));
        assertTrue(h.give(a, 10_000, 30_000), "blessed again: the same hearts, a fresh timer");
        assertEquals(8.0, h.bonus(a), "+4 hearts, never +8");
        assertFalse(h.due(a, 39_999), "the second blessing's 30 s");
        assertTrue(h.due(a, 40_000));
        assertTrue(h.take(a));
        assertEquals(0.0, h.bonus(a), "gone when the time's up");
        assertFalse(h.take(a), "and only taken once");
        for (String why : new String[] {"death", "logout", "rejoin"}) {
            h.give(a, 0, 30_000);
            assertTrue(h.take(a), why);
            assertEquals(0.0, h.bonus(a), why + ": nothing left");
        }
    }

    @Test void theHeartsAreOneUnsavedModifierTakenOffEveryWay() throws Exception {
        String b = read("LifeBlessing.java");
        assertTrue(b.contains("a.removeModifier(key); // never stacking") && b.contains("a.addTransientModifier(new AttributeModifier(key, 8.0"),
                "one keyed modifier, replaced not added, and never saved (a restart can't keep it)");
        for (String handler : new String[] {"public void onDeath(", "public void onRespawn(", "public void onQuit(", "public void onJoin("}) {
            int i = b.indexOf(handler);
            assertTrue(i >= 0 && b.substring(i, i + 160).contains("clear("), handler);
        }
        assertTrue(b.contains("void start() { for (Player p : Bukkit.getOnlinePlayers()) clear(p); }"));
        assertTrue(b.contains("void shutdown() { for (Player p : Bukkit.getOnlinePlayers()) clear(p); }"));
        String plugin = read("SoulboundGemsPlugin.java");
        assertTrue(plugin.contains("blessing.start()") && plugin.contains("blessing.shutdown()"));
    }

    @Test void heartLocksNeverBakeInABonusOrOutliveALogout() throws Exception {
        String gl = read("GemListener.java");
        int i = gl.indexOf("private void lockHearts(");
        assertTrue(gl.substring(i, i + 400).contains("heartOrig.put(v.getUniqueId(), baseMaxHealth(v))"),
                "the lock keeps your own max health, not the blessed one");
        assertFalse(gl.contains("maxHealthOf("), "nothing reads max health with bonuses for a lock");
        int q = gl.indexOf("public void onLeave(PlayerQuitEvent e)");
        assertTrue(gl.substring(q, q + 200).contains("restoreHearts(p)"), "a logout mid-lock gives the hearts back");
        int s = gl.indexOf("void shutdown() {");
        assertTrue(gl.substring(s, s + 200).contains("restoreHearts(p)"), "so does a server stop");
        int c = gl.indexOf("private void useCircleOfLife(");
        assertTrue(gl.substring(c, c + 1400).contains("!isTrusted(p, o)) targets.add(o); // never your own side"),
                "the steal never takes from your friends");
    }

    @Test void theDrainFilter() {
        for (String m : new String[] {"GRASS_BLOCK", "DIRT", "PODZOL", "MOSS_BLOCK", "SAND", "STONE", "OAK_LOG", "BIRCH_LOG"})
            assertTrue(LandMath.eligible(m, false, false), m);
        assertTrue(LandMath.eligible("OAK_LEAVES", false, false), "natural leaves (they vanish)");
        assertFalse(LandMath.eligible("OAK_LEAVES", false, true), "placed leaves stay");
        for (String m : new String[] {"CHEST", "WHITE_BED", "OAK_DOOR", "REDSTONE_WIRE", "OAK_SIGN", "WHITE_BANNER", "PLAYER_HEAD",
                "BEDROCK", "OAK_PLANKS", "FARMLAND", "DIAMOND_ORE"}) assertFalse(LandMath.eligible(m, false, false), m);
        assertFalse(LandMath.eligible("DIRT", true, false), "anything with a block entity");
        for (String m : new String[] {"SHORT_GRASS", "FERN", "POPPY", "DANDELION", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "TALL_GRASS", "SUNFLOWER"})
            assertTrue(LandMath.lifePlant(m), m + " withers");
        assertTrue(LandMath.tallPlant("TALL_GRASS") && LandMath.tallPlant("ROSE_BUSH"), "both halves go together");
        assertFalse(LandMath.lifePlant("TORCH"));
    }

    @Test void theVortexWorksAnywhereAndTheDrainIsSafe() throws Exception {
        String gl = read("GemListener.java");
        int i = gl.indexOf("private void useVitalityVortex(");
        String v = gl.substring(i, gl.indexOf("\n    }\n", i));
        assertFalse(v.contains("not enough nature") || v.contains("cdClear"), "it always works");
        assertTrue(v.contains("RESISTANCE") && v.contains("REGENERATION") && v.contains("ABSORPTION"), "and always gives its buffs");
        String d = read("VortexDrain.java");
        assertTrue(d.contains("LandWorks.holdsLiquid("), "a block holding back water or lava is never touched");
        assertTrue(d.contains("dead == Material.GRAVEL && !b.getRelative(BlockFace.DOWN).getType().isSolid()"), "nothing it places can fall");
        assertTrue(d.contains("works.restoreOne(j, e)") && d.contains("works.candidate("), "the shared restore and filter (with Crisp)");
        assertTrue(d.contains("\"vortex\", w,") && d.contains(", false, restoreAt)"), "its blocks drop their dead version when broken");
    }

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(6, c.getInt("life.circle-of-life.blessing.radius"));
        assertEquals(30, c.getInt("life.circle-of-life.blessing.seconds"));
        assertTrue(c.getBoolean("life.vitality-vortex.drain"));
        assertEquals(5, c.getInt("life.vitality-vortex.drain-radius"));
        assertEquals(0.45, c.getDouble("life.vitality-vortex.drain-chance"));
        assertEquals(30, c.getInt("life.vitality-vortex.restore-after-seconds"));
        assertEquals(Set.of("DEAD_TUBE_CORAL_BLOCK", "DEAD_BRAIN_CORAL_BLOCK", "DEAD_BUBBLE_CORAL_BLOCK", "DEAD_FIRE_CORAL_BLOCK",
                "DEAD_HORN_CORAL_BLOCK", "ANDESITE", "TUFF", "GRAVEL", "COARSE_DIRT", "COBBLESTONE"),
                c.getConfigurationSection("life.vitality-vortex.dead-blocks").getKeys(false));
        assertEquals("BLOCK_AMETHYST_BLOCK_CHIME", c.getString("sounds.life.blessing.event"));
        assertEquals("ENTITY_PLAYER_LEVELUP", c.getString("sounds.life.blessing.layer.event"));
        assertEquals("BLOCK_AZALEA_LEAVES_PLACE", c.getString("sounds.life.regrow.event"));
        assertEquals(60, c.getInt("cooldowns.vortex"), "the Vortex keeps its 60 s");
    }
}
