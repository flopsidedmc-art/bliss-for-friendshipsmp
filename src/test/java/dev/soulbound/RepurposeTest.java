package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The Gold Gem repurpose scene: torn out, rise, look up, quiver, shatter, absorbed. No chains, no lightning. */
class RepurposeTest {
    private static final RepurposeMath.Timing T = RepurposeMath.timing(5, 0.5, 2.5, 3.5, 4.5);

    @Test void theStepsInOrder() {
        assertEquals(new RepurposeMath.Timing(10, 50, 70, 90, 100), T, "0.5, 2.5, 3.5, 4.5 and 5 s, in ticks");
        assertEquals(RepurposeMath.Step.TORN_OUT, RepurposeMath.step(T, 0));
        assertEquals(RepurposeMath.Step.RISE, RepurposeMath.step(T, 10));
        assertEquals(RepurposeMath.Step.LOOK_UP, RepurposeMath.step(T, 50));
        assertEquals(RepurposeMath.Step.QUIVER, RepurposeMath.step(T, 70));
        assertEquals(RepurposeMath.Step.ABSORB, RepurposeMath.step(T, 90));
        assertEquals(RepurposeMath.Step.DONE, RepurposeMath.step(T, 100));
        RepurposeMath.Timing odd = RepurposeMath.timing(5, 3, 1, 1, 9);
        assertTrue(odd.tornOut() < odd.rise() && odd.rise() < odd.lookUp() && odd.lookUp() < odd.quiver() && odd.quiver() < odd.total(),
                "badly ordered config still gives every step a moment, in order");
    }

    @Test void itRisesSlowingAtTheTop() {
        assertEquals(0, RepurposeMath.rise(T, 5), "still at the chest while it's torn out");
        assertEquals(1, RepurposeMath.rise(T, 50), 1e-9, "at the apex by 2.5 s");
        double early = RepurposeMath.rise(T, 20) - RepurposeMath.rise(T, 10), late = RepurposeMath.rise(T, 50) - RepurposeMath.rise(T, 40);
        assertTrue(early > late * 3, "eases out: fast at first, slow at the top");
        assertEquals(1, RepurposeMath.rise(T, 95), 1e-9, "it stays up there");
        assertEquals(0.3, RepurposeMath.scale(T, 0, 0.3, 2.5), 1e-9);
        assertEquals(2.5, RepurposeMath.scale(T, 10, 0.3, 2.5), 1e-9, "large (2.5) once it's out");
    }

    @Test void itLooksUpThenQuiversHarder() {
        assertEquals(0, RepurposeMath.tilt(T, 49, 10), "upright while rising");
        assertEquals(90, RepurposeMath.tilt(T, 60, 10), 1e-9, "face to the sky after 0.5 s");
        assertTrue(RepurposeMath.tilt(T, 55, 10) > 0 && RepurposeMath.tilt(T, 55, 10) < 90, "smoothly");
        assertEquals(90, RepurposeMath.tilt(T, 85, 10), 1e-9, "and it holds");
        assertEquals(0, RepurposeMath.quiver(T, 69), "still before the quiver");
        double a = RepurposeMath.quiver(T, 72), b = RepurposeMath.quiver(T, 80), c = RepurposeMath.quiver(T, 89);
        assertTrue(a < b && b < c && c == 1.0, "stronger and stronger until it breaks");
        assertEquals(0, RepurposeMath.quiver(T, 90), "broken");
        assertEquals(1.0, RepurposeMath.absorb(T, 99), 1e-9, "the shards reach the holder at the end");
        assertTrue(RepurposeMath.absorb(T, 90) > 0 && RepurposeMath.absorb(T, 90) < 0.2);
    }

    @Test void sideOn() {
        assertEquals(90f, RepurposeMath.yaw(0f));
        assertEquals(-90f, RepurposeMath.yaw(180f), "wrapped");
        assertEquals(0f, RepurposeMath.yaw(-90f));
    }

    @Test void theSceneAndConfig() throws Exception {
        String g = Files.readString(Path.of("src/main/java/dev/soulbound/GoldGem.java"));
        assertTrue(g.contains("d.setBillboard(Display.Billboard.FIXED)"), "no billboard: fixed in the world");
        assertTrue(g.contains("GemSounds.play(plugin, \"gold.repurpose-hit\", sc.base); // THE HIT"));
        assertTrue(g.contains("Particle.CLOUD") && g.contains("Particle.POOF"), "smoke off its sides");
        assertTrue(g.contains("w.spawnParticle(Particle.ITEM, at, n, 0.3, 0.3, 0.3, 0.18, sc.gem.clone());"), "its own pieces");
        assertTrue(g.contains("GemSounds.playTo(plugin, \"gold.absorb\", holder); // a deep chime, for them"));
        assertTrue(g.contains("vp.teleport(look);"), "the camera keeps the gem centred (from their body)");
        assertTrue(g.contains("plugin.overlay().play(v, repurposeOverlayStyle(sc.type)"), "the animated overlay, through the death screen (true)");
        assertTrue(g.contains("v.setHealth(0); // a normal death"), "then a normal death: items drop, not the gem");
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(5, c.getInt("gold.repurpose.seconds"));
        assertEquals(2.5, c.getDouble("gold.repurpose.gem-scale"));
        assertEquals(4.0, c.getDouble("gold.repurpose.apex"));
        assertTrue(c.getBoolean("gold.repurpose.follow-camera"));
        assertFalse(c.contains("gold.repurpose.lightning") || c.contains("gold.repurpose.chains"), "no chain or lightning settings");
        assertFalse(c.contains("sounds.gold.chains") || c.contains("sounds.gold.chains-snap"));
        for (String s : new String[] {"repurpose-hit", "repurpose", "repurpose-strain", "repurpose-shatter", "absorb"})
            assertTrue(c.contains("sounds.gold." + s + ".event"), s);
        assertTrue(Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java")).contains("\"gold.repurpose.lightning\", \"gold.repurpose.chains\""),
                "old servers' configs lose them too");
    }
}
