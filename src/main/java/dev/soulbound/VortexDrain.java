package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Vitality Vortex's drain (life.vitality-vortex): the life is pulled out of the ground around you. Random patches of
 * natural blocks turn into dead ones (dead coral, andesite, tuff, gravel, coarse dirt, cobblestone), plants on them
 * wither into dead bushes or go, leaves vanish, spreading outward over a second with green-to-grey particles
 * flowing into you. Underground it's a sphere: floor, walls and ceiling. After restore-after-seconds it all grows back
 * outward over about 1.5 s, every spot exactly as it was unless a player changed it. LandWorks (shared with Crisp)
 * holds the ledger, saving, the natural-blocks filter, the off-limits places and the guards. Nothing that holds back
 * water or lava is touched, and nothing it places can fall or let a liquid through.
 */
final class VortexDrain {
    private final SoulboundGemsPlugin plugin;
    private final LandWorks works;
    private final Random rnd = new Random();
    private static final Particle.DustTransition LIFE = new Particle.DustTransition(Color.fromRGB(0x5F, 0xC1, 0x4A), Color.fromRGB(0x8A, 0x8A, 0x8A), 1.1f);
    private static final Particle.DustOptions REGROW = new Particle.DustOptions(Color.fromRGB(0x4C, 0xD1, 0x3C), 0.8f);

    VortexDrain(SoulboundGemsPlugin plugin, LandWorks works) {
        this.plugin = plugin;
        this.works = works;
    }

    private double cfgD(String k, double def) { return plugin.getConfig().getDouble("life.vitality-vortex." + k, def); }

    /** One planned change: a ground block (or, with no ground under it, just the plant on top). */
    private record Step(Block block, Material dead, Material was, double distance, boolean plantOnly) {}

