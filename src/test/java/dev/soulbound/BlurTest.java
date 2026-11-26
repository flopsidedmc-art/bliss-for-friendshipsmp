package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Speed's Blur clones: where they stand and how many, when each swings, and that every way a run ends removes them. */
class BlurTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    // ------------------------------------------------------------------ placement and count

    @Test void oneCloneAHitUpToTheMax() {
        assertEquals(1, BlurMath.clones(1, 3), "the first hit brings the first");
        assertEquals(2, BlurMath.clones(2, 3));
        assertEquals(3, BlurMath.clones(3, 3));
        assertEquals(3, BlurMath.clones(9, 3), "never more than blur.max-clones");
        assertEquals(0, BlurMath.clones(0, 3));
        assertEquals(1, BlurMath.clones(5, 1));
    }

    @Test void leftThenRightThenBehind() {
        // the target faces north (-z), toward the caster
        double fx = 0, fz = -1;
        double[] left = BlurMath.slot(0, fx, fz, 1.2), right = BlurMath.slot(1, fx, fz, 1.2), behind = BlurMath.slot(2, fx, fz, 1.2);
        assertArrayEquals(new double[] {-1.2, 0}, left, 1e-9, "facing north, left is west");
        assertArrayEquals(new double[] {1.2, 0}, right, 1e-9, "right is east");
        assertArrayEquals(new double[] {0, 1.2}, behind, 1e-9, "behind is south, away from the caster");
    }

    @Test void slotsTurnWithTheTarget() {
        double s = Math.sqrt(0.5);
        for (int i = 0; i < 3; i++) {
            double[] o = BlurMath.slot(i, s, s, 1.2);
            assertEquals(1.2, Math.hypot(o[0], o[1]), 1e-9, "about 1.2 blocks away");
        }
        double[] behind = BlurMath.slot(2, s, s, 1.2);
        assertEquals(-s * 1.2, behind[0], 1e-9);
        assertEquals(-s * 1.2, behind[1], 1e-9);
    }

    @Test void clonesFaceTheTarget() {
        assertEquals(0f, BlurMath.yaw(0, 0, 0, 5), 1e-4, "south");
        assertEquals(180f, Math.abs(BlurMath.yaw(0, 0, 0, -5)), 1e-4, "north");
        assertEquals(-90f, BlurMath.yaw(0, 0, 5, 0), 1e-4, "east");
        assertEquals(90f, BlurMath.yaw(0, 0, -5, 0), 1e-4, "west");
    }

    @Test void smoothFollowing() {
        assertEquals(3.5, BlurMath.follow(0, 10, false), 1e-9, "a third of the way each tick, not a jump");
        assertEquals(10, BlurMath.follow(0, 10, true), 1e-9, "far away: straight there");
    }

    // ------------------------------------------------------------------ the swing

    @Test void eachCloneSwingsALittleLater() {
        assertEquals(3, BlurMath.swingDelay(0, 3, 2));
        assertEquals(5, BlurMath.swingDelay(1, 3, 2));
        assertEquals(7, BlurMath.swingDelay(2, 3, 2), "tk-tk-tk");
        assertEquals(0, BlurMath.swingDelay(0, -1, 2), "never before your hit");
    }

    @Test void fastHitsFlicker() {
        assertTrue(BlurMath.fast(1_000, 1_400), "two hits within 10 ticks");
        assertFalse(BlurMath.fast(1_000, 1_500));
        assertFalse(BlurMath.fast(0, 1_000), "the first hit of a run");
    }

    @Test void theDamageStaysTheSame() throws Exception {
        String g = read("GemListener.java");
        int i = g.indexOf("private void cloneSwing(");
        String body = g.substring(i, i + 800);
        assertTrue(body.contains("abilities.blur.clone-damage\", 1.0"), "the existing clone damage");
        assertTrue(body.contains("< 1000) return;"), "at most once a second per clone, as before");
        assertTrue(g.contains("melee && abilityHits <= 0"), "a clone's own hit never counts as yours");
        int c = g.indexOf("public void onBlurCombat(");
        assertTrue(g.substring(c, c + 700).contains("struck.victim.equals(attacker.getUniqueId())"), "hit back: it ends, as before");
    }

    // ------------------------------------------------------------------ cleanup

    @Test void everyEndRemovesTheClones() throws Exception {
        String g = read("GemListener.java");
        for (String s : List.of(
                "if (shatter) blurClones().shatter(ownerId); else blurClones().remove(ownerId);", // every endBlur
                "public void onBlurDeath(", "public void onBlurWorld(", "public void onBlurUnload(",
                "endBlursOf(p.getUniqueId(), false); // no clones left behind", // logout
                "if (blurClones != null) blurClones.removeAll();")) assertTrue(g.contains(s), s); // plugin disable
        int t = g.indexOf("private void blurTick(long now) {");
        String tick = g.substring(t, t + 900);
        assertTrue(tick.contains("now - run.lastHit > window") && tick.contains("owner.isDead()") && tick.contains("getWorld().equals"),
                "stopping hitting, death, another world");
        String b = read("BlurClones.java");
        assertTrue(b.contains("c.setPersistent(false)"), "never saved");
        assertTrue(b.contains("t.removeEntry(c.getUniqueId().toString())"), "and out of the glow team");
    }

    @Test void theClonesAreOnlyAnEffect() throws Exception {
        String b = read("BlurClones.java");
        for (String s : List.of("setInvulnerable(true)", "setSilent(true)", "setCollidable(false)", "Team.OptionStatus.NEVER",
                "t.color(NamedTextColor.YELLOW)", "swingMainHand()", "Particle.ELECTRIC_SPARK", "\"speed.blur-shatter\"",
                "MannequinBody.available()", "Vex.class")) assertTrue(b.contains(s), s);
        String g = read("GemListener.java");
        for (String s : List.of("onBlurCloneHurt(", "onBlurCloneShot(", "onBlurCloneTarget(", "e.getDrops().clear()")) assertTrue(g.contains(s), s);
        assertFalse(g.contains("vex.setTarget(victim)"), "the clones don't fight on their own any more");
    }

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(3, c.getInt("abilities.blur.max-clones"));
        assertEquals(3, c.getInt("abilities.blur.clone-delay-ticks"));
        assertEquals(1.2, c.getDouble("abilities.blur.clone-distance"), 1e-9);
        assertEquals(3.0, c.getDouble("abilities.blur.combo-window-seconds"), 1e-9, "unchanged");
        assertEquals(1.0, c.getDouble("abilities.blur.clone-damage"), 1e-9, "unchanged");
        assertEquals("ENTITY_LIGHTNING_BOLT_IMPACT", c.getString("sounds.speed.blur-shatter.event"));
    }
}
