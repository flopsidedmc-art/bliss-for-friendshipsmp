package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
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
import org.bukkit.entity.Firework;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Auratus gem, rebuilt: the same chains, Aegis, slam and Flagpole, on the old Auratus controls.
 *
 * Gem in the main hand: left-click throws a chain, right-click Echoing Aegis, sneak + chain in the air Gilded Slam.
 * A chain hooks whatever it hits (an enemy takes the hit at once) and stays: right-click flies you there, the
 * swap-hands key hauls a hooked creature to you, or leave it. Pull an enemy in and catch them with the Aegis (it
 * appears 3 blocks ahead): do nothing and they're hoisted (Flagpole); chain again and they're launched skyward with
 * a hit built to kill a full Prot IV diamond player. Gem in the off hand: right-click chains, sneak +
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
    private final NamespacedKey fireworkKey = new NamespacedKey("soulbound-gems", "auratus_slam_firework");

    private final Map<UUID, Charges> chains = new HashMap<>();
    private final Map<UUID, Long> aegisReady = new HashMap<>(), slamReady = new HashMap<>(), flagReady = new HashMap<>();
    private final Map<UUID, Long> lastLeft = new HashMap<>(), lastRight = new HashMap<>(), lastHint = new HashMap<>();
    private final Map<UUID, Aegis> aegis = new HashMap<>();
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private final Map<UUID, Reel> reels = new HashMap<>();
    private final Map<UUID, Dive> dives = new HashMap<>();
    private final Map<UUID, Hook> hooks = new HashMap<>(); // holder -> the enemy their chain last latched onto
    private final Map<UUID, Tether> tethers = new HashMap<>(); // holder -> where their hook still is
    private final Map<UUID, Combo> combos = new HashMap<>(); // holder -> an enemy their Aegis just caught after a pull
    private final Map<UUID, Hoist> hoists = new HashMap<>(); // hoisted entity -> its Flagpole
    private final Map<UUID, Long> grasped = new HashMap<>(); // Angels Grasp: entity -> until (ms)
    private final Map<UUID, Long> fallGrace = new HashMap<>(); // a slam landing never hurts its caster
    private final Map<UUID, Integer> fullSwing = new HashMap<>(); // server tick of the holder's last full-strength swing
    private final Set<UUID> reachApplied = new HashSet<>();
    private final Set<UUID> purifying = new HashSet<>();
    private final Set<UUID> reflecting = new HashSet<>();
    private final Set<BukkitTask> sparkTasks = new HashSet<>(); // the slam's delayed sparks (each ends itself in 10 ticks)
    private static final Color ICE = Color.fromRGB(0xDDE6FF); // the slam sphere's cold fade
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
        /** Takes over another chain's models (the hook goes on being drawn, no flicker). */
        void take(Chain o) {
            links.addAll(o.links);
            o.links.clear();
            if (o.hook != null) { if (hook != null && hook.isValid()) hook.remove(); hook = o.hook; o.hook = null; }
        }
    }
    /**
     * A thrown hook flying out to what it will bite (visuals.chain-travel). The hook model stays where it was spawned
     * (the hand) and its translation is interpolated to the landing spot: set once, set again only if the target moves.
     * The links are spawned once each, where they will lie, as the hook passes. The bite (damage, sounds) waits for it.
     */
    private static final class Flight {
        Target target; Location from; int age, ticks; double bite; boolean go, haul;
        ItemDisplay hook; Vector3f aimedAt; ItemDisplay[] strands = new ItemDisplay[0];
    }
    /** A released or expired chain reeling back to the hand (visuals.chain-reel-in): links go as the hook passes them. */
    private static final class Retract { ItemDisplay hook; long hookGone; final List<ItemDisplay> links = new ArrayList<>(); final List<Long> gone = new ArrayList<>(); }
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Retract> retracts = new HashMap<>();
    private final List<ItemDisplay> removeNextTick = new ArrayList<>(); // a landed flight's hook, gone once the tether's own is up
    private static final class Reel { Location point; UUID entity; boolean hooked; double age, bite; int blocked; boolean airborne; Vector carry = new Vector(); final Chain chain = new Chain(); }
    /** A hook left in an enemy (victim) or a block / the Echo Anchor (point), until it lets go or you go or haul. */
    private static final class Tether { UUID victim; Location point; long until; double age, bite; int blocked; boolean hauling; Vector carry = new Vector(); final Chain chain = new Chain(); }
    /** The Aegis caught an enemy you'd just pulled: Flagpole when the window ends, the launch if you chain again first. */
    private static final class Combo { UUID victim; long until; }
    private static final class Dive { Location target; double startY, age; ItemDisplay decal; final Chain chain = new Chain(); }
    private static final class Aegis { long castAt, until; int age; boolean parried; Location at; Vector dir; ItemDisplay halo; final Set<UUID> struck = new HashSet<>(); }
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
        String[] keep = {"passives.feathered-fall-damage-multiplier", "chain.show-models", "chain.link-scale"};
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

    /** "Not now" (no hook for Flagpole, slam on cooldown): a soft click only you hear. No words on the action bar. */
    private void deny(Player p) {
        long n = now();
        if (n - lastHint.getOrDefault(p.getUniqueId(), 0L) < 600) return;
        lastHint.put(p.getUniqueId(), n);
        GemSounds.playTo(plugin, "auratus.not-ready", p);
    }

    private ItemStack model(int cmd) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(cmd);
        it.setItemMeta(m);
        return it;
    }

    /**
     * A full-bright pack model that moves smoothly between teleports. It is spawned facing nowhere (yaw and pitch 0):
     * a display turns its model by its own facing before its transformation, so a location copied from a player
     * (who faces somewhere) would turn every model a second time.
     */
    private ItemDisplay display(Location at, int cmd) {
        return at.getWorld().spawn(unturned(at), ItemDisplay.class, d -> {
            d.setItemStack(model(cmd));
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setInterpolationDuration(2);
        });
    }

    private static Location unturned(Location at) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        return l;
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

    /** Called by MythicListener for every click while the Auratus is held. */
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
        if (left) {
            if (n - lastLeft.getOrDefault(id, 0L) < 150) return; // one click can arrive as two events
            lastLeft.put(id, n);
            if (p.isSneaking() && slamPosition(p)) { slam(p); return; } // sneak + chain in the air
            chain(p);
            return;
        }
        if (n - lastRight.getOrDefault(id, 0L) < 150) return;
        lastRight.put(id, n);
        Tether held = tethers.get(id);
        boolean go = held != null && !held.hauling && !p.isSneaking(); // right-click while a hook is in: go there
        Flight flying = p.isSneaking() ? null : flights.get(id); // still flying out: go once it lands
        if (main) {
            if (flying != null) flying.go = true;
            else if (go) flyToHook(p, held);
            else aegis(p); // sneak + right-click still parries while hooked
            return;
        }
        // Off hand: right-click throws the chain (and goes, while hooked); sneak + right-click slams in the air, else the Aegis.
        if (!p.isSneaking()) { if (flying != null) flying.go = true; else if (go) flyToHook(p, held); else chain(p); }
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
        Combo cb = combos.get(id);
        if (cb != null) { finisher(p, cb); return; } // chain again after the Aegis caught them: the launch, not Flagpole
        long n = now();
        Charges c = charges(p);
        if (c.ready(n) == 0) {
            GemSounds.playTo(plugin, "auratus.not-ready", p);
            return;
        }
        Target t = aim(p);
        if (t == null) return; // nothing in range, or close enough to just hit: no charge used
        c.use(n, ms("chain.recharge-seconds", 8));
        if (travelOn()) launch(p, t);
        else hook(p, t, true);
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
            GemSounds.playTo(plugin, "auratus.not-ready", p);
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

    /**
     * The chain bites and stays: in an enemy (who takes the hit now: damage and a short Angels Grasp), in another
     * creature, in a block or in the Echo Anchor. Then right-click to go there, the swap-hands key to haul a creature
     * to you, or leave it: it slides out after chain.hook-seconds, and snaps if a wall gets between you.
     */
    private Tether hook(Player p, Target t, boolean throwSound) {
        UUID id = p.getUniqueId();
        stopReel(id);
        stopTether(id);
        Tether h = new Tether();
        h.until = now() + ms("chain.hook-seconds", 6);
        World w = p.getWorld();
        Location tip;
        LivingEntity v = t.entity();
        if (v != null) {
            h.victim = v.getUniqueId();
            tip = v.getLocation().add(0, v.getHeight() * 0.6, 0);
            if (isEnemy(p, v)) {
                hooks.put(id, new Hook(v.getUniqueId(), now()));
                plugin.listener().abilityHit(v, cfgD("chain.hit-damage", 3.0), p);
                grasp(v, cfgD("passives.angels-grasp-chain-seconds", 2));
                GemSounds.play(plugin, "auratus.chain-hook-enemy", tip); // the heavy clink, at them
                w.spawnParticle(Particle.ENCHANTED_HIT, tip, 16, 0.3, 0.4, 0.3, 0.3);
            }
        } else {
            h.point = t.point();
            tip = t.point();
            if (t.anchor()) removeAnchor(id); // the Echo Anchor is used up
            else h.bite = BITE; // the point stands off the block's face; the drawn hook reaches it
        }
        tethers.put(id, h);
        if (throwSound) GemSounds.play(plugin, "auratus.chain-throw", p.getLocation()); // the lighter throw, at you
        w.spawnParticle(Particle.CRIT, tip, 10, 0.15, 0.15, 0.15, 0.25);
        dust(w, tip, 10, 0.25, GOLD, 1.2f);
        return h;
    }

    // ---- the flight out, the release and the reel-in

    private boolean travelOn() {
        return cfgB("visuals.chain-travel", true) && cfgB("chain.show-models", true) && cfgI("visuals.chain-travel-ticks", 5) > 1;
    }

    /** Where the hook bites right now: the creature's middle (it may have moved) or the point; null if the creature is gone. */
    private static Location biteAt(Target t) {
        LivingEntity v = t.entity();
        if (v == null) return t.point();
        if (!v.isValid() || v.isDead()) return null;
        return v.getLocation().add(0, v.getHeight() * 0.5, 0);
    }

    /** The hook leaves the hand; aim() already chose what it bites, so range and target are as before. */
    private void launch(Player p, Target t) {
        UUID id = p.getUniqueId();
        stopReel(id);
        stopTether(id);
        dropFlight(id, true); // a new throw while one is still flying: the old one hit nothing, so its charge comes back
        Flight f = new Flight();
        f.target = t;
        f.ticks = Math.max(2, Math.min(40, cfgI("visuals.chain-travel-ticks", 5)));
        f.bite = t.entity() == null && !t.anchor() ? BITE : 0;
        f.from = hand(p);
        Location to = biteAt(t);
        Layout l = to == null ? null : layout(f.from, to, f.bite);
        if (l == null) { hook(p, t, true); return; }
        f.hook = display(f.from, CMD_HOOK);
        f.hook.setTransformation(l.hookPose(new Vector3f())); // at the hand, already pointing at the target
        f.strands = new ItemDisplay[l.at().length - 1];
        flights.put(id, f);
        GemSounds.play(plugin, "auratus.chain-throw", p.getLocation()); // the throw now; the bite's sounds when it lands
    }

    private void tickFlight(Player p, Flight f) {
        UUID id = p.getUniqueId();
        Location to = biteAt(f.target);
        if (to == null || !to.getWorld().equals(f.from.getWorld())) { missFlight(p, f); return; } // what it was flying at is gone
        Layout l = layout(f.from, to, f.bite);
        if (l == null) { land(p, f); return; }
        f.age++;
        Vector3f end = vec(l.centre(0)).sub(vec(f.from)); // from the hook model (at the hand) to where it lands
        if (f.aimedAt == null || f.aimedAt.distance(end) > 0.3f) { // the first tick, or the target moved: one interpolated move for the time left
            int left = Math.max(1, f.ticks - f.age);
            f.hook.setInterpolationDelay(0);
            f.hook.setInterpolationDuration(left);
            f.hook.setTransformation(l.hookPose(end));
            f.aimedAt = end;
        }
        int n = l.at().length - 1;
        if (f.strands.length != n) { // the target moved enough to change the chain's length
            ItemDisplay[] next = new ItemDisplay[n];
            for (int i = 0; i < f.strands.length; i++) {
                if (i < n) next[i] = f.strands[i];
                else if (f.strands[i] != null && f.strands[i].isValid()) f.strands[i].remove();
            }
            f.strands = next;
        }
        double progress = Math.min(1, (f.age - 1) / (double) Math.max(1, f.ticks - 1));
        Transformation pose = l.strandPose();
        for (int i = 1; i <= n; i++) {
            if (f.strands[i - 1] != null || !AuratusMath.hookPassed(l.len(), l.at(), i, progress)) continue;
            ItemDisplay d = display(l.centre(i), CMD_LINK); // spawned where it will lie, as the hook passes: it never moves in flight
            d.setTransformation(pose);
            f.strands[i - 1] = d;
        }
        if (f.age >= f.ticks) land(p, f);
    }

    /** It arrives: the bite happens now (damage, Angels Grasp, sounds), and the tether takes over the links. */
    private void land(Player p, Flight f) {
        UUID id = p.getUniqueId();
        flights.remove(id);
        Tether h = hook(p, f.target, false);
        for (ItemDisplay d : f.strands) if (d != null && d.isValid()) h.chain.links.add(d);
        if (f.hook != null) removeNextTick.add(f.hook); // the tether draws its own hook this tick, in the same spot
        if (f.go) flyToHook(p, h);
        else if (f.haul && h.victim != null) startHaul(h);
    }

    /** What it was flying at died or left: nothing was hit, so the charge comes back (release.refund-before-hit). */
    private void missFlight(Player p, Flight f) {
        flights.remove(p.getUniqueId());
        if (cfgB("release.refund-before-hit", true)) charges(p).refundLatest(now());
        retract(p, f.hook, flightHookAt(f), java.util.Arrays.asList(f.strands));
        GemSounds.play(plugin, "auratus.hook-slides-out", p.getLocation());
    }

    /** Where the flying hook is drawn now (it moves by interpolation, so this is worked out, not read). */
    private static Location flightHookAt(Flight f) {
        if (f.aimedAt == null) return f.from.clone();
        double q = Math.min(1, Math.max(0, (f.age - 1) / (double) Math.max(1, f.ticks - 1)));
        return f.from.clone().add(f.aimedAt.x * q, f.aimedAt.y * q, f.aimedAt.z * q);
    }

    /** Removes a flight's models at once (logout, death, a new throw, the slam); refunds when asked and configured. */
    private void dropFlight(UUID id, boolean refund) {
        Flight f = flights.remove(id);
        if (f == null) return;
        if (f.hook != null && f.hook.isValid()) f.hook.remove();
        for (ItemDisplay d : f.strands) if (d != null && d.isValid()) d.remove();
        Player p = Bukkit.getPlayer(id);
        if (refund && p != null && cfgB("release.refund-before-hit", true)) charges(p).refundLatest(now());
    }

    /**
     * Release [Q, the drop key, while a chain is out]: dropping the gem is blocked anyway (protection.prevent-drop), so
     * the key is free. Still flying out: the charge comes back (release.refund-before-hit). Already bitten: spent as normal.
     * Flying to the hook: you let go and keep your momentum.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRelease(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack dropped = e.getItemDrop().getItemStack();
        // Q takes the item out of the hand before this event fires, so the Auratus being dropped counts as held
        if (!enabled() || !(holds(p) || isAuratus(dropped)) || !chainOut(p.getUniqueId())) return;
        if (plugin.finale() != null && plugin.finale().claimsDrop(p, dropped)) return; // dropped on its pedestal: the finale's alone
        e.setCancelled(true);
        letGo(p);
    }

    private boolean chainOut(UUID id) { return flights.containsKey(id) || tethers.containsKey(id) || reels.containsKey(id); }

    /** Lets go of a chain that's out (flying out, bitten or reeling), as Q does. Also used when the gem goes on a finale pedestal. */
    void letGo(Player p) {
        UUID id = p.getUniqueId();
        if (!chainOut(id)) return;
        Flight f = flights.remove(id);
        if (f != null) {
            if (cfgB("release.refund-before-hit", true)) charges(p).refundLatest(now());
            retract(p, f.hook, flightHookAt(f), java.util.Arrays.asList(f.strands));
        } else {
            Tether t = tethers.remove(id);
            Chain c = t != null ? t.chain : null;
            if (c == null) { Reel r = reels.remove(id); c = r == null ? null : r.chain; }
            if (c != null) retractChain(p, c);
        }
        GemSounds.play(plugin, "auratus.hook-slides-out", p.getLocation());
    }

    private void retractChain(Player p, Chain c) {
        ItemDisplay h = c.hook;
        List<ItemDisplay> links = new ArrayList<>(c.links);
        c.links.clear();
        c.hook = null;
        retract(p, h, h != null && h.isValid() ? h.getLocation() : hand(p), links);
    }

    /**
     * The hook flies back to the hand over visuals.chain-reel-ticks: one interpolated move of its translation, set once.
     * Each link goes as the hook passes it. Off (or no hook): everything goes at once.
     */
    private void retract(Player p, ItemDisplay hookModel, Location hookAt, List<ItemDisplay> links) {
        UUID id = p.getUniqueId();
        clearRetract(id);
        if (!cfgB("visuals.chain-reel-in", true) || hookModel == null || !hookModel.isValid()) {
            if (hookModel != null && hookModel.isValid()) hookModel.remove();
            for (ItemDisplay d : links) if (d != null && d.isValid()) d.remove();
            return;
        }
        int ticks = Math.max(1, Math.min(40, cfgI("visuals.chain-reel-ticks", 4)));
        Location handAt = hand(p);
        Transformation now = hookModel.getTransformation();
        float sc = now.getScale().x;
        Vector3f lift = new Vector3f(0, (float) (STRAND_DROP * sc), 0).rotate(now.getLeftRotation());
        Vector3f back = vec(handAt).sub(vec(hookModel.getLocation())).add(lift); // the hook's middle onto the hand
        hookModel.setInterpolationDelay(0);
        hookModel.setInterpolationDuration(ticks);
        hookModel.setTransformation(new Transformation(back, now.getLeftRotation(), now.getScale(), now.getRightRotation()));
        Retract r = new Retract();
        r.hook = hookModel;
        r.hookGone = tick + ticks;
        double total = Math.max(0.01, hookAt.distance(handAt));
        for (ItemDisplay d : links) {
            if (d == null || !d.isValid()) continue;
            r.links.add(d);
            r.gone.add(AuratusMath.passTick(tick, ticks, d.getLocation().distance(hookAt), total));
        }
        retracts.put(id, r);
    }

    private void tickRetracts() {
        for (ItemDisplay d : removeNextTick) if (d.isValid()) d.remove();
        removeNextTick.clear();
        for (Iterator<Map.Entry<UUID, Retract>> it = retracts.entrySet().iterator(); it.hasNext(); ) {
            Retract r = it.next().getValue();
            for (int i = r.links.size() - 1; i >= 0; i--)
                if (tick >= r.gone.get(i)) { ItemDisplay d = r.links.remove(i); r.gone.remove(i); if (d.isValid()) d.remove(); }
            if (tick >= r.hookGone) { removeRetract(r); it.remove(); }
        }
    }

    private void clearRetract(UUID id) {
        Retract r = retracts.remove(id);
        if (r != null) removeRetract(r);
    }

    private static void removeRetract(Retract r) {
        if (r.hook != null && r.hook.isValid()) r.hook.remove();
        for (ItemDisplay d : r.links) if (d.isValid()) d.remove();
        r.links.clear();
        r.gone.clear();
    }

    private static Vector3f vec(Location l) { return new Vector3f((float) l.getX(), (float) l.getY(), (float) l.getZ()); }

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
        // A wall between you and the hook point snaps the chain instead of pinning you against it.
        if (r.age > 0 && blockedBetween(p.getEyeLocation(), target)) {
            if (++r.blocked >= 2) { snap(p, r.chain, target); stopReel(id); return; }
        } else r.blocked = 0;
        drawChain(r.chain, hand(p), target, r.bite);
        Vector to = target.toVector().subtract(p.getLocation().toVector().add(new Vector(0, 0.9, 0)));
        double dist = to.length();
        if (!p.isOnGround()) r.airborne = true;
        boolean landed = p.isOnGround() && r.airborne && r.age > 3;
        boolean arrived = dist <= (le != null ? 2.4 : 1.6);
        if (arrived || landed || r.age >= cfgD("chain.max-reel-ticks", 40)) {
            stopReel(id);
            boolean tugged = le != null && r.hooked && dist < 3.5 && isEnemy(p, le);
            if (tugged) tug(p, le); // its clank is the arrival sound
            if (!landed) p.setVelocity(r.carry.clone().multiply(cfgD("chain.arrival-momentum", 0.45) * scale)); // keep some speed, no dead stop
            p.setFallDistance(0);
            if (!tugged) GemSounds.play(plugin, "auratus.reel-arrive", p.getLocation());
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

    /** Arriving at an enemy you flew to: a tug toward you and a clank (the hook already did the damage). */
    private void tug(Player p, LivingEntity v) {
        Vector pull = p.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (pull.lengthSquared() > 1e-4) v.setVelocity(pull.normalize().multiply(0.45).setY(0.2));
        World w = v.getWorld();
        Location at = v.getLocation().add(0, v.getHeight() * 0.6, 0);
        GemSounds.play(plugin, "auratus.tug", at);
        dust(w, at, 8, 0.3, GOLD, 1.1f);
    }

    // ---- the hook: it stays where it bit and you choose what happens

    /** Right-click while a hook is in: you're reeled there (no extra charge). */
    private void flyToHook(Player p, Tether t) {
        UUID id = p.getUniqueId();
        tethers.remove(id);
        Reel r = new Reel();
        r.chain.take(t.chain); // the same chain goes on being drawn while you fly
        r.entity = t.victim;
        r.point = t.point;
        r.hooked = t.victim != null;
        r.bite = t.bite;
        reels.put(id, r);
        GemSounds.play(plugin, "auratus.fly-to-hook", p.getLocation());
    }

    /** Swap hands (F) while a hook is in a creature: haul them to you. Never swaps your items while it's in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        Flight f = flights.get(p.getUniqueId());
        if (f != null && f.target.entity() != null && holds(p) && enabled()) { e.setCancelled(true); f.haul = true; return; } // haul once it lands
        Tether t = tethers.get(p.getUniqueId());
        if (t == null || t.victim == null || !holds(p) || !enabled()) return;
        e.setCancelled(true);
        if (t.hauling || dives.containsKey(p.getUniqueId())) return;
        startHaul(t);
    }

    private void startHaul(Tether t) {
        t.hauling = true;
        t.age = 0;
        Entity v = Bukkit.getEntity(t.victim);
        if (v != null) GemSounds.play(plugin, "auratus.haul", v.getLocation());
    }

    private void tickTether(Player p, Tether t) {
        UUID id = p.getUniqueId();
        LivingEntity v = null;
        Location tip;
        if (t.victim != null) {
            Entity e = Bukkit.getEntity(t.victim);
            if (!(e instanceof LivingEntity l) || !l.isValid() || l.isDead() || !l.getWorld().equals(p.getWorld())) { stopTether(id); return; }
            v = l;
            tip = l.getLocation().add(0, l.getHeight() * 0.5, 0);
        } else {
            tip = t.point;
            if (!tip.getWorld().equals(p.getWorld())) { stopTether(id); return; }
        }
        if (p.getEyeLocation().distance(tip) > cfgD("chain.range", 40) + 4) { snap(p, t.chain, tip); stopTether(id); return; }
        if (blockedBetween(p.getEyeLocation(), tip)) {
            if (++t.blocked >= 2) { snap(p, t.chain, tip); stopTether(id); return; }
        } else t.blocked = 0;
        drawChain(t.chain, hand(p), tip, t.bite);
        if (t.hauling && v != null) {
            Vector to = p.getLocation().toVector().subtract(v.getLocation().toVector());
            if (to.length() <= 2.5 || t.age >= cfgD("chain.haul-max-ticks", 30)) {
                v.setVelocity(t.carry.clone().multiply(0.3));
                if (isEnemy(p, v)) {
                    tug(p, v);
                    hooks.put(id, new Hook(v.getUniqueId(), now())); // pulled in: the Aegis can now catch them
                }
                stopTether(id);
                return;
            }
            double scale = timeScale(v.getLocation(), id);
            Vector vel = to.normalize().multiply(AuratusMath.reelSpeed(t.age, cfgD("chain.haul-speed", 1.0), cfgD("chain.ramp-ticks", 4)));
            if (t.age < 1 && v.isOnGround()) vel.setY(Math.max(vel.getY(), 0.3)); // off the ground, not dragged along it
            t.carry = vel.clone();
            v.setVelocity(vel.multiply(scale));
            v.setFallDistance(0);
            t.age += Math.max(0.05, scale);
            return;
        }
        if (now() >= t.until) { // left alone: the hook slides out and reels back in
            GemSounds.play(plugin, "auratus.hook-slides-out", tip);
            tethers.remove(id);
            retractChain(p, t.chain);
        }
    }

    private void stopTether(UUID id) {
        Tether t = tethers.remove(id);
        if (t != null) t.chain.clear();
    }

    /** A block between the two points (stopping a little short, so the hooked block or the ground they stand on doesn't count). */
    private static boolean blockedBetween(Location from, Location to) {
        if (!from.getWorld().equals(to.getWorld())) return true;
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 1.2) return false;
        return from.getWorld().rayTraceBlocks(from, d.normalize(), len - 0.6, FluidCollisionMode.NEVER, true) != null;
    }

    private void snap(Player p, Chain c, Location at) {
        c.clear();
        World w = p.getWorld();
        GemSounds.play(plugin, "auratus.snap", p.getLocation());
        w.spawnParticle(Particle.CRIT, p.getLocation().add(0, 1.2, 0), 8, 0.2, 0.2, 0.2, 0.2);
        dust(w, at, 6, 0.2, DEEP, 1.0f);
    }

    private void stopReel(UUID id) {
        Reel r = reels.remove(id);
        if (r != null) r.chain.clear();
    }

    // The pack's chain models (echo shard 12348 = custom/chain, 12349 = custom/chain_end) are one straight strand of
    // links, 47 px long along the model's z and centred on it, a link every 6 px, the strand's axis 8.1 px below the
    // model's centre. chain_end is the same strand ending in the hook at its -z end. In blocks, at link-scale 1:
    private static final double STRAND = 47 / 16.0, LINK_STEP = 6 / 16.0, STRAND_DROP = 8.1 / 16.0;
    /** A strand 42 px (7 links) behind the one before carries the links on in step: one link is drawn twice, never a gap. */
    private static final double STRAND_STEP = 7 * LINK_STEP;
    /** A block hook's point stands this far off the face (so you don't fly into the block); the drawn hook reaches it. */
    private static final double BITE = 0.4;

    /** About where the hand holding the gem is: beside and a little in front of the body, at hip height. */
    private Location hand(Player p) {
        double yaw = Math.toRadians(p.getBodyYaw()); // the arms follow the body, which lags the head
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        boolean rightSide = (p.getMainHand() == MainHand.RIGHT) == inMain(p); // the gem's hand, main or off
        return p.getLocation().add(right.multiply(rightSide ? 0.36 : -0.36)).add(forward.multiply(0.2))
                .add(0, p.getEyeHeight() * 0.5, 0); // ~0.8 standing, lower when sneaking
    }

    /**
     * The chain from the hand to the tip: the hook model with its point on the tip, then plain strands back to the
     * hand, joined in step and ending there (pack models); or a line of gold dust. {@code bite} draws the tip that much
     * further along the chain (block hooks).
     */
    /**
     * Item displays draw their model turned 180 degrees about Y (vanilla's item display renderer does it). The plain
     * strands are symmetric so it doesn't matter for them, but chain_end has its claw at one end: without turning it
     * back, the claw ended up toward the hand and the hook's own links ran on past the target. HOOK_TURN undoes it,
     * so the model is drawn as built: claw (its -z end) on the target, links trailing back toward the hand.
     */
    static final Quaternionf HOOK_TURN = new Quaternionf().rotationY((float) Math.PI);

    /** Turns a chain model's -z toward {@code dir} (from the hand to the target). */
    static Quaternionf chainRotation(Vector dir) {
        return new Quaternionf().rotationTo(new Vector3f(0, 0, -1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
    }

    /** The strand's axis sits 8.1 px below the model's middle: lifted back onto the line. */
    static Vector3f chainLift(Quaternionf rot, float scale) { return new Vector3f(0, (float) (STRAND_DROP * scale), 0).rotate(rot); }

    /**
     * The hook (chain_end), for the normal drawing, the flight out and the reel-in alike: claw on the target, links back
     * toward the hand, moved by {@code offset} (the flight's travel), squashed along the chain when it's short.
     */
    static Transformation hookPose(Vector dir, float scale, float squash, Vector3f offset) {
        Quaternionf rot = chainRotation(dir);
        return new Transformation(chainLift(rot, scale).add(offset), rot, new Vector3f(scale, scale, scale * squash), new Quaternionf(HOOK_TURN));
    }

    /** A plain strand (chain): symmetric, so it isn't turned. */
    static Transformation strandPose(Vector dir, float scale) {
        Quaternionf rot = chainRotation(dir);
        return new Transformation(chainLift(rot, scale), rot, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    /** The chain's shape from hand to tip: the direction, the models' spots back from the tip, and their poses. */
    private record Layout(Location tip, Vector dir, double len, double[] at, float scale) {
        Location centre(int i) { return tip.clone().subtract(dir.clone().multiply(at[i])); }
        Transformation hookPose(Vector3f offset) {
            float squash = at.length == 1 ? (float) (len / (STRAND * scale)) : 1f; // a short chain is just the hook, squashed to fit
            return AuratusGem.hookPose(dir, scale, squash, offset);
        }
        Transformation strandPose() { return AuratusGem.strandPose(dir, scale); }
    }

    private Layout layout(Location from, Location to, double bite) {
        Vector delta = to.toVector().subtract(from.toVector());
        double len = delta.length();
        if (len < 0.5) return null;
        Vector dir = delta.clone().multiply(1 / len);
        Location tip = to;
        if (bite > 0) { tip = to.clone().add(dir.clone().multiply(bite)); len += bite; }
        float s = (float) Math.max(0.1, cfgD("chain.link-scale", 1.0));
        return new Layout(tip, dir, len, AuratusMath.chainLayout(len, STRAND * s, STRAND_STEP * s, LINK_STEP * s, 60), s);
    }

    private void drawChain(Chain c, Location from, Location to, double bite) {
        World w = from.getWorld();
        Layout l = layout(from, to, bite);
        if (l == null) { c.clear(); return; }
        if (!cfgB("chain.show-models", true)) {
            c.clear();
            int points = Math.max(2, Math.min(64, (int) (l.len() * 2)));
            for (int i = 0; i < points; i++)
                w.spawnParticle(Particle.DUST, from.clone().add(l.dir().clone().multiply(l.len() * i / points)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 3 == 0 ? PALE : GOLD, 0.9f));
            return;
        }
        if (c.hook == null || !c.hook.isValid()) c.hook = display(l.tip(), CMD_HOOK);
        place(c.hook, l.centre(0), l.hookPose(new Vector3f())); // the same pose as the flight out and the reel-in
        int n = l.at().length - 1;
        while (c.links.size() < n) c.links.add(display(from, CMD_LINK)); // new strands glide out from the hand
        while (c.links.size() > n) { ItemDisplay d = c.links.remove(c.links.size() - 1); if (d.isValid()) d.remove(); }
        Transformation pose = l.strandPose();
        for (int i = 0; i < n; i++) place(c.links.get(i), l.centre(i + 1), pose);
        if (tick % 4 == 0) w.spawnParticle(Particle.WAX_ON, from.clone().add(l.dir().clone().multiply(l.len() * Math.random())), 1, 0.05, 0.05, 0.05, 0);
    }

    /** Moves a chain model without giving it a facing of its own (see {@link #display}). */
    private static void place(ItemDisplay d, Location at, Transformation pose) {
        if (d == null || !d.isValid()) return;
        d.teleport(unturned(at));
        d.setTransformation(pose);
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
        // The shield appears 3 blocks ahead where you look, facing you (closer if a wall is nearer).
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double dist = Math.max(1, cfgD("aegis.distance", 3.0));
        RayTraceResult wall = p.getWorld().rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);
        if (wall != null) dist = Math.max(0.8, eye.distance(wall.getHitPosition().toLocation(p.getWorld())) - 0.3);
        a.at = eye.clone().add(dir.clone().multiply(dist));
        a.dir = dir;
        a.halo = display(a.at, CMD_HALO);
        facing(a.halo, a.dir, 0.3f, 0);
        aegis.put(id, a);
        World w = p.getWorld();
        GemSounds.play(plugin, "auratus.aegis", p.getLocation());
        strikeShield(p, a);
    }

    /** Stands a flat model (the Aegis halo) up on edge, facing along {@code dir}, centred on the display, turned by {@code spin}. */
    private static void facing(ItemDisplay d, Vector dir, float scale, float spin) {
        if (d == null || !d.isValid()) return;
        Vector3f n = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).normalize();
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), n).mul(new Quaternionf().rotationY(spin));
        d.setInterpolationDelay(0);
        // The flat model lies half a block below its centre along its own "up"; lift it back onto the display.
        d.setTransformation(new Transformation(new Vector3f(n).mul(0.5f * scale), rot, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    /** A ring of dust across the shield, square to {@code dir}. */
    private static void disc(World w, Location c, Vector dir, double r, int points, Color col, float size) {
        Vector n = dir.clone().normalize();
        Vector u = Math.abs(n.getY()) > 0.9 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        u = u.subtract(n.clone().multiply(u.dot(n))).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points;
            Vector off = u.clone().multiply(Math.cos(a) * r).add(v.clone().multiply(Math.sin(a) * r));
            w.spawnParticle(Particle.DUST, c.clone().add(off), 1, 0, 0, 0, 0, new Particle.DustOptions(col, size));
        }
    }

    /**
     * Anyone at the shield takes a hit, once per Aegis, for its first few ticks. An enemy you hooked or pulled in
     * the last few seconds is caught: Flagpole when the window ends, or the launch if you chain again first.
     */
    private void strikeShield(Player p, Aegis a) {
        UUID id = p.getUniqueId();
        double r = Math.max(0.5, cfgD("aegis.strike-radius", 1.3));
        for (Entity e : a.at.getWorld().getNearbyEntities(a.at, r, r, r)) {
            if (!(e instanceof LivingEntity v) || !isEnemy(p, v) || !a.struck.add(v.getUniqueId())) continue;
            plugin.listener().abilityHit(v, cfgD("aegis.strike-damage", 5.0), p);
            World w = v.getWorld();
            Location at = v.getLocation().add(0, v.getHeight() * 0.6, 0);
            w.spawnParticle(Particle.ENCHANTED_HIT, at, 14, 0.3, 0.4, 0.3, 0.3);
            Hook h = hooks.get(id);
            long n = now();
            if (h == null || !h.victim().equals(v.getUniqueId()) || n - h.at() > ms("flagpole.hook-memory-seconds", 5)
                    || flagReady.getOrDefault(id, 0L) > n || hoists.containsKey(v.getUniqueId()) || combos.containsKey(id)) {
                GemSounds.play(plugin, "auratus.aegis-strike", at);
                continue;
            }
            hooks.remove(id);
            stopTether(id);
            Combo c = new Combo();
            c.victim = v.getUniqueId();
            c.until = n + Math.max(50, plugin.getConfig().getLong(path("flagpole.window-ms"), 750));
            combos.put(id, c);
            GemSounds.play(plugin, "auratus.aegis-catch", at);
            dust(w, at, 20, 0.5, PALE, 1.4f);
        }
    }

    private void tickAegis(Player p, Aegis a) {
        a.age++;
        if (a.halo != null && a.halo.isValid()) {
            float grow = Math.min(1f, a.age / 3f);
            facing(a.halo, a.dir, (a.parried ? 2.9f : 2.4f) * (0.3f + 0.7f * grow), (float) (a.age * 0.25));
        }
        if (a.age % 2 == 0) disc(p.getWorld(), a.at, a.dir, 1.25, 16, a.parried ? PALE : GOLD, 0.8f);
        if (a.age <= cfgI("aegis.strike-ticks", 6)) strikeShield(p, a); // a target still arriving is caught too
        if (now() < a.until) return;
        aegis.remove(p.getUniqueId());
        if (a.halo != null && a.halo.isValid()) a.halo.remove();
        if (!a.parried && a.struck.isEmpty()) GemSounds.playTo(plugin, "auratus.aegis-fizzle", p);
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
        w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.1, 0), 18, 0.3, 0.4, 0.3, 0.08);
        if (a.parried) { GemSounds.playMain(plugin, "auratus.parry", p.getLocation()); return; }
        a.parried = true;
        // Reward, once per Aegis: heal, speed, a cleanse (Divine Purity) and an Echo Anchor above you.
        double heal = Math.max(0, cfgD("aegis.heal-hp", 5.0));
        p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) Math.round(cfgD("aegis.speed-seconds", 3) * 20), 1, false, false, true));
        cleanse(p);
        aegisReady.put(p.getUniqueId(), a.castAt + AuratusMath.aegisCooldownMs(true, cfgD("aegis.cooldown-seconds", 15), cfgD("aegis.success-cooldown-seconds", 10)));
        spawnAnchor(p);
        GemSounds.play(plugin, "auratus.parry", p.getLocation());
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
        if (wait > 0) { deny(p); return; }
        Location target = slamTarget(p);
        if (target == null) return;
        slamReady.put(id, n + ms("slam.cooldown-seconds", 0));
        stopReel(id);
        stopTether(id);
        dropFlight(id, true); // slamming before the hook landed: nothing was hit
        Dive d = new Dive();
        d.target = target;
        d.startY = p.getLocation().getY();
        d.decal = display(target.clone().add(0, 0.06, 0), CMD_SMASH);
        flat(d.decal, 0.4f, 0);
        dives.put(id, d);
        World w = p.getWorld();
        GemSounds.play(plugin, "auratus.slam-start", p.getLocation());
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
        drawChain(d.chain, hand(p), d.target, 0);
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
        // slam.firework is the old switch for the same thing: either one off turns the sphere off.
        if (cfgB("visuals.slam-burst", true) && cfgB("slam.firework", true)) {
            burst(randomNear(at, 1.5, 3.0, 0.5, 2.5), Color.WHITE, ICE); // a white sphere, somewhere new each slam; its blast is the layer
            GemSounds.playMain(plugin, "auratus.gilded-slam", at);
        } else {
            GemSounds.play(plugin, "auratus.gilded-slam", at);
        }
        if (cfgB("visuals.slam-sparks", true)) sparks(at, radius);
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

    /**
     * A large-ball firework bursting at {@code at} at once. THE ONLY PLACE THIS PLUGIN SPAWNS A FIREWORK
     * (FireworkSafetyTest checks it): every one is tagged with fireworkKey, so onSlamFirework cancels any damage it
     * would do. It makes its own large-blast sound.
     */
    private void burst(Location at, Color color, Color fade) {
        Firework fw = at.getWorld().spawn(at, Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.clearEffects();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(color).withFade(fade).build());
            m.setPower(0);
            f.setFireworkMeta(m);
            f.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
        fw.detonate();
    }

    /** A random spot minR..maxR out from {@code base} in any direction and minUp..maxUp above it; base + 1 up if that spot is in a solid block. */
    private static Location randomNear(Location base, double minR, double maxR, double minUp, double maxUp) {
        double[] o = AuratusMath.randomOffset(ThreadLocalRandom.current(), minR, maxR, minUp, maxUp);
        Location at = base.clone().add(o[0], o[1], o[2]);
        return at.getBlock().getType().isSolid() ? base.clone().add(0, 1, 0) : at;
    }

    /**
     * visuals.slam-spark-count firework sparks, each from its own random spot within 0.8 x the slam radius, 0.2 above
     * the ground, each with its own small delay (spread over the first 10 ticks): they shoot up and drift back down.
     * Everyone in range sees them. The task ends itself after 10 ticks (and on disable).
     */
    private void sparks(Location at, double radius) {
        int total = Math.max(0, Math.min(200, cfgI("visuals.slam-spark-count", 30)));
        if (total == 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int[] perTick = new int[10];
        for (int i = 0; i < total; i++) perTick[r.nextInt(10)]++;
        World w = at.getWorld();
        double reach = radius * 0.8;
        int[] t = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < perTick[t[0]]; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = reach * Math.sqrt(rnd.nextDouble()); // evenly over the disc
                w.spawnParticle(Particle.FIREWORK, at.getX() + Math.cos(a) * d, at.getY() + 0.2, at.getZ() + Math.sin(a) * d,
                        0, rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(0.4, 0.85), rnd.nextDouble(-0.2, 0.2), 1.0); // count 0: offsets = direction
            }
            if (++t[0] >= perTick.length) { self[0].cancel(); sparkTasks.remove(self[0]); }
        }, 0L, 1L);
        sparkTasks.add(self[0]);
    }

    /** Inside a block (its box, a little shrunk so touching the floor or a ceiling doesn't count). */
    private static boolean stuck(LivingEntity v) {
        BoundingBox box = v.getBoundingBox().expand(-0.05);
        return v.getWorld().hasCollisionsIn(box);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlamFirework(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(fireworkKey, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    private void stopDive(UUID id) {
        Dive d = dives.remove(id);
        if (d == null) return;
        d.chain.clear();
        if (d.decal != null && d.decal.isValid()) d.decal.remove();
    }

    // ------------------------------------------------------------------ Flagpole and the launch

    /** The Aegis caught them: hold them at the shield; when the window ends with nothing else, Flagpole. */
    private void tickCombo(Player p, Combo c) {
        UUID id = p.getUniqueId();
        Entity e = Bukkit.getEntity(c.victim);
        if (!(e instanceof LivingEntity v) || !v.isValid() || v.isDead() || !v.getWorld().equals(p.getWorld())) { combos.remove(id); return; }
        Vector vel = v.getVelocity();
        v.setVelocity(new Vector(vel.getX() * 0.2, Math.min(vel.getY(), 0.1), vel.getZ() * 0.2)); // pinned at the shield
        if (tick % 2 == 0) ring(v.getWorld(), v.getLocation().add(0, 0.2, 0), 0.7, 10, GOLD, 1.0f);
        if (now() < c.until) return;
        combos.remove(id);
        hoist(p, v);
    }

    /** Flagpole: hoisted a few blocks and held there, in a spiral of gold. */
    private void hoist(Player p, LivingEntity v) {
        UUID id = p.getUniqueId();
        if (hoists.containsKey(v.getUniqueId())) return;
        flagReady.put(id, now() + ms("flagpole.cooldown-seconds", 25));
        stopTether(id);
        dropFlight(id, false);
        Hoist hs = new Hoist();
        hs.owner = id;
        hs.baseY = v.getLocation().getY();
        int height = (int) Math.round(cfgD("flagpole.height", 5.0));
        double top = hs.baseY + height; // under a low ceiling they fly up into it and stay pinned there (velocity, never a teleport)
        if (cfgB("flagpole.ceiling-cap", false)) { // the old way: stop below any ceiling
            top = hs.baseY;
            for (int up = 1; up <= height; up++) {
                Block b = v.getLocation().getBlock().getRelative(0, up + 1, 0);
                if (!b.isPassable()) break;
                top = hs.baseY + up;
            }
        }
        hs.topY = top;
        int hold = (int) Math.round(cfgD("flagpole.hold-seconds", 2.5) * 20);
        hs.left = hold + 10; // half a second to rise
        hoists.put(v.getUniqueId(), hs);
        setModifier(v, Attribute.GRAVITY, hoistKey, -1);
        grasp(v, hs.left / 20.0 + 1);
        if (cfgB("visuals.flagpole-burst", true)) burst(v.getLocation().add(0, v.getHeight() * 0.7, 0), GOLD, PALE);
        GemSounds.play(plugin, "auratus.flagpole", v.getLocation());
        if (stuck(v)) release(v.getUniqueId(), v); // already inside a block: no hold at all
    }

    /**
     * Chain again while the Aegis holds them: no Flagpole. A chain lashes out and throws them straight up, with one
     * hit built for full Prot IV diamond: flagpole.finisher-damage (72) is about 22 HP through it, a kill from full
     * health on its own, and the fall from ~30 blocks comes after. It goes straight through as player damage, past
     * the ability-damage cap, so it can one-shot.
     */
    private void finisher(Player p, Combo c) {
        UUID id = p.getUniqueId();
        combos.remove(id);
        Entity e = Bukkit.getEntity(c.victim);
        if (!(e instanceof LivingEntity v) || !v.isValid() || v.isDead() || !v.getWorld().equals(p.getWorld())) return;
        flagReady.put(id, now() + ms("flagpole.cooldown-seconds", 25));
        stopTether(id);
        stopReel(id);
        World w = v.getWorld();
        Location from = p.getLocation().add(0, 1.2, 0), to = v.getLocation().add(0, v.getHeight() * 0.5, 0);
        Location stood = v.getLocation().add(0, v.getHeight() * 0.7, 0); // where they stood, chest height
        Vector step = to.toVector().subtract(from.toVector());
        int points = (int) Math.max(2, Math.min(40, step.length() * 3));
        for (int i = 0; i <= points; i++) w.spawnParticle(Particle.DUST, from.clone().add(step.clone().multiply(i / (double) points)), 1, 0, 0, 0, 0,
                new Particle.DustOptions(i % 2 == 0 ? GOLD : PALE, 1.2f));
        v.damage(Math.max(0, cfgD("flagpole.finisher-damage", 72)), p);
        double up = RevenantMath.leapSpeedFor(Math.max(1, cfgD("flagpole.finisher-launch-height", 30)));
        if (v.isValid() && !v.isDead()) {
            v.setVelocity(new Vector(0, up, 0));
            grasp(v, cfgD("passives.angels-grasp-seconds", 4));
            Bukkit.getScheduler().runTask(plugin, () -> { // after any knockback from the hit itself
                if (v.isValid() && !v.isDead() && v.getVelocity().getY() < up * 0.8) v.setVelocity(new Vector(0, up, 0));
            });
        }
        if (cfgB("visuals.launch-burst", true)) {
            burst(stood, GOLD, PALE);
            GemSounds.playLayer(plugin, "auratus.launch", to); // the burst's own blast is launch's main sound
        } else {
            GemSounds.play(plugin, "auratus.launch", to);
        }
        w.spawnParticle(Particle.END_ROD, to, 40, 0.3, 1.5, 0.3, 0.25);
        dust(w, to, 30, 0.6, GOLD, 1.6f);
    }

    private void tickHoist(LivingEntity v, Hoist hs) {
        if (stuck(v)) { release(v.getUniqueId(), v); return; } // never held inside a block (a block placed on them, say)
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
            GemSounds.play(plugin, "auratus.flagpole-release", v.getLocation());
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
                GemSounds.play(plugin, "auratus.shield-jam", v.getLocation());
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

    /** Angels Grasp: no ender pearls, wind charges, chorus fruit or tridents for a few seconds (4 from slam and Flagpole, 2 from a chain). */
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
        tickRetracts();
        if (!enabled()) return;
        for (UUID id : new ArrayList<>(flights.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Flight f = flights.get(id);
            if (p == null || p.isDead() || f == null) { dropFlight(id, false); continue; }
            tickFlight(p, f);
        }
        for (UUID id : new ArrayList<>(reels.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) { stopReel(id); continue; }
            Reel r = reels.get(id);
            if (r != null) tickReel(p, r);
        }
        for (UUID id : new ArrayList<>(tethers.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) { stopTether(id); continue; }
            Tether t = tethers.get(id);
            if (t != null) tickTether(p, t);
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
        for (UUID id : new ArrayList<>(combos.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Combo c = combos.get(id);
            if (p == null || p.isDead() || c == null) { combos.remove(id); continue; }
            tickCombo(p, c);
        }
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
     * The Auratus action bar: just the main pack's chain and Aegis icons, each bright with "Ready!" when ready, dimmed
     * with a timer while recharging, and "\u2026" while active (HudStatus).
     */
    Component hudComponent(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        Charges c = charges(p);
        boolean numbers = plugin.getConfig().getBoolean("mythics.hud.show-numbers", true);
        boolean out = dives.containsKey(id) || tethers.containsKey(id) || flights.containsKey(id);
        String chains = HudStatus.charges(GLYPH_PERFORATORS, c.ready(n), c.max(), c.nextRecharge(n), out, numbers);
        String guard = HudStatus.entry(GLYPH_AEGIS, aegisReady.getOrDefault(id, 0L) - n, aegis.containsKey(id), numbers);
        return LEGACY.deserialize(chains + "  " + guard);
    }

    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        stopReel(id);
        stopTether(id);
        dropFlight(id, false);
        clearRetract(id);
        for (Map.Entry<UUID, Tether> en : new ArrayList<>(tethers.entrySet())) // anyone hooked into them lets go
            if (id.equals(en.getValue().victim)) stopTether(en.getKey());
        combos.remove(id);
        combos.values().removeIf(c -> c.victim.equals(id));
        stopDive(id);
        Aegis a = aegis.remove(id);
        if (a != null && a.halo != null && a.halo.isValid()) a.halo.remove();
        removeAnchor(id);
        if (hoists.containsKey(id)) release(id, p);
        for (Map.Entry<UUID, Hoist> en : new ArrayList<>(hoists.entrySet())) // they let go of anyone they were holding
            if (en.getValue().owner.equals(id)) release(en.getKey(), Bukkit.getEntity(en.getKey()) instanceof LivingEntity l ? l : null);
        hooks.remove(id);
        fullSwing.remove(id);
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
        for (BukkitTask t : sparkTasks) t.cancel();
        sparkTasks.clear();
        for (UUID id : new ArrayList<>(reels.keySet())) stopReel(id);
        for (UUID id : new ArrayList<>(tethers.keySet())) stopTether(id);
        for (UUID id : new ArrayList<>(flights.keySet())) dropFlight(id, false);
        for (UUID id : new ArrayList<>(retracts.keySet())) clearRetract(id);
        for (ItemDisplay d : removeNextTick) if (d.isValid()) d.remove();
        removeNextTick.clear();
        combos.clear();
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
