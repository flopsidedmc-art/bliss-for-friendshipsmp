package dev.soulbound;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The Lost Halo, the parts that need no server (HaloTest): which fragments count, copies, finds, the claim, the beam. */
final class HaloMath {
    private HaloMath() {}

    /** A fragment as an item carries: its number (1-5), its id, and the hunt generation it belongs to. */
    record Fragment(int number, String id, int generation) {}

    /** It counts: registered for this hunt (its id) and of this generation (an old hunt's fragments are void). */
    static boolean valid(Fragment f, Map<String, Integer> registered, int generation) {
        if (f == null || f.id() == null) return false;
        Integer n = registered.get(f.id());
        return n != null && n == f.number() && f.generation() == generation;
    }

    /** The distinct valid fragment ids among these (a copy of one counts once). */
    static Set<String> distinct(Collection<Fragment> held, Map<String, Integer> registered, int generation) {
        Set<String> out = new LinkedHashSet<>();
        for (Fragment f : held) if (valid(f, registered, generation)) out.add(f.id());
        return out;
    }

    /**
     * Which of these items to delete: anything invalid, a second copy of an id in the same inventory, and any copy of an
     * id someone else online holds right now ({@code heldElsewhere}). Indexes into {@code held}.
     */
    static List<Integer> toDelete(List<Fragment> held, Map<String, Integer> registered, int generation, Set<String> heldElsewhere) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < held.size(); i++) {
            Fragment f = held.get(i);
            if (!valid(f, registered, generation) || heldElsewhere.contains(f.id()) || !seen.add(f.id())) out.add(i);
        }
        return out;
    }

    /** A first find: the hunt is on and this id hasn't been found before. Records it. */
    static boolean newlyFound(boolean active, Set<String> found, String id) {
        return active && id != null && found.add(id);
    }

    /** "(2/5)": found so far, out of every fragment of the hunt. */
    static String count(int found, int total) { return "(" + found + "/" + Math.max(total, found) + ")"; }

    /** The claim: the hunt is on and they hold enough distinct fragments. */
    static boolean claims(boolean active, int distinctHeld, int needed) {
        return active && distinctHeld >= Math.max(1, needed);
    }

    /** A fragment on the ground shows a faint beam only after lying there this long (so a lost one is found again). */
    static boolean beam(int ticksLived, int afterSeconds) { return ticksLived >= afterSeconds * 20; }

    /** A fragment that fell below the world: back to the world's spawn. */
    static boolean inVoid(double y, int minHeight) { return y < minHeight - 4; }

    /** The fragment display above a reliquary: bobbing up and down and spinning, tick by tick. */
    static double bob(int tick) { return 0.08 * Math.sin(tick * 0.08); }

    static float spin(int tick) { return (float) Math.toRadians((tick * 3) % 360); }

    /** Hunts never share ids: a new one per fragment given. */
    static String newId() { return UUID.randomUUID().toString().substring(0, 13); }
}
