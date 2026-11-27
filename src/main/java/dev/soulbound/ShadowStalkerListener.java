package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SHADOW STALKER — 1:1 with the showcase (corrected after dev review).
 *
 * It is the PARTICLE TRAIL toward the tracked player, not a mob:
 *  - Red chat message: "Activated Shadow Stalker"
 *  - A ribbon of chunky red cubes arcs from the hunter toward the victim,
 *    refreshed every trail-interval, hugging an elevated arc over terrain
 *    (the red ribbon crossing the mountains at 10:14/10:22).
 *  - "duration based on how many items belonging to that person [you hold],
 *     5 is max, head is max" — provenance-counted duration.
 *  - "when in 100 blocks of the player, the thing stops" — within
 *    stop-distance of the hunter the trail ends (hunt over).
 *
 * The floating five-heart row seen nearby likely belongs to the Wisdom/
 * Balance wisp or Prophet visuals — NOT wired here (deferred with those).
 */
public class ShadowStalkerListener implements Listener, CommandExecutor {

    private final SoulboundGemsPlugin plugin;

    /** hunter -> victim */
    private final Map<UUID, UUID> hunts = new HashMap<>();
    /** hunter -> seconds remaining */
    private final Map<UUID, Integer> remaining = new HashMap<>();
    private BukkitTask ticker;
    private BlockData redCube;

    private NamespacedKey[] ownerKeys() {
        return new NamespacedKey[]{
                new NamespacedKey(plugin, "dropowner"),
                new NamespacedKey(plugin, "drop_owner"),
                new NamespacedKey(plugin, "deathvictim"),
                new NamespacedKey(plugin, "death_victim")
        };
    }

