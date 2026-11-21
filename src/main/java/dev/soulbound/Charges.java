package dev.soulbound;

/**
 * Charges that each recharge on their own timer (Rewind, the Revenant's Plunge, Auratus' chains).
 * Times are epoch milliseconds.
 */
final class Charges {
    private long[] readyAt;

    Charges(int count) { readyAt = new long[Math.max(1, count)]; }

    /** Keeps existing timers when the configured count changes. */
    void resize(int count) {
        count = Math.max(1, count);
        if (count == readyAt.length) return;
        long[] next = new long[count];
        System.arraycopy(readyAt, 0, next, 0, Math.min(count, readyAt.length));
        readyAt = next;
    }

    int max() { return readyAt.length; }

    int ready(long now) {
        int n = 0;
        for (long t : readyAt) if (t <= now) n++;
        return n;
    }

    /** Uses one ready charge, which comes back {@code rechargeMs} later. False if none is ready. */
    boolean use(long now, long rechargeMs) {
        for (int i = 0; i < readyAt.length; i++) {
            if (readyAt[i] <= now) {
                readyAt[i] = now + Math.max(0, rechargeMs);
                return true;
            }
        }
        return false;
    }

    /** Milliseconds until the next charge is back, or 0 if one is ready. */
    long nextIn(long now) {
        long best = Long.MAX_VALUE;
        for (long t : readyAt) {
            if (t <= now) return 0;
            best = Math.min(best, t - now);
        }
        return best;
    }

    /** Milliseconds until the next charge that's recharging is back (even while others are ready), or 0 if none is. */
    long nextRecharge(long now) {
        long best = 0;
        for (long t : readyAt) if (t > now && (best == 0 || t - now < best)) best = t - now;
        return best;
    }

    /** Gives back the charge used most recently (the one that comes back last). False if none is recharging. */
    boolean refundLatest(long now) {
        int best = -1;
        for (int i = 0; i < readyAt.length; i++) if (readyAt[i] > now && (best < 0 || readyAt[i] > readyAt[best])) best = i;
        if (best < 0) return false;
        readyAt[best] = 0;
        return true;
    }

    void refillAll() { java.util.Arrays.fill(readyAt, 0L); }

    long[] times() { return readyAt.clone(); }

    void load(long[] times) {
        for (int i = 0; i < readyAt.length && i < times.length; i++) readyAt[i] = times[i];
    }
}
