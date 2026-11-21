package dev.soulbound;

import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Auratus hook (chain_end, custom model data 12349) must have its claw on the target and its own links trailing
 * back toward the hand. Positions are worked out the way the client draws an item display: the entity's
 * transformation (translation, left rotation, scale, right rotation), then vanilla's 180 degree turn about Y for item
 * displays, then the model centred on (8, 8, 8) px.
 */
class HookOrientationTest {
    private static final float PX = 1 / 16f;
    private static final float HALF = 23.5f / 16f; // the strand is 47 px long, centred on the model

    /** Where a model point (in px, Blockbench coordinates) is drawn, relative to the display's position. */
    private static Vector3f drawn(Transformation t, float x, float y, float z) {
        Matrix4f m = new Matrix4f().translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale())
                .rotate(t.getRightRotation()).rotateY((float) Math.PI); // the item display renderer's own turn
        return m.transformPosition(new Vector3f(x * PX - 0.5f, y * PX - 0.5f, z * PX - 0.5f));
    }

    private static Vector3f unit(Vector v) { return new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ()).normalize(); }

    private static final Vector[] DIRS = {new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(-2, 1, 3), new Vector(0.3, -1, -0.2), new Vector(0, 0, -1)};

    @Test void clawOnTheTargetLinksBackTowardTheHand() {
        for (Vector v : DIRS) {
            Vector3f dir = unit(v);
            Transformation t = AuratusGem.hookPose(v, 1f, 1f, new Vector3f());
            // The display stands HALF behind the tip (at[0]), so the claw's point must be HALF ahead of it.
            Vector3f claw = drawn(t, 8, -0.1f, -15.5f), tail = drawn(t, 8, -0.1f, 31.5f);
            assertEquals(HALF, claw.dot(dir), 1e-3, "the claw's point is on the target, going " + v);
            assertEquals(-HALF, tail.dot(dir), 1e-3, "the hook's links run back toward the hand, going " + v);
            Vector3f side = new Vector3f(claw).sub(new Vector3f(dir).mul(claw.dot(dir)));
            assertTrue(side.length() < 0.05f, "the claw sits on the chain's line, going " + v + " (off by " + side.length() + ")");
        }
    }

    /** Without the turn, the item display's own 180 degrees would put the claw toward the hand: the bug. */
    @Test void theTurnIsWhatPutsTheClawForward() {
        Vector v = new Vector(1, 0, 0);
        Transformation t = AuratusGem.hookPose(v, 1f, 1f, new Vector3f());
        Transformation unturned = new Transformation(t.getTranslation(), t.getLeftRotation(), t.getScale(), new Quaternionf());
        assertEquals(-HALF, drawn(unturned, 8, -0.1f, -15.5f).dot(unit(v)), 1e-3);
    }

    /** The first plain strand, 42 px behind the hook, shares its last vertical link exactly: the chain joins in step. */
    @Test void hookAndFirstStrandJoinInStep() {
        for (Vector v : DIRS) {
            Vector3f dir = unit(v);
            Transformation hook = AuratusGem.hookPose(v, 1f, 1f, new Vector3f());
            Transformation strand = AuratusGem.strandPose(v, 1f);
            Vector3f back = new Vector3f(dir).mul(-42 * PX); // the strand's display stands 42 px further back
            Vector3f hookLink = drawn(hook, 8, -0.1f, 29), strandLink = drawn(strand, 8, -0.1f, 29).add(back);
            assertTrue(hookLink.distance(strandLink) < 1e-3, "the shared link lines up, going " + v);
        }
    }

    /** The flight's travel only moves it: still claw first. */
    @Test void travelOffsetKeepsTheFacing() {
        Vector v = new Vector(0, 0, 1);
        Vector3f off = new Vector3f(1, 2, 3);
        Transformation t = AuratusGem.hookPose(v, 1f, 1f, off);
        Vector3f claw = drawn(t, 8, -0.1f, -15.5f).sub(off);
        assertEquals(HALF, claw.dot(unit(v)), 1e-3);
    }
}
