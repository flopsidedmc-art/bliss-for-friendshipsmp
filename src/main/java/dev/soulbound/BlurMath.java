package dev.soulbound;

/** Speed's Blur clones (BlurClones), the parts that need no server (BlurTest). */
final class BlurMath {
    private BlurMath() {}

    /**
     * Where each clone stands, as an angle from the target's front (facing whoever is Blurring them): the first on
     * their left, the second on their right, the third behind them; more (if blur.max-clones allows) fill the gaps.
     */
    private static final double[] ANGLES = {90, -90, 180, 135, -135, 45, -45};

    /** How many clones after this many hits in the run (one per hit, at most {@code max}). */
    static int clones(int hits, int max) { return Math.max(0, Math.min(hits, Math.min(max, ANGLES.length))); }

    /**
     * Clone {@code i}'s spot, {@code dist} blocks from the target, given the target's front ({@code fx}, {@code fz},
     * a flat unit vector). Returns {x, z} offsets from the target.
     */
    static double[] slot(int i, double fx, double fz, double dist) {
        double a = Math.toRadians(ANGLES[Math.floorMod(i, ANGLES.length)]);
        double x = fx * Math.cos(a) + fz * Math.sin(a), z = -fx * Math.sin(a) + fz * Math.cos(a);
        return new double[] {x * dist, z * dist};
    }

    /** Ticks after your hit that clone {@code i} swings: a little later for each one, so they go tk-tk-tk. */
    static int swingDelay(int i, int base, int step) { return Math.max(0, base) + Math.max(0, i) * Math.max(0, step); }

    /** Two hits within 10 ticks: the clones flicker. */
    static boolean fast(long previousHitMs, long nowMs) { return previousHitMs > 0 && nowMs - previousHitMs < 500; }

    /** Yaw (Minecraft degrees) to look from (x1, z1) at (x2, z2). */
    static float yaw(double x1, double z1, double x2, double z2) {
        return (float) Math.toDegrees(Math.atan2(-(x2 - x1), z2 - z1));
    }

    /** One step of smooth following: a third of the way there each tick, or straight there when it's far (a teleport). */
    static double follow(double from, double to, boolean snap) { return snap ? to : from + (to - from) * 0.35; }
}
