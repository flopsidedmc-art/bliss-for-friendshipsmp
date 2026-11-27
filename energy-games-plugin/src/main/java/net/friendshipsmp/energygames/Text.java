package net.friendshipsmp.energygames;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colour + formatting helpers. Supports '&' codes and <##RRGGBB> / <#RRGGBB> / &#RRGGBB hex. */
final class Text {
    private static final Pattern HEX = Pattern.compile("(?:<##?|&#)([0-9a-fA-F]{6})>?");

    private Text() {
    }

    static String color(String input) {
        if (input == null) return "";
        Matcher m = HEX.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toLowerCase().toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    static void actionBar(Player player, String legacyText) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacyText));
    }

    /** Remaining time split into {hours, minutes, seconds}, seconds rounded up. */
    static long[] hms(long millis) {
        long total = Math.max(0, (millis + 999) / 1000);
        return new long[]{total / 3600, (total % 3600) / 60, total % 60};
    }

    static String fillTime(String template, long millis) {
        long[] t = hms(millis);
        return template.replace("{h}", String.valueOf(t[0]))
                .replace("{m}", String.valueOf(t[1]))
                .replace("{s}", String.valueOf(t[2]));
    }

    /** Human readable duration, e.g. "1 hour", "1 hour 30 minutes", "45 seconds". */
    static String human(long millis) {
        long[] t = hms(millis);
        StringBuilder sb = new StringBuilder();
        append(sb, t[0], "hour");
        append(sb, t[1], "minute");
        append(sb, t[2], "second");
        return sb.length() == 0 ? "0 seconds" : sb.toString();
    }

    private static void append(StringBuilder sb, long value, String unit) {
        if (value <= 0) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(value).append(' ').append(unit);
        if (value != 1) sb.append('s');
    }
}