    /** Drains the land around {@code p}; returns how many spots it takes (the buffs grow with it, as before). 0 when it's off. */
    int drain(Player p) {
        if (!plugin.getConfig().getBoolean("life.vitality-vortex.drain", true)) return 0;
        World w = p.getWorld();
        Location feet = p.getLocation();
        double radius = Math.max(1, Math.min(12, cfgD("drain-radius", 5)));
        boolean sphere = LandWorks.underground(p);
        long restoreAt = System.currentTimeMillis() + (long) (Math.max(1, cfgD("restore-after-seconds", 30)) * 1000);
        LandWorks.Job j = works.open("vortex", w, feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(), radius, sphere, false, restoreAt);
        int r = (int) Math.ceil(radius);

        List<Block> cells = new ArrayList<>();
        List<Boolean> plantOnly = new ArrayList<>();
        List<double[]> rel = new ArrayList<>();
        if (sphere) {
            int ox = j.fx, oy = j.fy + 1, oz = j.fz;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (!LandMath.inSphere(dx, dy, dz, radius)) continue;
                Block b = w.getBlockAt(ox + dx, oy + dy, oz + dz);
                if (!b.getType().isSolid() || !LandWorks.exposed(b) || LandWorks.holdsLiquid(b) || !works.candidate(b)) continue;
                cells.add(b);
                plantOnly.add(false);
                rel.add(new double[] {dx, dy, dz});
            }
        } else {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (!LandMath.inDisc(dx, dz, radius)) continue;
                Block floor = null;
                for (int y = j.fy + 3; y >= j.fy - r; y--) {
                    Block b = w.getBlockAt(j.fx + dx, y, j.fz + dz);
                    if (b.getType().isSolid()) { floor = b; break; }
                }
                if (floor == null || LandWorks.holdsLiquid(floor)) continue;
                Block above = floor.getRelative(BlockFace.UP);
                boolean plant = LandMath.lifePlant(above.getType().name()) && !works.owns(above) && !works.protectedSpot(above);
                if (works.candidate(floor) && (above.getType().isAir() || plant)) {
                    cells.add(floor);
                    plantOnly.add(false);
                } else if (plant) { // crops on farmland, a flower on a path ...: the plant alone withers
                    cells.add(above);
                    plantOnly.add(true);
                } else continue;
                rel.add(new double[] {dx, 0, dz});
            }
        }

        List<Material> kinds = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        ConfigurationSection bs = plugin.getConfig().getConfigurationSection("life.vitality-vortex.dead-blocks");
        if (bs != null) for (String k : bs.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && m.isBlock() && m.isSolid()) { kinds.add(m); weights.add(bs.getDouble(k)); }
        }
        if (kinds.isEmpty()) { kinds.add(Material.COARSE_DIRT); weights.add(1.0); }
        int[] pick = LandMath.patches(rel, cfgD("drain-chance", 0.45), radius, sphere, weights.stream().mapToDouble(Double::doubleValue).toArray(), rnd);
        List<Step> plan = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (pick[i] < 0) continue;
            double[] d = rel.get(i);
            plan.add(new Step(cells.get(i), kinds.get(pick[i]), cells.get(i).getType(), Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), plantOnly.get(i)));
        }
        plan.sort(Comparator.comparingDouble(Step::distance));
        works.save();

        // Spread outward over a second, the life flowing into you; then it waits, and grows back.
        List<Object[]> streams = new ArrayList<>(); // {location, tick it started}
        final int[] t = {0};
        final int[] next = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (t[0] <= 20) {
                double reach = radius * t[0] / 20.0;
                while (next[0] < plan.size() && (plan.get(next[0]).distance() <= reach + 0.01 || t[0] == 20)) {
                    Step s = plan.get(next[0]++);
                    if (wither(j, s) && rnd.nextInt(2) == 0) streams.add(new Object[] {s.block().getLocation().add(0.5, 1.0, 0.5), t[0]});
                }
                if (t[0] == 20) works.save();
            }
            if (t[0] <= 32 && p.isOnline() && p.getWorld().equals(w)) flow(p, streams, t[0]);
            if (t[0] > 32 && System.currentTimeMillis() >= j.restoreAt) {
                task[0].cancel();
                regrow(j);
            }
        }, 1L, 1L);
        return plan.size();
    }

    /** One spot loses its life: leaves vanish, ground goes dead, the plant on it withers. Only if it's still as the cast saw it. */
    private boolean wither(LandWorks.Job j, Step s) {
        Block b = s.block();
        if (b.getType() != s.was() || works.owns(b)) return false;
        if (s.plantOnly()) return witherPlant(j, b);
        Block above = b.getRelative(BlockFace.UP);
        if (LandMath.lifePlant(above.getType().name()) && !works.owns(above)) witherPlant(j, above);
        if (b.getType().name().endsWith("_LEAVES")) {
            works.record(j, LandLedger.Phase.LAND, b, Material.AIR);
            b.setType(Material.AIR, false);
            return true;
        }
        Material dead = s.dead();
        if (dead == Material.GRAVEL && !b.getRelative(BlockFace.DOWN).getType().isSolid()) dead = Material.COARSE_DIRT; // nothing that can fall
        works.record(j, LandLedger.Phase.LAND, b, dead);
        b.setType(dead, false);
        return true;
    }

    /** A plant withers: a dead bush or nothing; a two-block plant goes, both halves. */
    private boolean witherPlant(LandWorks.Job j, Block plant) {
        String m = plant.getType().name();
        if (LandMath.tallPlant(m) || plant.getBlockData() instanceof Bisected) {
            Block bottom = plant.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP ? plant.getRelative(BlockFace.DOWN) : plant;
            Block top = bottom.getRelative(BlockFace.UP);
            if (works.owns(bottom) || works.owns(top)) return false;
            works.record(j, LandLedger.Phase.PLANT, top, Material.AIR);
            top.setType(Material.AIR, false);
            works.record(j, LandLedger.Phase.PLANT, bottom, Material.AIR);
            bottom.setType(Material.AIR, false);
            return true;
        }
        Material to = rnd.nextBoolean() ? Material.DEAD_BUSH : Material.AIR;
        works.record(j, LandLedger.Phase.PLANT, plant, to);
        plant.setType(to, false);
        return true;
    }

    /** Green-to-grey motes flowing from the drained spots into you over half a second each. */
    private void flow(Player p, List<Object[]> streams, int now) {
        Location chest = p.getLocation().add(0, 1.1, 0);
        for (Object[] s : streams) {
            int age = now - (int) s[1];
            if (age < 0 || age > 10) continue;
            Location from = (Location) s[0];
            double f = age / 10.0;
            Location at = from.clone().add(chest.toVector().subtract(from.toVector()).multiply(f));
            p.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0.05, 0.05, 0.05, 0, LIFE);
        }
    }

    /** It grows back: outward over about 1.5 s, green sparkles popping up as each spot regrows, with a soft sound. */
    private void regrow(LandWorks.Job j) {
        List<LandLedger.Entry> order = new ArrayList<>(j.ledger.entries());
        order.sort(Comparator.comparingDouble(e -> {
            double dx = e.x() - j.fx, dy = e.y() - j.fy, dz = e.z() - j.fz;
            return dx * dx + dy * dy + dz * dz;
        }));
        World w = j.world;
        Location centre = new Location(w, j.fx + 0.5, j.fy, j.fz + 0.5);
        GemSounds.play(plugin, "life.regrow", centre);
        int per = Math.max(1, (int) Math.ceil(order.size() / 30.0));
        final int[] at = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int k = 0; k < per && at[0] < order.size(); k++, at[0]++) {
                LandLedger.Entry e = order.get(at[0]);
                if (!works.restoreOne(j, e)) continue; // broken or replaced since: left as it is now
                if (at[0] % 2 == 0) {
                    Location top = new Location(w, e.x() + 0.5, e.y() + 1.0, e.z() + 0.5);
                    w.spawnParticle(Particle.HAPPY_VILLAGER, top, 2, 0.25, 0.15, 0.25, 0);
                    w.spawnParticle(Particle.DUST, top, 2, 0.2, 0.1, 0.2, 0, REGROW);
                }
            }
            if (at[0] == order.size() / 2) GemSounds.play(plugin, "life.regrow", centre);
            if (at[0] >= order.size()) {
                task[0].cancel();
                works.close(j);
            }
        }, 1L, 1L);
    }
}
