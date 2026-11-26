package dev.soulbound;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Puff's Dash, Breezy Bash wind blast and hit combo (GemListener), the parts that need no server (PuffTest). */
final class PuffMath {
    private PuffMath() {}

    // ------------------------------------------------------------------ Dash

    /** Blocks per tick: the dash covers {@code distance} in {@code ticks}. */
    static double dashSpeed(double distance, int ticks) { return Math.max(0.1, distance / Math.max(1, ticks)); }

    /**
     * The dash is over once it has covered its distance, risen max-rise blocks, or run twice its planned ticks
     * (blocked by a wall). From then on no momentum carries you on (and never further up).
     */
    static boolean dashDone(double travelled, double distance, double risen, double maxRise, int tick, int plannedTicks) {
        return travelled >= distance || risen >= maxRise || tick >= plannedTicks * 2;
    }

    /** The speed this tick: never more than what's left of the distance (or of the rise, going up). */
    static double[] dashStep(double dx, double dy, double dz, double speed, double left, double riseLeft) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return new double[] {0, 0, 0};
        double s = Math.max(0, Math.min(speed, left));
        double vx = dx / len * s, vy = dy / len * s, vz = dz / len * s;
        if (vy > riseLeft && vy > 0) { // going up: stop exactly at max-rise
            double k = Math.max(0, riseLeft) / vy;
            vx *= k; vy *= k; vz *= k;
        }
        return new double[] {vx, vy, vz};
    }

    /** Boxes {minX, minY, minZ, maxX, maxY, maxZ}: do they touch? */
    static boolean touching(double[] a, double[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
    }

    /** The box swept from where you were last tick to where you are now (so a fast dash can't skip past someone). */
    static double[] swept(double[] from, double[] to) {
        return new double[] {Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]),
                Math.max(from[3], to[3]), Math.max(from[4], to[4]), Math.max(from[5], to[5])};
    }

    /** At most one dash hit per target per {@code gapTicks}. */
    static boolean contactReady(Long lastHitTick, long nowTick, int gapTicks) { return lastHitTick == null || nowTick - lastHitTick >= gapTicks; }

    // ------------------------------------------------------------------ Breezy Bash: the wind blast

    /**
     * The shove on someone {@code dx, dz} from you (flat), {@code radius} the blast's reach: straight away from you,
     * {@code push} sideways and {@code up} upward at point blank, falling to 40% at the edge. Nothing outside the radius.
     * Returns {vx, vy, vz}.
     */
    static double[] repel(double dx, double dz, double radius, double push, double up) {
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > radius) return new double[] {0, 0, 0};
        double k = falloff(d, radius);
        double nx = d < 1e-6 ? 1 : dx / d, nz = d < 1e-6 ? 0 : dz / d;
        return new double[] {nx * push * k, up * k, nz * push * k};
    }

    /** 1 at point blank, 0.4 at the edge: stronger the closer they are. */
    static double falloff(double d, double radius) { return radius <= 0 ? 0 : 0.4 + 0.6 * (1 - Math.min(1, Math.max(0, d) / radius)); }

    // ------------------------------------------------------------------ the hit combo

    /**
     * The hit combo: {@code hits} melee hits in a row on the same target, each within {@code windowMs} of the last,
     * levitate them; then that target can't be combo-levitated again for {@code cooldownMs}. Being hit by them resets it.
     */
    static final class Combo {
        private UUID target;
        private int count;
        private long last;
        private final Map<UUID, Long> immuneUntil = new HashMap<>();

        /** A melee hit on {@code victim}: true if this one completes the combo (levitate them now). */
        boolean hit(UUID victim, long now, int hits, long windowMs, long cooldownMs) {
            if (!victim.equals(target) || now - last > windowMs) { target = victim; count = 0; }
            count++;
            last = now;
            if (count < hits) return false;
            count = 0;
            Long until = immuneUntil.get(victim);
            if (until != null && until > now) return false;
            immuneUntil.put(victim, now + cooldownMs);
            immuneUntil.values().removeIf(u -> u <= now - 60_000);
            return true;
        }

        /** They hit you: your combo starts over. */
        void reset() { target = null; count = 0; }

        /** Someone hit you: if it's who you're comboing, it starts over. */
        void hitBy(UUID who) { if (who != null && who.equals(target)) reset(); }

        int count() { return count; }
    }
}
