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

    /**
     * Whether the flying hook has passed strand {@code i}'s spot: the hook's middle has covered {@code progress} (0..1) of
     * the way to its landing spot (len - at[0] from the hand), and strand i lies len - at[i] from the hand.
     */
    static boolean hookPassed(double len, double[] at, int i, double progress) {
        return progress * (len - at[0]) >= len - at[i] - 1e-9;
    }

    /** The tick a link goes while the hook reels in: when the hook, travelling back over {@code ticks}, reaches it. */
    static long passTick(long now, int ticks, double distanceFromHook, double total) {
        return now + Math.max(1, Math.round(ticks * Math.min(1, distanceFromHook / Math.max(1e-6, total))));
    }

    /** A random offset {x, y, z}: minR..maxR out horizontally in any direction, minUp..maxUp up. */
    static double[] randomOffset(java.util.random.RandomGenerator r, double minR, double maxR, double minUp, double maxUp) {
        double a = r.nextDouble() * Math.PI * 2;
        double d = minR + r.nextDouble() * Math.max(0, maxR - minR);
        double up = minUp + r.nextDouble() * Math.max(0, maxUp - minUp);
        return new double[] {Math.cos(a) * d, up, Math.sin(a) * d};
    }

    /** Divine Purity: a harmful effect's duration in ticks, scaled. Infinite (-1) stays infinite; at least 1 tick. */
    static int purifiedDuration(int ticks, double multiplier) {
        if (ticks < 0) return ticks;
        return Math.max(1, (int) Math.round(ticks * Math.max(0, Math.min(1, multiplier))));
    }

    /**
     * Damage left after vanilla armor and Protection (1.21): armor reduces by clamp(armor - raw / (2 + toughness / 4),
     * armor / 5, 20) / 25, then Protection by 4% per point of EPF (4 per Prot IV piece), at most 80%. Used to check the
     * Auratus combo against full Prot IV diamond (armor 20, toughness 8, EPF 16).
     */
    static double afterArmor(double raw, double armor, double toughness, int protectionEpf) {
        double cut = Math.max(armor / 5, Math.min(20, armor - raw / (2 + toughness / 4))) / 25;
        double afterArmor = raw * (1 - cut);
        return afterArmor * (1 - Math.min(20, Math.max(0, protectionEpf)) * 0.04);
    }

    /** Aegis cooldown: a parry that caught a hit comes back sooner than a missed one. */
    static long aegisCooldownMs(boolean parried, double cooldownSeconds, double successCooldownSeconds) {
        double s = parried ? Math.min(successCooldownSeconds, cooldownSeconds) : cooldownSeconds;
        return (long) (Math.max(0, s) * 1000);
    }

    /**
     * Where the chain's models go on a chain {@code len} long, as distances back from the tip to each model's centre:
     * [0] the hook, then one per strand. Every model is {@code strand} long. The hook's point is on the tip. Each strand
     * sits {@code step} behind the one before so the links carry on in step, and the last is pulled forward by whole
     * links ({@code link}) so it ends at the hand, within half a link, instead of sticking out behind you. A strand
     * always stays at least two links behind the hook, off its claw, so a chain up to two links longer than a strand
     * is just the hook, centred (scaled to len / strand). At most {@code maxStrands} strands.
     */
    static double[] chainLayout(double len, double strand, double step, double link, int maxStrands) {
        if (len <= strand + 2 * link) return new double[] {len / 2};
        int n = Math.max(1, Math.min(maxStrands, (int) Math.ceil((len - strand - link / 2) / step - 1e-9)));
        double[] at = new double[n + 1];
        at[0] = strand / 2;
        for (int i = 1; i <= n; i++) at[i] = strand / 2 + i * step;
        double over = at[n] + strand / 2 - len; // how far the last strand's end is past the hand
        if (over > 0) at[n] -= Math.round(over / link) * link;
        return at;
    }
}
