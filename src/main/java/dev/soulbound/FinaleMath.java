package dev.soulbound;

import java.util.function.IntPredicate;

/** The finale's placement numbers, free of Bukkit so they can be unit-tested. */
final class FinaleMath {
    private FinaleMath() {}

    /**
     * The height to stand something at in one block column: the first open block above solid ground. Inside solid
     * ground it climbs to the surface; in the air it drops to the ground below. With no ground at all (the void) it
     * keeps {@code startY}.
     */
    static int surfaceY(int startY, int minY, int maxY, IntPredicate solid) {
        int y = Math.max(minY, Math.min(maxY, startY));
        if (solid.test(y)) {
            while (y < maxY && solid.test(y)) y++;
            return y;
        }
        while (y > minY && !solid.test(y - 1)) y--;
        return y > minY || solid.test(y - 1) ? y : startY;
    }

    /**
     * Where the two gems orbit and merge: the midpoint of the pedestals, half the distance between them as the
     * starting orbit radius, and the angle (radians, atan2 of z over x) the Auratus pedestal sits at from the middle.
     */
    static double[] mergeFrame(double ax, double az, double hx, double hz) {
        double mx = (ax + hx) / 2, mz = (az + hz) / 2;
        double radius = Math.hypot(ax - hx, az - hz) / 2;
        double angle = radius < 1e-6 ? Math.PI : Math.atan2(az - mz, ax - mx);
        return new double[]{mx, mz, radius, angle};
    }
}
