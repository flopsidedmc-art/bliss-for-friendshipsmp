package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** The Upgrader, Trader and Bottle of Energy: the pack's textures, and they work on a gem you aren't holding. */
class SpecialItemsTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    @Test void itemModels() throws Exception {
        assertEquals(Map.of("upgrader", "upgrader", "trader", "trader", "energybottle", "energy_bottle"), GemManager.KIND_MODELS);
        String m = read("GemManager.java");
        assertTrue(m.contains("if (model != null) m.setItemModel(new NamespacedKey(\"blissgems\", model));"), "new items get the look");
        assertTrue(m.contains("public void styleKind(ItemStack item) {"), "old ones are restyled");
        String g = read("GemListener.java");
        assertTrue(g.contains("if (item != null) gems.styleKind(item);") && g.contains("if (off != null) gems.styleKind(off);"), "on join");
        assertTrue(g.contains("gems.styleKind(inHand);"), "and when used");
    }

    @Test void thePackHasThem() throws Exception {
        try (ZipFile z = new ZipFile("resource-pack/Relay-Temporal-Pack.zip")) {
            for (String n : new String[] {"upgrader", "trader", "energy_bottle"}) {
                assertNotNull(z.getEntry("assets/blissgems/items/" + n + ".json"), n + " item definition");
                assertNotNull(z.getEntry("assets/blissgems/models/item/" + n + ".json"), n + " model");
                assertNotNull(z.getEntry("assets/blissgems/textures/item/" + n + ".png"), n + " texture");
                String def = new String(z.getInputStream(z.getEntry("assets/blissgems/items/" + n + ".json")).readAllBytes());
                assertTrue(def.contains("\"blissgems:item/" + n + "\""), def);
            }
        }
    }

    @Test void noNeedToHoldTheGem() throws Exception {
        String g = read("GemListener.java");
        int s = g.indexOf("private void handleSpecial("), e = g.indexOf("case \"repairkit\" -> {", s);
        String three = g.substring(s, e);
        assertEquals(3, three.split("int slot = inventoryGemSlot\\(p, ").length - 1, "the bottle, the Upgrader and the Trader all search the inventory");
        assertFalse(three.contains("Hold a gem"), "no more 'hold a gem'");
        assertTrue(three.contains("p.getInventory().setItem(slot, replacement);"), "the Trader swaps the gem where it lies");
        assertTrue(g.contains("if (gems.isGem(it) && !gems.isGold(it) && ok.test(it)) return i;"), "never the Gold Gem");
        assertTrue(g.contains("order.add(inv.getHeldItemSlot());\n        order.add(40); // the offhand"), "a held gem still comes first");
        assertTrue(three.contains("kind.equals(\"repairkit\") && (gems.isGold("), "repairs are unchanged");
    }
}
