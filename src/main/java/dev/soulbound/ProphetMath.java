package dev.soulbound;

/**
 * The Prophet's escalation, with no server (ProphetTest). Each player has a count (the last line he said them, 0 for
 * none yet) and when he said it. Each try at putting Auratus or Heretic in a container gets the next line; after the
 * last line the count starts again at 1. Tries inside the cooldown get no line and don't move the count (the gem is
 * still pulled out), so the lines always come in order. Back after a long time: "We have done this before." in place
 * of that try's line, and the count carries on from where it was.
 */
final class ProphetMath {
    private ProphetMath() {}

    /**
     * What happens to one try. {@code line}: the line to say (1-based), 0 for the welcome-back line, -1 for nothing
     * (inside the cooldown). {@code keep}: the gem stays in the container this time. {@code glow}: the player glows.
     * {@code count}: the count to save.
     */
    record Reply(int line, boolean keep, boolean glow, int count) {
        boolean silent() { return line < 0; }
        boolean welcomeBack() { return line == 0; }
    }

    static Reply next(int count, long lastAt, long now, long cooldownMs, long returnAfterMs, int lines, int keepOn, int glowOn) {
        int n = Math.max(1, lines);
        int c = Math.max(0, Math.min(count, n));
        if (lastAt > 0 && now - lastAt < cooldownMs) return new Reply(-1, false, false, c);
        if (c > 0 && lastAt > 0 && now - lastAt > returnAfterMs) return new Reply(0, false, false, c);
        int line = c % n + 1;
        return new Reply(line, line == keepOn, line == glowOn, line);
    }
}
