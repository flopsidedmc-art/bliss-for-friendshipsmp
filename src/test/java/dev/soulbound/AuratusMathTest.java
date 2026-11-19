package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Auratus' numbers: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity, Aegis cooldown, chain charges. */
class AuratusMathTest {
    @Test void slamFallsOffFromCentreToEdge() {
        assertEquals(6, AuratusMath.slamDamage(6, 3, 0, 5), 1e-9);
        assertEquals(4.5, AuratusMath.slamDamage(6, 3, 2.5, 5), 1e-9);
        assertEquals(3, AuratusMath.slamDamage(6, 3, 5, 5), 1e-9);
        assertEquals(0, AuratusMath.slamDamage(6, 3, 5.01, 5), "nothing outside the radius");
        for (double d = 0; d <= 5; d += 0.25) assertTrue(AuratusMath.slamDamage(6, 3, d, 5) <= 6);
    }

    @Test void reelRampsUpThenHolds() {
        assertTrue(AuratusMath.reelSpeed(0, 1.1, 4) > 0, "never zero on the first tick");
        assertTrue(AuratusMath.reelSpeed(1, 1.1, 4) > AuratusMath.reelSpeed(0, 1.1, 4));
        assertEquals(1.1, AuratusMath.reelSpeed(3, 1.1, 4), 1e-9);
        assertEquals(1.1, AuratusMath.reelSpeed(30, 1.1, 4), 1e-9);
        assertEquals(1.1, AuratusMath.reelSpeed(0, 1.1, 0), 1e-9);
    }

    @Test void anchorConeMeasuresTheLookAngle() {
        assertEquals(0, AuratusMath.angleTo(0, 1, 0, 0, 8, 0), 1e-6, "looking straight at it");
        assertEquals(90, AuratusMath.angleTo(1, 0, 0, 0, 8, 0), 1e-6);
        assertEquals(45, AuratusMath.angleTo(1, 1, 0, 0, 5, 0), 1e-6);
        assertEquals(180, AuratusMath.angleTo(0, 0, 0, 0, 1, 0), 1e-6, "no direction: never inside the cone");
    }

    @Test void flagpoleEasesUpAndHolds() {
        assertEquals(0.6, AuratusMath.hoistVelocity(60, 65, 0.6, 0.3), 1e-9, "capped on the way up");
        assertEquals(0.35, AuratusMath.hoistVelocity(64, 65, 0.6, 0.3), 1e-9, "slows near the top");
        assertEquals(0, AuratusMath.hoistVelocity(65, 65, 0.6, 0.3), 1e-9, "holds at the top");
        assertEquals(-0.3, AuratusMath.hoistVelocity(70, 65, 0.6, 0.3), 1e-9, "pushed above: drifts back, capped");
    }

    @Test void divinePurityShortensButNeverRemoves() {
        assertEquals(100, AuratusMath.purifiedDuration(200, 0.5));
        assertEquals(1, AuratusMath.purifiedDuration(1, 0.5), "never below one tick");
        assertEquals(-1, AuratusMath.purifiedDuration(-1, 0.5), "infinite stays infinite");
        assertEquals(200, AuratusMath.purifiedDuration(200, 3), "a multiplier above 1 never lengthens");
    }

    @Test void aegisRewardsAParry() {
        assertEquals(15_000, AuratusMath.aegisCooldownMs(false, 15, 10));
        assertEquals(10_000, AuratusMath.aegisCooldownMs(true, 15, 10));
        assertEquals(15_000, AuratusMath.aegisCooldownMs(true, 15, 20), "a parry never makes it longer");
    }

    @Test void chainChargesAreIndependent() {
        Charges c = new Charges(2);
        assertTrue(c.use(0, 8_000));
        assertTrue(c.use(2_000, 8_000));
        assertFalse(c.use(2_000, 8_000));
        assertEquals(1, c.ready(8_000), "the first chain is back 8s after it was thrown");
        assertEquals(2, c.ready(10_000));
    }
}
