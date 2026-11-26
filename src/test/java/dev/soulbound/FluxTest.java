package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Flux: watts from ingots, the charge, the beam's power, Overcharge, Overdrive's meter and Shocking Chance. */
class FluxTest {
    private static final Map<String, Integer> INGOTS = Map.of("copper", 12, "netherite", 10, "gold", 8, "iron", 5);

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    // ------------------------------------------------------------------ watts

    @Test void copperIsBest() {
        assertEquals(12, FluxMath.ingotWatts("COPPER_INGOT", INGOTS));
        assertEquals(10, FluxMath.ingotWatts("NETHERITE_INGOT", INGOTS));
        assertEquals(8, FluxMath.ingotWatts("GOLD_INGOT", INGOTS));
        assertEquals(5, FluxMath.ingotWatts("IRON_INGOT", INGOTS));
        assertEquals(0, FluxMath.ingotWatts("DIAMOND", INGOTS), "only what's listed");
        assertEquals(0, FluxMath.ingotWatts("COPPER_BLOCK", INGOTS));
        assertEquals(100, FluxMath.ingotWatts("COPPER_BLOCK", Map.of("copper_block", 100)), "anything can be added by its own name");
        var c = config().getConfigurationSection("flux.watts.per-ingot");
        assertEquals(12, c.getInt("copper"));
        assertEquals(10, c.getInt("netherite"));
        assertEquals(8, c.getInt("gold"));
        assertEquals(5, c.getInt("iron"));
    }

    @Test void feedingStopsAt100() {
        assertEquals(17, FluxMath.fed(5, 12), 1e-9);
        assertEquals(100, FluxMath.fed(95, 12), 1e-9, "max stays 100");
        assertEquals(150, FluxMath.fed(150, 12), 1e-9, "feeding never takes Overcharge down (or up)");
    }

    // ------------------------------------------------------------------ the charge

    @Test void chargingAt12APerSecondUpTo100() {
        double w = 30;
        for (int i = 0; i < 20; i++) w = FluxMath.charge(w, 12, 0.05, false); // one second
        assertEquals(42, w, 1e-9, "on top of what you had");
        assertEquals(100, FluxMath.charge(95, 12, 1, false), 1e-9, "it holds at 100");
        assertEquals(100, FluxMath.charge(100, 12, 30, false), 1e-9, "for as long as you like, until Q switches Overcharge on");
        assertEquals(112, FluxMath.charge(100, 12, 1, true), 1e-9, "Overcharge: on past 100");
        assertEquals(200, FluxMath.charge(195, 12, 1, true), 1e-9, "never past 200");
    }

    @Test void whatQDoes() {
        assertEquals(FluxMath.Press.START, FluxMath.press(false, 40, false), "Q starts charging");
        assertEquals(FluxMath.Press.STOP, FluxMath.press(true, 40, false), "Q below 100 W stops");
        assertEquals(FluxMath.Press.OVERCHARGE, FluxMath.press(true, 100, false), "Q again at 100 W: Overcharge");
        assertEquals(FluxMath.Press.STOP, FluxMath.press(true, 150, true), "Q in Overcharge: back to 100");
        assertEquals(FluxMath.Press.START, FluxMath.press(false, 100, false), "fed to 100 but not charging: Q starts (holding at 100)");
    }

    @Test void stoppingKeepsTheWattsButNotOvercharge() {
        assertEquals(64, FluxMath.stopped(64), 1e-9, "the watts you built stay");
        assertEquals(100, FluxMath.stopped(173), 1e-9, "Q in Overcharge: back to 100");
        assertTrue(FluxMath.canGround(100));
        assertFalse(FluxMath.canGround(99.5));
        assertFalse(FluxMath.canGround(100.5), "Overcharge fires the beam, never Ground");
        assertTrue(FluxMath.overcharged(101));
    }

    @Test void theReadout() {
        assertEquals("64W", FluxMath.readout(64.7));
        assertEquals("100W", FluxMath.readout(100));
        assertEquals("173%", FluxMath.readout(173.2), "Overcharge shows %");
        assertEquals(0, FluxMath.whiteness(100), 1e-9, "the gem's colour at 100 W ...");
        assertEquals(1, FluxMath.whiteness(200), 1e-9, "... white at 200");
    }

    // ------------------------------------------------------------------ the beam

    @Test void moreWattsMakeAStrongerBeam() {
        YamlConfiguration c = config();
        double low = c.getDouble("flux.beam.min-damage"), at100 = c.getDouble("flux.beam.damage-at-100"), max = c.getDouble("flux.overcharge.max-damage");
        assertEquals(90.0, max, 1e-9);
        double d30 = FluxMath.scale(30, low, at100, max), d100 = FluxMath.scale(100, low, at100, max), d150 = FluxMath.scale(150, low, at100, max);
        assertTrue(d30 < d100 && d100 < d150, "stronger and stronger");
        assertTrue(d100 > 2.5 * d30, "a 100 W beam is much stronger than a 30 W one");
        assertEquals(at100, d100, 1e-9);
        assertEquals(90.0, FluxMath.scale(200, low, at100, max), 1e-9, "exactly 200 W: flux.overcharge.max-damage");
        assertEquals(0, FluxMath.scale(0, low, at100, max), 1e-9);
        assertTrue(FluxMath.scale(100, 12, 30, 48) > FluxMath.scale(30, 12, 30, 48), "and longer");
        assertTrue(FluxMath.thickness(200) > FluxMath.thickness(100) && FluxMath.thickness(100) > FluxMath.thickness(10), "and thicker");
        assertTrue(FluxMath.afterglowTicks(200) > FluxMath.afterglowTicks(20), "and it glows longer");
    }

