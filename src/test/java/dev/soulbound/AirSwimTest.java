package dev.soulbound;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the air-swimming maths only. These tests integrate the controller's output as if the
 * client followed every velocity exactly; they are not a substitute for testing in a real client.
 */
class AirSwimTest {
    private static final AirSwim.Tuning DEFAULTS = new AirSwim.Tuning(0.38, 1.5, 0.14, 0.86, 0.10);
    private static final AirSwim.Tuning NO_STROKE = new AirSwim.Tuning(0.38, 1.5, 0.14, 0.86, 0);
    private static final double WALL = 48;

    private static Vector tick(AirSwim swim, AirSwim.Tuning t, float yaw, float pitch, String keys, Vector position) {
        Vector v = swim.step(t, yaw, pitch, keys.contains("W"), keys.contains("S"), keys.contains("A"), keys.contains("D"),
            keys.contains("^"), keys.contains("v"), keys.contains("!"), position, WALL);
        position.add(v);
        return v;
    }

    @Test void acceleratesSmoothlyToTopSpeedAndNeverSnaps() {
        AirSwim swim = new AirSwim(); Vector at = new Vector(); double previous = 0;
        for (int i = 0; i < 40; i++) {
            double speed = tick(swim, NO_STROKE, 0, 0, "W", at).length();
            assertTrue(speed >= previous - 1e-9, "speed must not drop while accelerating");
            assertTrue(speed - previous <= 0.38 * 0.14 + 1e-9, "no tick may add more than one acceleration step");
            previous = speed;
        }
        assertEquals(0.38, previous, 0.38 * 0.01, "reaches top speed within two seconds");
        assertTrue(tick(new AirSwim(), NO_STROKE, 0, 0, "W", new Vector()).length() < 0.38 * 0.2, "first tick is a gentle start");
    }

    @Test void glidesToACompleteStopWhenInputIsReleased() {
        AirSwim swim = new AirSwim(); Vector at = new Vector();
        for (int i = 0; i < 40; i++) tick(swim, DEFAULTS, 0, 0, "W", at);
        double released = at.getZ(), speed = 1;
        for (int i = 0; i < 60; i++) speed = tick(swim, DEFAULTS, 0, 0, "", at).length();
        assertEquals(0, speed, 0, "comes fully to rest; no residual drift");
        double glide = at.getZ() - released;
        assertTrue(glide > 1 && glide < 4, "glide distance should be a short coast, was " + glide);
    }

    @Test void thrustFollowsLookDirectionInThreeDimensions() {
        Vector south = tick(new AirSwim(), NO_STROKE, 0, 0, "W", new Vector());
        assertTrue(south.getZ() > 0 && Math.abs(south.getX()) < 1e-9 && Math.abs(south.getY()) < 1e-9);
        Vector upward = tick(new AirSwim(), NO_STROKE, 0, -60, "W", new Vector());
        assertTrue(upward.getY() > upward.getZ() && upward.getZ() > 0, "looking up and swimming forward climbs");
        Vector dive = tick(new AirSwim(), NO_STROKE, 0, 60, "W", new Vector());
        assertTrue(dive.getY() < 0, "looking down and swimming forward dives");
        Vector backward = tick(new AirSwim(), NO_STROKE, 0, 0, "S", new Vector());
        assertTrue(backward.getZ() < 0);
        Vector rightFacingSouth = tick(new AirSwim(), NO_STROKE, 0, 0, "D", new Vector());
        assertTrue(rightFacingSouth.getX() < 0 && Math.abs(rightFacingSouth.getZ()) < 1e-9, "facing south, right is west (-X)");
        Vector leftFacingWest = tick(new AirSwim(), NO_STROKE, 90, 0, "A", new Vector());
        assertTrue(leftFacingWest.getZ() > 0 && Math.abs(leftFacingWest.getX()) < 1e-9, "facing west, left is south (+Z)");
        Vector rise = tick(new AirSwim(), NO_STROKE, 0, 0, "^", new Vector());
        assertTrue(rise.getY() > 0 && rise.getX() == 0 && rise.getZ() == 0, "jump rises straight up");
    }

