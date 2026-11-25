package dev.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Prophet's lines: in order, the 9th lets the gem stay, the 10th makes you glow, then round again. */
class ProphetTest {
    private static final long COOLDOWN = 2_000, WEEK = 7L * 86_400_000L, DAY = 86_400_000L;

    private static ProphetMath.Reply next(int count, long last, long now) {
        return ProphetMath.next(count, last, now, COOLDOWN, WEEK, 10, 9, 10);
    }

    @Test void tenLinesInOrderThenAgain() {
        int count = 0;
        long last = 0, now = 1_000_000;
        for (int want = 1; want <= 10; want++) {
            now += 3_000;
            ProphetMath.Reply r = next(count, last, now);
            assertEquals(want, r.line(), "try " + want);
            assertEquals(want == 9, r.keep(), "only the 9th (\"Fine.\") lets it stay");
            assertEquals(want == 10, r.glow(), "only the 10th makes you glow");
            count = r.count();
            last = now;
        }
        ProphetMath.Reply again = next(count, last, now + 3_000);
        assertEquals(1, again.line(), "after the 10th it starts again");
        assertFalse(again.keep());
    }

    @Test void atMostOneLineEveryTwoSeconds() {
        ProphetMath.Reply r = next(3, 1_000_000, 1_001_500);
        assertTrue(r.silent(), "inside the cooldown: no line");
        assertEquals(3, r.count(), "and the count doesn't move, so no line is skipped");
        assertFalse(r.keep(), "it's still pulled out");
        assertEquals(4, next(3, 1_000_000, 1_002_000).line(), "two seconds later: the next line");
    }

    @Test void backAfterAWeek() {
        long last = 1_000_000;
        ProphetMath.Reply r = next(4, last, last + 8 * DAY);
        assertTrue(r.welcomeBack(), "We have done this before.");
        assertEquals(4, r.count(), "the count is kept");
        assertFalse(r.keep());
        assertEquals(5, next(r.count(), last + 8 * DAY, last + 8 * DAY + 3_000).line(), "then carries on where they were");
        assertEquals(5, next(4, last, last + 6 * DAY).line(), "six days: no welcome-back");
        assertEquals(1, next(0, 0, last + 30 * DAY).line(), "a first try is never a welcome-back");
    }

    @Test void theConfigHasTheDialogue() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        List<String> lines = c.getStringList("prophet.lines");
        assertEquals(List.of("You are not to do that.", "I said no.", "This is not a test of patience. Yours or mine.",
                "The gem does not belong in a box. Neither, I suspect, do you.", "Do you believe I will grow tired before you do?",
                "I have watched empires fall with more grace than this.", "...", "...", "Fine.", "Did you think that was real?"), lines);
        assertEquals(9, c.getInt("prophet.keep-on"));
        assertEquals(10, c.getInt("prophet.glow-on"));
        assertEquals(30, c.getInt("prophet.glow-seconds"));
        assertEquals(2, c.getInt("prophet.line-cooldown-seconds"));
        assertEquals(7, c.getInt("prophet.return-after-days"));
        assertEquals("We have done this before.", c.getString("prophet.welcome-back"));
        assertEquals("", c.getString("prophet.glyph"), "the pack's lore_eye");
        assertEquals("BLOCK_BELL_RESONATE", c.getString("sounds.prophet.refuse.event"));
        assertEquals(0.5, c.getDouble("sounds.prophet.refuse.pitch"));
    }

    @Test void auratusAndHereticAreDroppableAndTheProphetsInContainers() throws Exception {
        String gl = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        int i = gl.indexOf("public void onChestStore(");
        assertTrue(gl.substring(i, Math.min(gl.length(), i + 1200)).contains("LooseMythics.droppable("), "the chest rule steps aside for the two mythics");
        String lm = Files.readString(Path.of("src/main/java/dev/soulbound/LooseMythics.java"));
        assertTrue(lm.contains("t == GemType.AURATUS || t == GemType.HERETIC"), "only those two: the Relay and the mace are unchanged");
        assertTrue(lm.contains("setUnlimitedLifetime(true)") && lm.contains("setGlowing(true)"));
    }
}
