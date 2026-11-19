package dev.soulbound;

/**
 * Auratus' numbers, kept free of Bukkit so they can be unit-tested: slam falloff, the chain's reel-in speed,
 * the Echo Anchor's aim cone, Flagpole's hover, Divine Purity's shortened effects and Aegis' cooldown.
 */
final class AuratusMath {
    private AuratusMath() {}

    /** Slam damage {@code distance} blocks from the impact: {@code center} there, falling linearly to {@code edge} at the radius, 0 outside. */
    static double slamDamage(double center, double edge, double distance, double radius) {
        if (radius <= 0 || distance > radius) return 0;
        double t = Math.max(0, distance) / radius;
        return Math.max(0, center + (edge - center) * t);
    }

    /** Reel-in speed on tick {@code age} (0-based): ramps up to {@code max} over {@code rampTicks}, never standing still. */
    static double reelSpeed(double age, double max, double rampTicks) {
        if (rampTicks <= 0) return max;
        return max * Math.min(1, (age + 1) / rampTicks);
    }

    /** Angle in degrees between a look direction and the direction to a point (both as x, y, z). 180 if either is zero. */
    static double angleTo(double lx, double ly, double lz, double tx, double ty, double tz) {
        double a = Math.sqrt(lx * lx + ly * ly + lz * lz), b = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (a < 1e-9 || b < 1e-9) return 180;
        double cos = (lx * tx + ly * ty + lz * tz) / (a * b);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** Flagpole: vertical speed that eases a hoisted target to {@code targetY} and holds it there. */
    static double hoistVelocity(double y, double targetY, double maxUp, double maxDown) {
        double v = (targetY - y) * 0.35;
        return Math.max(-Math.abs(maxDown), Math.min(Math.abs(maxUp), v));
    }

    /** Divine Purity: a harmful effect's duration in ticks, scaled. Infinite (-1) stays infinite; at least 1 tick. */
    static int purifiedDuration(int ticks, double multiplier) {
        if (ticks < 0) return ticks;
        return Math.max(1, (int) Math.round(ticks * Math.max(0, Math.min(1, multiplier))));
    }

    /** Aegis cooldown: a parry that caught a hit comes back sooner than a missed one. */
    static long aegisCooldownMs(boolean parried, double cooldownSeconds, double successCooldownSeconds) {
        double s = parried ? Math.min(successCooldownSeconds, cooldownSeconds) : cooldownSeconds;
        return (long) (Math.max(0, s) * 1000);
    }
}
