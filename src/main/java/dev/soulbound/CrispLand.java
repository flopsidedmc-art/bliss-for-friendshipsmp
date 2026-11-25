package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Crisp's land effect (fire.crisp). Around you, random patches of natural ground turn into Nether blocks, spreading
 * outward over a second; some new netherrack catches fire and now and then a small lava pit opens in the floor.
 * Underground (no sky, a ceiling within 6 blocks) it's a sphere: floor, walls and ceiling, with lava dripping from
 * the ceiling. Water in the area steams away and stays away. After restore-after-seconds it all cools back with
 * steam, every spot exactly as it was unless a player has changed it since (CrispLedger). Pending restores are saved,
 * so a restart still puts the land back. Never touches anything built, any block entity, a running capture point, a
 * Rift blocked area or the finale's arena, and Crisp's fire and lava never spread or flow.
 */
final class CrispLand implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final Map<UUID, Cast> casts = new LinkedHashMap<>();
    private final Map<String, Cast> owned = new HashMap<>(); // "world:x:y:z" of every block Crisp holds right now
    private final Random rnd = new Random();

    private static final class Cast {
        final UUID id = UUID.randomUUID();
        World world;
        int fx, fy, fz; // your feet when you cast
        double radius;
        boolean sphere;
        long restoreAt;
        final CrispLedger ledger = new CrispLedger();
        final List<int[]> ceiling = new ArrayList<>();
        final List<Block> floors = new ArrayList<>();
        BukkitTask task;
        long lastHiss;
    }

    CrispLand(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crisp-restore.yml");
    }

    private double cfgD(String k, double def) { return plugin.getConfig().getDouble("fire.crisp." + k, def); }

    private static String key(World w, int x, int y, int z) { return w.getName() + ":" + x + ":" + y + ":" + z; }

    private boolean owns(Block b) { return owned.containsKey(key(b.getWorld(), b.getX(), b.getY(), b.getZ())); }

    // ------------------------------------------------------------------ start and stop

    /** On enable: anything a restart left changed is put back now. */
    void start() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("casts");
        int restored = 0;
        if (all != null) for (String id : all.getKeys(false)) {
            World w = Bukkit.getWorld(all.getString(id + ".world", ""));
            if (w == null) continue; // that world isn't loaded: left in the file for next time
            CrispLedger l = new CrispLedger();
            for (String s : all.getStringList(id + ".entries")) {
                CrispLedger.Entry e = CrispLedger.load(s);
                if (e != null) l.add(e);
            }
            restored += restoreNow(w, l);
            y.set("casts." + id, null);
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save crisp-restore.yml: " + ex.getMessage()); }
        if (restored > 0) plugin.getLogger().info("Crisp: put back " + restored + " blocks left changed by a restart.");
    }

    /** On disable or server stop: every cast cools at once. */
    void shutdown() {
        for (Cast c : new ArrayList<>(casts.values())) {
            if (c.task != null) c.task.cancel();
            restoreNow(c.world, c.ledger);
        }
        casts.clear();
        owned.clear();
        save();
    }

    private int restoreNow(World w, CrispLedger l) {
        return l.restore(p -> w.getBlockAt(p[0], p[1], p[2]).getType().name(), (e, data) -> {
            try { w.getBlockAt(e.x(), e.y(), e.z()).setBlockData(Bukkit.createBlockData(data), false); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Crisp: couldn't put back " + data + " at " + e.key()); }
            owned.remove(key(w, e.x(), e.y(), e.z()));
        });
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Cast c : casts.values()) {
            y.set("casts." + c.id + ".world", c.world.getName());
            y.set("casts." + c.id + ".restore-at", c.restoreAt);
            y.set("casts." + c.id + ".entries", c.ledger.saveAll());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save crisp-restore.yml: " + ex.getMessage()); }
    }

    // ------------------------------------------------------------------ which blocks

    /** Off limits: a running capture point, a Rift blocked area, the finale's arena. */
    private boolean protectedSpot(Block b) {
        Location l = b.getLocation();
        if (plugin.captures() != null && plugin.captures().riftBlocked(l) != null) return true;
        if (plugin.relay() != null && plugin.relay().rift() != null && plugin.relay().rift().configuredBlock(l) != null) return true;
        return plugin.finale() != null && plugin.finale().inArea(l);
    }

    private boolean candidate(Block b) {
        if (owns(b)) return false;
        BlockData d = b.getBlockData();
        boolean persistent = d instanceof Leaves lv && lv.isPersistent();
        boolean tile = b.getState(false) instanceof TileState;
        return CrispMath.eligible(b.getType().name(), tile, persistent) && !protectedSpot(b);
    }

    private static boolean wateryBlock(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    private static boolean waterlogged(Block b) { return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged(); }

    private static boolean nextToLava(Block b) {
        for (BlockFace f : new BlockFace[] {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            if (b.getRelative(f).getType() == Material.LAVA) return true;
        return false;
    }

    private static boolean exposed(Block b) {
        for (BlockFace f : new BlockFace[] {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.isPassable() || wateryBlock(n)) return true;
        }
        return false;
    }

    /** Underground: no sky above your head, and a solid ceiling within 6 blocks (a tree's leaves aren't a ceiling). */
    private static boolean underground(Player p) {
        Location head = p.getEyeLocation();
        World w = p.getWorld();
        boolean seesSky = w.getHighestBlockYAt(head.getBlockX(), head.getBlockZ()) <= head.getBlockY();
        int ceiling = 0;
        for (int i = 1; i <= 6; i++) {
            Material m = w.getBlockAt(head.getBlockX(), head.getBlockY() + i, head.getBlockZ()).getType();
            if (m.isSolid() && !m.name().endsWith("_LEAVES")) { ceiling = i; break; }
        }
        return CrispMath.underground(seesSky, ceiling);
    }

    // ------------------------------------------------------------------ the cast

    /** Crisp's land effect around {@code p} (fire.crisp.land). */
    void cast(Player p) {
        if (!plugin.getConfig().getBoolean("fire.crisp.land", true)) return;
        World w = p.getWorld();
        Location feet = p.getLocation();
        Cast c = new Cast();
        c.world = w;
        c.fx = feet.getBlockX();
        c.fy = feet.getBlockY();
        c.fz = feet.getBlockZ();
        c.radius = Math.max(1, Math.min(12, cfgD("radius", 5)));
        c.sphere = underground(p);
        c.restoreAt = System.currentTimeMillis() + (long) (Math.max(1, cfgD("restore-after-seconds", 30)) * 1000);
        int r = (int) Math.ceil(c.radius);

        // The domain: the floor area (a disc of floor blocks, and everything from r below to 3 above for water), or a sphere.
        List<Block> water = new ArrayList<>(), land = new ArrayList<>();
        List<double[]> rel = new ArrayList<>();
        if (c.sphere) {
            int ox = c.fx, oy = c.fy + 1, oz = c.fz;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (!CrispMath.inSphere(dx, dy, dz, c.radius)) continue;
                Block b = w.getBlockAt(ox + dx, oy + dy, oz + dz);
                if (wateryBlock(b) || waterlogged(b) || CrispMath.waterPlant(b.getType().name())) { water.add(b); continue; }
                if (!b.getType().isSolid() || !exposed(b) || !candidate(b)) continue;
                land.add(b);
                rel.add(new double[] {dx, dy, dz});
                if (dy < 0 && b.getRelative(BlockFace.UP).getType().isAir()) c.floors.add(b);
                if (dy > 0 && b.getRelative(BlockFace.DOWN).getType().isAir()) c.ceiling.add(new int[] {b.getX(), b.getY(), b.getZ()});
            }
        } else {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (!CrispMath.inDisc(dx, dz, c.radius)) continue;
                Block floor = null;
                for (int y = c.fy + 3; y >= c.fy - r; y--) {
                    Block b = w.getBlockAt(c.fx + dx, y, c.fz + dz);
                    if (wateryBlock(b) || waterlogged(b) || CrispMath.waterPlant(b.getType().name())) water.add(b);
                    if (floor == null && b.getType().isSolid() && !wateryBlock(b)) floor = b;
                }
                if (floor == null || !candidate(floor)) continue;
                Block above = floor.getRelative(BlockFace.UP);
                String a = above.getType().name();
                if (!above.getType().isAir() && !CrispMath.plant(a) && !wateryBlock(above) && !CrispMath.waterPlant(a)) continue; // a double plant, a torch, a slab ...
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
        int[] pick = CrispMath.patches(rel, cfgD("chance", 0.45), c.radius, c.sphere, wArr, rnd);
        List<Object[]> plan = new ArrayList<>(); // {block, material, original material, distance}
        for (int i = 0; i < land.size(); i++) {
            if (pick[i] < 0) continue;
            double[] d = rel.get(i);
            plan.add(new Object[] {land.get(i), kinds.get(pick[i]), land.get(i).getType(), Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])});
        }
        plan.sort(Comparator.comparingDouble(o -> (double) o[3]));
        casts.put(c.id, c);
        save();

        // Spread outward over a second; then fire and the pits; then it lasts, and cools.
        final int[] t = {0};
        final int[] next = {0};
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (t[0] <= 20) {
                double reach = c.radius * t[0] / 20.0;
                while (next[0] < plan.size() && (double) plan.get(next[0])[3] <= reach + 0.01) convert(c, plan.get(next[0]++));
                if (t[0] == 20) {
                    while (next[0] < plan.size()) convert(c, plan.get(next[0]++));
                    fire(c);
                    pits(c, p);
                    save();
                }
                return;
            }
            if (System.currentTimeMillis() >= c.restoreAt) { c.task.cancel(); cool(c); return; }
            if (t[0] % 4 == 0 && w.isChunkLoaded(c.fx >> 4, c.fz >> 4)) {
                if (plugin.getConfig().getBoolean("fire.crisp.evaporate", true)) keepDry(c);
                if (c.sphere) drip(c);
            }
        }, 1L, 1L);
    }

    private void record(Cast c, CrispLedger.Phase phase, Block b, Material placed) {
        c.ledger.add(new CrispLedger.Entry(phase, b.getX(), b.getY(), b.getZ(), placed.name(), b.getBlockData().getAsString()));
        owned.put(key(c.world, b.getX(), b.getY(), b.getZ()), c);
    }

    /** One block of the spread: only if it's still what it was when the cast looked. */
    private void convert(Cast c, Object[] step) {
        Block b = (Block) step[0];
        Material to = (Material) step[1];
        if (b.getType() != step[2] || owns(b)) return;
        Block above = b.getRelative(BlockFace.UP);
        if (CrispMath.plant(above.getType().name()) && !owns(above)) { // the plant on it would pop off: it goes, and comes back
            record(c, CrispLedger.Phase.PLANT, above, Material.AIR);
            above.setType(Material.AIR, false);
        }
        record(c, CrispLedger.Phase.LAND, b, to);
        b.setType(to, false);
        if (rnd.nextInt(3) == 0) {
            Location top = b.getLocation().add(0.5, 1.05, 0.5);
            c.world.spawnParticle(Particle.FLAME, top, 2, 0.25, 0.1, 0.25, 0.03);
            c.world.spawnParticle(Particle.LAVA, top, 1, 0.2, 0.05, 0.2, 0);
        }
    }

    /** Fire on some of the new netherrack, only where fire can sit (air above). */
    private void fire(Cast c) {
        double chance = cfgD("fire-chance", 0.15);
        for (CrispLedger.Entry e : new ArrayList<>(c.ledger.entries())) {
            if (e.phase() != CrispLedger.Phase.LAND || !e.placed().equals("NETHERRACK") || rnd.nextDouble() >= chance) continue;
            Block b = c.world.getBlockAt(e.x(), e.y(), e.z());
            Block above = b.getRelative(BlockFace.UP);
            if (b.getType() != Material.NETHERRACK || !above.getType().isAir() || owns(above) || protectedSpot(above)) continue;
            record(c, CrispLedger.Phase.FIRE, above, Material.FIRE);
            above.setType(Material.FIRE, false);
        }
    }

    /** 0 to lava-pits-max small pits, each with lava-pit-chance: floor only, enclosed, never under the caster or anyone standing. */
    private void pits(Cast c, Player caster) {
        int want = 0;
        int max = (int) cfgD("lava-pits-max", 2);
        for (int i = 0; i < max; i++) if (rnd.nextDouble() < cfgD("lava-pit-chance", 0.5)) want++;
        if (want == 0 || c.floors.isEmpty()) return;
        World w = c.world;
        CrispMath.Grid g = new CrispMath.Grid() {
            public boolean solid(int x, int y, int z) { Block b = w.getBlockAt(x, y, z); return b.getType().isSolid() && !waterlogged(b); }
            public boolean open(int x, int y, int z) { return w.getBlockAt(x, y, z).getType().isAir(); }
            public boolean water(int x, int y, int z) { Block b = w.getBlockAt(x, y, z); return wateryBlock(b) || waterlogged(b); }
        };
        int[] feet = caster.isOnline() && caster.getWorld().equals(w)
                ? new int[] {caster.getLocation().getBlockX(), caster.getLocation().getBlockY(), caster.getLocation().getBlockZ()}
                : new int[] {c.fx, c.fy, c.fz};
        List<int[]> standing = new ArrayList<>();
        for (Player o : w.getPlayers()) {
            Location l = o.getLocation();
            if (Math.abs(l.getX() - c.fx) <= c.radius + 3 && Math.abs(l.getZ() - c.fz) <= c.radius + 3)
                standing.add(new int[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        }
        List<Block> cand = new ArrayList<>();
        for (Block b : c.floors) {
            double dx = b.getX() - c.fx, dz = b.getZ() - c.fz;
            if (Math.sqrt(dx * dx + dz * dz) <= c.radius - 2) cand.add(b); // two blocks inside the edge, away from water flowing in
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
                Cast holder = owned.get(key(w, cell[0], cell[1], cell[2]));
                if (holder != null ? holder != c : !candidate(cb)) { ok = false; break; } // ours, or natural ground nobody holds
            }
            if (!ok) continue;
            for (int[] cell : cells) {
                Block cb = w.getBlockAt(cell[0], cell[1], cell[2]);
                record(c, CrispLedger.Phase.PIT, cb, Material.LAVA);
                cb.setType(Material.LAVA, false);
                w.spawnParticle(Particle.LAVA, cb.getLocation().add(0.5, 1, 0.5), 3, 0.2, 0.05, 0.2, 0);
            }
            made++;
        }
    }

    /** Water in the domain steams away: sources and flowing water go, waterlogged blocks dry, seagrass and kelp shrivel. */
    private void evaporate(Cast c, List<Block> water) {
        int n = 0;
        for (Block b : water) {
            if (owns(b) || protectedSpot(b) || nextToLava(b)) continue; // never open a way for lava to flow
            if (b.getState(false) instanceof TileState) continue; // a waterlogged chest or sign keeps its water
            String m = b.getType().name();
            if (waterlogged(b)) {
                Waterlogged d = (Waterlogged) b.getBlockData();
                record(c, CrispLedger.Phase.WATER, b, b.getType());
                d.setWaterlogged(false);
                b.setBlockData(d, false);
            } else if (wateryBlock(b) || CrispMath.waterPlant(m)) {
                record(c, CrispLedger.Phase.WATER, b, Material.AIR);
                b.setType(Material.AIR, false);
            } else continue;
            if (n++ % 3 == 0) c.world.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.6, 0.5), 4, 0.3, 0.3, 0.3, 0.03);
        }
        if (n > 0) {
            GemSounds.play(plugin, "fire.crisp-evaporate", new Location(c.world, c.fx + 0.5, c.fy, c.fz + 0.5));
            c.lastHiss = System.currentTimeMillis();
        }
    }

    /** While it lasts, water that flows in from outside steams away too (not recorded: it flows back by itself later). */
    private void keepDry(Cast c) {
        int r = (int) Math.ceil(c.radius), n = 0;
        World w = c.world;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!CrispMath.inDisc(dx, dz, c.radius)) continue;
            int y0 = c.sphere ? c.fy + 1 - r : c.fy - r, y1 = c.sphere ? c.fy + 1 + r : c.fy + 3;
            for (int y = y0; y <= y1; y++) {
                if (c.sphere && !CrispMath.inSphere(dx, y - c.fy - 1, dz, c.radius)) continue;
                Block b = w.getBlockAt(c.fx + dx, y, c.fz + dz);
                if (!wateryBlock(b) || owns(b) || protectedSpot(b) || nextToLava(b)) continue;
                b.setType(Material.AIR, false);
                if (n++ % 2 == 0) w.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0.02);
            }
        }
        if (n > 0 && System.currentTimeMillis() - c.lastHiss > 1000) {
            GemSounds.play(plugin, "fire.crisp-evaporate", new Location(w, c.fx + 0.5, c.fy, c.fz + 0.5));
            c.lastHiss = System.currentTimeMillis();
        }
    }

    /** Underground: lava drips from the ceiling as if it were melting. */
    private void drip(Cast c) {
        if (c.ceiling.isEmpty()) return;
        for (int i = 0; i < 4; i++) {
            int[] b = c.ceiling.get(rnd.nextInt(c.ceiling.size()));
            if (!owns(c.world.getBlockAt(b[0], b[1], b[2]))) continue;
            c.world.spawnParticle(Particle.DRIPPING_LAVA, b[0] + 0.2 + rnd.nextDouble() * 0.6, b[1] - 0.05, b[2] + 0.2 + rnd.nextDouble() * 0.6, 1);
        }
    }

    /** Cooling: over about 1.5 s every spot goes back (fire out first, the water last), with steam and a soft hiss. */
    private void cool(Cast c) {
        List<CrispLedger.Entry> order = c.ledger.restoreOrder();
        World w = c.world;
        GemSounds.play(plugin, "fire.crisp-cool", new Location(w, c.fx + 0.5, c.fy, c.fz + 0.5));
        int per = Math.max(1, (int) Math.ceil(order.size() / 30.0));
        final int[] at = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int k = 0; k < per && at[0] < order.size(); k++, at[0]++) {
                CrispLedger.Entry e = order.get(at[0]);
                Block b = w.getBlockAt(e.x(), e.y(), e.z());
                owned.remove(key(w, e.x(), e.y(), e.z()));
                if (!CrispLedger.stillOurs(b.getType().name(), e)) continue; // broken or replaced since: left as it is now
                try { b.setBlockData(Bukkit.createBlockData(e.original()), false); } catch (IllegalArgumentException ex) { continue; }
                if (at[0] % 3 == 0) {
                    Location top = b.getLocation().add(0.5, 1.0, 0.5);
                    w.spawnParticle(Particle.CLOUD, top, 2, 0.25, 0.15, 0.25, 0.02);
                    w.spawnParticle(Particle.WHITE_SMOKE, top, 2, 0.25, 0.1, 0.25, 0.01);
                }
            }
            if (at[0] >= order.size()) {
                task[0].cancel();
                casts.remove(c.id);
                save();
            }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ keeping it safe while it lasts

    /** Breaking a Crisp block drops nothing extra, and that spot is left as it is when it cools. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!owns(e.getBlock())) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
    }

    /** No free lava from a pit. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent e) {
        if (owns(e.getBlock()) || owns(e.getBlockClicked())) e.setCancelled(true);
    }

    /** Crisp's lava never flows. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (owns(e.getBlock())) e.setCancelled(true);
    }

    /** Crisp's fire and lava never spread or light anything. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (owns(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getIgnitingBlock() != null && owns(e.getIgnitingBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (e.getIgnitingBlock() != null && owns(e.getIgnitingBlock())) e.setCancelled(true);
    }

    /** Pistons, explosions and endermen leave Crisp blocks alone (no moving or duplicating them). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (owns(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (owns(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::owns); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::owns); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (owns(e.getBlock())) e.setCancelled(true);
    }

    /** A log that turned to netherrack would let its leaves decay for good: no decay near a cast while it lasts. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        Block b = e.getBlock();
        for (Cast c : casts.values()) {
            if (!c.world.equals(b.getWorld())) continue;
            double dx = b.getX() - c.fx, dz = b.getZ() - c.fz;
            if (dx * dx + dz * dz <= (c.radius + 8) * (c.radius + 8)) { e.setCancelled(true); return; }
        }
    }
}
