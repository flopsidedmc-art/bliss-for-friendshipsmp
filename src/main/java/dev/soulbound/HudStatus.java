package dev.soulbound;

/**
 * Ability status on the action bar: each ability's icon, and no names.
 *
 * Ready: the icon, bright, with "Ready!". Recharging: the icon dimmed, with a timer. Active: the icon with "\u2026".
 * Charges: "Ready!" while at least one is ready, dimmed with the time to the next one when none are.
 */
final class HudStatus {
    private HudStatus() {}

    /**
     * A normal gem's bar, from entries that already carry their own icon and status: Tier 2 shows the left-click
     * power, the ability, then the right-click power (Fire: fireball, crisp, campfire). Tier 1 shows the ability only.
     */
    static java.util.List<String> gemBar(boolean tier2, String left, String ability, String right) {
        return tier2 ? java.util.List.of(left, ability, right) : java.util.List.of(ability);
    }

    /** The pack's crystals (Bliss Pack v3.5): Tier 1, then Tier 2, for each gem; null for a gem without them. */
    static String crystal(GemType type, int tier) {
        String[] c = switch (type) {
            case ASTRA -> new String[] {"\uA43D", "\uA43E"};
            case FIRE -> new String[] {"\uA43F", "\uA440"};
            case FLUX -> new String[] {"\uA441", "\uA442"};
            case LIFE -> new String[] {"\uA443", "\uA444"};
            case PUFF -> new String[] {"\uA445", "\uA446"};
            case SPEED -> new String[] {"\uA447", "\uA448"};
            case STRENGTH -> new String[] {"\uA449", "\uA44A"};
            case WEALTH -> new String[] {"\uA44B", "\uA44C"};
            default -> null;
        };
        return c == null ? null : c[tier >= 2 ? 1 : 0];
    }

    /**
     * The ability's icon: Tier 2 gems use their Tier 2 crystal (actionbar.icons.&lt;GEM&gt;.ability-tier2, or the pack's
     * if that isn't set); Tier 1 gems keep {@code tier1Icon} (the "ability" icon, as before).
     */
    static String abilityIcon(GemType type, int tier, String tier1Icon, String configuredTier2) {
        if (tier < 2) return tier1Icon;
        if (configuredTier2 != null && !configuredTier2.isEmpty()) return configuredTier2;
        String t2 = crystal(type, 2);
        return t2 != null ? t2 : tier1Icon;
    }

    static String entry(String icon, long remainMs, boolean active) {
        if (active) return "§f" + icon + " §e\u2026";
        if (remainMs > 0) return "§7" + icon + " §b" + time(remainMs);
        return "§f" + icon + " " + READY;
    }

    /**
     * The mythic HUD (mythics.hud.show-numbers): {@code numbers} shows the seconds while recharging; off, the icon is
     * just dimmed until it's ready.
     */
    static String entry(String icon, long remainMs, boolean active, boolean numbers) {
        if (numbers || active || remainMs <= 0) return entry(icon, remainMs, active);
        return "§7" + icon;
    }

    /**
     * Charges with numbers: "Ready!" while one is ready, the charges left (1/2), and the seconds until the one
     * recharging is back; none left: the seconds, dimmed. {@code active}: in use ("…"), with the charges left.
     */
    static String charges(String icon, int ready, int max, long nextMs, boolean active, boolean numbers) {
        if (!numbers) return active ? entry(icon, 0, true) : ready > 0 ? charges(icon, ready, max, nextMs) : "§7" + icon;
        String count = max > 1 ? ready + "/" + max : "";
        if (active) return "§f" + icon + " §e\u2026" + (count.isEmpty() ? "" : " §f" + count);
        if (ready <= 0) return "§7" + icon + " §b" + time(nextMs) + (count.isEmpty() ? "" : " §7" + count);
        return "§f" + icon + " " + READY + (count.isEmpty() ? "" : " §f" + count) + (ready < max && nextMs > 0 ? " §b" + time(nextMs) : "");
    }

    static final String READY = "§aReady!";

    static String charges(String icon, int ready, int max, long nextMs) {
        if (ready <= 0) return "§7" + icon + " §b" + time(nextMs);
        return "§f" + icon + " " + READY;
    }

    /** 8s, 1m 5s, 1h 2m. */
    static String time(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s >= 3600) return (s / 3600) + "h" + ((s % 3600) / 60 > 0 ? " " + (s % 3600) / 60 + "m" : "");
        if (s >= 60) return (s / 60) + "m" + (s % 60 > 0 ? " " + s % 60 + "s" : "");
        return s + "s";
    }
}
