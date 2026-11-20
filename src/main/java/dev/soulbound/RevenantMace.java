package dev.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Revenant: the season's final boss weapon, a mace holding the third crystal's soul.
 *
 * Not a gem: it carries its own tag, so the Trader, Upgrader, energy system and gem disables never see it.
 * Exactly one is live at a time: it has an id, and the plugin records who it was given to; it only works in
 * that player's hands, so any copy (a creative clone copies the id too) is inert. It cannot be dropped or
 * stored, vanishes on death and logout, and comes back only through /revenant give (or, mid-fight, on its own
 * when the same holder rejoins).
 *
 * Powers (main hand): Plunge (right-click, independent charges), Rising (sneak + right-click), Undertow
 * (sneak + left-click). Passives: Abyssal Weight (hits stop jumping) and no fall damage. Boss mode adds health,
 * knockback resistance, a boss bar, phase 2 at half health and a shatter on death. The Relay counters him:
 * Time Dilate slows him and blocks Rising, and Temporal Divinity suspends him in place instead of taking him
 * to the void (see TemporalDomain).
 */
final class RevenantMace implements Listener, CommandExecutor, TabCompleter {
    static final Color TEAL = Color.fromRGB(0x3CE6CD), TEAL_DEEP = Color.fromRGB(0x1A9E91), TEAL_PALE = Color.fromRGB(0xBEFFF5);
    private static final String TEAL_TEXT = "§x§6§E§F§A§E§8", RELAY_TEXT = "§x§B§8§9§3§5§8";
    private static final TextColor TEAL_COLOR = TextColor.color(0x6EFAE8);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private enum State { NONE, GIVEN, RUNNING, PAUSED }

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final NamespacedKey idKey = new NamespacedKey("soulbound-gems", "revenant_id");
    private final NamespacedKey flagsKey = new NamespacedKey("soulbound-gems", "revenant_flags");
    private final NamespacedKey healthKey = new NamespacedKey("soulbound-gems", "revenant_health");
    private final NamespacedKey kbKey = new NamespacedKey("soulbound-gems", "revenant_knockback");
    private final NamespacedKey kbZeroKey = new NamespacedKey("soulbound-gems", "revenant_knockback_zero");
    private final NamespacedKey rootKey = new NamespacedKey("soulbound-gems", "revenant_root");
    private final NamespacedKey weightKey = new NamespacedKey("soulbound-gems", "revenant_weight");

    // Persisted fight state (revenant.yml).
    private State state = State.NONE;
    private UUID itemId, holder;
    private double savedHealth = -1; // boss health kept across logout/restart; -1 = full
    private boolean phase2;
    private final Charges plunge = new Charges(2);
    private long risingReady, undertowReady; // epoch ms

    // Live, per-session state (there is only ever one holder).
    private Plunge plungeRun;
    private Sink sinkRun;
    private Suspension suspension;
    private long immuneUntil;
    private final Map<UUID, Long> weighted = new HashMap<>();
    private long landingGraceUntil = -1;
    private BossBar bar;
    private final Set<UUID> barViewers = new HashSet<>();
    private int returnCountdown = -1;
    private boolean hudShown;
    private long tick, lastInput;

    private static final class Plunge { Location target; boolean slamming; int age; double peakY; }
    private static final class Sink { UUID target; int age; }
    private static final class Suspension { Location anchor; int remaining; boolean allowFlight, flying, gravity; }

