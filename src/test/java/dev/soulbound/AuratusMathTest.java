package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;

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

    @Test void theLaunchIsTheComboThroughFullDiamond() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        double chain = c.getDouble("mythics.auratus.chain.hit-damage"), shield = c.getDouble("mythics.auratus.aegis.strike-damage");
        double centre = c.getDouble("mythics.auratus.slam.damage-center");
        assertEquals(5.0, AuratusMath.throughDiamond(chain), 0.001, "the chain hook: 2.5 hearts through full diamond");
        assertEquals(8.0, AuratusMath.throughDiamond(shield), 0.001, "the Aegis shield: 4 hearts");
        assertEquals(18.0, centre, 1e-9, "the slam's centre is unchanged (6 x 3)");
        assertEquals(9.0, c.getDouble("mythics.auratus.slam.damage-edge"), 1e-9, "and its edge (3 x 3)");
        double fin = AuratusMath.finisherRaw(chain, shield, centre);
        double sum = AuratusMath.throughDiamond(chain) + AuratusMath.throughDiamond(shield) + AuratusMath.throughDiamond(centre);
        assertEquals(sum, AuratusMath.throughDiamond(fin), 1e-6, "the launch deals exactly the three together through full diamond");
        assertEquals(19.84, AuratusMath.throughDiamond(fin), 0.01);
        assertEquals(35.65, fin, 0.01, "its raw value");
        assertTrue(5 + 8 + AuratusMath.throughDiamond(fin) >= 24, "chain + shield + launch beats full health and a golden apple's absorption");
        assertTrue(AuratusMath.finisherRaw(chain + 5, shield, centre) > fin, "retuning the chain moves the launch");
        assertFalse(c.contains("mythics.auratus.flagpole.finisher-damage"), "no separate launch setting");
        assertEquals(30, RevenantMath.apexHeight(RevenantMath.leapSpeedFor(30)), 0.3, "thrown about 30 blocks up");
    }

    @Test void armourCountsButProtectionDoesNot() throws Exception {
        double chain = 14.495;
        assertTrue(AuratusMath.throughDiamond(chain) < chain * 0.4, "armour points cut it");
        assertEquals(AuratusMath.afterArmor(chain, 20, 8, 0), AuratusMath.throughDiamond(chain), 1e-12, "with no Protection in it");
        assertTrue(AuratusMath.afterArmor(chain, 20, 8, 16) < AuratusMath.throughDiamond(chain), "Prot IV would have cut it more");
        String g = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/soulbound/AuratusGem.java"));
        assertTrue(g.contains("e.setDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.MAGIC, 0)"), "Protection's share comes off");
        assertFalse(g.contains("DamageModifier.ARMOR"), "armour's share is left as it is");
        for (String s : new String[] {"hit(v, cfgD(\"chain.hit-damage\"", "hit(v, cfgD(\"aegis.strike-damage\"", "hit(v, dmg, p);",
                "protectionIgnored.add(v.getUniqueId());\n        try { plugin.listener().asAbility(true, () -> v.damage(finisherRaw(), p)); }", "protectionIgnored.add(attacker.getUniqueId());"})
            assertTrue(g.contains(s), "every Auratus hit ignores Protection: " + s);
        assertFalse(g.contains("plugin.listener().abilityHit("), "none goes through the x3 multiplier any more");
    }

    @Test void theTableInTheDocs() {
        assertEquals(10.0, AuratusMath.afterArmor(14.495, 15, 0, 0), 0.01, "chain through full iron");
        assertEquals(4.58, AuratusMath.afterArmor(14.495, 20, 12, 0), 0.01, "chain through full netherite");
        assertEquals(16.0, AuratusMath.afterArmor(20, 15, 0, 0), 0.01, "shield through iron");
        assertEquals(7.2, AuratusMath.afterArmor(20, 20, 12, 0), 0.01, "shield through netherite");
        assertEquals(14.495, AuratusMath.rawFor(5, 20, 8), 0.001, "the exact raw value: 10 x sqrt(6) - 10");
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
