package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Puff: Dash's contact hit and distance, the Breezy Bash wind blast, and the hit combo. */
class PuffTest {
    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static double[] player(double x, double y, double z) { return new double[] {x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3}; }

    // ------------------------------------------------------------------ Dash: contact

    @Test void onlyATouchHurts() {
        double[] me = player(0, 64, 0);
        assertTrue(PuffMath.touching(me, player(0.5, 64, 0)), "hitboxes overlapping");
        assertTrue(PuffMath.touching(me, player(0.6, 64, 0)), "just touching");
        assertFalse(PuffMath.touching(me, player(1.5, 64, 0)), "dashing past a little to the side: no damage");
        assertFalse(PuffMath.touching(me, player(0, 67, 0)), "above you");
    }

    @Test void aFastDashCantSkipPastSomeone() {
        double[] before = player(0, 64, 0), after = player(2, 64, 0); // 2 blocks in one tick
        double[] target = player(1, 64, 0);
        assertFalse(PuffMath.touching(before, target), "where you were doesn't touch them");
        assertFalse(PuffMath.touching(after, target), "nor where you are now");
        assertTrue(PuffMath.touching(PuffMath.swept(before, after), target), "the swept box catches them");
    }

    @Test void oncePerTargetEvery10Ticks() {
        assertTrue(PuffMath.contactReady(null, 100, 10), "the first touch");
        assertFalse(PuffMath.contactReady(100L, 105, 10), "not again within 10 ticks");
        assertTrue(PuffMath.contactReady(100L, 110, 10), "a hit each pass");
    }

    @Test void trustedAreNeverHurtAndTheOldDotIsGone() throws Exception {
        String g = read("GemListener.java");
        int i = g.indexOf("private void dashContact(");
        String body = g.substring(i, i + 900);
        assertTrue(body.contains("if (e instanceof Player o && isTrusted(p, o)) continue;"));
        assertTrue(body.contains("PuffMath.contactReady(dashHits.get(k), tick, 10)"));
        assertFalse(g.contains("dashHurt("), "no more hurting once a second after you pass");
        assertFalse(g.contains("PotionEffectType.SLOW_FALLING, 60"), "no Slow Falling");
    }

    // ------------------------------------------------------------------ Dash: distance and the rise cap

    @Test void sixteenBlocksAndNoTripToSpace() {
        YamlConfiguration c = config();
        double distance = c.getDouble("abilities.dash.distance"), maxRise = c.getDouble("abilities.dash.max-rise");
        int ticks = c.getInt("abilities.dash.ticks");
        assertEquals(16, distance, 1e-9);
        assertEquals(16, maxRise, 1e-9);
        assertEquals(40, c.getInt("cooldowns.dash"), "the 40 s cooldown stays");
        double speed = PuffMath.dashSpeed(distance, ticks);
        // straight up: simulate the dash
        double y = 0, travelled = 0;
        int t = 0;
        while (!PuffMath.dashDone(travelled, distance, y, maxRise, t, ticks)) {
            double[] v = PuffMath.dashStep(0, 1, 0, speed, distance - travelled, maxRise - y);
            y += v[1];
            travelled += Math.abs(v[1]);
            t++;
        }
        assertEquals(16, y, 1e-6, "looking straight up: about 16 blocks up, and it stops there");
        assertTrue(t <= ticks + 1, "over the same short time");
        // flat
        double x = 0;
        travelled = 0;
        t = 0;
        while (!PuffMath.dashDone(travelled, distance, 0, maxRise, t, ticks)) {
            double[] v = PuffMath.dashStep(1, 0, 0, speed, distance - travelled, maxRise);
            x += v[0];
            travelled += v[0];
            t++;
        }
        assertEquals(16, x, 1e-6, "about 16 blocks, not 40");
        double[] down = PuffMath.dashStep(0, -1, 0, speed, 16, 16);
        assertTrue(down[1] < 0, "looking down still dashes down");
        assertTrue(PuffMath.dashDone(3, 16, 0, 16, 16, 8), "blocked by a wall: it ends");
    }

