package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Balance 2: the keys the code reads exist in the shipped config, with the new numbers. */
class BalanceConfigTest {
    private static final YamlConfiguration CFG = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

    @Test void balanceKeys() {
        assertTrue(CFG.getBoolean("ability-damage.pierce-armor", false));
        assertEquals(7.0, CFG.getDouble("mythics.heretic.bloodsaws.damage"));
        assertEquals(11.0, CFG.getDouble("mythics.heretic.bloodlinking.damage"));
        assertEquals(2.5, CFG.getDouble("astra.dagger-hit-damage"));
        assertEquals(1.0, CFG.getDouble("abilities.blur.clone-damage"));
        assertFalse(CFG.contains("balance-version"), "the migration writes this; shipping it would skip the migration");
    }

    @Test void codeReadsTheSameKeys() throws Exception {
        String gl = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        for (String k : new String[] {"ability-damage.pierce-armor", "astra.dagger-hit-damage", "abilities.blur.clone-damage"})
            assertTrue(gl.contains("\"" + k + "\""), k);
        // The Auratus stays tuned with armour: its hits go through abilityHit, which doesn't pierce.
        assertTrue(gl.contains("abilityDamage(victim, base, source, false);"));
    }
}
