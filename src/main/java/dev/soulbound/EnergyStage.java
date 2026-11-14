package dev.soulbound;

/** Energy 0-10 stage mapping (Bliss-inspired, original implementation). */
public final class EnergyStage {
    private EnergyStage() {}

    public static String name(int energy) {
        if (energy <= 0) return "Broken";
        if (energy == 1) return "Ruined";
        if (energy == 2) return "Shattered";
        if (energy == 3) return "Cracked";
        if (energy == 4) return "Scratched";
        if (energy >= 10) return "Pristine +5";
        if (energy > 5) return "Pristine +" + (energy - 5);
        return "Pristine";
    }

    /** Broken(0): nothing works. Ruined(1): no passives. */
    public static boolean allowsPassives(int energy) {
        return energy != 0 && energy != 1;
    }

    /** Broken(0): nothing works. Shattered(2): no powers. */
    public static boolean allowsPowers(int energy) {
        return energy != 0 && energy != 2;
    }

    public static double cooldownMultiplier(int energy) {
        if (energy == 3 || energy == 4) return 1.5;
        return 1.0;
    }

    public static int clamp(int energy) {
        if (energy < 0) return 0;
        if (energy > 10) return 10;
        return energy;
    }
}
