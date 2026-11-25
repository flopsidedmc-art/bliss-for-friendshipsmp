package dev.soulbound;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * Item provenance (ItemProvenanceListener), the parts that need no server (ProvenanceTest). Every tracked item has one
 * OWNER, who never changes, and one status: ORIGINAL (with its owner), STOLEN (someone else has it without being given
 * it) or GIFTED (the owner dropped it and someone else picked it up). STORED is only how it shows inside a container.
 */
final class ProvenanceMath {
    private ProvenanceMath() {}

    enum Status {
        ORIGINAL, STOLEN, GIFTED;

        static Status of(String s) {
            if (s == null) return null;
            for (Status st : values()) if (st.name().equalsIgnoreCase(s)) return st;
            return null; // the old "stored" and anything unknown
        }

        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Who owns an item (and what they were called when it became theirs) and its status. */
    record Prov(UUID owner, String name, Status status) {
        Prov with(Status s) { return new Prov(owner, name, s); }
    }

    /**
     * The item reaches {@code holder}. {@code givenByOwner}: it's an item its owner dropped (Q, or out of their
     * inventory) while alive. Rules: an item nobody owns yet becomes the holder's, ORIGINAL; back with its owner it's
     * ORIGINAL again; dropped by its owner for someone else it's GIFTED; the owner's item anyone else gets any other way
     * (their death drop, a container, a frame, a stand, a pot) is STOLEN; passed on again it keeps its status.
     */
    static Prov arrive(Prov p, UUID holder, String holderName, boolean givenByOwner) {
        if (p == null || p.owner() == null || p.status() == null) return new Prov(holder, holderName, Status.ORIGINAL);
        if (p.owner().equals(holder)) return p.status() == Status.ORIGINAL ? p : p.with(Status.ORIGINAL);
        if (givenByOwner) return p.with(Status.GIFTED);
        if (p.status() == Status.ORIGINAL) return p.with(Status.STOLEN);
        return p;
    }

    /** Bounty offerings: an item counts toward hunting {@code target} if it was stolen from them or gifted by them. */
    static boolean counts(Prov p, UUID target) {
        return p != null && target != null && target.equals(p.owner()) && (p.status() == Status.STOLEN || p.status() == Status.GIFTED);
    }

    // ------------------------------------------------------------------ the lore line

    /** The line as text: the status word and its joining word in small caps, then the owner's name as it's written. */
    static String line(String word, String joiner, String name) {
        return SmallCaps.of(word) + " " + SmallCaps.of(joiner) + " " + name;
    }

    /** Is this lore line (plain text) a provenance line? {@code words}: the four status words as written in config. */
    static boolean isLine(String plain, Collection<String> words) {
        if (plain == null) return false;
        String s = plain.trim();
        for (String w : words) {
            String sc = SmallCaps.of(w);
            if (!sc.isEmpty() && s.startsWith(sc + " ")) return true;
        }
        return false;
    }
}
