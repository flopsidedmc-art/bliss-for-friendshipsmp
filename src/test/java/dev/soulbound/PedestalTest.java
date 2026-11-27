package dev.soulbound;

import dev.soulbound.PedestalLayout.Part;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The repair Pedestal, Bliss style: the layout, its open and closed blocks, the hidden base, the heart, the guards. */
class PedestalTest {
    /** The layout as drawn (north at the top, x left to right, the heart in the middle). */
    private static final String[] GRID = {
            ". . . R R R R R . . .",
            ". . R Q Q C Q Q R . .",
            ". R C S P S P S C R .",
            "R Q S P L O L P S Q R",
            "R Q P L F I F L P Q R",
            "R C S O I H I O S C R",
            "R Q P L F I F L P Q R",
            "R Q S P L O L P S Q R",
            ". R C S P S P S C R .",
            ". . R Q Q C Q Q R . .",
            ". . . R R R R R . . .",
    };

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    @Test void theLayoutMatchesTheGrid() {
        int blocks = 0;
        Map<Part, Integer> counts = new EnumMap<>(Part.class);
        for (int row = 0; row < 11; row++) {
            String[] cells = GRID[row].split(" ");
            for (int col = 0; col < 11; col++) {
                int dx = col - 5, dz = row - 5;
                Part p = PedestalLayout.part(dx, dz);
                if (cells[col].equals(".")) { assertNull(p, dx + "," + dz + " is outside"); continue; }
                assertEquals(Part.valueOf(cells[col]), p, dx + "," + dz);
                counts.merge(p, 1, Integer::sum);
                blocks++;
            }
        }
        assertEquals(97, blocks, "all 97 blocks");
        assertEquals(97, PedestalLayout.floor().size());
        assertNull(PedestalLayout.part(6, 0), "nothing past the grid");
        assertEquals(Map.of(Part.H, 1, Part.I, 4, Part.O, 4, Part.F, 4, Part.L, 8, Part.R, 28, Part.C, 8, Part.Q, 16, Part.S, 12, Part.P, 12), counts);
    }

