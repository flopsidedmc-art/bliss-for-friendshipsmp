package dev.soulbound;

import org.bukkit.util.Vector;

/**
 * Caster-only air-swimming controller for Temporal Divinity.
 *
 * One instance per domain session. Pure math: it keeps its own velocity (the server's view of a
 * player's velocity is not a reliable simulation state) and never touches Bukkit entities, so it
 * can be unit-tested without a server. TemporalDomain feeds it the caster's real key input and
 * sends the returned vector with Player#setVelocity once per tick.
 *
 * Feel: thrust follows the full look direction (look up + forward swims up, like sprint-swimming),
 * velocity eases toward the wanted speed instead of snapping, and with no input the caster glides
 * to rest. That is what separates it from creative flight, which is flat, instant and drag-heavy.
 */
final class AirSwim {
    /** Blocks/tick top speed, sprint multiplier, ease-in fraction per tick, glide retention per tick, stroke surge fraction. */
    record Tuning(double speed, double sprint, double acceleration, double drag, double stroke) {
        Tuning {
            speed = bound(speed, 0.05, 1.0);
            sprint = bound(sprint, 1, 2);
            acceleration = bound(acceleration, 0.02, 1);
            drag = bound(drag, 0, 0.98);
            stroke = bound(stroke, 0, 0.4);
        }
    }

    /** One swimming stroke (surge then glide) every 0.8 seconds. */
    static final int STROKE_TICKS = 16;

    private final Vector velocity = new Vector();
    private double phase, strokeGain;
    private boolean sneakReleased;

    static double bound(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /** Unit look vector using Minecraft's yaw/pitch convention (yaw 0 = +Z, pitch -90 = straight up). */
    static Vector look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Horizontal unit vector pointing to the player's right. */
    static Vector right(float yaw) {
        double y = Math.toRadians(yaw);
        return new Vector(-Math.cos(y), 0, -Math.sin(y));
    }

    /**
     * Advances one server tick.
     *
     * @param offset caster position relative to the domain centre
     * @param wall   radius at which the session would end; the caster is eased to a stop before it
     * @return the velocity to send to the caster this tick (a fresh vector)
     */
    Vector step(Tuning t, float yaw, float pitch, boolean forward, boolean back, boolean left, boolean right,
                boolean up, boolean down, boolean sprint, Vector offset, double wall) {
        // Divinity is cast with sneak held, so sneak only means "dive" once it has been released.
        if (!down) sneakReleased = true; else if (!sneakReleased) down = false;

        Vector wish = new Vector();
        if (forward != back) wish.add(look(yaw, pitch).multiply(forward ? 1 : -1));
        if (left != right) wish.add(right(yaw).multiply(right ? 1 : -1));
        if (up != down) wish.setY(wish.getY() + (up ? 1 : -1));

        boolean stroking = wish.lengthSquared() > 1.0e-6;
        if (stroking) {
            double top = t.speed() * (sprint && forward && !back ? t.sprint() : 1);
            wish.normalize().multiply(top);
            velocity.add(wish.subtract(velocity).multiply(t.acceleration()));
        } else {
            velocity.multiply(t.drag());
            if (velocity.lengthSquared() < 1.0e-5) velocity.zero();
        }

        // Soft wall: bleed off outward speed over the last stretch so the caster cannot swim out
        // of the boundary and end their own domain; past the hard edge, ease back inside.
        double distance = offset.length(), hard = wall * 0.97, soft = wall * 0.85;
        if (distance > soft && distance > 1.0e-6) {
            Vector outward = offset.clone().multiply(1 / distance);
            double keep = bound((hard - distance) / (hard - soft), 0, 1);
            double out = velocity.dot(outward);
            if (out > 0) velocity.subtract(outward.clone().multiply(out * (1 - keep)));
            if (keep <= 0) velocity.subtract(outward.multiply(0.1));
        }

        // Stroke rhythm: a gentle sinusoidal surge while actively swimming, fading out in a glide.
        strokeGain = stroking ? Math.min(1, strokeGain + 0.1) : strokeGain * 0.8;
        phase = (phase + Math.PI * 2 / STROKE_TICKS) % (Math.PI * 2);
        Vector result = velocity.clone().multiply(1 + t.stroke() * strokeGain * Math.sin(phase));

        // Last guard for extreme tuning: never send a velocity whose next position crosses the hard edge.
        Vector next = offset.clone().add(result);
        if (next.length() > hard && next.length() > distance && distance > 1.0e-6) {
            Vector outward = offset.clone().multiply(1 / distance);
            double out = result.dot(outward);
            if (out > 0) result.subtract(outward.multiply(out));
        }
        return result;
    }
}
