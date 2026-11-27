package dev.soulbound;

import java.util.Collection;

/** Broken gems (energy 0), the parts that need no server (BrokenTest). */
final class BrokenMath {
    private BrokenMath() {}

    /** Broken: they carry at least one standard gem and every one of them is at 0. */
    static boolean broken(Collection<Integer> standardGemEnergies) {
        if (standardGemEnergies == null || standardGemEnergies.isEmpty()) return false;
        for (int e : standardGemEnergies) if (e > 0) return false;
        return true;
    }

    /**
     * Ability damage on a Broken player: {@code base} (already through the ability multiplier and cap) times
     * {@code multiplier}, but never above {@code ceiling} (0 = no ceiling), and never below the hit as it was.
     */
    static double damage(double base, double multiplier, double ceiling) {
        double raised = base * Math.max(1.0, multiplier);
        if (ceiling > 0) raised = Math.min(raised, ceiling);
        return Math.max(base, raised);
    }
}
