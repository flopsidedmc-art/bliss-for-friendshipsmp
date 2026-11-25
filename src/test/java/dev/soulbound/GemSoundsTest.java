package dev.soulbound;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The sound overhaul's rules, checked against the shipped config.yml. */
class GemSoundsTest {
    /** Sounds the spec deliberately gives to more than one gem family. Everything else belongs to one family. */
    private static final Map<String, Set<String>> ALLOWED_SHARED = Map.of(
            "ITEM_MACE_SMASH_GROUND_HEAVY", Set.of("strength", "heretic", "auratus"),
            "ITEM_TRIDENT_RETURN", Set.of("speed", "auratus"),
            "ENTITY_WARDEN_SONIC_BOOM", Set.of("flux", "auratus"),
            "ENTITY_ENDERMAN_TELEPORT", Set.of("astra", "relay"),
            "BLOCK_BEACON_DEACTIVATE", Set.of("general", "relay"),
            "BLOCK_AMETHYST_BLOCK_RESONATE", Set.of("astra", "auratus")); // Astra's daggers-summon, as asked
    private static final List<String> BANNED = List.of("ENTITY_ELDER_GUARDIAN_CURSE", "ENTITY_WITHER_SPAWN",
            "ENTITY_LIGHTNING_BOLT_THUNDER", "BLOCK_PORTAL_TRAVEL", "ENTITY_EXPERIENCE_ORB_PICKUP");

    private static ConfigurationSection sounds() {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).getConfigurationSection("sounds");
    }

    /** gem -> every sound part (main and layer) of every entry. */
    private static Map<String, List<ConfigurationSection>> parts() {
        Map<String, List<ConfigurationSection>> out = new TreeMap<>();
        ConfigurationSection root = sounds();
        for (String gem : root.getKeys(false)) {
            ConfigurationSection g = root.getConfigurationSection(gem);
            if (g == null) continue;
            for (String ability : g.getKeys(false)) {
                ConfigurationSection s = g.getConfigurationSection(ability);
                if (s == null) continue;
                out.computeIfAbsent(gem, k -> new java.util.ArrayList<>()).add(s);
                if (s.isConfigurationSection("layer")) out.get(gem).add(s.getConfigurationSection("layer"));
                ConfigurationSection fb = s.getConfigurationSection("fallback"); // played when pack sounds are off
                if (fb != null) {
                    out.get(gem).add(fb);
                    if (fb.isConfigurationSection("layer")) out.get(gem).add(fb.getConfigurationSection("layer"));
                }
            }
        }
        return out;
    }

    @Test void everyEventIsARealSoundAndPitchIsInRange() throws Exception {
        Class<?> sound = Class.forName("org.bukkit.Sound", false, getClass().getClassLoader()); // no registry needed
        int n = 0;
        for (List<ConfigurationSection> list : parts().values()) for (ConfigurationSection s : list) {
            String ev = s.getString("event");
            assertNotNull(ev, s.getCurrentPath());
            if (!GemSounds.isPack(ev)) assertNotNull(sound.getField(ev), ev);
            double pitch = s.getDouble("pitch", 1.0);
            assertTrue(pitch >= 0.5 && pitch <= 2.0, s.getCurrentPath() + " pitch " + pitch);
            assertTrue(s.getDouble("volume", 1.0) > 0, s.getCurrentPath());
            n++;
        }
        assertTrue(n > 90, "all " + n + " sounds checked");
    }

    @Test void atMostOneLayerAndNoBannedSounds() {
        for (List<ConfigurationSection> list : parts().values()) for (ConfigurationSection s : list) {
            assertFalse(s.isConfigurationSection("layer") && s.getConfigurationSection("layer").isConfigurationSection("layer"), s.getCurrentPath());
            assertFalse(BANNED.contains(s.getString("event")), s.getCurrentPath() + " uses " + s.getString("event"));
        }
    }

    @Test void noSoundIsSharedBetweenGemsUnlessTheSpecSaysSo() {
        Map<String, Set<String>> families = new HashMap<>();
        for (Map.Entry<String, List<ConfigurationSection>> e : parts().entrySet()) {
            if (e.getKey().equals("other") || e.getKey().equals("capture") || e.getKey().equals("prophet")) continue; // not gems
            for (ConfigurationSection s : e.getValue()) families.computeIfAbsent(s.getString("event"), k -> new TreeSet<>()).add(e.getKey());
        }
        for (Map.Entry<String, Set<String>> e : families.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Set<String> allowed = ALLOWED_SHARED.get(e.getKey());
            assertNotNull(allowed, e.getKey() + " is shared by " + e.getValue());
            assertTrue(allowed.containsAll(e.getValue()), e.getKey() + " is shared by " + e.getValue() + ", allowed " + allowed);
        }
    }

    @Test void relayUsesItsOwnPackSoundsWithFallbacks() {
        ConfigurationSection relay = sounds().getConfigurationSection("relay");
        assertEquals("blissgems:relay.rewind", relay.getString("rewind.event"));
        assertEquals("blissgems:relay.time_dilate", relay.getString("time-dilate.event"));
        assertEquals("BLOCK_BEACON_DEACTIVATE", relay.getString("rewind.fallback.event"));
        assertEquals("BLOCK_BEACON_AMBIENT", relay.getString("time-dilate.fallback.event"));
        assertFalse(relay.isConfigurationSection("rewind.layer"), "the pack sound plays on its own");
    }

    @Test void packSoundsAndPitch() {
        assertTrue(GemSounds.isPack("blissgems:relay.rewind"));
        assertFalse(GemSounds.isPack("minecraft:entity.ravager.roar"));
        assertFalse(GemSounds.isPack("ENTITY_RAVAGER_ROAR"));
        assertEquals(0.5, GemSounds.clampPitch(0.1));
        assertEquals(2.0, GemSounds.clampPitch(3));
        assertEquals(1.2, GemSounds.clampPitch(1.2));
    }
}
