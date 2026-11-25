package dev.soulbound;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The land effects (Crisp's fire, Vitality Vortex's drain), the parts that need no server (FireTest, LifeTest):
 * which blocks may change, plants, the patches and the underground check. Blocks are named, not looked up.
 */
final class LandMath {
    private LandMath() {}

    /** Natural ground: what a land effect may change (logs and leaves too, as asked). Nothing built, nothing with a block entity. */
    private static final Set<String> NATURAL = Set.of("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM",
            "MOSS_BLOCK", "MUD", "SAND", "RED_SAND", "GRAVEL", "CLAY", "STONE", "GRANITE", "DIORITE", "ANDESITE", "DEEPSLATE",
            "TUFF", "CALCITE", "DRIPSTONE_BLOCK", "SNOW_BLOCK");

    /** Single-block plants: cleared (and put back on cooling) when the block under them changes. */
    private static final Set<String> PLANTS = Set.of("SHORT_GRASS", "FERN", "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM",
            "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER",
            "LILY_OF_THE_VALLEY", "DEAD_BUSH", "BROWN_MUSHROOM", "RED_MUSHROOM", "MOSS_CARPET", "PINK_PETALS", "TORCHFLOWER",
            "SHORT_DRY_GRASS", "LEAF_LITTER", "WILDFLOWERS", "BUSH", "FIREFLY_BUSH", "SWEET_BERRY_BUSH", "OPEN_EYEBLOSSOM",
            "CLOSED_EYEBLOSSOM");

    /** Short water plants that dry up with the water. */
    private static final Set<String> WATER_PLANTS = Set.of("SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT");

    /**
     * May this block change? Only natural ground, stone and its natural variants, logs (not stripped) and leaves that
     * grew there (not placed: {@code persistentLeaves}). Never anything with a block entity (containers, beds, signs,
     * banners, heads ...), never bedrock, doors, redstone or anything built.
     */
    static boolean eligible(String material, boolean blockEntity, boolean persistentLeaves) {
        if (blockEntity || material == null) return false;
        if (NATURAL.contains(material)) return true;
        if (material.endsWith("_LOG") && !material.startsWith("STRIPPED_")) return true;
        return material.endsWith("_LEAVES") && !persistentLeaves;
    }

    static boolean plant(String material) { return PLANTS.contains(material); }

    static boolean waterPlant(String material) { return WATER_PLANTS.contains(material); }

    /**
     * Underground: you can't see the sky and there's a solid ceiling within 6 blocks above your head ({@code ceiling}:
     * how many blocks up the first one is, or 0 for none). Then the domain is a sphere (floor, walls and ceiling).
     */
    static boolean underground(boolean seesSky, int ceiling) { return !seesSky && ceiling > 0 && ceiling <= 6; }

    static boolean inSphere(int dx, int dy, int dz, double r) { return dx * dx + dy * dy + dz * dz <= r * r; }

    static boolean inDisc(int dx, int dz, double r) { return dx * dx + dz * dz <= r * r; }

    /**
     * Patches, never a solid disc: random blobs of different Nether blocks, so the share {@code share} of the
     * candidate {@code cells} ({dx, dy, dz} from you) that turn is clumped and different every cast. Returns, for each
     * cell, the index into {@code weights} of the block it turns into, or -1 if it stays.
     */
    static int[] patches(List<double[]> cells, double share, double radius, boolean sphere, double[] weights, Random rnd) {
        int n = cells.size();
        int[] out = new int[n];
        Arrays.fill(out, -1);
        if (n == 0 || share <= 0 || weights.length == 0) return out;
        int blobs = Math.max(4, (int) Math.round(radius * 1.6));
        double[][] c = new double[blobs][3];
        double[] sigma = new double[blobs];
        int[] type = new int[blobs];
        for (int i = 0; i < blobs; i++) {
            c[i][0] = (rnd.nextDouble() * 2 - 1) * radius;
            c[i][1] = sphere ? (rnd.nextDouble() * 2 - 1) * radius : 0;
            c[i][2] = (rnd.nextDouble() * 2 - 1) * radius;
            sigma[i] = 1.0 + rnd.nextDouble() * 1.3;
            type[i] = pick(weights, rnd);
        }
        double[] field = new double[n];
        int[] nearest = new int[n];
        for (int k = 0; k < n; k++) {
            double[] p = cells.get(k);
            double sum = 0, top = -1;
            for (int i = 0; i < blobs; i++) {
                double dx = p[0] - c[i][0], dy = sphere ? p[1] - c[i][1] : 0, dz = p[2] - c[i][2];
                double v = Math.exp(-(dx * dx + dy * dy + dz * dz) / (2 * sigma[i] * sigma[i]));
                sum += v;
                if (v > top) { top = v; nearest[k] = i; }
            }
            field[k] = sum + rnd.nextDouble() * 0.15; // ragged edges
        }
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(field[b], field[a]));
        int take = (int) Math.round(n * Math.min(1.0, share));
        for (int k = 0; k < take; k++) out[order[k]] = type[nearest[order[k]]];
        return out;
    }

    static int pick(double[] weights, Random rnd) {
        double total = 0;
        for (double w : weights) total += Math.max(0, w);
        if (total <= 0) return 0;
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= Math.max(0, weights[i]);
            if (r < 0) return i;
        }
        return weights.length - 1;
    }

    /** Crops: Vitality Vortex withers them like the other plants. */
    private static final Set<String> CROPS = Set.of("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "MELON_STEM", "PUMPKIN_STEM",
            "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM", "TORCHFLOWER_CROP", "PITCHER_CROP");

    /** Two-block plants: both halves go together. */
    private static final Set<String> TALL_PLANTS = Set.of("TALL_GRASS", "LARGE_FERN", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY",
            "PITCHER_PLANT");

    /** Any plant Vitality Vortex may wither: single plants, flowers, grass and ferns, crops, and two-block plants. */
    static boolean lifePlant(String material) {
        return PLANTS.contains(material) || CROPS.contains(material) || TALL_PLANTS.contains(material);
    }

    static boolean tallPlant(String material) { return TALL_PLANTS.contains(material); }
}
