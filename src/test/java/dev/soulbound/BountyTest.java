package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Strength's Bounty Hunter: the offering, the board's pages, the thread, and that the old version is gone. */
class BountyTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    // ------------------------------------------------------------------ the offering

    @Test void aMinuteAnItemAtMostFiveMinutes() {
        assertEquals(new BountyMath.Offer(2, 120), BountyMath.offer(2, 60, 300, 0), "2 items: 2 minutes");
        assertEquals(new BountyMath.Offer(5, 300), BountyMath.offer(5, 60, 300, 0), "a full hopper: 5 minutes");
        assertEquals(new BountyMath.Offer(5, 300), BountyMath.offer(7, 60, 300, 0), "never more than 5 minutes; the rest go back");
        assertEquals(new BountyMath.Offer(0, 0), BountyMath.offer(0, 60, 300, 0), "nothing counted: nothing happens");
    }

    @Test void offeringAgainAddsTime() {
        assertEquals(new BountyMath.Offer(1, 60), BountyMath.offer(3, 60, 300, 240), "4 minutes left: only one item is used");
        assertEquals(new BountyMath.Offer(1, 50), BountyMath.offer(2, 60, 300, 250), "topped up to 5 minutes");
        assertEquals(new BountyMath.Offer(0, 0), BountyMath.offer(2, 60, 300, 300), "already at the limit: every item comes back");
    }

    @Test void minutesRead() {
        assertEquals("3 minutes", BountyHunt.minutes(180));
        assertEquals("1 minute", BountyHunt.minutes(60));
        assertEquals("2m 30s", BountyHunt.minutes(150));
    }

    // ------------------------------------------------------------------ the board

    @Test void pagesOf45() {
        assertEquals(1, BountyMath.pages(0));
        assertEquals(1, BountyMath.pages(45));
        assertEquals(2, BountyMath.pages(46), "more than 45: arrows on the bottom row");
        assertEquals(3, BountyMath.pages(91));
        assertEquals(1, BountyMath.clampPage(5, 60));
        assertEquals(0, BountyMath.clampPage(-1, 60));
    }

    // ------------------------------------------------------------------ the thread

    @Test void aThreadFourteenBlocksLongStartingAhead() {
        Vector from = new Vector(0, 65, 0), to = new Vector(40, 65, 0);
        BountyMath.Trail t = BountyMath.trail(from, to, 1.5, 14, 0.25, 0);
        assertEquals(t.black().size(), t.red().size(), "two strands");
        assertTrue(t.black().size() >= 50, "close enough to look like a line");
        Vector first = mid(t, 0), last = mid(t, t.black().size() - 1);
        assertEquals(1.5, first.distance(from), 0.05, "about 1.5 blocks ahead");
        assertEquals(15.5, last.distance(from), 0.05, "about 14 blocks long");
        for (int i = 0; i < t.black().size(); i++)
            assertEquals(0.18, t.black().get(i).distance(t.red().get(i)), 1e-6, "the strands wind round each other");
    }

    @Test void aCloseTargetIsWhereTheThreadEnds() {
        Vector from = new Vector(0, 65, 0), to = new Vector(3, 66, 7);
        for (long tick = 0; tick < 200; tick += 35) {
            BountyMath.Trail t = BountyMath.trail(from, to, 1.5, 14, 0.25, tick);
            Vector last = mid(t, t.black().size() - 1);
            assertTrue(last.distance(to) < 0.26, "it ends at them (" + last.distance(to) + ")");
        }
        assertTrue(BountyMath.trail(from, new Vector(1, 65, 0), 1.5, 14, 0.25, 0).black().isEmpty(), "right next to you: no thread");
    }

    @Test void itCurvesALittle() {
        Vector from = new Vector(0, 65, 0), to = new Vector(40, 65, 0);
        BountyMath.Trail t = BountyMath.trail(from, to, 1.5, 14, 0.25, 40); // sway at its widest around here
        double most = 0;
        for (int i = 0; i < t.black().size(); i++) {
            Vector m = mid(t, i);
            most = Math.max(most, Math.hypot(m.getY() - 65, m.getZ()));
        }
        assertTrue(most > 0.05 && most < 0.6, "a slight bend, like a thread (" + most + ")");
    }

    private static Vector mid(BountyMath.Trail t, int i) { return t.black().get(i).clone().add(t.red().get(i)).multiply(0.5); }

    // ------------------------------------------------------------------ the wiring

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(60, c.getInt("bounty.seconds-per-item"));
        assertEquals(300, c.getInt("bounty.max-seconds"));
        assertEquals(14, c.getInt("bounty.trail-length"));
        assertEquals(List.of("#111111", "#8B0000"), c.getStringList("bounty.trail-colors"));
        assertFalse(c.getBoolean("bounty.notify-target", true));
        assertEquals(35, c.getInt("cooldowns.bounty"), "the existing cooldown");
    }

    @Test void theNewHunt() throws Exception {
        String b = read("BountyHunt.java");
        for (String s : List.of("createInventory(h, 54, Component.text(\"Bounty Board\"))", "InventoryType.HOPPER", "\"Offer to the hunt: \"",
                "\"The hunt begins: \"", "\"They walk another realm\"", "\"In another realm\"", "\"You feel watched\"", "Reason.PLAYER",
                "hunter.spawnParticle(Particle.DUST", "ProvenanceMath.counts(prov.read(s), h.target)", "endHuntsOn(p, \"slipped away.\")",
                "endHuntsOn(e.getEntity(), \"fell.\")", "plugin.listener().bountyBegan(p)")) assertTrue(b.contains(s), s);
        int sd = b.indexOf("void shutdown() {");
        assertTrue(b.substring(sd, sd + 600).contains("giveBack(p, takeAll(o.inv))"), "a disable gives open offerings back");
        String plugin = read("SoulboundGemsPlugin.java");
        assertTrue(plugin.contains("bounty.start()") && plugin.contains("bounty.shutdown()"));
    }

    @Test void theOldHeadAndCompassVersionIsGone() throws Exception {
        String g = read("GemListener.java");
        for (String s : List.of("setCompassTarget", "headTargetFrom", "OfferHolder", "Offer for ", "bountyTarget", "drawTrackingLine",
                "You feel like you are being watched")) assertFalse(g.contains(s), s);
        assertTrue(g.contains("plugin.bounty().openBoard(p)"), "off hand + right-click opens the board");
        assertTrue(g.contains("plugin.bounty().hudSuffix(p)"), "the time left on the gem bar");
        assertFalse(read("BlissCommand.java").contains("setBounty("), "/bliss track goes through an offering too");
    }
}
