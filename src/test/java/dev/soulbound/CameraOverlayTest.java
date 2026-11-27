package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The finale and Turmoil overlays as camera overlays: fade timing, and every texture they can ask for is in the pack. */
class CameraOverlayTest {
    private static final Path TEXTURES = Path.of("resource-pack/overlay/assets/blissgems/textures/overlay");

    @Test void fadeInClimbsAndFadeOutFalls() {
        int steps = 4, ticks = 30, last = 0;
        for (int t = 1; t <= ticks; t++) {
            int level = FinaleMath.fadeInLevel(t, ticks, steps);
            assertTrue(level >= 1 && level <= steps);
            assertTrue(level >= last, "never fainter while fading in");
            last = level;
        }
        assertEquals(1, FinaleMath.fadeInLevel(1, ticks, steps));
        assertEquals(steps, FinaleMath.fadeInLevel(ticks, ticks, steps));
        last = steps + 1;
        for (int t = 1; t < ticks; t++) {
            int level = FinaleMath.fadeOutLevel(t, ticks, steps);
            assertTrue(level >= 1 && level <= steps);
            assertTrue(level <= last, "never brighter while fading out");
            last = level;
        }
        assertEquals(0, FinaleMath.fadeOutLevel(ticks, ticks, steps), "gone when the fade-out ends");
        assertEquals(0, FinaleMath.fadeOutLevel(1, ticks, 0), "no fade frames: cut");
    }

    /** A camera overlay whose texture is missing paints the whole screen magenta: every one must be in the pack. */
    @Test void everyOverlayTextureIsInThePack() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        int steps = c.getInt("finale.overlay.fade-steps");
        for (String style : new String[] {"gold", "red"}) {
            int frames = c.getInt("finale.overlay.frame-count." + style);
            assertTrue(frames >= 1, style + " has frames");
            for (int f = 0; f < frames; f++) assertTrue(Files.exists(TEXTURES.resolve(style + "_" + f + ".png")), style + "_" + f);
            for (int k = 1; k <= steps; k++) assertTrue(Files.exists(TEXTURES.resolve(style + "_fade_" + k + ".png")), style + "_fade_" + k);
        }
    }

    /** The overlay is never a title any more (titles scale with the GUI and the haze ended up off-screen). */
    @Test void overlaysAreNotTitles() throws Exception {
        for (String f : new String[] {"Finale.java", "Turmoil.java"}) {
            String s = Files.readString(Path.of("src/main/java/dev/soulbound", f));
            assertFalse(s.contains("0xE100") || s.contains("0xE110"), f + " still draws the overlay as a title glyph");
            assertTrue(s.contains("blissgems:overlay/") || s.contains("OverlayAnim."), f + " uses the camera overlay textures");
        }
        assertTrue(Files.readString(Path.of("src/main/java/dev/soulbound/OverlayAnim.java")).contains("\"blissgems:overlay/\" + style"),
                "the shared frame and fade names (the Finale and the repurpose)");
    }
}
