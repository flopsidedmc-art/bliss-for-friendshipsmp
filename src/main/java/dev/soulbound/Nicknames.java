package dev.soulbound;

import java.util.List;
import java.util.Locale;

/**
 * Player nicknames for their own gem (GemNamesTest): the rules (nicknames: in config.yml) and how one is shown. A
 * nickname is stored on the gem itself, so it stays through death, upgrades, repairs and restarts, and doesn't move
 * to a different gem.
 */
final class Nicknames {
    private Nicknames() {}

    /** {@code allowed}: a regex the whole name must match (letters, numbers, spaces, basic punctuation by default). */
    record Rules(boolean enabled, int maxLength, String allowed, List<String> blocked, long cooldownMs) {}

    /** {@code value}: the nickname to store; {@code error}: why it was refused, for the player. */
    record Result(String value, String error) {
        boolean ok() { return error == null; }
    }

    private static Result no(String why) { return new Result(null, why); }

    /** Whether {@code raw} may be a gem's nickname. {@code colour}: the player has soulbound.nickname.color. */
    static Result check(String raw, Rules r, boolean colour) {
        if (!r.enabled()) return no("Gem nicknames are turned off on this server.");
        if (raw == null || raw.isBlank()) return no("Give it a name.");
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        String value, plain;
        if (colour) {
            value = GemNames.clean(s, Integer.MAX_VALUE);
            if (value == null) return no("Give it a name.");
            plain = GemNames.plain(value).trim();
        } else {
            if (s.indexOf('§') >= 0 || s.indexOf('<') >= 0 || s.matches("(?s).*&[0-9a-fk-orxA-FK-ORX].*"))
                return no("Colour codes aren't allowed in gem nicknames.");
            value = s;
            plain = s;
        }
        if (plain.length() > r.maxLength()) return no("That's too long: at most " + r.maxLength() + " characters.");
        if (!plain.matches(r.allowed())) return no("Only letters, numbers, spaces and basic punctuation are allowed.");
        String flat = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String w : r.blocked()) {
            String b = w == null ? "" : w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (!b.isEmpty() && flat.contains(b)) return no("That name isn't allowed.");
        }
        return new Result(value, null);
    }

    /** How a nicknamed gem is shown: the format with {nickname}, {gem} (the server's gem name) and {color}. */
    static String display(String format, String nickname, String gemName, String color) {
        return format.replace("{nickname}", nickname).replace("{gem}", gemName).replace("{color}", color);
    }

    /** Milliseconds until this player may rename again (0: now). */
    static long cooldownLeft(long lastChange, long now, long cooldownMs) {
        return lastChange <= 0 ? 0 : Math.max(0, lastChange + cooldownMs - now);
    }
}
