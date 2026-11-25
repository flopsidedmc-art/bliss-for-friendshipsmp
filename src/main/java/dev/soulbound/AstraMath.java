package dev.soulbound;

import org.bukkit.util.Vector;

/** Astra's revamp, the parts that need no server (AstraTest): the controls, the dagger fan, the projection leash. */
final class AstraMath {
    private AstraMath() {}

    enum Action { DAGGERS, UNBOUNDED, VOID, PROJECTION, NONE }

    /**
     * Tier 2, gem in the main hand. Left-click: Daggers. Sneak + right-click: Astral Projection (anything under the
     * crosshair). Right-click a player: Unbounded. Right-click anywhere else (air, a block, a creature): Astral Void.
     * (Off-hand right-click is Dimensional Drift, routed with the other off-hand abilities.)
     */
    static Action route(boolean left, boolean right, boolean sneaking, boolean onPlayer) {
        if (left) return Action.DAGGERS;
        if (!right) return Action.NONE;
        if (sneaking) return Action.PROJECTION;
        return onPlayer ? Action.UNBOUNDED : Action.VOID;
    }

    // ------------------------------------------------------------------ daggers

    static final int DAGGERS = 5;

    /**
     * Where dagger {@code i} (0..4, left to right) hovers, relative to your feet and facing: {right, up, forward}
     * blocks. A fan behind your shoulders, highest in the middle, all of them behind your back.
     */
    static double[] fanSlot(int i) {
        double a = Math.toRadians(-50 + 25 * Math.max(0, Math.min(DAGGERS - 1, i)));
        return new double[] {0.85 * Math.sin(a), 1.35 + 0.3 * Math.cos(a), -0.4 - 0.15 * Math.cos(a)};
    }

    /** A fan's offset turned to the yaw you face (Minecraft yaw: 0 = +Z, 90 = -X). */
    static Vector turned(double[] local, float yaw) {
        double r = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(r), 0, Math.cos(r));
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());
        return right.multiply(local[0]).add(new Vector(0, local[1], 0)).add(fwd.multiply(local[2]));
    }

    /** No throw for linger-seconds: the rest fade out and the cooldown starts, as if all 5 were thrown. */
    static boolean lingered(long lastThrowAt, long now, long lingerMs) { return now - lastThrowAt >= lingerMs; }

    // ------------------------------------------------------------------ projection

    /**
     * The leash: a spot further than {@code max} from the body is pulled back onto the edge (half a block inside, so
     * you aren't pulled every tick). Within reach: null (leave them be).
     */
    static Vector clamp(Vector body, Vector at, double max) {
        Vector d = at.clone().subtract(body);
        double len = d.length();
        if (len <= max || len < 1e-9) return null;
        return body.clone().add(d.multiply(Math.max(0, max - 0.5) / len));
    }
}
