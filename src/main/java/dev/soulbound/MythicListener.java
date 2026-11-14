package dev.soulbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Mythic gem implementation. This is an original implementation based on the
 * user-provided wiki captures/footage plus currently documented public behavior.
 * Numbers are deliberately configurable so uncertain values can be tuned.
 */
public final class MythicListener implements Listener {
    private static final String BLOODSAW_TAG = "blisslike_bloodsaw";
    private static final String AURATUS_ANCHOR_TAG = "blisslike_auratus_anchor";

    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private final Map<UUID, long[]> bloodsawSlots = new HashMap<>(); // two independent 5s charges: [A ready-at, B ready-at]
    private final Map<UUID, Long> hereticBloodlinkCooldown = new HashMap<>();
    private final Map<UUID, Long> auratusChainCooldown = new HashMap<>();
    private final Map<UUID, Integer> auratusChainCharges = new HashMap<>();
    private final Map<UUID, Long> auratusAegisCooldown = new HashMap<>();
    private final Map<UUID, Long> aegisUntil = new HashMap<>();
    private final Map<UUID, Boolean> aegisSuccessful = new HashMap<>();
    private final Map<UUID, Location> aegisAnchor = new HashMap<>();
    private final Map<UUID, Long> anchorUntil = new HashMap<>();

    private final Map<UUID, Long> bleedUntil = new HashMap<>();
    private final Map<UUID, Integer> bloodSawHits = new HashMap<>();
    private final Map<UUID, Long> bloodSawComboUntil = new HashMap<>();

    private final Map<UUID, BloodlinkState> bloodlinks = new HashMap<>();
    private final Map<UUID, Integer> bloodlinkSlamCounts = new HashMap<>();
    private final Set<UUID> reflectedDamage = new HashSet<>();
    private final Set<UUID> bloodlinkTransferTargets = new HashSet<>();
    private final Map<UUID, SlamState> auratusSlams = new HashMap<>();
    private final Map<UUID, ItemDisplay> slamDecals = new HashMap<>();
    private final Map<UUID, Double> slamStartY = new HashMap<>();

    private static class SlamFlow {
        boolean air;
        boolean arrived;
        long windowUntil;
        long aimUntil;
        boolean dashing;
        boolean dashAir;
        double peak;
        int dashTicks;
    }

    private final Map<UUID, SlamFlow> slamFlows = new HashMap<>();
    private final Map<UUID, Long> slamGrace = new HashMap<>();

    private void endSlam(UUID id) {
        auratusSlams.remove(id);
        slamFlows.remove(id);
        clearSlamVisual(id);
    }
    private final Map<UUID, List<ItemDisplay>> grappleLinks = new HashMap<>();
    private final Map<UUID, ItemDisplay> grappleHooks = new HashMap<>();
    private final Map<UUID, Boolean> grappleAir = new HashMap<>();
    private final Map<UUID, Double> grappleRope = new HashMap<>();
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, BloodsawState> bloodsaws = new HashMap<>();
    private final Map<UUID, UUID> chainTargets = new HashMap<>(); // wielder → pulled target
    private final Map<UUID, Long> flagpoleFrozen = new HashMap<>(); // frozen player → release time
    private final Map<UUID, String> flagpoleAim = new HashMap<>(); // frozen player → "pitch,yaw"
    private final Map<UUID, Long> lastLeftClick = new HashMap<>();
    private final Map<UUID, Long> lastRightClick = new HashMap<>();

    private record BloodlinkState(Set<UUID> victims, long until) {}
    private record SlamState(Location impact, long startedAt, int hits) {}

    private static final class BloodsawState {
        ArmorStand entity;
        ItemDisplay visual;
        float spin;
        UUID owner;
        Vector velocity;
        long lastHitAt;
        int slot;
        int bounces;
        Set<UUID> hitEntities = new HashSet<>();
        Map<UUID, Long> dragging = new HashMap<>();
        BloodsawState(ArmorStand entity, UUID owner, Vector velocity, long now, int slot) {
            this.entity = entity;
            this.owner = owner;
            this.velocity = velocity;
            this.lastHitAt = now;
            this.slot = slot;
        }
    }

    public MythicListener(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
    }

    public boolean isMythic(ItemStack item) {
        return gems.isGem(item) && gems.getType(item) != null && gems.getType(item).isMythic();
    }

    public boolean hasMythic(Player p) {
        return heldMythic(p) != null;
    }

    private ItemStack heldMythic(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isMythic(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isMythic(off)) return off;
        return null;
    }

    private GemType heldType(Player p) {
        ItemStack gem = heldMythic(p);
        return gem == null ? null : gems.getType(gem);
    }

    /** The mythic type the player is currently holding (main or off hand), or null. */
    public GemType heldMythicType(Player p) {
        return heldType(p);
    }

    public void handleInteract(PlayerInteractEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        Player p = e.getPlayer();
        if (plugin.relay().mainHandIsRelay(p)) return; // Relay owns its HIGHEST-priority input handler
        if (plugin.relay().offHandIsRelay(p) && heldType(p) == GemType.RELAY) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND && e.getAction().isRightClick()) {
            routeRightClick(p);
            e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isLeftClick() && !e.getAction().isRightClick()) return;
        GemType type = heldType(p);
        if (type == null) return;

