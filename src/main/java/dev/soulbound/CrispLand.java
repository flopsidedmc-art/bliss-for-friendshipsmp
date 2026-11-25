package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Crisp's land effect (fire.crisp). Around you, random patches of natural ground turn into Nether blocks, spreading
 * outward over a second; some new netherrack catches fire and now and then a small lava pit opens in the floor.
 * Underground (no sky, a ceiling within 6 blocks) it's a sphere: floor, walls and ceiling, with lava dripping from
 * the ceiling. Water in the area steams away and stays away. After restore-after-seconds it all cools back with
 * steam, every spot exactly as it was unless a player has changed it since. The ledger, saving, the natural-blocks
 * filter, the places it keeps out of and the guards are LandWorks', shared with Vitality Vortex's drain.
 */
final class CrispLand {
    private final SoulboundGemsPlugin plugin;
    private final LandWorks works;
    private final Map<UUID, Cast> casts = new HashMap<>();
    private final Random rnd = new Random();

    private static final class Cast {
        LandWorks.Job job;
        final List<int[]> ceiling = new ArrayList<>();
        final List<Block> floors = new ArrayList<>();
        BukkitTask task;
        long lastHiss;
    }

    CrispLand(SoulboundGemsPlugin plugin, LandWorks works) {
        this.plugin = plugin;
        this.works = works;
    }

    private double cfgD(String k, double def) { return plugin.getConfig().getDouble("fire.crisp." + k, def); }

    private static boolean watery(Block b) {
        return LandWorks.wateryBlock(b) || LandWorks.waterlogged(b) || LandMath.waterPlant(b.getType().name());
    }

    // ------------------------------------------------------------------ the cast

