package dev.soulbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The Gold Gem (GoldGem), the parts that need no server (GoldTest): sockets, marks, the cycle, releases, caps, the ritual, Snap. */
final class GoldMath {
    private GoldMath() {}

    /** The eight gem types in the order of the pack's gold_gem_&lt;bits&gt; names. */
    static final List<String> ORDER = List.of("ASTRA", "FIRE", "FLUX", "LIFE", "PUFF", "SPEED", "STRENGTH", "WEALTH");

    /** "GOLD" in the switch cycle: the awakened Gold Gem's own powers (Snap, Golden Drill). */
    static final String GOLD = "GOLD";

    /** The pack's look for these types: blissgems:gold/gold_gem_01010000 (core Life + Fire). */
    static String bits(Collection<String> types) {
        StringBuilder b = new StringBuilder(8);
        for (String t : ORDER) b.append(types.contains(t) ? '1' : '0');
        return b.toString();
    }

    static String model(Collection<String> types, boolean awakened) {
        return awakened ? "gold/gold_gem_awakened" : "gold/gold_gem_" + bits(types);
    }

    // ------------------------------------------------------------------ one Gold Gem's state

    /** A gem in the Gold Gem: its type, whose it was, and the item itself (serialized) to give back. */
    record Socket(String type, UUID owner, String ownerName, String item) {}

    /** What happens to a repurposed gem. */
    enum Taken { SOCKETED, HELD }

    /** One Gold Gem: its holder, the core, the socketed gems, gems held to give back, marks, the active entry. */
    static final class State {
        final UUID holder;
        String holderName;
        final String coreType;
        final String coreItem;
        final List<Socket> sockets = new ArrayList<>(); // shown in the gem, usable
        final List<Socket> held = new ArrayList<>();    // destroyed duplicates: given back when the holder dies
        final Map<UUID, Long> marks = new LinkedHashMap<>(); // victim -> when marked
        boolean awakened;
        String active;

        State(UUID holder, String holderName, String coreType, String coreItem) {
            this.holder = holder;
            this.holderName = holderName;
            this.coreType = coreType;
            this.coreItem = coreItem;
            this.active = coreType;
        }

        /** Every type in the gem, the core first. */
        List<String> types() {
            List<String> out = new ArrayList<>();
            out.add(coreType);
            for (String t : ORDER) for (Socket s : sockets) if (s.type().equals(t) && !out.contains(t)) out.add(t);
            return out;
        }

        boolean full() { return types().size() >= ORDER.size(); }

        /** A type can go in if it's a gem type, not already in (the core counts) and there's room (8 in all). */
        boolean canSocket(String type) { return ORDER.contains(type) && !types().contains(type) && types().size() < ORDER.size(); }

        /** A repurposed gem: socketed if it fits, otherwise held (destroyed for now) until the holder dies. */
        Taken take(String type, UUID owner, String ownerName, String item) {
            Socket s = new Socket(type, owner, ownerName, item);
            if (canSocket(type)) { sockets.add(s); return Taken.SOCKETED; }
            held.add(s);
            return Taken.HELD;
        }

        /** Whose gem is in here (socketed or held): they can't get a new one until it comes back. */
        boolean hasGemOf(UUID owner) {
            for (Socket s : sockets) if (s.owner().equals(owner)) return true;
            for (Socket s : held) if (s.owner().equals(owner)) return true;
            return false;
        }

        /** The switch cycle: the core, then each socketed type (in the pack's order), then GOLD once awakened. */
        List<String> cycle() {
            List<String> c = new ArrayList<>(types());
            if (awakened) c.add(GOLD);
            return c;
        }

        String next() {
            List<String> c = cycle();
            int i = c.indexOf(active);
            return c.get((i + 1) % c.size());
        }

        boolean goldMode() { return GOLD.equals(active); }

        /** The holder died (or lost the gem): every socketed and held gem goes back to its owner; core only, not awakened. */
        List<Socket> release(boolean keepMarks) {
            List<Socket> out = new ArrayList<>(sockets);
            out.addAll(held);
            sockets.clear();
            held.clear();
            awakened = false;
            active = coreType;
            if (!keepMarks) marks.clear();
            return out;
        }
    }

    // ------------------------------------------------------------------ marks

    enum MarkResult { OK, NOT_TIER_2, FULL, ALREADY, SELF }

    static MarkResult canMark(State s, UUID target, int targetTier, int slots) {
        if (target.equals(s.holder)) return MarkResult.SELF;
        if (s.marks.containsKey(target)) return MarkResult.ALREADY;
        if (targetTier < 2) return MarkResult.NOT_TIER_2;
        if (s.marks.size() >= slots) return MarkResult.FULL;
        return MarkResult.OK;
    }

    /** Milliseconds until a mark may be taken off (0: now). */
    static long unmarkWait(long markedAt, long now, long lockMs) { return Math.max(0, markedAt + lockMs - now); }

    // ------------------------------------------------------------------ cooldowns

    /** Nothing on the Gold Gem waits longer than the cap (gold.max-cooldown-seconds). */
    static long cap(long ms, long capMs) { return capMs > 0 ? Math.min(ms, capMs) : ms; }

    // ------------------------------------------------------------------ the repurpose

    /** The holder's hit counts for the kill if it was the killing blow itself, or landed within {@code windowMs} before. */
    static boolean credited(boolean directByHolder, long lastHolderHit, long now, long windowMs) {
        return directByHolder || (lastHolderHit > 0 && now - lastHolderHit <= windowMs);
    }

    // ------------------------------------------------------------------ the ritual

    enum Ritual { GOING, CANCELLED, DONE }

    /** One ritual check: cancelled if the holder is gone, dead or too far from the site; done when the time is up. */
    static Ritual ritual(boolean online, boolean alive, double distance, double radius, int age, int totalTicks) {
        if (!online || !alive || distance > radius) return Ritual.CANCELLED;
        return age >= totalTicks ? Ritual.DONE : Ritual.GOING;
    }

    // ------------------------------------------------------------------ Snap

    /** Snap's victims: {@code n} random players from those online, never the holder, and not {@code excluded} ones. */
    static List<UUID> snapTargets(List<UUID> online, UUID holder, Set<UUID> excluded, int n, Random r) {
        List<UUID> pool = new ArrayList<>();
        for (UUID u : online) if (!u.equals(holder) && !excluded.contains(u)) pool.add(u);
        Collections.shuffle(pool, r);
        return new ArrayList<>(pool.subList(0, Math.min(Math.max(0, n), pool.size())));
    }
}
