package dev.soulbound;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Mythic-gem particle structures reverse-engineered from the showcase footage:
 *
 *   fountain(...)   Heretic "spore fountain": red dust column rising as two
 *                   counter-rotating spirals, breaking into an overhead canopy
 *                   of red whisker ribbons (as seen at 1:20).
 *   curls(...)      Victim markers: floating blue / pink horizontal squiggle
 *                   glyphs (the "cant jump" stun + bleed markers, 0:38 / 1:56).
 *   chainSparks(...) Auratus chain link trail: diagonal staircase of white-gold
 *                   sparkle X-links (0:32) between two points, refreshed each tick.
 *   slamDecal(...)   Red diamond ground decal (0:20) drawn with particle frame.
 *   tripleSpin(...)  Auratus ambient: three offset gold dust whirls orbiting the
 *                   caster's hand (the pink/elite wrapper seen at 0:11).
 *
 * All methods are cosmetic-only; they never damage entities.
 */
public final class MythicFx {

    private MythicFx() {
    }

    private static final Random RANDOM = new Random();

    // ---- prismatic palette ----------------------------------------------------

    /** White with prismatic accents: picks a rainbow hue ~one-third of the time. */
    public static Particle.DustOptions prismaticDust(float size) {
        if (RANDOM.nextInt(3) == 0) {
            float hue = ((System.currentTimeMillis() % 3000) / 3000f) + RANDOM.nextFloat() * 0.35f;
            java.awt.Color c = java.awt.Color.getHSBColor(hue % 1f, 0.85f, 1.0f);
            return dust(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, size);
        }
        return dust(1.0f, 1.0f, 1.0f, size);
    }