    @Test void theChargeGetsWilderButStaysWithin60() {
        int last = 0;
        for (int w = 0; w <= 200; w += 5) {
            int n = FluxMath.particles(w);
            assertTrue(n >= last, "never fewer at more watts");
            assertTrue(n <= 60, "within 60 a tick");
            last = n;
        }
        assertTrue(FluxMath.particles(10) < 8 && FluxMath.particles(190) >= 50);
    }

    // ------------------------------------------------------------------ Overdrive

    @Test void theOverdriveMeter() {
        FluxMath.Meter m = new FluxMath.Meter();
        assertEquals(3, m.add(3, 0, 6), 1e-9, "a hit");
        assertEquals(3, m.add(5, 500, 6), 1e-9, "capped at 6 a second");
        assertEquals(0, m.add(5, 900, 6), 1e-9, "nothing more this second");
        assertEquals(1, m.add(1, 1_000, 6), 1e-9, "a new second");
        for (long t = 2_000; t < 30_000; t += 1_000) m.add(10, t, 6);
        assertTrue(m.full(), "it fills");
        assertEquals(100, m.value, 1e-9, "never past 100");
        assertEquals(0, m.add(5, 40_000, 6), 1e-9);
        m.empty();
        assertFalse(m.full(), "Overdrive empties it");
        assertEquals(0, m.value, 1e-9);
        YamlConfiguration c = config();
        assertEquals(12, c.getInt("flux.overdrive.radius"));
        for (String k : List.of("per-hit", "per-block-mined", "per-block-moved", "max-per-second")) assertTrue(c.getDouble("flux.overdrive." + k) > 0, k);
    }

    @Test void overdriveIsFedByFightsMiningAndMovement() throws Exception {
        String f = read("FluxPower.java");
        for (String s : List.of("public void onFight(EntityDamageByEntityEvent e)", "public void onMine(BlockBreakEvent e)",
                "public void onMove(PlayerMoveEvent e)", "cfg(\"overdrive.per-hit\"", "cfg(\"overdrive.per-block-mined\"",
                "cfg(\"overdrive.per-block-moved\"", "cfg(\"overdrive.max-per-second\"")) assertTrue(f.contains(s), s);
        String g = read("GemListener.java");
        assertTrue(g.contains("if (plugin.flux() == null || !plugin.flux().overdriveFull(p))"), "right-click only when FULL");
        assertTrue(g.contains("plugin.flux().emptyOverdrive(p);\n            useOverdrive(p, energy);"), "it empties, and the effect is the old one");
    }

    // ------------------------------------------------------------------ Shocking Chance

    @Test void shockChanceAndItsLimit() {
        assertTrue(FluxMath.shock(0.05, 0.10, null, 0, 3_000), "a roll under 10%");
        assertFalse(FluxMath.shock(0.15, 0.10, null, 0, 3_000));
        assertFalse(FluxMath.shock(0.01, 0.10, 1_000L, 3_999, 3_000), "never twice within 3 s on the same target");
        assertTrue(FluxMath.shock(0.01, 0.10, 1_000L, 4_000, 3_000));
        java.util.Random r = new java.util.Random(4);
        int shocks = 0;
        for (int i = 0; i < 20_000; i++) if (FluxMath.shock(r.nextDouble(), 0.10, null, 0, 3_000)) shocks++;
        assertEquals(2_000, shocks, 150, "about 1 in 10");
        assertEquals(0.10, config().getDouble("flux.shock.chance"), 1e-9);
        assertEquals(2.0, config().getDouble("flux.shock.damage"), 1e-9);
    }

    @Test void theShockIsOnlyALightningLook() throws Exception {
        String f = read("FluxPower.java");
        assertTrue(f.contains("strikeLightningEffect("), "no fire, nobody else hurt");
        assertFalse(f.contains(".strikeLightning("));
        assertTrue(f.contains("if (v instanceof Player o && gl().isTrusted(p, o)) return; // trusted players are never shocked"));
        assertFalse(read("GemListener.java").contains("stunShots"), "the old arrow stun is gone");
    }

    // ------------------------------------------------------------------ the wiring

    @Test void qChargesAndTheGemStays() throws Exception {
        String g = read("GemListener.java");
        int i = g.indexOf("private boolean fluxCharge(PlayerDropItemEvent e) {");
        String body = g.substring(i, i + 700);
        assertTrue(body.contains("e.setCancelled(true);"), "the gem is never dropped");
        assertTrue(body.contains("plugin.flux().toggle(p)"));
        assertTrue(g.contains("if (fluxCharge(e)) return;"));
        String f = read("FluxPower.java");
        assertTrue(f.contains("if (p == null || p.isDead() || !holdsFlux(p)) { stop(en.getKey()); continue; } // switched away"));
        assertTrue(f.contains("public void onQuit(PlayerQuitEvent e) {\n        UUID id = e.getPlayer().getUniqueId();\n        stop(id);"));
        assertTrue(f.contains("\"No watts: charge with Q or feed ingots\""));
        assertTrue(f.contains("You can't ground a trusted player"));
        assertTrue(f.contains("for (int i = 0; i < 36; i++) items.add(inv.getItem(i));"), "Ground shuffles the hotbar and inventory only");
        assertEquals(8, config().getInt("cooldowns.beam"), "the beam's cooldown is short now");
        for (String s : List.of("beam-charge", "overcharge", "beam-max", "ground")) assertTrue(config().contains("sounds.flux." + s), s);
        assertEquals("ENTITY_WARDEN_SONIC_BOOM", config().getString("sounds.flux.beam-max.event"));
        assertEquals("ENTITY_GENERIC_EXPLODE", config().getString("sounds.flux.beam-max.layer.event"));
    }
}
