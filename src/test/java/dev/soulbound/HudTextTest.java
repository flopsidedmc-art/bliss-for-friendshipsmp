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
        assertEquals(8, HudText.width("\u2026"));
    }

    @Test void colourCodesTakeNoSpaceAndBoldAddsOnePixel() {
        assertEquals(30, HudText.width("§aReady"));
        assertEquals(30, HudText.width("§x§B§8§9§3§5§8Ready"));
        assertEquals(35, HudText.width("§lReady"));
        assertEquals(30, HudText.width("§l§aReady"), "a colour code resets bold, as in vanilla");
    }

    @Test void relayIcons() {
        assertEquals(34 + 4 + 18, HudText.width("§f\uE040 §b2/2"), "Rewind icon, space, 2/2");
        assertEquals(26, HudText.width("\uE041"));
        assertEquals(26, HudText.width("\uE043"));
        assertEquals(26, HudText.width("\uE044"), "Divinity");
        assertEquals(34, HudText.width("\uE05A"));
    }

    @Test void shiftsMoveExactlyTheRequestedDistance() {
        for (int n = -511; n <= 511; n++) assertEquals(n, HudText.shiftWidth(HudText.shift(n)), "shift " + n);
        assertEquals("", HudText.shift(0));
        assertEquals(1, HudText.shift(256).length());
        assertEquals(9, HudText.shift(511).length(), "at most one character per power of two");
    }
}
