package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Trader never hands out a mythic and never trades one away. */
class TraderTest {
    @Test void poolIsEveryOtherStandardGem() {
        for (GemType cur : GemType.values()) {
            if (cur.isMythic()) continue;
            List<GemType> pool = GemType.tradePool(cur);
            assertFalse(pool.contains(cur), cur + " can't trade into itself");
            for (GemType g : pool) assertFalse(g.isMythic(), cur + " could trade into the mythic " + g);
            for (GemType g : GemType.values())
                if (!g.isMythic() && g != cur) assertTrue(pool.contains(g), cur + " should be able to trade into " + g);
        }
    }

    @Test void mythicsCantBeTraded() {
        for (GemType g : GemType.values()) if (g.isMythic()) assertTrue(GemType.tradePool(g).isEmpty(), g + " has a trade pool");
        assertTrue(GemType.tradePool(null).isEmpty());
        assertTrue(GemType.HERETIC.isMythic() && GemType.AURATUS.isMythic() && GemType.RELAY.isMythic());
    }

    /** The fix lives in GemListener's case "trader": it must refuse mythics and take its pool from tradePool. */
    @Test void traderCodeKeepsTheMythicChecks() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        int start = src.indexOf("case \"trader\" -> {");
        assertTrue(start >= 0, "GemListener has no case \"trader\"");
        int end = src.indexOf("case \"", start + 1);
        String block = src.substring(start, end < 0 ? src.length() : end);
        assertTrue(block.contains("isMythic()"), "the Trader must refuse a mythic gem");
        assertTrue(block.contains("GemType.tradePool("), "the Trader's pool must come from GemType.tradePool");
        assertFalse(block.contains("GemType.values()"), "the Trader's pool must not be built from GemType.values()");
        int refuse = block.indexOf("isMythic()"), consume = block.indexOf("consumeOne(");
        assertTrue(consume < 0 || refuse < consume, "check for a mythic before the Trader is used up");
    }
}
