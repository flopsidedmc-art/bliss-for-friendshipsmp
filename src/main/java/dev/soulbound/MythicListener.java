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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

/**
 * Mythic gem implementation. This is an original implementation based on the
 * user-provided wiki captures/footage plus currently documented public behavior.
 * Numbers are deliberately configurable so uncertain values can be tuned.
 */
public final class MythicListener implements Listener {
    private static final String BLOODSAW_TAG = "blisslike_bloodsaw";

    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private final Map<UUID, long[]> bloodsawSlots = new HashMap<>(); // two independent 5s charges: [A ready-at, B ready-at]
    private final Map<UUID, Long> hereticBloodlinkCooldown = new HashMap<>();
    private final Map<UUID, Long> bleedUntil = new HashMap<>();
    private final Map<UUID, Integer> bloodSawHits = new HashMap<>();
    private final Map<UUID, Long> bloodSawComboUntil = new HashMap<>();

    private final Map<UUID, BloodlinkState> bloodlinks = new HashMap<>();
    private final Map<UUID, Integer> bloodlinkSlamCounts = new HashMap<>();
    private final Set<UUID> bloodlinkTransferTargets = new HashSet<>();
    private final Map<UUID, BloodsawState> bloodsaws = new HashMap<>();
    private record BloodlinkState(Set<UUID> victims, long until) {}

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
        if (heldType(p) == GemType.AURATUS) { plugin.auratus().handleInput(e); return; } // AuratusGem owns its controls
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
    }

    private void routeRightClick(Player p) {
        GemType type = heldType(p);
        if (type == GemType.HERETIC) useBloodsaws(p);
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

        GemSounds.play(plugin, "heretic.bloodsaws", p.getLocation());
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
                GemSounds.play(plugin, "heretic.bloodsaw-hit", loc);
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
            plugin.listener().pierceHit(le, damage, source); // skips armour points (ability-damage.pierce-armor)
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
        GemSounds.play(plugin, "heretic.spore", loc);
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
        GemSounds.play(plugin, "heretic.bloodlinking", p.getLocation());
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
            plugin.listener().pierceHit(victim, damage, p);
            victim.setVelocity(new Vector(0, 0.6, 0));
        }
        int slamCount = bloodlinkSlamCounts.getOrDefault(p.getUniqueId(), 0) + 1;
        bloodlinkSlamCounts.put(p.getUniqueId(), slamCount);
        p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 2, 0.2, 0.1, 0.2, 0.02);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 45, radius * 0.45, 0.25, radius * 0.45,
                new Particle.DustOptions(Color.fromRGB(150, 0, 20), 1.6f));
        GemSounds.play(plugin, "heretic.bloodlink-slam", p.getLocation());
        if (victims.size() > 0 && slamCount < plugin.getConfig().getInt("mythics.heretic.bloodlinking.max-slams", 3)) {
            bloodlinks.put(p.getUniqueId(), new BloodlinkState(victims, now() + 10_000L));
            p.setVelocity(new Vector(0, 0.95, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> beginBloodlinkDive(p), 8L);
        } else {
            bloodlinks.remove(p.getUniqueId());
            bloodlinkSlamCounts.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (!(e.getEntity() instanceof Player p)) return;
        GemType type = heldType(p);
        if (type == GemType.HERETIC) {
            double cap = plugin.getConfig().getDouble("mythics.heretic.passives.blood-hardening-max-damage", 14.0);
            if (e.getDamage() > cap) e.setDamage(cap);
        }
        if (!bloodlinkTransferTargets.contains(p.getUniqueId())) {
            mirrorBloodlinkDamage(p, e.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (e.isCancelled()) return;
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
    }

    public void tick() {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        tickBloodsaws();
        long n = now();
        bloodlinks.entrySet().removeIf(entry -> entry.getValue().until() <= n);
        for (Player p : Bukkit.getOnlinePlayers()) {
            GemType type = heldType(p);
            Long bleed = bleedUntil.get(p.getUniqueId());
            if (bleed != null && bleed <= n) bleedUntil.remove(p.getUniqueId());
            // Only touch the action bar while a mythic is held (and once more to clear it when the
            // mythic is put away). Sending an empty bar to everyone else wiped the normal gem HUD
            // that GemListener.tick() sends on the very same tick.
            boolean revenantBar = plugin.revenant() != null && plugin.revenant().hudActive(p);
            if (type != null && !revenantBar) {
                String capture = plugin.captures() == null ? "" : plugin.captures().hudSuffix(p); // your time on a capture point
                p.sendActionBar(capture.isEmpty() ? mythicActionBar(p, type, n)
                        : mythicActionBar(p, type, n).append(LegacyComponentSerializer.legacySection().deserialize(capture)));
                mythicBarShown.add(p.getUniqueId());
            } else if (mythicBarShown.remove(p.getUniqueId()) && !revenantBar) {
                p.sendActionBar(net.kyori.adventure.text.Component.empty());
            }
        }
    }

    /** Players whose action bar was last written by the mythic HUD (so we can clear it exactly once). */
    private final java.util.Set<UUID> mythicBarShown = new java.util.HashSet<>();

    // Mythic HUD glyphs from the resource pack font (default.json: blissgems:icons/*.png).
    private static final String GLYPH_BLOODSAWS = "\uD83E\uDD1F";    // bloodsaws.png
    private static final String GLYPH_BLOODLINKING = "\uD83E\uDD0C"; // bloodlinking.png

    private net.kyori.adventure.text.Component mythicActionBar(Player p, GemType type, long n) {
        if (type == null) return net.kyori.adventure.text.Component.empty();
        UUID id = p.getUniqueId();
        String msg;
        if (type == GemType.HERETIC) {
            // Icons and timers only: the saws as one icon with two charges, then Bloodlinking.
            int ready = (slotRemain(id, 0, n) <= 0 ? 1 : 0) + (slotRemain(id, 1, n) <= 0 ? 1 : 0);
            long next = Math.min(slotRemain(id, 0, n) > 0 ? slotRemain(id, 0, n) : Long.MAX_VALUE,
                    slotRemain(id, 1, n) > 0 ? slotRemain(id, 1, n) : Long.MAX_VALUE);
            String saws = HudStatus.charges(GLYPH_BLOODSAWS, ready, 2, next == Long.MAX_VALUE ? 0 : next);
            String link = HudStatus.entry(GLYPH_BLOODLINKING, cooldown(hereticBloodlinkCooldown, id),
                    bloodlinks.containsKey(id) || bloodlinkSlamCounts.containsKey(id));
            msg = saws + "  " + link;
        } else if (type == GemType.AURATUS) {
            return plugin.auratus().hudComponent(p);
        } else if (type == GemType.RELAY) {
            return plugin.relay().hudComponent(p); // carries the Rift slot beside the hotbar
        } else {
            return net.kyori.adventure.text.Component.empty();
        }
        return LegacyComponentSerializer.legacySection().deserialize(msg);
    }

    private long slotRemain(UUID id, int slot, long n) {
        long[] slots = bloodsawSlots.get(id);
        if (slots == null || slot < 0 || slot >= slots.length) return 0;
        return Math.max(0, slots[slot] - n);
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
            // Auratus chains
            new MythicSetting("mythics.auratus.chain.hit-damage", Material.BOW, "Chain Hit Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 20.0, null),
            new MythicSetting("mythics.auratus.chain.range", Material.LEAD, "Chain Range", SettingKind.DOUBLE, 2.0, 10.0, 5.0, 100.0, null),
            new MythicSetting("mythics.auratus.chain.charges", Material.IRON_CHAIN, "Chain Charges", SettingKind.INT, 1, 1, 1, 5, null),
            new MythicSetting("mythics.auratus.chain.recharge-seconds", Material.RECOVERY_COMPASS, "Chain Recharge (s)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 60.0, null),
            new MythicSetting("mythics.auratus.chain.reel-speed", Material.SUGAR, "Chain Reel Speed", SettingKind.DOUBLE, 0.05, 0.25, 0.2, 3.0, null),
            new MythicSetting("mythics.auratus.chain.hook-seconds", Material.TRIPWIRE_HOOK, "Hook Hold (s)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 30.0, null),
            new MythicSetting("mythics.auratus.chain.haul-speed", Material.LEAD, "Haul-In Speed", SettingKind.DOUBLE, 0.05, 0.25, 0.2, 3.0, null),
            new MythicSetting("mythics.auratus.chain.max-reel-ticks", Material.ARROW, "Chain Max Reel Ticks", SettingKind.INT, 2, 10, 4, 200, null),
            new MythicSetting("mythics.auratus.chain.link-scale", Material.MAGMA_CREAM, "Chain Link Scale", SettingKind.DOUBLE, 0.05, 0.25, 0.1, 3.0, null),
            // Auratus Aegis + Echo Anchor
            new MythicSetting("mythics.auratus.aegis.cooldown-seconds", Material.SHIELD, "Aegis Cooldown (s)", SettingKind.DOUBLE, 1.0, 5.0, 0.0, 300.0, null),
            new MythicSetting("mythics.auratus.aegis.success-cooldown-seconds", Material.CLOCK, "Aegis Cooldown After a Parry (s)", SettingKind.DOUBLE, 1.0, 5.0, 0.0, 300.0, null),
            new MythicSetting("mythics.auratus.aegis.window-ms", Material.TOTEM_OF_UNDYING, "Aegis Window", SettingKind.LONG, 250, 1000, 100, 10000, null),
            new MythicSetting("mythics.auratus.aegis.reflect", Material.CACTUS, "Aegis Reflect Share", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 2.0, null),
            new MythicSetting("mythics.auratus.aegis.heal-hp", Material.GOLDEN_APPLE, "Aegis Heal (HP)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.aegis.strike-damage", Material.GOLDEN_SWORD, "Aegis Strike Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 20.0, null),
            new MythicSetting("mythics.auratus.aegis.anchor-seconds", Material.BEACON, "Echo Anchor (s)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 30.0, null),
            // Auratus slam
            new MythicSetting("mythics.auratus.slam.cooldown-seconds", Material.ANVIL, "Slam Cooldown (s)", SettingKind.DOUBLE, 1.0, 5.0, 0.0, 300.0, null),
            new MythicSetting("mythics.auratus.slam.radius", Material.END_CRYSTAL, "Slam Radius", SettingKind.DOUBLE, 0.5, 2.0, 1.0, 16.0, null),
            new MythicSetting("mythics.auratus.slam.damage-center", Material.MACE, "Slam Centre Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.damage-edge", Material.WOODEN_SWORD, "Slam Edge Damage", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.min-height", Material.SCAFFOLDING, "Slam Min Height", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 40.0, null),
            new MythicSetting("mythics.auratus.slam.dive-speed", Material.RABBIT_FOOT, "Slam Dive Speed", SettingKind.DOUBLE, 0.1, 0.5, 0.3, 6.0, null),
            // Auratus Flagpole
            new MythicSetting("mythics.auratus.flagpole.window-ms", Material.LEVER, "Catch Window (Flagpole / Launch)", SettingKind.LONG, 50, 250, 50, 3000, null),
            new MythicSetting("mythics.auratus.flagpole.finisher-damage", Material.NETHERITE_SWORD, "Launch Hit (raw HP)", SettingKind.DOUBLE, 1.0, 10.0, 0.0, 200.0, null),
            new MythicSetting("mythics.auratus.flagpole.finisher-launch-height", Material.FIREWORK_ROCKET, "Launch Height", SettingKind.DOUBLE, 1.0, 5.0, 1.0, 60.0, null),
            new MythicSetting("mythics.auratus.flagpole.cooldown-seconds", Material.WHITE_BANNER, "Flagpole Cooldown (s)", SettingKind.DOUBLE, 1.0, 5.0, 0.0, 300.0, null),
            new MythicSetting("mythics.auratus.flagpole.hold-seconds", Material.PHANTOM_MEMBRANE, "Flagpole Hold (s)", SettingKind.DOUBLE, 0.5, 1.0, 0.5, 10.0, null),
            new MythicSetting("mythics.auratus.flagpole.height", Material.LADDER, "Flagpole Height", SettingKind.DOUBLE, 0.5, 1.0, 1.0, 12.0, null),
            // Auratus passives
            new MythicSetting("mythics.auratus.passives.feathered-fall-damage-multiplier", Material.FEATHER, "Feathered Fall Multiplier", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 1.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-extra-reach", Material.TRIDENT, "Haul Extra Reach", SettingKind.DOUBLE, 0.25, 1.0, 0.0, 3.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-pull", Material.STICK, "Haul Pull", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 2.0, null),
            new MythicSetting("mythics.auratus.passives.hauling-strike-shield-ticks", Material.IRON_AXE, "Haul Shield Jam", SettingKind.INT, 20, 100, 0, 600, null),
            new MythicSetting("mythics.auratus.passives.angels-grasp-seconds", Material.HEART_OF_THE_SEA, "Angels Grasp (s)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 30.0, null),
            new MythicSetting("mythics.auratus.passives.angels-grasp-chain-seconds", Material.PRISMARINE_SHARD, "Angels Grasp from Chains (s)", SettingKind.DOUBLE, 0.5, 2.0, 0.0, 30.0, null),
            new MythicSetting("mythics.auratus.passives.divine-purity-multiplier", Material.MILK_BUCKET, "Divine Purity Multiplier", SettingKind.DOUBLE, 0.05, 0.25, 0.0, 1.0, null)
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
