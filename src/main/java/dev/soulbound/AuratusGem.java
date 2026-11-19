package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Auratus gem, rebuilt: the same chains, Aegis, slam and Flagpole, on the old Auratus controls.
 *
 * Gem in the main hand: left-click Venerated Perforators (chains), sneak + right-click Echoing Aegis, sneak + chain
 * in the air Gilded Slam, left + right-click together Flagpole. Gem in the off hand: right-click chains, sneak +
 * right-click Aegis (or the slam, in the air). Passives while holding it in either hand: Feathered Fall, Hauling
 * Strike, Angels Grasp, Divine Purity.
 *
 * Damage goes through the same ability-damage scaling and cap as the standard gems, so it hurts unarmored
 * players a lot and armored ones within reason. Nothing is random.
 */
final class AuratusGem implements Listener {
    static final Color GOLD = Color.fromRGB(0xFFC846), PALE = Color.fromRGB(0xFFF0B4), DEEP = Color.fromRGB(0xC88C1E);
    /** Echo-shard models in the main Bliss pack: slam ring, parry halo, chain link, chain hook. */
    private static final int CMD_SMASH = 12345, CMD_HALO = 12346, CMD_LINK = 12348, CMD_HOOK = 12349;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;
    private final NamespacedKey reachKey = new NamespacedKey("soulbound-gems", "auratus_reach");
    private final NamespacedKey hoistKey = new NamespacedKey("soulbound-gems", "auratus_hoist");

    private final Map<UUID, Charges> chains = new HashMap<>();
    private final Map<UUID, Long> aegisReady = new HashMap<>(), slamReady = new HashMap<>(), flagReady = new HashMap<>();
    private final Map<UUID, Long> lastLeft = new HashMap<>(), lastRight = new HashMap<>(), lastHint = new HashMap<>();
    private final Map<UUID, String> hintText = new HashMap<>();
    private final Map<UUID, Aegis> aegis = new HashMap<>();
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private final Map<UUID, Reel> reels = new HashMap<>();
    private final Map<UUID, Dive> dives = new HashMap<>();
    private final Map<UUID, Hook> hooks = new HashMap<>(); // holder -> the enemy their chain last latched onto
    private final Map<UUID, Hoist> hoists = new HashMap<>(); // hoisted entity -> its Flagpole
    private final Map<UUID, Long> grasped = new HashMap<>(); // Angels Grasp: entity -> until (ms)
    private final Map<UUID, Long> fallGrace = new HashMap<>(); // a slam landing never hurts its caster
    private final Map<UUID, Integer> fullSwing = new HashMap<>(); // server tick of the holder's last full-strength swing
    private final Set<UUID> reachApplied = new HashSet<>();
    private final Set<UUID> purifying = new HashSet<>();
    private final Set<UUID> reflecting = new HashSet<>();
    private long tick;

    /** The chain's links and hook (pack models), or nothing when drawn as dust. */
    private static final class Chain {
        final List<ItemDisplay> links = new ArrayList<>();
        ItemDisplay hook;
        void clear() {
            for (ItemDisplay d : links) if (d.isValid()) d.remove();
            links.clear();
            if (hook != null && hook.isValid()) hook.remove();
            hook = null;
        }
    }
    private static final class Reel { Location point; UUID entity; double age; boolean airborne; Vector carry = new Vector(); final Chain chain = new Chain(); }
    private static final class Dive { Location target; double startY, age; ItemDisplay decal; final Chain chain = new Chain(); }
    private static final class Aegis { long castAt, until; int age; boolean parried; ItemDisplay halo; }
    private static final class Anchor { Location at; long until; ItemDisplay ring; }
    private record Hook(UUID victim, long at) {}
    private static final class Hoist { UUID owner; double baseY, topY; int left; }
    private record Target(Location point, LivingEntity entity, boolean anchor) {}

    AuratusGem(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        migrateConfig();
    }

    /**
     * The old Auratus settings meant different things (hauling-strike-reach was the total reach, damage was unscaled),
     * so a config from before the rebuild gets the new section once. Feathered Fall and the chain's look are kept.
     */
    private void migrateConfig() {
        var cfg = plugin.getConfig();
        if (!cfg.contains("mythics.auratus.chain.regen-ms", true) && !cfg.contains("mythics.auratus.passives.hauling-strike-chance", true)) return;
        String[] keep = {"passives.feathered-fall-damage-multiplier", "chain.show-models", "chain.link-spacing", "chain.link-scale"};
        Object[] kept = new Object[keep.length];
        for (int i = 0; i < keep.length; i++) kept[i] = cfg.contains(path(keep[i]), true) ? cfg.get(path(keep[i])) : null;
        cfg.set("mythics.auratus", null);
        for (int i = 0; i < keep.length; i++) if (kept[i] != null) cfg.set(path(keep[i]), kept[i]);
        plugin.saveConfig();
        plugin.getLogger().info("Auratus was rebuilt: its old settings in config.yml were replaced with the new ones "
                + "(Feathered Fall and the chain model settings were kept). See AURATUS.md.");
    }

    // ------------------------------------------------------------------ config and helpers

