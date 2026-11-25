package dev.soulbound;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Heretic's Spore field, drawn as a blood fountain at its centre for as long as the field lasts (visual only: the
 * field itself is MythicListener's). A column of dark red dust rises from the ground to pillar-height (or a ceiling).
 * About once a second it surges brighter and thicker with a heartbeat. At the top, a canopy spreads outward in rings
 * whose height ripples in a sine wave rolling outward, and blood rains from it across the field. Dust doesn't move by
 * itself, so the column flows by being drawn a little higher each tick while the older particles linger, which also
 * makes it read as one solid column. At most particles-per-tick a tick, sent to players within 64 blocks; nothing is
 * drawn after the tick the field ends.
 */
final class SporeFountain {
    static final Color DARK = Color.fromRGB(0x7A, 0x00, 0x10), BLOOD = Color.fromRGB(0xB0, 0x10, 0x1C),
            PULSE = Color.fromRGB(0xD0, 0x1A, 0x28), DRIP = Color.fromRGB(0x5A, 0x00, 0x0C);
    static final int SURGE_TICKS = 4;
    private static BlockData redstone; // made on first use (it needs the server)

    private final SoulboundGemsPlugin plugin;
    private final World w;
    private final double x, z, baseY, topY, canopyR, fieldR;
    private final boolean capped, rain;
    private final int perTick, period;
    private final long endAt;
    private BukkitTask task;
    private int t;

    // ------------------------------------------------------------------ the shape (no server: SporeFountainTest)

    /** The column's top: pillar-height (kept within 15-20) above the ground, or just under a ceiling below that. */
    static double top(double baseY, double height, Double ceilingY) {
        double h = Math.max(15, Math.min(20, height));
        double open = baseY + h;
        return ceilingY != null && ceilingY - 0.2 < open ? Math.max(baseY + 0.5, ceilingY - 0.2) : open;
    }

    /** The column surges for a few ticks at the start of every period. */
    static boolean surging(int tick, int periodTicks) { return Math.floorMod(tick, Math.max(SURGE_TICKS + 1, periodTicks)) < SURGE_TICKS; }

    /**
     * Particles this tick for {pillar, canopy, rain}, never more than {@code perTick} together. Settled: about 70% of
     * it; surging: all of it, most of the extra going to the column. No rain: its share isn't used.
     */
    static int[] budget(int perTick, boolean surge, boolean rain) {
        int n = Math.max(0, perTick);
        int pillar = (int) (n * (surge ? 0.55 : 0.35));
        int canopy = (int) (n * (surge ? 0.25 : 0.2));
        int drops = rain ? (int) (n * (surge ? 0.2 : 0.15)) : 0;
        return new int[] {pillar, canopy, drops};
    }

    /**
     * The canopy's height above the column's top at {@code r} blocks out, at tick {@code tick}: a spray that arcs up
     * and falls away towards the edge, with a ripple rolling outward (crests move out as time goes on). Under a ceiling
     * it can't rise: it spreads just under it, rippling downward.
     */
    static double canopyHeight(double r, double radius, int tick, boolean ceiling) {
        double f = Math.min(1, Math.max(0, r / Math.max(0.1, radius)));
        double ripple = Math.sin(r * 2.4 - tick * 0.35);
        if (ceiling) return -0.25 - 0.3 * (0.5 + 0.5 * ripple) * f;
        return 1.4 * (1 - f * f) - 0.6 * f + 0.35 * ripple;
    }

    /** A dust size within 1.4-2.0 (a little bigger when surging). */
    static float size(double rnd, boolean surge) { return (float) (1.4 + 0.6 * rnd + (surge ? 0.2 : 0)); }

    // ------------------------------------------------------------------ drawing

    private SporeFountain(SoulboundGemsPlugin plugin, Location centre, double fieldRadius, long endAt) {
        this.plugin = plugin;
        this.w = centre.getWorld();
        var c = plugin.getConfig();
        String v = "mythics.heretic.spore.visuals.";
        this.x = centre.getBlockX() + 0.5;
        this.z = centre.getBlockZ() + 0.5;
        this.baseY = ground(centre);
        Double ceiling = ceiling(baseY, Math.max(15, Math.min(20, c.getDouble(v + "pillar-height", 18.0))));
        this.capped = ceiling != null;
        this.topY = top(baseY, c.getDouble(v + "pillar-height", 18.0), ceiling);
        this.canopyR = Math.max(1, c.getDouble(v + "canopy-radius", 5.0));
        this.fieldR = Math.max(0.5, fieldRadius);
        this.rain = c.getBoolean(v + "rain", true);
        this.perTick = Math.max(10, Math.min(400, c.getInt(v + "particles-per-tick", 100)));
        this.period = Math.max(SURGE_TICKS + 1, (int) Math.round(c.getDouble(v + "pulse-seconds", 1.0) * 20));
        this.endAt = endAt;
    }

