package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fixes from testing, round 1: Astra's daggers and settings, Strength's visuals, Puff's hit cooldown, /bliss and /blissop. */
class FixesRoundOneTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    @Test void theDaggersFollowOnTheirOwnTick() throws Exception {
        String plugin = read("SoulboundGemsPlugin.java"), g = read("GemListener.java");
        assertTrue(plugin.contains("runTaskTimer(this, () -> listener.tickAstraDaggers(), 1L, 1L)"), "every tick, not the 10-tick gem tick");
        int t = g.indexOf("public void tick() {");
        assertFalse(g.substring(t, g.indexOf("private void applyPassive(")).contains("tickAstraDaggers()"), "not from the slow tick any more");
        assertTrue(g.contains("boolean move = (++daggerTicks & 1) == 0;"), "every 2 of its own ticks, whatever the server's tick count");
        assertFalse(g.contains("Bukkit.getCurrentTick() % 2 == 0"));
    }

    @Test void astraSettings() throws Exception {
        assertEquals(60, config().getInt("cooldowns.projection"));
        assertTrue(read("GemListener.java").contains("case \"projection\" -> 60;"));
        assertFalse(read("GemListener.java").contains("Unbounded exit"), "leaving Unbounded no longer disables your gem");
        assertFalse(read("AstralSpectate.java").contains("unboundedExit("));
        assertEquals("BLOCK_AMETHYST_BLOCK_CHIME", config().getString("sounds.astra.daggers-summon.event"));
        assertNotEquals("BLOCK_AMETHYST_BLOCK_RESONATE", config().getString("sounds.astra.daggers-summon.event"), "that's the Aegis' sound");
    }

    @Test void strengthVisualsOnly() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("chadCritFlash(p, e.getEntity()); // the dots flash red"), "the 4th-hit crit");
        assertTrue(g.contains("fromRGB(0xD01A1A)") && g.contains("flash ? 1.3f : 1.0f"), "red, slightly bigger");
        assertTrue(g.contains("frailerLine(p, le);") && g.contains("fromRGB(0x8B0000)") && g.contains("fromRGB(0x111111)"), "Frailer's red and black line");
        assertTrue(g.contains("nullifyRing(o);") && g.contains("fromRGB(0xC81E1E)"), "Nullify's ring at their feet");
        int f = g.indexOf("private void frailerLine(");
        assertTrue(g.substring(f, f + 1400).contains("for (int t = 0; t < 6; t++)"), "about 6 ticks");
        int n = g.indexOf("private void nullifyRing(");
        assertTrue(g.substring(n, n + 900).contains("for (int t = 0; t < 10; t++)"), "about 10 ticks");
        assertTrue(g.contains("e.setDamage(e.getDamage() * 2.0);\n                p.sendMessage(ChatColor.RED + GemNames.tx(\"CHAD STRENGTH x2! (hit 4)\"));"),
                "what the powers do is unchanged");
    }

    @Test void breezyBashHitHasACooldown() throws Exception {
        assertEquals(8, config().getInt("abilities.breezy-bash.hit-cooldown-seconds"));
        String g = read("GemListener.java");
        assertTrue(g.contains("&& e.getEntity() instanceof LivingEntity le2 && cdRemain(p, \"bash-hit\") <= 0) {"));
        assertTrue(g.contains("cdStart(p, \"bash-hit\""));
        assertTrue(g.contains("cdRemain(p, \"bash-hit\") > 0) bar.add(hudEntry("), "on the action bar");
    }

    @Test void onlyFinishedCommands() throws Exception {
        assertEquals(List.of("upgrader", "trader", "repairkit", "energybottle", "eobsidian"), BlissCommand.ITEMS, "only items that do something");
        String b = read("BlissCommand.java");
        for (String gone : List.of("createSpiritDagger()", "createRemote()", "createTotemToken()", "createFragment()", "createReviveItem()", "createRevivalItem()"))
            assertFalse(b.contains(gone), gone);
        assertTrue(b.contains("// Everyone sees every subcommand and its arguments; the admin ones still need bliss.admin to run."));
        assertTrue(b.contains("case \"give\" -> {\n                if (!s.hasPermission(\"bliss.admin\"))"), "running an admin one still needs the permission");
        String o = read("BlissOpCommand.java");
        for (String gone : List.of("\"assessment\"", "\"energy\"", "\"test\"", "\"gold\"")) assertFalse(o.contains(gone), gone);
        for (String kept : List.of("\"give\"", "\"mythic\"", "\"progression\"", "\"border\"", "\"stalker\"", "\"pris\"", "\"mace\"", "\"reloadconfig\""))
            assertTrue(o.contains(kept), kept);
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertFalse(yml.contains("<reload|edge|energy|give|border>"), "the old /blissop usage");
    }
}
