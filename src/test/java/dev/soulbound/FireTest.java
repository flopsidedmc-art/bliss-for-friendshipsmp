package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Fire revamp: the fireball's cooldown and charge, and Crisp's land (which blocks, the pits, underground, restore). */
class FireTest {
    // ------------------------------------------------------------------ fireball

    @Test void fireballCooldownIs120() throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(120, c.getInt("cooldowns.fireball"));
        String gl = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        assertTrue(gl.contains("case \"fireball\" -> 120;"), "the fallback too");
        String plugin = Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java"));
        assertTrue(plugin.contains("c.getInt(\"cooldowns.fireball\", 180) == 180) { c.set(\"cooldowns.fireball\", 120);"),
                "a server still on 180 moves to 120 once");
    }

    @Test void twentySecondsNormallyTenOnFire() {
        double c = 0;
        int steps = 0;
        while (c < 1.0 && steps < 1000) { c = FireballCharge.step(c, 0.1, false, 20, 10); steps++; }
        assertEquals(200, steps, "20 s in 0.1 s steps");
        c = 0;
        steps = 0;
        while (c < 1.0 && steps < 1000) { c = FireballCharge.step(c, 0.1, true, 20, 10); steps++; }
        assertEquals(100, steps, "on a fire block: 10 s");
        double half = 0;
        for (int i = 0; i < 50; i++) half = FireballCharge.step(half, 0.1, true, 20, 10); // 5 s on fire: half
        assertEquals(0.5, half, 1e-9);
        double after = FireballCharge.step(half, 0.1, false, 20, 10);
        assertEquals(0.505, after, 1e-9, "step off: slower again, from where it was (never reset)");
        assertEquals(1.0, FireballCharge.step(0.999, 5, true, 20, 10), 1e-9, "never over full");
    }

    @Test void theFireBlocks() {
        List<String> list = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).getStringList("fire.fireball.fire-blocks");
        for (String m : new String[] {"NETHERRACK", "MAGMA_BLOCK", "BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "GILDED_BLACKSTONE",
                "BLACKSTONE_SLAB", "BASALT", "SOUL_SAND", "SOUL_SOIL", "CRIMSON_NYLIUM", "WARPED_NYLIUM", "CAMPFIRE", "SOUL_CAMPFIRE", "FIRE", "LAVA"})
            assertTrue(FireballCharge.fireBlock(m, list), m);
        for (String m : new String[] {"GRASS_BLOCK", "STONE", "OBSIDIAN", "DIRT"}) assertFalse(FireballCharge.fireBlock(m, list), m);
        YamlConfiguration crisp = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String m : crisp.getConfigurationSection("fire.crisp.blocks").getKeys(false))
            assertTrue(FireballCharge.fireBlock(m, list), "Crisp's " + m + " sets up fast fireballs");
    }

    // ------------------------------------------------------------------ Crisp: which blocks

    @Test void onlyNaturalBlocksChange() {
        for (String m : new String[] {"GRASS_BLOCK", "DIRT", "PODZOL", "MOSS_BLOCK", "SAND", "GRAVEL", "STONE", "DEEPSLATE", "GRANITE",
                "TUFF", "OAK_LOG", "SPRUCE_LOG"}) assertTrue(LandMath.eligible(m, false, false), m);
        assertTrue(LandMath.eligible("OAK_LEAVES", false, false), "leaves that grew there");
        assertFalse(LandMath.eligible("OAK_LEAVES", false, true), "leaves someone placed");
        for (String m : new String[] {"CHEST", "WHITE_BED", "OAK_DOOR", "REDSTONE_WIRE", "OAK_SIGN", "WHITE_BANNER", "PLAYER_HEAD",
                "BEDROCK", "OAK_PLANKS", "COBBLESTONE", "STONE_BRICKS", "DIAMOND_ORE", "STRIPPED_OAK_LOG", "GLASS", "NETHERRACK", "LAVA", "WATER"})
            assertFalse(LandMath.eligible(m, false, false), m);
        assertFalse(LandMath.eligible("GRASS_BLOCK", true, false), "anything with a block entity, whatever it is");
        assertTrue(LandMath.plant("SHORT_GRASS") && LandMath.plant("POPPY"));
        assertFalse(LandMath.plant("TALL_GRASS"), "a double plant: the block under it is left alone");
        assertTrue(LandMath.waterPlant("SEAGRASS") && LandMath.waterPlant("KELP_PLANT"));
    }

    @Test void undergroundMeansASphere() {
        assertTrue(LandMath.underground(false, 3), "a cave: no sky, a ceiling 3 up");
        assertTrue(LandMath.underground(false, 6));
        assertFalse(LandMath.underground(false, 7), "a ceiling further than 6 up: the floor");
        assertFalse(LandMath.underground(false, 0), "no ceiling found");
        assertFalse(LandMath.underground(true, 2), "you can see the sky");
        assertTrue(LandMath.inSphere(3, 3, 2, 5) && !LandMath.inSphere(3, 3, 3, 5));
    }

    @Test void patchesTakeTheShareAndDifferEveryCast() {
        List<double[]> cells = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) if (LandMath.inDisc(dx, dz, 5)) cells.add(new double[] {dx, 0, dz});
        double[] weights = {40, 12, 12, 10, 8, 8, 5, 5};
        int[] a = LandMath.patches(cells, 0.45, 5, false, weights, new Random(1));
        int[] b = LandMath.patches(cells, 0.45, 5, false, weights, new Random(2));
        int na = 0, diff = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (a[i] >= 0) na++;
            if ((a[i] >= 0) != (b[i] >= 0)) diff++;
            assertTrue(a[i] < weights.length);
        }
        assertEquals(Math.round(cells.size() * 0.45), na, "the share");
        assertTrue(na < cells.size(), "never a solid disc");
        assertTrue(diff > 5, "different every cast");
        // patchy: a turned cell's neighbours turn far more often than the share alone would give
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) at.put((int) cells.get(i)[0] + ":" + (int) cells.get(i)[2], a[i]);
        int same = 0, pairs = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (a[i] < 0) continue;
            Integer right = at.get(((int) cells.get(i)[0] + 1) + ":" + (int) cells.get(i)[2]);
            if (right == null) continue;
            pairs++;
            if (right >= 0) same++;
        }
        assertTrue(same / (double) pairs > 0.55, "clumped: " + same + "/" + pairs);
    }

    // ------------------------------------------------------------------ Crisp: lava pits

    /** A test world: a solid floor at y 64 (and 63 under it), air above, with changes. */
    private static final class World implements CrispMath.Grid {
        final Set<String> air = new HashSet<>(), water = new HashSet<>(), solidExtra = new HashSet<>();
        public boolean solid(int x, int y, int z) { return !air.contains(x + ":" + y + ":" + z) && !water.contains(x + ":" + y + ":" + z) && (y <= 64 || solidExtra.contains(x + ":" + y + ":" + z)); }
        public boolean open(int x, int y, int z) { return !solid(x, y, z) && !water.contains(x + ":" + y + ":" + z); }
        public boolean water(int x, int y, int z) { return water.contains(x + ":" + y + ":" + z); }
    }

    @Test void lavaPitsOnlyOnTheFloorEnclosedAndNeverUnderYou() {
        World w = new World();
        int[] far = {20, 65, 20};
        assertNotNull(CrispMath.pit(5, 64, 5, 1, w, far, List.of()), "one block, on the floor");
        assertEquals(5, CrispMath.pit(5, 64, 5, 3, w, far, List.of()).size(), "3 across: a plus of 5");
        assertEquals(4, CrispMath.pit(5, 64, 5, 2, w, far, List.of()).size());

        assertNull(CrispMath.pit(5, 64, 5, 1, w, new int[] {5, 65, 5}, List.of()), "never at the caster's feet");
        assertNull(CrispMath.pit(5, 64, 5, 1, w, new int[] {6, 65, 6}, List.of()), "nor right beside them");
        assertNotNull(CrispMath.pit(5, 64, 5, 1, w, new int[] {8, 65, 5}, List.of()));
        assertNull(CrispMath.pit(5, 64, 5, 1, w, far, List.of(new int[] {5, 65, 5})), "never under someone standing there");

        World hole = new World();
        hole.air.add("6:64:5"); // a hole beside it: the lava could flow out
        assertNull(CrispMath.pit(5, 64, 5, 1, hole, far, List.of()), "enclosed on every side");
        World cave = new World();
        cave.air.add("5:63:5"); // nothing under it
        assertNull(CrispMath.pit(5, 64, 5, 1, cave, far, List.of()), "solid below");
        World roofed = new World();
        roofed.solidExtra.add("5:65:5"); // a block on top: it isn't the floor
        assertNull(CrispMath.pit(5, 64, 5, 1, roofed, far, List.of()), "the floor only");
        World wet = new World();
        wet.water.add("6:64:5");
        assertNull(CrispMath.pit(5, 64, 5, 1, wet, far, List.of()), "never next to water");
        World wetAbove = new World();
        wetAbove.water.add("6:65:5");
        assertNull(CrispMath.pit(5, 64, 5, 1, wetAbove, far, List.of()), "nor where water could run in from above");
    }

    // ------------------------------------------------------------------ Crisp: restore

    @Test void everythingGoesBackExactlyUnlessAPlayerChangedIt() {
        Map<String, String> world = new HashMap<>(); // "x:y:z" -> block data; a material is the part after "minecraft:" up to "["
        world.put("0:64:0", "minecraft:grass_block[snowy=false]");
        world.put("1:64:0", "minecraft:dirt");
        world.put("2:64:0", "minecraft:stone");
        world.put("1:65:0", "minecraft:air");
        world.put("3:64:0", "minecraft:water[level=0]");
        LandLedger l = new LandLedger();
        l.add(new LandLedger.Entry(LandLedger.Phase.LAND, 0, 64, 0, "NETHERRACK", world.get("0:64:0")));
        l.add(new LandLedger.Entry(LandLedger.Phase.LAND, 1, 64, 0, "MAGMA_BLOCK", world.get("1:64:0")));
        l.add(new LandLedger.Entry(LandLedger.Phase.LAND, 2, 64, 0, "BASALT", world.get("2:64:0")));
        l.add(new LandLedger.Entry(LandLedger.Phase.PIT, 2, 64, 0, "LAVA", "minecraft:basalt[axis=y]")); // a pit on a turned block
        l.add(new LandLedger.Entry(LandLedger.Phase.FIRE, 1, 65, 0, "FIRE", world.get("1:65:0")));
        l.add(new LandLedger.Entry(LandLedger.Phase.WATER, 3, 64, 0, "AIR", world.get("3:64:0")));
        world.put("0:64:0", "minecraft:netherrack");
        world.put("1:64:0", "minecraft:air"); // a player broke this one
        world.put("2:64:0", "minecraft:lava[level=0]");
        world.put("1:65:0", "minecraft:fire[age=7]");
        world.put("3:64:0", "minecraft:air");

        List<String> order = new ArrayList<>();
        int n = l.restore(p -> material(world.get(p[0] + ":" + p[1] + ":" + p[2])), (e, data) -> {
            world.put(e.key(), data);
            order.add(e.phase().name());
        });
        assertEquals(5, n, "all but the broken one");
        assertEquals("minecraft:grass_block[snowy=false]", world.get("0:64:0"), "the same block and state");
        assertEquals("minecraft:air", world.get("1:64:0"), "the player's change is left as it is");
        assertEquals("minecraft:stone", world.get("2:64:0"), "the pit, then the ground under it");
        assertEquals("minecraft:air", world.get("1:65:0"), "Crisp's fire goes out");
        assertEquals("minecraft:water[level=0]", world.get("3:64:0"), "the water comes back exactly");
        assertEquals(List.of("FIRE", "PIT", "LAND", "LAND", "WATER"), order, "fire out first, the water last");
        assertEquals(0, l.restore(p -> material(world.get(p[0] + ":" + p[1] + ":" + p[2])), (e, data) -> fail("twice")), "only once");
    }

    @Test void pendingRestoresSurviveARestart() {
        LandLedger l = new LandLedger();
        LandLedger.Entry e = new LandLedger.Entry(LandLedger.Phase.WATER, -3, 12, 40, "OAK_STAIRS",
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]");
        l.add(e);
        LandLedger.Entry back = LandLedger.load(l.saveAll().get(0));
        assertEquals(e, back, "saved and read back the same");
        assertNull(LandLedger.load("nonsense"));
        String land = read("src/main/java/dev/soulbound/LandWorks.java");
        assertTrue(land.contains("void start()") && land.contains("restoreNow("), "on start, land a restart left changed goes back");
        assertTrue(land.contains("crisp-restore.yml"), "including what the last version saved");
        String plugin = read("src/main/java/dev/soulbound/SoulboundGemsPlugin.java");
        assertTrue(plugin.contains("land.start()") && plugin.contains("land.shutdown()"));
    }

    @Test void theCrispConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(c.getBoolean("fire.crisp.land"));
        assertEquals(5, c.getInt("fire.crisp.radius"));
        assertEquals(0.45, c.getDouble("fire.crisp.chance"));
        assertEquals(0.15, c.getDouble("fire.crisp.fire-chance"));
        assertEquals(2, c.getInt("fire.crisp.lava-pits-max"));
        assertEquals(0.5, c.getDouble("fire.crisp.lava-pit-chance"));
        assertTrue(c.getBoolean("fire.crisp.evaporate"));
        assertEquals(30, c.getInt("fire.crisp.restore-after-seconds"));
        assertEquals(8, c.getConfigurationSection("fire.crisp.blocks").getKeys(false).size());
        assertEquals("BLOCK_FIRE_EXTINGUISH", c.getString("sounds.fire.crisp-evaporate.event"));
        assertEquals("BLOCK_LAVA_EXTINGUISH", c.getString("sounds.fire.crisp-cool.event"));
    }

    private static String material(String data) {
        String m = data.substring(data.indexOf(':') + 1);
        int b = m.indexOf('[');
        return (b < 0 ? m : m.substring(0, b)).toUpperCase(java.util.Locale.ROOT);
    }

    private static String read(String p) {
        try { return Files.readString(Path.of(p)); } catch (Exception e) { return ""; }
    }
}