    /** Starts one at the field's centre; it stops itself the tick the field ends. */
    static SporeFountain start(SoulboundGemsPlugin plugin, Location centre, double fieldRadius, long endAt) {
        if (centre.getWorld() == null) return null;
        SporeFountain f = new SporeFountain(plugin, centre, fieldRadius, endAt);
        f.task = Bukkit.getScheduler().runTaskTimer(plugin, f::tick, 0L, 1L);
        return f;
    }

    /** The ground under the centre (the field can start in the air, where a saw hit someone). */
    private double ground(Location at) {
        int bx = at.getBlockX(), bz = at.getBlockZ(), y = at.getBlockY();
        for (int i = 0; i <= 12 && y - i > w.getMinHeight(); i++) {
            if (w.getBlockAt(bx, y - i, bz).getType().isSolid()) return y - i + 1;
        }
        return at.getY();
    }

    /** The first solid block above the ground within the column's height, or null. */
    private Double ceiling(double base, double height) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) Math.floor(base) + 1; y <= base + height && y < w.getMaxHeight(); y++) {
            if (w.getBlockAt(bx, y, bz).getType().isSolid()) return (double) y;
        }
        return null;
    }

    private void tick() {
        if (System.currentTimeMillis() >= endAt || !w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
            task.cancel();
            return;
        }
        Location base = new Location(w, x, baseY, z);
        List<Player> viewers = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(base) <= 64 * 64) viewers.add(p);
        boolean surge = surging(t, period);
        if (Math.floorMod(t, period) == 0) GemSounds.play(plugin, "heretic.spore-pulse", base);
        if (!viewers.isEmpty()) {
            int[] n = budget(perTick, surge, rain);
            ParticleBuilder dust = new ParticleBuilder(Particle.DUST).receivers(viewers).force(true).count(1).offset(0, 0, 0).extra(0);
            pillar(dust, n[0], surge);
            canopy(dust, n[1]);
            if (rain) rain(dust, n[2], viewers);
        }
        t++;
    }

    private void pillar(ParticleBuilder dust, int count, boolean surge) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double height = topY - baseY, scroll = (t * 0.045) % 1.0, thick = surge ? 0.42 : 0.28;
        for (int i = 0; i < count; i++) {
            double h = (((i + 0.5) / count) + scroll) % 1.0 * height;
            double a = r.nextDouble(Math.PI * 2), d = thick * Math.sqrt(r.nextDouble());
            Color c = surge && i % 2 == 0 ? PULSE : i % 3 == 0 ? BLOOD : DARK;
            dust.location(w, x + Math.cos(a) * d, baseY + h, z + Math.sin(a) * d)
                    .data(new Particle.DustOptions(c, size(r.nextDouble(), surge))).spawn();
        }
    }

    private void canopy(ParticleBuilder dust, int count) {
        if (count <= 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int rings = 5;
        double total = 0;
        for (int k = 1; k <= rings; k++) total += k;
        double spin = t * 0.03;
        for (int k = 1; k <= rings; k++) {
            double rr = canopyR * k / rings;
            int pts = Math.max(1, (int) Math.round(count * k / total)); // more points on the wider rings
            for (int j = 0; j < pts; j++) {
                double a = spin + (j + r.nextDouble(0.4)) * (Math.PI * 2 / pts);
                double y = topY + canopyHeight(rr, canopyR, t, capped);
                dust.location(w, x + Math.cos(a) * rr, y, z + Math.sin(a) * rr)
                        .data(new Particle.DustOptions(j % 2 == 0 ? BLOOD : DARK, (float) (1.2 + 0.4 * r.nextDouble()))).spawn();
            }
        }
    }

    /** Drips from the canopy down to the ground across the field: falling redstone dust, and a few small dark drops. */
    private void rain(ParticleBuilder dust, int count, List<Player> viewers) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (redstone == null) redstone = Material.REDSTONE_BLOCK.createBlockData();
        ParticleBuilder fall = new ParticleBuilder(Particle.FALLING_DUST).receivers(viewers).force(true).count(1).offset(0, 0, 0).extra(0).data(redstone);
        double reach = Math.min(fieldR, canopyR);
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble(Math.PI * 2), d = reach * Math.sqrt(r.nextDouble());
            // half start at the canopy; the rest are already on the way down, so the whole height rains
            double y = i % 2 == 0 ? topY + canopyHeight(d, canopyR, t, capped) : baseY + 0.3 + r.nextDouble() * (topY - baseY - 0.3);
            Location at = new Location(w, x + Math.cos(a) * d, y, z + Math.sin(a) * d);
            if (i % 4 == 3) dust.location(at).data(new Particle.DustOptions(DRIP, 0.6f)).spawn();
            else fall.location(at).spawn();
        }
    }
}
