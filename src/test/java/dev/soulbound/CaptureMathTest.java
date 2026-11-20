package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/** Capture points: /fill-style coordinates, the box and its size limit, floor tiles and the hold timer. */
class CaptureMathTest {
    @Test void coordinatesWorkLikeFill() {
        assertEquals(10, CaptureMath.coord("10", 99.7));
        assertEquals(-3, CaptureMath.coord("-3", 0));
        assertEquals(12, CaptureMath.coord("~", 12.7), "~ is your block");
        assertEquals(17, CaptureMath.coord("~5", 12.7));
        assertEquals(9, CaptureMath.coord("~-3", 12.7));
        assertEquals(-6, CaptureMath.coord("~", -5.2), "floored, so a negative position rounds down");
        assertEquals(14, CaptureMath.coord("~1.5", 12.7));
        for (String bad : new String[] {"", "abc", "1.5", "~x", "^", "^1", "~NaN", "~Infinity"})
            assertThrows(IllegalArgumentException.class, () -> CaptureMath.coord(bad, 0), bad);
    }

    @Test void cornersInAnyOrderMakeTheSameBox() {
        int[] a = CaptureMath.box(5, 70, -5, -5, 64, 5), b = CaptureMath.box(-5, 64, 5, 5, 70, -5);
        assertArrayEquals(new int[] {-5, 64, -5, 5, 70, 5}, a);
        assertArrayEquals(a, b);
        assertArrayEquals(new int[] {11, 7, 11}, CaptureMath.size(a), "both corners are included, like /fill");
    }

    @Test void sizeLimit() {
        assertTrue(CaptureMath.fits(CaptureMath.box(0, 0, 0, 63, 31, 63), 64, 32, 64));
        assertFalse(CaptureMath.fits(CaptureMath.box(0, 0, 0, 64, 31, 63), 64, 32, 64), "65 wide");
        assertFalse(CaptureMath.fits(CaptureMath.box(0, 0, 0, 63, 32, 63), 64, 32, 64), "33 tall");
        assertFalse(CaptureMath.fits(CaptureMath.box(0, 0, 0, 63, 31, 64), 64, 32, 64), "65 deep");
    }

    @Test void standingInside() {
        int[] b = CaptureMath.box(0, 64, 0, 4, 66, 4);
        assertTrue(CaptureMath.inside(b, 0, 64, 0));
        assertTrue(CaptureMath.inside(b, 4.99, 66.9, 4.99), "the far blocks count too");
        assertFalse(CaptureMath.inside(b, 5.0, 65, 2));
        assertFalse(CaptureMath.inside(b, 2, 63.9, 2));
        assertFalse(CaptureMath.inside(b, -0.01, 65, 2));
    }

    @Test void floorTilesCoverTheBoxWithinTheLimit() {
        for (int w = 1; w <= 64; w += 7) for (int d = 1; d <= 64; d += 5) {
            List<int[]> tiles = CaptureMath.tiles(w, d, 32);
            assertTrue(tiles.size() <= 32, w + "x" + d + " used " + tiles.size());
            int area = 0;
            boolean[][] seen = new boolean[w][d];
            for (int[] t : tiles) {
                for (int x = t[0]; x < t[0] + t[2]; x++) for (int z = t[1]; z < t[1] + t[3]; z++) {
                    assertFalse(seen[x][z], "tiles overlap at " + x + "," + z);
                    seen[x][z] = true;
                }
                area += t[2] * t[3];
            }
            assertEquals(w * d, area, w + "x" + d + " fully covered");
        }
        assertEquals(1, CaptureMath.tiles(1, 1, 32).size());
        assertEquals(16, CaptureMath.tiles(11, 11, 32).size(), "11x11: 3-block tiles");
    }

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
    private static final BiPredicate<UUID, UUID> NOBODY_TRUSTED = (x, y) -> false;
    private static final BiPredicate<UUID, UUID> A_AND_B = (x, y) -> Set.of(x, y).equals(Set.of(A, B));

