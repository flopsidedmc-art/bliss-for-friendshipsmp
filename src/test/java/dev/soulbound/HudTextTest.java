package dev.soulbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Widths used to place the Rift slot beside the hotbar. Values are vanilla font advances in GUI pixels. */
class HudTextTest {
    @Test void vanillaLetterWidths() {
        assertEquals(30, HudText.width("Ready"));
        assertEquals(2, HudText.width("i"));
        assertEquals(3, HudText.width("l"));
        assertEquals(4, HudText.width("t"));
        assertEquals(4, HudText.width(" "));
        assertEquals(6, HudText.width("/"));
        assertEquals(8, HudText.width("…"));
    }

    @Test void colourCodesTakeNoSpaceAndBoldAddsOnePixel() {
        assertEquals(30, HudText.width("§aReady"));
        assertEquals(30, HudText.width("§x§B§8§9§3§5§8Ready"));
        assertEquals(35, HudText.width("§lReady"));
        assertEquals(30, HudText.width("§l§aReady"), "a colour code resets bold, as in vanilla");
    }

    @Test void relayIcons() {
        assertEquals(34 + 4 + 18, HudText.width("§f §b2/2"), "Rewind icon, space, 2/2");
        assertEquals(26, HudText.width(""));
        assertEquals(26, HudText.width(""));
        assertEquals(34, HudText.width(""));
    }

    @Test void shiftsMoveExactlyTheRequestedDistance() {
        for (int n = -511; n <= 511; n++) assertEquals(n, HudText.shiftWidth(HudText.shift(n)), "shift " + n);
        assertEquals("", HudText.shift(0));
        assertEquals(1, HudText.shift(256).length());
        assertEquals(9, HudText.shift(511).length(), "at most one character per power of two");
    }
}