    RevenantMace(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "revenant.yml");
        load();
    }

    // ------------------------------------------------------------------ config

    private String cfgPath(String k) { return "mythics.revenant." + k; }
    private double cfgD(String k, double d) { return plugin.getConfig().getDouble(cfgPath(k), d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt(cfgPath(k), d); }
    private boolean cfgB(String k, boolean d) { return plugin.getConfig().getBoolean(cfgPath(k), d); }
    private long now() { return System.currentTimeMillis(); }
    private double cap() { return Math.max(1, cfgD("plunge.damage-cap", 28)); }

    // ------------------------------------------------------------------ who is the Revenant

    private Player holderOnline() { return holder == null ? null : Bukkit.getPlayer(holder); }

    boolean isLiveItem(ItemStack it) {
        if (it == null || it.getType() != Material.MACE || itemId == null || !it.hasItemMeta()) return false;
        return itemId.toString().equals(it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    private boolean isRevenantItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    /** Also used by capture points: the Revenant can't capture. */
    boolean isHolder(Player p) {
        return p != null && holder != null && holder.equals(p.getUniqueId()) && (state == State.GIVEN || state == State.RUNNING);
    }

    /** The Revenant's holder with the live mace in the main hand: powers and the Revenant HUD work. */
    boolean holdsMace(Player p) { return isHolder(p) && isLiveItem(p.getInventory().getItemInMainHand()); }

    private boolean inEitherHand(Player p) {
        return isHolder(p) && (isLiveItem(p.getInventory().getItemInMainHand()) || isLiveItem(p.getInventory().getItemInOffHand()));
    }

    /** The holder with the live mace anywhere on them: Divinity and the Rift treat this player as the boss. */
    boolean isBoss(Player p) {
        if (!isHolder(p)) return false;
        if (isLiveItem(p.getItemOnCursor())) return true;
        for (ItemStack it : p.getInventory().getContents()) if (isLiveItem(it)) return true;
        return false;
    }

    /** True while the Revenant HUD owns this player's action bar (other HUDs then skip it). */
    boolean hudActive(Player p) { return cfgB("enabled", true) && holdsMace(p); }

    // ------------------------------------------------------------------ the item

    private List<String> lore() {
        List<String> l = new ArrayList<>();
        l.add(TEAL_TEXT + "THE REVENANT");
        l.add("§f§lTHE SOUL THAT WOULD NOT LET GO");
        l.add("§3(Mythic Weapon)");
        l.add("");
        l.add(TEAL_TEXT + "♦ §ePASSIVES");
        l.add("§7— Abyssal Weight §8[Hits stop jumping]");
        l.add("§7— Soul-Bound Fall §8[No fall damage]");
        l.add("");
        l.add(TEAL_TEXT + "♦ §bPOWERS");
        l.add("§7— " + TEAL_TEXT + "▼ PLUNGE §8[Right-click, " + cfgI("plunge.charges", 2) + " charges]");
        l.add("§7— " + TEAL_TEXT + "▲ RISING §8[Sneak + right-click]");
        l.add("§7— " + TEAL_TEXT + "◎ UNDERTOW §8[Sneak + left-click]");
        l.add("");
        l.add("§8Boss weapon • not a gem • there is only one");
        return plugin.gems().styleLoreLines(l);
    }

    private ItemStack createItem() {
        ItemStack it = new ItemStack(Material.MACE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(TEAL_TEXT + "§lThe Revenant");
        m.setItemModel(new NamespacedKey("blissgems", "revenant_mace"));
        m.setUnbreakable(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, itemId.toString());
        m.setLore(lore());
        it.setItemMeta(m);
        return it;
    }

    /** Copies (or old maces from an ended fight) say so in their lore, once. */
    private void markInert(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        List<String> l = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        String line = "§8ɪɴᴇʀᴛ: the soul is not in this one";
        if (l.contains(line)) return;
        l.add(line);
        m.setLore(l);
        it.setItemMeta(m);
    }

    private int removeRevenantItems(Player p) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (isRevenantItem(c[i])) { inv.setItem(i, null); n++; }
        if (isRevenantItem(p.getItemOnCursor())) { p.setItemOnCursor(null); n++; }
        return n;
    }

    // ------------------------------------------------------------------ boss stats

    private void applyStats(Player p) {
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) {
            if (mh.getModifier(healthKey) != null) mh.removeModifier(healthKey);
            double extra = cfgD("boss.max-health", 60) - mh.getBaseValue();
            if (extra > 0) mh.addTransientModifier(new AttributeModifier(healthKey, extra, AttributeModifier.Operation.ADD_NUMBER));
        }
        AttributeInstance kb = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) {
            if (kb.getModifier(kbKey) != null) kb.removeModifier(kbKey);
            kb.addTransientModifier(new AttributeModifier(kbKey, Math.max(0, cfgD("boss.knockback-resistance", 0.6)), AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void removeStats(Player p) {
        for (Attribute type : new Attribute[]{Attribute.MAX_HEALTH, Attribute.KNOCKBACK_RESISTANCE}) {
            AttributeInstance a = p.getAttribute(type);
            if (a == null) continue;
            if (a.getModifier(healthKey) != null) a.removeModifier(healthKey);
            if (a.getModifier(kbKey) != null) a.removeModifier(kbKey);
            if (a.getModifier(kbZeroKey) != null) a.removeModifier(kbZeroKey);
        }
        if (!p.isDead() && p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private void setModifier(LivingEntity e, Attribute type, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance a = e.getAttribute(type);
        if (a == null) return;
        if (a.getModifier(key) != null) a.removeModifier(key);
        a.addTransientModifier(new AttributeModifier(key, amount, op));
    }

    private void clearModifier(LivingEntity e, Attribute type, NamespacedKey key) {
        AttributeInstance a = e.getAttribute(type);
        if (a != null && a.getModifier(key) != null) a.removeModifier(key);
    }

    /** Can't walk or jump (Rising's sink and Divinity's suspension). */
    private void root(Player p, boolean on) {
        if (on) {
            setModifier(p, Attribute.MOVEMENT_SPEED, rootKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            setModifier(p, Attribute.JUMP_STRENGTH, rootKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        } else {
            clearModifier(p, Attribute.MOVEMENT_SPEED, rootKey);
            clearModifier(p, Attribute.JUMP_STRENGTH, rootKey);
        }
    }

    // ------------------------------------------------------------------ input

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !cfgB("enabled", true)) return;
        Player p = e.getPlayer();
        if (!holdsMace(p)) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!right && !(left && p.isSneaking())) return; // a plain left-click is an ordinary mace swing
        if (e.getClickedBlock() != null && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        e.setCancelled(true);
        long n = now();
        if (n - lastInput < 250) return;
        lastInput = n;
        if (right && p.isSneaking()) rising(p);
        else if (right) plunge(p);
        else undertow(p);
    }

    private boolean busy(Player p) {
        if (suspension != null) { // no words on the action bar: a dull click, and the boss bar already says SUSPENDED
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.6f, 0.6f);
            return true;
        }
        return plungeRun != null || sinkRun != null;
    }

    // ------------------------------------------------------------------ Plunge

    private void plunge(Player p) {
        if (busy(p)) return;
        long n = now();
        plunge.resize(cfgI("plunge.charges", 2));
        if (plunge.ready(n) == 0) return;
        Location target = plungeTarget(p);
        if (target == null) {
            p.sendMessage(TEAL_TEXT + "No safe ground to plunge onto there.");
            return; // no charge used
        }
        long recharge = (long) (cfgD("plunge.recharge-seconds", 12) * 1000 * (phase2 ? cfgD("boss.phase2-plunge-recharge", 0.7) : 1));
        plunge.use(n, recharge);
        double scale = plugin.relay().timeScale(p.getLocation(), p.getUniqueId());
        Vector toTarget = target.toVector().subtract(p.getLocation().toVector()).setY(0);
        Vector forward = toTarget.lengthSquared() < 1e-4 ? new Vector() : toTarget.normalize().multiply(cfgD("plunge.leap-forward-speed", 0.35));
        double up = RevenantMath.leapSpeedFor(cfgD("plunge.leap-height", 12));
        // Inside a Time Dilate field the leap starts slow, then the field's own slowed gravity takes over.
        p.setVelocity(forward.setY(up).multiply(scale));
        p.setFallDistance(0);
        Plunge run = new Plunge();
        run.target = target;
        run.peakY = p.getLocation().getY();
        plungeRun = run;
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ITEM_MACE_SMASH_AIR, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 0.2, 0), 14, 0.4, 0.1, 0.4, 0.04);
    }

    /** Where Plunge lands: the block looked at (up to plunge.range), or the ground below that point; never lava, never outside the border. */
    private Location plungeTarget(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        double range = Math.max(4, cfgD("plunge.range", 16));
        RayTraceResult hit = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.ALWAYS, true);
        Location aim = hit != null ? hit.getHitPosition().toLocation(w) : eye.clone().add(eye.getDirection().multiply(range));
        WorldBorder border = w.getWorldBorder();
        double[] xz = RevenantMath.clampInside(aim.getX(), aim.getZ(), border.getCenter().getX(), border.getCenter().getZ(), border.getSize(), 2);
        aim.setX(xz[0]);
        aim.setZ(xz[1]);
        Location ground = groundBelow(aim.clone().add(0, 1, 0), 48);
        if (ground == null) return null;
        Block feet = ground.getBlock();
        if (feet.getType() == Material.LAVA || feet.getRelative(BlockFace.DOWN).getType() == Material.LAVA || feet.getType() == Material.FIRE) {
            for (int[] o : RelayRift.searchOffsets(3)) {
                if (RelayRift.standable(w, feet.getX() + o[0], feet.getY() + o[1], feet.getZ() + o[2]))
                    return new Location(w, feet.getX() + o[0] + 0.5, feet.getY() + o[1], feet.getZ() + o[2] + 0.5);
            }
            return null;
        }
        return ground;
    }

    /** The first standing spot at or below {@code from} (ground or water surface) within {@code depth} blocks. */
    private Location groundBelow(Location from, int depth) {
        World w = from.getWorld();
        int x = from.getBlockX(), z = from.getBlockZ();
        for (int y = Math.min(from.getBlockY(), w.getMaxHeight() - 2); y > Math.max(w.getMinHeight(), from.getBlockY() - depth); y--) {
            Block below = w.getBlockAt(x, y - 1, z);
            if (below.getType().isSolid() || below.isLiquid()) return new Location(w, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void tickPlunge(Player p) {
        Plunge run = plungeRun;
        run.age++;
        run.peakY = Math.max(run.peakY, p.getLocation().getY());
        p.setFallDistance(0);
        double scale = plugin.relay().timeScale(p.getLocation(), p.getUniqueId());
        if (!run.slamming) {
            // Leap until the apex; in a Time Dilate field start the (slowed) descent straight away.
            if (p.getVelocity().getY() > 0.05 && run.age < 40 && scale >= 1) return;
            run.slamming = true;
        }
        Vector to = run.target.toVector().subtract(p.getLocation().toVector());
        boolean landed = (p.isOnGround() && run.age > 3) || p.isInWater() || p.isInLava() || to.length() < 1.0 || run.age > 160;
        if (landed || !p.getWorld().equals(run.target.getWorld())) { land(p); return; }
        // Slam towards the target, slowed by Time Dilate like Auratus' dives (the field's own speed caps still apply).
        p.setVelocity(to.normalize().multiply(Math.max(0.2, cfgD("plunge.slam-speed", 1.8)) * scale));
        if (run.age % 2 == 0) p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 3, 0.2, 0.3, 0.2, 0, new Particle.DustOptions(TEAL, 1.3f));
    }

    private void land(Player p) {
        Plunge run = plungeRun;
        plungeRun = null;
        if (run == null) return;
        Location at = p.getLocation();
        double fall = Math.max(0, run.peakY - at.getY());
        double radius = Math.max(1, cfgD("plunge.radius", 4));
        double base = cfgD("plunge.base-damage", 6);
        double knock = cfgD("plunge.knockback", 1.0);
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof LivingEntity v) || !isEnemy(p, v)) continue;
            double d = v.getLocation().distance(at);
            double dmg = RevenantMath.plungeDamage(base, fall, d, radius, cap());
            if (dmg <= 0) continue;
            v.damage(dmg, p);
            Vector away = v.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            v.setVelocity(away.normalize().multiply(knock)); // outward, never upward (Abyssal Weight)
            weigh(v);
        }
        World w = p.getWorld();
        w.playSound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.6f, 0.7f);
        w.spawnParticle(Particle.SCULK_SOUL, at.clone().add(0, 0.3, 0), 40, radius * 0.4, 0.2, radius * 0.4, 0.05);
        Block ground = at.getBlock().getRelative(BlockFace.DOWN);
        if (ground.getType().isSolid()) w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 50, radius * 0.4, 0.1, radius * 0.4, 0.2, ground.getBlockData());
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0, new Particle.DustOptions(TEAL, 1.5f));
        }
        p.setFallDistance(0);
    }

    // ------------------------------------------------------------------ Rising

    private void rising(Player p) {
        if (busy(p)) return;
        long n = now();
        if (risingReady > n) return;
        if (plugin.relay().timeScale(p.getLocation(), p.getUniqueId()) < 1) {
            p.sendMessage(TEAL_TEXT + "Time holds you here. You can't sink while it's slowed.");
            return;
        }
        Player target = nearestEnemy(p, Math.max(4, cfgD("rising.range", 20)));
        if (target == null) { p.sendMessage(TEAL_TEXT + "No one to rise behind."); return; }
        risingReady = n + (long) (cfgD("rising.cooldown-seconds", 25) * 1000);
        Sink s = new Sink();
        s.target = target.getUniqueId();
        sinkRun = s;
        root(p, true);
        p.setVelocity(new Vector());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    private void tickSink(Player p) {
        Sink s = sinkRun;
        s.age++;
        p.setVelocity(new Vector(0, Math.min(0, p.getVelocity().getY()), 0));
        Block ground = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (ground.getType().isSolid() && s.age % 2 == 0)
            p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0, 0.1, 0), 12, 0.35, 0.05, 0.35, 0.1, ground.getBlockData());
        if (s.age % 4 == 0) p.getWorld().spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 0.5, 0), 3, 0.3, 0.3, 0.3, 0.02);
        if (s.age < Math.max(1, cfgI("rising.sink-ticks", 20))) return;
        sinkRun = null;
        root(p, false);
        Player t = Bukkit.getPlayer(s.target);
        double range = Math.max(4, cfgD("rising.range", 20));
        // The target may have left, died or changed world while he sank: take whoever is nearest now.
        if (t == null || t.isDead() || !t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > range * 1.5 || !isEnemy(p, t))
            t = nearestEnemy(p, range);
        Location spot = t == null ? null : behindSpot(t);
        World w = p.getWorld();
        if (spot == null) {
            risingReady = now() + (long) (cfgD("rising.cooldown-seconds", 25) * 500); // half the cooldown back
            w.playSound(p.getLocation(), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 0.8f, 1.3f);
            w.spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 0.5, 0), 20, 0.4, 0.5, 0.4, 0.03);
            p.sendMessage(TEAL_TEXT + "No room behind them. You rise where you stood; half the cooldown is back.");
            return;
        }
        p.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
        w.playSound(spot, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.2f, 1.1f);
        w.spawnParticle(Particle.SCULK_SOUL, spot.clone().add(0, 0.5, 0), 30, 0.4, 0.6, 0.4, 0.04);
        Block under = spot.getBlock().getRelative(BlockFace.DOWN);
        if (under.getType().isSolid()) w.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.1, 0), 30, 0.4, 0.1, 0.4, 0.2, under.getBlockData());
    }

    /** A safe spot about 1.6 blocks behind the target, facing them; never inside a block or inside the target. */
    private Location behindSpot(Player t) {
        Location tl = t.getLocation();
        World w = t.getWorld();
        double[] xz = RevenantMath.behind(tl.getX(), tl.getZ(), tl.getYaw(), 1.6);
        int bx = (int) Math.floor(xz[0]), by = tl.getBlockY(), bz = (int) Math.floor(xz[1]);
        for (int[] o : RelayRift.searchOffsets(1)) {
            int x = bx + o[0], y = by + o[1], z = bz + o[2];
            if (!RelayRift.standable(w, x, y, z)) continue;
            Location spot = new Location(w, x + 0.5, y, z + 0.5);
            if (spot.distanceSquared(tl) < 0.81 || !w.getWorldBorder().isInside(spot)) continue;
            spot.setYaw(RevenantMath.yawTowards(spot.getX(), spot.getZ(), tl.getX(), tl.getZ()));
            spot.setPitch(10);
            return spot;
        }
        return null;
    }

    // ------------------------------------------------------------------ Undertow

    private void undertow(Player p) {
        if (busy(p)) return;
        long n = now();
        if (undertowReady > n) return;
        undertowReady = n + (long) (cfgD("undertow.cooldown-seconds", 30) * 1000);
        double radius = Math.max(1, cfgD("undertow.radius", 8));
        double strength = Math.max(0.1, cfgD("undertow.strength", 1.4));
        Location me = p.getLocation();
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof LivingEntity v) || !isEnemy(p, v)) continue;
            Vector pull = me.toVector().subtract(v.getLocation().toVector());
            double d = pull.length();
            if (d > radius || d < 0.5 || Math.hypot(pull.getX(), pull.getZ()) < 0.3) continue; // straight above/below: no pull direction
            // Pulls everyone in to roughly melee range; no damage.
            v.setVelocity(pull.setY(0).normalize().multiply(Math.min(strength, 0.25 + d * 0.18)).setY(0.15));
        }
        World w = p.getWorld();
        w.playSound(me, Sound.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(me, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 0.8f);
        for (int ring = 0; ring < 3; ring++) {
            double r = radius * (1 - ring * 0.3);
            for (int i = 0; i < 28; i++) {
                double a = i * Math.PI * 2 / 28 + ring * 0.2;
                w.spawnParticle(Particle.SCULK_SOUL, me.clone().add(Math.cos(a) * r, 0.4, Math.sin(a) * r), 1,
                        -Math.cos(a) * 0.3, 0, -Math.sin(a) * 0.3, 0.15);
            }
        }
    }

    // ------------------------------------------------------------------ targets

    /** Hostile mobs, and players who are in survival or adventure and not trusted either way. */
    private boolean isEnemy(Player p, LivingEntity v) {
        if (v.equals(p) || v.isDead() || v instanceof ArmorStand) return false;
        if (v instanceof Player o) {
            if (o.getGameMode() != GameMode.SURVIVAL && o.getGameMode() != GameMode.ADVENTURE) return false;
            return !plugin.listener().isTrusted(p, o) && !plugin.listener().isTrusted(o, p);
        }
        return v instanceof Enemy;
    }

    private Player nearestEnemy(Player p, double range) {
        Player best = null;
        double bestD = range * range;
        for (Player o : p.getWorld().getPlayers()) {
            if (!isEnemy(p, o) || plugin.relay().inDomain(o.getUniqueId())) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d <= bestD) { bestD = d; best = o; }
        }
        return best;
    }

    // ------------------------------------------------------------------ passives

    /** Abyssal Weight: no jumping and no upward launches for a couple of seconds. No potion icon or particles. */
    private void weigh(LivingEntity v) {
        setModifier(v, Attribute.JUMP_STRENGTH, weightKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        weighted.put(v.getUniqueId(), tick + Math.max(1, Math.round(cfgD("abyssal-weight-seconds", 2) * 20)));
    }

    private void tickWeight() {
        if (weighted.isEmpty()) return;
        for (var it = weighted.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());
            if (!(e instanceof LivingEntity v) || !v.isValid()) { it.remove(); continue; }
            if (tick >= entry.getValue()) { clearModifier(v, Attribute.JUMP_STRENGTH, weightKey); it.remove(); continue; }
            Vector vel = v.getVelocity();
            if (vel.getY() > 0.08 && !v.isInWater() && !v.isClimbing()) v.setVelocity(vel.setY(0));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHolderHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !isHolder(p)) return;
        if (!holdsMace(p) && plungeRun == null) return;
        // One cap for every Revenant hit, melee smash included, so a long drop never one-shots.
        if (e.getDamage() > cap()) e.setDamage(cap());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHolderHitLanded(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && holdsMace(p) && e.getEntity() instanceof LivingEntity v && !v.equals(p) && e.getFinalDamage() > 0) weigh(v);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p) || !holder(p)) return;
        if (inEitherHand(p) || landingGraceUntil >= tick) { e.setCancelled(true); landingGraceUntil = -1; }
    }

    private boolean holder(Player p) { return holder != null && holder.equals(p.getUniqueId()); }

    // ------------------------------------------------------------------ Temporal Divinity: suspension

    /** Whether Divinity would suspend this player now (the boss, not already suspended, not immune). */
    boolean suspendable(Player p) { return isBoss(p) && suspension == null && now() >= immuneUntil; }

    /**
     * Temporal Divinity caught the Revenant: he never goes to the void. Instead he hangs frozen 3 blocks above
     * where he stood, unable to move or use his powers, with no knockback resistance.
     */
    void suspend(Player p) {
        if (!suspendable(p)) return;
        if (plungeRun != null) plungeRun = null;
        if (sinkRun != null) { sinkRun = null; root(p, false); }
        Location start = p.getLocation();
        Location anchor = start.clone();
        for (int up = 1; up <= 3; up++) { // as high as there is headroom, up to three blocks
            Block feet = start.getBlock().getRelative(0, up, 0);
            if (!feet.isPassable() || !feet.getRelative(BlockFace.UP).isPassable()) break;
            anchor = start.clone().add(0, up, 0);
        }
        Suspension s = new Suspension();
        s.anchor = anchor;
        s.allowFlight = p.getAllowFlight();
        s.flying = p.isFlying();
        s.gravity = p.hasGravity();
        p.getPersistentDataContainer().set(flagsKey, PersistentDataType.BYTE_ARRAY,
                new byte[]{(byte) (s.allowFlight ? 1 : 0), (byte) (s.flying ? 1 : 0), (byte) (s.gravity ? 1 : 0)});
        int seconds = phase2 ? cfgI("divinity-suspend-seconds-phase2", 3) : cfgI("divinity-suspend-seconds", 5);
        s.remaining = Math.max(1, seconds) * 20;
        suspension = s;
        p.teleport(anchor, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setVelocity(new Vector());
        p.setFlying(false);
        p.setAllowFlight(true); // only stops vanilla's anti-hover kick; flying is cancelled while suspended
        p.setGravity(false);
        p.setFallDistance(0);
        root(p, true);
        setModifier(p, Attribute.KNOCKBACK_RESISTANCE, kbZeroKey, -1, AttributeModifier.Operation.ADD_NUMBER);
        World w = p.getWorld();
        w.playSound(anchor, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.6f, 0.5f);
        w.playSound(anchor, Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        Bukkit.broadcastMessage(RELAY_TEXT + "The Revenant is caught in the moment before the fall");
    }

    private void tickSuspension(Player p) {
        Suspension s = suspension;
        // He can't move himself (no walking, jumping or flying, no gravity), but with no knockback resistance
        // every hit pushes him through the air; the push dies away and he hangs where it left him.
        p.setVelocity(p.getVelocity().multiply(0.85));
        p.setFallDistance(0);
        if (tick % 4 == 0) { // a brown clock: a ring and two turning hands around him
            World w = p.getWorld();
            Location c = p.getLocation().add(0, 1.0, 0);
            Particle.DustOptions rim = new Particle.DustOptions(RelayRift.RIM, 1.1f), hand = new Particle.DustOptions(RelayRift.PALE, 0.9f);
            for (int i = 0; i < 20; i++) {
                double a = i * Math.PI * 2 / 20;
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 1.4, 0, Math.sin(a) * 1.4), 1, 0, 0, 0, 0,
                        i % 5 == 0 ? new Particle.DustOptions(RelayRift.DEEP, 1.3f) : rim);
            }
            double minute = -tick * 0.05, hour = -tick * 0.0125;
            for (int i = 1; i <= 4; i++) {
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(minute) * 0.3 * i, 0, Math.sin(minute) * 0.3 * i), 1, 0, 0, 0, 0, hand);
                if (i <= 3) w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(hour) * 0.25 * i, 0, Math.sin(hour) * 0.25 * i), 1, 0, 0, 0, 0, hand);
            }
        }
        if (--s.remaining <= 0) endSuspension(p, true);
    }

    /** Ends a suspension and restores his own movement. {@code gentle}: a slow, damage-free drop. */
    private void endSuspension(Player p, boolean gentle) {
        Suspension s = suspension;
        suspension = null;
        if (s == null || p == null) return;
        p.setGravity(s.gravity);
        p.setFlying(false);
        p.setAllowFlight(s.allowFlight);
        if (s.allowFlight) p.setFlying(s.flying);
        p.getPersistentDataContainer().remove(flagsKey);
        root(p, false);
        clearModifier(p, Attribute.KNOCKBACK_RESISTANCE, kbZeroKey);
        p.setFallDistance(0);
        immuneUntil = now() + (long) (Math.max(0, cfgD("divinity-immunity-seconds", 30)) * 1000);
        if (gentle) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0, false, false, false));
            landingGraceUntil = tick + 200;
        }
    }

    /** No ender pearls, chorus fruit or other plugins' teleports out of a suspension. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSuspendedTeleport(PlayerTeleportEvent e) {
        if (suspension != null && holder(e.getPlayer()) && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent e) {
        if (e.isFlying() && suspension != null && holder(e.getPlayer())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ boss fight

    private void startFight(CommandSender by) {
        Player h = holderOnline();
        if (state == State.PAUSED) { by.sendMessage(TEAL_TEXT + "The fight is paused while the holder is offline. It resumes when they rejoin."); return; }
        if (h == null || !isBoss(h)) { by.sendMessage("§cGive the Revenant to someone first: /revenant give <player>"); return; }
        if (state == State.RUNNING) { by.sendMessage(TEAL_TEXT + "The fight is already running."); return; }
        state = State.RUNNING;
        phase2 = false;
        savedHealth = -1;
        plunge.refillAll();
        risingReady = undertowReady = 0;
        applyStats(h);
        h.setHealth(h.getMaxHealth()); // every fight starts at full health with everything ready
        Bukkit.broadcastMessage(TEAL_TEXT + "§lThe Revenant rises. " + h.getName() + " carries the soul that would not let go.");
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.MASTER, 0.8f, 0.7f);
        save();
    }

    /** /revenant boss stop: ends everything quietly, no shatter. */
    private void stopFight(CommandSender by) {
        Player h = holderOnline();
        if (h != null) {
            endActions(h, true);
            removeRevenantItems(h);
            removeStats(h);
        }
        clearFight();
        Bukkit.broadcastMessage(TEAL_TEXT + "The Revenant's fight was called off.");
        if (by != null) by.sendMessage(TEAL_TEXT + "Revenant stopped: stats, boss bar and mace removed.");
    }

    private void clearFight() {
        state = State.NONE;
        itemId = null;
        holder = null;
        savedHealth = -1;
        phase2 = false;
        returnCountdown = -1;
        plunge.refillAll();
        risingReady = undertowReady = 0;
        immuneUntil = 0;
        hideBar();
        save();
    }

    private void enterPhase2(Player h) {
        phase2 = true;
        Bukkit.broadcastMessage(TEAL_TEXT + "§lThe Revenant remembers falling.");
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.MASTER, 0.9f, 0.6f);
        if (cfgB("phase2-sky", true) && plugin.finale() != null) plugin.finale().skyPulse(Math.max(1, cfgI("phase2-sky-seconds", 10)));
        save();
    }

    private void shatter(Player h, Player killer) {
        Location at = h.getLocation().add(0, 1, 0);
        World w = at.getWorld();
        w.spawnParticle(Particle.DUST, at, 160, 1.8, 1.8, 1.8, 0, new Particle.DustOptions(TEAL, 2.0f));
        w.spawnParticle(Particle.SCULK_SOUL, at, 90, 1.5, 1.5, 1.5, 0.12);
        w.spawnParticle(Particle.SOUL, at, 50, 1.2, 1.2, 1.2, 0.05);
        w.playSound(at, Sound.ENTITY_WARDEN_DEATH, SoundCategory.MASTER, 2.0f, 0.8f);
        w.playSound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.MASTER, 1.5f, 0.6f);
        Bukkit.broadcastMessage(TEAL_TEXT + "§lThe Revenant has shattered" + (killer != null && !killer.equals(h) ? " at the hands of " + killer.getName() : "")
                + ". §7The soul is finally still.");
        removeStats(h);
        clearFight();
    }

    /** The holder left mid-fight: the mace vanishes and the fight waits for them (health and charges kept). */
    private void pause(Player h) {
        savedHealth = Math.max(1, h.getHealth());
        state = State.PAUSED;
        removeRevenantItems(h);
        removeStats(h);
        Bukkit.broadcastMessage(TEAL_TEXT + "The Revenant has vanished… §7The fight is paused.");
        save();
    }

    /** Plunge, Rising and a suspension stop; anything rooted or floating is put back to normal. */
    private void endActions(Player p, boolean dropToGround) {
        plungeRun = null;
        if (sinkRun != null) { sinkRun = null; root(p, false); }
        if (suspension != null) endSuspension(p, false);
        if (dropToGround && !p.isOnGround()) {
            Location ground = groundBelow(p.getLocation(), 256);
            if (ground != null) { // so a rejoin mid-air can't become a fall
                ground.setYaw(p.getLocation().getYaw());
                ground.setPitch(p.getLocation().getPitch());
                p.teleport(ground, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            p.setFallDistance(0);
        }
    }

    // ------------------------------------------------------------------ boss bar and HUD

    private void hideBar() {
        if (bar == null) return;
        for (UUID id : barViewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        barViewers.clear();
    }

    private void barTick() {
        if (state != State.RUNNING && state != State.PAUSED) { hideBar(); return; }
        if (bar == null) bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        Player h = holderOnline();
        double max = Math.max(1, cfgD("boss.max-health", 60));
        Set<UUID> want = new HashSet<>();
        if (state == State.PAUSED || h == null) {
            bar.name(Component.text("The Revenant has vanished…", TEAL_COLOR));
            bar.progress((float) Math.max(0, Math.min(1, (savedHealth < 0 ? max : savedHealth) / max)));
            for (Player o : Bukkit.getOnlinePlayers()) want.add(o.getUniqueId());
        } else {
            String title = suspension != null ? "The Revenant — SUSPENDED" : phase2 ? "The Revenant — Phase 2" : "The Revenant";
            bar.name(Component.text(title, TEAL_COLOR));
            bar.progress((float) Math.max(0, Math.min(1, h.getHealth() / Math.max(1, h.getMaxHealth()))));
            double r = Math.max(8, cfgD("boss.bar-radius", 100));
            for (Player o : h.getWorld().getPlayers())
                if (o.getLocation().distanceSquared(h.getLocation()) <= r * r) want.add(o.getUniqueId());
        }
        for (UUID id : new HashSet<>(barViewers)) if (!want.contains(id)) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) o.hideBossBar(bar);
            barViewers.remove(id);
        }
        for (UUID id : want) if (barViewers.add(id)) { Player o = Bukkit.getPlayer(id); if (o != null) o.showBossBar(bar); }
    }

    private String captureHud(Player p) { return plugin.captures() == null ? "" : plugin.captures().hudSuffix(p); }

    private void hudTick(Player h) {
        if (h == null || !hudActive(h)) {
            if (hudShown && h != null) h.sendActionBar(Component.empty());
            hudShown = false;
            return;
        }
        hudShown = true;
        long n = now();
        boolean glyphs = cfgB("hud.use-glyphs", true);
        String pg = glyphs ? plugin.getConfig().getString(cfgPath("hud.plunge-glyph"), "\uE046") : "Plunge";
        String rg = glyphs ? plugin.getConfig().getString(cfgPath("hud.rising-glyph"), "\uE047") : "Rising";
        String ug = glyphs ? plugin.getConfig().getString(cfgPath("hud.undertow-glyph"), "\uE048") : "Undertow";
        plunge.resize(cfgI("plunge.charges", 2));
        int ready = plunge.ready(n);
        // Icons and timers only, no words (HudStatus). Rising can't be cast in a Time Dilate field: its icon goes dark.
        String ps = plungeRun != null ? HudStatus.entry(pg, 0, true) : HudStatus.charges(pg, ready, plunge.max(), plunge.nextIn(n));
        String rs = sinkRun != null ? HudStatus.entry(rg, 0, true)
                : plugin.relay().timeScale(h.getLocation(), h.getUniqueId()) < 1 ? "§8" + rg : HudStatus.entry(rg, risingReady - n, false);
        String us = HudStatus.entry(ug, undertowReady - n, false);
        String msg = ps + "  " + rs + "  " + us;
        if (suspension != null) msg = "§6\u231B §e" + HudStatus.time(suspension.remaining * 50L) + "  " + msg; // suspended: an hourglass and its timer
        h.sendActionBar(LEGACY.deserialize(msg + captureHud(h)));
    }



    // ------------------------------------------------------------------ tick

    void tick() {
        tick++;
        if (!cfgB("enabled", true)) return;
        Player h = holderOnline();
        if (h != null && isHolder(h)) {
            if (plungeRun != null) { if (h.isDead()) plungeRun = null; else tickPlunge(h); }
            if (sinkRun != null) { if (h.isDead()) { sinkRun = null; root(h, false); } else tickSink(h); }
            if (suspension != null) { if (h.isDead()) endSuspension(h, false); else tickSuspension(h); }
            if (state == State.RUNNING) {
                if (tick % 20 == 0 && !isBoss(h) && !h.isDead()) { pause(h); }
                else if (!phase2 && !h.isDead() && h.getHealth() <= h.getMaxHealth() * 0.5) enterPhase2(h);
                if (tick % 100 == 0) { savedHealth = h.getHealth(); save(); }
            }
        }
        // A paused fight with its holder online (a rejoin, a /reload, or the mace somehow lost) hands it back shortly.
        if (state == State.PAUSED && h != null && returnCountdown < 0) startReturn(h);
        if (returnCountdown >= 0) tickReturn();
        tickWeight();
        if (tick % 5 == 0) { barTick(); hudTick(h); }
        if (tick % 40 == 0) markInertCopies();
    }

    private void markInertCopies() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack main = p.getInventory().getItemInMainHand();
            if (isRevenantItem(main) && !(isHolder(p) && isLiveItem(main))) markInert(main);
        }
    }

    // ------------------------------------------------------------------ returning after a logout

    private void startReturn(Player h) {
        removeRevenantItems(h); // a crash may have left one behind; the fight hands out a fresh one
        returnCountdown = Math.max(20, cfgI("return-delay-seconds", 5) * 20);
        Bukkit.broadcastMessage(TEAL_TEXT + "The soul returns to " + h.getName() + "… §7(the Revenant is back in " + returnCountdown / 20 + "s)");
    }

    private void tickReturn() {
        Player h = holderOnline();
        if (h == null || state != State.PAUSED) { returnCountdown = -1; return; }
        if (h.isDead()) return; // waits on the death screen
        if (returnCountdown % 20 == 0) {
            h.getWorld().spawnParticle(Particle.SCULK_SOUL, h.getLocation().add(0, 1, 0), 18, 0.5, 0.8, 0.5, 0.03);
            h.getWorld().playSound(h.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.2f, 0.7f);
        }
        if (--returnCountdown > 0) return;
        returnCountdown = -1;
        handOver(h, false);
        Bukkit.broadcastMessage(TEAL_TEXT + "§lThe Revenant has returned. §7The fight goes on.");
    }

    /** Gives the live mace to {@code p} and applies boss stats; a paused fight resumes with its saved health. */
    private boolean handOver(Player p, boolean fresh) {
        if (state == State.RUNNING && holder(p) && !fresh) savedHealth = p.getHealth(); // re-giving mid-fight never heals
        if (itemId == null || fresh && state != State.PAUSED && state != State.RUNNING) itemId = UUID.randomUUID();
        if (fresh) { savedHealth = -1; phase2 = false; plunge.refillAll(); risingReady = undertowReady = 0; }
        removeRevenantItems(p);
        PlayerInventory inv = p.getInventory();
        ItemStack mace = createItem();
        if (inv.firstEmpty() < 0) { // full: the mace takes the selected slot and that item goes elsewhere
            ItemStack old = inv.getItemInMainHand();
            inv.setItemInMainHand(mace);
            if (old != null && old.getType() != Material.AIR) p.getWorld().dropItemNaturally(p.getLocation(), old);
        } else if (inv.getItemInMainHand().getType() == Material.AIR) {
            inv.setItemInMainHand(mace);
        } else {
            inv.addItem(mace);
        }
        holder = p.getUniqueId();
        if (state == State.PAUSED) state = State.RUNNING;
        else if (state == State.NONE) state = State.GIVEN;
        applyStats(p);
        double max = p.getMaxHealth();
        p.setHealth(savedHealth > 0 ? Math.min(max, savedHealth) : max);
        immuneUntil = 0;
        save();
        return true;
    }

    // ------------------------------------------------------------------ events: death, logout, join

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        e.getDrops().removeIf(this::isRevenantItem); // never drops, for anyone
        weighted.remove(p.getUniqueId());
        if (!isHolder(p)) return;
        endActions(p, false);
        removeRevenantItems(p); // with keepInventory it would otherwise stay in the inventory
        if (state == State.RUNNING) {
            shatter(p, p.getKiller());
        } else { // given but no fight running: it just vanishes; /revenant give brings it back
            removeStats(p);
            save();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // keepInventory would leave a mace behind after its holder died: remove anything not live.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) if (isRevenantItem(c[i]) && !(isHolder(p) && isLiveItem(c[i]))) inv.setItem(i, null);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (weighted.remove(p.getUniqueId()) != null && jump != null && jump.getModifier(weightKey) != null) jump.removeModifier(weightKey);
        if (barViewers.remove(p.getUniqueId()) && bar != null) p.hideBossBar(bar);
        if (!holder(p)) return;
        returnCountdown = -1;
        endActions(p, true);
        if (state == State.RUNNING) {
            pause(p);
        } else if (state == State.GIVEN) { // vanishes; /revenant give brings it back
            removeRevenantItems(p);
            removeStats(p);
            save();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        byte[] f = p.getPersistentDataContainer().get(flagsKey, PersistentDataType.BYTE_ARRAY);
        if (f != null && f.length == 3) { // a crash during a suspension: give him his own movement back
            p.setGravity(f[2] != 0);
            p.setFlying(false);
            p.setAllowFlight(f[0] != 0);
            if (f[0] != 0) p.setFlying(f[1] != 0);
            p.getPersistentDataContainer().remove(flagsKey);
        }
        if (!holder(p)) return;
        // A paused fight is picked up by tick(): the return countdown starts as soon as the holder is online.
        if (state == State.GIVEN && isBoss(p)) {
            applyStats(p); // after a crash the mace is still there but the stats are not
        }
    }

    // ------------------------------------------------------------------ keeping the mace with its holder

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLiveItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        boolean live = isLiveItem(e.getCurrentItem()) || isLiveItem(e.getCursor());
        if (live && (e.getSlotType() == InventoryType.SlotType.CRAFTING || e.getSlotType() == InventoryType.SlotType.RESULT)) {
            e.setCancelled(true); // not even into the 2x2 crafting grid
            return;
        }
        if (live && (isBundle(e.getCurrentItem()) || isBundle(e.getCursor()))) {
            e.setCancelled(true); // not into a bundle either
            return;
        }
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) return; // moving it around its own inventory is fine
        ItemStack hotbar = e.getHotbarButton() >= 0 ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : null;
        if (isLiveItem(e.getCurrentItem()) || isLiveItem(e.getCursor()) || isLiveItem(hotbar)
                || e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && isLiveItem(e.getWhoClicked().getInventory().getItemInOffHand()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!isLiveItem(e.getOldCursor())) return;
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < top) { e.setCancelled(true); return; } // a container or the crafting grid
    }

    private static boolean isBundle(ItemStack it) { return it != null && it.getType().name().endsWith("BUNDLE"); }

    /** Not into item frames, and not handed to an allay. */
    @EventHandler(ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        if ((e.getRightClicked() instanceof ItemFrame || e.getRightClicked() instanceof Allay)
                && (isLiveItem(e.getPlayer().getInventory().getItemInMainHand()) || isLiveItem(e.getPlayer().getInventory().getItemInOffHand())))
            e.setCancelled(true);
    }

    /** Not onto an armor stand. */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (isLiveItem(e.getPlayerItem())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ shutdown and persistence

    void shutdown() {
        Player h = holderOnline();
        for (UUID id : new ArrayList<>(weighted.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity v) clearModifier(v, Attribute.JUMP_STRENGTH, weightKey);
        }
        weighted.clear();
        if (h != null && holder(h)) {
            endActions(h, true);
            if (state == State.RUNNING) {
                savedHealth = Math.max(1, h.getHealth());
                state = State.PAUSED;
                removeRevenantItems(h);
                removeStats(h);
            } else if (state == State.GIVEN) {
                removeRevenantItems(h);
                removeStats(h);
            }
        }
        hideBar();
        save();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file);
            state = State.valueOf(y.getString("state", "NONE").toUpperCase(Locale.ROOT));
            String id = y.getString("item-id"), h = y.getString("holder");
            itemId = id == null || id.isEmpty() ? null : UUID.fromString(id);
            holder = h == null || h.isEmpty() ? null : UUID.fromString(h);
            savedHealth = y.getDouble("health", -1);
            phase2 = y.getBoolean("phase2", false);
            List<Long> times = y.getLongList("plunge-ready");
            long[] t = new long[times.size()];
            for (int i = 0; i < t.length; i++) t[i] = times.get(i);
            plunge.resize(Math.max(1, t.length));
            plunge.load(t);
            risingReady = y.getLong("rising-ready", 0);
            undertowReady = y.getLong("undertow-ready", 0);
            if (state == State.RUNNING) state = State.PAUSED; // the server stopped without a clean shutdown
            if (holder == null || itemId == null) state = State.NONE;
        } catch (Exception ex) {
            plugin.getLogger().severe("revenant.yml could not be read (" + ex.getMessage() + "); the Revenant starts with no fight.");
            state = State.NONE;
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("state", state.name());
        y.set("item-id", itemId == null ? "" : itemId.toString());
        y.set("holder", holder == null ? "" : holder.toString());
        y.set("health", savedHealth);
        y.set("phase2", phase2);
        List<Long> times = new ArrayList<>();
        for (long t : plunge.times()) times.add(t);
        y.set("plunge-ready", times);
        y.set("rising-ready", risingReady);
        y.set("undertow-ready", undertowReady);
        try {
            plugin.getDataFolder().mkdirs();
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            y.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save revenant.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ /revenant

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage("§cThat player isn't online."); return true; }
            boolean fresh = a.length >= 3 && a[2].equalsIgnoreCase("fresh");
            Player current = holderOnline();
            if (state == State.RUNNING && current != null && !current.equals(t)) {
                s.sendMessage("§c" + current.getName() + " holds the Revenant in a running fight. /revenant boss stop first.");
                return true;
            }
            if (current != null && !current.equals(t) && isBoss(current)) { // handing it on outside a fight
                endActions(current, false);
                removeRevenantItems(current);
                removeStats(current);
            }
            returnCountdown = -1;
            handOver(t, fresh);
            s.sendMessage(TEAL_TEXT + "The Revenant is in " + t.getName() + "'s hands"
                    + (state == State.RUNNING ? " and the fight goes on" + (fresh ? " at full health." : " with its saved health.") : ". Start the fight with /revenant boss start."));
            t.sendMessage(TEAL_TEXT + "§lThe Revenant answers to you.");
            t.getWorld().playSound(t.getLocation(), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1f, 0.9f);
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("boss")) {
            if (a[1].equalsIgnoreCase("start")) { startFight(s); return true; }
            if (a[1].equalsIgnoreCase("stop")) { stopFight(s); return true; }
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("status")) {
            Player h = holderOnline();
            String who = holder == null ? "nobody" : (h != null ? h.getName() : Bukkit.getOfflinePlayer(holder).getName() + " (offline)");
            s.sendMessage(TEAL_TEXT + "Revenant: " + state.name().toLowerCase(Locale.ROOT) + ", held by " + who
                    + (phase2 ? ", phase 2" : "") + (savedHealth > 0 ? ", saved health " + Math.round(savedHealth) : ""));
            return true;
        }
        s.sendMessage(TEAL_TEXT + "/revenant give <player> [fresh] §7| " + TEAL_TEXT + "/revenant boss <start|stop> §7| " + TEAL_TEXT + "/revenant status");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("give", "boss", "status"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 2 && a[0].equalsIgnoreCase("boss")) out.addAll(List.of("start", "stop"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.add("fresh");
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
