package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** The animated overlays: the repurpose's per-gem loop and fades, and the Finale's gold/red haze as before. */
class OverlayAnimTest {
    private static final String[] GEMS = {"astra", "fire", "flux", "life", "puff", "speed", "strength", "wealth"};

    /** What the player sees, tick by tick, from tick 1 to {@code ticks}. */
    private static List<String> run(String style, int frames, int frameTicks, int fadeIn, int steps, int ticks) {
        List<String> out = new ArrayList<>();
        int[] state = new int[2];
        for (int t = 1; t <= ticks; t++) {
            String s = OverlayAnim.step(t, style, frames, frameTicks, fadeIn, steps, true, state);
            if (s != null) out.add(s.substring("blissgems:overlay/".length()));
        }
        return out;
    }

    @Test void frameNamesPerGem() throws Exception {
        try (ZipFile z = new ZipFile("resource-pack/Relay-Temporal-Pack.zip")) {
            for (String gem : GEMS) {
                String style = RepurposeMath.overlayStyle("repurpose_<gem>", gem.toUpperCase());
                assertEquals("repurpose_" + gem, style);
                for (int i = 0; i < 12; i++) {
                    assertEquals("blissgems:overlay/repurpose_" + gem + "_" + i, OverlayAnim.texture(style, i));
                    assertNotNull(z.getEntry("assets/blissgems/textures/overlay/repurpose_" + gem + "_" + i + ".png"), gem + " frame " + i + " is in the pack");
                }
                for (int f = 1; f <= 4; f++) {
                    assertEquals("blissgems:overlay/repurpose_" + gem + "_fade_" + f, OverlayAnim.fadeTexture(style, f));
                    assertNotNull(z.getEntry("assets/blissgems/textures/overlay/repurpose_" + gem + "_fade_" + f + ".png"), gem + " fade " + f);
                }
            }
            assertNotNull(z.getEntry("assets/blissgems/items/upgrader.json"), "the merge kept the special items");
        }
    }

    @Test void fadeInThenLoop() {
        List<String> seen = run("repurpose_astra", 12, 2, 8, 4, 8 + 2 * 13);
        assertEquals(List.of("repurpose_astra_fade_1", "repurpose_astra_fade_2", "repurpose_astra_fade_3", "repurpose_astra_fade_4"), seen.subList(0, 4),
                "fades in 1 (faintest) to 4 over 8 ticks");
        List<String> loop = seen.subList(4, seen.size());
        for (int i = 0; i < loop.size(); i++) assertEquals("repurpose_astra_" + ((i + 1) % 12), loop.get(i), "the loop, in order, wrapping 11 -> 0");
        assertTrue(loop.contains("repurpose_astra_0") && loop.contains("repurpose_astra_11"));
        assertEquals(List.of("repurpose_fire_0"), run("repurpose_fire", 12, 2, 0, 4, 1), "no fade: the first frame at once");
    }

    @Test void theFinaleIsUnchanged() throws Exception {
        // finale defaults: gold, 5 frames, a frame every 4 ticks, a 30-tick fade in over 4 steps
        List<String> gold = run("gold", 5, 4, 30, 4, 52);
        assertEquals(List.of("gold_fade_1", "gold_fade_2", "gold_fade_3", "gold_fade_4", "gold_1", "gold_2", "gold_3", "gold_4", "gold_0", "gold_1"), gold);
        String f = Files.readString(Path.of("src/main/java/dev/soulbound/Finale.java"));
        assertTrue(f.contains("private String overlayStyle() { return pulseRed || \"red\".equalsIgnoreCase(skyS(\"overlay.style\", \"gold\")) ? \"red\" : \"gold\"; }"),
                "still gold, or red (the Revenant's pulse)");
        assertTrue(f.contains("OverlayAnim.step(skyTicks, style, overlayFrames(style), frameTicks, fadeIn, steps, skyTicks >= flashUntil, overlayState)"),
                "the same steps, held during a flash");
        assertTrue(f.contains("int fallback = \"gold\".equals(style) ? 5 : 6;"));
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals("gold", c.getString("finale.overlay.style"));
        assertEquals(5, c.getInt("finale.overlay.frame-count.gold"));
        assertEquals(6, c.getInt("finale.overlay.frame-count.red"));
        assertEquals(4, c.getInt("finale.overlay.frame-ticks"));
    }

    @Test void repurposeConfigAndCleanup() throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals("repurpose_<gem>", c.getString("gold.repurpose.overlay.style"));
        assertEquals(12, c.getInt("gold.repurpose.overlay.frame-count"));
        assertEquals(4, c.getInt("gold.repurpose.overlay.fade-steps"));
        assertEquals(2, c.getInt("gold.repurpose.overlay.frame-ticks"));
        assertEquals(8, c.getInt("gold.repurpose.overlay.fade-in-ticks"));
        for (String gem : GEMS) assertFalse(c.contains("gold.repurpose.overlay." + gem), "no per-gem texture keys");
        assertTrue(Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java")).contains("String k = \"gold.repurpose.overlay.\" + t.name().toLowerCase(java.util.Locale.ROOT);"),
                "old configs lose them too");
        String o = Files.readString(Path.of("src/main/java/dev/soulbound/CameraOverlay.java"));
        assertTrue(o.contains("if (anims.isEmpty() && animTask != null) { animTask.cancel(); animTask = null; }"), "no task keeps running");
        assertTrue(o.contains("anims.remove(p.getUniqueId());\n        if (shown.remove(p.getUniqueId()) != null) Bukkit.getScheduler().runTask(plugin, () -> sendRealHelmet(p));"), "ends at respawn");
        assertTrue(o.contains("anims.remove(e.getPlayer().getUniqueId());"), "and on logout");
        assertTrue(o.contains("if (a == null || !a.throughDeath) { clear(p); return; }"), "plays on through the death screen");
        assertTrue(o.contains("if (anims.containsKey(p.getUniqueId())) return; // their own animation"), "only the victim, never overwritten by the finale");
    }
}
