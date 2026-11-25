package dev.soulbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Life's revamp, the parts that need no server (LifeTest): which Circle of Life, who's blessed, and the blessing's timer. */
final class LifeMath {
    private LifeMath() {}

    enum Circle { STEAL, BLESSING }

    /**
     * Circle of Life: an untrusted player in front of you is the steal (as before); anything else (the air, a block,
     * nobody, or a trusted player) is the blessing.
     */
    static Circle circle(boolean playerInFront, boolean trusted) {
        return playerInFront && !trusted ? Circle.STEAL : Circle.BLESSING;
    }

    /** Who the blessing reaches: you, and every player inside the ring you trust. Untrusted players get nothing. */
    static <T> List<T> blessed(T caster, List<T> inRing, BiPredicate<T, T> trusts) {
        List<T> out = new ArrayList<>();
        out.add(caster);
        for (T o : inRing) if (!o.equals(caster) && trusts.test(caster, o) && !out.contains(o)) out.add(o);
        return out;
    }

    /**
     * The blessing's extra hearts. One entry a player, so a second blessing only refreshes the timer (they never stack:
     * the hearts are one keyed modifier). They come off when the time's up, and on death, logout and rejoin (and the
     * modifier is never saved, so a restart can't keep them).
     */
    static final class Hearts {
        private final Map<UUID, Long> until = new HashMap<>();

        /** True if they already had it (the modifier is replaced, not added again). */
        boolean give(UUID id, long now, long ms) { return until.put(id, now + ms) != null; }

        boolean has(UUID id) { return until.containsKey(id); }

        /** Time's up (now or later than the newest blessing's end). */
        boolean due(UUID id, long now) {
            Long u = until.get(id);
            return u != null && now >= u;
        }

        /** Off (time up, death, logout, rejoin, server stop). True if there was something to take off. */
        boolean take(UUID id) { return until.remove(id) != null; }

        /** The extra max health they have now: 8 (4 hearts) or none, never more. */
        double bonus(UUID id) { return has(id) ? 8.0 : 0.0; }
    }
}
