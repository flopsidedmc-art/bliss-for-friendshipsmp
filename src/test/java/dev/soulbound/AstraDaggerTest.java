package dev.soulbound;

import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Astra Daggers: a volley of five before the cooldown, and the flying dagger points where it's going. */
class AstraDaggerTest {
    private static String src() throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java")); }

    /** The bug: every left-click restarted the 90 s cooldown, so only one dagger could be fired. */
    @Test void leftClickDoesNotStartTheCooldownBeforeFiring() throws Exception {
        String s = src();
        int at = s.indexOf("long rem = cdRemain(p, \"daggers\");");
        assertTrue(at >= 0, "the Astra left-click checks the daggers cooldown");
        String click = s.substring(at, s.indexOf("return;\n            }", at));
        assertFalse(click.contains("cdStart(p, \"daggers\""), "a left-click must not start the cooldown itself");
        int h = s.indexOf("private void handleAstraDaggers(");
        String handle = s.substring(h, s.indexOf("\n    }", h));
        assertTrue(handle.contains("fireAstraDagger(p)") && handle.contains("<= 0) cdStart(p, \"daggers\""),
                "the cooldown starts once the last dagger of the volley is fired");
    }

    @Test void flyingDaggerPointsAlongItsFlight() {
        for (Vector v : new Vector[] {new Vector(1, 0, 0), new Vector(0, 0, -3), new Vector(2, 1, -1), new Vector(0.01, -3, 0.01)}) {
            Transformation t = GemListener.daggerPose(v, 1f);
            Vector3f tip = new Vector3f(0, 1, 0).rotate(t.getLeftRotation());
            Vector3f want = new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ()).normalize();
            assertEquals(1.0, tip.dot(want), 1e-4, "the blade points along " + v);
            Vector3f face = new Vector3f(0, 0, 1).rotate(t.getLeftRotation());
            if (Math.abs(want.y) < 0.99) assertEquals(0, face.y, 1e-4, "the flat face is turned sideways for " + v);
        }
    }
}
