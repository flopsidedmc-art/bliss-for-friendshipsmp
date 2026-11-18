package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Revenant's numbers: smash damage and its cap, leap height, independent Plunge charges, positions. */
class RevenantMathTest {
    private static final double BASE = 6, RADIUS = 4, CAP = 28;

    @Test void vanillaMaceSmashBonus() {
        assertEquals(0, RevenantMath.smashBonus(0));
        assertEquals(12, RevenantMath.smashBonus(3), 1e-9, "4 per block for the first 3");
        assertEquals(22, RevenantMath.smashBonus(8), 1e-9, "then 2 per block up to 8");
        assertEquals(30, RevenantMath.smashBonus(16), 1e-9, "then 1 per block");
    }

    @Test void damageCapHoldsFromAnyHeight() {
        assertEquals(CAP, RevenantMath.plungeDamage(BASE, 12, 0, RADIUS, CAP), 1e-9, "a 12-block plunge already hits the cap");
        assertEquals(CAP, RevenantMath.plungeDamage(BASE, 300, 0, RADIUS, CAP), 1e-9, "a cliff-height drop never exceeds it");
        assertEquals(BASE + 4, RevenantMath.plungeDamage(BASE, 1, 0, RADIUS, CAP), 1e-9, "a short drop is under the cap");
        for (double fall = 0; fall < 400; fall += 7)
            for (double d = 0; d <= RADIUS; d += 0.5)
                assertTrue(RevenantMath.plungeDamage(BASE, fall, d, RADIUS, CAP) <= CAP);
    }

    @Test void damageFallsOffToHalfAtTheEdgeAndStopsOutside() {
        double centre = RevenantMath.plungeDamage(BASE, 2, 0, RADIUS, 1000);
        assertEquals(centre / 2, RevenantMath.plungeDamage(BASE, 2, RADIUS, RADIUS, 1000), 1e-9);
        assertEquals(0, RevenantMath.plungeDamage(BASE, 50, RADIUS + 0.01, RADIUS, CAP));
    }

    @Test void leapReachesTheConfiguredHeight() {
        for (double h : new double[]{4, 8, 12, 20}) {
            double v = RevenantMath.leapSpeedFor(h);
            assertEquals(h, RevenantMath.apexHeight(v), 0.2, "leap height " + h);
        }
        assertTrue(RevenantMath.leapSpeedFor(12) < 2.5, "a sane launch speed");
    }

    @Test void plungeChargesRechargeIndependently() {
        RevenantMath.Charges c = new RevenantMath.Charges(2);
        long t = 1_000_000;
        assertEquals(2, c.ready(t));
        assertTrue(c.use(t, 12_000));
        assertTrue(c.use(t + 3_000, 12_000));
        assertFalse(c.use(t + 3_000, 12_000), "both spent");
        assertEquals(9_000, c.nextIn(t + 3_000), "the first comes back 12s after it was used");
        assertEquals(1, c.ready(t + 12_000), "one back, the other still recharging");
        assertEquals(2, c.ready(t + 15_000));
        assertEquals(0, c.nextIn(t + 15_000));
    }

    @Test void chargeCountChangesKeepTimers() {
        RevenantMath.Charges c = new RevenantMath.Charges(2);
        c.use(0, 10_000);
        c.resize(3);
        assertEquals(3, c.max());
        assertEquals(2, c.ready(5_000), "the used charge is still recharging, the new one is ready");
        long[] saved = c.times();
        RevenantMath.Charges back = new RevenantMath.Charges(3);
        back.load(saved);
        assertEquals(2, back.ready(5_000), "timers survive a save and load");
        back.refillAll();
        assertEquals(3, back.ready(0));
    }

    @Test void behindAndFacing() {
        double[] b = RevenantMath.behind(0, 0, 0, 2); // facing +Z (south): behind is -Z
        assertEquals(0, b[0], 1e-9);
        assertEquals(-2, b[1], 1e-9);
        double[] w = RevenantMath.behind(10, 10, 90, 2); // facing -X (west): behind is +X
        assertEquals(12, w[0], 1e-9);
        assertEquals(10, w[1], 1e-9);
        assertEquals(0, RevenantMath.yawTowards(0, -2, 0, 0), 1e-4, "standing behind someone facing south, look south");
        assertEquals(90, RevenantMath.yawTowards(12, 10, 10, 10), 1e-4, "look west");
    }

    @Test void landingPointsStayInsideTheWorldBorder() {
        double[] in = RevenantMath.clampInside(100, -50, 0, 0, 1000, 2);
        assertArrayEquals(new double[]{100, -50}, in, 1e-9, "inside: unchanged");
        double[] out = RevenantMath.clampInside(600, -700, 0, 0, 1000, 2);
        assertArrayEquals(new double[]{498, -498}, out, 1e-9, "outside: pulled in, with a margin");
    }
}
