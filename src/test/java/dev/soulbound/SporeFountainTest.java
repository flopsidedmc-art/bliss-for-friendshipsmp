package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** The Spore's blood fountain: its height and ceiling, the pulse, the particle budget and the rippling top. */
class SporeFountainTest {
    @Test void theColumnIsFifteenToTwentyHighAndStopsAtACeiling() {
        assertEquals(82, SporeFountain.top(64, 18, null), 1e-9);
        assertEquals(79, SporeFountain.top(64, 5, null), 1e-9, "never below 15");
        assertEquals(84, SporeFountain.top(64, 40, null), 1e-9, "never above 20");
        assertEquals(69.8, SporeFountain.top(64, 18, 70.0), 1e-9, "a cave roof at 70: just under it");
        assertEquals(82, SporeFountain.top(64, 18, 90.0), 1e-9, "a ceiling above the column changes nothing");
        assertTrue(SporeFountain.top(64, 18, 64.0) > 64, "even a roof right above stays above the ground");
    }

    @Test void aHeartbeatAboutOnceASecond() {
        int surges = 0;
        for (int t = 0; t < 200; t++) if (SporeFountain.surging(t, 20) && !SporeFountain.surging(t - 1, 20)) surges++;
        assertEquals(10, surges, "10 seconds: 10 surges");
        int on = 0;
        for (int t = 0; t < 20; t++) if (SporeFountain.surging(t, 20)) on++;
        assertEquals(SporeFountain.SURGE_TICKS, on, "a few ticks, then it settles");
    }

    @Test void neverMoreThanTheBudget() {
        for (int n : new int[] {10, 50, 100, 400}) for (boolean surge : new boolean[] {false, true}) for (boolean rain : new boolean[] {false, true}) {
            int[] b = SporeFountain.budget(n, surge, rain);
            assertTrue(b[0] + b[1] + b[2] <= n, n + " " + surge + " " + rain);
        }
        int[] settled = SporeFountain.budget(100, false, true), surge = SporeFountain.budget(100, true, true);
        assertTrue(surge[0] > settled[0], "a surge is more particles in the column");
        assertEquals(0, SporeFountain.budget(100, false, false)[2], "no rain: none for it");
    }

    @Test void theTopRipplesOutward() {
        // a crest at r at tick t is further out a few ticks later
        double r0 = crest(0), r5 = crest(5);
        assertTrue(r5 > r0, "crest moved from " + r0 + " to " + r5);
        for (int t = 0; t < 40; t++) for (double r = 0; r <= 5; r += 0.25) {
            assertTrue(SporeFountain.canopyHeight(r, 5, t, true) < 0, "under a ceiling it never rises into it");
        }
        assertTrue(SporeFountain.canopyHeight(0.5, 5, 0, false) > SporeFountain.canopyHeight(5, 5, 0, false) - 0.7,
                "the spray arcs up near the column and falls away at the edge");
    }

    /** The first ripple crest (local maximum of the ripple term) past 0.5 blocks at tick t. */
    private static double crest(int t) {
        double best = -1, prev = Double.NEGATIVE_INFINITY;
        boolean rising = false;
        for (double r = 0.5; r <= 5; r += 0.01) {
            double v = Math.sin(r * 2.4 - t * 0.35);
            if (rising && v < prev) { best = r; break; }
            rising = v > prev;
            prev = v;
        }
        return best;
    }

    @Test void sizesAndColours() {
        for (double rnd = 0; rnd <= 1; rnd += 0.1) {
            float s = SporeFountain.size(rnd, false);
            assertTrue(s >= 1.4f && s <= 2.0f, "size " + s);
        }
        assertEquals(0x7A0010, SporeFountain.DARK.asRGB());
        assertEquals(0xB0101C, SporeFountain.BLOOD.asRGB());
        assertEquals(0xD01A28, SporeFountain.PULSE.asRGB());
    }

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        String v = "mythics.heretic.spore.visuals.";
        assertTrue(c.getBoolean(v + "fountain"));
        assertEquals(18.0, c.getDouble(v + "pillar-height"));
        assertEquals(5.0, c.getDouble(v + "canopy-radius"));
        assertEquals(1.0, c.getDouble(v + "pulse-seconds"));
        assertTrue(c.getBoolean(v + "rain"));
        assertEquals(100, c.getInt(v + "particles-per-tick"));
        assertEquals("ENTITY_WARDEN_HEARTBEAT", c.getString("sounds.heretic.spore-pulse.event"));
        assertEquals(0.6, c.getDouble("sounds.heretic.spore-pulse.volume"));
        assertEquals(0.8, c.getDouble("sounds.heretic.spore-pulse.pitch"));
        assertEquals(4.0, c.getDouble("mythics.heretic.spore.radius"), "the field itself is unchanged");
        assertEquals(8000, c.getInt("mythics.heretic.spore.duration-ms"));
        assertEquals(8000, c.getInt("mythics.heretic.spore.bleed-ms"));
    }
}