    @Test void everyPartsOpenAndClosedBlock() {
        String[][] want = {
                {"H", "BEACON", "BEDROCK"}, {"I", "GILDED_BLACKSTONE", "BLACKSTONE"}, {"O", "CHERRY_WOOD", "BASALT"},
                {"F", "GOLD_BLOCK", "NETHERITE_BLOCK"}, {"L", "RAW_GOLD_BLOCK", "COBBLED_DEEPSLATE"},
                {"S", "CHERRY_PLANKS", "DEEPSLATE_TILES"}, {"P", "STRIPPED_CHERRY_WOOD", "POLISHED_DEEPSLATE"},
                {"C", "CHISELED_QUARTZ_BLOCK", "CHISELED_POLISHED_BLACKSTONE"}, {"Q", "QUARTZ_BLOCK", "POLISHED_BLACKSTONE"},
                {"R", "QUARTZ_SLAB", "POLISHED_BLACKSTONE_SLAB"}};
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String[] w : want) {
            Part p = Part.valueOf(w[0]);
            assertEquals(w[1], PedestalLayout.block(p, true), w[0] + " open");
            assertEquals(w[2], PedestalLayout.block(p, false), w[0] + " closed");
            assertEquals(w[1], c.getString("pedestal.blocks." + w[0] + ".open"), "config " + w[0]);
            assertEquals(w[2], c.getString("pedestal.blocks." + w[0] + ".closed"), "config " + w[0]);
        }
        assertEquals('x', PedestalLayout.axis(2, 0), "basalt on the east/west arms runs along x");
        assertEquals('x', PedestalLayout.axis(-2, 0));
        assertEquals('z', PedestalLayout.axis(0, 2), "and along z on the north/south arms");
        assertEquals(1.5, c.getDouble("pedestal.transform-seconds"));
        assertEquals(1.6, c.getDouble("pedestal.particle-radius"));
    }

    @Test void theHiddenBase() {
        List<int[]> base = PedestalLayout.base();
        assertEquals(10, base.size());
        int netherite = 0;
        for (int[] b : base) {
            if (b[1] == -1) {
                assertTrue(Math.abs(b[0]) <= 1 && Math.abs(b[2]) <= 1, "3 x 3 right under the heart");
                assertEquals("NETHERITE_BLOCK", PedestalLayout.baseBlock(b[1]));
                netherite++;
            } else {
                assertArrayEquals(new int[] {0, -2, 0}, b, "debris under the centre of that");
                assertEquals("ANCIENT_DEBRIS", PedestalLayout.baseBlock(b[1]));
            }
        }
        assertEquals(9, netherite, "a full beacon base: the beam");
    }

    @Test void theWave() {
        double rim = PedestalLayout.maxDistance();
        assertTrue(PedestalLayout.turned(0, 0.0, true), "opening: the heart first");
        assertFalse(PedestalLayout.turned(rim, 0.5, true), "the rim last");
        assertTrue(PedestalLayout.turned(rim, 1.0, true));
        assertTrue(PedestalLayout.turned(rim, 0.0, false), "closing: the rim first");
        assertFalse(PedestalLayout.turned(0, 0.5, false), "the heart last");
        assertTrue(PedestalLayout.turned(0, 1.0, false));
        assertEquals(0, PedestalLayout.floor().get(0)[0], "the floor list starts at the heart");
    }

    @Test void theHeartCounter() {
        int breaks = 0;
        for (int i = 1; i <= 4; i++) {
            PedestalLayout.Break b = PedestalLayout.heartBreak(breaks, true);
            assertEquals(i, b.breaks());
            assertFalse(b.fifth());
            breaks = b.breaks();
        }
        PedestalLayout.Break fifth = PedestalLayout.heartBreak(breaks, true);
        assertTrue(fifth.fifth() && fifth.closes(), "5 breaks end an active rite");
        assertEquals(0, fifth.breaks(), "and the count starts again");
        PedestalLayout.Break weathered = PedestalLayout.heartBreak(4, false);
        assertTrue(weathered.fifth());
        assertFalse(weathered.closes(), "no rite: it weathers it");
    }

    @Test void nothingElseBreaksNothingDrops() throws Exception {
        String p = read("Pedestal.java");
        assertTrue(p.contains("if (isHeart(b) || !isPart(b)) return;") && p.contains("e.setCancelled(true);\n        e.getPlayer().sendMessage(ChatColor.GRAY + \"The Pedestal does not break.\");"),
                "every part but the heart: no break (so no drops), in every game mode");
        assertTrue(p.contains("&& b.getZ() == center.getBlockZ() && b.getType() == Material.BEACON;"), "the beacon is the heart (bedrock when closed never breaks)");
        assertTrue(p.contains("public void onHeartBreak(BlockBreakEvent e) {\n        if (!isHeart(e.getBlock())) return;\n        e.setCancelled(true);"), "the heart drops nothing and stays");
        for (String guard : List.of("onBlastProtect(EntityExplodeEvent e) { e.blockList().removeIf(this::isPart); }",
                "onBlockBlastProtect(BlockExplodeEvent e) { e.blockList().removeIf(this::isPart); }",
                "onPush(BlockPistonExtendEvent e)", "onPull(BlockPistonRetractEvent e)", "onEntityChange(EntityChangeBlockEvent e) { if (isPart(e.getBlock())) e.setCancelled(true); }",
                "onBurn(BlockBurnEvent e) { if (isPart(e.getBlock())) e.setCancelled(true); }", "onPlaceInto(BlockPlaceEvent e)", "onBucket(PlayerBucketEmptyEvent e)"))
            assertTrue(p.contains(guard), guard);
        assertTrue(p.contains("if (isHeart(b)) {\n            e.setCancelled(true);"), "no beacon menu");
        assertTrue(p.contains("beacon.setPrimaryEffect(null);") && p.contains("beacon.setSecondaryEffect(null);"), "no beacon effects for anyone");
        assertTrue(p.contains("tool.endsWith(\"_AXE\")"), "no stripping the cherry wood");
        assertFalse(p.contains("inside(b, 12)"), "only the build's own blocks are protected now");
        assertTrue(p.contains("The Pedestal weathers the assault (5/5)."), "the old 0/5 message");
        assertTrue(read("LandWorks.java").contains("plugin.pedestal().center()"), "Crisp and Vortex keep away (land.site-radius 8)");
    }

    @Test void movingRestartsAndOldSaves() throws Exception {
        String p = read("Pedestal.java");
        assertTrue(p.contains("if (center != null) removeBuild(); // somewhere else: the old build goes, the ground comes back"));
        assertTrue(p.contains("originals.putIfAbsent(key(o[0], o[1], o[2]), at(o[0], o[1], o[2]).getBlockData().getAsString());"), "what was there is recorded");
        assertTrue(p.contains("if (!bliss) migrate();\n        else placeAll(open);"), "an old save is rebuilt once; a restart keeps the state");
        assertTrue(p.contains("if (crown.getType() == Material.DIAMOND_BLOCK) crown.setType(Material.AIR, false);"), "the old crown goes (the beam)");
        assertTrue(p.contains("wave.cancel();\n            wave = null;\n            placeAll(waveOpening);"), "disable mid-wave: finished at once");
        String plugin = read("SoulboundGemsPlugin.java");
        assertTrue(plugin.contains("pedestal.start();") && plugin.contains("if (pedestal != null) pedestal.shutdown();"));
        assertFalse(read("PedestalDrop.java").contains("PedestalLayout"), "the Finale's merge pedestal is untouched");
    }
}
