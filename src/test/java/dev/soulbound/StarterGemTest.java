package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Starter gems: a random Tier 1 standard gem on the first join, once. */
class StarterGemTest {
    @Test void onlyStandardGems() {
        List<GemType> c = StarterGem.choices();
        assertEquals(8, c.size());
        for (GemType t : c) assertFalse(t.isMythic(), t.name());
        assertFalse(c.contains(GemType.AURATUS) || c.contains(GemType.HERETIC) || c.contains(GemType.RELAY));
    }

    @Test void onceAndOnlyWithoutAGem() {
        assertTrue(StarterGem.due(false, false), "first join with no gem: one");
        assertFalse(StarterGem.due(false, true), "already carries one (from before): just recorded");
        assertFalse(StarterGem.due(true, false), "had one already: losing it never hands out another");
    }

    @Test void tierOneAndWired() throws Exception {
        String s = Files.readString(Path.of("src/main/java/dev/soulbound/StarterGem.java"));
        assertTrue(s.contains("plugin.gems().createGem(type, tier, energy)"), "the configured tier and energy");
        assertTrue(Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java")).contains("new StarterGem(this)"));
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(c.getBoolean("starter-gem.enabled"));
        assertEquals(1, c.getInt("starter-gem.tier"));
        assertEquals(5, c.getInt("starter-gem.energy"));
        assertEquals("random", c.getString("starter-gem.type"));
        assertTrue(s.contains("waiting.put(p.getUniqueId(), GoldGem.encode(gem)); // never on the ground"), "a full inventory: held, never dropped");
        assertFalse(s.contains("dropItemNaturally"), "never dropped");
    }

    @Test void whereAndWhich() {
        assertEquals(StarterGem.Spot.OFF_HAND, StarterGem.spot(true, true));
        assertEquals(StarterGem.Spot.OFF_HAND, StarterGem.spot(true, false));
        assertEquals(StarterGem.Spot.INVENTORY, StarterGem.spot(false, true));
        assertEquals(StarterGem.Spot.HELD, StarterGem.spot(false, false), "full: held until there's room");
        java.util.Random r = new java.util.Random(1);
        assertEquals(GemType.FIRE, StarterGem.pick("fire", StarterGem.choices(), r), "a fixed type");
        assertNotEquals(GemType.AURATUS, StarterGem.pick("auratus", StarterGem.choices(), r), "never a mythic, even asked for");
        List<GemType> pool = StarterGem.choices(List.of("astra", "FIRE"));
        assertEquals(6, pool.size());
        assertFalse(pool.contains(GemType.ASTRA) || pool.contains(GemType.FIRE), "excluded");
        for (int i = 0; i < 50; i++) assertTrue(pool.contains(StarterGem.pick("random", pool, r)));
        assertNull(StarterGem.pick("random", List.of(), r), "everything excluded: nothing");
    }
}
