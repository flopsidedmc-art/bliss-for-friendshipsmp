package dev.soulbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small-caps lettering for gem lore: every letter, capital or not, becomes its small-capital form
 * ("Tuned In" -> "ᴛᴜɴᴇᴅ ɪɴ"). Colour codes are left intact, and so are the item-history lines
 * ("STOLEN by Name" and friends), because other features read those back by their exact words.
 */
final class SmallCaps {
    private SmallCaps() {}

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    /** Small capitals for a..z. There is no small-capital x, so x stays x. */
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** One line in small caps. The character after each § is a formatting code and is kept as it is. */
    static String of(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                out.append(c).append(line.charAt(++i));
                continue;
            }
            int at = LETTERS.indexOf(Character.toLowerCase(c));
            out.append(at >= 0 && c < 128 ? SMALL.charAt(at) : c);
        }
        return out.toString();
    }

    /** Back to plain capitals, for matching words in lore that may already be in small caps. */
    static String plain(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            int at = SMALL.indexOf(c);
            out.append(at >= 0 ? Character.toUpperCase(LETTERS.charAt(at)) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** "STOLEN by X", "GIFTED by X", "STORED by X": written and read back by ItemProvenanceListener and bounties. */
    static boolean isHistoryLine(String line) {
        String s = stripCodes(line).trim().toLowerCase(Locale.ROOT);
        return s.startsWith("stolen by ") || s.startsWith("gifted by ") || s.startsWith("stored by ");
    }

    static List<String> lore(List<String> lore) {
        List<String> out = new ArrayList<>(lore.size());
        for (String line : lore) out.add(line == null || isHistoryLine(line) ? line : of(line));
        return out;
    }

    private static String stripCodes(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }
}
