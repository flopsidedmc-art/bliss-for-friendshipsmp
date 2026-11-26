package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Wealth's Amplification: who's buffed, no stacking, and the buff always coming off. */
class WealthTest {
    private static final UUID ME = UUID.randomUUID(), FRIEND = UUID.randomUUID(), STRANGER = UUID.randomUUID(), FAR_FRIEND = UUID.randomUUID();

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    // ------------------------------------------------------------------ who

    @Test void youAndTrustedPlayersInTheRadius() {
        List<UUID> t = AmpMath.targets(ME, List.of(
                new AmpMath.Near(FRIEND, 5 * 5, true),
                new AmpMath.Near(STRANGER, 3 * 3, false),
                new AmpMath.Near(FAR_FRIEND, 9 * 9, true)), 8);
        assertEquals(List.of(ME, FRIEND), t, "the caster and the trusted friend 5 blocks away; not the stranger, not a friend 9 away");
        assertEquals(List.of(ME), AmpMath.targets(ME, List.of(), 8), "alone: just you");
        assertEquals(List.of(ME, FRIEND), AmpMath.targets(ME, List.of(new AmpMath.Near(FRIEND, 64, true)), 8), "exactly 8 blocks counts");
        assertEquals(List.of(ME), AmpMath.targets(ME, List.of(new AmpMath.Near(ME, 0, true)), 8), "never twice");
    }

    // ------------------------------------------------------------------ the levels

    @Test void plusOneAndBackAgain() {
        assertEquals(6, AmpMath.boosted(5), "Sharpness V acts like VI");
        assertEquals(5, AmpMath.restored(6, 5), "and goes back afterwards");
        assertEquals(10, AmpMath.boosted(10), "never past 10, as before");
        assertEquals(10, AmpMath.restored(10, 10), "nothing was added, nothing comes off");
        assertEquals(5, AmpMath.restored(5, 5), "already back: stays");
        assertEquals(6, AmpMath.restored(7, 5), "raised during the buff: only the +1 comes off");
        assertEquals(3, AmpMath.restored(3, 5), "lowered during the buff: left as it is");
    }

    @Test void buffsNeverStack() throws Exception {
        assertEquals(31_000, AmpMath.refreshed(1_000, 30_000), "casting again refreshes the time");
        assertEquals(31_000, AmpMath.refreshed(1_000, 30_000), "and never adds to it");
        String a = read("Amplification.java");
        assertTrue(a.contains("if (pdc.has(kOrig, PersistentDataType.STRING)) continue; // never stacks"), "an item already buffed isn't buffed again");
        assertTrue(a.contains("until.put(id, end); // a second cast only refreshes the time"));
    }

    @Test void theRecordRoundTrips() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:sharpness", 5);
        m.put("minecraft:unbreaking", 3);
        String s = AmpMath.encode(m);
        assertEquals("minecraft:sharpness=5;minecraft:unbreaking=3", s);
        assertEquals(m, AmpMath.decode(s));
        assertTrue(AmpMath.decode("").isEmpty());
        assertTrue(AmpMath.decode("junk;minecraft:x=oops").isEmpty(), "a broken record is ignored");
    }

    // ------------------------------------------------------------------ always removed

    @Test void theBuffAlwaysComesOff() throws Exception {
        String a = read("Amplification.java");
        for (String s : List.of("public void onQuit(PlayerQuitEvent e) { restorePlayer(e.getPlayer()); }", // logout
                "for (ItemStack s : e.getDrops()) restore(s);", // death
                "if (left <= 0) {\n                restorePlayer(p);", // the time ends
                "if (p.isOnline() && !buffed(p)) restorePlayer(p);", // a restart or crash
                "for (Player p : Bukkit.getOnlinePlayers()) restorePlayer(p);", // plugin disable
                "public void onDrop(", "public void onPickup(", "public void onOpen(", "public void onClose(")) assertTrue(a.contains(s), s);
        assertTrue(read("SoulboundGemsPlugin.java").contains("amplification.shutdown()"));
        String g = read("GemListener.java");
        assertFalse(g.contains("revertEnchantBoost") || g.contains("BoostRecord"), "the old slot-based boost (which could stick) is gone");
    }

    @Test void theControls() throws Exception {
        String g = read("GemListener.java");
        int w = g.indexOf("if (type == GemType.WEALTH) {");
        String wealth = g.substring(w, w + 900);
        assertTrue(wealth.indexOf("p.isSneaking()) { castAmplification(p, energy); return; }") < wealth.indexOf("useRichRush(p, energy)"),
                "sneak + right-click is Amplification; plain right-click still Rich Rush");
        assertTrue(wealth.indexOf("useItemLock") < wealth.indexOf("castAmplification"), "left-click is still Item Lock");
        assertTrue(g.contains("cooldownMs(\"amp\", energy)"), "the same 240 s cooldown");
        String amp = read("Amplification.java");
        for (String s : List.of("\"Amplified by \"", "\": +1 enchantments for \"", "Particle.HAPPY_VILLAGER", "isTrusted(caster, o)"))
            assertTrue(amp.contains(s), s);
        assertTrue(read("GemType.java").contains("Amplification [Sneak + Right-click]"), "the guide");
    }

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(8, c.getInt("wealth.amplification.radius"));
        assertEquals(30, c.getInt("wealth.amplification.seconds"));
        assertEquals(240, c.getInt("cooldowns.amp"), "unchanged");
    }
}
