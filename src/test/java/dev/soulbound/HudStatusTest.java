package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The word-free action bar: no "Ready", "Active" or gem names, just icons, timers and "\u2026". */
class HudStatusTest {
    private static final String ICON = "\u22B4";

    private static String plain(String s) { return s.replaceAll("§.", ""); }

    @Test void readyIsJustTheIcon() {
        assertEquals("§f" + ICON, HudStatus.entry(ICON, 0, false));
        assertEquals("§f" + ICON, HudStatus.charges(ICON, 2, 2, 0));
    }

    @Test void recharginDimsTheIconAndShowsATimer() {
        assertEquals("§7" + ICON + " §b8s", HudStatus.entry(ICON, 7_200, false));
        assertEquals("§7" + ICON + " §b5s", HudStatus.charges(ICON, 0, 2, 5_000));
        assertEquals("§f" + ICON + " §33s", HudStatus.charges(ICON, 1, 2, 3_000), "one left: still bright, refill timer softer");
    }

    @Test void activeIsAnEllipsis() {
        assertEquals("§f" + ICON + " §e\u2026", HudStatus.entry(ICON, 9_000, true));
    }

    @Test void noWordsAnywhere() {
        for (String s : new String[]{HudStatus.entry(ICON, 0, false), HudStatus.entry(ICON, 65_000, false), HudStatus.entry(ICON, 0, true),
                HudStatus.charges(ICON, 0, 3, 4_000), HudStatus.charges(ICON, 1, 3, 4_000)})
            assertFalse(plain(s).matches(".*[A-Za-z]{2,}.*"), s);
        assertEquals("1m 5s", HudStatus.time(65_000));
        assertEquals("2m", HudStatus.time(120_000));
        assertEquals("1h 1m", HudStatus.time(3_660_000));
    }
}
