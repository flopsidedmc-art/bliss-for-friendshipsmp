package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PRISMATIC EDGE — the showcase sword.
 *
 * Confirmed from dev (corrections applied):
 *  - "Turns you into Sukuna" was FIGURATIVE — visuals are white particles with
 *    occasional rainbow (prismatic) accents, not red.
 *  - The sword: large cleaver-style blade (vanilla netherite base; drop in a
 *    custom CMD model via BlissPackVisuals when the pack has one).
 *  - Ambient while held: white orbit ring with occasional rainbow sparks.
 *  - The "look thing": white aim tracer along look direction + reach marker
 *    (red tip at max reach + small markers over entities inside reach).
 *  - DISMANTLE (left click): white slash fan (occasional rainbow sparks).
 *  - CLEAVE (sneak + left click): bigger fan + diagonal cross flare, knockback.
 *  - PARRY (right click): 1.25s window; incoming melee hit negated and
 *    reflected; the burst is a comic-POW 4-point starburst with diamond base
 *    (MythicFx.powBurst).
 */
public class PrismaticEdge implements Listener {

    public static final String KEY = "prismatic-edge";

    private final SoulboundGemsPlugin plugin;

    /** player -> dismantle cooldown end */
    private final Map<UUID, Long> dismantleCd = new HashMap<>();
    /** player -> cleave cooldown end */
    private final Map<UUID, Long> cleaveCd = new HashMap<>();
    /** player -> parry window end */
    private final Map<UUID, Long> parryUntil = new HashMap<>();
    /** player -> parry cooldown end */
    private final Map<UUID, Long> parryCd = new HashMap<>();

