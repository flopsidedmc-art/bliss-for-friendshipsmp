package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every firework this plugin makes is tagged and harmless, and the Auratus bursts land where they should. */
class FireworkSafetyTest {
    /** The one firework spawn is AuratusGem.burst(), which tags it; no other code path spawns a firework. */
    @Test void onlyBurstSpawnsFireworks() throws Exception {
        int spawns = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String s = Files.readString(f);
                assertFalse(s.contains("EntityType.FIREWORK"), f + " spawns a firework by entity type");
                int i = -1;
                while ((i = s.indexOf("Firework.class", i + 1)) >= 0) {
                    spawns++;
                    assertTrue(f.endsWith("AuratusGem.java"), f + " spawns a firework outside burst()");
                    int method = s.lastIndexOf("private void ", i);
                    assertTrue(s.startsWith("private void burst(", method), "the firework spawn must be inside burst()");
                    String body = s.substring(i, s.indexOf("fw.detonate();", i));
                    assertTrue(body.contains("fireworkKey"), "burst() must tag the firework so its damage is cancelled");
                }
            }
        }
        assertEquals(1, spawns, "exactly one firework spawn site");
    }

    @Test void randomSpotsStayInTheirRing() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double[] o = AuratusMath.randomOffset(r, 1.5, 3.0, 0.5, 2.5);
            double h = Math.hypot(o[0], o[2]);
            assertTrue(h >= 1.5 - 1e-9 && h <= 3.0 + 1e-9, "horizontal " + h);
            assertTrue(o[1] >= 0.5 && o[1] <= 2.5, "up " + o[1]);
        }
    }

    @Test void visualsSettings() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String k : new String[] {"slam-burst", "slam-sparks", "launch-burst", "flagpole-burst"})
            assertTrue(c.getBoolean("mythics.auratus.visuals." + k, false), k);
        assertEquals(30, c.getInt("mythics.auratus.visuals.slam-spark-count"));
        assertFalse(c.getBoolean("mythics.auratus.flagpole.ceiling-cap", true));
        assertFalse(c.contains("mythics.auratus.slam.firework"), "visuals.slam-burst replaces it in the shipped config");
    }
}
