package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the land effects share (Crisp's fire, CrispLand; Vitality Vortex's drain, VortexDrain): which blocks each one
 * holds right now, their ledgers (LandLedger) saved to land-restore.yml so a restart or crash still puts the land back,
 * the natural-blocks-only filter and the places they keep out of, and the guards while a change lasts: no moving,
 * exploding or picking up its blocks, no liquids flowing out of them, no fire spreading from them, no leaf decay
 * near them.
 */
final class LandWorks implements Listener {
    /** One cast's changes. {@code noDrops}: breaking one of its blocks drops nothing (Crisp); otherwise it drops as it is (Vortex's dead blocks). */
    static final class Job {
        final UUID id = UUID.randomUUID();
        final String kind;
        final World world;
        final int fx, fy, fz; // the caster's feet
        final double radius;
        final boolean sphere, noDrops;
        final LandLedger ledger = new LandLedger();
        long restoreAt;

        Job(String kind, World world, int fx, int fy, int fz, double radius, boolean sphere, boolean noDrops, long restoreAt) {
            this.kind = kind;
            this.world = world;
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.radius = radius;
            this.sphere = sphere;
            this.noDrops = noDrops;
            this.restoreAt = restoreAt;
        }
    }

    private final SoulboundGemsPlugin plugin;
    private final File file, legacy;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Job> owned = new HashMap<>(); // "world:x:y:z" -> the job holding that block now