    public PrismaticEdge(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        long period = plugin.getConfig().getLong("prismatic-edge.marker-interval-ticks", 2L);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (isEdge(p.getInventory().getItemInMainHand())) {
                        drawAimLine(p);
                        drawSukunaAmbient(p);
                    }
                }
            }
        }.runTaskTimer(plugin, period, period);
    }

    // ----- item ----------------------------------------------------------------

    public ItemStack createEdge() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        var meta = sword.getItemMeta();
        meta.displayName(Component.text("Prismatic Edge", TextColor.color(0xFF42A5)));
        meta.lore(java.util.List.of(
                Component.text("Cleave the world, never their intent.", NamedTextColor.DARK_RED),
                Component.text("Left click — Dismantle · Sneak+Left — Cleave", NamedTextColor.GRAY),
                Component.text("Right click — Parry", NamedTextColor.GRAY)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "prismatic"),
                PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        sword.setItemMeta(meta);
        return sword;
    }

    public boolean isEdge(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(new org.bukkit.NamespacedKey(plugin, "prismatic"), PersistentDataType.BYTE);
    }

    // ----- the "look thing": aim line + reach marker ------------------------------

    private void drawAimLine(Player player) {
        double reach = plugin.getConfig().getDouble("prismatic-edge.reach", 6.5);
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        RayTraceResult ray = player.getWorld().rayTraceBlocks(eye, dir, reach,
                org.bukkit.FluidCollisionMode.NEVER, true);
        double end = ray == null || ray.getHitPosition() == null ? reach : eye.toVector().distance(ray.getHitPosition());

        // sparse light tracer to the reach end
        for (double d = 1.5; d < Math.max(1.6, end); d += 0.9) {
            Location p = eye.clone().add(dir.clone().multiply(d));
            player.getWorld().spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                    MythicFx.dust(1.0f, 0.95f, 0.9f, 0.7f));
        }
        // reach marker: entities in range get a red arc; the reach end gets a tip
        Location tip = eye.clone().add(dir.clone().multiply(end)).add(0, 0.05, 0);
        player.getWorld().spawnParticle(Particle.DUST, tip, 3, 0.03, 0.03, 0.03, 0,
                MythicFx.dust(MythicFx.RED_R, MythicFx.RED_G, MythicFx.RED_B, 1.2f));
        for (org.bukkit.entity.Entity ent : player.getWorld().getNearbyEntities(eye, reach, reach, reach,
                e -> e instanceof LivingEntity l && !(l instanceof ArmorStand) && l != player)) {
            if (!(ent instanceof LivingEntity target)) continue;
            if (target.getEyeLocation().distance(eye) <= reach) {
                MythicFx.curls(target, true, false, 0.35); // tiny red-family marker on target(s) in reach
            }
        }
    }

    /** Prismatic ambient: white orbit with occasional rainbow sparks (not Sukuna-red; that was figurative). */
    private void drawSukunaAmbient(Player player) {
        Location anchor = player.getLocation();
        double phase = (System.currentTimeMillis() % 4000) * 0.0016;
        for (int w = 0; w < 3; w++) {
            double a = phase + Math.PI * 2 * w / 3D;
            double r = 0.55 + 0.15 * Math.sin(phase * 2 + w);
            Location p = anchor.clone().add(Math.cos(a) * r, 1.4 + Math.sin(a) * 0.3, Math.sin(a) * r);
            player.getWorld().spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0,
                    MythicFx.prismaticDust(0.9f));
        }
        // faint core shimmer at chest height
        player.getWorld().spawnParticle(Particle.DUST, anchor.clone().add(0, 1.6, 0), 1, 0.25, 0.35, 0.25, 0,
                MythicFx.prismaticDust(0.8f));
    }

    // ----- slashes ----------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!isEdge(held)) return;

        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true); // don't swing into blocks; the slash IS the attack
            if (player.isSneaking()) useCleave(player);
            else useDismantle(player);
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            useParry(player);
        }
    }

    private void useDismantle(Player player) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getInt("prismatic-edge.dismantle-cooldown-seconds", 3) * 1000L;
        if (now < dismantleCd.getOrDefault(player.getUniqueId(), 0L)) {
            player.sendActionBar(Component.text("Dismantle " + (dismantleCd.get(player.getUniqueId()) - now) / 1000 + "s",
                    NamedTextColor.RED));
            return;
        }
        dismantleCd.put(player.getUniqueId(), now + cd);

        double reach = plugin.getConfig().getDouble("prismatic-edge.reach", 6.5);
        double damage = plugin.getConfig().getDouble("prismatic-edge.dismantle-damage-hp", 7.0);
        slashCone(player, reach, Math.PI / 6.0, damage, false);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f);
        player.sendActionBar(Component.text("Dismantle", NamedTextColor.DARK_RED));
    }

    private void useCleave(Player player) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getInt("prismatic-edge.cleave-cooldown-seconds", 8) * 1000L;
        if (now < cleaveCd.getOrDefault(player.getUniqueId(), 0L)) {
            player.sendActionBar(Component.text("Cleave " + (cleaveCd.get(player.getUniqueId()) - now) / 1000 + "s",
                    NamedTextColor.RED));
            return;
        }
        cleaveCd.put(player.getUniqueId(), now + cd);

        double reach = plugin.getConfig().getDouble("prismatic-edge.reach", 6.5);
        double damage = plugin.getConfig().getDouble("prismatic-edge.cleave-damage-hp", 12.0);
        slashCone(player, reach, Math.PI / 4.0, damage, true);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.55f);
        player.sendActionBar(Component.text("Cleave", NamedTextColor.WHITE));
    }

    /** Slashes entities in a cone; draws the red/white slash ribbons. */
    private void slashCone(Player player, double reach, double halfAngle, double damage, boolean cleave) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        org.bukkit.util.BoundingBox box = player.getBoundingBox().expand(reach + 2);
        for (org.bukkit.entity.Entity ent : player.getWorld().getNearbyEntities(box,
                e -> e instanceof LivingEntity l && !(l instanceof ArmorStand) && l != player)) {
            if (!(ent instanceof LivingEntity target)) continue;
            Vector to = target.getEyeLocation().toVector().subtract(eye.toVector());
            if (to.length() > reach) continue;
            double angle = dir.angle(to.normalize());
            if (angle > halfAngle) continue;
            target.damage(damage, player);
            if (cleave) {
                target.setVelocity(dir.clone().setY(0.25).multiply(0.8));
            }
            MythicFx.powBurst(target.getEyeLocation(), 0.7); // small impact star
        }

        // slash ribbons: white fans with occasional rainbow spark (cleave adds a white cross flare)
        int lines = cleave ? 5 : 3;
        for (int i = 0; i < lines; i++) {
            double spin = (i - (lines - 1) / 2.0) * 0.28;
            for (double d = 1.0; d < reach; d += 0.5) {
                Location p = eye.clone().add(dir.clone().multiply(d));
                p.add(0, Math.sin(spin) * d * 0.28, 0);
                boolean flare = cleave && (i % 2 == 0);
                player.getWorld().spawnParticle(Particle.DUST, p, 2, 0.1, 0.12, 0.1, 0,
                        MythicFx.prismaticDust(flare ? 1.4f : 1.2f));
            }
            if (cleave) {
                // white diagonal cross: second ribbon rotated ~90°
                Vector cross = rotateYaw(dir.clone(), Math.PI / 2).setY(0.35).normalize();
                for (double d = 1.0; d < reach; d += 0.7) {
                    Location p = eye.clone().add(cross.clone().multiply(d * 0.6));
                    player.getWorld().spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0,
                            MythicFx.prismaticDust(1.2f));
                }
            }
        }
    }

    private Vector rotateYaw(Vector v, double rad) {
        double x = v.getX() * StrictMath.sin(rad) + v.getZ() * StrictMath.cos(rad);
        double z = -v.getX() * StrictMath.cos(rad) + v.getZ() * StrictMath.sin(rad);
        return new Vector(x, 0, z);
    }

    // ----- parry -------------------------------------------------------------------

    private void useParry(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getInt("prismatic-edge.parry-cooldown-seconds", 6) * 1000L;
        if (now < parryCd.getOrDefault(id, 0L)) {
            player.sendActionBar(Component.text("Parry " + (parryCd.get(id) - now) / 1000 + "s", NamedTextColor.GOLD));
            return;
        }
        long window = plugin.getConfig().getLong("prismatic-edge.parry-window-ms", 1250);
        parryUntil.put(id, now + window);
        parryCd.put(id, now + cd);
        player.sendActionBar(Component.text("Parry ready…", TextColor.color(0xFFC43D)));
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 6, 0.4, 0.4, 0.4, 0,
                MythicFx.prismaticDust(1.0f));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.7f, 1.6f);
    }

    @EventHandler
    public void onParry(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Long windowEnd = parryUntil.get(player.getUniqueId());
        if (windowEnd == null || System.currentTimeMillis() > windowEnd) return;
        if (!isEdge(player.getInventory().getItemInMainHand())) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;

        parryUntil.remove(player.getUniqueId());
        event.setCancelled(true);

        // the blow lands on the attacker instead, with the comic POW burst
        double reflect = plugin.getConfig().getDouble("prismatic-edge.parry-reflect-hp", 5.0);
        attacker.damage(reflect, player);
        MythicFx.powBurst(player.getLocation().add(0, 1.1, 0), 1.6);
        attacker.getWorld().spawnParticle(Particle.DUST, attacker.getEyeLocation(), 8, 0.3, 0.3, 0.3, 0,
                MythicFx.prismaticDust(1.3f));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.8f);
        player.sendActionBar(Component.text("PARRY", NamedTextColor.GOLD));
    }

    // ----- admin helpers ---------------------------------------------------------------

    /** Call from /blissop pris. */
    public void give(Player player) {
        player.getInventory().addItem(createEdge());
        player.sendMessage(Component.text("Prismatic Edge granted.", NamedTextColor.LIGHT_PURPLE));
    }
}
