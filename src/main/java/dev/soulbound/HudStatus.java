package dev.soulbound;

/**
 * Ability status on the action bar without words: the icon itself says it all.
 *
 * Ready: the icon, bright. Recharging: the icon dimmed, with a timer. Active: the icon with "\u2026". Charges: bright
 * while at least one is ready (with the next refill timer in a softer colour until they are all back), dimmed with
 * a timer when none are.
 */
final class HudStatus {
    private HudStatus() {}

    static String entry(String icon, long remainMs, boolean active) {
        if (active) return "§f" + icon + " §e\u2026";
        if (remainMs > 0) return "§7" + icon + " §b" + time(remainMs);
        return "§f" + icon;
    }

    static String charges(String icon, int ready, int max, long nextMs) {
        if (ready <= 0) return "§7" + icon + " §b" + time(nextMs);
        if (ready < max && nextMs > 0) return "§f" + icon + " §3" + time(nextMs);
        return "§f" + icon;
    }

    /** 8s, 1m 5s, 1h 2m. */
    static String time(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s >= 3600) return (s / 3600) + "h" + ((s % 3600) / 60 > 0 ? " " + (s % 3600) / 60 + "m" : "");
        if (s >= 60) return (s / 60) + "m" + (s % 60 > 0 ? " " + s % 60 + "s" : "");
        return s + "s";
    }
}
