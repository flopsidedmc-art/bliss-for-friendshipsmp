package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The combat rules module: the fight tag, per-fight counters, carry caps, the debuff check, maces, name scrambles, the wiring. */
class RulesTest {
    private static final long TAG = 15_000;

    private static String read(String f) throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/" + f)); }

    private static YamlConfiguration config() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    // ------------------------------------------------------------------ the combat tag

    @Test void aFightLastsTagSecondsAfterTheLastHit() {
        RuleMath.Fights f = new RuleMath.Fights();
        UUID a = UUID.randomUUID();
        assertFalse(f.inFight(a, 0));
        assertTrue(f.hit(a, 0, TAG), "the first hit starts a fight (the action bar shows In combat once)");
        assertTrue(f.inFight(a, 14_999));
        assertFalse(f.hit(a, 5_000, TAG), "another hit keeps the same fight going, no second message");
        assertTrue(f.inFight(a, 19_999), "each hit pushes the end back to 15 s after it");
        assertFalse(f.inFight(a, 20_000));
        assertTrue(f.expired(19_999).isEmpty());
        assertEquals(List.of(a), f.expired(20_000), "out of combat, reported once");
        assertTrue(f.expired(30_000).isEmpty(), "and only once");
        assertEquals(0, f.size());
        assertTrue(f.hit(a, 40_000, TAG), "a hit after it ended starts a new fight");
    }

    @Test void fightsAreEachPlayersOwn() {
        RuleMath.Fights f = new RuleMath.Fights();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        f.hit(a, 0, TAG);
        f.hit(b, 10_000, TAG);
        assertEquals(List.of(a), f.expired(15_000));
        assertTrue(f.inFight(b, 15_000));
    }

    // ------------------------------------------------------------------ per-fight counters

    @Test void threeEnchantedApplesPerFight() {
        RuleMath.Fights f = new RuleMath.Fights();
        UUID a = UUID.randomUUID();
        for (int i = 0; i < 5; i++) assertTrue(f.eatEgap(a, 0, 3), "outside a fight they're never limited");
        assertEquals(0, f.egaps(a), "and not counted");
        f.hit(a, 0, TAG);
        assertTrue(f.eatEgap(a, 1_000, 3));
        assertTrue(f.eatEgap(a, 2_000, 3));
        assertTrue(f.eatEgap(a, 3_000, 3));
        assertFalse(f.eatEgap(a, 4_000, 3), "the 4th is refused");
        assertEquals(3, f.egaps(a), "a refused one isn't counted");
        f.expired(20_000); // 15 s out of combat
        f.hit(a, 30_000, TAG);
        assertEquals(0, f.egaps(a), "a new fight starts at 0");
        assertTrue(f.eatEgap(a, 31_000, 3));
    }

    @Test void oneTotemPerFightAndCountersResetOnDeathOrLogout() {
        RuleMath.Fights f = new RuleMath.Fights();
        UUID a = UUID.randomUUID();
        f.hit(a, 0, TAG);
        assertTrue(f.useTotem(a, 1_000, 1));
        assertFalse(f.useTotem(a, 2_000, 1), "a second totem in the same fight doesn't save you");
        f.eatEgap(a, 2_000, 3);
        assertTrue(f.end(a), "death or logout ends the fight");
        assertFalse(f.end(a));
        assertEquals(0, f.totems(a));
        assertEquals(0, f.egaps(a));
        f.hit(a, 3_000, TAG);
        assertTrue(f.useTotem(a, 4_000, 1), "the next fight has its totem again");
        assertTrue(f.useTotem(UUID.randomUUID(), 4_000, 1), "outside a fight a totem always works");
    }

    // ------------------------------------------------------------------ carry caps

    @Test void carryCaps() {
        assertEquals(64, RuleMath.fits(0, 64, 128));
        assertEquals(28, RuleMath.fits(100, 64, 128), "only what fits under the cap");
        assertEquals(0, RuleMath.fits(128, 1, 128), "a third stack is refused");
        assertEquals(0, RuleMath.fits(150, 10, 128), "already over: nothing");
        assertEquals(0, RuleMath.over(128, 128));
        assertEquals(2, RuleMath.over(130, 128));
        assertEquals(0, RuleMath.over(10, 128));
    }

    @Test void extraIsTakenFromTheOffHandLast() {
        int[] o = RuleMath.TAKE_ORDER;
        assertEquals(37, o.length);
        assertEquals(35, o[0], "the main inventory first");
        assertEquals(0, o[35], "then the hotbar");
        assertEquals(40, o[36], "the off hand last");
        Set<Integer> seen = new HashSet<>();
        for (int i : o) assertTrue(seen.add(i));
    }

    // ------------------------------------------------------------------ debuff potions

