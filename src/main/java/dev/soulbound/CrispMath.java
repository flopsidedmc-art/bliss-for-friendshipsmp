package dev.soulbound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Crisp's lava pits, with no server (FireTest). Which blocks may change, the patches and the underground check are LandMath's. */
final class CrispMath {
    private CrispMath() {}

    // ------------------------------------------------------------------ lava pits

    /** What a pit needs to know about the blocks around it. */
    interface Grid {
        /** A solid block (lava can sit against it and on it). */
        boolean solid(int x, int y, int z);
        /** Air: the pit is open above, on the floor. */
        boolean open(int x, int y, int z);
        /** Water, or a waterlogged block. */
        boolean water(int x, int y, int z);
    }

    /** The floor blocks of a pit {@code size} (1-3) across, from (x, y, z): one block, a 2x2, or a plus of 5. */
    static List<int[]> shape(int x, int y, int z, int size) {
        List<int[]> out = new ArrayList<>();
        switch (Math.max(1, Math.min(3, size))) {
            case 1 -> out.add(new int[] {x, y, z});
            case 2 -> { for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) out.add(new int[] {x + dx, y, z + dz}); }
            default -> {
                out.add(new int[] {x, y, z});
                out.add(new int[] {x + 1, y, z});
                out.add(new int[] {x - 1, y, z});
                out.add(new int[] {x, y, z + 1});
                out.add(new int[] {x, y, z - 1});
            }
        }
        return out;
    }

    /**
     * A lava pit whose floor block is at (x, y, z), or null if it can't go there. Each pit block is a floor block (solid,
     * air above, solid below), every side not in the pit is solid (the lava can't flow out), there's no water beside
     * it, above it or beside the air above it, it's never within a block of the caster's feet ({@code caster}: {x, y,
     * z}), and never right under anyone standing ({@code standing}: their feet blocks).
     */
    static List<int[]> pit(int x, int y, int z, int size, Grid g, int[] caster, List<int[]> standing) {
        List<int[]> cells = shape(x, y, z, size);
        Set<Long> in = new HashSet<>();
        for (int[] c : cells) in.add(key(c[0], c[2]));
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] c : cells) {
            int cx = c[0], cy = c[1], cz = c[2];
            if (!g.solid(cx, cy, cz) || !g.open(cx, cy + 1, cz) || !g.solid(cx, cy - 1, cz)) return null; // the floor only
            if (g.water(cx, cy + 1, cz) || g.water(cx, cy - 1, cz)) return null;
            for (int[] s : sides) {
                int nx = cx + s[0], nz = cz + s[1];
                if (g.water(nx, cy, nz) || g.water(nx, cy + 1, nz)) return null; // never next to water
                if (!in.contains(key(nx, nz)) && !g.solid(nx, cy, nz)) return null; // enclosed: it can't flow out
            }
            if (caster != null && Math.abs(cx - caster[0]) <= 1 && Math.abs(cz - caster[2]) <= 1 && Math.abs(cy + 1 - caster[1]) <= 2)
                return null; // never at the caster's feet
            for (int[] f : standing)
                if (Math.abs(cx - f[0]) <= 1 && Math.abs(cz - f[2]) <= 1 && Math.abs(cy + 1 - f[1]) <= 1) return null; // never under anyone
        }
        return cells;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