    LandWorks(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "land-restore.yml");
        this.legacy = new File(plugin.getDataFolder(), "crisp-restore.yml"); // the last version's file
    }

    Job open(String kind, World w, int fx, int fy, int fz, double radius, boolean sphere, boolean noDrops, long restoreAt) {
        Job j = new Job(kind, w, fx, fy, fz, radius, sphere, noDrops, restoreAt);
        jobs.put(j.id, j);
        return j;
    }

    /** It has cooled: forget it. */
    void close(Job j) {
        jobs.remove(j.id);
        for (LandLedger.Entry e : j.ledger.entries()) owned.remove(key(j.world, e.x(), e.y(), e.z()), j);
        save();
    }

    private static String key(World w, int x, int y, int z) { return w.getName() + ":" + x + ":" + y + ":" + z; }

    boolean owns(Block b) { return owned.containsKey(key(b.getWorld(), b.getX(), b.getY(), b.getZ())); }

    Job holder(Block b) { return owned.get(key(b.getWorld(), b.getX(), b.getY(), b.getZ())); }

    void record(Job j, LandLedger.Phase phase, Block b, Material placed) {
        j.ledger.add(new LandLedger.Entry(phase, b.getX(), b.getY(), b.getZ(), placed.name(), b.getBlockData().getAsString()));
        owned.put(key(j.world, b.getX(), b.getY(), b.getZ()), j);
    }

    /** One entry back, if the spot still holds what the effect put there (else it's left as it is now). True if it went back. */
    boolean restoreOne(Job j, LandLedger.Entry e) {
        Block b = j.world.getBlockAt(e.x(), e.y(), e.z());
        owned.remove(key(j.world, e.x(), e.y(), e.z()), j);
        if (!LandLedger.stillOurs(b.getType().name(), e)) return false;
        try { b.setBlockData(Bukkit.createBlockData(e.original()), false); }
        catch (IllegalArgumentException ex) { plugin.getLogger().warning("Couldn't put back " + e.original() + " at " + e.key()); return false; }
        return true;
    }

    private int restoreNow(World w, LandLedger l) {
        return l.restore(p -> w.getBlockAt(p[0], p[1], p[2]).getType().name(), (e, data) -> {
            try { w.getBlockAt(e.x(), e.y(), e.z()).setBlockData(Bukkit.createBlockData(data), false); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Couldn't put back " + data + " at " + e.key()); }
            owned.remove(key(w, e.x(), e.y(), e.z()));
        });
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Job j : jobs.values()) {
            y.set("jobs." + j.id + ".kind", j.kind);
            y.set("jobs." + j.id + ".world", j.world.getName());
            y.set("jobs." + j.id + ".restore-at", j.restoreAt);
            y.set("jobs." + j.id + ".entries", j.ledger.saveAll());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save land-restore.yml: " + ex.getMessage()); }
    }

    /** On enable: land a restart or crash left changed goes back now (this file, and the last version's Crisp file). */
    void start() {
        int restored = 0;
        YamlConfiguration keep = new YamlConfiguration();
        for (File f : new File[] {file, legacy}) {
            if (!f.exists()) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            for (String root : new String[] {"jobs", "casts"}) {
                ConfigurationSection all = y.getConfigurationSection(root);
                if (all == null) continue;
                for (String id : all.getKeys(false)) {
                    World w = Bukkit.getWorld(all.getString(id + ".world", ""));
                    if (w == null) { keep.createSection("jobs." + id, all.getConfigurationSection(id).getValues(false)); continue; } // not loaded: kept for next time
                    LandLedger l = new LandLedger();
                    for (String s : all.getStringList(id + ".entries")) {
                        LandLedger.Entry e = LandLedger.load(s);
                        if (e != null) l.add(e);
                    }
                    restored += restoreNow(w, l);
                }
            }
        }
        if (legacy.exists() && !legacy.delete()) plugin.getLogger().warning("Couldn't remove crisp-restore.yml (already restored).");
        try { keep.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save land-restore.yml: " + ex.getMessage()); }
        if (restored > 0) plugin.getLogger().info("Put back " + restored + " blocks of land a restart left changed (Crisp, Vitality Vortex).");
    }

    /** On disable or server stop: every change goes back at once. */
    void shutdown() {
        for (Job j : new ArrayList<>(jobs.values())) restoreNow(j.world, j.ledger);
        jobs.clear();
        owned.clear();
        save();
    }

    // ------------------------------------------------------------------ which blocks

    /** Off limits: a running capture point, a Rift blocked area, the finale's arena. */
    boolean protectedSpot(Block b) {
        Location l = b.getLocation();
        if (plugin.captures() != null && plugin.captures().riftBlocked(l) != null) return true;
        if (plugin.relay() != null && plugin.relay().rift() != null && plugin.relay().rift().configuredBlock(l) != null) return true;
        return plugin.finale() != null && plugin.finale().inArea(l);
    }

    /** Natural ground nobody holds, outside the off-limits places (LandMath.eligible: never a block entity or anything built). */
    boolean candidate(Block b) {
        if (owns(b)) return false;
        BlockData d = b.getBlockData();
        boolean persistent = d instanceof Leaves lv && lv.isPersistent();
        boolean tile = b.getState(false) instanceof TileState;
        return LandMath.eligible(b.getType().name(), tile, persistent) && !protectedSpot(b);
    }

    static boolean wateryBlock(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    static boolean waterlogged(Block b) { return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged(); }

    static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    static boolean nextToLava(Block b) {
        for (BlockFace f : FACES) if (b.getRelative(f).getType() == Material.LAVA) return true;
        return false;
    }

    /** It holds back a liquid: water or lava (or a waterlogged block) right beside it. */
    static boolean holdsLiquid(Block b) {
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (n.isLiquid() || wateryBlock(n) || waterlogged(n)) return true;
        }
        return false;
    }

    static boolean exposed(Block b) {
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (n.isPassable() || wateryBlock(n)) return true;
        }
        return false;
    }

    /** Underground: no sky above your head, and a solid ceiling within 6 blocks (a tree's leaves aren't a ceiling). */
    static boolean underground(Player p) {
        Location head = p.getEyeLocation();
        World w = p.getWorld();
        boolean seesSky = w.getHighestBlockYAt(head.getBlockX(), head.getBlockZ()) <= head.getBlockY();
        int ceiling = 0;
        for (int i = 1; i <= 6; i++) {
            Material m = w.getBlockAt(head.getBlockX(), head.getBlockY() + i, head.getBlockZ()).getType();
            if (m.isSolid() && !m.name().endsWith("_LEAVES")) { ceiling = i; break; }
        }
        return LandMath.underground(seesSky, ceiling);
    }

    // ------------------------------------------------------------------ the guards while a change lasts

    /** Crisp's blocks drop nothing extra; the Vortex's drop as their dead selves. That spot is left as it is when it cools. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Job j = holder(e.getBlock());
        if (j == null || !j.noDrops) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
    }

    /** No free lava (or anything else) out of a changed spot. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent e) {
        if (owns(e.getBlock()) || owns(e.getBlockClicked())) e.setCancelled(true);
    }

    /** Nothing an effect placed ever flows. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (owns(e.getBlock())) e.setCancelled(true);
    }

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

    /** Endermen, ravagers and falling (a dead gravel block can't drop out from under itself). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (owns(e.getBlock())) e.setCancelled(true);
    }

    /** A log that changed would let its leaves decay for good: no decay near a change while it lasts. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        Block b = e.getBlock();
        for (Job j : jobs.values()) {
            if (!j.world.equals(b.getWorld())) continue;
            double dx = b.getX() - j.fx, dz = b.getZ() - j.fz;
            if (dx * dx + dz * dz <= (j.radius + 8) * (j.radius + 8)) { e.setCancelled(true); return; }
        }
    }
}