    @Test void debuffCheck() {
        List<String> banned = config().getStringList("rules.debuff-potions.effects");
        assertTrue(RuleMath.debuff(List.of("weakness"), banned));
        assertTrue(RuleMath.debuff(List.of("minecraft:slowness"), banned), "namespaced keys too");
        assertTrue(RuleMath.debuff(List.of("INSTANT_DAMAGE"), banned), "any case");
        assertTrue(RuleMath.debuff(List.of("resistance", "slowness"), banned), "one debuff is enough (Turtle Master)");
        assertFalse(RuleMath.debuff(List.of("speed", "strength", "regeneration", "fire_resistance", "invisibility"), banned), "positive potions are untouched");
        assertFalse(RuleMath.debuff(List.of(), banned), "water");
        assertTrue(RuleMath.debuff(List.of("poison"), List.of("minecraft:poison")));
    }

    @Test void everyDebuffFromThePromptIsBanned() {
        List<String> banned = config().getStringList("rules.debuff-potions.effects");
        for (String e : List.of("weakness", "slowness", "poison", "instant_damage", "wither", "blindness", "darkness", "nausea",
                "mining_fatigue", "hunger", "levitation", "infested", "oozing", "weaving", "wind_charged")) assertTrue(banned.contains(e), e);
        assertEquals(15, banned.size());
    }

    // ------------------------------------------------------------------ maces

    @Test void maceRaceAndEventModes() {
        assertEquals(RuleMath.MaceMode.RACE, RuleMath.MaceMode.of("race"));
        assertEquals(RuleMath.MaceMode.EVENT, RuleMath.MaceMode.of("EVENT"));
        assertEquals(RuleMath.MaceMode.RACE, RuleMath.MaceMode.of(null), "race by default");
        assertEquals(RuleMath.MaceMode.RACE, RuleMath.MaceMode.of("nonsense"));
        for (int n = 0; n < 3; n++) assertTrue(RuleMath.canCraft(RuleMath.MaceMode.RACE, n, 3), n + " made");
        assertFalse(RuleMath.canCraft(RuleMath.MaceMode.RACE, 3, 3), "the 4th is blocked");
        assertFalse(RuleMath.canCraft(RuleMath.MaceMode.RACE, 5, 3), "more already exist (from before the rule)");
        for (int n = 0; n < 4; n++) assertFalse(RuleMath.canCraft(RuleMath.MaceMode.EVENT, n, 3), "no crafting in event mode");
        assertTrue(RuleMath.canGive(2, 3));
        assertFalse(RuleMath.canGive(3, 3), "an admin's give still counts toward the limit");
    }

    @Test void maceEnchantCapsAndSmash() {
        assertEquals(3, RuleMath.capLevel(5, 3), "Density V becomes III");
        assertEquals(2, RuleMath.capLevel(2, 3));
        assertEquals(2, RuleMath.capLevel(4, 2), "Breach IV becomes II");
        assertEquals(0, RuleMath.capLevel(3, 0));
        assertFalse(RuleMath.smashAllowed(true, 0, 10_000, 2_000), "no smash while gliding");
        assertFalse(RuleMath.smashAllowed(false, 9_000, 10_000, 2_000), "or 1 s after");
        assertTrue(RuleMath.smashAllowed(false, 8_000, 10_000, 2_000), "2 s after is fine");
        assertTrue(RuleMath.smashAllowed(false, 0, 10_000, 2_000), "never glided");
    }

    // ------------------------------------------------------------------ invisibility

