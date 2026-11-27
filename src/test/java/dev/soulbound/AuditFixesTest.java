package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The pre-launch audit's fixes (AUDIT.md): gem loss and dupes, data loss, stuck players, config and commands. */
class AuditFixesTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static String section(String src, String from, String to) {
        int a = src.indexOf(from);
        assertTrue(a >= 0, from);
        int b = src.indexOf(to, a + from.length());
        return src.substring(a, b < 0 ? src.length() : b);
    }

    @Test void deathCostsEnergyWhereverTheGemIs() throws Exception {
        String g = read("GemListener.java");
        String loss = section(g, "private void deathEnergyLoss(PlayerDeathEvent e) {", "/** Every gem kept through a death");
        assertTrue(loss.contains("if (kept != null) for (ItemStack g : kept) if (standardGem(g)) losing.add(g);"), "the copies that come back");
        assertTrue(loss.contains("for (ItemStack it : victim.getInventory().getContents()) if (standardGem(it)) losing.add(it);"),
                "keepInventory: every slot (hands, hotbar, inventory, armour)");
        assertTrue(loss.contains("for (ItemStack d : e.getDrops()) if (standardGem(d)) losing.add(d);"), "keep-on-death off: the drops");
        assertTrue(loss.contains("\" energy → \" + after + \"/10\""), "the player is told");
        String death = section(g, "public void onDeath(PlayerDeathEvent e) {", "public void onDeathKeptInventory(");
        assertFalse(death.contains("findGem(victim)"), "no longer only the held gem");
        assertTrue(section(g, "public void onDeathKeptInventory(", "private void deathEnergyLoss(").contains("if (!inEvent) deathEnergyLoss(e);"),
                "once keepInventory is final, and never in a Pantheon event");
    }

    @Test void keepInventoryNeverGivesASecondGem() throws Exception {
        String g = read("GemListener.java");
        String h = section(g, "public void onDeathKeptInventory(", "private void deathEnergyLoss(");
        assertTrue(h.contains("if (e.getKeepInventory() && pendingRespawnGem.remove(id) != null) saveRespawnGems();"), "no copy on respawn");
        assertTrue(h.contains("inv.setItemInMainHand(mythic)") && h.contains("inv.setItemInOffHand(mythic)"), "a held mythic isn't lost either");
    }

    @Test void gemsStayOutOfContainersBundlesFramesAndWorkstations() throws Exception {
        String g = read("GemListener.java");
        String click = section(g, "public void onChestStore(InventoryClickEvent e) {", "private static boolean isBundle(");
        assertTrue(click.contains("e.isShiftClick() && top != InventoryType.CRAFTING"), "shift-click into an open container");
        assertTrue(click.contains("e.getHotbarButton() >= 0") && click.contains("ClickType.SWAP_OFFHAND"), "number keys and the offhand key");
        assertTrue(click.contains("isBundle(e.getCursor()) && bound(e.getCurrentItem())"), "bundles");
        assertTrue(g.contains("public void onChestDrag(org.bukkit.event.inventory.InventoryDragEvent e)"), "drags");
        assertTrue(g.contains("en instanceof org.bukkit.entity.ItemFrame || en instanceof org.bukkit.entity.Allay"), "item frames and allays");
        assertTrue(g.contains("public void onGemToStand(") && g.contains("Material.DECORATED_POT"), "armour stands and pots");
        for (String t : List.of("CRAFTER", "FURNACE", "SHULKER_BOX")) assertTrue(section(g, "static boolean storage(", "}\n    }").contains(t), t);
        for (String t : List.of("WORKBENCH", "CRAFTING", "SMITHING", "LOOM", "MERCHANT", "BEACON"))
            assertTrue(section(g, "static boolean workstation(", "}\n    }").contains(t), t);
        assertTrue(g.contains("if (gems.isGem(it)) { e.getInventory().setResult(null); return; }"), "never crafted away");
        assertTrue(g.contains("public void onGemSmith(") && g.contains("public void onGemAnvilMaterial("), "no trims, no anvil repairs with a gem");
    }

    @Test void thunderstepNeverEndsInAWall() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("Location target = thunderstepTarget(p.getLocation(), 3.0);"));
        String t = section(g, "static Location thunderstepTarget(", "private Player aimPlayerWithin(");
        assertTrue(t.contains("if (!feet.isPassable() || !head.isPassable()) break;"), "stops before anything solid, so never through a wall");
    }

    @Test void pocketsAreSaved() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("dataFile(\"pockets.yml\")"));
        assertTrue(g.contains("public void onPocketsClose(") && g.contains("savePockets(); // what's in everyone's Pockets survives the restart"));
    }

    @Test void nothingLeftBehind() throws Exception {
        assertTrue(read("GemListener.java").contains("horse.setPersistent(false);"), "the Drift horse");
        assertTrue(read("GemListener.java").contains("public void onDriftHorseDeath("), "and its saddle doesn't drop");
        assertTrue(read("MythicListener.java").contains("stand.setPersistent(false);"), "the Bloodsaw stands");
        assertTrue(read("SoulboundGemsPlugin.java").contains("if (mythics != null) mythics.shutdown();"));
    }

    @Test void pristineStagesAreGone() throws Exception {
        assertFalse(Files.exists(Path.of("src/main/java/dev/soulbound/EnergyManager.java")), "removed (D8): the real 0-10 energy is untouched");
        String plugin = read("SoulboundGemsPlugin.java");
        assertFalse(plugin.contains("EnergyManager") || plugin.contains("energy.passiveTick()") || plugin.contains("energy.save()"));
        assertFalse(new YamlConfiguration().contains("energy.stage-cooldown-factor"));
        assertFalse(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).contains("energy.stage-cooldown-factor"));
    }

    @Test void invisibleKillerScrambled() throws Exception {
        assertTrue(read("RevenantMace.java").contains("\" at the hands of \" + plugin.shownName(killer)"));
    }

    @Test void mythicConfigHasAnExecutor() throws Exception {
        assertTrue(read("SoulboundGemsPlugin.java").contains("getCommand(\"mythicconfig\").setExecutor("));
    }

    @Test void experimentalApiLogsOnce() throws Exception {
        String m = read("GemManager.java");
        assertTrue(m.contains("catch (LinkageError | RuntimeException ex) {") && m.contains("consumableApiBroken = true;"));
    }

    /** Every key read by its full path is in config.yml, so admins can see and tune it. */
    @Test void everyReadKeyIsInTheConfig() throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Pattern read = Pattern.compile("getConfig\\(\\)\\.(?:get|is)\\w*\\(\\s*\"([a-z0-9_.\\-]+)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/dev/soulbound"))) {
            for (Path f : files.toList()) {
                Matcher m = read.matcher(Files.readString(f));
                while (m.find()) if (!c.contains(m.group(1), true)) missing.add(f.getFileName() + ": " + m.group(1));
            }
        }
        assertEquals(List.of(), missing);
        assertTrue(c.getBoolean("energy.glint-at-max"));
        assertEquals(6.5, c.getDouble("prismatic-edge.reach"));
        assertEquals(30, c.getInt("shadow-stalker.base-seconds"));
    }
}
