package dev.soulbound;

/**
 * Pixel widths of action-bar text, for placing a HUD element at a fixed spot on screen.
 *
 * The action bar is drawn centred on its total width, so anything that must sit at a fixed
 * position (the Rift slot beside the hotbar) needs the width of the text before it. Widths are
 * the vanilla font's advances (measured from minecraft:font/ascii.png, which the Bliss pack does
 * not replace) plus the Relay pack icons. Anything unknown counts as 6, the most common width.
 */
final class HudText {
    private HudText() {}

    /** Advances for ' ' (U+0020) to '~' (U+007E) in the vanilla font. Space comes from include/space.json. */
    private static final String ASCII =
            "42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";

    /** Width of one character in GUI pixels (before GUI scale). */
    static int advance(char c) {
        if (c >= ' ' && c <= '~') return ASCII.charAt(c - ' ') - '0';
        return switch (c) {
            case '\u2026' -> 8; // the ellipsis, from nonlatin_european.png
            case '\uE040', '\uE042' -> 34; // Rewind, Tuned In (icon with its side box)
            case '\uE041', '\uE043', '\uE044', '\uE046', '\uE047', '\uE048' -> 26; // Dilation, Rift, Divinity, Revenant powers
            default -> (c >= '\uE050' && c <= '\uE05C') ? 34 : 6; // Tuned In charge frames
        };
    }

    /** Width of a legacy (§-coded) string as the client lays it out. Bold adds one pixel per character. */
    static int width(String legacy) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(++i));
                if (code == 'l') bold = true;
                else if (code == 'r' || code == 'x' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) bold = false;
                continue;
            }
            w += advance(c) + (bold ? 1 : 0);
        }
        return w;
    }

    /** Characters of the blissgems:hud_low font that move the pen by +2^i (U+E400+i) or -2^i (U+E410+i). */
    static String shift(int pixels) {
        StringBuilder out = new StringBuilder();
        int n = Math.abs(pixels);
        char base = pixels < 0 ? '\uE410' : '\uE400';
        for (int bit = 8; bit >= 0; bit--) {
            while (n >= (1 << bit)) {
                out.append((char) (base + bit));
                n -= 1 << bit;
            }
        }
        return out.toString();
    }

    /** Total pen movement of a string produced by shift(), for tests. */
    static int shiftWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            if (c >= '\uE400' && c <= '\uE408') w += 1 << (c - '\uE400');
            else if (c >= '\uE410' && c <= '\uE418') w -= 1 << (c - '\uE410');
        }
        return w;
    }
}
