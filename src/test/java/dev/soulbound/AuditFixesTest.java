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

    @Test void deathCostsEnergyOnTheGemThatComesBack() throws Exception {
        String g = section(read("GemListener.java"), "public void onDeath(PlayerDeathEvent e) {", "public void onDeathKeptInventory(");
        int keep = g.indexOf("pendingRespawnGem.put(victim.getUniqueId(), toKeep.get(0));");
        int loss = g.indexOf("gems.setEnergy(kept, gems.getEnergy(kept) - loss);");
        assertTrue(keep > 0 && loss > keep, "the kept copy is taken first, then it loses the energy too");
    }

    @Test void keepInventoryNeverGivesASecondGem() throws Exception {
        String g = read("GemListener.java");
        String h = section(g, "@EventHandler(priority = EventPriority.MONITOR)\n    public void onDeathKeptInventory(", "/** Logged out on the death screen");
        assertTrue(h.contains("if (!e.getKeepInventory()) return;"));
        assertTrue(h.contains("if (pendingRespawnGem.remove(id) != null) saveRespawnGems();"), "the gem stayed in the inventory: no copy on respawn");
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

    @Test void pristineStagesTickOnceAMinute() throws Exception {
        String m = read("EnergyManager.java");
        assertTrue(m.contains("if (now - lastPassive < 60_000L) return;"));
        assertTrue(m.contains("units != null && units > 0 ? units : null"), "0 is cleared on disk");
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
        assertEquals(0.9, c.getDouble("energy.stage-cooldown-factor"));
        assertEquals(6.5, c.getDouble("prismatic-edge.reach"));
        assertEquals(30, c.getInt("shadow-stalker.base-seconds"));
    }
}
