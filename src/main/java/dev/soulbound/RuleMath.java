package dev.soulbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** The combat rules module (rules: in config.yml), the parts that need no server (RulesTest). */
final class RuleMath {
    private RuleMath() {}

    /** Every rule, by its config key under rules: (each has its own enabled). */
    static final List<String> RULES = List.of("combat", "enchanted-golden-apples", "golden-apples", "totems", "tipped-arrows",
            "debuff-potions", "cobwebs", "ender-pearls", "wind-charges", "chorus-fruit", "mace", "end-crystals", "bed-bombing",
            "tnt-minecarts", "riptide", "elytra", "invisibility");

    // ------------------------------------------------------------------ a fight

    /**
     * Who's in a fight: from the moment they hurt or are hurt by another player until rules.combat.tag-seconds pass
     * with no player damage either way. Each fight has its own counters (enchanted golden apples eaten, totems
     * popped), reset when it ends, on death and on logout.
     */
    static final class Fights {
        private static final class Fight { long until; int egaps, totems; }

        private final Map<UUID, Fight> fights = new HashMap<>();

        /** Player damage to or from {@code id}: true if it starts a fight (it was out of one). */
        boolean hit(UUID id, long now, long tagMs) {
            Fight f = fights.get(id);
            boolean fresh = f == null || f.until <= now;
            if (fresh) { f = new Fight(); fights.put(id, f); }
            f.until = Math.max(f.until, now + tagMs);
            return fresh;
        }

        boolean inFight(UUID id, long now) {
            Fight f = fights.get(id);
            return f != null && f.until > now;
        }

        /** Fights that just ran out: each is ended (its counters gone) and returned once. */
        List<UUID> expired(long now) {
            List<UUID> out = new ArrayList<>();
            fights.entrySet().removeIf(e -> {
                if (e.getValue().until > now) return false;
                out.add(e.getKey());
                return true;
            });
            return out;
        }

        /** Death or logout: the fight (and its counters) ends now. True if they were in one. */
        boolean end(UUID id) { return fights.remove(id) != null; }

        int egaps(UUID id) { Fight f = fights.get(id); return f == null ? 0 : f.egaps; }

        int totems(UUID id) { Fight f = fights.get(id); return f == null ? 0 : f.totems; }

        /** An enchanted golden apple: true (and counted) if it's within {@code max} this fight. Outside a fight it's always fine. */
        boolean eatEgap(UUID id, long now, int max) {
            Fight f = fights.get(id);
            if (f == null || f.until <= now) return true;
            if (f.egaps >= max) return false;
            f.egaps++;
            return true;
        }

        /** A totem about to save you: true (and counted) if it's within {@code max} this fight. */
        boolean useTotem(UUID id, long now, int max) {
            Fight f = fights.get(id);
            if (f == null || f.until <= now) return true;
            if (f.totems >= max) return false;
            f.totems++;
            return true;
        }

        int size() { return fights.size(); }
    }

    // ------------------------------------------------------------------ carry caps

    /** How many of {@code incoming} you may take when you carry {@code have} and may carry {@code cap}. */
    static int fits(int have, int incoming, int cap) { return Math.max(0, Math.min(incoming, cap - have)); }

    /** How many you carry over the cap (to send back or drop). */
    static int over(int have, int cap) { return Math.max(0, have - cap); }

    /** Slots extra is taken from: the main inventory (35 down to 9), the hotbar (8 down to 0), then the off hand (40). */
    static final int[] TAKE_ORDER = takeOrder();

    private static int[] takeOrder() {
        int[] o = new int[37];
        int n = 0;
        for (int i = 35; i >= 0; i--) o[n++] = i;
        o[n] = 40;
        return o;
    }

    // ------------------------------------------------------------------ debuff potions

    /** A potion is banned if any of its effects (keys like "weakness", "minecraft:slowness") is on the banned list. */
    static boolean debuff(Collection<String> effects, Collection<String> banned) {
        for (String e : effects) {
            String k = bare(e);
            for (String b : banned) if (bare(b).equals(k)) return true;
        }
        return false;
    }

    private static String bare(String key) {
        String k = key.toLowerCase(Locale.ROOT).trim();
        int c = k.indexOf(':');
        return c >= 0 ? k.substring(c + 1) : k;
    }

    // ------------------------------------------------------------------ maces

    enum MaceMode {
        /** crafting is open until the server has max-on-server maces; each craft is announced */
        RACE,
        /** no crafting: admins give them with /rules mace give */
        EVENT;

        static MaceMode of(String s) { return "event".equalsIgnoreCase(s) ? EVENT : RACE; }
    }

    static boolean canCraft(MaceMode mode, int count, int max) { return mode == MaceMode.RACE && count < max; }

    /** An admin's /rules mace give still counts toward the limit. */
    static boolean canGive(int count, int max) { return count < max; }

    static int capLevel(int level, int max) { return Math.max(0, Math.min(level, max)); }

    /** No mace smash damage while gliding, or within {@code windowMs} after gliding ended. */
    static boolean smashAllowed(boolean gliding, long glideEndedAt, long now, long windowMs) {
        return !gliding && (glideEndedAt <= 0 || now - glideEndedAt >= windowMs);
    }

    // ------------------------------------------------------------------ invisibility

    /** A scrambled name: 4 to 10 letters, shown obfuscated (§k), so nobody can tell who it is or how long the name is. */
    static String scramble(Random r) {
        int n = 4 + r.nextInt(7);
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append((char) ('a' + r.nextInt(26)));
        return b.toString();
    }
}
