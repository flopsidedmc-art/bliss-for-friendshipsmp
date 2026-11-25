package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Renameable gems, abilities and tiers (names.yml), and player nicknames: lookups, renames, validation, the format. */
class GemNamesTest {
    @AfterEach void back() { GemNames.use(new GemNames()); }

    @Test void lookupsAndFallbacks() {
        GemNames n = new GemNames();
        assertEquals("Fire", n.gem(GemType.FIRE));
        assertEquals("§x§B§8§9§3§5§8", n.color(GemType.RELAY), "the defaults are the old values, exactly");
        assertEquals("Crisp", n.ability(GemType.FIRE, "crisp"));
        assertEquals("Venerated Perforators", n.ability(GemType.AURATUS, "chain"));
        assertEquals("nonsense", n.ability(GemType.FIRE, "nonsense"), "an unknown id falls back to itself");
        assertEquals("Tier I", n.tier(1));
        assertEquals("Tier II", n.tier(2));
        assertEquals("Tier 3", n.tier(3), "a tier with no name");
        GemNames.use(n);
        assertEquals("Fire", GemType.FIRE.shown());
        assertEquals("§6", GemType.FIRE.tint());
    }

    @Test void untouchedNamesChangeNothing() {
        GemNames n = new GemNames();
        for (String s : new String[] {"§6◆ Used Crisp.", "§7— §c🔥 FIREBALL 🔥", "Heart Drainer: -4 hearts for 20s!", "The Auratus gem refused",
                "Fire Resistance", "§cFIRE §eGEM", "Tier 2 • Energy 5/10"}) assertSame(s, n.t(s), s);
        assertEquals(new GemNames().version(), n.version(), "the same names give the same stamp");
    }

    @Test void renameAGem() {
        GemNames n = new GemNames();
        assertTrue(n.renameGem(GemType.FIRE, "Solaris"));
        assertEquals("Solaris", n.gem(GemType.FIRE));
        assertEquals("You received a Solaris Gem!", n.t("You received a Fire Gem!"));
        assertEquals("Fire Resistance", n.t("Fire Resistance"), "the word on its own isn't the gem");
        assertTrue(n.gemChanged(GemType.FIRE));
        assertNotEquals(new GemNames().version(), n.version(), "a rename changes the stamp, so gems rebuild");
        assertTrue(n.renameGem(GemType.AURATUS, "Goldbind"));
        assertEquals("The Goldbind gem refused to be destroyed.", n.t("The Auratus gem refused to be destroyed."));
        assertEquals("GOLDBIND GEM", n.t("AURATUS GEM"), "lore headings in capitals");
        assertTrue(n.recolor(GemType.FIRE, "#FF8A2A"));
        assertEquals("§x§f§f§8§a§2§a", n.color(GemType.FIRE));
        assertFalse(n.recolor(GemType.FIRE, "not-a-colour"));
    }

    @Test void renameAnAbility() {
        GemNames n = new GemNames();
        assertTrue(n.renameAbility(GemType.FIRE, "crisp", "Scorch"));
        assertEquals("§6◆ Used Scorch.", n.t("§6◆ Used Crisp."));
        assertEquals("§7— §6Scorch", n.t("§7— §6Crisp"));
        assertEquals("Crispy", n.t("Crispy"), "only the whole word");
        assertTrue(n.renameAbility(GemType.RELAY, "dilate", "Slow Field"));
        assertEquals("⌛ SLOW FIELD §8[Right-click gem]", n.t("⌛ TIME DILATE §8[Right-click gem]"), "in capitals too");
        assertEquals("Slow Field ended", n.t("Time Dilation ended"), "and the other way the plugin spelt it");
        assertTrue(n.renameAbility(GemType.FLUX, "burst", "Pulse"));
        assertEquals("Pulse and Kinetic Overdrive", n.t("Kinetic Burst and Kinetic Overdrive"), "longest names first, nothing else touched");
        assertFalse(n.renameAbility(GemType.FIRE, "nosuch", "X"), "an ability the gem hasn't got");
        assertTrue(n.renameAbility(GemType.FIRE, "fireball", "<red>Inferno"));
        assertEquals("§7— §c🔥 §cINFERNO§c 🔥", n.t("§7— §c🔥 FIREBALL 🔥"), "a coloured name, then the line's own colour again");
    }

    @Test void namesAreCleaned() {
        assertEquals("§6Sol", GemNames.clean("<click:run_command:/op me><gold>Sol</gold></click>", 32), "no clicks or hovers, colours kept");
        assertEquals("Two words", GemNames.plain(GemNames.clean("Two\nwords", 32)), "no newlines");
        assertEquals("§cRed", GemNames.clean("&cRed", 32));
        assertNull(GemNames.clean("x".repeat(33), 32), "too long");
        assertNotNull(GemNames.clean("§l" + "x".repeat(32), 32), "colour codes don't count");
        assertNull(GemNames.clean("   ", 32));
    }

    @Test void tiersAndReset() {
        GemNames n = new GemNames();
        assertFalse(n.tiersChanged());
        assertTrue(n.renameTier(2, "Ascended"));
        assertTrue(n.tiersChanged());
        assertEquals("Ascended", n.tier(2));
        n.renameGem(GemType.FIRE, "Solaris");
        n.renameAbility(GemType.FIRE, "crisp", "Scorch");
        n.renameGem(GemType.LIFE, "Vita");
        n.reset(GemType.FIRE);
        assertEquals("Fire", n.gem(GemType.FIRE));
        assertEquals("Crisp", n.ability(GemType.FIRE, "crisp"));
        assertEquals("Vita", n.gem(GemType.LIFE), "one gem reset leaves the others");
        n.reset(null);
        assertEquals("Life", n.gem(GemType.LIFE));
        assertEquals("Tier II", n.tier(2));
        assertEquals(new GemNames().version(), n.version(), "everything back to the defaults");
    }

