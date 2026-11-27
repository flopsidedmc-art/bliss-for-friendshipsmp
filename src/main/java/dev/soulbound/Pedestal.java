package dev.soulbound;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * The repair Pedestal, Bliss style (PedestalLayout): an 11 x 11 round floor, level with the ground, whose ten parts all
 * swap between an open look (beacon heart, gold, cherry and quartz) and a closed one (bedrock heart, blackstone and
 * deepslate), in a wave from the heart out when it opens and from the rim in when it closes. Hidden under the heart:
 * a 3 x 3 of netherite (the open beacon's beam) and ancient debris. Repair rites happen here while open. The beacon is
 * the heart: 5 breaks end an active rite; it drops nothing and comes back. Every other block of the build is
 * unbreakable and nothing ever drops from it. What was there before is recorded (pedestal.yml), so moving it puts the
 * ground back.
 */
public class Pedestal implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private final File dataFile;
    private YamlConfiguration data;

    private Location center; // the heart, on the floor's level
    private boolean open;
    private int breaks;
    private boolean bliss; // built in this style (an old save is rebuilt once)
    /** What was there before, by "dx,dy,dz" from the heart: put back when the Pedestal moves. */
    private final Map<String, String> originals = new LinkedHashMap<>();

    private BukkitTask wave;
    private boolean waveOpening;

    public Pedestal(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        this.dataFile = new File(plugin.getDataFolder(), "pedestal.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        load();
    }

    private void save() {
        try {
            data = new YamlConfiguration();
            if (center != null) {
                data.set("center.world", center.getWorld().getName());
                data.set("center.x", center.getBlockX());
                data.set("center.y", center.getBlockY());
                data.set("center.z", center.getBlockZ());
            }
            data.set("open", open);
            data.set("breaks", breaks);
            data.set("style", bliss ? "bliss" : null);
            for (Map.Entry<String, String> e : originals.entrySet()) data.set("originals." + e.getKey().replace(',', '_'), e.getValue());
            data.save(dataFile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save pedestal: " + ex.getMessage());
        }
    }

    private void load() {
        try {
            if (data.isConfigurationSection("center")) {
                World w = Bukkit.getWorld(data.getString("center.world", "world"));
                if (w != null) {
                    center = new Location(w, data.getInt("center.x"), data.getInt("center.y"), data.getInt("center.z"));
                }
            }
            open = data.getBoolean("open", false);
            breaks = data.getInt("breaks", 0);
            bliss = "bliss".equals(data.getString("style"));
            ConfigurationSection o = data.getConfigurationSection("originals");
            if (o != null) for (String k : o.getKeys(false)) originals.put(k.replace('_', ','), o.getString(k));
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not load pedestal: " + ex.getMessage());
        }
    }

    /** On enable: an old-style Pedestal is rebuilt in this style once; otherwise the build is set to its saved state. */
    void start() {
        if (center == null) return;
        if (!bliss) migrate();
        else placeAll(open);
    }

    /** On disable: a wave in progress finishes at once, in the state it was going to. */
    void shutdown() {
        if (wave != null) {
            wave.cancel();
            wave = null;
            placeAll(waveOpening);
        }
    }

    public boolean exists() { return center != null; }

    /** The heart's block (null if there's no Pedestal). */
    Location center() { return center == null ? null : center.clone(); }

    public boolean isOpen() { return exists() && open; }

    public boolean nearGem(Player p, double radius) {
        if (!exists() || !p.getWorld().equals(center.getWorld())) return false;
        return p.getLocation().distanceSquared(center) <= radius * radius;
    }

    // ------------------------------------------------------------------ the blocks

    private final Map<String, Boolean> warned = new HashMap<>();

    /** The block for a part (config pedestal.blocks.&lt;part&gt;.open / closed, else the default), set up for its spot. */
    private BlockData blockFor(PedestalLayout.Part part, boolean open, int dx, int dz) {
        String key = "pedestal.blocks." + part.name() + "." + (open ? "open" : "closed");
        String name = plugin.getConfig().getString(key, PedestalLayout.block(part, open));
        Material m = Material.matchMaterial(name == null ? "" : name);
        if (m == null || !m.isBlock()) {
            if (warned.put(key, true) == null) plugin.getLogger().warning("config.yml " + key + ": '" + name + "' isn't a block, using the default.");
            m = Material.matchMaterial(PedestalLayout.block(part, open));
        }
        BlockData d = m.createBlockData();
        if (part == PedestalLayout.Part.O && d instanceof Orientable or && or.getAxes().contains(Axis.X))
            or.setAxis(PedestalLayout.axis(dx, dz) == 'x' ? Axis.X : Axis.Z); // lying along its arm, stripes into the heart
        if (d instanceof Slab s) s.setType(Slab.Type.BOTTOM);
        return d;
    }

    private Block at(int dx, int dy, int dz) {
        return center.getWorld().getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
    }

    private static String key(int dx, int dy, int dz) { return dx + "," + dy + "," + dz; }

    /** Every block of the build: the 97 floor blocks and the hidden base. */
    private List<int[]> allOffsets() {
        List<int[]> out = new ArrayList<>();
        for (int[] c : PedestalLayout.floor()) out.add(new int[] {c[0], 0, c[1]});
        out.addAll(PedestalLayout.base());
        return out;
    }

    /** One floor block in its open or closed look (the beacon gets no effects). */
    private void setFloor(int dx, int dz, boolean open) {
        PedestalLayout.Part part = PedestalLayout.part(dx, dz);
        if (part == null) return;
        Block b = at(dx, 0, dz);
        b.setBlockData(blockFor(part, open, dx, dz), false);
        if (b.getState() instanceof org.bukkit.block.Beacon beacon) {
            beacon.setPrimaryEffect(null);
            beacon.setSecondaryEffect(null);
            beacon.update(true, false);
        }
    }

    /** The whole build at once, in one state (enable, disable, a finished or interrupted wave, a rebuild). */
    private void placeAll(boolean open) {
        if (center == null || center.getWorld() == null) return;
        for (int[] c : PedestalLayout.floor()) setFloor(c[0], c[1], open);
        for (int[] b : PedestalLayout.base()) at(b[0], b[1], b[2]).setType(Material.matchMaterial(PedestalLayout.baseBlock(b[1])), false);
    }

    /** Records what's at every spot of the build before it's built there (only spots not recorded yet). */
    private void recordOriginals() {
        for (int[] o : allOffsets()) originals.putIfAbsent(key(o[0], o[1], o[2]), at(o[0], o[1], o[2]).getBlockData().getAsString());
    }

    /** Puts back what was there before, everywhere the build was, and forgets it. */
    private void removeBuild() {
        if (center == null || center.getWorld() == null) return;
        if (wave != null) { wave.cancel(); wave = null; }
        for (int[] o : allOffsets()) {
            String was = originals.get(key(o[0], o[1], o[2]));
            Block b = at(o[0], o[1], o[2]);
            if (was == null) { b.setType(o[1] == 0 ? Material.GRASS_BLOCK : Material.DIRT, false); continue; }
            try { b.setBlockData(Bukkit.createBlockData(was), false); }
            catch (IllegalArgumentException ex) { b.setType(o[1] == 0 ? Material.GRASS_BLOCK : Material.DIRT, false); }
        }
        originals.clear();
    }

    // ------------------------------------------------------------------ the old style (rebuilt once)

    /**
     * An old-style Pedestal (a radius-9 deepslate disc with netherite, a debris heart and a diamond crown): rebuilt in
     * this style at the same centre and state. The old one stored nothing about the ground, so its leftovers (the ring
     * past the new floor) and the recorded "originals" are the ground block most common just outside the old disc.
     */
    private void migrate() {
        World w = center.getWorld();
        if (w == null) return;
        int cx = center.getBlockX(), y = center.getBlockY(), cz = center.getBlockZ();
        Material top = commonGround(w, cx, y, cz, Material.GRASS_BLOCK), under1 = commonGround(w, cx, y - 1, cz, Material.DIRT),
                under2 = commonGround(w, cx, y - 2, cz, Material.DIRT);
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) {
            if (dx * dx + dz * dz > 81 || PedestalLayout.part(dx, dz) != null) continue;
            Block b = w.getBlockAt(cx + dx, y, cz + dz);
            if (oldPedestalBlock(b.getType())) b.setType(top, false); // the old ring past the new floor
        }
        Block crown = w.getBlockAt(cx, y + 1, cz);
        if (crown.getType() == Material.DIAMOND_BLOCK) crown.setType(Material.AIR, false); // the beam needs the sky
        originals.clear();
        for (int[] o : allOffsets()) originals.put(key(o[0], o[1], o[2]), (o[1] == 0 ? top : o[1] == -1 ? under1 : under2).createBlockData().getAsString());
        bliss = true;
        placeAll(open);
        save();
        plugin.getLogger().info("Rebuilt the Pedestal in the new style (" + (open ? "open" : "closed") + ").");
    }

    private static boolean oldPedestalBlock(Material m) {
        return m == Material.DEEPSLATE_BRICKS || m == Material.DEEPSLATE_TILES || m == Material.POLISHED_DEEPSLATE
                || m == Material.CHISELED_DEEPSLATE || m == Material.NETHERITE_BLOCK || m == Material.ANCIENT_DEBRIS;
    }

    /** The most common solid block at height {@code y} in a ring just outside the old disc (radius 10-11), not the old build. */
    private static Material commonGround(World w, int cx, int y, int cz, Material fallback) {
        Map<Material, Integer> n = new HashMap<>();
        for (int dx = -11; dx <= 11; dx++) for (int dz = -11; dz <= 11; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 <= 100 || d2 > 121) continue;
            Material m = w.getBlockAt(cx + dx, y, cz + dz).getType();
            if (!m.isSolid() || oldPedestalBlock(m) || m.name().endsWith("_LEAVES") || !m.isBlock()) continue;
            n.merge(m, 1, Integer::sum);
        }
        Material best = fallback;
        int most = 0;
        for (Map.Entry<Material, Integer> e : n.entrySet()) if (e.getValue() > most) { best = e.getKey(); most = e.getValue(); }
        return best;
    }

    // ------------------------------------------------------------------ activate, deactivate and the wave

    /** Builds it open at the sender's feet (the first time, or somewhere new), or opens it again at the same spot. */
    public void activate(Player p) {
        Location base = p.getLocation().getBlock().getLocation();
        Location heart = new Location(base.getWorld(), base.getBlockX(), base.getBlockY() - 1, base.getBlockZ());
        boolean sameSpot = center != null && heart.getWorld().equals(center.getWorld())
                && heart.getBlockX() == center.getBlockX() && heart.getBlockY() == center.getBlockY() && heart.getBlockZ() == center.getBlockZ();
        if (sameSpot && bliss) {
            if (open && wave == null) { p.sendMessage(ChatColor.GRAY + "The Pedestal is already open."); return; }
            transform(true);
            Bukkit.broadcastMessage(ChatColor.GOLD + "The Pedestal opens. Rites may begin.");
            return;
        }
        if (center != null) removeBuild(); // somewhere else: the old build goes, the ground comes back
        center = heart;
        originals.clear();
        recordOriginals();
        bliss = true;
        breaks = 0;
        placeAll(false); // built closed, then it blooms open
        open = false;
        transform(true);
        Bukkit.broadcastMessage(ChatColor.GOLD + "The Pedestal rises. Rites may begin.");
    }

    public void deactivate(Player p) {
        if (!exists()) {
            p.sendMessage(ChatColor.GRAY + "No Pedestal exists. Build one with /bliss pedestal activate.");
            return;
        }
        if (!open && wave == null) { p.sendMessage(ChatColor.GRAY + "The Pedestal is already closed."); return; }
        transform(false);
        Bukkit.broadcastMessage(ChatColor.GRAY + "The Pedestal goes quiet.");
    }

    /**
     * Opens (heart first, rim last) or closes (rim first, heart last) over pedestal.transform-seconds, one block at a
     * time with a few particles. The state saved is the one it's going to, so a crash mid-wave comes back finished.
     */
    private void transform(boolean opening) {
        if (wave != null) { wave.cancel(); wave = null; placeAll(waveOpening); } // finish the last one first
        open = opening;
        save();
        waveOpening = opening;
        GemSounds.play(plugin, "other.pedestal-activate", center);
        List<int[]> cells = PedestalLayout.floor();
        boolean[] done = new boolean[cells.size()];
        int ticks = Math.max(1, (int) Math.round(plugin.getConfig().getDouble("pedestal.transform-seconds", 1.5) * 20));
        int[] t = {0};
        wave = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            double progress = Math.min(1.0, t[0] / (double) ticks);
            World w = center.getWorld();
            for (int i = 0; i < cells.size(); i++) {
                if (done[i]) continue;
                int[] c = cells.get(i);
                if (!PedestalLayout.turned(Math.hypot(c[0], c[1]), progress, opening)) continue;
                done[i] = true;
                setFloor(c[0], c[1], opening);
                Location at = center.clone().add(c[0] + 0.5, 1.05, c[1] + 0.5);
                if (opening) {
                    w.spawnParticle(Particle.END_ROD, at, 2, 0.2, 0.05, 0.2, 0.01);
                    w.spawnParticle(Particle.FIREWORK, at, 1, 0.15, 0.05, 0.15, 0.0);
                } else {
                    w.spawnParticle(Particle.SMOKE, at, 3, 0.2, 0.05, 0.2, 0.01);
                    w.spawnParticle(Particle.ASH, at, 4, 0.25, 0.1, 0.25, 0.0);
                }
            }
            if (progress >= 1.0) {
                wave.cancel();
                wave = null;
                for (int[] b : PedestalLayout.base()) at(b[0], b[1], b[2]).setType(Material.matchMaterial(PedestalLayout.baseBlock(b[1])), false);
            }
        }, 1L, 1L);
    }

    public void tick() {
        if (!isOpen()) return;
        // Breathing glow while rites are possible.
        double r = plugin.getConfig().getDouble("pedestal.particle-radius", 1.6);
        for (int i = 0; i < 8; i++) {
            double a = (System.currentTimeMillis() / 900.0) + (Math.PI * 2 * i / 8);
            Location at = center.clone().add(0.5 + Math.cos(a) * r, 1.2, 0.5 + Math.sin(a) * r);
            center.getWorld().spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ repair rites (unchanged)

    /** Repair rite: gem + kit at the open Pedestal. Returns true when handled. */
    public boolean tryRepair(Player p, ItemStack gem, ItemStack inHand) {
        if (gem == null) {
            p.sendMessage(ChatColor.RED + "Hold a gem or keep one in offhand to repair.");
            return true;
        }
        int en = gems.getEnergy(gem);
        if (en <= 0) {
            p.sendMessage(ChatColor.RED + "Broken gems need a Restoration (not a Repair Kit).");
            return true;
        }
        if (en >= 5) {
            p.sendMessage(ChatColor.GRAY + "Already Pristine or higher. No repair needed.");
            return true;
        }
        if (!isOpen() || !nearGem(p, 10)) {
            p.sendMessage(ChatColor.RED + "Repairs happen at the open Pedestal.");
            return true;
        }
        gems.setEnergy(gem, en + 1);
        if (p.getInventory().getItemInOffHand().equals(inHand)) {
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off.getAmount() > 1) off.setAmount(off.getAmount() - 1);
            else p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        } else {
            if (inHand.getAmount() > 1) inHand.setAmount(inHand.getAmount() - 1);
            else p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.5);
        p.sendMessage(ChatColor.GREEN + "The rite restores +1 → " + gems.getEnergy(gem) + "/10 ("
                + EnergyStage.name(gems.getEnergy(gem)) + ")");
        ritualActivity();
        return true;
    }

    private long lastRite;

    private void ritualActivity() {
        lastRite = System.currentTimeMillis();
    }

    private boolean riteActive() {
        return System.currentTimeMillis() - lastRite < 60_000L;
    }

    // ------------------------------------------------------------------ the heart and the guards

    /** One of the build's blocks: the floor (97) or the hidden base. */
    boolean isPart(Block b) {
        if (!exists() || !bliss || !b.getWorld().equals(center.getWorld())) return false;
        int dx = b.getX() - center.getBlockX(), dy = b.getY() - center.getBlockY(), dz = b.getZ() - center.getBlockZ();
        if (dy == 0) return PedestalLayout.part(dx, dz) != null;
        for (int[] o : PedestalLayout.base()) if (o[0] == dx && o[1] == dy && o[2] == dz) return true;
        return false;
    }

    /** The heart while open: the beacon. Closed, it's bedrock and nothing breaks. */
    private boolean isHeart(Block b) {
        return exists() && b.getWorld().equals(center.getWorld())
                && b.getX() == center.getBlockX() && b.getY() == center.getBlockY()
                && b.getZ() == center.getBlockZ() && b.getType() == Material.BEACON;
    }

    /** Everything about the build is unbreakable, except the beacon heart (below). Nothing drops. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProtect(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (isHeart(b) || !isPart(b)) return; // the heart plays by break-attempt rules below
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.GRAY + "The Pedestal does not break.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlastProtect(EntityExplodeEvent e) { e.blockList().removeIf(this::isPart); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBlastProtect(BlockExplodeEvent e) { e.blockList().removeIf(this::isPart); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (isPart(b)) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (isPart(b)) { e.setCancelled(true); return; } }

    /** Endermen, withers, ravagers: nothing takes a block of it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (isPart(e.getBlock())) e.setCancelled(true); }

    /** The cherry parts are flammable: they never burn away. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (isPart(e.getBlock())) e.setCancelled(true); }

    /** No slab doubled onto the rim, no block placed into it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceInto(BlockPlaceEvent e) { if (isPart(e.getBlockPlaced())) e.setCancelled(true); }

    /** No water in the rim's slabs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (isPart(e.getBlockClicked()) || isPart(e.getBlock())) e.setCancelled(true);
    }

    /** Breaking the beacon: orange counter, it comes back, 5/5 ends an active rite. Nothing drops. */
    @EventHandler
    public void onHeartBreak(BlockBreakEvent e) {
        if (!isHeart(e.getBlock())) return;
        e.setCancelled(true);
        PedestalLayout.Break r = PedestalLayout.heartBreak(breaks, riteActive());
        int shown = r.fifth() ? 5 : r.breaks();
        breaks = r.breaks();
        save();
        Location at = e.getBlock().getLocation().add(0.5, 1.5, 0.5);
        for (Entity en : e.getBlock().getWorld().getNearbyEntities(at, 32, 16, 32)) {
            if (en instanceof Player p) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("§6Pedestal break attempt " + shown + "/5"));
            }
        }
        e.getBlock().getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 1, 0.7f);
        if (!r.fifth()) return;
        if (r.closes()) {
            transform(false);
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "The rite is broken. The Pedestal goes quiet.");
        } else {
            Bukkit.broadcastMessage(ChatColor.GOLD + "The Pedestal weathers the assault (5/5).");
        }
    }

    /**
     * Right-clicking the heart: no beacon menu, no effects to set. An axe, shovel or hoe on any part changes nothing
     * (no stripping the cherry wood).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHeartTouch(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (isHeart(b)) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) e.getPlayer().sendMessage(ChatColor.GRAY + "The heart hums with pooled energy.");
            return;
        }
        if (!isPart(b) || e.getItem() == null) return;
        String tool = e.getItem().getType().name();
        if (tool.endsWith("_AXE") || tool.endsWith("_SHOVEL") || tool.endsWith("_HOE")) e.setCancelled(true);
    }
}
