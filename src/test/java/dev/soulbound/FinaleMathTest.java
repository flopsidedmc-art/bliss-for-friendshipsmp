package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pedestals land on the ground, and the merge happens between them. */
class FinaleMathTest {
    @Test void dropsFromTheSkyToTheGround() {
        // Spawn set at y 100, the ground's top block at 63: the pedestal stands at 64, not floating at 100.
        assertEquals(64, FinaleMath.surfaceY(100, -64, 319, y -> y <= 63));
    }

    @Test void climbsOutOfTheGround() {
        assertEquals(64, FinaleMath.surfaceY(60, -64, 319, y -> y <= 63), "started inside the hill");
    }

    @Test void standsOnAPlatform() {
        // A one-block platform at 80 over open air: it stays on the platform.
        assertEquals(81, FinaleMath.surfaceY(85, -64, 319, y -> y == 80));
    }

    @Test void keepsItsHeightOverTheVoid() {
        assertEquals(70, FinaleMath.surfaceY(70, 0, 255, y -> false));
    }

    @Test void mergesBetweenThePedestals() {
        double[] f = FinaleMath.mergeFrame(-6, 0, 6, 0); // the default layout, either side of Spawn
        assertArrayEquals(new double[]{0, 0, 6, Math.PI}, f, 1e-9);
        double[] g = FinaleMath.mergeFrame(10, 20, 10, 40); // set with /turmoil pedestal, along z
        assertEquals(10, g[0], 1e-9);
        assertEquals(30, g[1], 1e-9);
        assertEquals(10, g[2], 1e-9);
        assertEquals(-Math.PI / 2, g[3], 1e-9, "Auratus sits toward -z from the middle");
    }
}
