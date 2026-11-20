package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Auratus' numbers: slam falloff, reel ramp, anchor cone, Flagpole hover, Divine Purity, Aegis cooldown, the one-shot combo, chain charges. */
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

    @Test void theLaunchComboKillsFullProt4Diamond() {
        // Full Prot IV diamond: armor 20, toughness 8, EPF 16. Default config: ability damage x3.
        double bite = AuratusMath.afterArmor(3.0 * 3, 20, 8, 16);       // the chain hook
        double strike = AuratusMath.afterArmor(5.0 * 3, 20, 8, 16);     // the Aegis shield
        double finisher = AuratusMath.afterArmor(72, 20, 8, 16);        // the launch (raw, no cap)
        assertTrue(finisher >= 20, "the launch alone kills from full health: " + finisher);
        assertTrue(bite + strike + finisher >= 24, "the whole combo gets through a golden apple's absorption too: " + (bite + strike + finisher));
        assertTrue(AuratusMath.afterArmor(72, 0, 0, 0) == 72, "unarmored takes it all");
        assertTrue(AuratusMath.afterArmor(9, 20, 8, 16) < 1.5, "a single chain hit stays small through diamond");
        assertEquals(30, RevenantMath.apexHeight(RevenantMath.leapSpeedFor(30)), 0.3, "thrown about 30 blocks up");
    }

    @Test void armorMathMatchesVanilla() {
        // 10 damage against full diamond, no Protection: armor cuts 20 - 10/4 = 17.5 points -> 70%.
        assertEquals(3.0, AuratusMath.afterArmor(10, 20, 8, 0), 1e-9);
        // Huge hits bottom out at armor / 5 (16% for 20 armor); Protection never goes past 80%.
        assertEquals(1000 * 0.84 * 0.2, AuratusMath.afterArmor(1000, 20, 8, 99), 1e-6);
    }

    @Test void chainModelsRunFromTheHookPointToTheHand() {
        double strand = 47 / 16.0, link = 6 / 16.0, step = 7 * link;
        for (double len = 0.5; len <= 45; len += 0.05) {
            double[] at = AuratusMath.chainLayout(len, strand, step, link, 60);
            if (len <= strand + 2 * link) { // just the hook, scaled to fit between hand and tip
                assertEquals(1, at.length, "at " + len);
                assertEquals(len / 2, at[0], 1e-9);
                continue;
            }
            assertEquals(strand / 2, at[0], 1e-9, "the hook's point is on the tip");
            double far = at[at.length - 1] + strand / 2;
            assertEquals(len, far, link / 2 + 1e-9, "the chain ends at the hand at " + len);
            for (int i = 1; i < at.length; i++) {
                double gap = at[i] - at[i - 1];
                assertTrue(gap >= link - 1e-9 && gap <= step + 1e-9, "no gap between strands, none drawn twice, at " + len);
                assertTrue(at[i] - at[0] >= 2 * link - 1e-9, "strands stay off the hook's claw at " + len);
                double links = (at[i] - at[0]) / link;
                assertEquals(Math.round(links), links, 1e-6, "strands stay in step with the hook at " + len);
            }
        }
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
