package dev.soulbound;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A merge gem dropped on its finale pedestal must end up in exactly one place, whatever order the drop listeners run
 * in (FinaleDropTest). The drop is always cancelled, so the server gives the gem back the way it does for any
 * cancelled drop (whoever else cancelled it too). It's stamped with a one-time id first, and on the next tick exactly
 * the stack carrying that id is taken back out, and only then put on the pedestal. No server here, so it can be tested.
 */
final class PedestalDrop {
    enum Verdict {
        /** not a merge gem in the drop phase: the other rules handle it */
        NOT_OURS,
        /** already placed, or being placed (Q spammed): cancelled, nothing else */
        ALREADY,
        /** too far from its pedestal: cancelled */
        TOO_FAR,
        /** cancelled, stamped, waiting for the next tick */
        CLAIMED
    }

    /** What the finale knows about one drop. */
    record Facts(boolean dropPhase, boolean mergeGem, boolean placed, boolean near) {}

    private record Pending(String token, boolean couldNotReturn) {}

    private final Map<GemType, Pending> pending = new EnumMap<>(GemType.class);

    /**
     * Whether the finale alone handles this drop: a merge gem dropped near its pedestal before it's placed (including
     * while a drop of it is waiting). The Auratus Q release and the soulbound rule stay out of it.
     */
    static boolean owns(Facts f) { return f.dropPhase() && f.mergeGem() && !f.placed() && f.near(); }

    /**
     * At the drop event (highest priority). Every merge gem in the drop phase is cancelled. A claim is stamped with
     * {@code stamp} (it returns the id, or null if it couldn't). {@code couldNotReturn}: the player had no free storage
     * slot when the event fired, so the server has nowhere to give a cancelled drop back and deletes it.
     */
    Verdict onDrop(GemType type, Facts f, Runnable cancel, Supplier<String> stamp, boolean couldNotReturn) {
        if (type == null || !f.dropPhase() || !f.mergeGem()) return Verdict.NOT_OURS;
        cancel.run();
        if (f.placed() || pending.containsKey(type)) return Verdict.ALREADY;
        if (!f.near()) return Verdict.TOO_FAR;
        String token = stamp.get();
        if (token == null) return Verdict.ALREADY; // couldn't stamp it: it just goes back, as a refused drop
        pending.put(type, new Pending(token, couldNotReturn));
        return Verdict.CLAIMED;
    }

    /**
     * The tick after a claim: true if the gem goes on the pedestal now. {@code take} removes the stack carrying the
     * id from wherever it went (any inventory slot, the cursor, the ground) and says whether it found it. Not found:
     * it's placed only if the server had nowhere to give it back (then it no longer exists anywhere else).
     */
    boolean settle(GemType type, boolean stillDropPhase, boolean placed, Predicate<String> take) {
        Pending p = pending.remove(type);
        if (p == null || !stillDropPhase || placed) return false; // it stays where the server put it back
        if (take.test(p.token())) return true;
        return p.couldNotReturn();
    }

    boolean isPending(GemType type) { return pending.containsKey(type); }

    void clear() { pending.clear(); }
}