    @Test void scrambledNamesAreFourToTenLetters() {
        Random r = new Random(7);
        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String s = RuleMath.scramble(r);
            assertTrue(s.length() >= 4 && s.length() <= 10, s);
            assertTrue(s.matches("[a-z]+"), s);
            lengths.add(s.length());
        }
        assertEquals(7, lengths.size(), "every length from 4 to 10 comes up");
    }

    @Test void deathMessageLosesTheNameAndTheWeapon() {
        CombatRules rules = new CombatRules(null, null); // mask needs no server
        Component msg = Component.translatable("death.attack.player.item", Component.text("Bob"), Component.text("Alice"), Component.text("[Excalibur]"));
        Component masked = rules.mask(msg, null, "Alice");
        TranslatableComponent t = assertInstanceOf(TranslatableComponent.class, masked);
        assertEquals("death.attack.player", t.key(), "\"using [item]\" is gone");
        assertEquals(2, t.args().size());
        assertEquals("Bob", PlainTextComponentSerializer.plainText().serialize(t.args().get(0)), "a visible victim keeps their name");
        Component killer = t.args().get(1);
        String shown = PlainTextComponentSerializer.plainText().serialize(killer);
        assertNotEquals("Alice", shown);
        assertTrue(shown.length() >= 4 && shown.length() <= 10);
        assertEquals(TextDecoration.State.TRUE, killer.decoration(TextDecoration.OBFUSCATED), "shown as the magic scramble");

        Component victimHidden = rules.mask(Component.translatable("death.attack.player", Component.text("Bob"), Component.text("Alice")), "Bob", null);
        TranslatableComponent v = (TranslatableComponent) victimHidden;
        assertEquals("death.attack.player", v.key());
        assertNotEquals("Bob", PlainTextComponentSerializer.plainText().serialize(v.args().get(0)), "an invisible victim is scrambled too");
        assertEquals("Alice", PlainTextComponentSerializer.plainText().serialize(v.args().get(1)));
    }

    // ------------------------------------------------------------------ the wiring

    @Test void everyRuleHasItsOwnSwitchAndTheDefaults() {
        YamlConfiguration c = config();
        for (String rule : RuleMath.RULES) assertTrue(c.getBoolean("rules." + rule + ".enabled", false), rule + " is on by default with its own enabled");
        assertEquals(17, RuleMath.RULES.size());
        assertFalse(c.getBoolean("rules.exempt-revenant", true));
        assertTrue(c.isConfigurationSection("rules.worlds"), "the per-world toggle");
        assertEquals(15, c.getInt("rules.combat.tag-seconds"));
        assertEquals(3, c.getInt("rules.enchanted-golden-apples.max-per-fight"));
        assertEquals(128, c.getInt("rules.golden-apples.max-carried"));
        assertEquals(128, c.getInt("rules.cobwebs.max-carried"));
        assertEquals(1, c.getInt("rules.totems.max-per-fight"));
        assertEquals(7, c.getInt("rules.ender-pearls.fight-cooldown-seconds"));
        assertEquals(5, c.getInt("rules.wind-charges.cooldown-seconds"));
        assertEquals(7, c.getInt("rules.chorus-fruit.fight-cooldown-seconds"));
        assertEquals(3, c.getInt("rules.mace.max-on-server"));
        assertEquals("race", c.getString("rules.mace.mode"));
        assertFalse(c.getBoolean("rules.mace.destroyed-frees-slot", true));
        assertEquals(3, c.getInt("rules.mace.max-density"));
        assertEquals(2, c.getInt("rules.mace.max-breach"));
        assertEquals(2, c.getInt("rules.mace.no-smash-after-gliding-seconds"));
        assertEquals(8, c.getInt("rules.riptide.fight-cooldown-seconds"));
        assertEquals(5, c.getInt("rules.elytra.no-glide-after-hit-seconds"));
        assertTrue(c.getBoolean("rules.invisibility.chat-name", false));
        assertFalse(c.getBoolean("rules.invisibility.tab", true), "the tab list is left alone by default");
    }

    @Test void everyRuleIsCheckedSomewhere() throws Exception {
        String all = read("CombatRules.java") + read("ItemRules.java") + read("MaceRules.java") + read("RuleCore.java");
        for (String rule : RuleMath.RULES) {
            if (rule.equals("golden-apples") || rule.equals("cobwebs")) assertTrue(all.contains("\"" + rule + "\""), rule);
            else assertTrue(all.contains("core.on(\"" + rule + "\""), rule + " has a switch that's read");
        }
    }

    @Test void theModuleIsWiredIn() throws Exception {
        String plugin = read("SoulboundGemsPlugin.java");
        for (String s : List.of("new CombatRules(this, rules)", "registerEvents(combatRules, this)", "combatRules.start()",
                "registerEvents(new ItemRules(this, rules), this)", "registerEvents(maceRules, this)",
                "getCommand(\"rules\")", "combatRules.shutdown()")) assertTrue(plugin.contains(s), s);
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertTrue(yml.contains("\n  rules:\n"), "the /rules command");
        assertTrue(yml.contains("soulbound.rules.admin:"));
        assertTrue(yml.contains("soulbound.rules.see-invisible:"));
        assertTrue(read("RulesCommand.java").contains("RulesCommand.ADMIN") || read("RulesCommand.java").contains("hasPermission(ADMIN)"));
        assertTrue(read("CaptureManager.java").contains("plugin.nameHidden("), "capture messages scramble an invisible leader or winner");
        String mace = read("MaceRules.java");
        assertTrue(mace.contains("isLiveItem"), "the Revenant's mace is never counted");
        for (String c : List.of("Cause.DEATH", "Cause.DESPAWN", "Cause.OUT_OF_WORLD")) assertTrue(mace.contains(c), "a mace lost to " + c);
        assertTrue(mace.contains("DamageType.MACE_SMASH"), "the glide rule only takes away real smash damage");
    }
}
