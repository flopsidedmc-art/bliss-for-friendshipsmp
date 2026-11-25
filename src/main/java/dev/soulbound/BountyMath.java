package dev.soulbound;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Strength's Bounty Hunter (BountyHunt), the parts that need no server (BountyTest): the offering, the board's pages, the trail. */
final class BountyMath {
    private BountyMath() {}

    /** Heads per board page: the top five rows; the bottom row holds the page arrows. */
    static final int PAGE = 45;

    static int pages(int players) { return Math.max(1, (players + PAGE - 1) / PAGE); }

    static int clampPage(int page, int players) { return Math.max(0, Math.min(page, pages(players) - 1)); }

    // ------------------------------------------------------------------ the offering

    /** What an offering does: how many of the counting items are used up, and how many seconds of hunt they add. */
    record Offer(int used, int seconds) {}

    /**
     * {@code counting} items that count, each worth {@code perItem} seconds; a hunt never has more than {@code maxSeconds}
     * left, and {@code leftNow} are left of a hunt already running on this target (0 for a new one). Only the items
     * needed are used up; the rest go back.
     */
    static Offer offer(int counting, int perItem, int maxSeconds, int leftNow) {
        if (counting <= 0 || perItem <= 0) return new Offer(0, 0);
        int room = Math.max(0, maxSeconds - Math.max(0, leftNow));
        int used = Math.min(counting, (room + perItem - 1) / perItem);
        return new Offer(used, Math.min(room, used * perItem));
    }

    // ------------------------------------------------------------------ the trail

    /** The two strands of the trail, as points. */
    record Trail(List<Vector> black, List<Vector> red) {}

    /**
     * Two thin strands wound round each other like a thread, from {@code start} blocks ahead of {@code from} toward
     * {@code to}, {@code length} blocks long, or ending at the target if it's closer. The thread bends a little to the
     * side and sways slowly with {@code ticks}; it's straight at both ends, so it ends exactly on the target.
     */
    static Trail trail(Vector from, Vector to, double start, double length, double spacing, long ticks) {
        List<Vector> a = new ArrayList<>(), b = new ArrayList<>();
        Vector d = to.clone().subtract(from);
        double dist = d.length();
        if (dist <= start + spacing || spacing <= 0) return new Trail(a, b);
        Vector dir = d.multiply(1 / dist);
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0); // straight up or down
        side.normalize();
        Vector up = side.clone().crossProduct(dir).normalize();
        double end = Math.min(start + length, dist), span = end - start;
        double sway = Math.sin(ticks * 0.04), radius = 0.09, turn = 2 * Math.PI / 1.6; // a turn every 1.6 blocks
        for (double s = start; s <= end + 1e-9; s += spacing) {
            double u = span <= 0 ? 1 : (s - start) / span;
            double bend = Math.sin(Math.PI * u);
            Vector center = from.clone().add(dir.clone().multiply(s))
                    .add(side.clone().multiply(0.35 * bend * sway))
                    .add(up.clone().multiply(0.15 * bend * Math.cos(ticks * 0.03)));
            double angle = s * turn + ticks * 0.25;
            Vector off = side.clone().multiply(Math.cos(angle) * radius).add(up.clone().multiply(Math.sin(angle) * radius));
            a.add(center.clone().add(off));
            b.add(center.clone().subtract(off));
        }
        return new Trail(a, b);
    }
}
