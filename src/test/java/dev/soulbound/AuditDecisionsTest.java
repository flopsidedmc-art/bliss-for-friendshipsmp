package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The audit decisions (D1-D11): the Pantheon link, event blocks, land keep-out, deaths, the hold, Broken, the glint. */
class AuditDecisionsTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    // ------------------------------------------------------------------ D1: powers in Pantheon events

    @Test void blockedInEventsAllowedOutside() {
        List<String> all = List.of("teleport", "astral", "repurpose"), motion = List.of("movement");
        assertFalse(EventBlock.blocked(null, all, motion, "rewind"), "not in an event: nothing is blocked");
        assertFalse(EventBlock.blocked(null, all, motion, "dash"));
        for (String p : List.of("rewind", "rift", "thunderstep", "temporal-divinity", "revenant-plunge", "projection", "unbounded", "repurpose"))
            assertTrue(EventBlock.blocked("duels", all, List.of(), p), p + " in every event");
        assertFalse(EventBlock.blocked("duels", all, List.of(), "dash"), "movement only in Motion");
        for (String p : List.of("dash", "double-jump", "drift", "golden-drill", "bloodlinking", "auratus-chains", "overdrive-speed", "speed-passive", "blur"))
            assertTrue(EventBlock.blocked("motion", all, motion, p), p + " in Motion");
        assertTrue(EventBlock.blocked("teams", List.of(), List.of("Dash"), "dash"), "single powers, any case");
        assertFalse(EventBlock.blocked("teams", List.of(), List.of(), "dash"));
    }

    @Test void eventBlockConfigAndHooks() throws Exception {
        YamlConfiguration c = config();
        assertEquals(List.of("teleport", "astral", "repurpose"), c.getStringList("event-blocked-powers.all"));
        assertEquals(List.of("movement"), c.getStringList("event-blocked-powers.motion"));
        String g = read("GemListener.java");
        for (String power : List.of("\"dash\"", "\"thunderstep\"", "\"double-jump\"", "\"drift\"", "\"projection\"", "\"unbounded\"", "\"overdrive-speed\""))
            assertTrue(g.contains("plugin.eventRefuses(p, " + power + ")"), power);
        // the check comes before the cooldown starts: no cooldown spent
        assertTrue(g.indexOf("if (plugin.eventRefuses(p, \"dash\")) return;") < g.indexOf("cdStart(p, \"dash\", cooldownMs(\"dash\", energy));"));
        assertTrue(g.contains("if (plugin.inPantheonEvent(host))"), "never Unbounded INTO a player in an event");
        assertTrue(read("RelayGem.java").contains("plugin.eventRefuses(p, \"rewind\")"));
        assertTrue(read("RelayRift.java").contains("plugin.eventRefuses(p, \"rift\")") && read("RelayRift.java").contains("if (plugin.inPantheonEvent(o)) return;"));
        assertTrue(read("TemporalDomain.java").contains("!plugin.inPantheonEvent(p) // never taken out of a Pantheon event"));
        assertTrue(read("GoldGem.java").contains("plugin.eventBlocks(v, \"repurpose\")"));
        assertTrue(read("AstralSpectate.java").contains("if (plugin.inPantheonEvent(p)) { end(p, SpectatorSessions.End.EVENT); continue; }"));
        assertTrue(read("EventBlock.java").contains("SmallCaps.of(\"Not in an event\")") && read("EventBlock.java").contains("now - last < 2000"));
    }

    // ------------------------------------------------------------------ D2: land keep-out

    @Test void landKeepOutBoxesAndSpawnRadius() {
        LandKeepOut k = new LandKeepOut()
                .box(LandKeepOut.Box.of("world", 10, 60, 10, 0, 70, 0))
                .circle("world", 0.5, 0.5, 48);
        assertTrue(k.contains("world", 5, 65, 5), "inside the box (corners in any order)");
        assertTrue(k.contains("world", 0, 60, 10), "a corner counts");
        assertTrue(k.contains("world", 40, 200, 0), "within 48 of spawn, any height");
        assertFalse(k.contains("world", 49, 64, 0), "outside the spawn radius");
        assertFalse(k.contains("world_nether", 5, 65, 5), "another world");
        assertTrue(new LandKeepOut().circle("world", 100.5, 100.5, 8).contains("world", 107, 0, 100), "the ritual site / pedestal radius");
        YamlConfiguration c = config();
        assertEquals(48, c.getInt("land.spawn-radius"));
        assertEquals(8, c.getInt("land.site-radius"));
        assertEquals(24, c.getInt("land.pantheon-radius"));
    }

    @Test void landKeepOutIsUsed() throws Exception {
        String w = read("LandWorks.java");
        assertTrue(w.contains("if (keepOut().contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return true;"));
        assertTrue(w.contains("plugin.pantheon().arenaPoints()") && w.contains("plugin.gold().ritualSite()") && w.contains("plugin.pedestal().center()"));
        assertTrue(read("BlissCommand.java").contains("case \"landprotect\" ->"));
    }

    // ------------------------------------------------------------------ D3, D10: deaths

    @Test void noDeathEffectsInEvents() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("boolean inEvent = plugin.inPantheonEvent(victim);"));
        assertTrue(g.contains("if (!inEvent && killer != null"), "no energy gain");
        assertTrue(g.contains("if (!inEvent) deathEnergyLoss(e);"), "no energy loss");
        assertTrue(read("GoldGem.java").contains("if (plugin.inPantheonEvent(p)) return; // a Pantheon event death"), "no socket release");
        assertTrue(read("RevenantMace.java").contains("if (plugin.inPantheonEvent(p)) return;"), "no shatter");
        assertTrue(read("BountyHunt.java").contains("if (plugin.inPantheonEvent(e.getEntity())) return; // a Pantheon event death: the hunts go on"));
    }

    @Test void everyGemIsKeptOnDeath() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("private final Map<UUID, List<ItemStack>> pendingRespawnGem"));
        assertTrue(g.contains("pendingRespawnGem.put(victim.getUniqueId(), toKeep);"), "all of them, not toKeep.get(0)");
        assertFalse(g.contains("toKeep.get(0)"));
        assertTrue(g.contains("else kept.add(GoldGem.decode(r.getString(id))); // the old one-gem format"));
        assertTrue(g.contains("private void giveKept(Player p, List<ItemStack> kept)"));
    }

    // ------------------------------------------------------------------ D5, D6: Soulbound events, the Finale

    @Test void soulboundEventsAndTheSeasonLeader() throws Exception {
        String m = read("SoulboundGemsPlugin.java");
        assertTrue(m.contains("public boolean isInSoulboundEvent(Player p)") && m.contains("public void holdActionBar(Player p, long ms)"));
        assertTrue(m.contains("finale != null && finale.inBattle(p) || revenant != null && revenant.inBossFight(p)"), "capture points don't count");
        String f = read("Finale.java");
        assertTrue(f.contains("Object r = seasonLeader();") && f.contains("link.seasonLeaders()"), "the Solstice season leader");
        assertFalse(f.contains("points.yml"), "no longer the Pantheon-only file");
        assertEquals(64, config().getInt("mythics.revenant.boss-radius"));
    }

    // ------------------------------------------------------------------ D7: the hold

    @Test void actionBarHold() throws Exception {
        ActionBarHold h = new ActionBarHold();
        UUID a = new UUID(0, 1);
        assertFalse(h.held(a, 1000));
        h.hold(a, 1500, 1000);
        assertTrue(h.held(a, 2499), "1.5 s");
        assertFalse(h.held(a, 2500), "then the HUD is back");
        h.hold(a, 5000, 0);
        h.hold(a, 100, 0);
        assertTrue(h.held(a, 4000), "a shorter hold never cuts a longer one");
        String g = read("GemListener.java");
        assertTrue(g.contains("if (held != null && !plugin.hudHeld(p))"), "the gem HUD waits");
        assertTrue(read("MythicListener.java").contains("if (plugin.hudHeld(p)) continue;"));
        assertTrue(read("RevenantMace.java").contains("if (!plugin.hudHeld(h)) h.sendActionBar("));
        assertTrue(read("CombatRules.java").contains("plugin.holdActionBar(p, 1500L);"), "combat tag start/end");
        assertTrue(read("RuleCore.java").contains("plugin.holdActionBar(p, 1500L);"), "rule refusals");
        assertTrue(read("SoulboundGemsPlugin.java").contains("return pantheon != null && pantheon.eventHud(p);"), "a Motion run / Teams match HUD wins");
    }

    // ------------------------------------------------------------------ D8, D9, D11

    @Test void glintAtMaxOnly() throws Exception {
        assertTrue(GemManager.glintAt(10, true));
        assertFalse(GemManager.glintAt(9, true));
        assertFalse(GemManager.glintAt(10, false), "energy.glint-at-max: false");
        assertTrue(read("GemManager.java").contains("m.setEnchantmentGlintOverride(glintAt(energy,"), "in refreshVisuals: every energy change");
        assertTrue(config().getBoolean("energy.glint-at-max"));
    }

    @Test void stalkerIsAdminOnly() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        assertEquals("bliss.admin", y.getString("commands.stalker.permission"));
        assertTrue(read("ShadowStalkerListener.java").contains("if (!hunter.hasPermission(\"bliss.admin\"))"));
    }

    @Test void broken() throws Exception {
        assertTrue(BrokenMath.broken(List.of(0)));
        assertTrue(BrokenMath.broken(List.of(0, 0)));
        assertFalse(BrokenMath.broken(List.of(0, 3)), "a working gem too: not Broken");
        assertFalse(BrokenMath.broken(List.of()), "no gem: not Broken");
        assertEquals(20.0, BrokenMath.damage(10, 2.0, 40), 1e-9, "doubled");
        assertEquals(40.0, BrokenMath.damage(30, 2.0, 40), 1e-9, "up to the ceiling");
        assertEquals(90.0, BrokenMath.damage(90, 2.0, 40), 1e-9, "a hit already over it stays as it was (never lowered)");
        assertEquals(180.0, BrokenMath.damage(90, 2.0, 0), 1e-9, "0: no ceiling");
        YamlConfiguration c = config();
        assertEquals(2.0, c.getDouble("energy.broken.ability-damage-multiplier"));
        assertTrue(c.getBoolean("energy.broken.include-mythics"));
        assertEquals(40.0, c.getDouble("energy.broken.max-damage"));
        String g = read("GemListener.java");
        assertTrue(g.contains("@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)\n    public void onBrokenVictim("), "after the cap (HIGH)");
        assertTrue(g.contains("if (!plugin.hudHeld(p)) p.sendActionBar(BROKEN_BAR);"));
        // every ability path is marked: standard gems and mythics
        assertTrue(g.contains("gemAbilityHits++;") && g.contains("mythicAbilityHits++; // the Auratus"));
        assertTrue(g.contains("asAbility(true, () -> { // the Heretic's saws and slams"));
        assertTrue(g.contains("asAbility(false, () -> victim.damage(blast, built)); // the Fireball"));
        assertTrue(g.contains("asAbility(false, () -> le.damage(2.0, p)); // Siphon"));
        assertTrue(g.contains("fb.getPersistentDataContainer().set(ABILITY_PROJECTILE"), "Meteor Shower");
        assertTrue(read("AuratusGem.java").contains("asAbility(true, () -> v.damage(finisherRaw(), p))"));
        assertTrue(read("RevenantMace.java").contains("asAbility(true, () -> v.damage(dmg, p)); // Plunge's landing"));
        assertTrue(read("GoldGem.java").contains("drillingUntil.put(p.getUniqueId(), now + 1500);"), "the Golden Drill");
        assertTrue(g.contains("inventoryGemSlot(p, g -> gems.getEnergy(g) < 10); // a Broken gem (0) can drink it too"), "a bottle ends Broken");
    }
}