        if (type == GemType.HERETIC) {
            // Saws only on open-air swings — clicking blocks mines normally.
            if (e.getAction().isRightClick()) { e.setCancelled(true); useBloodlinking(p); }
            else if (e.getAction() == Action.LEFT_CLICK_AIR) { e.setCancelled(true); useBloodsaws(p); }
            return;
        }
        e.setCancelled(true);
        if (type == GemType.AURATUS) {
            long n = now();
            if (e.getAction().isLeftClick()) {
                lastLeftClick.put(p.getUniqueId(), n);
                long lastRight = lastRightClick.getOrDefault(p.getUniqueId(), 0L);
                if (n - lastRight < 100) {
                    useFlagpole(p);
                    return;
                }
                useAuratusChain(p);
            } else if (e.getAction().isRightClick()) {
                lastRightClick.put(p.getUniqueId(), n);
                long lastLeft = lastLeftClick.getOrDefault(p.getUniqueId(), 0L);
                if (n - lastLeft < 100) {
                    useFlagpole(p);
                    return;
                }
                if (p.isSneaking()) {
                    useAegis(p);
                } else {
                    tryExtendedHaulingStrike(p);
                }
            }
        }
    }

    private void routeRightClick(Player p) {
        GemType type = heldType(p);
        if (type == GemType.HERETIC) useBloodsaws(p);
        if (type == GemType.AURATUS) useAuratusChain(p);
    }

    private long now() { return System.currentTimeMillis(); }
    private long secondsToMs(String path, long fallback) {
        return plugin.getConfig().getLong(path, fallback / 1000L) * 1000L;
    }
    private long cooldown(Map<UUID, Long> map, UUID id) {
        return Math.max(0L, map.getOrDefault(id, 0L) - now());
    }

    private void useBloodsaws(Player p) {
        // Two independent charges (slots A and B), each recharging on its own timer.
        long n = now();
        long recharge = plugin.getConfig().getLong("mythics.heretic.bloodsaws.recharge-ms", 5000L);
        long[] slots = bloodsawSlots.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        int slot = -1;
        if (slots[0] <= n) slot = 0;
        else if (slots[1] <= n) slot = 1;
        if (slot < 0) {
            long wait = Math.max(slots[0], slots[1]) - n;
            // Silenced — no chat spam
            return;
        }
        slots[slot] = n + recharge;

        Location eye = p.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        double spacing = plugin.getConfig().getDouble("mythics.heretic.bloodsaws.spawn-spacing", 0.55);
        // Alternate sides so consecutive throws fan out left/right.
        Vector side = right.clone().multiply(slot == 0 ? spacing : -spacing);
        spawnBloodsaw(p, eye.clone().add(side).add(forward.clone().multiply(0.6)), forward, slot);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.45f);
        Sfx.play(plugin, p.getLocation(), "heretic.bloodsaw", 1.0f, 1.0f);
    }

    private void spawnBloodsaw(Player owner, Location at, Vector direction, int slot) {
        World world = at.getWorld();
        if (world == null) return;
        ArmorStand as = world.spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, BLOODSAW_TAG),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        double speed = plugin.getConfig().getDouble("mythics.heretic.bloodsaws.speed", 1.65);
        BloodsawState state = new BloodsawState(as, owner.getUniqueId(), direction.normalize().multiply(speed), now(), slot);
        bloodsaws.put(as.getUniqueId(), state);
        // Pack saw disc, spun at a constant rate in the tick.
        ItemStack disc = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = disc.getItemMeta();
        meta.setCustomModelData(12347);
        disc.setItemMeta(meta);
        state.visual = world.spawn(as.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(disc);
            d.setPersistent(false);
        });
    }

    private void removeSaw(BloodsawState s) {
        if (s.entity != null && s.entity.isValid()) s.entity.remove();
        if (s.visual != null && s.visual.isValid()) s.visual.remove();
    }

    private void tickBloodsaws() {
        long n = now();
        Iterator<Map.Entry<UUID, BloodsawState>> it = bloodsaws.entrySet().iterator();
        while (it.hasNext()) {
            BloodsawState s = it.next().getValue();
            long idleMs = plugin.getConfig().getLong("mythics.heretic.bloodsaws.idle-despawn-ms", 5000L);
            if (!s.entity.isValid() || n - s.lastHitAt >= idleMs) {
                Location loc = s.entity.getLocation();
                // "ding" + firework burst on the way out.
                loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 2.0f);
                loc.getWorld().spawnParticle(Particle.FIREWORK, loc, 30, 0.25, 0.25, 0.25, 0.12);
                removeSaw(s);
                it.remove();
                continue;
            }

            double timeScale = plugin.relay().timeScale(s.entity.getLocation(), s.owner);
            Vector delta = s.velocity.clone().multiply(timeScale);
            double distance = delta.length();
            if (distance <= 1e-6) continue;
            Location from = s.entity.getLocation();
            World world = from.getWorld();
            if (world == null) continue;

            RayTraceResult hit = world.rayTraceBlocks(from, delta.clone().normalize(), distance,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null) {
                Location impact = hit.getHitPosition().toLocation(world);
                BlockFace face = hit.getHitBlockFace();
                Vector normal = face == null ? estimateNormal(s.velocity) : new Vector(face.getModX(), face.getModY(), face.getModZ());
                if (normal.lengthSquared() < 1e-6) normal = estimateNormal(s.velocity);
                normal.normalize();
                Vector incoming = s.velocity.clone().normalize();
                Vector reflected = incoming.clone().subtract(normal.clone().multiply(2.0 * incoming.dot(normal))).normalize()
                        .multiply(s.velocity.length());
                s.velocity = reflected;
                s.bounces++;
                int maxBounces = plugin.getConfig().getInt("mythics.heretic.bloodsaws.max-bounces", -1);
                if (maxBounces >= 0 && s.bounces > maxBounces) {
                    impact.getWorld().spawnParticle(Particle.FIREWORK, impact, 10, 0.15, 0.15, 0.15, 0.08);
                    removeSaw(s);
                        it.remove();
                    continue;
                }
                impact.add(s.velocity.clone().normalize().multiply(0.08));
                s.entity.teleport(impact);
                world.spawnParticle(Particle.ENCHANTED_HIT, impact, 8, 0.12, 0.12, 0.12, 0.01);
            } else {
                s.entity.teleport(from.clone().add(delta));
            }
            breakCobwebsAround(s.entity.getLocation());

            // Drag cut victims along in the wake: velocity pull toward the saw.
            if (!s.dragging.isEmpty()) {
                Location sawLoc = s.entity.getLocation();
                s.dragging.entrySet().removeIf(entry -> {
                    if (entry.getValue() <= n) return true;
                    Entity victim = Bukkit.getEntity(entry.getKey());
                    if (victim == null || !victim.isValid()) return true;
                    Vector pull = sawLoc.toVector().subtract(victim.getLocation().toVector());
                    pull.setY(pull.getY() * 0.6 + 0.15);
                    double dist = pull.length();
                    if (dist > 0.4) {
                        victim.setVelocity(pull.normalize().multiply(Math.min(2.5, 0.7 + dist * 0.35) * timeScale));
                        victim.setFallDistance(0);
                    } else {
                        victim.setVelocity(new Vector(0, 0, 0));
                    }
                    return false;
                });
            }

            Location loc = s.entity.getLocation();
            // Constant-speed saw spin.
            if (s.visual != null && s.visual.isValid()) {
                s.spin += (float) (25.0 * timeScale);
                s.visual.teleport(loc);
                s.visual.setRotation(s.spin, 0);
            }
            spawnBloodTrail(loc, s.velocity);
            hitBloodsawEntities(s);
        }
    }

    private void breakCobwebsAround(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (b.getType() == Material.COBWEB) {
                        b.setType(Material.AIR, false);
                        w.spawnParticle(Particle.SWEEP_ATTACK, b.getLocation().add(0.5, 0.5, 0.5),
                                2, 0.2, 0.2, 0.2, 0);
                    }
                }
            }
        }
    }

    private Vector estimateNormal(Vector incoming) {
        Vector abs = new Vector(Math.abs(incoming.getX()), Math.abs(incoming.getY()), Math.abs(incoming.getZ()));
        if (abs.getY() >= abs.getX() && abs.getY() >= abs.getZ()) return new Vector(0, incoming.getY() > 0 ? -1 : 1, 0);
        if (abs.getX() >= abs.getZ()) return new Vector(incoming.getX() > 0 ? -1 : 1, 0, 0);
        return new Vector(0, 0, incoming.getZ() > 0 ? -1 : 1);
    }

    private void spawnBloodTrail(Location loc, Vector velocity) {
        World w = loc.getWorld();
        if (w == null) return;
        Vector back = velocity.clone().normalize().multiply(-0.22);
        Location trail = loc.clone().add(back);
        w.spawnParticle(Particle.DUST, trail, 5, 0.12, 0.12, 0.12,
                new Particle.DustOptions(Color.fromRGB(165, 0, 25), 1.25f));
    }

    private void hitBloodsawEntities(BloodsawState s) {
        Location loc = s.entity.getLocation();
        double radius = plugin.getConfig().getDouble("mythics.heretic.bloodsaws.hit-radius", 0.8);
        for (Entity target : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!(target instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(s.owner)) continue;
            if (s.hitEntities.contains(le.getUniqueId())) continue;
            s.hitEntities.add(le.getUniqueId());
            s.lastHitAt = now();
            // Cut victims get dragged along in the wake briefly.
            long dragMs = plugin.getConfig().getLong("mythics.heretic.bloodsaws.drag-ticks", 20L) * 50L;
            s.dragging.put(le.getUniqueId(), now() + dragMs);
            loc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, loc, 2, 0.3, 0.3, 0.3, 0);
            double damage = plugin.getConfig().getDouble("mythics.heretic.bloodsaws.damage", 5.0);
            Player source = Bukkit.getPlayer(s.owner);
            if (source != null) le.damage(damage, source);
            else le.damage(damage);
            applyBleed(le);
            registerBloodsawHit(s.owner, loc);
        }
    }

    private void registerBloodsawHit(UUID owner, Location loc) {
        int hits = bloodSawHits.getOrDefault(owner, 0) + 1;
        long window = plugin.getConfig().getLong("mythics.heretic.bloodsaws.spore-window-ms", 650L);
        long until = bloodSawComboUntil.getOrDefault(owner, 0L);
        if (until < now()) {
            hits = 1;
        }
        bloodSawHits.put(owner, Math.min(hits, 2));
        bloodSawComboUntil.put(owner, now() + window);
        if (hits >= 2) {
            bloodSawHits.put(owner, 0);
            spawnSpore(owner, loc);
        }
    }

    private void spawnSpore(UUID owner, Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        double radius = plugin.getConfig().getDouble("mythics.heretic.spore.radius", 4.0);
        long duration = plugin.getConfig().getLong("mythics.heretic.spore.duration-ms", 8000L);
        w.spawnParticle(Particle.CRIMSON_SPORE, loc.clone().add(0, 0.2, 0), 90,
                radius * 0.45, 0.5, radius * 0.45, 0.01);
        w.spawnParticle(Particle.DUST, loc.clone().add(0, 0.25, 0), 60,
                radius * 0.45, 0.3, radius * 0.45,
                new Particle.DustOptions(Color.fromRGB(180, 0, 20), 1.8f));
        w.playSound(loc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 0.6f);
        Sfx.play(plugin, loc, "heretic.spore", 0.9f, 1.0f);
        final BukkitTask[] task = new BukkitTask[1];
        final long endAt = now() + duration;
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (now() >= endAt || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                task[0].cancel();
                return;
            }
            w.spawnParticle(Particle.DUST, loc.clone().add(0, 0.35, 0), 12,
                    radius, 0.25, radius,
                    new Particle.DustOptions(Color.fromRGB(190, 0, 20), 1.1f));
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= radius * radius) applyBleed(p);
            }
        }, 1L, 4L);
    }

    private void applyBleed(LivingEntity le) {
        long ms = plugin.getConfig().getLong("mythics.heretic.spore.bleed-ms", 8000L);
        bleedUntil.put(le.getUniqueId(), now() + ms);
        le.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, le.getLocation().add(0, 1.1, 0), 3, 0.15, 0.2, 0.15, 0.1);
    }

    private void useBloodlinking(Player p) {
        if (cooldown(hereticBloodlinkCooldown, p.getUniqueId()) > 0) {
            // Silenced — no chat spam
            return;
        }
        long cd = secondsToMs("mythics.heretic.bloodlinking.cooldown-seconds", 100_000L);
        hereticBloodlinkCooldown.put(p.getUniqueId(), now() + cd);
        p.setVelocity(new Vector(0, plugin.getConfig().getDouble("mythics.heretic.bloodlinking.launch-y", 1.35), 0));
        bloodlinks.put(p.getUniqueId(), new BloodlinkState(new HashSet<>(), now() + 10_000L));
        bloodlinkSlamCounts.put(p.getUniqueId(), 0);
        Sfx.play(plugin, p.getLocation(), "heretic.bloodlink", 1.0f, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> beginBloodlinkDive(p), 14L);
    }

    private void beginBloodlinkDive(Player p) {
        if (!p.isOnline()) return;
        BloodlinkState state = bloodlinks.get(p.getUniqueId());
        if (state == null) return;
        Location target = p.getTargetBlockExact(30) == null ? p.getLocation() : p.getTargetBlockExact(30).getLocation().add(0.5, 1, 0.5);
        Vector to = target.toVector().subtract(p.getLocation().toVector());
        if (to.lengthSquared() < 1) to = p.getLocation().getDirection().normalize().multiply(8);
        to.normalize().multiply(plugin.getConfig().getDouble("mythics.heretic.bloodlinking.dive-speed", 1.3));
        p.setVelocity(to);
        scheduleBloodlinkLand(p);
    }

    private void scheduleBloodlinkLand(Player p) {
        final int[] ticks = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { task[0].cancel(); return; }
            ticks[0]++;
            if (p.isOnGround() && ticks[0] > 2 || ticks[0] > 40) {
                task[0].cancel();
                doBloodlinkSlam(p);
            }
        }, 1L, 1L);
    }

    private void doBloodlinkSlam(Player p) {
        BloodlinkState state = bloodlinks.get(p.getUniqueId());
        if (state == null) return;
        Set<UUID> victims = new HashSet<>(state.victims());
        int radius = plugin.getConfig().getInt("mythics.heretic.bloodlinking.radius", 4);
        double damage = plugin.getConfig().getDouble("mythics.heretic.bloodlinking.damage", 6.0);
        for (Entity e : p.getNearbyEntities(radius, 3, radius)) {
            if (!(e instanceof Player victim) || victim.getUniqueId().equals(p.getUniqueId())) continue;
            int maxVictims = plugin.getConfig().getInt("mythics.heretic.bloodlinking.max-victims", 2);
            if (victims.size() < maxVictims || victims.contains(victim.getUniqueId())) {
                victims.add(victim.getUniqueId());
            }
            victim.damage(damage, p);
            victim.setVelocity(new Vector(0, 0.6, 0));
        }
        int slamCount = bloodlinkSlamCounts.getOrDefault(p.getUniqueId(), 0) + 1;
        bloodlinkSlamCounts.put(p.getUniqueId(), slamCount);
        p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 2, 0.2, 0.1, 0.2, 0.02);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 45, radius * 0.45, 0.25, radius * 0.45,
                new Particle.DustOptions(Color.fromRGB(150, 0, 20), 1.6f));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.5f);
        Sfx.play(plugin, p.getLocation(), "heretic.slam", 1.0f, 1.0f);
        if (victims.size() > 0 && slamCount < plugin.getConfig().getInt("mythics.heretic.bloodlinking.max-slams", 3)) {
            bloodlinks.put(p.getUniqueId(), new BloodlinkState(victims, now() + 10_000L));
            p.setVelocity(new Vector(0, 0.95, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> beginBloodlinkDive(p), 8L);
        } else {
            bloodlinks.remove(p.getUniqueId());
            bloodlinkSlamCounts.remove(p.getUniqueId());
        }
    }

    private void useAegis(Player p) {
        if (cooldown(auratusAegisCooldown, p.getUniqueId()) > 0) {
            // Silenced — no chat spam
            return;
        }
        long cd = secondsToMs("mythics.auratus.aegis.cooldown-seconds", 15_000L);
        auratusAegisCooldown.put(p.getUniqueId(), now() + cd);
        long window = plugin.getConfig().getLong("mythics.auratus.aegis.window-ms", 1250L);
        aegisUntil.put(p.getUniqueId(), now() + window);
        aegisSuccessful.put(p.getUniqueId(), false);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.55f);
        Sfx.play(plugin, p.getLocation(), "auratus.aegis", 1.0f, 1.0f);
        // Pre-parry buff: heal 2-3 hearts and grant Speed 3 + Weaving on successful parry
        // (applied in onDamage when aegisSuccessfully parries)

        // Parry-launch: if this player is being pulled by an Auratus chain, the parry
        // breaks the pull and launches them high into the sky for 6 hearts total
        // (3.5 from the chain break + 2.5 from the parry burst).
        UUID id = p.getUniqueId();
        if (grappleRope.containsKey(id)) {
            // Find the wielder who pulled this player
            for (Map.Entry<UUID, UUID> entry : chainTargets.entrySet()) {
                if (entry.getValue().equals(id)) {
                    Player wielder = Bukkit.getPlayer(entry.getKey());
                    if (wielder != null && wielder.isOnline()) {
                        // Cancel the grapple for the wielder
                        cancelGrapple(entry.getKey());
                        // Launch the victim skyward
                        p.setVelocity(new Vector(0, 1.8, 0));
                        p.setFallDistance(0);
                        // 3.5 hearts from chain break
                        p.damage(7.0, wielder);
                        // 2.5 hearts from parry burst
                        p.damage(5.0, wielder);
                        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.08);
                        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);
                        wielder.sendMessage(ChatColor.GOLD + p.getName() + " parried your chain! Launched skyward.");
                    }
                    break;
                }
            }
        }
    }

    private void useAuratusChain(Player p) {
        UUID id = p.getUniqueId();
        int maxCharges = plugin.getConfig().getInt("mythics.auratus.chain.charges", 2);
        int charges = auratusChainCharges.computeIfAbsent(id, k -> maxCharges);
        refillAuratusCharges(p, maxCharges);
        charges = auratusChainCharges.getOrDefault(id, maxCharges);
        Location anchor = activeAnchor(p);
        if (anchor != null) {
            beginGrapple(p, anchor, null, false, true);
            return;
        }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        // Steep downward looks arm the slam and cost no charge; shallow looks grapple normally.
        double steepDeg = plugin.getConfig().getDouble("mythics.auratus.slam.steep-degrees", 65.0);
        double pitchDown = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -dir.getY()))));
        boolean slamShot = pitchDown >= steepDeg;
        if (!slamShot) {
            if (charges <= 0) {
                return;
            }
            auratusChainCharges.put(id, charges - 1);
            if (charges == maxCharges) {
                auratusChainCooldown.put(id, now() + plugin.getConfig().getLong("mythics.auratus.chain.regen-ms", 8000L));
            }
        }
        double range = plugin.getConfig().getDouble("mythics.auratus.chain.range", 48.0);
        RayTraceResult entityHit = p.getWorld().rayTraceEntities(eye, dir, range, 0.35,
                entity -> entity != p && entity instanceof LivingEntity);
        RayTraceResult blockHit = p.getWorld().rayTraceBlocks(eye, dir, range, org.bukkit.FluidCollisionMode.NEVER, true);
        double ed = entityHit == null ? Double.MAX_VALUE : eye.distance(entityHit.getHitPosition().toLocation(eye.getWorld()));
        double bd = blockHit == null ? Double.MAX_VALUE : eye.distance(blockHit.getHitPosition().toLocation(eye.getWorld()));
        if (ed == Double.MAX_VALUE && bd == Double.MAX_VALUE) {
            return;
        }
        if (ed < bd && entityHit.getHitEntity() instanceof LivingEntity le) {
            beginGrapple(p, le.getLocation(), le, slamShot, false);
        } else {
            Location hit = blockHit.getHitPosition().toLocation(eye.getWorld());
            beginGrapple(p, hit, null, slamShot, false);
        }
    }

    private void refillAuratusCharges(Player p, int max) {
        UUID id = p.getUniqueId();
        long readyAt = auratusChainCooldown.getOrDefault(id, 0L);
        if (readyAt > 0 && readyAt <= now()) {
            int current = auratusChainCharges.getOrDefault(id, 0);
            if (current < max) {
                auratusChainCharges.put(id, Math.min(max, current + 1));
                long regen = plugin.getConfig().getLong("mythics.auratus.chain.regen-ms", 8000L);
                auratusChainCooldown.put(id, now() + regen);
            } else {
                auratusChainCooldown.remove(id);
            }
        }
    }

    private Location activeAnchor(Player p) {
        Long until = anchorUntil.get(p.getUniqueId());
        Location loc = aegisAnchor.get(p.getUniqueId());
        if (loc == null || until == null || until <= now()) {
            aegisAnchor.remove(p.getUniqueId());
            anchorUntil.remove(p.getUniqueId());
            return null;
        }
        return loc;
    }

    private void beginGrapple(Player p, Location target, LivingEntity entity, boolean slam, boolean anchor) {
        if (!slam) Sfx.play(plugin, p.getLocation(), "auratus.chain", 0.9f, 1.0f);
        final Location start = p.getLocation().clone();
        final UUID targetId = entity == null ? null : entity.getUniqueId();
        final long maxTicks = plugin.getConfig().getLong("mythics.auratus.chain.max-flight-ticks", 28L);
        final double speed = plugin.getConfig().getDouble("mythics.auratus.chain.fly-speed", 0.3);
        final int[] tick = {0};
        final double[] timeBudget = {0};
        final long firedAt = now();
        final UUID id = p.getUniqueId();
        final Vector[] carry = {new Vector()}; // last pull velocity, so arrival keeps momentum instead of stopping dead
        grappleRope.put(id, Math.max(2.5, start.distance(target)));
        if (targetId != null) chainTargets.put(id, targetId);
        if (slam && !anchor) {
            auratusSlams.put(id, new SlamState(target.clone(), firedAt, 0));
            slamFlows.put(id, new SlamFlow());
            slamGrace.put(id, now() + 20000L);
            spawnSlamDecal(p, target);
            slamStartY.put(id, p.getLocation().getY());
            p.setVelocity(p.getVelocity().add(new Vector(0, 0.55, 0)));
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline()) { taskCancel(task); clearGrappleVisual(id); endSlam(id); dropGrappleState(id); return; }
            if (p.isDead() || !p.getWorld().equals(target.getWorld())) {
                taskCancel(task); clearGrappleVisual(id); endSlam(id); dropGrappleState(id); return;
            }
            double timeScale = Math.min(plugin.relay().timeScale(p.getLocation(), id), plugin.relay().timeScale(target, id));
            timeBudget[0] += timeScale;
            if (timeBudget[0] >= 1) { tick[0]++; timeBudget[0] -= 1; }
            Location nowLoc = p.getLocation();
            Location actualTarget = target;
            if (targetId != null) {
                Entity e = Bukkit.getEntity(targetId);
                if (!(e instanceof LivingEntity le) || !le.isValid() || !le.getWorld().equals(p.getWorld())) { taskCancel(task); clearGrappleVisual(id); endSlam(id); dropGrappleState(id); return; }
                actualTarget = le.getLocation().clone().add(0, 0.9, 0);
            }
            if (slam && !anchor) {
                tickSlamDive(p, id, task, tick[0], nowLoc, actualTarget, targetId, firedAt, maxTicks, speed);
                return;
            }
            Location hand = nowLoc.clone().add(0, 1.2, 0);
            Location tip = actualTarget.clone().add(0, 0.5, 0);
            if (plugin.getConfig().getBoolean("mythics.auratus.chain.show-models", true)) {
                updateGrappleVisual(p, hand, tip);
            } else {
                drawChain(hand, tip, anchor);
            }
            double dist = nowLoc.distance(actualTarget);
            // Touchdown ends the pull (must have left the ground first).
            if (!p.isOnGround()) grappleAir.put(id, true);
            if (p.isOnGround() && grappleAir.getOrDefault(id, false)) {
                taskCancel(task);
                clearGrappleVisual(id);
                grappleAir.remove(id);
                grappleRope.remove(id);
                lastPos.remove(id);
                return;
            }
            if (dist <= 1.8 || tick[0] >= maxTicks) {
                taskCancel(task);
                clearGrappleVisual(p.getUniqueId());
                grappleAir.remove(id);
                grappleRope.remove(id);
                lastPos.remove(id);
                if (targetId != null) {
                    Entity ent = Bukkit.getEntity(targetId);
                    if (ent instanceof LivingEntity le) {
                        double dmg = plugin.getConfig().getDouble("mythics.auratus.chain.entity-damage", 4.0);
                        le.damage(dmg, p);
                        Vector pull = p.getLocation().toVector().subtract(le.getLocation().toVector()).normalize();
                        le.setVelocity(pull.multiply(0.8 * timeScale));
                    }
                    p.setVelocity(carry[0].clone().multiply(0.3 * timeScale));
                } else {
                    // Arrived (or ran out of chain): carry some of the pull's momentum, no dead stop.
                    p.setVelocity(carry[0].clone().multiply(0.45 * timeScale));
                }
            } else {
                // Straight-line pull: velocity is set directly toward the anchor each tick at a
                // smoothly ramping speed. No sideways drift, no acceleration stacking — the
                // player moves in a clean straight line from start to target.
                double maxSpeed = plugin.getConfig().getDouble("mythics.auratus.chain.pull-max-speed", 1.1);
                Location cur = p.getLocation();
                Vector toward = actualTarget.toVector().subtract(cur.toVector().add(new Vector(0, 0.9, 0)));
                if (toward.lengthSquared() > 1.0E-6) toward.normalize();
                // Ramp up over the first few ticks for a smooth launch, then hold top speed.
                double ramp = Math.min(1.0, tick[0] / 5.0);
                double spd = maxSpeed * ramp;
                if (tick[0] == 1 && p.isOnGround()) {
                    // Lift off the ground on the first tick.
                    toward.setY(Math.max(toward.getY(), 0.25));
                    toward.normalize();
                }
                Vector pullV = toward.multiply(spd);
                carry[0] = pullV.clone();
                p.setVelocity(pullV.clone().multiply(timeScale));
                p.setFallDistance(0); // otherwise long hauls stack up fall damage
            }
        }, 1L, 1L);
    }

    private void taskCancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private void dropGrappleState(UUID id) {
        grappleAir.remove(id);
        grappleRope.remove(id);
        lastPos.remove(id);
        chainTargets.remove(id);
    }

    private void cancelGrapple(UUID id) {
        // Force-end the grapple task by setting rope to 0 so the tick loop exits on next run.
        grappleRope.remove(id);
        clearGrappleVisual(id);
        dropGrappleState(id);
    }

    /**
     * Flagpole: simultaneous left+right click with Auratus freezes the target in the sky
     * for 5 seconds. A spiral of firework rocket particles spirals up from the ground to
     * the player in a circular pattern, and the player bobs up and down. This leaves an
     * open gap for the wielder to chain again.
     */
    private void useFlagpole(Player p) {
        UUID id = p.getUniqueId();
        UUID targetId = chainTargets.get(id);
        if (targetId == null) {
            // Silenced — no chat spam
            return;
        }
        Entity ent = Bukkit.getEntity(targetId);
        if (!(ent instanceof LivingEntity target) || !target.isValid()) {
            // Silenced — no chat spam
            return;
        }
        long duration = 5000L;
        flagpoleFrozen.put(targetId, now() + duration);
        flagpoleAim.put(targetId, target.getLocation().getPitch() + "," + target.getLocation().getYaw());
        // Apply strong levitation to reach ~40 blocks up (amplifier 4 for high lift)
        target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, (int) (duration / 50) + 1, 4, true, false, false));
        target.setVelocity(new Vector(0, 0, 0));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 0.9f);
        // Silenced — no chat spam
    }

    private void tickFlagpole() {
        long n = now();
        flagpoleFrozen.entrySet().removeIf(entry -> {
            UUID id = entry.getKey();
            long releaseAt = entry.getValue();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || n >= releaseAt) {
                if (p != null && p.isOnline()) {
                    flagpoleAim.remove(id);
                }
                return true;
            }
            // Freeze aim: force pitch/yaw to stay fixed
            String aim = flagpoleAim.get(id);
            if (aim != null) {
                String[] parts = aim.split(",");
                if (parts.length == 2) {
                    float fixedPitch = Float.parseFloat(parts[0]);
                    float fixedYaw = Float.parseFloat(parts[1]);
                    Location loc = p.getLocation();
                    loc.setPitch(fixedPitch);
                    loc.setYaw(fixedYaw);
                    p.teleport(loc); // teleport to apply rotation without moving
                }
            }
            // Bob up and down with gentle vertical motion
            double bob = Math.sin(n / 150.0) * 0.25;
            p.setVelocity(p.getVelocity().setY(bob));
            // Spiral of firework rocket particles from ground to player
            double angle = n / 120.0;
            double radius = 1.5;
            Location base = p.getLocation().clone().add(0, -1, 0);
            for (int i = 0; i < 8; i++) {
                double frac = i / 8.0;
                double a = angle + frac * Math.PI * 4;
                Location spiral = base.clone().add(
                        Math.cos(a) * radius,
                        frac * 2.0,
                        Math.sin(a) * radius
                );
                p.getWorld().spawnParticle(Particle.FIREWORK, spiral, 2, 0.02, 0.02, 0.02, 0.01);
            }
            return false;
        });
    }

    private ItemStack modelShard(int cmd) {
        ItemStack shard = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = shard.getItemMeta();
        meta.setCustomModelData(cmd);
        shard.setItemMeta(meta);
        return shard;
    }

    /** Chain links + hook head along hand->target, per the player's own models. */
    private void updateGrappleVisual(Player p, Location from, Location to) {
        UUID id = p.getUniqueId();
        World w = from.getWorld();
        if (w == null) return;
        Vector delta = to.toVector().subtract(from.toVector());
        double len = delta.length();
        if (len < 0.5) { clearGrappleVisual(id); return; }
        Vector dir = delta.normalize();
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, -1),
                new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
        double spacing = plugin.getConfig().getDouble("mythics.auratus.chain.link-spacing", 1.0);
        if (spacing < 0.2) spacing = 0.2;
        float linkScale = (float) plugin.getConfig().getDouble("mythics.auratus.chain.link-scale", 1.0);
        Transformation pose = new Transformation(new Vector3f(), rot,
                new Vector3f(linkScale, linkScale, linkScale), new Quaternionf());
        int n = Math.max(1, Math.min(60, (int) Math.ceil(len / spacing)));
        List<ItemDisplay> links = grappleLinks.computeIfAbsent(id, k -> new ArrayList<>());
        while (links.size() < n) {
            links.add(w.spawn(from, ItemDisplay.class, d -> {
                d.setItemStack(modelShard(12348));
                d.setPersistent(false);
            }));
        }
        while (links.size() > n) links.remove(links.size() - 1).remove();
        for (int i = 0; i < n; i++) {
            ItemDisplay link = links.get(i);
            if (!link.isValid()) continue;
            link.teleport(from.clone().add(dir.clone().multiply(len * (i + 0.5) / n)));
            link.setTransformation(pose);
        }
        ItemDisplay hook = grappleHooks.get(id);
        if (hook == null || !hook.isValid()) {
            hook = w.spawn(to, ItemDisplay.class, d -> {
                d.setItemStack(modelShard(12349));
                d.setPersistent(false);
            });
            grappleHooks.put(id, hook);
        }
        hook.teleport(to);
        hook.setTransformation(pose);
    }

    private void clearGrappleVisual(UUID id) {
        List<ItemDisplay> links = grappleLinks.remove(id);
        if (links != null) for (ItemDisplay link : links) if (link.isValid()) link.remove();
        ItemDisplay hook = grappleHooks.remove(id);
        if (hook != null && hook.isValid()) hook.remove();
    }

    private void drawChain(Location from, Location to, boolean anchor) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 0.1) return;
        Vector dir = delta.normalize();
        int points = Math.max(2, Math.min(64, (int) (length * 2)));
        for (int i = 0; i < points; i++) {
            Location at = from.clone().add(dir.clone().multiply(length * i / points));
            at.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0,
                    new Particle.DustOptions(anchor ? Color.fromRGB(255, 225, 80) : Color.fromRGB(210, 165, 60), 1.0f));
        }
    }

    private void spawnSlamDecal(Player p, Location target) {
        ItemStack smash = new ItemStack(Material.ECHO_SHARD);
        ItemMeta smashMeta = smash.getItemMeta();
        smashMeta.setCustomModelData(12345);
        smash.setItemMeta(smashMeta);
        Location decalLoc = target.clone().add(0, 0.15, 0);
        ItemDisplay decal = p.getWorld().spawn(decalLoc, ItemDisplay.class, d -> {
            d.setItemStack(smash);
            d.setPersistent(false);
        });
        decal.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(0.3f, 0.3f, 0.3f), new Quaternionf()));
        slamDecals.put(p.getUniqueId(), decal);
    }

    private void growSlamDecal(Player p, UUID id, Location target) {
        ItemDisplay dec = slamDecals.get(id);
        Double startY = slamStartY.get(id);
        if (dec == null || !dec.isValid() || startY == null) return;
        double maxScale = plugin.getConfig().getDouble("mythics.auratus.slam.decal-size", 3.0);
        double total = Math.max(1.0, startY - target.getY());
        double remain = Math.max(0.0, p.getLocation().getY() - target.getY());
        float scale = (float) (0.3 + (maxScale - 0.3) * (1.0 - Math.min(1.0, remain / total)));
        dec.teleport(target.clone().add(0, 0.15, 0));
        dec.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    /** Slam dive: touchdown opens a sneak window, sneak starts a 5-tick aim, then dash. */
    private void tickSlamDive(Player p, UUID id, BukkitTask task, int t, Location nowLoc,
            Location target, UUID targetId, long firedAt, long maxTicks, double speed) {
        SlamFlow f = slamFlows.get(id);
        long n = now();
        if (f == null || n - firedAt > 15000L) {
            taskCancel(task);
            clearGrappleVisual(id);
            endSlam(id);
            return;
        }
        if (f.dashing) {
            f.dashTicks++;
            f.peak = Math.max(f.peak, p.getLocation().getY());
            if (!p.isOnGround()) {
                f.dashAir = true;
            } else if (f.dashAir || f.dashTicks > 40) {
                taskCancel(task);
                clearGrappleVisual(id);
                endSlam(id);
                double minHeight = plugin.getConfig().getDouble("mythics.auratus.slam.min-height", 4.0);
                // No 7-8 block fall, no slam.
                if (f.dashAir && f.peak - p.getLocation().getY() >= minHeight) {
                    doAuratusSlam(p, p.getLocation().clone());
                }
                return;
            }
            return;
        }
        if (f.aimUntil > 0) {
            p.setVelocity(new Vector(0, 0, 0));
            if (n >= f.aimUntil) {
                Vector d = p.getEyeLocation().getDirection().clone();
                d.setY(Math.max(-0.15, Math.min(0.35, d.getY())));
                double dashSpeed = plugin.getConfig().getDouble("mythics.auratus.slam.dash-speed", 1.9);
                p.setVelocity(d.normalize().multiply(dashSpeed * plugin.relay().timeScale(p.getLocation(), id)));
                slamGrace.put(p.getUniqueId(), n + 8000L);
                f.aimUntil = 0;
                f.dashing = true;
                f.dashAir = false;
                f.dashTicks = 0;
                f.peak = p.getLocation().getY();
            }
            return;
        }
        // Dive toward the marked point.
        Vector toward = target.toVector().subtract(nowLoc.toVector());
        double dist = toward.length();
        if (dist > 0.5) {
            double diveSpeed = plugin.getConfig().getDouble("mythics.auratus.slam.dive-speed", 1.6);
            p.setVelocity(toward.normalize().multiply(diveSpeed * plugin.relay().timeScale(nowLoc, id)));
        }
        growSlamDecal(p, id, target);
        if (!p.isOnGround()) {
            f.air = true;
        } else if (f.air || dist <= 2.0) {
            if (f.windowUntil <= 0) {
                f.windowUntil = n + plugin.getConfig().getLong("mythics.auratus.slam.sneak-window-ms", 3000L);
            }
            f.air = false;
        }
        if (f.windowUntil > 0) {
            if (n > f.windowUntil) {
                taskCancel(task);
                clearGrappleVisual(id);
                endSlam(id);
                return;
            }
            if (p.isSneaking()) {
                f.windowUntil = 0;
                int aimTicks = plugin.getConfig().getInt("mythics.auratus.slam.aim-ticks", 2);
                f.aimUntil = n + aimTicks * 50L;
                p.setVelocity(new Vector(0, 0, 0));
            }
        }
        // Chain strike still lands for 2.5 hearts on the way in, once.
        if (!f.arrived && targetId != null) {
            Entity ent = Bukkit.getEntity(targetId);
            if (ent instanceof LivingEntity le && le.isValid()
                    && p.getLocation().distanceSquared(le.getLocation()) < 9.0) {
                f.arrived = true;
                le.damage(plugin.getConfig().getDouble("mythics.auratus.chain.entity-damage", 5.0), p);
            }
        }
    }

    private void clearSlamVisual(UUID id) {
        ItemDisplay d = slamDecals.remove(id);
        if (d != null && d.isValid()) d.remove();
        slamStartY.remove(id);
    }

    private void doAuratusSlam(Player p, Location impact) {
        double radius = plugin.getConfig().getDouble("mythics.auratus.slam.radius", 6.0);
        double centerDamage = plugin.getConfig().getDouble("mythics.auratus.slam.center-damage", 3.0);
        double edgeDamage = plugin.getConfig().getDouble("mythics.auratus.slam.edge-damage", 3.0);
        for (Entity e : p.getNearbyEntities(radius, 3, radius)) {
            if (!(e instanceof LivingEntity le) || le == p) continue;
            double d = Math.min(radius, p.getLocation().distance(le.getLocation()));
            double alpha = 1.0 - (d / radius);
            double dmg = edgeDamage + (centerDamage - edgeDamage) * alpha;
            le.damage(dmg, p);
            if (le instanceof Player victim) victim.setCooldown(Material.WIND_CHARGE, 80);
        }
        p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 6, radius * 0.25, 0.15, radius * 0.25, 0.08);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 80, radius * 0.5, 0.2, radius * 0.5,
                new Particle.DustOptions(Color.fromRGB(230, 175, 45), 1.6f));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 0.75f);
        Sfx.play(plugin, p.getLocation(), "auratus.slam", 1.0f, 1.0f);
        clearSlamVisual(p.getUniqueId());
        auratusSlams.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (!(e.getEntity() instanceof Player p)) return;
        // Slam landings never hurt the caster.
        Long grace = slamGrace.get(p.getUniqueId());
        if (grace != null) {
            if (grace <= now()) {
                slamGrace.remove(p.getUniqueId());
            } else if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
                e.setCancelled(true);
                return;
            }
        }
        GemType type = heldType(p);
        if (type == GemType.HERETIC) {
            double cap = plugin.getConfig().getDouble("mythics.heretic.passives.blood-hardening-max-damage", 14.0);
            if (e.getDamage() > cap) e.setDamage(cap);
        }
        if (!bloodlinkTransferTargets.contains(p.getUniqueId())) {
            mirrorBloodlinkDamage(p, e.getFinalDamage());
        }
        if (type == GemType.AURATUS && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            double mult = plugin.getConfig().getDouble("mythics.auratus.passives.feathered-fall-damage-multiplier", 0.1);
            e.setDamage(e.getDamage() * mult);
        }
        Long until = aegisUntil.get(p.getUniqueId());
        if (type == GemType.AURATUS && until != null && until > now()) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
            if (e instanceof EntityDamageByEntityEvent de) reflectDamage(p, de);
            // Successful parry: heal 2-3 hearts + grant Speed 3 and Weaving
            if (!aegisSuccessful.getOrDefault(p.getUniqueId(), true)) {
                aegisSuccessful.put(p.getUniqueId(), true);
                double heal = 4.0 + Math.random() * 2.0; // 2-3 hearts (4-6 HP)
                p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 2, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAVING, 120, 0, true, false, false));
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                // Silenced — no chat spam
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (e.isCancelled()) return;
        if (reflectedDamage.contains(e.getEntity().getUniqueId())) return;
        if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                && shooter instanceof LivingEntity) {
            Entity victim = e.getEntity();
            if (victim instanceof Player p && heldType(p) == GemType.AURATUS && isAegisActive(p)) {
                if (!e.isCancelled()) e.setCancelled(true);
                reflectDamage(p, e);
                return;
            }
        }
        if (!(e.getDamager() instanceof Player p)) return;
        GemType type = heldType(p);
        if (type == GemType.HERETIC) {
            // Heretic cuts straight through Resistance.
            if (e.getEntity() instanceof LivingEntity hereticTarget) {
                PotionEffect res = hereticTarget.getPotionEffect(PotionEffectType.RESISTANCE);
                if (res != null) {
                    double reduction = Math.min(0.8, 0.2 * (res.getAmplifier() + 1));
                    e.setDamage(e.getDamage() / (1.0 - reduction));
                }
            }
            // Auto-Crit: full-strength swings always crit. Weak (spam-clicked)
            // hits are left alone.
            if (p.getCooledAttackStrength(0.0f) < 0.9f) return;
            // Auto-Crit: reconstruct a crit-like damage multiplier even when vanilla
            // movement/attack-cooldown conditions would not have produced one.
            double multiplier = plugin.getConfig().getDouble("mythics.heretic.passives.auto-crit-multiplier", 1.5);
            e.setDamage(e.getDamage() * multiplier);
            p.getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 0.8, 0), 5, 0.2, 0.25, 0.2, 0.08);
            Long bleed = bleedUntil.get(e.getEntity().getUniqueId());
            if (bleed != null && bleed > now()) {
                e.setDamage(e.getDamage() * plugin.getConfig().getDouble("mythics.heretic.passives.hemorrhage-multiplier", 2.0));
            }
            // Shield breaker: full-strength hits jam blocking shields (wall flips 5s/10s).
            if (e.getEntity() instanceof Player victim && victim.isBlocking()) {
                int ticks = plugin.getConfig().getInt("mythics.heretic.passives.shield-disable-ticks", 100);
                victim.setCooldown(Material.SHIELD, Math.max(victim.getCooldown(Material.SHIELD), ticks));
            }
        }
        if (type == GemType.AURATUS && e.getEntity() instanceof LivingEntity target) {
            // Hauling Strike only grabs half of all normal hits.
            if (Math.random() < plugin.getConfig().getDouble("mythics.auratus.passives.hauling-strike-chance", 0.5)) {
                applyHaulingStrikeKnockback(p, target);
            }
            String graspMode = plugin.getConfig().getString("mythics.auratus.passives.angels-grasp-mode", "PULL");
            if (graspMode.equalsIgnoreCase("PULL") || graspMode.equalsIgnoreCase("BOTH")) {
                if (Math.random() < plugin.getConfig().getDouble("mythics.auratus.passives.angels-grasp-pull-chance", 0.05)) {
                    Vector pull = p.getLocation().toVector().subtract(target.getLocation().toVector());
                    if (pull.lengthSquared() > 0) target.setVelocity(pull.normalize().multiply(0.45));
                }
            }
            // ARMOR_REDUCTION/BOTH is handled by PlayerItemDamageEvent so the actual durability event is reduced.

        }
    }

    private void mirrorBloodlinkDamage(Player victim, double damage) {
        if (damage <= 0) return;
        long n = now();
        for (Map.Entry<UUID, BloodlinkState> entry : bloodlinks.entrySet()) {
            BloodlinkState state = entry.getValue();
            if (state.until() <= n || !state.victims().contains(victim.getUniqueId())) continue;
            for (UUID otherId : state.victims()) {
                if (otherId.equals(victim.getUniqueId())) continue;
                Player other = Bukkit.getPlayer(otherId);
                if (other == null || !other.isOnline()) continue;
                bloodlinkTransferTargets.add(otherId);
                try {
                    other.damage(damage, Bukkit.getPlayer(entry.getKey()));
                } finally {
                    bloodlinkTransferTargets.remove(otherId);
                }
            }
            break;
        }
    }

    private void tryExtendedHaulingStrike(Player p) {
        double range = plugin.getConfig().getDouble("mythics.auratus.passives.hauling-strike-reach", 4.0);
        RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.35,
                entity -> entity != p && entity instanceof LivingEntity);
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) return;
        double distance = p.getEyeLocation().distance(hit.getHitPosition().toLocation(p.getWorld()));
        if (distance <= 3.15) return;
        double damage = estimateAttackDamage(p);
        target.damage(damage, p);
        applyHaulingStrikeKnockback(p, target);
    }

    private double estimateAttackDamage(Player p) {
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return 1.0;
        String n = item.getType().name();
        if (n.endsWith("_SWORD")) {
            if (n.startsWith("NETHERITE")) return 8.0;
            if (n.startsWith("DIAMOND")) return 7.0;
            if (n.startsWith("IRON")) return 6.0;
            if (n.startsWith("STONE")) return 5.0;
            return 4.0;
        }
        if (n.endsWith("_AXE")) {
            if (n.startsWith("NETHERITE")) return 10.0;
            if (n.startsWith("DIAMOND")) return 9.0;
            if (n.startsWith("IRON")) return 9.0;
            if (n.startsWith("STONE")) return 9.0;
            return 7.0;
        }
        if (n.equals("MACE")) return 5.0;
        return 1.0;
    }

    private void applyHaulingStrikeKnockback(Player p, LivingEntity target) {
        Vector forward = p.getLocation().getDirection().clone();
        if (forward.lengthSquared() > 0) forward.normalize();
        target.setVelocity(forward.multiply(plugin.getConfig().getDouble("mythics.auratus.passives.hauling-strike-knockback", 0.65)));
        if (target instanceof Player victim && victim.isBlocking()) {
            victim.setCooldown(Material.SHIELD, Math.max(0, victim.getCooldown(Material.SHIELD)) +
                    plugin.getConfig().getInt("mythics.auratus.passives.hauling-strike-shield-extend-ticks", 100));
        }
    }

    private boolean isAegisActive(Entity e) {
        return e instanceof Player p && aegisUntil.getOrDefault(p.getUniqueId(), 0L) > now();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDurability(PlayerItemDamageEvent e) {
        Player p = e.getPlayer();
        if (heldType(p) != GemType.AURATUS) return;
        String name = e.getItem().getType().name();
        if (!name.endsWith("_HELMET") && !name.endsWith("_CHESTPLATE") && !name.endsWith("_LEGGINGS")
                && !name.endsWith("_BOOTS")) return;
        String mode = plugin.getConfig().getString("mythics.auratus.passives.angels-grasp-mode", "PULL");
        if (!(mode.equalsIgnoreCase("ARMOR_REDUCTION") || mode.equalsIgnoreCase("BOTH"))) return;
        e.setDamage((int) Math.max(0, Math.floor(e.getDamage() * 0.90)));
    }

    private void reflectDamage(Player defender, EntityDamageByEntityEvent e) {
        UUID sourceId = null;
        Entity source = e.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        if (source instanceof LivingEntity le) sourceId = le.getUniqueId();
        if (source != null && sourceId != null) {
            reflectedDamage.add(sourceId);
            try {
                if (source instanceof LivingEntity le && le.isValid()) le.damage(e.getDamage(), defender);
            } finally {
                reflectedDamage.remove(sourceId);
            }
        }
        Damageable weapon = null;
        if (source instanceof Player attacker) {
            ItemStack main = attacker.getInventory().getItemInMainHand();
            if (main.getItemMeta() instanceof Damageable d && main.getType().getMaxDurability() > 0) weapon = d;
            if (weapon != null) {
                int loss = plugin.getConfig().getInt("mythics.auratus.aegis.weapon-durability-loss", 30);
                weapon.setDamage(Math.min(main.getType().getMaxDurability() - 1, weapon.getDamage() + loss));
                main.setItemMeta(weapon);
            }
        }
        aegisSuccessful.put(defender.getUniqueId(), true);
        double heal = plugin.getConfig().getDouble("mythics.auratus.aegis.success-heal-hp", 5.0);
        defender.setHealth(Math.min(defender.getMaxHealth(), defender.getHealth() + heal));
        defender.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                plugin.getConfig().getInt("mythics.auratus.aegis.success-speed-ticks", 60), 2, true, false, false));
        try {
            defender.addPotionEffect(new PotionEffect(PotionEffectType.WEAVING, 60, 0, true, false, false));
        } catch (IllegalArgumentException ignored) {
            // Safe fallback for API versions without Weaving.
        }
        spawnAegisAnchor(defender);
        defender.sendMessage(ChatColor.GOLD + "Aegis parry successful! Sky anchor formed.");
        defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1, 1.4f);
        Sfx.play(plugin, defender.getLocation(), "auratus.parry", 1.0f, 1.0f);
    }

    private void spawnAegisAnchor(Player p) {
        Location loc = p.getLocation().clone().add(0, plugin.getConfig().getDouble("mythics.auratus.aegis.anchor-height", 8.0), 0);
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, AURATUS_ANCHOR_TAG),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        aegisAnchor.put(p.getUniqueId(), stand.getLocation());
        long ms = plugin.getConfig().getLong("mythics.auratus.aegis.anchor-window-ms", 5000L);
        anchorUntil.put(p.getUniqueId(), now() + ms);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stand.isValid()) stand.remove();
            aegisAnchor.remove(p.getUniqueId());
            anchorUntil.remove(p.getUniqueId());
        }, Math.max(1L, ms / 50L));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (!(e.getEntity() instanceof Player p)) return;
        // Enduring Strength: Heretic holders keep Strength far longer —
        // Strength II for 8m, Strength I for 16m.
        if (heldType(p) == GemType.HERETIC && e.getNewEffect() != null
                && e.getNewEffect().getType().equals(PotionEffectType.STRENGTH)) {
            int amp = e.getNewEffect().getAmplifier();
            int target = (amp >= 1 ? 8 : 16) * 60 * 20;
            if (e.getNewEffect().getDuration() < target) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline()) p.addPotionEffect(
                            new PotionEffect(PotionEffectType.STRENGTH, target, amp, true, false, false));
                });
                // Silenced — no chat spam
            }
            return;
        }
        if (!isAegisActive(p)) return;
        // We cannot universally identify the source of every effect in Bukkit's API,
        // but we can block hostile externally-applied effects during the parry window.
        if (e.getNewEffect() != null && !e.getNewEffect().getType().equals(PotionEffectType.SPEED)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        Player p = e.getEntity();
        ItemStack mythic = heldMythic(p);
        if (mythic == null) return;
        // Mythics are explicitly not soul-kept. Guarantee one death drop even if a
        // custom keep-inventory setup would otherwise remove it from the event drops.
        boolean present = false;
        for (ItemStack d : e.getDrops()) if (gems.isGem(d) && gems.getType(d) == gems.getType(mythic)) { present = true; break; }
        if (!present) e.getDrops().add(mythic.clone());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        bloodlinks.remove(id);
        bloodlinkSlamCounts.remove(id);
        bloodlinkTransferTargets.remove(id);
        bloodsawSlots.remove(id);
        bloodsaws.values().removeIf(s -> {
            if (id.equals(s.owner)) {
                removeSaw(s);
                return true;
            }
            return false;
        });
        aegisUntil.remove(id);
        aegisAnchor.remove(id);
        anchorUntil.remove(id);
        auratusSlams.remove(id);
        clearSlamVisual(id);
        clearGrappleVisual(id);
        slamFlows.remove(id);
        slamGrace.remove(id);
        grappleAir.remove(id);
        grappleRope.remove(id);
        lastPos.remove(id);
    }

    public void tick() {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        tickBloodsaws();
        tickFlagpole();
        long n = now();
        bloodlinks.entrySet().removeIf(entry -> entry.getValue().until() <= n);
        for (Player p : Bukkit.getOnlinePlayers()) {
            GemType type = heldType(p);
            if (type == GemType.AURATUS) {
                if (p.isSneaking()) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, 1, true, false, false));
                }
                refillAuratusCharges(p, plugin.getConfig().getInt("mythics.auratus.chain.charges", 2));
            }
            Long bleed = bleedUntil.get(p.getUniqueId());
            if (bleed != null && bleed <= n) bleedUntil.remove(p.getUniqueId());
            // Only touch the action bar while a mythic is held (and once more to clear it when the
            // mythic is put away). Sending an empty bar to everyone else wiped the normal gem HUD
            // that GemListener.tick() sends on the very same tick.
            if (type != null) {
                p.sendActionBar(mythicActionBar(p, type, n));
                mythicBarShown.add(p.getUniqueId());
            } else if (mythicBarShown.remove(p.getUniqueId())) {
                p.sendActionBar(net.kyori.adventure.text.Component.empty());
            }
        }
    }

    /** Players whose action bar was last written by the mythic HUD (so we can clear it exactly once). */
    private final java.util.Set<UUID> mythicBarShown = new java.util.HashSet<>();

    // Mythic HUD glyphs from the resource pack font (default.json: blissgems:icons/*.png).
    private static final String GLYPH_AEGIS = "\u22B4";               // aegis.png
    private static final String GLYPH_PERFORATORS = "\u22B5";         // perforators.png (Auratus chain)
    private static final String GLYPH_BLOODSAWS = "\uD83E\uDD1F";    // bloodsaws.png
    private static final String GLYPH_BLOODLINKING = "\uD83E\uDD0C"; // bloodlinking.png

    private net.kyori.adventure.text.Component mythicActionBar(Player p, GemType type, long n) {
        if (type == null) return net.kyori.adventure.text.Component.empty();
        UUID id = p.getUniqueId();
        String msg;
        if (type == GemType.HERETIC) {
            String sawA = mythicStatus(slotRemain(id, 0, n), hasLiveBloodsaw(id, 0), GLYPH_BLOODSAWS);
            String sawB = mythicStatus(slotRemain(id, 1, n), hasLiveBloodsaw(id, 1), GLYPH_BLOODSAWS);
            String link = mythicStatus(cooldown(hereticBloodlinkCooldown, id), bloodlinks.containsKey(id) || bloodlinkSlamCounts.containsKey(id), GLYPH_BLOODLINKING);
            msg = "§f" + GLYPH_BLOODSAWS + " §bHeretic " + sawA + " §7| " + sawB + " §7➜ §f" + GLYPH_BLOODLINKING + " " + link;
        } else if (type == GemType.AURATUS) {
            int charges = auratusChainCharges.getOrDefault(id, plugin.getConfig().getInt("mythics.auratus.chain.charges", 2));
            long chainCd = cooldown(auratusChainCooldown, id);
            boolean chainActive = auratusSlams.containsKey(id);
            String chains = chainActive ? "§cActive..." : (charges > 0 ? "§aReady!" : "§b" + format(chainCd));
            String aegis = mythicStatus(cooldown(auratusAegisCooldown, id), isAegisActive(p), GLYPH_AEGIS);
            msg = "§f" + GLYPH_PERFORATORS + " §bAuratus " + chains + " §7➜ §f" + GLYPH_AEGIS + " " + aegis;
        } else if (type == GemType.RELAY) {
            msg = plugin.relay().hud(p);
        } else {
            return net.kyori.adventure.text.Component.empty();
        }
        return LegacyComponentSerializer.legacySection().deserialize(msg);
    }

    private String mythicStatus(long remainMs, boolean active, String icon) {
        if (active) return "§cActive...";
        if (remainMs <= 0) return "§aReady!";
        return "§b" + format(remainMs);
    }

    private String format(long ms) {
        long totalSeconds = Math.max(1L, (ms + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "") + (seconds > 0 ? " " + seconds + "s" : "");
        if (minutes > 0) return minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
        return seconds + "s";
    }

    private long slotRemain(UUID id, int slot, long n) {
        long[] slots = bloodsawSlots.get(id);
        if (slots == null || slot < 0 || slot >= slots.length) return 0;
        return Math.max(0, slots[slot] - n);
    }

    private boolean hasLiveBloodsaw(UUID ownerId, int slot) {
        return bloodsaws.values().stream().anyMatch(s -> ownerId.equals(s.owner) && s.slot == slot
                && s.entity != null && s.entity.isValid());
    }

    // ---------- /mythicconfig: in-depth live tuner for every mythic number ----------

    private enum SettingKind { INT, LONG, DOUBLE, CYCLE }

    private record MythicSetting(String path, Material icon, String label, SettingKind kind,
            double step, double big, double min, double max, List<String> options) {}

    private final List<MythicSetting> mythicSettings = List.of(
            // Heretic passives
            new MythicSetting("mythics.heretic.passives.crit-multiplier", Material.DIAMOND_SWORD, "Crit Multiplier", SettingKind.DOUBLE, 0.1, 0.5, 1.0, 5.0, null),
            new MythicSetting("mythics.heretic.passives.bleed-multiplier", Material.REDSTONE, "Hemorrhage Multiplier", SettingKind.DOUBLE, 0.5, 1.0, 1.0, 5.0, null),
            new MythicSetting("mythics.heretic.passives.blood-hardening-max-damage", Material.IRON_CHESTPLATE, "Hardening Cap (HP)", SettingKind.DOUBLE, 1.0, 5.0, 1.0, 40.0, null),
            new MythicSetting("mythics.heretic.passives.shield-disable-ticks", Material.SHIELD, "Heretic Shield Jam", SettingKind.INT, 20, 100, 0, 600, null),
            // Heretic saws
            new MythicSetting("mythics.heretic.bloodsaws.damage", Material.IRON_AXE, "Saw Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 20.0, null),
            new MythicSetting("mythics.heretic.bloodsaws.recharge-ms", Material.CLOCK, "Saw Recharge", SettingKind.LONG, 500, 2000, 0, 60000, null),
            new MythicSetting("mythics.heretic.bloodsaws.speed", Material.FEATHER, "Saw Speed", SettingKind.DOUBLE, 0.05, 0.25, 0.1, 5.0, null),
            new MythicSetting("mythics.heretic.bloodsaws.idle-despawn-ms", Material.BONE, "Saw Idle Despawn", SettingKind.LONG, 500, 2000, 0, 30000, null),
            new MythicSetting("mythics.heretic.bloodsaws.hit-radius", Material.STICK, "Saw Hit Radius", SettingKind.DOUBLE, 0.1, 0.5, 0.2, 5.0, null),
            new MythicSetting("mythics.heretic.bloodsaws.max-bounces", Material.SLIME_BALL, "Saw Max Bounces (-1=inf)", SettingKind.INT, 1, 5, -1, 20, null),
            new MythicSetting("mythics.heretic.bloodsaws.drag-ticks", Material.LEAD, "Saw Drag Ticks", SettingKind.INT, 5, 20, 0, 100, null),
            new MythicSetting("mythics.heretic.bloodsaws.spore-window-ms", Material.SPORE_BLOSSOM, "Spore Combo Window", SettingKind.LONG, 50, 250, 0, 10000, null),
            // Heretic spore + link
            new MythicSetting("mythics.heretic.spore.radius", Material.CRIMSON_FUNGUS, "Spore Radius", SettingKind.DOUBLE, 0.5, 2.0, 1.0, 12.0, null),
            new MythicSetting("mythics.heretic.spore.duration-ms", Material.REDSTONE_TORCH, "Spore Duration", SettingKind.LONG, 1000, 5000, 0, 60000, null),
            new MythicSetting("mythics.heretic.spore.bleed-ms", Material.REDSTONE_BLOCK, "Bleed Duration", SettingKind.LONG, 1000, 5000, 0, 60000, null),
            new MythicSetting("mythics.heretic.bloodlinking.cooldown-seconds", Material.ENDER_EYE, "Bloodlink Cooldown", SettingKind.INT, 5, 30, 0, 600, null),
            new MythicSetting("mythics.heretic.bloodlinking.launch-y", Material.WIND_CHARGE, "Bloodlink Launch", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 3.0, null),
            new MythicSetting("mythics.heretic.bloodlinking.radius", Material.TNT, "Bloodlink Radius", SettingKind.INT, 1, 2, 1, 12, null),
            new MythicSetting("mythics.heretic.bloodlinking.damage", Material.MACE, "Bloodlink Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 30.0, null),
            new MythicSetting("mythics.heretic.bloodlinking.max-slams", Material.ANVIL, "Bloodlink Max Slams", SettingKind.INT, 1, 1, 1, 10, null),
            new MythicSetting("mythics.heretic.bloodlinking.max-victims", Material.SKELETON_SKULL, "Bloodlink Max Victims", SettingKind.INT, 1, 1, 1, 10, null),
            // Auratus chain
            new MythicSetting("mythics.auratus.chain.entity-damage", Material.BOW, "Chain Hit Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 20.0, null),
            new MythicSetting("mythics.auratus.chain.range", Material.LEAD, "Chain Range", SettingKind.DOUBLE, 2.0, 10.0, 5.0, 100.0, null),
            new MythicSetting("mythics.auratus.chain.charges", Material.IRON_CHAIN, "Chain Charges", SettingKind.INT, 1, 1, 1, 5, null),
            new MythicSetting("mythics.auratus.chain.regen-ms", Material.RECOVERY_COMPASS, "Chain Regen", SettingKind.LONG, 1000, 5000, 0, 60000, null),
            new MythicSetting("mythics.auratus.chain.fly-speed", Material.SUGAR, "Chain Pull Speed", SettingKind.DOUBLE, 0.05, 0.25, 0.05, 5.0, null),
            new MythicSetting("mythics.auratus.chain.max-flight-ticks", Material.ARROW, "Chain Max Flight", SettingKind.INT, 2, 10, 4, 200, null),
            new MythicSetting("mythics.auratus.chain.link-spacing", Material.IRON_CHAIN, "Chain Link Spacing", SettingKind.DOUBLE, 0.1, 0.5, 0.2, 5.0, null),
            new MythicSetting("mythics.auratus.chain.link-scale", Material.MAGMA_CREAM, "Chain Link Scale", SettingKind.DOUBLE, 0.05, 0.25, 0.1, 3.0, null),
            // Auratus aegis + anchor
            new MythicSetting("mythics.auratus.aegis.cooldown-seconds", Material.SHIELD, "Aegis Cooldown", SettingKind.INT, 1, 10, 0, 300, null),
            new MythicSetting("mythics.auratus.aegis.window-ms", Material.TOTEM_OF_UNDYING, "Aegis Window", SettingKind.LONG, 250, 1000, 100, 10000, null),
            new MythicSetting("mythics.auratus.aegis.success-heal-hp", Material.GOLDEN_APPLE, "Aegis Heal", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.anchor-window-ms", Material.BEACON, "Anchor Window", SettingKind.LONG, 500, 2000, 0, 30000, null),
            // Auratus slam
            new MythicSetting("mythics.auratus.slam.radius", Material.END_CRYSTAL, "Slam Radius", SettingKind.DOUBLE, 0.5, 2.0, 1.0, 16.0, null),
            new MythicSetting("mythics.auratus.slam.center-damage", Material.MACE, "Slam Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.edge-damage", Material.WOODEN_SWORD, "Slam Edge Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.steep-degrees", Material.COMPASS, "Slam Arm Angle", SettingKind.DOUBLE, 5.0, 15.0, 10.0, 90.0, null),
            new MythicSetting("mythics.auratus.slam.aim-ticks", Material.REPEATER, "Slam Aim Ticks", SettingKind.INT, 1, 5, 1, 40, null),
            new MythicSetting("mythics.auratus.slam.dash-speed", Material.RABBIT_FOOT, "Slam Dash Speed", SettingKind.DOUBLE, 0.1, 0.5, 0.2, 6.0, null),
            new MythicSetting("mythics.auratus.slam.sneak-window-ms", Material.LEVER, "Slam Sneak Window", SettingKind.LONG, 250, 1000, 500, 15000, null),
            new MythicSetting("mythics.auratus.slam.min-height", Material.SCAFFOLDING, "Slam Min Height", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.decal-size", Material.ITEM_FRAME, "Slam Decal Size", SettingKind.DOUBLE, 0.5, 1.0, 0.5, 10.0, null),
            // Auratus passives
            new MythicSetting("mythics.auratus.passives.hauling-strike-reach", Material.TRIDENT, "Haul Reach", SettingKind.DOUBLE, 0.5, 1.0, 2.0, 8.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-knockback", Material.STICK, "Haul Knockback", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 3.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-chance", Material.SNOWBALL, "Haul Chance", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 1.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-shield-extend-ticks", Material.IRON_AXE, "Haul Shield Jam", SettingKind.INT, 20, 100, 0, 600, null),
            new MythicSetting("mythics.auratus.passives.angels-grasp-pull-chance", Material.HEART_OF_THE_SEA, "Grasp Pull Chance", SettingKind.DOUBLE, 0.01, 0.05, 0.0, 1.0, null),
            new MythicSetting("mythics.auratus.passives.angels-grasp-mode", Material.NETHERITE_CHESTPLATE, "Grasp Mode", SettingKind.CYCLE, 0, 0, 0, 0, List.of("PULL", "ARMOR_REDUCTION", "BOTH"))
    );

    private static class MythicConfigHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<UUID, String> configFilter = new HashMap<>();

    private List<MythicSetting> visibleSettings(Player p) {
        String prefix = configFilter.get(p.getUniqueId());
        if (prefix == null) return mythicSettings;
        List<MythicSetting> out = new ArrayList<>();
        for (MythicSetting s : mythicSettings) {
            if (s.path().startsWith(prefix)) out.add(s);
        }
        return out;
    }

    public void openMythicConfig(Player p, String prefix) {
        configFilter.put(p.getUniqueId(), prefix);
        String title = prefix.contains("heretic") ? "Heretic Config"
                : prefix.contains("auratus") ? "Auratus Config" : "Mythic Config";
        Inventory gui = Bukkit.createInventory(new MythicConfigHolder(), 54, title);
        List<MythicSetting> visible = visibleSettings(p);
        for (int i = 0; i < visible.size() && i < 54; i++) gui.setItem(i, renderSetting(visible.get(i)));
        p.openInventory(gui);
    }

    private ItemStack renderSetting(MythicSetting s) {
        ItemStack item = new ItemStack(s.icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + s.label);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.WHITE + "Current: " + settingText(s));
        if (s.kind == SettingKind.CYCLE) lore.add(ChatColor.GRAY + "Click: next option");
        else lore.add(ChatColor.GRAY + "Left/Right: +/-" + trimNum(s.step) + "   Shift: +/-" + trimNum(s.big));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String trimNum(double v) {
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    private String settingText(MythicSetting s) {
        var cfg = plugin.getConfig();
        return switch (s.kind) {
            case INT -> String.valueOf(cfg.getInt(s.path, 0));
            case LONG -> {
                long v = cfg.getLong(s.path, 0L);
                if (s.path.endsWith("-ms")) yield String.format("%.1fs", v / 1000.0);
                if (s.path.endsWith("-ticks")) yield v + "t (" + String.format("%.1f", v / 20.0) + "s)";
                yield String.valueOf(v);
            }
            case DOUBLE -> String.valueOf(cfg.getDouble(s.path, 0.0));
            case CYCLE -> cfg.getString(s.path, s.options.get(0));
        };
    }

    @EventHandler
    public void onMythicConfigClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof MythicConfigHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof MythicConfigHolder)) return;
        List<MythicSetting> visible = visibleSettings(p);
        int slot = e.getSlot();
        if (slot < 0 || slot >= visible.size()) return;
        MythicSetting s = visible.get(slot);
        boolean right = e.getClick().isRightClick();
        boolean shift = e.getClick().isShiftClick();
        var cfg = plugin.getConfig();
        if (s.kind == SettingKind.CYCLE) {
            String cur = cfg.getString(s.path, s.options.get(0));
            int next = (s.options.indexOf(cur) + 1) % s.options.size();
            if (next < 0) next = 0;
            cfg.set(s.path, s.options.get(next));
        } else {
            double delta = (shift ? s.big : s.step) * (right ? -1 : 1);
            switch (s.kind) {
                case INT -> {
                    int v = (int) Math.round(Math.max(s.min, Math.min(s.max, cfg.getInt(s.path, 0) + delta)));
                    cfg.set(s.path, v);
                }
                case LONG -> {
                    long v = Math.max((long) s.min, Math.min((long) s.max, Math.round(cfg.getLong(s.path, 0L) + delta)));
                    cfg.set(s.path, v);
                }
                default -> {
                    double v = Math.max(s.min, Math.min(s.max, cfg.getDouble(s.path, 0.0) + delta));
                    cfg.set(s.path, Math.round(v * 100.0) / 100.0);
                }
            }
        }
        plugin.saveConfig();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        e.getInventory().setItem(slot, renderSetting(s));
    }

    public boolean isBleeding(LivingEntity entity) {
        return bleedUntil.getOrDefault(entity.getUniqueId(), 0L) > now();
    }
}
