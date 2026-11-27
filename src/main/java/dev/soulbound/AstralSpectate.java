package dev.soulbound;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Astra's spectator powers. Astral Projection: spectator for projection.seconds, your body (a Mannequin with your skin
 * and armour, or a zombie with your head where there's no Mannequin) left where you stood; you can't go further than
 * projection.max-distance from it, can't possess anything or use portals, and a left-click on a player within
 * projection.tag-range tags them instead. Unbounded: spectator with your camera inside the player you right-clicked,
 * for unbounded.seconds. Every way out goes through end(), which puts back your game mode and spot exactly once
 * (SpectatorSessions); a restore record on disk covers logouts and crashes, so nobody is left in spectator.
 */
final class AstralSpectate implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final SpectatorSessions sessions = new SpectatorSessions();
    private final Map<UUID, LivingEntity> bodies = new HashMap<>();
    private final Map<UUID, UUID> hosts = new HashMap<>(); // Unbounded rider -> the player they're in
    private final Map<UUID, Location> hostLast = new HashMap<>(); // where that player was last tick
    private final Map<UUID, Set<UUID>> tagged = new HashMap<>(); // this projection's tags
    private final File file;
    private final YamlConfiguration records;
    private BukkitTask task;

    AstralSpectate(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "astral-restore.yml");
        this.records = YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (Player p : Bukkit.getOnlinePlayers()) restoreRecord(p); // a reload with someone still out
    }

    /** Plugin disable or server stop: everyone back in their body, in their own game mode. */
    void shutdown() {
        for (UUID id : sessions.ids()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) end(p, SpectatorSessions.End.DISABLE);
            else forget(id);
        }
        if (task != null) task.cancel();
        task = null;
    }

    private double cfgD(String k, double def) { return plugin.getConfig().getDouble("astra." + k, def); }

    boolean projecting(Player p) { return p != null && sessions.in(p.getUniqueId(), SpectatorSessions.Kind.PROJECTION); }

    boolean unbounded(Player p) { return p != null && sessions.in(p.getUniqueId(), SpectatorSessions.Kind.UNBOUNDED); }

    boolean busy(Player p) { return p != null && sessions.any(p.getUniqueId()); }

    // ------------------------------------------------------------------ Astral Projection

    boolean startProjection(Player p) {
        UUID id = p.getUniqueId();
        if (busy(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.isInsideVehicle()) p.leaveVehicle();
        Location home = p.getLocation().clone();
        long ends = System.currentTimeMillis() + (long) (Math.max(1, cfgD("projection.seconds", 10)) * 1000);
        if (!sessions.start(id, SpectatorSessions.Kind.PROJECTION, p.getGameMode(), home, ends)) return false;
        writeRecord(id, home, p.getGameMode());
        LivingEntity body = spawnBody(p, home);
        body.setGravity(false); // it stays exactly where you were, even mid-air
        bodies.put(id, body);
        tagged.put(id, new HashSet<>());
        p.setGameMode(GameMode.SPECTATOR);
        GemSounds.play(plugin, "astra.projection", home);
        return true;
    }

    private LivingEntity spawnBody(Player p, Location at) {
        if (MannequinBody.available()) {
            try { return MannequinBody.spawn(p, at); }
            catch (Throwable t) { plugin.getLogger().warning("Mannequin body failed (" + t + "), using a zombie."); }
        }
        return at.getWorld().spawn(at, Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setAdult();
            z.setCanPickupItems(false);
            z.setShouldBurnInDay(false);
            z.setPersistent(false); // never saved: a crash can't leave a body behind
            z.setRemoveWhenFarAway(false);
            AttributeInstance kb = z.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(1.0);
            dress(z, p, true);
        });
    }

    /** The body wears copies of your armour (the zombie wears your head instead of your helmet). Nothing of yours is touched. */
    static void dress(LivingEntity body, Player p, boolean head) {
        EntityEquipment eq = body.getEquipment();
        if (eq == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack helmet = head ? playerHead(p) : copy(inv.getHelmet());
        eq.setHelmet(helmet);
        eq.setChestplate(copy(inv.getChestplate()));
        eq.setLeggings(copy(inv.getLeggings()));
        eq.setBoots(copy(inv.getBoots()));
        try { // only mobs have drop chances; nothing it wears may ever drop
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
        } catch (RuntimeException ignored) { }
        body.setCustomName(p.getName() + "'s Body");
        body.setCustomNameVisible(true);
    }

    private static ItemStack copy(ItemStack it) { return it == null ? null : it.clone(); }

    private static ItemStack playerHead(Player p) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (skull.getItemMeta() instanceof SkullMeta m) {
            m.setOwningPlayer(p);
            skull.setItemMeta(m);
        }
        return skull;
    }

    /** Left-click on a player while projected (the spectator "possess" click): their gem locks and they're spooked. */
    private void tryTag(Player p, Player o) {
        if (o.equals(p) || o.getGameMode() == GameMode.SPECTATOR || !o.getWorld().equals(p.getWorld())) return;
        if (o.getEyeLocation().distance(p.getEyeLocation()) > cfgD("projection.tag-range", 4)) return;
        Set<UUID> done = tagged.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        int max = (int) cfgD("projection.max-tags", 3);
        if (done.contains(o.getUniqueId()) || done.size() >= max) return;
        if (plugin.listener() != null && plugin.listener().isTrusted(p, o)) return; // like Astral Void: never your own side
        done.add(o.getUniqueId());
        long lock = (long) (cfgD("projection.tag-lock-seconds", 6) * 1000);
        if (plugin.listener() != null && lock > 0) plugin.listener().applyGemDisable(o, lock, "something unseen"); // never who
        GemSounds.playTo(plugin, "astra.tag-spook", o);
        o.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, true, false, false));
        Location face = o.getEyeLocation().add(o.getLocation().getDirection().multiply(0.35));
        o.spawnParticle(Particle.SOUL, face, 12, 0.25, 0.25, 0.25, 0.02);
        o.spawnParticle(Particle.DUST, face, 14, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(0x8E, 0x44, 0xFF), 1.1f));
        p.sendActionBar(Component.text("Tagged " + done.size() + "/" + max, NamedTextColor.LIGHT_PURPLE));
    }

    // ------------------------------------------------------------------ Unbounded

    boolean startUnbounded(Player p, Player host) {
        UUID id = p.getUniqueId();
        if (busy(p) || busy(host) || p.isDead() || host.isDead() || host.getGameMode() == GameMode.SPECTATOR
                || p.getGameMode() == GameMode.SPECTATOR || !host.getWorld().equals(p.getWorld())) return false;
        if (p.isInsideVehicle()) p.leaveVehicle();
        long ends = System.currentTimeMillis() + (long) (Math.max(1, cfgD("unbounded.seconds", 4)) * 1000);
        if (!sessions.start(id, SpectatorSessions.Kind.UNBOUNDED, p.getGameMode(), p.getLocation(), ends)) return false;
        writeRecord(id, p.getLocation(), p.getGameMode());
        hosts.put(id, host.getUniqueId());
        hostLast.put(id, host.getLocation());
        p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(host);
        GemSounds.play(plugin, "astra.unbounded-enter", p.getLocation());
        GemSounds.playTo(plugin, "astra.unbounded-whisper", host); // a subtle cue for them, nothing more
        return true;
    }

    private Player hostOf(UUID rider) {
        UUID h = hosts.get(rider);
        return h == null ? null : Bukkit.getPlayer(h);
    }

    /** Just behind them, facing their back; the nearest safe spot if that one isn't. */
    private Location behind(Player host) {
        Location l = host.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        Location spot = l.clone().subtract(f.normalize().multiply(1.2));
        spot.setYaw(l.getYaw());
        spot.setPitch(0);
        Location safe = safeNear(spot, true);
        if (safe == null) safe = safeNear(spot, false);
        if (safe != null) return safe;
        Location at = l.clone();
        at.setPitch(0);
        return at;
    }

    private static Location safeNear(Location at, boolean ground) {
        for (int r = 0; r <= 2; r++) for (int dy : new int[] {0, 1, -1}) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // this ring only
            Location c = at.clone().add(dx, dy, dz);
            if (safe(c, ground)) return c;
        }
        return null;
    }

    private static boolean safe(Location c, boolean ground) {
        Block feet = c.getBlock(), head = feet.getRelative(BlockFace.UP), below = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (feet.getType() == Material.LAVA || head.getType() == Material.LAVA || below.getType() == Material.LAVA
                || feet.getType() == Material.FIRE || feet.getType() == Material.SOUL_FIRE) return false;
        return !ground || below.getType().isSolid();
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        long now = System.currentTimeMillis();
        double max = cfgD("projection.max-distance", 48);
        for (UUID id : sessions.ids()) {
            Player p = Bukkit.getPlayer(id);
            SpectatorSessions.Session s = sessions.get(id);
            if (p == null || s == null) continue; // a logout is handled by onQuit
            if (p.getGameMode() != GameMode.SPECTATOR) { end(p, SpectatorSessions.End.MODE_CHANGED); continue; }
            // Pulled into a Pantheon event: out at once, in their own game mode, where the event put them.
            if (plugin.inPantheonEvent(p)) { end(p, SpectatorSessions.End.EVENT); continue; }
            if (s.kind() == SpectatorSessions.Kind.PROJECTION) {
                LivingEntity body = bodies.get(id);
                if (body == null || !body.isValid()) { end(p, SpectatorSessions.End.BODY_HIT); continue; }
                if (now >= s.endsAt()) { end(p, SpectatorSessions.End.TIME_UP); continue; }
                if (!p.getWorld().equals(body.getWorld())) { end(p, SpectatorSessions.End.WORLD_CHANGE); continue; }
                Vector back = AstraMath.clamp(body.getLocation().toVector(), p.getLocation().toVector(), max);
                if (back != null) {
                    Location to = back.toLocation(p.getWorld(), p.getLocation().getYaw(), p.getLocation().getPitch());
                    p.teleport(to); // pulled back at the edge
                }
            } else {
                Player host = hostOf(id);
                if (host == null || !host.isOnline() || host.isDead() || !host.getWorld().equals(p.getWorld())) {
                    end(p, SpectatorSessions.End.TARGET_GONE);
                    continue;
                }
                // Their host is in a Pantheon event: back where they started, never into the arena.
                if (plugin.inPantheonEvent(host)) { end(p, SpectatorSessions.End.HOST_EVENT); continue; }
                hostLast.put(id, host.getLocation());
                if (now >= s.endsAt()) { end(p, SpectatorSessions.End.TIME_UP); continue; }
                if (!host.equals(p.getSpectatorTarget())) p.setSpectatorTarget(host);
            }
        }
    }

    // ------------------------------------------------------------------ the end, every way

    /** Back to the body (or out behind the target), in the old game mode, once. */
    void end(Player p, SpectatorSessions.End why) {
        UUID id = p.getUniqueId();
        SpectatorSessions.Session s = sessions.end(id, why);
        if (s == null) return;
        Location behind = null, last = null;
        if (s.kind() == SpectatorSessions.Kind.PROJECTION) {
            LivingEntity body = bodies.remove(id);
            if (body != null && body.isValid()) body.remove();
            tagged.remove(id);
        } else {
            Player host = hostOf(id);
            hosts.remove(id);
            last = hostLast.remove(id);
            if (host != null && host.isOnline() && !host.isDead() && host.getWorld().equals(p.getWorld())
                    && why != SpectatorSessions.End.TARGET_GONE) behind = behind(host);
        }
        Location to = SpectatorSessions.where(s, why, behind, last);
        if (p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
        if (to != null) p.teleport(to);
        if (why != SpectatorSessions.End.MODE_CHANGED) p.setGameMode(s.mode()); // someone gave them another mode: theirs stands
        p.setFallDistance(0);
        if (why == SpectatorSessions.End.LOGOUT && to != null) writeRecord(id, to, s.mode()); // put back again on join
        else clearRecord(id);
        if (s.kind() == SpectatorSessions.Kind.PROJECTION) {
            if (to != null) GemSounds.play(plugin, "astra.projection-end", to);
        } else {
            GemSounds.play(plugin, "astra.unbounded-exit", p.getLocation());
            // no gem disable on the way out any more: the ride is only unbounded.seconds (its own cooldown still applies)
        }
    }

    /** A session whose player is gone without a logout event (shouldn't happen): its body goes, its record stays. */
    private void forget(UUID id) {
        sessions.end(id, SpectatorSessions.End.DISABLE);
        LivingEntity body = bodies.remove(id);
        if (body != null && body.isValid()) body.remove();
        hosts.remove(id);
        hostLast.remove(id);
        tagged.remove(id);
    }

    /** Everyone riding inside {@code host} comes out where they were. */
    private void dropRiders(Player host) {
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(hosts.entrySet())) {
            if (!e.getValue().equals(host.getUniqueId())) continue;
            Player rider = Bukkit.getPlayer(e.getKey());
            hostLast.put(e.getKey(), host.getLocation());
            if (rider != null) end(rider, SpectatorSessions.End.TARGET_GONE);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        dropRiders(p);
        if (busy(p)) end(p, SpectatorSessions.End.LOGOUT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        dropRiders(p);
        if (busy(p)) end(p, SpectatorSessions.End.DEATH);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        dropRiders(p);
        if (busy(p)) end(p, SpectatorSessions.End.WORLD_CHANGE);
    }

    /** Back from a logout or a crash mid-projection: the record puts them back. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && !busy(p)) restoreRecord(p); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && busy(e.getPlayer())) end(e.getPlayer(), SpectatorSessions.End.SNEAK);
    }

    /** No possessing: while projected the click tags a player instead; while Unbounded the camera stays in them. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpectate(PlayerStartSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        if (projecting(p)) {
            e.setCancelled(true);
            if (e.getNewSpectatorTarget() instanceof Player o) tryTag(p, o);
        } else if (unbounded(p)) {
            UUID h = hosts.get(p.getUniqueId());
            if (h == null || !h.equals(e.getNewSpectatorTarget().getUniqueId())) e.setCancelled(true);
        }
    }

    /** Sneaking out of the camera ends Unbounded (and so does the target dying or leaving: the tick sees to that). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        if (!unbounded(p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!unbounded(p)) return;
            Player host = hostOf(p.getUniqueId());
            boolean gone = host == null || !host.isOnline() || host.isDead() || !host.getWorld().equals(p.getWorld());
            end(p, gone ? SpectatorSessions.End.TARGET_GONE : SpectatorSessions.End.SNEAK);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    /** No spectator hotbar teleports and no portals while out. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!busy(e.getPlayer())) return;
        switch (e.getCause()) {
            case SPECTATE, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> e.setCancelled(true);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the body

    private UUID ownerOf(Entity en) {
        for (Map.Entry<UUID, LivingEntity> b : bodies.entrySet()) if (b.getValue().getUniqueId().equals(en.getUniqueId())) return b.getKey();
        return null;
    }

    /**
     * Hits on the body go to you (projection.body-takes-hits): attacks, arrows and explosions. One that would kill you
     * ends the projection first and lands on you in your body. Anything else (falling, drowning ...) can't hurt it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyHit(EntityDamageEvent e) {
        UUID owner = ownerOf(e.getEntity());
        if (owner == null) return;
        double dealt = e.getFinalDamage(), raw = e.getDamage();
        e.setCancelled(true);
        boolean hit = e instanceof EntityDamageByEntityEvent || e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (!hit || !plugin.getConfig().getBoolean("astra.projection.body-takes-hits", true) || dealt <= 0) return;
        Player p = Bukkit.getPlayer(owner);
        if (p == null) return;
        if (e.getEntity() instanceof LivingEntity body) body.playHurtAnimation(0);
        Entity source = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        if (p.getHealth() - dealt <= 0) {
            end(p, SpectatorSessions.End.BODY_HIT); // back in your body, and the hit lands on you there
            if (source != null) p.damage(raw, source);
            else p.damage(raw);
            return;
        }
        p.setHealth(Math.max(0.5, p.getHealth() - dealt));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.8f, 1.0f);
        p.sendActionBar(Component.text("Your body is being hit!", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyBurn(EntityCombustEvent e) {
        if (ownerOf(e.getEntity()) != null) e.setCancelled(true);
    }

    /** A body removed some other way (/kill) drops nothing; the tick ends that projection. */
    @EventHandler
    public void onBodyDeath(EntityDeathEvent e) {
        if (ownerOf(e.getEntity()) == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // ------------------------------------------------------------------ the restore record

    private void writeRecord(UUID id, Location at, GameMode mode) {
        if (at.getWorld() == null) return;
        String k = "players." + id + ".";
        records.set(k + "world", at.getWorld().getName());
        records.set(k + "x", at.getX());
        records.set(k + "y", at.getY());
        records.set(k + "z", at.getZ());
        records.set(k + "yaw", (double) at.getYaw());
        records.set(k + "pitch", (double) at.getPitch());
        records.set(k + "mode", mode.name());
        save();
    }

    private void clearRecord(UUID id) {
        if (!records.contains("players." + id)) return;
        records.set("players." + id, null);
        save();
    }

    private void restoreRecord(Player p) {
        ConfigurationSection c = records.getConfigurationSection("players." + p.getUniqueId());
        if (c == null) return;
        World w = Bukkit.getWorld(c.getString("world", ""));
        GameMode mode;
        try { mode = GameMode.valueOf(c.getString("mode", "SURVIVAL")); } catch (IllegalArgumentException ex) { mode = GameMode.SURVIVAL; }
        if (mode == GameMode.SPECTATOR) mode = GameMode.SURVIVAL;
        if (w != null) p.teleport(new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch")));
        p.setGameMode(mode);
        p.setFallDistance(0);
        clearRecord(p.getUniqueId());
        p.sendMessage(ChatColor.DARK_PURPLE + "You're back in your body.");
    }

    private void save() {
        try { records.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Couldn't save astral-restore.yml: " + ex.getMessage()); }
    }

}
