package dev.soulbound;

/** The Fire gem fireball's model size (FireballLookTest): set from the charge at launch, never changed in flight. */
final class FireballLook {
    private FireballLook() {}

    /** {@code level}: the charge, 0..1 (it fires from 0.15). Weakest: {@code min}; full: {@code max}. */
    static double scale(double level, double min, double max) {
        double l = Math.max(0, Math.min(1, level));
        return min + (Math.max(min, max) - min) * l;
    }
}
