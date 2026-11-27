package dev.soulbound;

/**
 * The Gold Gem repurpose scene's timing and motion (GoldTest): the gem is torn out, rises slowly to its apex, tips
 * back to face the sky, quivers harder and harder, shatters, and the shards stream into the holder. Ticks from the
 * killing blow; every step ends at a configured second (gold.repurpose.steps), the absorb runs to the end.
 */
final class RepurposeMath {
    private RepurposeMath() {}

    enum Step { TORN_OUT, RISE, LOOK_UP, QUIVER, ABSORB, DONE }

    /** When each step ends, in ticks (always in order, inside the total). */
    record Timing(int tornOut, int rise, int lookUp, int quiver, int total) {}

    static Timing timing(double seconds, double tornOut, double rise, double lookUp, double quiver) {
        int total = Math.max(20, (int) Math.round(seconds * 20));
        int a = clamp((int) Math.round(tornOut * 20), 1, total - 4);
        int b = clamp((int) Math.round(rise * 20), a + 1, total - 3);
        int c = clamp((int) Math.round(lookUp * 20), b + 1, total - 2);
        int d = clamp((int) Math.round(quiver * 20), c + 1, total - 1);
        return new Timing(a, b, c, d, total);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static Step step(Timing t, int age) {
        if (age < t.tornOut()) return Step.TORN_OUT;
        if (age < t.rise()) return Step.RISE;
        if (age < t.lookUp()) return Step.LOOK_UP;
        if (age < t.quiver()) return Step.QUIVER;
        if (age < t.total()) return Step.ABSORB;
        return Step.DONE;
    }

    /** Fast at first, slowing to a stop at the top. */
    static double easeOut(double x) {
        double c = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - c, 3);
    }

    /** How far up it has risen (0 at the chest, 1 at the apex). */
    static double rise(Timing t, int age) {
        if (age <= t.tornOut()) return 0;
        if (age >= t.rise()) return 1;
        return easeOut((age - t.tornOut()) / (double) (t.rise() - t.tornOut()));
    }

    /** Its size while it's torn out: from {@code from} to the full size. */
    static double scale(Timing t, int age, double from, double full) {
        if (age >= t.tornOut()) return full;
        return from + (full - from) * easeOut(age / (double) t.tornOut());
    }

    /** Tipped back toward the sky, in degrees (0 upright, 90 facing up), over {@code tiltTicks} once it stops at the top. */
    static double tilt(Timing t, int age, int tiltTicks) {
        if (age < t.rise()) return 0;
        double x = (age - t.rise()) / (double) Math.max(1, tiltTicks);
        double c = Math.max(0, Math.min(1, x));
        return 90 * (c * c * (3 - 2 * c)); // smooth start and stop
    }

    /** How hard it shakes (0..1): from nothing at the start of the quiver to full just before it breaks. */
    static double quiver(Timing t, int age) {
        if (age < t.lookUp() || age >= t.quiver()) return 0;
        double x = (age - t.lookUp() + 1) / (double) (t.quiver() - t.lookUp());
        return Math.min(1, x * x);
    }

    /** The shards' trail head (0 at the shatter, 1 at the holder). */
    static double absorb(Timing t, int age) {
        if (age < t.quiver()) return 0;
        return Math.min(1, (age - t.quiver() + 1) / (double) (t.total() - t.quiver()));
    }

    /** The victim's overlay style: {@code pattern} with &lt;gem&gt; as the gem's name in lower case (repurpose_astra ...). */
    static String overlayStyle(String pattern, String gem) {
        String p = pattern == null || pattern.isBlank() ? "repurpose_<gem>" : pattern.trim();
        return p.replace("<gem>", gem.toLowerCase(java.util.Locale.ROOT));
    }

    /** The gem stands side-on to the victim: turned 90 degrees from the way they face. */
    static float yaw(float victimYaw) {
        float y = victimYaw + 90;
        while (y >= 180) y -= 360;
        while (y < -180) y += 360;
        return y;
    }
}
