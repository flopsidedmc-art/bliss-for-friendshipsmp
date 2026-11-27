package dev.soulbound;

/**
 * An animated camera overlay (OverlayAnimTest), shared by the Finale's haze and the Gold Gem repurpose: it fades in
 * through the pack's fainter frames textures/overlay/&lt;style&gt;_fade_1..N (1 the faintest; camera overlays have no
 * alpha), then loops &lt;style&gt;_0..frames-1, one frame every frame-ticks.
 */
final class OverlayAnim {
    private OverlayAnim() {}

    /** One loop frame: blissgems:overlay/&lt;style&gt;_&lt;frame&gt;. */
    static String texture(String style, int frame) { return "blissgems:overlay/" + style + "_" + frame; }

    /** One fade step: blissgems:overlay/&lt;style&gt;_fade_&lt;level&gt;. */
    static String fadeTexture(String style, int level) { return "blissgems:overlay/" + style + "_fade_" + level; }

    /**
     * What to show at {@code tick} (1 = the first), or null when nothing changes. {@code state}: [0] the loop frame now,
     * [1] the fade level shown (both start at 0). Fade in over {@code fadeIn} ticks through {@code steps} levels (none if
     * either is 0: the first frame at once), then every {@code frameTicks} the next frame, if {@code mayAdvance} (the
     * Finale holds its frame during a flash).
     */
    static String step(int tick, String style, int frames, int frameTicks, int fadeIn, int steps, boolean mayAdvance, int[] state) {
        int n = Math.max(1, frames);
        if (steps > 0 && fadeIn > 0 && tick <= fadeIn) {
            int level = FinaleMath.fadeInLevel(tick, fadeIn, steps);
            if (level == state[1]) return null;
            state[1] = level;
            return fadeTexture(style, level);
        }
        if ((steps <= 0 || fadeIn <= 0) && tick == 1) return texture(style, state[0] % n);
        if (tick % Math.max(1, frameTicks) == 0 && mayAdvance) {
            state[0] = (state[0] + 1) % n;
            return texture(style, state[0]);
        }
        return null;
    }
}
