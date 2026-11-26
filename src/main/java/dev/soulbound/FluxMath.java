package dev.soulbound;

import java.util.Locale;
import java.util.Map;

/** Flux's watts, Energy Beam, Overcharge, Overdrive meter and Shocking Chance (FluxPower), the parts that need no server (FluxTest). */
final class FluxMath {
    private FluxMath() {}

    /** Full watts; Overcharge goes on to OVER_MAX. */
    static final double FULL = 100, OVER_MAX = 200;

    // ------------------------------------------------------------------ watts

    /**
     * Watts one item gives when fed (flux.watts.per-ingot): a key is an ingot's metal ("copper" for COPPER_INGOT) or any
     * material's own name ("copper_block"). 0: it can't be fed.
     */
    static int ingotWatts(String material, Map<String, Integer> perIngot) {
        String m = material.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : perIngot.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (m.equals(k) || m.equals(k + "_ingot")) return Math.max(0, e.getValue());
        }
        return 0;
    }

    /** Fed watts never go past 100 (only charging goes on into Overcharge). */
    static double fed(double watts, int gain) { return watts >= FULL ? watts : Math.min(FULL, watts + gain); }

    /**
     * One step of charging, {@code seconds} long at {@code perSecond}: up to 100, then (once {@code plateauLeft} seconds at
     * 100 have passed) on toward 200. Returns {watts, plateau left}.
     */
    static double[] charge(double watts, double perSecond, double seconds, double plateauLeft) {
        double w = watts + Math.max(0, perSecond) * seconds;
        if (watts < FULL && w >= FULL) return new double[] {FULL, plateauLeft}; // arrives at 100: a short hold there
        if (watts >= FULL && watts <= FULL && plateauLeft > 0) return new double[] {FULL, Math.max(0, plateauLeft - seconds)};
        return new double[] {Math.min(OVER_MAX, w), plateauLeft};
    }

    /** Stopping (Q again, switching away, logging out): watts you built stay, but Overcharge drops back to 100. */
    static double stopped(double watts) { return Math.min(FULL, Math.max(0, watts)); }

    static boolean overcharged(double watts) { return watts > FULL; }

    /** Ground needs exactly a full 100 W (not Overcharge). */
    static boolean canGround(double watts) { return watts >= FULL && watts <= FULL; }

    // ------------------------------------------------------------------ the beam

    /** Up to 100 W: {@code low} at 1 W rising to {@code at100}; in Overcharge on up to {@code at200} at 200 W. */
    static double scale(double watts, double low, double at100, double at200) {
        if (watts <= 0) return 0;
        if (watts <= FULL) return low + (at100 - low) * (Math.max(1, watts) - 1) / (FULL - 1);
        return at100 + (at200 - at100) * (Math.min(OVER_MAX, watts) - FULL) / (OVER_MAX - FULL);
    }

    /** How thick the beam is (blocks across): a thin line at low watts, thick at 100, huge at 200. */
    static double thickness(double watts) { return 0.05 + 0.55 * Math.min(1, Math.max(0, watts) / OVER_MAX); }

    /** How many ticks it glows after firing. */
    static int afterglowTicks(double watts) { return 2 + (int) (Math.max(0, watts) / 20); }

    /** The action bar entry while charging: "64W", or in Overcharge the percentage "173%". */
    static String readout(double watts) {
        return overcharged(watts) ? Math.round(watts) + "%" : (int) Math.floor(watts) + "W";
    }

    /** 0 at 100 W, 1 at 200 W: how far the readout has gone from the gem's colour to white. */
    static double whiteness(double watts) { return Math.max(0, Math.min(1, (watts - FULL) / (OVER_MAX - FULL))); }

    /** Charging particles per tick by stage, kept within 60. */
    static int particles(double watts) {
        double w = Math.max(0, Math.min(OVER_MAX, watts));
        if (w < 50) return 2 + (int) (w / 10);           // a few above your head
        if (w < 100) return 8 + (int) ((w - 50) / 4);    // swirls round your upper body
        if (w < 150) return 22 + (int) ((w - 100) / 3);  // white joins, sonic puffs
        return Math.min(60, 40 + (int) ((w - 150) / 2.5)); // the full storm
    }

    // ------------------------------------------------------------------ Overdrive's meter

    /** Overdrive's 0-100 meter, filled by what happens nearby, at most {@code capPerSecond} each second. */
    static final class Meter {
        double value;
        private long second = Long.MIN_VALUE;
        private double thisSecond;

        /** Adds what fits under this second's cap and the meter's 100. Returns what was added. */
        double add(double amount, long nowMs, double capPerSecond) {
            if (amount <= 0 || value >= FULL) return 0;
            long s = Math.floorDiv(nowMs, 1000);
            if (s != second) { second = s; thisSecond = 0; }
            double room = Math.min(FULL - value, Math.max(0, capPerSecond - thisSecond));
            double add = Math.min(amount, room);
            value += add;
            thisSecond += add;
            return add;
        }

        boolean full() { return value >= FULL; }

        void empty() { value = 0; }
    }

    // ------------------------------------------------------------------ Shocking Chance

    /** An arrow hit shocks if the roll is under the chance and this target wasn't shocked in the last {@code cooldownMs}. */
    static boolean shock(double roll, double chance, Long lastShockMs, long nowMs, long cooldownMs) {
        return roll < chance && (lastShockMs == null || nowMs - lastShockMs >= cooldownMs);
    }
}