    /** Crisp's land effect around {@code p} (fire.crisp.land). */
    void cast(Player p) {
        if (!plugin.getConfig().getBoolean("fire.crisp.land", true)) return;
        World w = p.getWorld();
        Location feet = p.getLocation();
        double radius = Math.max(1, Math.min(12, cfgD("radius", 5)));
        boolean sphere = LandWorks.underground(p);
        long restoreAt = System.currentTimeMillis() + (long) (Math.max(1, cfgD("restore-after-seconds", 30)) * 1000);
        Cast c = new Cast();
        c.job = works.open("crisp", w, feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(), radius, sphere, true, restoreAt);
        LandWorks.Job j = c.job;
        int r = (int) Math.ceil(radius);

        // The domain: the floor area (a disc of floor blocks, and everything from r below to 3 above for water), or a sphere.
        List<Block> water = new ArrayList<>(), land = new ArrayList<>();
        List<double[]> rel = new ArrayList<>();
        if (sphere) {
            int ox = j.fx, oy = j.fy + 1, oz = j.fz;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (!LandMath.inSphere(dx, dy, dz, radius)) continue;
                Block b = w.getBlockAt(ox + dx, oy + dy, oz + dz);
                if (watery(b)) { water.add(b); continue; }
                if (!b.getType().isSolid() || !LandWorks.exposed(b) || !works.candidate(b)) continue;
                land.add(b);
                rel.add(new double[] {dx, dy, dz});
                if (dy < 0 && b.getRelative(BlockFace.UP).getType().isAir()) c.floors.add(b);
                if (dy > 0 && b.getRelative(BlockFace.DOWN).getType().isAir()) c.ceiling.add(new int[] {b.getX(), b.getY(), b.getZ()});
            }
        } else {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (!LandMath.inDisc(dx, dz, radius)) continue;
                Block floor = null;
                for (int y = j.fy + 3; y >= j.fy - r; y--) {
                    Block b = w.getBlockAt(j.fx + dx, y, j.fz + dz);
                    if (watery(b)) water.add(b);
                    if (floor == null && b.getType().isSolid() && !LandWorks.wateryBlock(b)) floor = b;
                }
                if (floor == null || !works.candidate(floor)) continue;
                Block above = floor.getRelative(BlockFace.UP);
                String a = above.getType().name();
                if (!above.getType().isAir() && !LandMath.plant(a) && !LandWorks.wateryBlock(above) && !LandMath.waterPlant(a)) continue; // a double plant, a torch, a slab ...
                land.add(floor);
                rel.add(new double[] {dx, 0, dz});
                c.floors.add(floor);
            }
        }

        // Evaporation: at once, with steam.
        if (plugin.getConfig().getBoolean("fire.crisp.evaporate", true)) evaporate(c, water);

        // The patches, and what each one becomes.
        List<Material> kinds = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        ConfigurationSection bs = plugin.getConfig().getConfigurationSection("fire.crisp.blocks");
        if (bs != null) for (String k : bs.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && m.isBlock() && m.isSolid()) { kinds.add(m); weights.add(bs.getDouble(k)); }
        }
        if (kinds.isEmpty()) { kinds.add(Material.NETHERRACK); weights.add(1.0); }
        double[] wArr = weights.stream().mapToDouble(Double::doubleValue).toArray();
        int[] pick = LandMath.patches(rel, cfgD("chance", 0.45), radius, sphere, wArr, rnd);
        List<Object[]> plan = new ArrayList<>(); // {block, material, original material, distance}
        for (int i = 0; i < land.size(); i++) {
            if (pick[i] < 0) continue;
            double[] d = rel.get(i);
            plan.add(new Object[] {land.get(i), kinds.get(pick[i]), land.get(i).getType(), Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])});
        }
        plan.sort(Comparator.comparingDouble(o -> (double) o[3]));
        casts.put(j.id, c);
        works.save();

        // Spread outward over a second; then fire and the pits; then it lasts, and cools.
        final int[] t = {0};
        final int[] next = {0};
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (t[0] <= 20) {
                double reach = radius * t[0] / 20.0;
                while (next[0] < plan.size() && (double) plan.get(next[0])[3] <= reach + 0.01) convert(c, plan.get(next[0]++));
                if (t[0] == 20) {
                    while (next[0] < plan.size()) convert(c, plan.get(next[0]++));
                    fire(c);
                    pits(c, p);
                    works.save();
                }
                return;
            }
            if (System.currentTimeMillis() >= j.restoreAt) { c.task.cancel(); cool(c); return; }
            if (t[0] % 4 == 0 && w.isChunkLoaded(j.fx >> 4, j.fz >> 4)) {
                if (plugin.getConfig().getBoolean("fire.crisp.evaporate", true)) keepDry(c);
                if (sphere) drip(c);
            }
        }, 1L, 1L);
    }

    /** One block of the spread: only if it's still what it was when the cast looked. */
    private void convert(Cast c, Object[] step) {
        Block b = (Block) step[0];
        Material to = (Material) step[1];
        if (b.getType() != step[2] || works.owns(b)) return;
        Block above = b.getRelative(BlockFace.UP);
        if (LandMath.plant(above.getType().name()) && !works.owns(above)) { // the plant on it would pop off: it goes, and comes back
            works.record(c.job, LandLedger.Phase.PLANT, above, Material.AIR);
            above.setType(Material.AIR, false);
        }
        works.record(c.job, LandLedger.Phase.LAND, b, to);
        b.setType(to, false);
        if (rnd.nextInt(3) == 0) {
            Location top = b.getLocation().add(0.5, 1.05, 0.5);
            b.getWorld().spawnParticle(Particle.FLAME, top, 2, 0.25, 0.1, 0.25, 0.03);
            b.getWorld().spawnParticle(Particle.LAVA, top, 1, 0.2, 0.05, 0.2, 0);
        }
    }

    /** Fire on some of the new netherrack, only where fire can sit (air above). */
    private void fire(Cast c) {
        double chance = cfgD("fire-chance", 0.15);
        World w = c.job.world;
        for (LandLedger.Entry e : new ArrayList<>(c.job.ledger.entries())) {
            if (e.phase() != LandLedger.Phase.LAND || !e.placed().equals("NETHERRACK") || rnd.nextDouble() >= chance) continue;
            Block b = w.getBlockAt(e.x(), e.y(), e.z());
            Block above = b.getRelative(BlockFace.UP);
            if (b.getType() != Material.NETHERRACK || !above.getType().isAir() || works.owns(above) || works.protectedSpot(above)) continue;
            works.record(c.job, LandLedger.Phase.FIRE, above, Material.FIRE);
            above.setType(Material.FIRE, false);
        }
    }

    /** 0 to lava-pits-max small pits, each with lava-pit-chance: floor only, enclosed, never under the caster or anyone standing. */
    private void pits(Cast c, Player caster) {
        LandWorks.Job j = c.job;
        int want = 0;
        int max = (int) cfgD("lava-pits-max", 2);
        for (int i = 0; i < max; i++) if (rnd.nextDouble() < cfgD("lava-pit-chance", 0.5)) want++;
        if (want == 0 || c.floors.isEmpty()) return;
        World w = j.world;
        CrispMath.Grid g = new CrispMath.Grid() {
            public boolean solid(int x, int y, int z) { Block b = w.getBlockAt(x, y, z); return b.getType().isSolid() && !LandWorks.waterlogged(b); }
            public boolean open(int x, int y, int z) { return w.getBlockAt(x, y, z).getType().isAir(); }
            public boolean water(int x, int y, int z) { Block b = w.getBlockAt(x, y, z); return LandWorks.wateryBlock(b) || LandWorks.waterlogged(b); }
        };
        int[] feet = caster.isOnline() && caster.getWorld().equals(w)
                ? new int[] {caster.getLocation().getBlockX(), caster.getLocation().getBlockY(), caster.getLocation().getBlockZ()}
                : new int[] {j.fx, j.fy, j.fz};
        List<int[]> standing = new ArrayList<>();
        for (Player o : w.getPlayers()) {
            Location l = o.getLocation();
            if (Math.abs(l.getX() - j.fx) <= j.radius + 3 && Math.abs(l.getZ() - j.fz) <= j.radius + 3)
                standing.add(new int[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        }
        List<Block> cand = new ArrayList<>();
        for (Block b : c.floors) {
            double dx = b.getX() - j.fx, dz = b.getZ() - j.fz;
            if (Math.sqrt(dx * dx + dz * dz) <= j.radius - 2) cand.add(b); // two blocks inside the edge, away from water flowing in
        }
        Collections.shuffle(cand, rnd);
        int made = 0;
        for (Block b : cand) {
            if (made >= want) break;
            List<int[]> cells = CrispMath.pit(b.getX(), b.getY(), b.getZ(), 1 + rnd.nextInt(3), g, feet, standing);
            if (cells == null) continue;
            boolean ok = true;
            for (int[] cell : cells) {
                Block cb = w.getBlockAt(cell[0], cell[1], cell[2]);
                LandWorks.Job holder = works.holder(cb);
                if (holder != null ? holder != j : !works.candidate(cb)) { ok = false; break; } // ours, or natural ground nobody holds
            }
            if (!ok) continue;
            for (int[] cell : cells) {
                Block cb = w.getBlockAt(cell[0], cell[1], cell[2]);
                works.record(j, LandLedger.Phase.PIT, cb, Material.LAVA);
                cb.setType(Material.LAVA, false);
                w.spawnParticle(Particle.LAVA, cb.getLocation().add(0.5, 1, 0.5), 3, 0.2, 0.05, 0.2, 0);
            }
            made++;
        }
    }

    /** Water in the domain steams away: sources and flowing water go, waterlogged blocks dry, seagrass and kelp shrivel. */
    private void evaporate(Cast c, List<Block> water) {
        int n = 0;
        LandWorks.Job j = c.job;
        for (Block b : water) {
            if (works.owns(b) || works.protectedSpot(b) || LandWorks.nextToLava(b)) continue; // never open a way for lava to flow
            if (b.getState(false) instanceof TileState) continue; // a waterlogged chest or sign keeps its water
            String m = b.getType().name();
            if (LandWorks.waterlogged(b)) {
                Waterlogged d = (Waterlogged) b.getBlockData();
                works.record(j, LandLedger.Phase.WATER, b, b.getType());
                d.setWaterlogged(false);
                b.setBlockData(d, false);
            } else if (LandWorks.wateryBlock(b) || LandMath.waterPlant(m)) {
                works.record(j, LandLedger.Phase.WATER, b, Material.AIR);
                b.setType(Material.AIR, false);
            } else continue;
            if (n++ % 3 == 0) j.world.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.6, 0.5), 4, 0.3, 0.3, 0.3, 0.03);
        }
        if (n > 0) {
            GemSounds.play(plugin, "fire.crisp-evaporate", new Location(j.world, j.fx + 0.5, j.fy, j.fz + 0.5));
            c.lastHiss = System.currentTimeMillis();
        }
    }

    /** While it lasts, water that flows in from outside steams away too (not recorded: it flows back by itself later). */
    private void keepDry(Cast c) {
        LandWorks.Job j = c.job;
        int r = (int) Math.ceil(j.radius), n = 0;
        World w = j.world;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!LandMath.inDisc(dx, dz, j.radius)) continue;
            int y0 = j.sphere ? j.fy + 1 - r : j.fy - r, y1 = j.sphere ? j.fy + 1 + r : j.fy + 3;
            for (int y = y0; y <= y1; y++) {
                if (j.sphere && !LandMath.inSphere(dx, y - j.fy - 1, dz, j.radius)) continue;
                Block b = w.getBlockAt(j.fx + dx, y, j.fz + dz);
                if (!LandWorks.wateryBlock(b) || works.owns(b) || works.protectedSpot(b) || LandWorks.nextToLava(b)) continue;
                b.setType(Material.AIR, false);
                if (n++ % 2 == 0) w.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0.02);
            }
        }
        if (n > 0 && System.currentTimeMillis() - c.lastHiss > 1000) {
            GemSounds.play(plugin, "fire.crisp-evaporate", new Location(w, j.fx + 0.5, j.fy, j.fz + 0.5));
            c.lastHiss = System.currentTimeMillis();
        }
    }

    /** Underground: lava drips from the ceiling as if it were melting. */
    private void drip(Cast c) {
        if (c.ceiling.isEmpty()) return;
        World w = c.job.world;
        for (int i = 0; i < 4; i++) {
            int[] b = c.ceiling.get(rnd.nextInt(c.ceiling.size()));
            if (!works.owns(w.getBlockAt(b[0], b[1], b[2]))) continue;
            w.spawnParticle(Particle.DRIPPING_LAVA, b[0] + 0.2 + rnd.nextDouble() * 0.6, b[1] - 0.05, b[2] + 0.2 + rnd.nextDouble() * 0.6, 1);
        }
    }

    /** Cooling: over about 1.5 s every spot goes back (fire out first, the water last), with steam and a soft hiss. */
    private void cool(Cast c) {
        LandWorks.Job j = c.job;
        List<LandLedger.Entry> order = j.ledger.restoreOrder();
        World w = j.world;
        GemSounds.play(plugin, "fire.crisp-cool", new Location(w, j.fx + 0.5, j.fy, j.fz + 0.5));
        int per = Math.max(1, (int) Math.ceil(order.size() / 30.0));
        final int[] at = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int k = 0; k < per && at[0] < order.size(); k++, at[0]++) {
                LandLedger.Entry e = order.get(at[0]);
                if (!works.restoreOne(j, e)) continue; // broken or replaced since: left as it is now
                if (at[0] % 3 == 0) {
                    Location top = new Location(w, e.x() + 0.5, e.y() + 1.0, e.z() + 0.5);
                    w.spawnParticle(Particle.CLOUD, top, 2, 0.25, 0.15, 0.25, 0.02);
                    w.spawnParticle(Particle.WHITE_SMOKE, top, 2, 0.25, 0.1, 0.25, 0.01);
                }
            }
            if (at[0] >= order.size()) {
                task[0].cancel();
                casts.remove(j.id);
                works.close(j);
            }
        }, 1L, 1L);
    }
}
