package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world turmoil (the build-up to the Relay Gem finale).
 *
 * While active the sky is locked to a red sunset (turmoil.redsky.*; the finale's merge scene takes the sky over
 * while it runs and hands it back afterwards):
 *  - passive animals hunt players and bite them;
 *  - at night, hostile mobs (except creepers) wear diamond armor, and every hostile deals extra damage.
 *
 * Nothing here is permanent: /turmoil stop (or the end of the finale) unarmors the mobs it armored.
 */
public final class Turmoil implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey armoredKey;
    private final Set<UUID> overlayOff = new HashSet<>();
    private final Map<UUID, Long> lastBite = new HashMap<>();

    private boolean active;
    private boolean skySuppressed;
    private int overlayFrame;
    private int ticks;
    private BukkitTask task;

    public Turmoil(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.armoredKey = new NamespacedKey(plugin, "turmoil_armored");
    }

    // ------------------------------------------------------------------ config

    private double cfgD(String key, double def) {
        return plugin.getConfig().getDouble("turmoil." + key, def);
    }

    private int cfgI(String key, int def) {
        return plugin.getConfig().getInt("turmoil." + key, def);
    }

    private boolean cfgB(String key, boolean def) {
        return plugin.getConfig().getBoolean("turmoil." + key, def);
    }

    private long cfgL(String key, long def) {
        return plugin.getConfig().getLong("turmoil." + key, def);
    }

    // ------------------------------------------------------------------ red sky

    /** The finale calls this while it owns the sky; when it lets go the red sky comes straight back. */
    public void setSkySuppressed(boolean suppressed) {
        skySuppressed = suppressed;
        if (!suppressed && active) applyRedSkyAll();
    }

    private void applyRedSkyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) applyRedSky(p);
    }

    /** A locked sunset is the reddest sky vanilla can show per player (no resource pack needed). */
    private void applyRedSky(Player p) {
        if (!cfgB("redsky.enabled", true)) return;
        p.setPlayerTime(cfgL("redsky.time", 12800L), false);
        if (cfgB("redsky.rain", false)) p.setPlayerWeather(WeatherType.DOWNFALL);
    }

    /** Optional red haze around the screen edges: the pack's red frames as a camera overlay (CameraOverlay). Off by default. */
    private void overlayTick() {
        if (!cfgB("redsky.overlay", false) || ticks % 4 != 0) return;
        overlayFrame = (overlayFrame + 1) % 6;
        String texture = "blissgems:overlay/red_" + overlayFrame;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (overlayAllowed(p)) plugin.overlay().show(p, texture);
            else plugin.overlay().clear(p);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean isActive() {
        return active;
    }

    public boolean start() {
        if (active) return false;
        active = true;
        ticks = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    public boolean stop() {
        if (!active) return false;
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (World w : Bukkit.getWorlds()) {
            for (Monster m : w.getEntitiesByClass(Monster.class)) unarmor(m);
        }
        lastBite.clear();
        if (!skySuppressed) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.resetPlayerTime();
                if (cfgB("redsky.rain", false)) p.resetPlayerWeather();
                plugin.overlay().clear(p); // the real helmet back
            }
        }
        return true;
    }

    /** Toggle the finale's screen overlay for one player (accessibility). Returns true if the overlay is now on. */
    public boolean toggleOverlay(Player p) {
        if (overlayOff.remove(p.getUniqueId())) return true;
        overlayOff.add(p.getUniqueId());
        plugin.overlay().clear(p);
        return false;
    }

    /** True unless this player switched the finale's screen overlay off. */
    public boolean overlayAllowed(Player p) {
        return !overlayOff.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ main loop (every tick)

    private void tick() {
        ticks++;
        if (!skySuppressed) {
            if (ticks % 20 == 1) applyRedSkyAll();
            overlayTick();
        }
        if (ticks % 10 == 0) passiveHunt();
        if (ticks % 100 == 0) armorSweep();
    }

    // ------------------------------------------------------------------ angry animals

    private void passiveHunt() {
        double range = cfgD("passive.range", 24.0);
        double speed = cfgD("passive.speed", 1.25);
        double damage = cfgD("passive.damage", 4.0);
        long cooldownMs = cfgI("passive.cooldown-ticks", 20) * 50L;
        long now = System.currentTimeMillis();
        if (lastBite.size() > 500) lastBite.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            for (Entity e : p.getNearbyEntities(range, range / 2.0, range)) {
                if (!(e instanceof Animals a) || !a.isValid() || a.isDead()) continue;
                if (a instanceof Tameable t && t.isTamed()) continue;
                if (a.isLeashed() || a.isInsideVehicle()) continue;
                if (!a.getWorld().equals(p.getWorld())) continue;

                double dist = a.getLocation().distance(p.getLocation());
                if (dist <= 1.8) {
                    long last = lastBite.getOrDefault(a.getUniqueId(), 0L);
                    if (now - last >= cooldownMs) {
                        lastBite.put(a.getUniqueId(), now);
                        p.damage(damage, a);
                        p.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, a.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0.0);
                    }
                } else {
                    a.getPathfinder().moveTo(p.getLocation(), speed);
                }
            }
        }
    }

    // ------------------------------------------------------------------ night hostiles

    private boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000L && t < 23000L;
    }

    private void armor(Monster m) {
        if (m instanceof Creeper || m instanceof Boss) return;
        if (m.getPersistentDataContainer().has(armoredKey, PersistentDataType.BYTE)) return;
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;
        eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        m.getPersistentDataContainer().set(armoredKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void unarmor(Monster m) {
        if (!m.getPersistentDataContainer().has(armoredKey, PersistentDataType.BYTE)) return;
        EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            eq.setHelmet(null);
            eq.setChestplate(null);
            eq.setLeggings(null);
            eq.setBoots(null);
        }
        m.getPersistentDataContainer().remove(armoredKey);
    }

    /** Arms mobs near players at night and strips the armor again in daylight. */
    private void armorSweep() {
        if (!cfgB("night.armor", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            boolean night = isNight(w);
            for (Entity e : p.getNearbyEntities(64, 32, 64)) {
                if (!(e instanceof Monster m) || !m.isValid()) continue;
                if (night) armor(m);
                else unarmor(m);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!active || !cfgB("night.armor", true)) return;
        if (e.getEntity() instanceof Monster m && isNight(m.getWorld())) armor(m);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!active || !(e.getEntity() instanceof Player)) return;
        if (!isNight(e.getEntity().getWorld())) return;
        Entity source = e.getDamager();
        if (source instanceof Projectile pr && pr.getShooter() instanceof LivingEntity shooter) source = shooter;
        if (source instanceof Enemy) {
            e.setDamage(e.getDamage() * cfgD("night.damage-multiplier", 2.0));
        }
    }

    // ------------------------------------------------------------------ joins

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastBite.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ the summons

    /** The warden-spawn sound for everyone, then the one-word message. */
    public void broadcastSpawn() {
        String message = plugin.getConfig().getString("turmoil.broadcast.message", "Spawn.");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.MASTER, 1.0f, 1.0f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Component line = Component.text(message, NamedTextColor.DARK_RED, TextDecoration.BOLD);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(line);
                p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.MASTER, 1.0f, 0.8f);
            }
        }, 30L);
    }
}