    @Test void namesYmlRoundTrip() throws Exception {
        File f = Files.createTempFile("names", ".yml").toFile();
        f.delete();
        GemNames fresh = GemNames.load(f);
        assertTrue(f.exists(), "created with the defaults");
        assertEquals(new GemNames().version(), fresh.version(), "nothing changes until someone edits it");
        assertEquals("Crisp", YamlConfiguration.loadConfiguration(f).getString("gems.FIRE.abilities.crisp"));
        fresh.renameGem(GemType.FIRE, "Solaris");
        fresh.renameAbility(GemType.FIRE, "crisp", "Scorch");
        fresh.save(f);
        GemNames back = GemNames.load(f);
        assertEquals("Solaris", back.gem(GemType.FIRE));
        assertEquals("Scorch", back.ability(GemType.FIRE, "crisp"));
        assertEquals("§x§B§8§9§3§5§8", back.color(GemType.RELAY), "an untouched colour reads back exactly");
        f.delete();
    }

    @Test void whatPlayersSeeGoesThroughTheNames() throws Exception {
        String gm = Files.readString(new File("src/main/java/dev/soulbound/GemManager.java").toPath());
        assertEquals(10, gm.split("m.setLore\\(styled\\(named\\(type, lore\\)\\)\\)", -1).length - 1, "every gem's lore, mythics too");
        assertFalse(gm.contains("type.color + \"§l\" + type.display"), "item names are the server's names");
        String gl = Files.readString(new File("src/main/java/dev/soulbound/GemListener.java").toPath());
        assertFalse(gl.matches("(?s).*\\bused(On|Group)?\\((p|caster|attacker), \"[A-Z].*"), "every \"Used ...\" line goes through the names");
        int wrapped = 0;
        for (File f : new File("src/main/java/dev/soulbound").listFiles((d, n) -> n.endsWith(".java")))
            wrapped += Files.readString(f.toPath()).split("GemNames\\.tx\\(", -1).length - 1;
        assertTrue(wrapped > 100, "messages, titles, menus and labels with ability names: " + wrapped);
        String plugin = Files.readString(new File("src/main/resources/plugin.yml").toPath());
        assertTrue(plugin.contains("  gems:") && plugin.contains("  gem:") && plugin.contains("soulbound.admin.names:"));
    }

    // ------------------------------------------------------------------ nicknames

    private static final Nicknames.Rules RULES = new Nicknames.Rules(true, 16, "[A-Za-z0-9 .,!?'\"()_-]+", List.of("admin", "staff"), 600_000);

    @Test void nicknameValidation() {
        assertTrue(Nicknames.check("Sunny", RULES, false).ok());
        assertEquals("Sunny", Nicknames.check("  Sunny ", RULES, false).value());
        assertTrue(Nicknames.check("Old Faithful!", RULES, false).ok());
        assertFalse(Nicknames.check("x".repeat(17), RULES, false).ok(), "longer than 16");
        assertTrue(Nicknames.check("x".repeat(16), RULES, false).ok());
        assertFalse(Nicknames.check("The Admin", RULES, false).ok(), "a blocked word");
        assertFalse(Nicknames.check("st.aff", RULES, false).ok(), "even split up");
        assertFalse(Nicknames.check("Sun@ny", RULES, false).ok(), "characters outside the list");
        assertFalse(Nicknames.check("&cSunny", RULES, false).ok(), "no colour codes without the permission");
        assertFalse(Nicknames.check("<red>Sunny", RULES, false).ok());
        Nicknames.Result coloured = Nicknames.check("&cSunny", RULES, true);
        assertTrue(coloured.ok(), "with soulbound.nickname.color");
        assertEquals("§cSunny", coloured.value());
        assertFalse(Nicknames.check("", RULES, false).ok());
        assertFalse(Nicknames.check("Sunny", new Nicknames.Rules(false, 16, ".*", List.of(), 0), false).ok(), "turned off");
        String error = Nicknames.check("x".repeat(30), RULES, false).error();
        assertTrue(error.contains("16"), "a clear message: " + error);
    }

    @Test void nicknameFormatAndCooldown() {
        assertEquals("§6§l\"Sunny\" §7(Fire)", Nicknames.display("{color}§l\"{nickname}\" §7({gem})", "Sunny", "Fire", "§6"));
        assertEquals("\"Sunny\" (Solaris)", Nicknames.display("\"{nickname}\" ({gem})", "Sunny", "Solaris", "§6"), "the server's gem name");
        assertEquals(0, Nicknames.cooldownLeft(0, 1_000, 600_000), "never renamed");
        assertEquals(500_000, Nicknames.cooldownLeft(1_000, 101_000, 600_000));
        assertEquals(0, Nicknames.cooldownLeft(1_000, 700_000, 600_000));
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(c.getBoolean("nicknames.enabled"));
        assertEquals(16, c.getInt("nicknames.max-length"));
        assertEquals(10, c.getInt("nicknames.cooldown-minutes"));
        assertTrue(c.getBoolean("nicknames.mythic-nicknames"));
        assertEquals("{color}§l\"{nickname}\" §7({gem})", c.getString("nicknames.format"));
    }
}
