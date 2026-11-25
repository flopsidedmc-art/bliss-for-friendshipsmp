package dev.soulbound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every block one land effect changed (Crisp's fire, Vitality Vortex's drain), and what it was (FireTest, LifeTest).
 * Restoring puts each spot back exactly (same block and state) only if it still holds what the effect put there: a
 * spot a player broke or replaced is left as it is now. Entries are plain strings, so they can be saved and a restart
 * can still put the land back.
 */
final class LandLedger {
    /** The restore order: Crisp's fire goes out first, then the pits, the land, the plants, and the water last. */
    enum Phase { FIRE, PIT, LAND, PLANT, WATER }

    /** {@code placed}: the material Crisp put there. {@code original}: the full block data it replaced. */
    record Entry(Phase phase, int x, int y, int z, String placed, String original) {
        String key() { return x + ":" + y + ":" + z; }
    }

    private final List<Entry> entries = new ArrayList<>();

    void add(Entry e) { entries.add(e); }

    List<Entry> entries() { return entries; }

    List<Entry> restoreOrder() {
        List<Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingInt(e -> e.phase().ordinal())); // stable: same phase, the order they were made in
        return out;
    }

    /** Still Crisp's: the spot holds the material Crisp put there (so it's put back); anything else is left as it is. */
    static boolean stillOurs(String currentMaterial, Entry e) { return e.placed().equals(currentMaterial); }

    /**
     * Puts back every entry still Crisp's: {@code current} reads a spot's material ({x, y, z}), {@code set} writes the
     * original block data. Returns how many went back. Running it twice puts nothing back the second time.
     */
    int restore(Function<int[], String> current, BiConsumer<Entry, String> set) {
        int n = 0;
        for (Entry e : restoreOrder()) {
            if (!stillOurs(current.apply(new int[] {e.x(), e.y(), e.z()}), e)) continue;
            set.accept(e, e.original());
            n++;
        }
        return n;
    }

    String save(Entry e) {
        return e.phase().name() + ";" + e.x() + ";" + e.y() + ";" + e.z() + ";" + e.placed() + ";" + e.original();
    }

    /** One saved entry, or null if it can't be read. */
    static Entry load(String s) {
        String[] f = s.split(";", 6);
        if (f.length < 6) return null;
        try {
            return new Entry(Phase.valueOf(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4], f[5]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    List<String> saveAll() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(save(e));
        return out;
    }
}
