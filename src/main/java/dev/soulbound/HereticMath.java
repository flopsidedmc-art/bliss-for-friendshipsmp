package dev.soulbound;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Heretic Bloodlinking: the parts that need no server, so they can be unit-tested. */
final class HereticMath {
    private HereticMath() {}

    enum AfterSlam { BOUNCE, END }

    /**
     * After a slam: no slams left, it ends. It hit someone: you bounce straight back up, that same tick. It hit
     * nobody: the rule as before, you bounce only if someone has been bloodlinked so far (by any slam), else it ends.
     */
    static AfterSlam afterSlam(int hits, boolean anyoneLinked, int slamsDone, int maxSlams) {
        if (slamsDone >= maxSlams) return AfterSlam.END;
        if (hits > 0) return AfterSlam.BOUNCE;
        return anyoneLinked ? AfterSlam.BOUNCE : AfterSlam.END;
    }

    /** The space your hitbox covered this tick: last tick's box and this tick's together (a fast dive can't skip a body). */
    static BoundingBox swept(BoundingBox now, BoundingBox before) {
        return before == null ? now.clone() : now.clone().union(before);
    }

    /** Where you touched them: the middle of the overlap. Null if the boxes don't overlap. */
    static Vector contact(BoundingBox you, BoundingBox them) {
        if (!you.overlaps(them)) return null;
        return you.clone().intersection(them).getCenter();
    }
}
