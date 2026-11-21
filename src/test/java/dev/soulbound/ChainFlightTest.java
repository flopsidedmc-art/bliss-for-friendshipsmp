package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Auratus chains: the release refund, the flight's unspooling, the reel-in timing, and the HUD numbers. */
class ChainFlightTest {
    @Test void refundGivesBackTheChargeJustUsed() {
        Charges c = new Charges(2);
        assertTrue(c.use(0, 8_000));      // throw 1 at 0 s
        assertTrue(c.use(1_000, 8_000));  // throw 2 at 1 s
        assertEquals(0, c.ready(1_000));
        assertTrue(c.refundLatest(1_000)); // released before throw 2 bit anything
        assertEquals(1, c.ready(1_000));
        assertEquals(7_000, c.nextRecharge(1_000), "throw 1's charge still recharges on its own timer");
        assertTrue(c.refundLatest(1_000));
        assertFalse(c.refundLatest(1_000), "nothing left to give back");
        assertEquals(2, c.ready(1_000));
    }

    @Test void nextRechargeIsShownEvenWhileOneIsReady() {
        Charges c = new Charges(2);
        c.use(0, 8_000);
        assertEquals(1, c.ready(2_000));
        assertEquals(0, c.nextIn(2_000), "nextIn: a charge is ready");
        assertEquals(6_000, c.nextRecharge(2_000), "nextRecharge: the one coming back");
        assertEquals(0, new Charges(2).nextRecharge(0));
    }

    @Test void linksUnspoolFromTheHandOutward() {
        double strand = 47 / 16.0, link = 6 / 16.0;
        double len = 20;
        double[] at = AuratusMath.chainLayout(len, strand, 7 * link, link, 60);
        int n = at.length - 1;
        boolean[] seen = new boolean[n + 1];
        for (double q = 0; q <= 1.0001; q += 0.05) {
            for (int i = 1; i <= n; i++) {
                boolean passed = AuratusMath.hookPassed(len, at, i, q);
                if (seen[i]) assertTrue(passed, "a link never disappears during the flight");
                if (passed) seen[i] = true;
                if (passed && i < n) assertTrue(AuratusMath.hookPassed(len, at, i + 1, q), "links nearer the hand appear first");
            }
        }
        for (int i = 1; i <= n; i++) assertTrue(AuratusMath.hookPassed(len, at, i, 1.0), "all there when it lands");
        assertFalse(AuratusMath.hookPassed(len, at, 1, 0.0), "none before it leaves");
    }

    @Test void reelInRemovesLinksAsTheHookPasses() {
        assertEquals(101, AuratusMath.passTick(100, 4, 0.1, 20), "next to the hook: at once (next tick)");
        assertEquals(102, AuratusMath.passTick(100, 4, 10, 20));
        assertEquals(104, AuratusMath.passTick(100, 4, 20, 20), "at the hand: when the hook gets there");
        assertEquals(104, AuratusMath.passTick(100, 4, 99, 20));
    }

    @Test void hudNumbers() {
        String icon = "X";
        assertEquals("§fX §aReady! §f2/2", HudStatus.charges(icon, 2, 2, 0, false, true));
        assertEquals("§fX §aReady! §f1/2 §b6s", HudStatus.charges(icon, 1, 2, 6_000, false, true));
        assertEquals("§7X §b6s §70/2", HudStatus.charges(icon, 0, 2, 6_000, false, true));
        assertEquals("§fX §e… §f1/2", HudStatus.charges(icon, 1, 2, 6_000, true, true));
        assertEquals("§7X §b12s", HudStatus.entry(icon, 12_000, false, true));
        assertEquals("§fX §aReady!", HudStatus.entry(icon, 0, false, true));
        // show-numbers off: icons only
        assertEquals("§7X", HudStatus.entry(icon, 12_000, false, false));
        assertEquals("§fX §aReady!", HudStatus.charges(icon, 1, 2, 6_000, false, false));
        assertEquals("§7X", HudStatus.charges(icon, 0, 2, 6_000, false, false));
    }

    @Test void settings() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(c.getBoolean("mythics.hud.show-numbers", false));
        assertTrue(c.getBoolean("mythics.auratus.release.refund-before-hit", false));
        assertTrue(c.getBoolean("mythics.auratus.visuals.chain-travel", false));
        assertEquals(5, c.getInt("mythics.auratus.visuals.chain-travel-ticks"));
        assertTrue(c.getBoolean("mythics.auratus.visuals.chain-reel-in", false));
        assertEquals(4, c.getInt("mythics.auratus.visuals.chain-reel-ticks"));
    }
}
