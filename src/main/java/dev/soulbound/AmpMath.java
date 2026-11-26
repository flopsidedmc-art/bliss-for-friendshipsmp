package dev.soulbound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wealth's Amplification (Amplification), the parts that need no server (WealthTest). */
final class AmpMath {
    private AmpMath() {}

    /** Enchantment levels never go past this, as before. */
    static final int MAX_LEVEL = 10;

    /** Someone near the caster when it's cast: how far (squared) and whether the caster trusts them. */
    record Near(UUID id, double distSq, boolean trusted) {}

    /** Who's buffed: the caster, and every trusted player within {@code radius}. Untrusted players get nothing. */
    static List<UUID> targets(UUID caster, List<Near> near, double radius) {
        List<UUID> out = new ArrayList<>();
        out.add(caster);
        for (Near n : near) {
            if (n.id().equals(caster) || !n.trusted() || n.distSq() > radius * radius || out.contains(n.id())) continue;
            out.add(n.id());
        }
        return out;
    }

    /** The buffed level: one more (at most MAX_LEVEL). */
    static int boosted(int level) { return Math.min(MAX_LEVEL, level + 1); }

    /**
     * Back from the buff: the +1 comes off. {@code current} is the level now, {@code original} the level before.
     * If something lowered it during the buff it stays as it is; never below the original.
     */
    static int restored(int current, int original) {
        int added = boosted(original) - original;
        return current >= original + added ? Math.max(original, current - added) : current;
    }

    /** Casting again only refreshes the time; buffs never stack. */
    static long refreshed(long nowMs, long durationMs) { return nowMs + durationMs; }

    // ------------------------------------------------------------------ the record on each buffed item

    /** "minecraft:sharpness=5;minecraft:unbreaking=3": the levels before the buff. */
    static String encode(Map<String, Integer> levels) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            if (b.length() > 0) b.append(';');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    static Map<String, Integer> decode(String s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try { out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