    @Test void onePlayerGainsTime() {
        Map<UUID, Long> held = new HashMap<>();
        CaptureMath.Step s = CaptureMath.step(List.of(A), NOBODY_TRUSTED, held, 5, 600);
        assertEquals(CaptureMath.State.HOLDING, s.state());
        assertEquals(5L, held.get(A));
        assertNull(s.winner());
        assertEquals(CaptureMath.State.EMPTY, CaptureMath.step(List.of(), NOBODY_TRUSTED, held, 5, 600).state());
    }

    @Test void untrustedPlayersContestAndNobodyGains() {
        Map<UUID, Long> held = new HashMap<>(Map.of(A, 100L));
        CaptureMath.Step s = CaptureMath.step(List.of(A, B), NOBODY_TRUSTED, held, 5, 600);
        assertEquals(CaptureMath.State.CONTESTED, s.state());
        assertEquals(100L, held.get(A));
        assertNull(held.get(B));
        // a trusted pair plus a stranger is still contested
        assertEquals(CaptureMath.State.CONTESTED, CaptureMath.step(List.of(A, B, C), A_AND_B, held, 5, 600).state());
        assertEquals(100L, held.get(A));
    }

    @Test void trustedPlayersAreOneSideAndAllGain() {
        Map<UUID, Long> held = new HashMap<>(Map.of(A, 100L));
        CaptureMath.Step s = CaptureMath.step(List.of(A, B), A_AND_B, held, 5, 600);
        assertEquals(CaptureMath.State.HOLDING, s.state());
        assertEquals(105L, held.get(A));
        assertEquals(5L, held.get(B));
    }

    @Test void leavingKeepsYourTimeAndTheFirstToTheGoalWins() {
        Map<UUID, Long> held = new HashMap<>();
        for (int i = 0; i < 50; i++) CaptureMath.step(List.of(A), NOBODY_TRUSTED, held, 5, 600); // 250 ticks
        for (int i = 0; i < 10; i++) CaptureMath.step(List.of(), NOBODY_TRUSTED, held, 5, 600); // A left
        assertEquals(250L, held.get(A), "time is kept off the point");
        CaptureMath.Step s = null;
        for (int i = 0; i < 70 && (s == null || s.winner() == null); i++) s = CaptureMath.step(List.of(A), NOBODY_TRUSTED, held, 5, 600);
        assertEquals(A, s.winner());
        assertEquals(600L, held.get(A));
    }

    @Test void onSameStepTheMostTimeWins() {
        Map<UUID, Long> held = new HashMap<>(Map.of(A, 595L, B, 598L));
        assertEquals(B, CaptureMath.step(List.of(A, B), A_AND_B, held, 5, 600).winner());
    }

    @Test void coloursAndBossBarColours() {
        assertEquals(0xB0703A, CaptureMath.color("#B0703A"));
        assertEquals(0xB0703A, CaptureMath.color("b0703a"));
        assertEquals(0xFFAA00, CaptureMath.color("gold"));
        assertEquals(0xFF55FF, CaptureMath.color("LIGHT_PURPLE"));
        assertNull(CaptureMath.color("#12345"));
        assertNull(CaptureMath.color("brown"));
        assertEquals("#B0703A", CaptureMath.hex(0xB0703A));
        assertEquals("YELLOW", CaptureMath.barColor(0xFFAA00));
        assertEquals("RED", CaptureMath.barColor(0xFF5555));
        assertEquals("GREEN", CaptureMath.barColor(0x55FF55));
        assertEquals("BLUE", CaptureMath.barColor(0x55FFFF));
        assertEquals("PURPLE", CaptureMath.barColor(0xAA00AA));
        assertEquals("PURPLE", CaptureMath.barColor(0xFF55FF));
        assertEquals("PINK", CaptureMath.barColor(0xFF69B4));
        assertEquals("WHITE", CaptureMath.barColor(0xAAAAAA));
    }

    @Test void names() {
        assertTrue(CaptureMath.validName("week1_heretic"));
        assertTrue(CaptureMath.validName("Point-2"));
        assertFalse(CaptureMath.validName(""));
        assertFalse(CaptureMath.validName("two words"));
        assertFalse(CaptureMath.validName("x".repeat(33)));
    }
}