    @Test void sneakHeldFromActivationDoesNotDiveUntilReleased() {
        AirSwim swim = new AirSwim(); Vector at = new Vector();
        for (int i = 0; i < 10; i++) assertEquals(0, tick(swim, NO_STROKE, 0, 0, "v", at).length(), 0, "cast sneak is ignored");
        tick(swim, NO_STROKE, 0, 0, "", at);
        assertTrue(tick(swim, NO_STROKE, 0, 0, "v", at).getY() < 0, "after releasing once, sneak dives");
    }

    @Test void sprintIsFasterButOnlyWhenSwimmingForward() {
        AirSwim normal = new AirSwim(), sprint = new AirSwim(), backSprint = new AirSwim();
        double a = 0, b = 0, c = 0;
        for (int i = 0; i < 60; i++) {
            a = tick(normal, NO_STROKE, 0, 0, "W", new Vector()).length();
            b = tick(sprint, NO_STROKE, 0, 0, "W!", new Vector()).length();
            c = tick(backSprint, NO_STROKE, 0, 0, "S!", new Vector()).length();
        }
        assertEquals(a * 1.5, b, 1e-3);
        assertEquals(a, c, 1e-3);
    }

    @Test void turningCurvesInsteadOfSnapping() {
        AirSwim swim = new AirSwim(); Vector at = new Vector();
        for (int i = 0; i < 40; i++) tick(swim, NO_STROKE, 0, 0, "W", at);
        Vector first = tick(swim, NO_STROKE, 90, 0, "W", at);
        assertTrue(first.getZ() > 0.25, "momentum is kept on the tick the caster turns");
        Vector later = first;
        for (int i = 0; i < 40; i++) later = tick(swim, NO_STROKE, 90, 0, "W", at);
        assertTrue(later.getX() < -0.37 && Math.abs(later.getZ()) < 0.01, "and fully follows the new heading within two seconds");
    }

    @Test void strokeIsABoundedSurgeAroundTheBaseSpeed() {
        AirSwim swim = new AirSwim(); Vector at = new Vector(); double min = 9, max = 0;
        for (int i = 0; i < 200; i++) {
            double speed = tick(swim, DEFAULTS, 0, 0, "W", new Vector()).length();
            if (i > 60) { min = Math.min(min, speed); max = Math.max(max, speed); }
        }
        assertEquals(0.38 * 1.10, max, 0.005);
        assertEquals(0.38 * 0.90, min, 0.005);
    }

    @Test void softWallKeepsTheCasterInsideTheBoundaryFromEveryHeading() {
        for (float pitch : new float[]{-90, -45, 0, 45, 90})
            for (float yaw = 0; yaw < 360; yaw += 45) {
                AirSwim swim = new AirSwim(); Vector at = new Vector();
                for (int i = 0; i < 1200; i++) {
                    tick(swim, DEFAULTS, yaw, pitch, "W!", at);
                    assertTrue(at.length() < WALL, "left the boundary at yaw " + yaw + " pitch " + pitch + " tick " + i);
                }
                assertTrue(at.length() > WALL * 0.8, "the wall should not shrink the usable space much");
                // ...and the caster can still turn round and swim back in.
                for (int i = 0; i < 100; i++) tick(swim, DEFAULTS, yaw + 180, -pitch, "W", at);
                assertTrue(at.length() < WALL * 0.6, "can swim back toward the centre");
            }
    }

    @Test void extremeConfigIsClampedAndNeverProducesNaN() {
        AirSwim.Tuning wild = new AirSwim.Tuning(Double.NaN, 99, -5, 7, 99);
        assertEquals(0.05, wild.speed()); assertEquals(2, wild.sprint()); assertEquals(0.02, wild.acceleration());
        assertEquals(0.98, wild.drag()); assertEquals(0.4, wild.stroke());
        AirSwim swim = new AirSwim(); Vector at = new Vector();
        for (int i = 0; i < 400; i++) {
            Vector v = tick(swim, new AirSwim.Tuning(1.0, 2, 1, 0.98, 0.4), i * 37f, (i % 180) - 90f, i % 3 == 0 ? "WD^!" : "SAv", at);
            assertTrue(Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ()));
            assertTrue(at.length() < WALL);
        }
        assertEquals(0, new AirSwim().step(DEFAULTS, 0, 0, true, true, true, true, false, false, true, new Vector(), WALL).length(), 0,
            "opposing keys cancel out");
    }
}
