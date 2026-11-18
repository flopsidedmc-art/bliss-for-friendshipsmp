package dev.soulbound;

/**
 * The Revenant's numbers, kept free of Bukkit so they can be unit-tested: mace smash damage and its cap,
 * leap height, independently recharging Plunge charges, the spot behind a target, and world-border clamping.
 */
final class RevenantMath {
    private RevenantMath() {}

    /** Player physics in the air: gravity per tick and the vertical drag applied after it. */
    static final double GRAVITY = 0.08, DRAG = 0.98;

    /**
     * Vanilla mace smash bonus for a fall of {@code fall} blocks: +4 per block for the first 3, +2 per block
     * up to 8, then +1 per block. The weapon's own base damage is added on top.
     */
    static double smashBonus(double fall) {
        if (fall <= 0) return 0;
        if (fall <= 3) return 4 * fall;
        if (fall <= 8) return 12 + 2 * (fall - 3);
        return 22 + (fall - 8);
    }

    /**
     * Plunge damage to a target {@code distance} blocks from the impact: base plus the smash bonus, falling
     * off to half at the edge of {@code radius}, never above {@code cap}. Zero outside the radius.
     */
    static double plungeDamage(double base, double fall, double distance, double radius, double cap) {
        if (distance > radius || radius <= 0) return 0;
        double full = base + smashBonus(fall);
        double falloff = 1.0 - 0.5 * Math.max(0, Math.min(1, distance / radius));
        return Math.min(Math.max(0, cap), full * falloff);
    }

    /** Height gained from an upward speed of {@code vy} blocks/tick under player gravity and drag. */
    static double apexHeight(double vy) {
        double y = 0, v = vy;
        for (int i = 0; i < 400 && v > 0; i++) {
            y += v;
            v = (v - GRAVITY) * DRAG;
        }
        return y;
    }

    /** The upward speed that reaches {@code height} blocks (bisection on apexHeight). */
    static double leapSpeedFor(double height) {
        double lo = 0, hi = 5;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (apexHeight(mid) < height) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** A point {@code distance} blocks behind something at (x, z) facing {@code yaw} (Minecraft yaw: 0 = +Z). */
    static double[] behind(double x, double z, float yaw, double distance) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        return new double[]{x - fx * distance, z - fz * distance};
    }

    /** Yaw that faces from (fromX, fromZ) towards (toX, toZ). */
    static float yawTowards(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /**
     * Moves (x, z) inside a square world border centred on (cx, cz) with the given full size, keeping a margin.
     * Points already inside are returned unchanged.
     */
    static double[] clampInside(double x, double z, double cx, double cz, double size, double margin) {
        double half = Math.max(0, size / 2 - margin);
        return new double[]{Math.max(cx - half, Math.min(cx + half, x)), Math.max(cz - half, Math.min(cz + half, z))};
    }

    /** Charges that each recharge on their own timer (like Rewind's two charges). Times are epoch milliseconds. */
    static final class Charges {
        private long[] readyAt;

        Charges(int count) { readyAt = new long[Math.max(1, count)]; }

        /** Keeps existing timers when the configured count changes. */
        void resize(int count) {
            count = Math.max(1, count);
            if (count == readyAt.length) return;
            long[] next = new long[count];
            System.arraycopy(readyAt, 0, next, 0, Math.min(count, readyAt.length));
            readyAt = next;
        }

        int max() { return readyAt.length; }

        int ready(long now) {
            int n = 0;
            for (long t : readyAt) if (t <= now) n++;
            return n;
        }

        /** Uses one ready charge, which comes back {@code rechargeMs} later. False if none is ready. */
        boolean use(long now, long rechargeMs) {
            for (int i = 0; i < readyAt.length; i++) {
                if (readyAt[i] <= now) {
                    readyAt[i] = now + Math.max(0, rechargeMs);
                    return true;
                }
            }
            return false;
        }

        /** Milliseconds until the next charge is back, or 0 if one is ready. */
        long nextIn(long now) {
            long best = Long.MAX_VALUE;
            for (long t : readyAt) {
                if (t <= now) return 0;
                best = Math.min(best, t - now);
            }
            return best;
        }

        void refillAll() { java.util.Arrays.fill(readyAt, 0L); }

        long[] times() { return readyAt.clone(); }

        void load(long[] times) {
            for (int i = 0; i < readyAt.length && i < times.length; i++) readyAt[i] = times[i];
        }
    }
}
