package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Small-caps gem lore: letters change, colour codes, symbols, digits and item-history lines do not. */
class SmallCapsTest {
    @Test void everyLetterBecomesASmallCapital() {
        assertEquals("ᴛᴜɴᴇᴅ ɪɴ", SmallCaps.of("Tuned In"));
        assertEquals("ʀᴇʟᴀʏ ɢᴇᴍ", SmallCaps.of("RELAY GEM"));
        assertEquals("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ", SmallCaps.of("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test void colourCodesSymbolsAndDigitsAreKept() {
        assertEquals("§7— §x§B§8§9§3§5§8⟲ ʀᴇᴡɪɴᴅ §8[ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ɢᴇᴍ]", SmallCaps.of("§7— §x§B§8§9§3§5§8⟲ REWIND §8[Left-click gem]"));
        assertEquals("§8ᴛɪᴇʀ 2 • ᴇɴᴇʀɢʏ 5/10", SmallCaps.of("§8Tier 2 • Energy 5/10"));
        assertEquals("§l§c", SmallCaps.of("§l§c"), "codes at the end of a line");
    }

    @Test void convertingTwiceChangesNothing() {
        String once = SmallCaps.of("§dTEMPORAL DIVINITY §8[Sneak + right-click gem]");
        assertEquals(once, SmallCaps.of(once));
    }

    @Test void plainReadsSmallCapsBackAsCapitals() {
        assertTrue(SmallCaps.plain(SmallCaps.of("§dTEMPORAL DIVINITY")).contains("TEMPORAL DIVINITY"));
        assertTrue(SmallCaps.plain(SmallCaps.of(GemManager.RIFT_LORE)).contains("✦ RIFT"));
        assertTrue(SmallCaps.plain("§dTEMPORAL DIVINITY").contains("TEMPORAL DIVINITY"), "unconverted lore matches too");
    }

    @Test void itemHistoryLinesAreLeftAlone() {
        List<String> lore = List.of("§7— Quickened", "§cSTOLEN §7by Steve", "§eGIFTED §7by Alex", "§9STORED §7by notch_2");
        List<String> out = SmallCaps.lore(lore);
        assertEquals("§7— ǫᴜɪᴄᴋᴇɴᴇᴅ", out.get(0));
        assertEquals(lore.subList(1, 4), out.subList(1, 4));
        assertTrue(SmallCaps.isHistoryLine("§cSTOLEN §7by Steve"));
        assertFalse(SmallCaps.isHistoryLine("§7— Stolen goods"));
    }
}
