package dev.soulbound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** The Heretic's Games, the parts that need no server (GamesTest): placements, points, the Blood Duel, curses, the kit editor. */
final class GamesMath {
    private GamesMath() {}

    /** The tournament's phases. Damage only counts in a round or the duel. */
    enum Phase {
        IDLE, OPEN, TITLE, ROUND, INTERMISSION, DUEL_TITLE, DUEL, FINISH;

        /** Players can hurt each other (and themselves) only now. */
        boolean fighting() { return this == ROUND || this == DUEL; }

        /** Between fights: no damage of any kind, and no gem power that moves or hits anyone (event-blocked-powers). */
        boolean resting() { return this == OPEN || this == TITLE || this == INTERMISSION || this == DUEL_TITLE || this == FINISH; }
    }

    /**
     * A round's placings, best first: whoever is still standing (best health first, then most kills this round), then
     * the fallen, the last to fall first.
     */
    static List<UUID> roundOrder(List<UUID> alive, List<UUID> fellInOrder, Map<UUID, Double> health, Map<UUID, Integer> roundKills) {
        List<UUID> standing = new ArrayList<>(alive);
        standing.sort(Comparator.<UUID>comparingDouble(id -> -health.getOrDefault(id, 0.0))
                .thenComparingInt(id -> -roundKills.getOrDefault(id, 0)));
        List<UUID> out = new ArrayList<>(standing);
        for (int i = fellInOrder.size() - 1; i >= 0; i--) if (!out.contains(fellInOrder.get(i))) out.add(fellInOrder.get(i));
        return out;
    }

    /** Points for a placing (1 = first) from a table like [5, 3, 2]; past the table, nothing. */
    static int placePoints(List<Integer> table, int place) {
        if (table == null || place < 1 || place > table.size()) return 0;
        return Math.max(0, table.get(place - 1));
    }

    /**
     * Best first: most points, then most kills, then a coin flip ({@code rnd} shuffles before a stable sort, so only a
     * true tie is left to chance).
     */
    static List<UUID> standings(List<UUID> players, Map<UUID, Integer> points, Map<UUID, Integer> kills, Random rnd) {
        List<UUID> out = new ArrayList<>(players);
        Collections.shuffle(out, rnd);
        out.sort(Comparator.<UUID>comparingInt(id -> -points.getOrDefault(id, 0)).thenComparingInt(id -> -kills.getOrDefault(id, 0)));
        return out;
    }

    /** The Blood Duel: the top two (ties for second go to kills, then a coin flip). Fewer than two players: what there is. */
    static List<UUID> duelPair(List<UUID> players, Map<UUID, Integer> points, Map<UUID, Integer> kills, Random rnd) {
        List<UUID> s = standings(players, points, kills, rnd);
        return new ArrayList<>(s.subList(0, Math.min(2, s.size())));
    }

    /** The final placings: the duel's winner, its loser, then everyone else by points and kills. */
    static List<UUID> finalOrder(UUID winner, UUID loser, List<UUID> rest) {
        List<UUID> out = new ArrayList<>();
        if (winner != null) out.add(winner);
        if (loser != null && !loser.equals(winner)) out.add(loser);
        for (UUID id : rest) if (!out.contains(id)) out.add(id);
        return out;
    }

    /** Season points: the table for the top places (10, 6, 4), {@code finisher} for everyone else who finished. */
    static int seasonPoints(List<Integer> table, int finisher, int place) {
        int t = placePoints(table, place);
        return t > 0 ? t : place >= 1 ? Math.max(0, finisher) : 0;
    }

    /** A duel that runs out of time: more health left wins, then more tournament points, then a coin flip. */
    static UUID duelTimeout(UUID a, double healthA, UUID b, double healthB, Map<UUID, Integer> points, Random rnd) {
        if (healthA != healthB) return healthA > healthB ? a : b;
        int pa = points.getOrDefault(a, 0), pb = points.getOrDefault(b, 0);
        if (pa != pb) return pa > pb ? a : b;
        return rnd.nextBoolean() ? a : b;
    }