    private String path(String k) { return "mythics.auratus." + k; }
    private double cfgD(String k, double d) { return plugin.getConfig().getDouble(path(k), d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt(path(k), d); }
    private boolean cfgB(String k, boolean d) { return plugin.getConfig().getBoolean(path(k), d); }
    private boolean enabled() { return plugin.getConfig().getBoolean("mythics.enabled", true); }
    private long now() { return System.currentTimeMillis(); }
    private long ms(String k, double seconds) { return (long) (Math.max(0, cfgD(k, seconds)) * 1000); }

    private boolean isAuratus(ItemStack it) { return gems.isGem(it) && gems.getType(it) == GemType.AURATUS; }
    private boolean inMain(Player p) { return isAuratus(p.getInventory().getItemInMainHand()); }
    /** Holding the Auratus in either hand (passives work from either). */
    boolean holds(Player p) { return plugin.mythics().heldMythicType(p) == GemType.AURATUS; }

    private Charges charges(Player p) {
        Charges c = chains.computeIfAbsent(p.getUniqueId(), k -> new Charges(2));
        c.resize(cfgI("chain.charges", 2));
        return c;
    }

    private double timeScale(Location at, UUID id) { return plugin.relay().timeScale(at, id); }

    /** Hostile mobs, and players in survival or adventure who aren't trusted either way. Others can be hooked, not hurt. */
    private boolean isEnemy(Player p, LivingEntity v) {
        if (v.equals(p) || v.isDead() || !v.isValid() || v instanceof ArmorStand) return false;
        if (v instanceof Player o) {
            if (o.getGameMode() != GameMode.SURVIVAL && o.getGameMode() != GameMode.ADVENTURE) return false;
            return !plugin.listener().isTrusted(p, o) && !plugin.listener().isTrusted(o, p);
        }
        if (v instanceof Tameable t && t.isTamed()) {
            AnimalTamer owner = t.getOwner();
            if (owner != null && owner.getUniqueId().equals(p.getUniqueId())) return false;
            if (owner instanceof Player op && plugin.listener().isTrusted(p, op)) return false;
        }
        return v instanceof Enemy;
    }

    private void hint(Player p, String text) {
        long n = now();
        if (n - lastHint.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        lastHint.put(p.getUniqueId(), n);
        hintText.put(p.getUniqueId(), text); // the HUD shows it instead of the timers for a moment
        p.sendActionBar(Component.text(text, net.kyori.adventure.text.format.TextColor.color(0xFFC846)));
    }

    private ItemStack model(int cmd) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(cmd);
        it.setItemMeta(m);
        return it;
    }

    /** A full-bright pack model that moves smoothly between teleports. */
    private ItemDisplay display(Location at, int cmd) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(model(cmd));
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setInterpolationDuration(2);
        });
    }

    /** Lays a flat model (halo, slam ring) level at the display's height, scaled and turned. */
    private static void flat(ItemDisplay d, float scale, float spin) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setTransformation(new Transformation(new Vector3f(0, 0.5f * scale, 0), new Quaternionf().rotationY(spin),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private static void dust(World w, Location at, int count, double spread, Color c, float size) {
        w.spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(c, size));
    }

    private static void ring(World w, Location centre, double radius, int points, Color c, float size) {
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points;
            w.spawnParticle(Particle.DUST, centre.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(c, size));
        }
    }

    // ------------------------------------------------------------------ input

    /** Called by MythicListener for every click while the Auratus is held. The old Auratus controls. */
    void handleInput(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        boolean main = inMain(p);
        if (!main && !isAuratus(p.getInventory().getItemInOffHand())) return;
        Block clicked = e.getClickedBlock();
        // Doors, chests, buttons and levers still work with a plain right-click.
        if (right && !p.isSneaking() && clicked != null && clicked.getType().isInteractable()) return;
        if (!main && (!right || !offHandFree(p.getInventory().getItemInMainHand(), a))) return;
        if (clicked != null && e.useInteractedBlock() == Event.Result.DENY) return; // a protection plugin said no
        e.setCancelled(true);
        if (p.isDead() || !enabled()) return;
        UUID id = p.getUniqueId();
        long n = now();
        long combo = Math.max(0, plugin.getConfig().getLong(path("flagpole.combo-ms"), 100));
        if (left) {
            if (n - lastLeft.getOrDefault(id, 0L) < 150) return; // one click can arrive as two events
            lastLeft.put(id, n);
            if (main && n - lastRight.getOrDefault(id, 0L) <= combo) { flagpole(p); return; } // left + right together
            if (p.isSneaking() && slamPosition(p)) { slam(p); return; } // sneak + chain in the air
            chain(p);
            return;
        }
        if (n - lastRight.getOrDefault(id, 0L) < 150) return;
        lastRight.put(id, n);
        if (main) {
            if (n - lastLeft.getOrDefault(id, 0L) <= combo) { flagpole(p); return; }
            if (p.isSneaking()) aegis(p);
            // A plain right-click does nothing: the old long-reach poke is Hauling Strike's +1 reach on every hit now.
            return;
        }
        // Off hand: right-click is the chain, so sneak + right-click is the slam in the air and the Aegis otherwise.
        if (!p.isSneaking()) chain(p);
        else if (slamPosition(p)) slam(p);
        else aegis(p);
    }

    /** High enough in the air for Gilded Slam: sneaking with the chain there slams instead of throwing a chain. */
    private boolean slamPosition(Player p) {
        return !p.isOnGround() && !p.isInWater() && !p.isFlying()
                && heightAboveGround(p.getLocation(), 64) >= cfgD("slam.min-height", 4.0);
    }

    /** With the gem in the off hand, the main hand's own right-click use wins (bows, food, pearls, placing blocks). */
    private static boolean offHandFree(ItemStack hand, Action a) {
        if (hand == null || hand.getType().isAir()) return true;
        String n = hand.getType().name();
        if (n.endsWith("_SWORD") || hand.getType() == Material.MACE) return true;
        boolean tool = n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
        // In the air a tool or a block does nothing; on a block a tool strips, paths or tills and a block is placed.
        return a == Action.RIGHT_CLICK_AIR && (tool || hand.getType().isBlock());
    }

    // ------------------------------------------------------------------ Venerated Perforators (chains)

    private void chain(Player p) {
        UUID id = p.getUniqueId();
        if (dives.containsKey(id) || hoists.containsKey(id)) return;
        long n = now();
        Charges c = charges(p);
        if (c.ready(n) == 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 0.7f, 0.6f);
            return;
        }
        Target t = aim(p);
        if (t == null) return; // nothing in range, or close enough to just hit: no charge used
        c.use(n, ms("chain.recharge-seconds", 8));
        startReel(p, t);
    }

    /** Something within melee reach in front of you: the click was a hit, not a power. */
    private boolean swingingAtSomeone(Player p) {
        AttributeInstance reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double r = (reach == null ? 3 : reach.getValue()) + 0.3;
        return p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), r, 0.2,
                e -> e != p && e instanceof LivingEntity && !(e instanceof ArmorStand)) != null;
    }

    private Target aim(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double range = Math.max(5, cfgD("chain.range", 40));
        Anchor an = anchors.get(p.getUniqueId());
        if (an != null && an.until > now() && an.at.getWorld().equals(w)) {
            Vector to = an.at.toVector().subtract(eye.toVector());
            if (to.length() <= range + 8 && AuratusMath.angleTo(dir.getX(), dir.getY(), dir.getZ(), to.getX(), to.getY(), to.getZ())
                    <= cfgD("aegis.anchor-cone-degrees", 35)) return new Target(an.at.clone(), null, true);
        }
        RayTraceResult eh = w.rayTraceEntities(eye, dir, range, 0.4,
                e -> e != p && e instanceof LivingEntity le && !(le instanceof ArmorStand) && !(le instanceof Player o && o.getGameMode() == GameMode.SPECTATOR));
        RayTraceResult bh = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double ed = eh == null ? Double.MAX_VALUE : eye.distance(eh.getHitPosition().toLocation(w));
        double bd = bh == null ? Double.MAX_VALUE : eye.distance(bh.getHitPosition().toLocation(w));
        if (eh == null && bh == null) { // nothing out there: a rattle, no charge
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 0.7f, 0.8f);
            return null;
        }
        if (eh != null && ed < bd) {
            AttributeInstance reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            double melee = Math.max(cfgD("chain.min-entity-distance", 3.5), reach == null ? 3 : reach.getValue() + 0.3);
            if (ed < melee) return null; // within reach (Hauling Strike's included): an ordinary swing
            return new Target(null, (LivingEntity) eh.getHitEntity(), false);
        }
        if (bh == null || bd < 2) return null;
        Location hit = bh.getHitPosition().toLocation(w);
        if (bh.getHitBlockFace() != null) hit.add(bh.getHitBlockFace().getDirection().multiply(0.4)); // stop short of the face
        return new Target(hit, null, false);
    }

    private void startReel(Player p, Target t) {
        UUID id = p.getUniqueId();
        stopReel(id); // a new chain replaces the one you're on
        Reel r = new Reel();
        r.point = t.point();
        r.entity = t.entity() == null ? null : t.entity().getUniqueId();
        reels.put(id, r);
        World w = p.getWorld();
        Location tip = t.entity() != null ? t.entity().getLocation().add(0, t.entity().getHeight() * 0.5, 0) : t.point();
        if (t.entity() != null && isEnemy(p, t.entity())) hooks.put(id, new Hook(t.entity().getUniqueId(), now()));
        if (t.anchor()) removeAnchor(id); // the Echo Anchor is used up
        w.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.1f, 0.7f);
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 0.6f, 1.5f);
        w.playSound(tip, Sound.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 1.2f, 0.8f);
        Sfx.play(plugin, p.getLocation(), "auratus.chain", 0.9f, 1.0f);
        w.spawnParticle(Particle.CRIT, tip, 10, 0.15, 0.15, 0.15, 0.25);
        dust(w, tip, 8, 0.2, GOLD, 1.1f);
    }

    private void tickReel(Player p, Reel r) {
        UUID id = p.getUniqueId();
        LivingEntity le = null;
        Location target;
        if (r.entity != null) {
            Entity e = Bukkit.getEntity(r.entity);
            if (!(e instanceof LivingEntity l) || !l.isValid() || l.isDead() || !l.getWorld().equals(p.getWorld())) { stopReel(id); return; }
            le = l;
            target = l.getLocation().add(0, l.getHeight() * 0.5, 0);
        } else {
            target = r.point;
        }
        if (!target.getWorld().equals(p.getWorld())) { stopReel(id); return; }
        double scale = Math.min(timeScale(p.getLocation(), id), timeScale(target, id));
        drawChain(r.chain, p.getLocation().add(0, 1.2, 0), target);
        Vector to = target.toVector().subtract(p.getLocation().toVector().add(new Vector(0, 0.9, 0)));
        double dist = to.length();
        if (!p.isOnGround()) r.airborne = true;
        boolean landed = p.isOnGround() && r.airborne && r.age > 3;
        boolean arrived = dist <= (le != null ? 2.4 : 1.6);
        if (arrived || landed || r.age >= cfgD("chain.max-reel-ticks", 40)) {
            stopReel(id);
            if (le != null && dist < 3.5) strike(p, le);
            if (!landed) p.setVelocity(r.carry.clone().multiply(cfgD("chain.arrival-momentum", 0.45) * scale)); // keep some speed, no dead stop
            p.setFallDistance(0);
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, SoundCategory.PLAYERS, 0.8f, 1.3f);
            return;
        }
        Vector v = dist > 1e-6 ? to.normalize() : new Vector();
        if (r.age < 1 && p.isOnGround()) v.setY(Math.max(v.getY(), 0.3)).normalize(); // lift off cleanly
        v.multiply(AuratusMath.reelSpeed(r.age, cfgD("chain.reel-speed", 1.1), cfgD("chain.ramp-ticks", 4)));
        r.carry = v.clone();
        p.setVelocity(v.multiply(scale)); // slowed inside a Time Dilate field
        p.setFallDistance(0);
        r.age += Math.max(0.05, scale);
        if (tick % 3 == 0) p.getWorld().spawnParticle(Particle.WAX_OFF, p.getLocation().add(0, 1, 0), 2, 0.2, 0.3, 0.2, 0);
    }

    /** Reaching an enemy you hooked: a hit, Angels Grasp, and a tug toward you. */
    private void strike(Player p, LivingEntity v) {
        if (!isEnemy(p, v)) return; // friends, animals and villagers are a ride, not a target
        plugin.listener().abilityHit(v, cfgD("chain.hit-damage", 3.0), p);
        grasp(v);
        Vector pull = p.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (pull.lengthSquared() > 1e-4) v.setVelocity(pull.normalize().multiply(0.45).setY(0.2));
        World w = v.getWorld();
        Location at = v.getLocation().add(0, v.getHeight() * 0.6, 0);
        w.playSound(at, Sound.BLOCK_CHAIN_BREAK, SoundCategory.PLAYERS, 1.2f, 0.8f);
        w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0f, 1.1f);
        w.spawnParticle(Particle.ENCHANTED_HIT, at, 16, 0.3, 0.4, 0.3, 0.3);
        dust(w, at, 12, 0.35, GOLD, 1.3f);
    }

    private void stopReel(UUID id) {
        Reel r = reels.remove(id);
        if (r != null) r.chain.clear();
    }

    /** Links and hook along hand -> tip (pack models), or a line of gold dust. */
    private void drawChain(Chain c, Location from, Location to) {
        World w = from.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        double len = delta.length();
        if (len < 0.5) { c.clear(); return; }
        Vector dir = delta.clone().normalize();
        if (!cfgB("chain.show-models", true)) {
            c.clear();
            int points = Math.max(2, Math.min(64, (int) (len * 2)));
            for (int i = 0; i < points; i++)
                w.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(len * i / points)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 3 == 0 ? PALE : GOLD, 0.9f));
            return;
        }
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, -1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
        float scale = (float) cfgD("chain.link-scale", 1.0);
        Transformation pose = new Transformation(new Vector3f(), rot, new Vector3f(scale, scale, scale), new Quaternionf());
        double spacing = Math.max(0.2, cfgD("chain.link-spacing", 1.0));
        int n = Math.max(1, Math.min(60, (int) Math.ceil(len / spacing)));
        while (c.links.size() < n) c.links.add(display(from, CMD_LINK));
        while (c.links.size() > n) { ItemDisplay d = c.links.remove(c.links.size() - 1); if (d.isValid()) d.remove(); }
        for (int i = 0; i < n; i++) {
            ItemDisplay link = c.links.get(i);
            if (!link.isValid()) continue;
            link.teleport(from.clone().add(dir.clone().multiply(len * (i + 0.5) / n)));
            link.setTransformation(pose);
        }
        if (c.hook == null || !c.hook.isValid()) c.hook = display(to, CMD_HOOK);
        c.hook.teleport(to);
        c.hook.setTransformation(pose);
        if (tick % 4 == 0) w.spawnParticle(Particle.WAX_ON, from.clone().add(dir.clone().multiply(len * Math.random())), 1, 0.05, 0.05, 0.05, 0);
    }

    // ------------------------------------------------------------------ Echoing Aegis

    private void aegis(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        if (aegisReady.getOrDefault(id, 0L) > n || aegis.containsKey(id)) return;
        aegisReady.put(id, n + AuratusMath.aegisCooldownMs(false, cfgD("aegis.cooldown-seconds", 15), cfgD("aegis.success-cooldown-seconds", 10)));
        Aegis a = new Aegis();
        a.castAt = n;
        a.until = n + Math.max(100, plugin.getConfig().getLong(path("aegis.window-ms"), 1000));
        a.halo = display(p.getLocation().add(0, 1.0, 0), CMD_HALO);
        flat(a.halo, 0.2f, 0);
        aegis.put(id, a);
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 1.5f);
        w.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.3f);
        Sfx.play(plugin, p.getLocation(), "auratus.aegis", 1.0f, 1.0f);
    }

    private void tickAegis(Player p, Aegis a) {
        a.age++;
        Location at = p.getLocation().add(0, 1.0, 0);
        if (a.halo != null && a.halo.isValid()) {
            a.halo.teleport(at);
            float grow = Math.min(1f, a.age / 3f);
            flat(a.halo, (a.parried ? 2.9f : 2.4f) * (0.2f + 0.8f * grow), (float) (a.age * 0.25));
        }
        if (a.age % 2 == 0) ring(p.getWorld(), at, 1.25, 16, a.parried ? PALE : GOLD, 0.8f);
        if (now() < a.until) return;
        aegis.remove(p.getUniqueId());
        if (a.halo != null && a.halo.isValid()) a.halo.remove();
        if (!a.parried) p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    /** A melee hit or a projectile during the window is parried: blocked, partly sent back, and rewarded once. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttacked(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || reflecting.contains(p.getUniqueId())) return;
        Aegis a = aegis.get(p.getUniqueId());
        if (a == null || a.until < now() || !holds(p)) return;
        LivingEntity attacker = e.getDamager() instanceof LivingEntity l ? l
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof LivingEntity s ? s : null;
        if (attacker == null || attacker.equals(p)) return;
        e.setCancelled(true);
        World w = p.getWorld();
        double back = e.getDamage() * Math.max(0, cfgD("aegis.reflect", 0.5));
        if (back > 0 && attacker.isValid() && !attacker.isDead()) {
            reflecting.add(attacker.getUniqueId());
            try { attacker.damage(back, p); } finally { reflecting.remove(attacker.getUniqueId()); }
        }
        Vector away = attacker.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (away.lengthSquared() > 1e-4) attacker.setVelocity(away.normalize().multiply(0.7).setY(0.25));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(cfgD("aegis.attacker-slow-seconds", 1.5) * 20), 1, false, true, true));
        if (attacker instanceof Player ap && e.getDamager() == ap) wearWeapon(ap);
        w.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 1.3f);
        w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.1, 0), 18, 0.3, 0.4, 0.3, 0.08);
        if (a.parried) return;
        a.parried = true;
        // Reward, once per Aegis: heal, speed, a cleanse (Divine Purity) and an Echo Anchor above you.
        double heal = Math.max(0, cfgD("aegis.heal-hp", 5.0));
        p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) Math.round(cfgD("aegis.speed-seconds", 3) * 20), 1, false, false, true));
        cleanse(p);
        aegisReady.put(p.getUniqueId(), a.castAt + AuratusMath.aegisCooldownMs(true, cfgD("aegis.cooldown-seconds", 15), cfgD("aegis.success-cooldown-seconds", 10)));
        spawnAnchor(p);
        w.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        Sfx.play(plugin, p.getLocation(), "auratus.parry", 1.0f, 1.0f);
        dust(w, p.getLocation().add(0, 1, 0), 30, 0.6, GOLD, 1.4f);
    }

    private void wearWeapon(Player attacker) {
        int loss = cfgI("aegis.weapon-durability-loss", 30);
        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (loss <= 0 || main.getType().getMaxDurability() <= 0 || !(main.getItemMeta() instanceof Damageable d) || d.isUnbreakable()) return;
        d.setDamage(Math.min(main.getType().getMaxDurability() - 1, d.getDamage() + loss));
        main.setItemMeta(d);
    }

    /** The Echo Anchor: a gold ring up to 8 blocks above you for 5s; a chain thrown toward it latches on. */
    private void spawnAnchor(Player p) {
        removeAnchor(p.getUniqueId());
        Location base = p.getLocation();
        Location at = base.clone().add(0, 2, 0);
        int height = (int) Math.round(cfgD("aegis.anchor-height", 8.0));
        for (int up = 2; up <= height; up++) { // as high as there is open air
            Block b = base.getBlock().getRelative(0, up, 0);
            if (!b.isPassable()) break;
            at = base.clone().add(0, up, 0);
        }
        Anchor an = new Anchor();
        an.at = at;
        an.until = now() + ms("aegis.anchor-seconds", 5);
        an.ring = display(at, CMD_HALO);
        flat(an.ring, 1.4f, 0);
        anchors.put(p.getUniqueId(), an);
        p.getWorld().spawnParticle(Particle.END_ROD, at, 20, 0.4, 0.4, 0.4, 0.05);
    }

    private void removeAnchor(UUID id) {
        Anchor an = anchors.remove(id);
        if (an != null && an.ring != null && an.ring.isValid()) an.ring.remove();
    }

    private void tickAnchor(UUID id, Anchor an) {
        if (an.until <= now() || an.ring == null || !an.ring.isValid()) { removeAnchor(id); return; }
        flat(an.ring, 1.4f, (float) (tick * 0.12));
        if (tick % 3 == 0) {
            World w = an.at.getWorld();
            w.spawnParticle(Particle.END_ROD, an.at, 1, 0.3, 0.1, 0.3, 0.01);
            ring(w, an.at, 0.75, 10, GOLD, 0.7f);
        }
    }

    // ------------------------------------------------------------------ Gilded Slam

    private void slam(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        if (dives.containsKey(id)) return;
        long wait = slamReady.getOrDefault(id, 0L) - n;
        if (wait > 0) { hint(p, "Slam ready in " + fmt(wait)); return; }
        Location target = slamTarget(p);
        if (target == null) return;
        slamReady.put(id, n + ms("slam.cooldown-seconds", 0));
        stopReel(id);
        Dive d = new Dive();
        d.target = target;
        d.startY = p.getLocation().getY();
        d.decal = display(target.clone().add(0, 0.06, 0), CMD_SMASH);
        flat(d.decal, 0.4f, 0);
        dives.put(id, d);
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.2f, 0.5f);
        w.playSound(p.getLocation(), Sound.ITEM_MACE_SMASH_AIR, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** Where you look if that is ground below you within range, otherwise straight down. */
    private Location slamTarget(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        RayTraceResult hit = w.rayTraceBlocks(eye, eye.getDirection(), Math.max(4, cfgD("slam.range", 24)), FluidCollisionMode.ALWAYS, true);
        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() == BlockFace.UP && hit.getHitPosition().getY() < p.getLocation().getY() - 1) {
            Block b = hit.getHitBlock();
            return new Location(w, hit.getHitPosition().getX(), b.getY() + 1, hit.getHitPosition().getZ());
        }
        return groundBelow(p.getLocation(), 64);
    }

    private static Location groundBelow(Location from, int depth) {
        World w = from.getWorld();
        int x = from.getBlockX(), z = from.getBlockZ();
        for (int y = from.getBlockY(); y > Math.max(w.getMinHeight(), from.getBlockY() - depth); y--) {
            Block below = w.getBlockAt(x, y - 1, z);
            if (below.getType().isSolid() || below.isLiquid()) return new Location(w, from.getX(), y, from.getZ());
        }
        return null;
    }

    private static double heightAboveGround(Location at, int depth) {
        Location g = groundBelow(at, depth);
        return g == null ? depth : at.getY() - g.getY();
    }

    private void tickDive(Player p, Dive d) {
        UUID id = p.getUniqueId();
        if (!d.target.getWorld().equals(p.getWorld())) { stopDive(id); return; }
        double scale = timeScale(p.getLocation(), id);
        Vector to = d.target.toVector().subtract(p.getLocation().toVector());
        double total = Math.max(1, d.startY - d.target.getY());
        double left = Math.max(0, p.getLocation().getY() - d.target.getY());
        flat(d.decal, (float) (0.4 + 2.6 * (1 - Math.min(1, left / total))), (float) (d.age * 0.15));
        drawChain(d.chain, p.getLocation().add(0, 1.0, 0), d.target);
        if ((p.isOnGround() && d.age > 2) || p.isInWater() || p.isInLava() || to.length() < 1.0 || d.age > 60) { impact(p, d); return; }
        p.setVelocity(to.normalize().multiply(Math.max(0.3, cfgD("slam.dive-speed", 1.6)) * scale));
        p.setFallDistance(0);
        d.age += Math.max(0.05, scale);
        if (tick % 2 == 0) dust(p.getWorld(), p.getLocation().add(0, 1, 0), 3, 0.25, GOLD, 1.2f);
    }

    private void impact(Player p, Dive d) {
        UUID id = p.getUniqueId();
        dives.remove(id);
        d.chain.clear();
        Location at = p.getLocation();
        World w = p.getWorld();
        double radius = Math.max(1, cfgD("slam.radius", 5.0));
        double centre = cfgD("slam.damage-center", 6.0), edge = cfgD("slam.damage-edge", 3.0), launch = cfgD("slam.launch", 0.45);
        for (Entity e : p.getNearbyEntities(radius, 3, radius)) {
            if (!(e instanceof LivingEntity v) || !isEnemy(p, v)) continue;
            Vector out = v.getLocation().toVector().subtract(at.toVector()).setY(0);
            double dist = out.length();
            double dmg = AuratusMath.slamDamage(centre, edge, dist, radius);
            if (dmg <= 0) continue;
            plugin.listener().abilityHit(v, dmg, p);
            if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
            v.setVelocity(out.normalize().multiply(0.4).setY(launch));
            grasp(v);
        }
        fallGrace.put(id, now() + 1500);
        p.setFallDistance(0);
        w.playSound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.3f, 0.8f);
        w.playSound(at, Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.9f, 1.4f);
        Sfx.play(plugin, at, "auratus.slam", 1.0f, 1.0f);
        Block ground = at.getBlock().getRelative(BlockFace.DOWN);
        if (ground.getType().isSolid()) w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 50, radius * 0.4, 0.1, radius * 0.4, 0.2, ground.getBlockData());
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.3, 0), 24, radius * 0.3, 0.2, radius * 0.3, 0.12);
        // A gold shockwave: three rings racing out to the edge, then the ring on the ground fades.
        ItemDisplay decal = d.decal;
        flat(decal, (float) (radius * 0.9), 0);
        for (int i = 0; i < 4; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ring(w, at.clone().add(0, 0.2, 0), radius * (step + 1) / 4.0, 14 + step * 8, step % 2 == 0 ? GOLD : PALE, 1.4f);
                if (step == 3 && decal != null && decal.isValid()) flat(decal, 0.05f, 0);
            }, 1L + i * 2L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (decal != null && decal.isValid()) decal.remove(); }, 12L);
    }

    private void stopDive(UUID id) {
        Dive d = dives.remove(id);
        if (d == null) return;
        d.chain.clear();
        if (d.decal != null && d.decal.isValid()) d.decal.remove();
    }

    // ------------------------------------------------------------------ Flagpole

    private void flagpole(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        if (flagReady.getOrDefault(id, 0L) > n) return;
        Hook h = hooks.get(id);
        if (h == null || n - h.at() > ms("flagpole.hook-memory-seconds", 5)) {
            if (!swingingAtSomeone(p)) hint(p, "Hook someone with your chain first"); // a sneaking melee hit stays quiet
            return;
        }
        Entity e = Bukkit.getEntity(h.victim());
        if (!(e instanceof LivingEntity v) || !v.isValid() || v.isDead() || !v.getWorld().equals(p.getWorld())
                || v.getLocation().distance(p.getLocation()) > cfgD("flagpole.range", 24) || !isEnemy(p, v) || hoists.containsKey(v.getUniqueId())) {
            hint(p, "They're out of reach");
            return;
        }
        flagReady.put(id, n + ms("flagpole.cooldown-seconds", 25));
        hooks.remove(id);
        Hoist hs = new Hoist();
        hs.owner = id;
        hs.baseY = v.getLocation().getY();
        double top = hs.baseY;
        int height = (int) Math.round(cfgD("flagpole.height", 5.0));
        for (int up = 1; up <= height; up++) { // never into a ceiling
            Block b = v.getLocation().getBlock().getRelative(0, up + 1, 0);
            if (!b.isPassable()) break;
            top = hs.baseY + up;
        }
        hs.topY = top;
        int hold = (int) Math.round(cfgD("flagpole.hold-seconds", 2.5) * 20);
        hs.left = hold + 10; // half a second to rise
        hoists.put(v.getUniqueId(), hs);
        setModifier(v, Attribute.GRAVITY, hoistKey, -1);
        grasp(v, hs.left / 20.0 + 1);
        World w = v.getWorld();
        w.playSound(v.getLocation(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.3f, 0.6f);
        w.playSound(v.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.9f, 0.8f);
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.7f, 1.4f);
    }

    private void tickHoist(LivingEntity v, Hoist hs) {
        Location l = v.getLocation();
        Vector vel = v.getVelocity();
        double vy = AuratusMath.hoistVelocity(l.getY(), hs.topY, 0.6, 0.3);
        v.setVelocity(new Vector(vel.getX() * 0.5, vy, vel.getZ() * 0.5)); // hits still push them, but they can't run
        v.setFallDistance(0);
        if (tick % 2 == 0) { // gold chains spiralling up from the ground to them
            World w = v.getWorld();
            double h = Math.max(0.5, l.getY() - hs.baseY + 1);
            for (int i = 0; i < 10; i++) {
                double f = i / 10.0, a = tick * 0.25 + f * Math.PI * 4;
                w.spawnParticle(Particle.DUST, new Location(w, l.getX() + Math.cos(a) * 0.8, hs.baseY + f * h, l.getZ() + Math.sin(a) * 0.8),
                        1, 0, 0, 0, 0, new Particle.DustOptions(i % 3 == 0 ? PALE : GOLD, 1.0f));
            }
        }
        if (--hs.left <= 0) release(v.getUniqueId(), v);
    }

    private void release(UUID victim, LivingEntity v) {
        hoists.remove(victim);
        if (v == null) return;
        clearModifier(v, Attribute.GRAVITY, hoistKey);
        if (v.isValid()) {
            v.setVelocity(new Vector(0, -0.1, 0));
            v.getWorld().playSound(v.getLocation(), Sound.BLOCK_CHAIN_BREAK, SoundCategory.PLAYERS, 1.0f, 0.9f);
        }
    }

    private void setModifier(LivingEntity e, Attribute type, NamespacedKey key, double amount) {
        AttributeInstance a = e.getAttribute(type);
        if (a == null) return;
        if (a.getModifier(key) != null) a.removeModifier(key);
        a.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void clearModifier(LivingEntity e, Attribute type, NamespacedKey key) {
        AttributeInstance a = e.getAttribute(type);
        if (a != null && a.getModifier(key) != null) a.removeModifier(key);
    }

    // ------------------------------------------------------------------ passives

    /** Feathered Fall (unchanged): 90% less fall damage while holding the gem. A slam landing is free. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p) || !enabled()) return;
        Long grace = fallGrace.get(p.getUniqueId());
        if (grace != null && grace >= now()) { e.setCancelled(true); return; }
        if (holds(p)) e.setDamage(e.getDamage() * cfgD("passives.feathered-fall-damage-multiplier", 0.10));
    }

    /** Hauling Strike: remember full-strength swings (the attack resets the meter before the hit lands). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        if (!e.willAttack() || !enabled() || !holds(p) || p.getAttackCooldown() < 0.9f) return;
        fullSwing.put(p.getUniqueId(), Bukkit.getCurrentTick());
        int jam = cfgI("passives.hauling-strike-shield-ticks", 100);
        if (jam > 0 && e.getAttacked() instanceof Player v && v.isBlocking()) {
            Bukkit.getScheduler().runTask(plugin, () -> { // after the hit: the shield drops and stays down
                if (!v.isOnline()) return;
                v.setCooldown(Material.SHIELD, Math.max(v.getCooldown(Material.SHIELD), jam));
                v.clearActiveItem();
                v.getWorld().playSound(v.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.8f, 1.2f);
            });
        }
    }

    /** Hauling Strike: a full-strength hit pulls the target toward you instead of knocking them away. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityPushedByEntityAttackEvent e) {
        if (e.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK || !(e.getPushedBy() instanceof Player p)) return;
        Integer swung = fullSwing.get(p.getUniqueId());
        double pull = cfgD("passives.hauling-strike-pull", 0.3);
        if (swung == null || swung != Bukkit.getCurrentTick() || pull <= 0 || !holds(p)) return;
        Vector in = p.getLocation().toVector().subtract(e.getEntity().getLocation().toVector()).setY(0);
        if (in.lengthSquared() < 0.25) return; // already on top of you
        Vector kb = e.getKnockback();
        in.normalize().multiply(pull);
        e.setKnockback(new Vector(in.getX(), Math.min(kb.getY(), 0.3), in.getZ()));
    }

    /** Angels Grasp: no ender pearls, wind charges, chorus fruit or tridents for a few seconds. */
    private void grasp(LivingEntity v) { grasp(v, cfgD("passives.angels-grasp-seconds", 4)); }

    private void grasp(LivingEntity v, double seconds) {
        if (seconds <= 0) return;
        grasped.merge(v.getUniqueId(), now() + (long) (seconds * 1000), Math::max);
        if (!(v instanceof Player p)) return;
        int ticks = (int) Math.round(seconds * 20);
        for (Material m : new Material[]{Material.ENDER_PEARL, Material.WIND_CHARGE, Material.CHORUS_FRUIT, Material.TRIDENT})
            p.setCooldown(m, Math.max(p.getCooldown(m), ticks));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGraspedTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if ((c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
                && grasped.getOrDefault(e.getPlayer().getUniqueId(), 0L) > now()) e.setCancelled(true);
    }

    /** Divine Purity: harmful effects on you last half as long. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || purifying.contains(p.getUniqueId()) || !enabled()) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        PotionEffect eff = e.getNewEffect();
        if (eff == null || eff.getType().getCategory() != PotionEffectTypeCategory.HARMFUL || eff.isInfinite() || eff.getDuration() < 4 || !holds(p)) return;
        int shorter = AuratusMath.purifiedDuration(eff.getDuration(), cfgD("passives.divine-purity-multiplier", 0.5));
        if (shorter >= eff.getDuration()) return;
        e.setCancelled(true);
        PotionEffect replaced = new PotionEffect(eff.getType(), shorter, eff.getAmplifier(), eff.isAmbient(), eff.hasParticles(), eff.hasIcon());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            purifying.add(p.getUniqueId());
            try { p.addPotionEffect(replaced); } finally { purifying.remove(p.getUniqueId()); }
        });
    }

    private void cleanse(Player p) {
        for (PotionEffect eff : new ArrayList<>(p.getActivePotionEffects()))
            if (eff.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) p.removePotionEffect(eff.getType());
    }

    // ------------------------------------------------------------------ tick, HUD, cleanup

    void tick() {
        tick++;
        if (!enabled()) return;
        for (UUID id : new ArrayList<>(reels.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) { stopReel(id); continue; }
            Reel r = reels.get(id);
            if (r != null) tickReel(p, r);
        }
        for (UUID id : new ArrayList<>(dives.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) { stopDive(id); continue; }
            Dive d = dives.get(id);
            if (d != null) tickDive(p, d);
        }
        for (UUID id : new ArrayList<>(aegis.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Aegis a = aegis.get(id);
            if (p == null || p.isDead()) { aegis.remove(id); if (a != null && a.halo != null && a.halo.isValid()) a.halo.remove(); continue; }
            tickAegis(p, a);
        }
        for (UUID id : new ArrayList<>(anchors.keySet())) tickAnchor(id, anchors.get(id));
        for (UUID id : new ArrayList<>(hoists.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity v) || !v.isValid() || v.isDead()) { release(id, e instanceof LivingEntity l ? l : null); continue; }
            tickHoist(v, hoists.get(id));
        }
        if (tick % 5 == 0) {
            long n = now();
            grasped.entrySet().removeIf(en -> {
                if (en.getValue() <= n) return true;
                Entity e = Bukkit.getEntity(en.getKey());
                if (!(e instanceof LivingEntity v) || !v.isValid()) return true;
                ring(v.getWorld(), v.getLocation().add(0, 0.1, 0), 0.45, 8, DEEP, 0.7f); // gold shackles at their feet
                return false;
            });
            fallGrace.values().removeIf(t -> t < n);
            for (Player p : Bukkit.getOnlinePlayers()) updateReach(p);
        }
    }

    /** Hauling Strike's extra reach, only while the gem is held. */
    private void updateReach(Player p) {
        boolean want = holds(p) && cfgD("passives.hauling-strike-extra-reach", 1.0) > 0;
        if (want == reachApplied.contains(p.getUniqueId())) return;
        AttributeInstance a = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (a == null) return;
        if (a.getModifier(reachKey) != null) a.removeModifier(reachKey);
        if (want) {
            a.addTransientModifier(new AttributeModifier(reachKey, cfgD("passives.hauling-strike-extra-reach", 1.0), AttributeModifier.Operation.ADD_NUMBER));
            reachApplied.add(p.getUniqueId());
        } else {
            reachApplied.remove(p.getUniqueId());
        }
    }

    // The old Auratus HUD glyphs from the main pack (default.json: blissgems:icons/peforators.png and aegis.png).
    private static final String GLYPH_PERFORATORS = "\u22B5", GLYPH_AEGIS = "\u22B4";

    /**
     * The old Auratus action bar: chains and Aegis with the pack's own icons. Flagpole's cooldown, a slam cooldown (if
     * one is set) and the Echo Anchor are added only while they matter.
     */
    Component hudComponent(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        String h = hintText.get(id);
        if (h != null && n - lastHint.getOrDefault(id, 0L) < 1500) return Component.text(h, net.kyori.adventure.text.format.TextColor.color(0xFFC846));
        Charges c = charges(p);
        String chains = dives.containsKey(id) ? "§cActive..." : c.ready(n) > 0 ? "§aReady!" : "§b" + fmt(c.nextIn(n));
        long aegisLeft = aegisReady.getOrDefault(id, 0L) - n;
        String guard = aegis.containsKey(id) ? "§cActive..." : aegisLeft > 0 ? "§b" + fmt(aegisLeft) : "§aReady!";
        StringBuilder msg = new StringBuilder("§f" + GLYPH_PERFORATORS + " §bAuratus " + chains + " §7➜ §f" + GLYPH_AEGIS + " " + guard);
        long slamLeft = slamReady.getOrDefault(id, 0L) - n, flagLeft = flagReady.getOrDefault(id, 0L) - n;
        if (slamLeft > 0) msg.append(" §7| §6Slam §b").append(fmt(slamLeft));
        if (flagLeft > 0) msg.append(" §7| §6Flagpole §b").append(fmt(flagLeft));
        Anchor an = anchors.get(id);
        if (an != null && an.until > n) msg.append(" §7| §6Anchor §b").append(fmt(an.until - n));
        return LEGACY.deserialize(msg.toString());
    }

    private static String fmt(long ms) { return Math.max(1, (ms + 999) / 1000) + "s"; }

    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        stopReel(id);
        stopDive(id);
        Aegis a = aegis.remove(id);
        if (a != null && a.halo != null && a.halo.isValid()) a.halo.remove();
        removeAnchor(id);
        if (hoists.containsKey(id)) release(id, p);
        for (Map.Entry<UUID, Hoist> en : new ArrayList<>(hoists.entrySet())) // they let go of anyone they were holding
            if (en.getValue().owner.equals(id)) release(en.getKey(), Bukkit.getEntity(en.getKey()) instanceof LivingEntity l ? l : null);
        hooks.remove(id);
        fullSwing.remove(id);
        hintText.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        cleanup(p);
        grasped.remove(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (a != null && a.getModifier(reachKey) != null) a.removeModifier(reachKey);
        reachApplied.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { cleanup(e.getEntity()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { cleanup(e.getPlayer()); }

    void shutdown() {
        for (UUID id : new ArrayList<>(reels.keySet())) stopReel(id);
        for (UUID id : new ArrayList<>(dives.keySet())) stopDive(id);
        for (Aegis a : aegis.values()) if (a.halo != null && a.halo.isValid()) a.halo.remove();
        aegis.clear();
        for (UUID id : new ArrayList<>(anchors.keySet())) removeAnchor(id);
        for (UUID id : new ArrayList<>(hoists.keySet())) release(id, Bukkit.getEntity(id) instanceof LivingEntity l ? l : null);
        for (UUID id : reachApplied) {
            Player p = Bukkit.getPlayer(id);
            AttributeInstance a = p == null ? null : p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            if (a != null && a.getModifier(reachKey) != null) a.removeModifier(reachKey);
        }
        reachApplied.clear();
    }
}
