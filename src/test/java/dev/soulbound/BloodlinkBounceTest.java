package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.soulbound.HereticMath.AfterSlam.BOUNCE;
import static dev.soulbound.HereticMath.AfterSlam.END;
import static org.junit.jupiter.api.Assertions.*;

/** Heretic Bloodlinking: the bounce rule, touching, and the new numbers. */
class BloodlinkBounceTest {
    @Test void aHitBouncesAtOnce() {
        assertEquals(BOUNCE, HereticMath.afterSlam(1, false, 1, 3), "hit someone: straight back up, even with nobody linked");
        assertEquals(BOUNCE, HereticMath.afterSlam(5, true, 2, 3));
    }

    @Test void aMissKeepsTheOldRule() {
        assertEquals(END, HereticMath.afterSlam(0, false, 1, 3), "nobody hit, nobody linked: it ends");
        assertEquals(BOUNCE, HereticMath.afterSlam(0, true, 1, 3), "nobody hit, but someone linked earlier: it bounces, as before");
    }

    @Test void maxSlamsIsRespected() {
        assertEquals(END, HereticMath.afterSlam(3, true, 3, 3), "the third slam is the last, hit or not");
        assertEquals(END, HereticMath.afterSlam(1, false, 1, 1));
        int slams = 0;
        HereticMath.AfterSlam next = BOUNCE;
        while (next == BOUNCE) { slams++; next = HereticMath.afterSlam(1, true, slams, 3); }
        assertEquals(3, slams, "hitting every time: exactly max-slams slams");
    }

    @Test void touchingIncludesWhatYouFlewThrough() {
        BoundingBox zombie = new BoundingBox(9.7, 64, -0.3, 10.3, 65.95, 0.3);
        BoundingBox before = new BoundingBox(7.7, 66, -0.3, 8.3, 67.8, 0.3), now = new BoundingBox(11.7, 62, -0.3, 12.3, 63.8, 0.3);
        assertFalse(before.overlaps(zombie) || now.overlaps(zombie), "this tick's and last tick's boxes both miss it");
        BoundingBox swept = HereticMath.swept(now, before);
        Vector at = HereticMath.contact(swept, zombie);
        assertNotNull(at, "but the dive went through it: a touch");
        assertTrue(zombie.contains(at), "the slam is centred inside where you touched them");
        assertNull(HereticMath.contact(now, new BoundingBox(20, 64, 20, 21, 66, 21)));
    }

    @Test void strongerDefaults() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        String k = "mythics.heretic.bloodlinking.";
        assertEquals(1.9, c.getDouble(k + "launch-y"));
        assertEquals(1.9, c.getDouble(k + "bounce-launch-y"));
        assertEquals(11.0, c.getDouble(k + "damage"));
        assertEquals(4, c.getInt(k + "max-victims"));
        assertEquals(100, c.getInt(k + "cooldown-seconds"));
        assertEquals(4, c.getInt(k + "radius"));
        assertEquals(3, c.getInt(k + "max-slams"));
    }

    @Test void sawsHaveNoSweepParticles() throws Exception {
        assertFalse(Files.readString(Path.of("src/main/java/dev/soulbound/MythicListener.java")).contains("SWEEP_ATTACK"));
    }
}