    /** The next round's curse: any enabled one but last round's (one curse alone can repeat; none: null). */
    static String nextCurse(List<String> enabled, String last, Random rnd) {
        if (enabled == null || enabled.isEmpty()) return null;
        List<String> pool = new ArrayList<>(enabled);
        if (pool.size() > 1) pool.remove(last);
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** The next round's kit: any enabled one, last round's only if it's the only one. */
    static String nextKit(List<String> enabled, String last, Random rnd) { return nextCurse(enabled, last, rnd); }

    /** Swap: everyone moves to someone else's spot (a shuffle where nobody keeps their own). Index i goes to out[i]. */
    static int[] derangement(int n, Random rnd) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i;
        if (n < 2) return out;
        for (int i = n - 1; i > 0; i--) { // Sattolo's shuffle: one cycle through everyone, so no fixed point
            int j = rnd.nextInt(i);
            int t = out[i]; out[i] = out[j]; out[j] = t;
        }
        return out;
    }

    /** Blind: the heartbeat of the nearest player, louder the closer they are (0 outside {@code range}). */
    static double heartbeatVolume(double distance, double range) {
        if (range <= 0 || distance >= range) return 0;
        return Math.max(0.15, 1.6 * (1 - distance / range));
    }

    /** ...and faster: every second when close, every 2 s further out. */
    static boolean heartbeatNow(int second, double distance, double range) {
        if (heartbeatVolume(distance, range) <= 0) return false;
        return distance < range / 3 || second % 2 == 0;
    }

    /** Rising lava: the layer to fill {@code elapsed} seconds into the round (0 = the box's bottom), or -1 for none yet. */
    static int lavaLayer(int elapsed, int everySeconds, int riseHeight) {
        if (everySeconds <= 0 || riseHeight <= 0 || elapsed < everySeconds) return -1;
        return Math.min(riseHeight - 1, elapsed / everySeconds - 1);
    }

    /** Bloodthirst: no hit dealt for {@code graceMs}: the drain bites (never below {@code floor}). */
    static double bloodthirst(double health, long sinceHitMs, long graceMs, double drain, double floor) {
        if (sinceHitMs < graceMs) return health;
        return Math.max(Math.min(health, floor), health - drain);
    }

    /** A spot inside the box (inclusive block coordinates), with a margin for pushing outsiders out. */
    static boolean inside(double x, double y, double z, int[] box, double margin) {
        return x >= box[0] - margin && x < box[3] + 1 + margin && y >= box[1] - margin && y < box[4] + 1 + margin
                && z >= box[2] - margin && z < box[5] + 1 + margin;
    }

    /** The box (x1, y1, z1, x2, y2, z2) with the low corner first. */
    static int[] box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new int[] {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
    }

    /** An outsider in the box: the nearest spot just outside it, on the x or z side (x, z). */
    static double[] pushOut(double x, double z, int[] box, double gap) {
        double west = x - box[0], east = box[3] + 1 - x, north = z - box[2], south = box[5] + 1 - z;
        double m = Math.min(Math.min(west, east), Math.min(north, south));
        if (m == west) return new double[] {box[0] - gap, z};
        if (m == east) return new double[] {box[3] + 1 + gap, z};
        if (m == north) return new double[] {x, box[2] - gap};
        return new double[] {x, box[5] + 1 + gap};
    }

    // ------------------------------------------------------------------ the kit editor's layout

    /** Player inventory indexes: 0-8 hotbar, 9-35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand. */
    static final int BOOTS = 36, LEGS = 37, CHEST = 38, HELMET = 39, OFFHAND = 40;

    /**
     * The editor (54 slots) is laid out like an inventory: the top row holds the armour (helmet to boots) and the
     * offhand, the next three rows the main inventory, the fifth the hotbar. Returns the inventory index a menu slot
     * edits, or -1 for a button or a filler.
     */
    static int editorToInventory(int slot) {
        if (slot >= 0 && slot <= 3) return HELMET - slot; // 0 helmet, 1 chestplate, 2 leggings, 3 boots
        if (slot == 4) return OFFHAND;
        if (slot >= 9 && slot <= 35) return slot; // the main inventory sits where it does in your own
        if (slot >= 36 && slot <= 44) return slot - 36; // the hotbar
        return -1;
    }

    static int inventoryToEditor(int index) {
        if (index >= BOOTS && index <= HELMET) return HELMET - index;
        if (index == OFFHAND) return 4;
        if (index >= 9 && index <= 35) return index;
        if (index >= 0 && index <= 8) return index + 36;
        return -1;
    }

    /** A kit id from a typed name: lower case letters, digits, - and _, 1 to 16 long; null if nothing usable. */
    static String kitId(String typed) {
        if (typed == null) return null;
        String s = typed.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
        if (s.isEmpty()) return null;
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    /** "2:31". */
    static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
