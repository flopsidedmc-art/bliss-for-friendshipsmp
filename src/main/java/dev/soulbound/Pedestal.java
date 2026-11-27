package dev.soulbound;

import java.io.File;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

/**
 * The Pedestal: buildable/closable ritual platform. Repair rites happen here
 * while open. The ancient debris heart can be broken 5 times to end an active
 * rite; everything else about the build is unbreakable (free netherite otherwise).
 */
public class Pedestal implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private final File dataFile;
    private YamlConfiguration data;

    private Location center;
    private boolean open;
    private int breaks;

    public Pedestal(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        this.dataFile = new File(plugin.getDataFolder(), "pedestal.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        load();
    }

    private void save() {
        try {
            if (center != null) {
                data.set("center.world", center.getWorld().getName());
                data.set("center.x", center.getBlockX());
                data.set("center.y", center.getBlockY());
                data.set("center.z", center.getBlockZ());
            } else {
                data.set("center", null);
            }
            data.set("open", open);
            data.set("breaks", breaks);
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
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not load pedestal: " + ex.getMessage());
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

    /** Builds the ring at the sender's feet and opens it. */
    public void activate(Player p) {
        Location base = p.getLocation().getBlock().getLocation();
        World w = base.getWorld();
        int y = base.getBlockY() - 1;
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 81) continue;
                double d = Math.sqrt(d2);
                Material m = d > 7 ? Material.DEEPSLATE_BRICKS
                        : d > 4 ? Material.DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE;
                // Chiseled accents on the diagonals.
                if (Math.abs(Math.abs(dx) - Math.abs(dz)) <= 1 && d > 5 && d <= 7) m = Material.CHISELED_DEEPSLATE;
                w.getBlockAt(base.getBlockX() + dx, y, base.getBlockZ() + dz).setType(m, false);
            }
        }
        // Netherite cardinals (display only — the build is unbreakable).
        w.getBlockAt(base.getBlockX() + 4, y, base.getBlockZ()).setType(Material.NETHERITE_BLOCK, false);
        w.getBlockAt(base.getBlockX() - 4, y, base.getBlockZ()).setType(Material.NETHERITE_BLOCK, false);
        w.getBlockAt(base.getBlockX(), y, base.getBlockZ() + 4).setType(Material.NETHERITE_BLOCK, false);
        w.getBlockAt(base.getBlockX(), y, base.getBlockZ() - 4).setType(Material.NETHERITE_BLOCK, false);
        // Heart: ancient debris with a diamond crown.
        w.getBlockAt(base.getBlockX(), y, base.getBlockZ()).setType(Material.ANCIENT_DEBRIS, false);
        w.getBlockAt(base.getBlockX(), y + 1, base.getBlockZ()).setType(Material.DIAMOND_BLOCK, false);
        center = new Location(w, base.getBlockX(), y, base.getBlockZ());
        open = true;
        breaks = 0;
        save();
        GemSounds.play(plugin, "other.pedestal-activate", center);
        Bukkit.broadcastMessage(ChatColor.GOLD + "The Pedestal rises. Rites may begin.");
    }

    public void deactivate(Player p) {
        if (!exists()) {
            p.sendMessage(ChatColor.GRAY + "No Pedestal exists. Build one with /bliss pedestal activate.");
            return;
        }
        open = false;
        save();
        Bukkit.broadcastMessage(ChatColor.GRAY + "The Pedestal goes quiet.");
    }

    public void tick() {
        if (!isOpen()) return;
        // Breathing glow while rites are possible.
        for (int i = 0; i < 8; i++) {
            double a = (System.currentTimeMillis() / 900.0) + (Math.PI * 2 * i / 8);
            Location at = center.clone().add(Math.cos(a) * 2.5, 1.2, Math.sin(a) * 2.5);
            center.getWorld().spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0);
        }
    }

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

    private boolean isHeart(Block b) {
        return exists() && b.getWorld().equals(center.getWorld())
                && b.getX() == center.getBlockX() && b.getY() == center.getBlockY()
                && b.getZ() == center.getBlockZ() && b.getType() == Material.ANCIENT_DEBRIS;
    }

    private boolean inside(Block b, double radius) {
        return exists() && b.getWorld().equals(center.getWorld())
                && b.getLocation().distanceSquared(center) <= radius * radius;
    }

    /** Everything about the build is unbreakable — except the debris heart. */
    @EventHandler
    public void onProtect(BlockBreakEvent e) {
        if (!exists()) return;
        Block b = e.getBlock();
        if (isHeart(b)) return; // the heart plays by break-attempt rules below
        if (inside(b, 12)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.GRAY + "The Pedestal does not break.");
        }
    }

    @EventHandler
    public void onBlastProtect(EntityExplodeEvent e) {
        if (!exists()) return;
        e.blockList().removeIf(b -> inside(b, 12) && !isHeart(b));
    }

    /** Breaking the debris: orange counter, restores itself, 5/5 ends rites. */
    @EventHandler
    public void onHeartBreak(BlockBreakEvent e) {
        if (!isHeart(e.getBlock())) return;
        e.setCancelled(true);
        breaks++;
        save();
        Location at = e.getBlock().getLocation().add(0.5, 1.5, 0.5);
        for (Entity en : e.getBlock().getWorld().getNearbyEntities(at, 32, 16, 32)) {
            if (en instanceof Player p) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("§6Pedestal break attempt " + Math.min(breaks, 5) + "/5"));
            }
        }
        e.getBlock().getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 1, 0.7f);
        if (breaks >= 5) {
            breaks = 0;
            save();
            if (riteActive()) {
                open = false;
                save();
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "The rite is broken. The Pedestal goes quiet.");
            } else {
                Bukkit.broadcastMessage(ChatColor.GOLD + "The Pedestal weathers the assault (" + breaks + "/5).");
            }
        }
    }

    /** Right-clicking the heart with empty hand does nothing harmful. */
    @EventHandler
    public void onHeartTouch(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null || !isHeart(e.getClickedBlock())) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.getPlayer().sendMessage(ChatColor.GRAY + "The heart hums with pooled energy.");
        }
    }
}
