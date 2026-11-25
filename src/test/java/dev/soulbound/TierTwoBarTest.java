package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The normal gems' action bar: Tier 2 order (left, ability, right) and the Tier 2 crystal. */
class TierTwoBarTest {
    private static final Map<GemType, String[]> CRYSTALS = Map.of( // Tier 1, Tier 2 (the pack's glyphs)
            GemType.ASTRA, new String[] {"ꐽ", "ꐾ"}, GemType.FIRE, new String[] {"ꐿ", "ꑀ"},
            GemType.FLUX, new String[] {"ꑁ", "ꑂ"}, GemType.LIFE, new String[] {"ꑃ", "ꑄ"},
            GemType.PUFF, new String[] {"ꑅ", "ꑆ"}, GemType.SPEED, new String[] {"ꑇ", "ꑈ"},
            GemType.STRENGTH, new String[] {"ꑉ", "ꑊ"}, GemType.WEALTH, new String[] {"ꑋ", "ꑌ"});

    @Test void tierTwoIsLeftAbilityRight() {
        // Fire: fireball, crisp, campfire. Each entry keeps its own icon and its own timer.
        String fireball = HudStatus.entry("ꐥ", 4_000, false);
        String crisp = HudStatus.entry("ꑀ", 0, false);
        String campfire = HudStatus.entry("ꐦ", 0, true);
        assertEquals(List.of(fireball, crisp, campfire), HudStatus.gemBar(true, fireball, crisp, campfire));
        assertTrue(HudStatus.gemBar(true, fireball, crisp, campfire).get(0).contains("4s"), "the timer stays on the fireball");
        assertTrue(HudStatus.gemBar(true, fireball, crisp, campfire).get(1).contains(HudStatus.READY), "Ready! stays on the ability");
        assertTrue(HudStatus.gemBar(true, fireball, crisp, campfire).get(2).contains("…"), "and ... on the campfire");
        assertEquals(List.of(crisp), HudStatus.gemBar(false, fireball, crisp, campfire), "Tier 1: the ability only");
    }

    @Test void tierTwoUsesTheTierTwoCrystal() {
        for (var e : CRYSTALS.entrySet()) {
            GemType t = e.getKey();
            String t1 = e.getValue()[0], t2 = e.getValue()[1];
            assertEquals(t1, HudStatus.crystal(t, 1), t + " Tier 1");
            assertEquals(t2, HudStatus.crystal(t, 2), t + " Tier 2");
            assertEquals(t1, HudStatus.abilityIcon(t, 1, t1, t2), t + ": Tier 1 keeps its crystal");
            assertEquals(t2, HudStatus.abilityIcon(t, 2, t1, t2), t + ": Tier 2 uses ability-tier2");
            assertEquals(t2, HudStatus.abilityIcon(t, 2, t1, ""), t + ": no ability-tier2 in the config: the pack's Tier 2 crystal");
            assertEquals(t2, HudStatus.abilityIcon(t, 2, t1, null), t.name());
        }
        assertEquals("X", HudStatus.abilityIcon(GemType.FIRE, 2, "ꐿ", "X"), "a configured glyph wins");
        assertEquals("", HudStatus.abilityIcon(GemType.FIRE, 1, "", "X"), "Tier 1 uses the configured ability icon");
    }

    @Test void theConfigHasBothCrystals() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (var e : CRYSTALS.entrySet()) {
            String base = "actionbar.icons." + e.getKey().name() + ".";
            assertEquals(e.getValue()[0], c.getString(base + "ability"), base + "ability");
            assertEquals(e.getValue()[1], c.getString(base + "ability-tier2"), base + "ability-tier2");
        }
    }

    @Test void everyGemUsesTheOrder() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        int i = src.indexOf("private void sendActionbar(Player p, ItemStack gem)");
        String bar = src.substring(i, src.indexOf("private String captureHud", i));
        assertEquals(7, bar.split("HudStatus.gemBar\\(", -1).length - 1, "the seven gems other than Flux");
        assertFalse(bar.contains("icon(type, \"ability\")"), "the ability icon always goes through abilityIcon (the tier)");
        assertTrue(src.contains("abilityIcon(GemType.FLUX, tier)"), "Flux's burst too");
    }
}
