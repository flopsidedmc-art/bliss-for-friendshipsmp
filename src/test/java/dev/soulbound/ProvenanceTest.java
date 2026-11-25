package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static dev.soulbound.ProvenanceMath.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Item provenance: what's tracked, every status change, and the lore line. */
class ProvenanceTest {
    private static final UUID MOON = UUID.randomUUID(), STEVE = UUID.randomUUID(), ALEX = UUID.randomUUID();

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    /** Moon crafts it (or loots, buys, fishes it): theirs, ORIGINAL. */
    private static ProvenanceMath.Prov crafted() { return ProvenanceMath.arrive(null, MOON, "Moon", false); }

    // ------------------------------------------------------------------ what's tracked

    @Test void armourWeaponsAndToolsOnly() {
        for (String m : List.of("DIAMOND_SWORD", "NETHERITE_AXE", "IRON_PICKAXE", "STONE_SHOVEL", "GOLDEN_HOE", "LEATHER_HELMET",
                "CHAINMAIL_CHESTPLATE", "IRON_LEGGINGS", "NETHERITE_BOOTS", "TURTLE_HELMET", "ELYTRA", "SHIELD", "BOW", "CROSSBOW",
                "TRIDENT", "MACE", "SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "BRUSH")) assertTrue(ItemProvenanceListener.isTracked(m), m);
        for (String m : List.of("DIAMOND", "STICK", "AMETHYST_SHARD", "IRON_HORSE_ARMOR", "WOLF_ARMOR", "CARVED_PUMPKIN", "PLAYER_HEAD",
                "TOTEM_OF_UNDYING", "GOLDEN_APPLE", "ARROW", "COMPASS", "ENDER_PEARL")) assertFalse(ItemProvenanceListener.isTracked(m), m);
    }

    @Test void theGameItemsAreNeverTracked() throws Exception {
        String src = read("ItemProvenanceListener.java");
        int i = src.indexOf("boolean tracked(ItemStack it) {");
        String body = src.substring(i, i + 600);
        for (String s : List.of("isGem(it)", "isLiveItem(it)", "isEdge(it)", "kFinaleKit")) assertTrue(body.contains(s), s);
    }

    // ------------------------------------------------------------------ the status

    @Test void craftedIsOriginal() {
        ProvenanceMath.Prov p = crafted();
        assertEquals(MOON, p.owner());
        assertEquals("Moon", p.name());
        assertEquals(ORIGINAL, p.status());
        assertSame(p, ProvenanceMath.arrive(p, MOON, "Moon", false), "the owner taking their own item out of a chest: nothing changes");
    }

    @Test void theOwnersDeathDropIsStolen() {
        ProvenanceMath.Prov p = ProvenanceMath.arrive(crafted(), STEVE, "Steve", false);
        assertEquals(STOLEN, p.status());
        assertEquals(MOON, p.owner(), "the owner never changes");
        assertEquals("Moon", p.name());
    }

    @Test void aQDropPickedUpByAFriendIsGifted() {
        ProvenanceMath.Prov p = ProvenanceMath.arrive(crafted(), STEVE, "Steve", true);
        assertEquals(GIFTED, p.status());
        assertEquals(MOON, p.owner());
    }

    @Test void someoneElseTakingItOutOfAChestIsStolen() {
        ProvenanceMath.Prov inChest = crafted(); // the owner put it in: still ORIGINAL underneath (it only shows STORED)
        assertEquals(STOLEN, ProvenanceMath.arrive(inChest, ALEX, "Alex", false).status());
    }

    @Test void backWithTheOwnerItsOriginalAgain() {
        ProvenanceMath.Prov stolen = ProvenanceMath.arrive(crafted(), STEVE, "Steve", false);
        assertEquals(ORIGINAL, ProvenanceMath.arrive(stolen, MOON, "Moon", false).status(), "given back");
        ProvenanceMath.Prov gifted = ProvenanceMath.arrive(crafted(), STEVE, "Steve", true);
        assertEquals(ORIGINAL, ProvenanceMath.arrive(gifted, MOON, "Moon", true).status(), "a gift returned");
        assertEquals(ORIGINAL, ProvenanceMath.arrive(stolen, MOON, "Moon2", false).status());
        assertEquals("Moon", ProvenanceMath.arrive(stolen, MOON, "Moon2", false).name(), "the name from when it became theirs");
    }

    @Test void passedOnItKeepsItsStatus() {
        ProvenanceMath.Prov stolen = ProvenanceMath.arrive(crafted(), STEVE, "Steve", false);
        ProvenanceMath.Prov passed = ProvenanceMath.arrive(stolen, ALEX, "Alex", false); // Steve's death drop, or his chest
        assertEquals(STOLEN, passed.status(), "stolen from Moon stays stolen from Moon");
        assertEquals(MOON, passed.owner());
        assertEquals(STOLEN, ProvenanceMath.arrive(stolen, ALEX, "Alex", false).status(), "Steve dropping it for Alex isn't Moon's gift");
        ProvenanceMath.Prov gifted = ProvenanceMath.arrive(crafted(), STEVE, "Steve", true);
        assertEquals(GIFTED, ProvenanceMath.arrive(gifted, ALEX, "Alex", false).status(), "a gift passed on stays gifted by Moon");
    }

    @Test void itemsFromBeforeBecomeTheNextHoldersOriginal() {
        ProvenanceMath.Prov p = ProvenanceMath.arrive(null, ALEX, "Alex", true);
        assertEquals(new ProvenanceMath.Prov(ALEX, "Alex", ORIGINAL), p, "nobody owned it: whoever has it next, even from a drop");
        assertNull(ProvenanceMath.Status.of("stored"), "the old STORED status means nobody owns it yet");
        assertEquals(STOLEN, ProvenanceMath.Status.of("stolen"));
    }

    @Test void whatCountsForABounty() {
        ProvenanceMath.Prov stolen = ProvenanceMath.arrive(crafted(), STEVE, "Steve", false);
        ProvenanceMath.Prov gifted = ProvenanceMath.arrive(crafted(), STEVE, "Steve", true);
        assertTrue(ProvenanceMath.counts(stolen, MOON), "stolen from Moon counts toward hunting Moon");
        assertTrue(ProvenanceMath.counts(gifted, MOON), "gifted by Moon too");
        assertFalse(ProvenanceMath.counts(crafted(), MOON), "an ORIGINAL item doesn't");
        assertFalse(ProvenanceMath.counts(stolen, ALEX), "an item linked to anyone else doesn't");
        assertFalse(ProvenanceMath.counts(null, MOON));
    }

    // ------------------------------------------------------------------ the lore line

    @Test void theLineIsSmallCaps() {
        assertEquals("ᴏʀɪɢɪɴᴀʟ · Moon", ProvenanceMath.line("Original", "·", "Moon"));
        assertEquals("ꜱᴛᴏʟᴇɴ ꜰʀᴏᴍ Moon", ProvenanceMath.line("Stolen", "from", "Moon"));
        assertEquals("ɢɪꜰᴛᴇᴅ ʙʏ Moon", ProvenanceMath.line("Gifted", "by", "Moon"));
        assertEquals("ꜱᴛᴏʀᴇᴅ · Moon", ProvenanceMath.line("Stored", "·", "Moon"));
        List<String> words = List.of("Original", "Stolen", "Gifted", "Stored");
        assertTrue(ProvenanceMath.isLine("ꜱᴛᴏʟᴇɴ ꜰʀᴏᴍ Moon", words), "found again, so it's replaced and never doubled");
        assertTrue(ProvenanceMath.isLine("ꜱᴛᴏʀᴇᴅ · Moon", words));
        assertFalse(ProvenanceMath.isLine("Stolen goods", words), "other lore is left alone");
        assertFalse(ProvenanceMath.isLine("ꜱharpness V", words));
    }

    @Test void theLineIsColourCodedWithNoItalics() {
        Component c = ItemProvenanceListener.line("Stolen", "from", "Moon",
                TextColor.fromHexString("#E04848"), TextColor.fromHexString("#9A9A9A"), TextColor.fromHexString("#FFFFFF"));
        assertEquals("ꜱᴛᴏʟᴇɴ ꜰʀᴏᴍ Moon", PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(TextDecoration.State.FALSE, c.decoration(TextDecoration.ITALIC), "lore is italic unless told not to be");
        List<Component> parts = c.children();
        assertEquals(TextColor.fromHexString("#E04848"), parts.get(0).color(), "the status word in its colour");
        assertEquals(TextColor.fromHexString("#9A9A9A"), parts.get(2).color(), "the joining word gray");
        assertEquals(TextColor.fromHexString("#FFFFFF"), parts.get(4).color(), "the name white");
        assertEquals("Moon", PlainTextComponentSerializer.plainText().serialize(parts.get(4)));
    }

    @Test void theConfigHasThePromptsWordsAndColours() {
        YamlConfiguration c = config();
        assertTrue(c.getBoolean("provenance.enabled"));
        assertTrue(c.getBoolean("provenance.show-line"));
        assertEquals("#9A9A9A", c.getString("provenance.joiner-color"));
        String[][] want = {{"original", "Original", "·", "#D8C3A0"}, {"stolen", "Stolen", "from", "#E04848"},
                {"gifted", "Gifted", "by", "#FFC846"}, {"stored", "Stored", "·", "#8FD3FF"}};
        for (String[] w : want) {
            assertEquals(w[1], c.getString("provenance." + w[0] + ".word"), w[0]);
            assertEquals(w[2], c.getString("provenance." + w[0] + ".joiner"), w[0]);
            assertEquals(w[3], c.getString("provenance." + w[0] + ".color"), w[0]);
        }
    }

    @Test void theListenerFollowsTheRules() throws Exception {
        String src = read("ItemProvenanceListener.java");
        assertTrue(src.contains("boolean given = p != null && p.owner().toString().equals(dropper);"), "a gift is the owner's own drop");
        assertTrue(src.contains("ProvenanceMath.arrive(read(it), p.getUniqueId(), p.getName(), false)"), "everything a player holds is settled");
        assertTrue(src.contains("inv.getLocation() == null"), "plugin menus (the bounty offering) are never containers");
        assertTrue(src.contains("SmallCaps.isHistoryLine(LEGACY.serialize(c))"), "the old STOLEN by lines are replaced");
        assertFalse(src.contains("InventoryMoveItemEvent"), "hoppers moving items change nothing");
    }
}
