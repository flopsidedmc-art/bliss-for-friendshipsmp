package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The fireball keeps the size it was fired at, for its whole flight; nothing else about it changes. */
class FireballLookTest {
    @Test void sizedByTheCharge() {
        assertEquals(0.75, FireballLook.scale(0, 0.75, 1.5), 1e-9);
        assertEquals(1.5, FireballLook.scale(1, 0.75, 1.5), 1e-9);
        assertEquals(1.125, FireballLook.scale(0.5, 0.75, 1.5), 1e-9);
        assertEquals(1.5, FireballLook.scale(3, 0.75, 1.5), 1e-9, "overcharge can't grow it");
    }

    @Test void setOnceAtLaunchAndTheFireballIsUntouched() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        int i = src.indexOf("private void fireballModel(");
        String model = src.substring(i, src.indexOf("    }\n\n", src.indexOf("runTaskTimer", i)));
        assertEquals(1, model.split("setTransformation\\(", -1).length - 1, "its size is set once, when it's fired");
        assertFalse(model.contains("setVelocity") || model.contains("setYield") || model.contains("setDirection"), "speed, range and explosion untouched");
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(c.getBoolean("fire.fireball.model.enabled"));
        assertEquals(1.0, c.getDouble("fire.fireball.explosion-power-min"), "explosion settings unchanged");
        assertEquals(3.0, c.getDouble("fire.fireball.explosion-power-max"));
    }
}
