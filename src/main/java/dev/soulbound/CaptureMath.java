package dev.soulbound;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Capture points (CAPTURE.md): the parts that need no server, so they can be unit-tested. */
final class CaptureMath {
    private CaptureMath() {}

    /**
     * One coordinate, the way /fill reads it: a whole number, or ~ relative to {@code here} (~, ~5, ~-3, ~1.5).
     * The result is a block coordinate (floored, as /fill does).
     */
    static int coord(String token, double here) {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("a coordinate is missing");
        if (token.startsWith("^")) throw new IllegalArgumentException("^ coordinates aren't supported here, use ~");
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            double off;
            try { off = rest.isEmpty() ? 0 : Double.parseDouble(rest); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("'" + token + "' isn't a coordinate"); }
            if (!Double.isFinite(off)) throw new IllegalArgumentException("'" + token + "' isn't a coordinate");
            return (int) Math.floor(here + off);
        }
        try { return Integer.parseInt(token); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'" + token + "' isn't a whole number or ~"); }
    }

    /** Two corners in any order: {minX, minY, minZ, maxX, maxY, maxZ}, block coordinates, both ends included. */
    static int[] box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new int[] {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
    }

    /** Blocks along x, y and z. */
    static int[] size(int[] b) { return new int[] {b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1}; }

    static boolean fits(int[] b, int maxX, int maxY, int maxZ) {
        int[] s = size(b);
        return s[0] <= maxX && s[1] <= maxY && s[2] <= maxZ;
    }

    /** A position (a player's feet) inside the box's blocks. */
    static boolean inside(int[] b, double x, double y, double z) {
        return x >= b[0] && x < b[3] + 1 && y >= b[1] && y < b[4] + 1 && z >= b[2] && z < b[5] + 1;
    }

    /**
     * Floor tiles for a box {@code w} by {@code d} blocks: {dx, dz, width, depth} from its min corner, covering it
     * exactly with at most {@code max} tiles, as square as possible (the last row and column may be narrower).
     */
    static List<int[]> tiles(int w, int d, int max) {
        int limit = Math.max(1, max);
        int t = Math.max(1, (int) Math.ceil(Math.sqrt((double) w * d / limit)));
        while (ceilDiv(w, t) * ceilDiv(d, t) > limit) t++;
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < w; x += t) for (int z = 0; z < d; z += t) out.add(new int[] {x, z, Math.min(t, w - x), Math.min(t, d - z)});
        return out;
    }

    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }

    enum State { EMPTY, HOLDING, CONTESTED }

    /** What one timer step did: who's holding (or contested), and the winner if someone just reached the goal. */
    record Step(State state, UUID winner) {}

    /**
     * One step of the hold timer. {@code on}: the players who can capture standing on the point. Alone, or all
     * {@code friends} with each other: every one of them gains {@code dt}. Anyone who isn't a friend of someone else
     * there: contested, nobody gains. Time in {@code held} is kept when you leave. The winner is whoever on the point
     * reaches {@code goal} first (the most time if several reach it on the same step).
     */
    static Step step(List<UUID> on, BiPredicate<UUID, UUID> friends, Map<UUID, Long> held, long dt, long goal) {
        if (on.isEmpty()) return new Step(State.EMPTY, null);
        for (int i = 0; i < on.size(); i++)
            for (int j = i + 1; j < on.size(); j++)
                if (!friends.test(on.get(i), on.get(j))) return new Step(State.CONTESTED, null);
        UUID winner = null;
        long best = -1;
        for (UUID id : on) {
            long t = held.merge(id, dt, Long::sum);
            if (t >= goal && t > best) { best = t; winner = id; }
        }
        return new Step(State.HOLDING, winner);
    }

    /** "#B0703A" or one of the 16 chat colour names (gold, aqua, light_purple ...): RGB, or null if neither. */
    static Integer color(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(t.startsWith("#") ? t.substring(1) : t, 16);
        NamedTextColor named = NamedTextColor.NAMES.value(t.toLowerCase(Locale.ROOT));
        return named == null ? null : named.value();
    }

    static String hex(int rgb) { return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF); }

    /** Boss bars come in 7 colours: the nearest by hue (grey and white go to white). */
    static String barColor(int rgb) {
        double r = (rgb >> 16 & 255) / 255.0, g = (rgb >> 8 & 255) / 255.0, b = (rgb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 0.15 || max < 0.15) return "WHITE";
        double h;
        if (max == r) h = 60 * (((g - b) / (max - min)) % 6);
        else if (max == g) h = 60 * ((b - r) / (max - min) + 2);
        else h = 60 * ((r - g) / (max - min) + 4);
        if (h < 0) h += 360;
        String[] names = {"RED", "YELLOW", "GREEN", "BLUE", "PURPLE", "PINK", "RED"};
        double[] hues = {0, 50, 120, 210, 290, 320, 360}; // both chat purples (hue 300) are purple; hot pinks are pink
        int best = 0;
        for (int i = 1; i < hues.length; i++) if (Math.abs(h - hues[i]) < Math.abs(h - hues[best])) best = i;
        return names[best];
    }

    /** A point name: letters, digits, - and _, up to 32. */
    static boolean validName(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,32}"); }
}
