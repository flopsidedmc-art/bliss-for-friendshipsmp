package dev.soulbound;

import java.util.List;
import java.util.Locale;

/**
 * The Fire gem's fireball charge (FireTest). It grows a little every step: a full charge takes charge-seconds, or
 * charge-seconds-on-fire while you stand on or in a fire block, so stepping off slows it again from where it is (it
 * never resets). The level (0..1) sets the explosion power and the model size, as before.
 */
final class FireballCharge {
    private FireballCharge() {}

    static double step(double charge, double dtSeconds, boolean onFire, double fullSeconds, double fullOnFireSeconds) {
        double full = onFire ? fullOnFireSeconds : fullSeconds;
        if (full <= 0) return 1.0;
        return Math.min(1.0, Math.max(0.0, charge) + dtSeconds / full);
    }

    /** Whether {@code material} is on the fire-blocks list: exact names, or a pattern with * (*BLACKSTONE* = any blackstone). */
    static boolean fireBlock(String material, List<String> list) {
        String m = material.toUpperCase(Locale.ROOT);
        for (String raw : list) {
            String e = raw.trim().toUpperCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.indexOf('*') < 0 ? e.equals(m) : m.matches(e.replace("*", "[A-Z0-9_]*"))) return true;
        }
        return false;
    }
}
