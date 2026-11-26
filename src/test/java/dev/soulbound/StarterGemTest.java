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
        assertTrue(s.contains("createGem(type, 1, plugin.getConfig().getInt(\"energy.start\", 5))"), "Tier 1, the starting energy");
        assertTrue(Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java")).contains("new StarterGem(this)"));
        assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).getBoolean("starter-gem.enabled"));
    }
}
