package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The action bar: icons, "Ready!", timers and "\u2026", but no ability or gem names. */
class HudStatusTest {
    private static final String ICON = "\u22B4";

    private static String plain(String s) { return s.replaceAll("§.", ""); }

    @Test void readySaysReady() {
        assertEquals("§f" + ICON + " §aReady!", HudStatus.entry(ICON, 0, false));
        assertEquals("§f" + ICON + " §aReady!", HudStatus.charges(ICON, 2, 2, 0));
        assertEquals("§f" + ICON + " §aReady!", HudStatus.charges(ICON, 1, 2, 3_000), "one charge left is still ready to use");
    }

    @Test void rechargingDimsTheIconAndShowsATimer() {
        assertEquals("§7" + ICON + " §b8s", HudStatus.entry(ICON, 7_200, false));
        assertEquals("§7" + ICON + " §b5s", HudStatus.charges(ICON, 0, 2, 5_000));
    }

    @Test void activeIsAnEllipsis() {
        assertEquals("§f" + ICON + " §e\u2026", HudStatus.entry(ICON, 9_000, true));
    }

    @Test void theOnlyWordIsReady() {
        for (String s : new String[]{HudStatus.entry(ICON, 0, false), HudStatus.entry(ICON, 65_000, false), HudStatus.entry(ICON, 0, true),
                HudStatus.charges(ICON, 0, 3, 4_000), HudStatus.charges(ICON, 1, 3, 4_000)})
            assertFalse(plain(s).replace("Ready!", "").matches(".*[A-Za-z]{2,}.*"), s);
        assertEquals("1m 5s", HudStatus.time(65_000));
        assertEquals("2m", HudStatus.time(120_000));
        assertEquals("1h 1m", HudStatus.time(3_660_000));
    }
}