    /**
     * Comic "POW" burst — the parry shape from the showcase: a 4-point
     * starburst (tapered spikes N/E/S/W) over a diamond base, expanding over
     * ~6 frames around {@code center} (horizontal plane at eyeball height).
     */
    public static void powBurst(Location center, double scale) {
        if (center.getWorld() == null) return;
        new BukkitRunnable() {
            double t = 0;
            @Override
            public void run() {
                if (center.getWorld() == null) { cancel(); return; }
                double grow = 0.55 + t * 0.35;
                double r = scale * grow;                 // diamond base radius
                double spike = scale * grow * 2.2;       // spike length
                for (int i = 0; i < 4; i++) {            // diamond base edges
                    double a0 = Math.PI / 2 * i, a1 = Math.PI / 2 * (i + 1);
                    for (int s = 0; s <= 8; s++) {
                        double f = s / 8.0, ang = a0 + (a1 - a0) * f;
                        Location p = center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                        center.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, prismaticDust(1.2f));
                    }
                }
                for (int i = 0; i < 4; i++) {            // starburst spikes, tapering widths
                    double ang = Math.PI / 2 * i;
                    Vector dir = new Vector(Math.cos(ang), 0, Math.sin(ang));
                    for (double d = 0.3; d < spike; d += 0.35) {
                        double w = 0.12 * (1 - d / (spike + 0.01));
                        Location p = center.clone().add(dir.clone().multiply(d));
                        center.getWorld().spawnParticle(Particle.DUST, p, 2, w, w * 0.4, w, 0, prismaticDust(1.1f));
                    }
                }
                t += 1;
                if (t >= 6) cancel();
            }
        }.runTaskTimer(org.bukkit.Bukkit.getPluginManager().getPlugin("SoulboundGems"), 0L, 1L);
    }

    // ---- shared dust presets ------------------------------------------------

    public static Particle.DustOptions dust(float r, float g, float b, float size) {
        return new Particle.DustOptions(org.bukkit.Color.fromRGB(
                clamp255(r), clamp255(g), clamp255(b)), size);
    }

    public static final float RED_R = 0.85f, RED_G = 0.06f, RED_B = 0.06f;   // heretic spore/blood
    public static final float BLUE_R = 0.20f, BLUE_G = 0.55f, BLUE_B = 1.00f; // stun curls
    public static final float PINK_R = 1.00f, PINK_G = 0.45f, PINK_B = 0.80f; // bleed curls
    public static final float GOLD_R = 1.00f, GOLD_G = 0.85f, GOLD_B = 0.45f; // auratus

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    // ---- the fountain / spore tree ------------------------------------------

    /**
     * Heretic spore fountain. Two mirrored spirals climb a trunk up to
     * {@code height} while the top ring blooms outward like a canopy, then
     * individual ribbons drift down as whiskers. Runs for the full durationMs.
     */
    public static List<org.bukkit.scheduler.BukkitTask> fountain(Location origin, long durationMs) {
        List<org.bukkit.scheduler.BukkitTask> tasks = new ArrayList<>();
        World world = origin.getWorld();
        if (world == null || origin.getBlockY() < world.getMinHeight()) return tasks;
        Location base = origin.clone().add(0.5, 0, 0.5); // center the spiral in the source block
        tasks.add(new BukkitRunnable() {
            double t = 0;
            double height = 0;
            final long start = System.currentTimeMillis();

            @Override
            public void run() {
                if (System.currentTimeMillis() - start > durationMs) {
                    cancel();
                    return;
                }
                double step = 0.55;                       // blocks climbed per frame
                if (height < 14) {
                    height += step;                       // trunk growth phase
                }
                // trunk: two counter-rotating spiral strands
                spawnSpiralAt(base, height, +1);
                spawnSpiralAt(base, height, -1);

                // canopy: the higher the trunk, the wider the umbrella ring
                double canopyRadius = Math.min(9.0, height * 0.65);
                if (height > 6) {
                    for (int i = 0; i < 36; i++) {
                        double ang = Math.PI * 2 * i / 36D + t * 0.25;
                        double r = canopyRadius * (0.55 + 0.45 * Math.abs(Math.sin(t * 1.7)));
                        double y = base.getY() + height + Math.sin(t * 3 + i) * 0.8;
                        Location p = base.clone().add(Math.cos(ang) * r, y - base.getY(), Math.sin(ang) * r);
                        world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust(RED_R, RED_G, RED_B, 0.8f));
                    }
                    // whisker ribbons drifting down
                    for (int i = 0; i < 10; i++) {
                        double ang = RANDOM.nextDouble() * Math.PI * 2;
                        double r = canopyRadius * (0.4 + 0.6 * RANDOM.nextDouble());
                        Location p = base.clone().add(Math.cos(ang) * r, height * (0.35 + RANDOM.nextDouble() * 0.6), Math.sin(ang) * r);
                        world.spawnParticle(Particle.DUST, p, 2, 0, 0.3, 0, 0, dust(RED_R, RED_G, RED_B, 0.6f));
                    }
                }
                t += 0.35;
            }
        }.runTaskTimer(org.bukkit.Bukkit.getPluginManager().getPlugin("SoulboundGems"), 0L, 2L));
        return tasks;
    }

    private static void spawnSpiralAt(Location base, double y, int dir) {
        World world = base.getWorld();
        double phase = (System.currentTimeMillis() % 10000) * 0.03 * dir;
        for (int s = 0; s < 14; s++) {
            double ang = phase + Math.PI * s / 7D;
            double r = 0.9 + s * 0.05;
            Location p = base.clone().add(Math.cos(ang) * r, y + s * 0.1, Math.sin(ang) * r);
            if (world != null) {
                world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.15, 0.05, 0, dust(RED_R, RED_G, RED_B, 1.1f));
            }
        }
    }

    // ---- victim curl markers -------------------------------------------------

    /**
     * Draws one floating squiggle glyph above a victim's head. Blue = stun
     * ("cant jump"), pink = bleed marker. Each glyph is a small 3-point curl
     * of dust particles that persists for one pulse.
     */
    public static void curls(LivingEntity victim, boolean blue, boolean pink) {
        curls(victim, blue, pink, 0.30);
    }

    public static void curls(LivingEntity victim, boolean blue, boolean pink, double scale) {
        World world = victim.getWorld();
        double lift = victim.getHeight() + 0.9 * scale;
        Location head = victim.getLocation().add(0, lift + scale, 0);
        double yaw = Math.toRadians(victim.getLocation().getYaw());
        double phase = (System.currentTimeMillis() % 3000) * 0.002;
        if (blue) drawCurl(world, head.clone(), yaw, BLUE_R, BLUE_G, BLUE_B, phase, scale);
        if (pink) drawCurl(world, head.clone().add(1.5 * scale, -0.4 * scale, -1.0 * scale), yaw, PINK_R, PINK_G, PINK_B, phase + 0.7, scale * 0.55);
    }

    private static void drawCurl(World world, Location origin, double yaw,
                                 float r, float g, float b, double tickPhase, double scale) {
        // showcase: hovering 2-block-wide squiggle ribbons over marked victims
        for (int seg = 0; seg < 3; seg++) {
            double radius = 0.55 * scale;
            for (int i = 0; i < 14; i++) {
                double a = Math.PI * (seg * 2 + i / 14.0) + tickPhase;
                double px, py, pz;
                switch (seg) {
                    case 0 -> { px = Math.cos(a) * radius; py = Math.sin(a) * radius; pz = 0; }
                    case 1 -> { px = Math.cos(a) * radius; py = -Math.sin(a) * radius; pz = 0.2 * scale; }
                    default -> { px = 0; py = Math.sin(a) * radius; pz = Math.cos(a) * radius; }
                }
                double rot = -yaw;
                double wx = px * Math.cos(rot) - pz * Math.sin(rot);
                double wz = px * Math.sin(rot) + pz * Math.cos(rot);
                Location p = origin.clone().add(wx, py, wz);
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust(r, g, b, 1.6f));
            }
        }
    }

    // ---- chain sparkle links -------------------------------------------------

    /**
     * Auratus chain link trail: a staircase of small X-sparkles from {@code a}
     * to {@code b}, refreshed by the caller's movement tick (call every 2 tasks).
     */
    public static void chainSparks(Location a, Location b) {
        World world = a.getWorld();
        if (world == null || b.getWorld() != world) return;
        Vector step = b.clone().subtract(a).toVector();
        double length = step.length();
        if (length < 0.5) return;
        step.multiply(1.0 / length);
        double spacing = 0.9;
        for (double dist = 0; dist < length; dist += spacing) {
            Location p = a.clone().add(0, 0.4 + Math.sin(dist * 2.2) * 0.25, 0).add(step.clone().multiply(dist));
            world.spawnParticle(Particle.CRIT, p, 2, 0.04, 0.04, 0.04, 0.01);
            // every other link gets a gold crackle
            if (Math.round(dist / spacing) % 2 == 0) {
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust(GOLD_R, GOLD_G, GOLD_B, 0.9f));
            }
        }
    }

    // ---- slam decal -----------------------------------------------------------

    /** Red wireframe diamond on the ground under {@code loc}; call per tick while armed. */
    public static void slamDecal(Location loc, double size) {
        World world = loc.getWorld();
        if (world == null) return;
        Location base = loc.clone();
        double he = size / 2D;
        int per = 10;
        for (int i = 0; i <= per; i++) {
            double f = i / (double) per;
            // four diamond edges: N->E, E->S, S->W, W->N
            world.spawnParticle(Particle.DUST, base.clone().add(-he + size * f, 0.05, he), 1, 0, 0, 0, 0,
                    dust(RED_R, RED_G, RED_B, 1.2f));
            world.spawnParticle(Particle.DUST, base.clone().add(he, 0.05, he - size * f), 1, 0, 0, 0, 0,
                    dust(RED_R, RED_G, RED_B, 1.2f));
            world.spawnParticle(Particle.DUST, base.clone().add(he - size * f, 0.05, -he), 1, 0, 0, 0, 0,
                    dust(RED_R, RED_G, RED_B, 1.2f));
            world.spawnParticle(Particle.DUST, base.clone().add(-he, 0.05, -he + size * f), 1, 0, 0, 0, 0,
                    dust(RED_R, RED_G, RED_B, 1.2f));
        }
    }

    // ---- auratus ambient whirls ------------------------------------------------

    /** Three offset gold dust whirls orbiting an anchor (hand or origin point). */
    public static void tripleSpin(Location anchor) {
        World world = anchor.getWorld();
        if (world == null) return;
        double phase = (System.currentTimeMillis() % 4000) * 0.001 * Math.PI;
        for (int w = 0; w < 3; w++) {
            double a = phase + Math.PI * 2 * w / 3D;
            double r = 0.55 + 0.15 * Math.sin(phase * 2 + w);
            Location p = anchor.clone().add(Math.cos(a) * r, 1.1 + Math.sin(a) * 0.35, Math.sin(a) * r);
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, dust(GOLD_R, GOLD_G, GOLD_B, 0.8f));
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
