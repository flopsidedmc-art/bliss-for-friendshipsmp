package dev.soulbound;

import org.bukkit.GameMode;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who Astra has put in spectator (Astral Projection, Unbounded), and how to put them back (AstraTest). Every way out
 * goes through {@link #end}: it hands back the game mode and the spot exactly once, so nobody is left in spectator
 * and nobody is restored twice.
 */
final class SpectatorSessions {
    enum Kind { PROJECTION, UNBOUNDED }

    /** Every way a session can end. */
    enum End { TIME_UP, SNEAK, BODY_HIT, TARGET_GONE, LOGOUT, DEATH, WORLD_CHANGE, DISABLE, MODE_CHANGED }

    /** {@code home}: the body's exact spot and facing (projection) or where the rider stood (Unbounded). */
    record Session(Kind kind, GameMode mode, Location home, long endsAt) {}

    private final Map<UUID, Session> live = new HashMap<>();

    /** False if they're already in one (no stacking, and the first game mode is the one that comes back). */
    boolean start(UUID id, Kind kind, GameMode mode, Location home, long endsAt) {
        if (live.containsKey(id) || mode == GameMode.SPECTATOR) return false;
        live.put(id, new Session(kind, mode, home.clone(), endsAt));
        return true;
    }

    Session get(UUID id) { return live.get(id); }

    boolean in(UUID id, Kind kind) {
        Session s = live.get(id);
        return s != null && s.kind() == kind;
    }

    boolean any(UUID id) { return live.containsKey(id); }

    Set<UUID> ids() { return Set.copyOf(live.keySet()); }

    /** Ends it, once: what to restore (the game mode and spot), or null if it had already ended. */
    Session end(UUID id, End why) { return live.remove(id); }

    /**
     * Where they go back to. Projection: always the body's spot and facing. Unbounded: just behind the target
     * ({@code behindTarget}, already made safe), or where the target was last seen if they're gone, or where the rider
     * started if neither is known. Death: nowhere (they respawn), but the game mode still comes back.
     */
    static Location where(Session s, End why, Location behindTarget, Location targetLast) {
        if (why == End.DEATH) return null;
        if (s.kind() == Kind.PROJECTION) return s.home();
        if (why == End.TARGET_GONE && targetLast != null) return targetLast;
        if (behindTarget != null) return behindTarget;
        return targetLast != null ? targetLast : s.home();
    }
}