    public ShadowStalkerListener(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        long period = plugin.getConfig().getLong("shadow-stalker.trail-interval-ticks", 20L);
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                tickAll();
            }
        }.runTaskTimer(plugin, period, period);
        try {
            redCube = Material.RED_WOOL.createBlockData();
        } catch (Throwable t) {
            redCube = null; // dust fallback in drawTrail
        }
    }

    // ----- activation --------------------------------------------------------

    public boolean activate(Player hunter, Player victim) {
        if (!plugin.getConfig().getBoolean("shadow-stalker.enabled", true)) return false;
        if (hunts.containsKey(hunter.getUniqueId())) return false;
        if (victim == null || victim == hunter) return false;
        if (victim.getWorld() != hunter.getWorld()) return false;

        int items = countBelongingItems(hunter, victim);
        boolean headMax = headBelongsToVictim(hunter, victim);
        int maxSeconds = plugin.getConfig().getInt("shadow-stalker.max-seconds", 180);
        int perItem = plugin.getConfig().getInt("shadow-stalker.seconds-per-item", 30);
        int base = plugin.getConfig().getInt("shadow-stalker.base-seconds", 30);
        int duration = headMax ? maxSeconds : Math.min(maxSeconds, base + items * perItem);

        hunts.put(hunter.getUniqueId(), victim.getUniqueId());
        remaining.put(hunter.getUniqueId(), duration);
        hunter.sendMessage(Component.text("Activated Shadow Stalker", NamedTextColor.DARK_RED));
        return true;
    }

    // ----- trail rendering ----------------------------------------------------

    private void tickAll() {
        for (UUID hunterId : hunts.keySet().toArray(new UUID[0])) {
            int left = remaining.getOrDefault(hunterId, 0) - 1;
            if (left <= 0) {
                endHunt(hunterId, false);
                continue;
            }
            remaining.put(hunterId, left);
            Player victim = org.bukkit.Bukkit.getPlayer(hunts.get(hunterId));
            Player hunter = org.bukkit.Bukkit.getPlayer(hunterId);
            if (victim == null || hunter == null || victim.getWorld() != hunter.getWorld()) {
                endHunt(hunterId, false);
                continue;
            }
            double stopDistance = plugin.getConfig().getDouble("shadow-stalker.stop-distance", 100.0);
            if (victim.getLocation().distance(hunter.getLocation()) <= stopDistance) {
                endHunt(hunterId, true); // "the thing stops"
                continue;
            }
            drawTrail(hunter, victim);
        }
    }

    /** The red arc ribbon from the hunter toward the tracked player. */
    private void drawTrail(Player hunter, Player victim) {
        World world = hunter.getWorld();
        double arcHeight = plugin.getConfig().getDouble("shadow-stalker.trail-height", 25.0);
        double spacing = plugin.getConfig().getDouble("shadow-stalker.trail-cube-spacing", 2.5);

        Location from = hunter.getLocation().add(0, 1.2, 0);
        Location to = victim.getLocation().add(0, 1.2, 0);
        Vector line = to.toVector().subtract(from.toVector());
        double length = line.length();
        if (length < 2) return;

        // elevated sine arc peaking at arcHeight mid-way
        for (double d = 0; d <= length; d += spacing) {
            double f = d / length;
            Vector step = line.clone().multiply(f);
            Location p = from.clone().add(step);
            p.add(0, StrictMath.sin(f * Math.PI) * arcHeight, 0);
            if (redCube != null) {
                world.spawnParticle(Particle.BLOCK, p, 1, 0.1, 0.1, 0.1, redCube);
            } else {
                world.spawnParticle(Particle.DUST, p, 2, 0.1, 0.1, 0.1, 0,
                        MythicFx.dust(MythicFx.RED_R, MythicFx.RED_G, MythicFx.RED_B, 3.0f));
            }
            // droplets alongside the ribbon
            if (Math.random() < 0.3) {
                world.spawnParticle(Particle.DUST, p.clone().add(0, -0.5, 0), 1, 0.4, 0.4, 0.4, 0,
                        MythicFx.dust(0.55f, 0.02f, 0.02f, 1.4f));
            }
        }
    }

    // ----- provenance counting (rules confirmed in-dev) -------------------------

    private int countBelongingItems(Player hunter, Player victim) {
        int count = 0;
        for (ItemStack item : hunter.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            if (isOwnedBy(item, victim)) count++;
        }
        return count;
    }

    private boolean headBelongsToVictim(Player hunter, Player victim) {
        ItemStack helmet = hunter.getInventory().getHelmet();
        return helmet != null && isOwnedBy(helmet, victim);
    }

    private boolean isOwnedBy(ItemStack item, Player victim) {
        if (item.getItemMeta() == null) return false;
        String victimId = victim.getUniqueId().toString();
        var pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey key : ownerKeys()) {
            String val = pdc.get(key, PersistentDataType.STRING);
            if (victimId.equals(val) || victim.getName().equalsIgnoreCase(val)) return true;
        }
        return false;
    }

    // ----- lifecycle ------------------------------------------------------------

    public void endHunt(UUID hunterId, boolean reached) {
        hunts.remove(hunterId);
        remaining.remove(hunterId);
        Player hunter = org.bukkit.Bukkit.getPlayer(hunterId);
        if (hunter != null) {
            hunter.sendMessage(reached
                    ? Component.text("The Shadow Stalker has gone still.", NamedTextColor.GRAY)
                    : Component.text("The Shadow Stalker lost your scent.", NamedTextColor.DARK_RED));
        }
    }

    @EventHandler
    public void onVictimDeath(PlayerDeathEvent event) {
        UUID victimId = event.getEntity().getUniqueId();
        for (UUID hunterId : hunts.keySet().toArray(new UUID[0])) {
            if (victimId.equals(hunts.get(hunterId))) endHunt(hunterId, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endHunt(event.getPlayer().getUniqueId(), false);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (UUID id : hunts.keySet().toArray(new UUID[0])) {
            endHunt(id, false);
        }
    }

    // ----- command -----------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player hunter)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!hunter.hasPermission("bliss.admin")) { // admins only for now (plugin.yml says so too)
            hunter.sendMessage(Component.text("You lack permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            hunter.sendMessage(Component.text("/stalker <player> — start hunt · /stalker stop — cancel",
                    NamedTextColor.GRAY));
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            endHunt(hunter.getUniqueId(), false);
            return true;
        }
        Player victim = org.bukkit.Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            hunter.sendMessage(Component.text("No such player online.", NamedTextColor.RED));
            return true;
        }
        if (!activate(hunter, victim)) {
            hunter.sendMessage(Component.text("A Shadow Stalker is already active.", NamedTextColor.RED));
        }
        return true;
    }
}