    @Test void itStopsInsteadOfCarryingOn() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("p.setVelocity(new Vector(v.getX() * 0.2, Math.min(0, v.getY()), v.getZ() * 0.2)); // it stops here"));
    }

    // ------------------------------------------------------------------ Breezy Bash: the wind blast

    @Test void shovedAwayNotUp() {
        double[] v = PuffMath.repel(2, 0, 6, 1.8, 0.35);
        assertTrue(v[0] > 0 && v[2] == 0, "straight away from you");
        assertTrue(v[1] > 0 && v[1] < 0.5, "a little lift, not into the sky");
        assertTrue(v[0] > 3 * v[1], "mostly sideways");
        double[] back = PuffMath.repel(-3, -3, 6, 1.8, 0.35);
        assertTrue(back[0] < 0 && back[2] < 0, "whichever side they're on");
        assertArrayEquals(new double[] {0, 0, 0}, PuffMath.repel(7, 0, 6, 1.8, 0.35), "outside the radius: nothing");
    }

    @Test void strongerTheCloserTheyAre() {
        double near = PuffMath.repel(0.5, 0, 6, 1.8, 0.35)[0], mid = PuffMath.repel(3, 0, 6, 1.8, 0.35)[0], far = PuffMath.repel(6, 0, 6, 1.8, 0.35)[0];
        assertTrue(near > mid && mid > far);
        assertEquals(1.8 * 0.4, far, 1e-9, "40% at the edge");
        assertEquals(1.0, PuffMath.falloff(0, 6), 1e-9);
        YamlConfiguration c = config();
        assertEquals(6, c.getDouble("abilities.breezy-bash.radius"), 1e-9);
        assertEquals(1.8, c.getDouble("abilities.breezy-bash.push"), 1e-9);
        assertEquals(0.35, c.getDouble("abilities.breezy-bash.up"), 1e-9);
        assertEquals(2.0, c.getDouble("abilities.breezy-bash.damage"), 1e-9);
        assertEquals(60, c.getInt("cooldowns.bash"), "its 60 s cooldown stays");
    }

    @Test void bothBreezyBashesAndTheLore() throws Exception {
        String g = read("GemListener.java");
        assertTrue(g.contains("useWindBlast(p);"), "left-click: the wind blast");
        assertTrue(g.contains("private void useBreezyHit(Player attacker, LivingEntity victim) {"), "hit: the launch and slam, as before");
        String lore = read("GemManager.java");
        assertTrue(lore.contains("[Left-click: a wind blast that repels]") && lore.contains("[Hit: launch them up and slam]"));
        assertTrue(read("GemType.java").contains("Breezy Bash has two versions"));
    }

    // ------------------------------------------------------------------ the combo

    @Test void fiveQuickHitsLevitate() {
        PuffMath.Combo c = new PuffMath.Combo();
        UUID friend = UUID.randomUUID();
        long t = 0;
        for (int i = 0; i < 4; i++) assertFalse(c.hit(friend, t += 500, 5, 1500, 6000), "hit " + (i + 1));
        assertTrue(c.hit(friend, t += 500, 5, 1500, 6000), "the 5th: they float");
        for (int i = 0; i < 5; i++) assertFalse(c.hit(friend, t += 500, 5, 1500, 6000), "again right away: no levitation for 6 s");
        t += 4000;
        for (int i = 0; i < 4; i++) c.hit(friend, t += 300, 5, 1500, 6000);
        assertTrue(c.hit(friend, t += 300, 5, 1500, 6000), "after the cooldown, again");
    }

    @Test void slowHitsOrSwitchingTargetsStartsOver() {
        PuffMath.Combo c = new PuffMath.Combo();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        long t = 0;
        for (int i = 0; i < 4; i++) c.hit(a, t += 500, 5, 1500, 6000);
        assertFalse(c.hit(a, t += 1600, 5, 1500, 6000), "more than 1.5 s since the last: starts over");
        assertEquals(1, c.count());
        for (int i = 0; i < 3; i++) c.hit(a, t += 500, 5, 1500, 6000);
        c.hit(b, t += 500, 5, 1500, 6000);
        assertEquals(1, c.count(), "another target: starts over");
    }

    @Test void beingHitByThemResets() throws Exception {
        PuffMath.Combo c = new PuffMath.Combo();
        UUID a = UUID.randomUUID(), other = UUID.randomUUID();
        for (int i = 0; i < 4; i++) c.hit(a, i * 100L, 5, 1500, 6000);
        c.hitBy(other);
        assertEquals(4, c.count(), "someone else hitting you changes nothing");
        c.hitBy(a);
        assertEquals(0, c.count(), "they hit you: your combo starts over");
        assertFalse(c.hit(a, 500, 5, 1500, 6000));
        assertTrue(read("GemListener.java").contains("public void onPuffComboBroken(EntityDamageByEntityEvent e)"));
        assertFalse(read("GemListener.java").contains("Math.random() < 0.25) {\n                if (Math.random() < 0.5) le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION"),
                "the random levitation is gone");
        YamlConfiguration cfg = config();
        assertEquals(5, cfg.getInt("puff.combo.hits"));
        assertEquals(20, cfg.getInt("puff.combo.levitation-ticks"));
        assertEquals(6, cfg.getInt("puff.combo.cooldown-seconds"));
    }

    @Test void theRestOfPuffIsUntouched() throws Exception {
        String g = read("GemListener.java");
        for (String s : List.of("private void useDoubleJump(Player p) {", "public void onToggleFlight(", "passives.puff-no-fall"))
            assertTrue(g.contains(s), s);
    }
}
