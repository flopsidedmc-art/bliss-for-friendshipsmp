package dev.soulbound;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-maths parts of Rift only: who counts as walking into the tear, and where the landing search looks. */
class RelayRiftTest {
    private static final double W = 2.5, H = 6.0, DEPTH = 0.8;

    @Test void insideTheTearFromEitherSideAndAlongItsWidth() {
        // Tear facing south (+Z): its opening runs east-west.
        assertTrue(RelayRift.inside(0, 0, 0, 0, 1, W, H, DEPTH), "standing at its foot");
        assertTrue(RelayRift.inside(1.2, 1, 0.5, 0, 1, W, H, DEPTH), "near the east edge, just in front");
        assertTrue(RelayRift.inside(-1.2, 3, -0.5, 0, 1, W, H, DEPTH), "near the west edge, just behind");
        assertFalse(RelayRift.inside(1.4, 0, 0, 0, 1, W, H, DEPTH), "past the edge of the opening");
        assertFalse(RelayRift.inside(0, 0, 1.2, 0, 1, W, H, DEPTH), "a step in front is not inside");
        assertFalse(RelayRift.inside(0, 6.5, 0, 0, 1, W, H, DEPTH), "above the top");
        assertFalse(RelayRift.inside(0, -1.5, 0, 0, 1, W, H, DEPTH), "well below the foot");
    }

    @Test void theOpeningTurnsWithTheTear() {
        // Facing east (+X): the opening now runs north-south.
        assertTrue(RelayRift.inside(0, 0, 1.2, 1, 0, W, H, DEPTH));
        assertFalse(RelayRift.inside(1.2, 0, 0, 1, 0, W, H, DEPTH));
        // Facing north-east (+X, -Z): the opening runs north-west to south-east.
        double f = Math.sqrt(0.5);
        assertTrue(RelayRift.inside(0.8, 0, 0.8, f, -f, W, H, DEPTH), "on the opening, towards the south-east end");
        assertFalse(RelayRift.inside(-0.8, 0, 0.8, f, -f, W, H, DEPTH), "behind it, along its facing");
        assertFalse(RelayRift.inside(0.8, 0, 0.8, f, f, W, H, DEPTH), "in front of a tear facing south-east");
    }

    @Test void aClosedTearCatchesNobody() {
        assertFalse(RelayRift.inside(0.1, 1, 0, 0, 1, 0, H, DEPTH));
    }

    @Test void landingSearchStartsAtTheAnchorAndStaysInRange() {
        List<int[]> offsets = RelayRift.searchOffsets(5);
        assertArrayEquals(new int[]{0, 0, 0}, offsets.get(0), "the anchor itself is tried first");
        Set<String> seen = new HashSet<>();
        double last = -1;
        for (int[] o : offsets) {
            assertTrue(o[0] * o[0] + o[1] * o[1] + o[2] * o[2] <= 25, "within 5 blocks");
            assertTrue(seen.add(o[0] + "," + o[1] + "," + o[2]), "no spot twice");
            double key = o[0] * o[0] + o[2] * o[2] + 1.2 * o[1] * o[1];
            assertTrue(key >= last - 1e-9, "nearest first");
            last = key;
        }
        assertEquals(515, offsets.size(), "every block in the sphere");
        int up = indexOf(offsets, 0, 1, 0), down = indexOf(offsets, 0, -1, 0);
        assertTrue(up < down, "one block up is tried before one block down");
        assertTrue(indexOf(offsets, 1, 0, 0) < up, "a step sideways before a step up");
    }

    @Test void searchRadiusZeroIsJustTheAnchor() {
        assertEquals(1, RelayRift.searchOffsets(0).size());
    }

    @Test void glowColoursParseOrFallBack() {
        assertEquals(Color.fromRGB(0xB0703A), RelayRift.parseColor("#B0703A", Color.WHITE));
        assertEquals(Color.fromRGB(0x2C180C), RelayRift.parseColor("2c180c", Color.WHITE));
        assertEquals(Color.WHITE, RelayRift.parseColor("brown", Color.WHITE));
        assertEquals(Color.WHITE, RelayRift.parseColor(null, Color.WHITE));
    }

    private static int indexOf(List<int[]> offsets, int x, int y, int z) {
        for (int i = 0; i < offsets.size(); i++) {
            int[] o = offsets.get(i);
            if (o[0] == x && o[1] == y && o[2] == z) return i;
        }
        return -1;
    }
}
