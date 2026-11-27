package dev.soulbound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The repair Pedestal's layout (PedestalTest): an 11 x 11 round floor of 97 blocks, each one of ten parts that swap
 * between an open and a closed block, and the hidden base under the heart (a 3 x 3 of netherite so the open beacon has
 * a beam, and ancient debris under that). Coordinates are offsets from the heart, on the floor's level.
 */
final class PedestalLayout {
    private PedestalLayout() {}

    /** H heart, I touching it, O two out on the axes, F diagonals, L knights, R rim, C accents, Q outer, S / P checkers. */
    enum Part { H, I, O, F, L, R, C, Q, S, P }

    /** Open and closed blocks, by default (config: pedestal.blocks.&lt;part&gt;.open / closed). */
    static final Map<Part, String[]> DEFAULTS = new EnumMap<>(Part.class);
    static {
        DEFAULTS.put(Part.H, new String[] {"BEACON", "BEDROCK"});
        DEFAULTS.put(Part.I, new String[] {"GILDED_BLACKSTONE", "BLACKSTONE"});
        DEFAULTS.put(Part.O, new String[] {"CHERRY_WOOD", "BASALT"});
        DEFAULTS.put(Part.F, new String[] {"GOLD_BLOCK", "NETHERITE_BLOCK"});
        DEFAULTS.put(Part.L, new String[] {"RAW_GOLD_BLOCK", "COBBLED_DEEPSLATE"});
        DEFAULTS.put(Part.S, new String[] {"CHERRY_PLANKS", "DEEPSLATE_TILES"});
        DEFAULTS.put(Part.P, new String[] {"STRIPPED_CHERRY_WOOD", "POLISHED_DEEPSLATE"});
        DEFAULTS.put(Part.C, new String[] {"CHISELED_QUARTZ_BLOCK", "CHISELED_POLISHED_BLACKSTONE"});
        DEFAULTS.put(Part.Q, new String[] {"QUARTZ_BLOCK", "POLISHED_BLACKSTONE"});
        DEFAULTS.put(Part.R, new String[] {"QUARTZ_SLAB", "POLISHED_BLACKSTONE_SLAB"});
    }

    static final double RADIUS = 5.5;

    static boolean inside(int dx, int dz) { return dx * dx + dz * dz <= RADIUS * RADIUS; }

    /** The part at this offset, or null outside the floor. */
    static Part part(int dx, int dz) {
        if (!inside(dx, dz)) return null;
        int ax = Math.abs(dx), az = Math.abs(dz);
        if (ax == 0 && az == 0) return Part.H;
        if (ax + az == 1) return Part.I;
        if (ax == 2 && az == 0 || ax == 0 && az == 2) return Part.O;
        if (ax == 1 && az == 1) return Part.F;
        if (ax == 2 && az == 1 || ax == 1 && az == 2) return Part.L;
        if (!inside(dx + 1, dz) || !inside(dx - 1, dz) || !inside(dx, dz + 1) || !inside(dx, dz - 1)) return Part.R;
        if (ax == 4 && az == 0 || ax == 0 && az == 4 || ax == 3 && az == 3) return Part.C;
        if (ax >= 4 || az >= 4) return Part.Q;
        return (dx + dz) % 2 != 0 ? Part.S : Part.P;
    }

    /** The default block for a part (open or closed). */
    static String block(Part p, boolean open) { return DEFAULTS.get(p)[open ? 0 : 1]; }

    /** Basalt lies along its arm, its stripes running into the heart: axis x on the east/west arms, z on north/south. */
    static char axis(int dx, int dz) { return dx != 0 ? 'x' : 'z'; }

    /** Every floor offset {dx, dz}, centre first and rim last (the opening wave's order). */
    static List<int[]> floor() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) if (part(dx, dz) != null) out.add(new int[] {dx, dz});
        out.sort(Comparator.comparingDouble(c -> Math.hypot(c[0], c[1])));
        return out;
    }

    /** The furthest floor block from the heart. */
    static double maxDistance() { return Math.hypot(5, 2); }

    /**
     * The wave: whether the block at distance {@code d} has turned after {@code progress} (0..1). Opening goes from
     * the heart out (the heart first, the rim last); closing from the rim in.
     */
    static boolean turned(double d, double progress, boolean opening) {
        double max = maxDistance();
        if (progress >= 1) return true;
        return opening ? d <= progress * max : d >= (1 - progress) * max;
    }

    /** The hidden base {dx, dy, dz} below the floor (dy from the floor): netherite 3 x 3 under the heart, debris under that. */
    static List<int[]> base() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) out.add(new int[] {dx, -1, dz});
        out.add(new int[] {0, -2, 0});
        return out;
    }

    static String baseBlock(int dy) { return dy == -1 ? "NETHERITE_BLOCK" : "ANCIENT_DEBRIS"; }

    /** One heart break: the new count, and what it did. 5 ends an active rite (or is weathered); the count starts again. */
    record Break(int breaks, boolean fifth, boolean closes) {}

    static Break heartBreak(int breaks, boolean riteActive) {
        int n = breaks + 1;
        if (n < 5) return new Break(n, false, false);
        return new Break(0, true, riteActive);
    }
}
