package dev.soulbound;

import com.destroystokyo.paper.ParticleBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Rift (Relay, sneak + left-click): quick travel between two saved anchors.
 *
 * Choosing a destination starts a channel while a brown tear grows in front of the holder. Damage,
 * moving away, death, logout, letting go of the Relay or the finale starting breaks it. When it
 * completes the holder is moved to the anchor, and the tear stays open where they stood for a few
 * seconds: anyone who walks into it arrives at the same anchor a second later. Anchors and the
 * cooldown belong to the player (never the item) and are kept in relay-rift.yml.
 *
 * Visuals are server-side only: one flat item display per tear showing six pack frames (swapped by
 * the plugin so the stutters can be random), full brightness and a brown glow outline via the
 * display's own glow colour (no scoreboard team), plus one flat light-spill display and dust.
 */
final class RelayRift implements Listener {
    static final int FRAMES = 6;
    static final Color RIM = Color.fromRGB(0xB0703A), DEEP = Color.fromRGB(0x8C5530), PALE = Color.fromRGB(0xF0C48C);
    private static final String RELAY = "§x§B§8§9§3§5§8";
    private static final String[] NAMES = {"A", "B"};
    /** Menu layout: travel A, travel B, status, set A, set B. */
    private static final int TRAVEL_A = 1, TRAVEL_B = 3, STATUS = 4, SET_A = 5, SET_B = 7;

    private final SoulboundGemsPlugin plugin;
    private final RelayGem relay;
    private final File file;
    private final Random random = new Random();
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> landingSafeUntil = new HashMap<>();
    private final Map<UUID, Cast> casts = new HashMap<>();
    private final Map<UUID, Follower> followers = new HashMap<>();
    /** Tears that outlived their cast (closing) and short arrival tears. */
    private final List<Tear> loose = new ArrayList<>();
    private final Map<ChunkRef, Integer> tickets = new HashMap<>();
    private final Map<String, ItemStack> modelItems = new HashMap<>();
    private List<int[]> offsets = List.of();
    private int offsetsRadius = -1;
    private boolean sealed;
    private long tick;

    /** A saved destination. Stored by world name so it survives the world being unloaded. */
    record Anchor(String world, double x, double y, double z, float yaw, float pitch) {
        static Anchor of(Location l) {
            return new Anchor(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
        String describe() {
            return world + " " + (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
        }
    }

    private static final class Profile {
        final Anchor[] anchors = new Anchor[2];
        long cooldownUntil;
    }

    private enum Stage { CHANNEL, OPEN }

    private final class Cast {
        final UUID owner;
        final int slot;
        final Anchor anchor;
        final Location origin;
        final Tear tear;
        final ChunkRef ticket;
        /** Spots already landed on at the destination, so nobody arrives inside anyone. */
        final List<Location> landed = new ArrayList<>();
        Stage stage = Stage.CHANNEL;
        int age;
        long closesAt;

        Cast(UUID owner, int slot, Anchor anchor, Location origin, Tear tear, ChunkRef ticket) {
            this.owner = owner;
            this.slot = slot;
            this.anchor = anchor;
            this.origin = origin;
            this.tear = tear;
            this.ticket = ticket;
        }
    }

    private record Follower(UUID player, Anchor anchor, long arriveAt, List<Location> landed) {}

    private record ChunkRef(UUID world, int x, int z) {}

    /** The rift menu. Holding the owner lets the click handler ignore anyone else's view of it. */
    static final class Menu implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        Menu(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    RelayRift(SoulboundGemsPlugin plugin, RelayGem relay) {
        this.plugin = plugin;
        this.relay = relay;
        this.file = new File(plugin.getDataFolder(), "relay-rift.yml");
        load();
    }

    // ------------------------------------------------------------------ config

    private double cfgD(String k, double d) { return plugin.getConfig().getDouble("mythics.relay.rift." + k, d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt("mythics.relay.rift." + k, d); }
    private boolean cfgB(String k, boolean d) { return plugin.getConfig().getBoolean("mythics.relay.rift." + k, d); }
    boolean enabled() { return cfgB("enabled", true) && plugin.getConfig().getBoolean("mythics.relay.enabled", true); }
    private boolean finaleRunning() { return plugin.finale() != null && plugin.finale().isActive(); }
    private String domainWorld() { return plugin.getConfig().getString("mythics.relay.temporal.divinity.world", "soulbound_temporal_void"); }
    private int channelTicks() { return Math.max(20, Math.min(400, (int) Math.round(cfgD("channel-seconds", 5) * 20))); }
    private Color glowColor() { return parseColor(plugin.getConfig().getString("mythics.relay.rift.glow-color", "#B0703A"), RIM); }

    /** "#RRGGBB" or "RRGGBB" to a colour, or the fallback if it does not parse. */
    static Color parseColor(String hex, Color fallback) {
        if (hex == null) return fallback;
        String h = hex.trim().startsWith("#") ? hex.trim().substring(1) : hex.trim();
        try {
            return h.length() == 6 ? Color.fromRGB(Integer.parseInt(h, 16)) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ state shown elsewhere

    private Profile profile(UUID id) { return profiles.computeIfAbsent(id, k -> new Profile()); }
    private long now() { return System.currentTimeMillis(); }
    private long cooldownLeft(UUID id) { Profile p = profiles.get(id); return p == null ? 0 : Math.max(0, p.cooldownUntil - now()); }
    private long combatLeft(UUID id) { return Math.max(0, combatUntil.getOrDefault(id, 0L) - now()); }
    boolean busy(UUID id) { return casts.containsKey(id); }

    /** Status for the Relay HUD, or null when Rift is switched off. */
    /**
     * The Rift's action-bar entry (see HudStatus): bright with "Ready!" when ready, dimmed with a timer while it
     * recharges, an orange timer while combat makes it unstable, dark while the finale seals it, "…" while channelling.
     * {@code ascii}: for the slot beside the hotbar, whose font only has ASCII, so "…" is written "...". Null when off.
     */
    String hudEntry(Player p, String icon, boolean ascii) {
        if (!enabled()) return null;
        UUID id = p.getUniqueId();
        if (casts.containsKey(id)) return "§f" + icon + (ascii ? " §e..." : " §e\u2026");
        if (finaleRunning()) return "§8" + icon;
        long cd = cooldownLeft(id);
        if (cd > 0) return "§7" + icon + " §b" + fmt(cd);
        long combat = combatLeft(id);
        if (combat > 0) return "§7" + icon + " §6" + fmt(combat);
        return "§f" + icon + " " + HudStatus.READY;
    }

    private static String fmt(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    // ------------------------------------------------------------------ menu

    /** Sneak + left-click with the Relay in the main hand. */
    private boolean revenant(Player p) { return plugin.revenant() != null && plugin.revenant().isBoss(p); }

    void openMenu(Player p) {
        if (!enabled()) return;
        if (revenant(p)) { p.sendMessage("§6The Revenant can't open rifts."); return; }
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) return;
        if (finaleRunning()) { p.sendMessage("§6Rifts are sealed while the finale runs."); return; }
        if (relay.inDomain(p.getUniqueId()) || p.getWorld().getName().equals(domainWorld())) return;
        if (casts.containsKey(p.getUniqueId())) return;
        Menu menu = new Menu(p.getUniqueId());
        menu.inventory = Bukkit.createInventory(menu, 9, Component.text("Rift", TextColor.color(0xB0703A)));
        fillMenu(p, menu.inventory);
        p.openInventory(menu.inventory);
        GemSounds.playTo(plugin, "relay.rift-menu", p);
    }

    private void fillMenu(Player p, Inventory inv) {
        Profile pr = profile(p.getUniqueId());
        for (int slot = 0; slot < 2; slot++) {
            Anchor a = pr.anchors[slot];
            List<String> lore = new ArrayList<>();
            if (a == null) {
                lore.add("§8Not set. Use Set " + NAMES[slot] + " here.");
            } else {
                lore.add("§7" + a.describe());
                if (Bukkit.getWorld(a.world()) == null) lore.add("§c(world not loaded)");
                lore.add("");
                lore.add("§7Channel " + (channelTicks() / 20) + "s. Don't move or get hurt.");
                lore.add("§7Others can follow through for " + cfgI("open-seconds", 4) + "s.");
            }
            inv.setItem(slot == 0 ? TRAVEL_A : TRAVEL_B, button(slot == 0 ? Material.ENDER_PEARL : Material.ENDER_EYE,
                    RELAY + "Travel to " + NAMES[slot], lore));
            List<String> setLore = new ArrayList<>();
            setLore.add("§7Save where you stand as anchor " + NAMES[slot] + ".");
            setLore.add(a == null ? "§8Currently not set." : "§8Currently: " + a.describe());
            inv.setItem(slot == 0 ? SET_A : SET_B, button(slot == 0 ? Material.LODESTONE : Material.RECOVERY_COMPASS,
                    RELAY + "Set " + NAMES[slot] + " here", setLore));
        }
        UUID id = p.getUniqueId();
        long cd = cooldownLeft(id), combat = combatLeft(id);
        String status = cd > 0 ? "§bRecharging: " + fmt(cd) : combat > 0 ? "§6Unstable: in combat (" + fmt(combat) + ")" : "§aReady";
        inv.setItem(STATUS, button(Material.CLOCK, RELAY + "Rift", List.of(status)));
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(menu.owner)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot != TRAVEL_A && slot != TRAVEL_B && slot != SET_A && slot != SET_B) return;
        // Act next tick, outside the click event, after the menu has closed.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.closeInventory();
            if (slot == TRAVEL_A || slot == TRAVEL_B) travel(p, slot == TRAVEL_A ? 0 : 1);
            else setAnchor(p, slot == SET_A ? 0 : 1);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ anchors

    /** Why a rift may not anchor at this spot, or null if it may. */
    String blockedReason(Location l) {
        if (l == null || l.getWorld() == null) return "that world isn't loaded";
        if (finaleRunning()) return "rifts are sealed while the finale runs";
        String w = l.getWorld().getName();
        if (w.equals(domainWorld())) return "rifts can't anchor inside Temporal Divinity";
        String capture = plugin.captures() == null ? null : plugin.captures().riftBlocked(l);
        if (capture != null) return "rifts can't anchor inside the capture point " + capture + " while it runs";
        for (String b : plugin.getConfig().getStringList("mythics.relay.rift.blocked-worlds")) {
            if (b.equalsIgnoreCase(w)) return "rifts can't anchor in this world";
        }
        for (Map<?, ?> area : plugin.getConfig().getMapList("mythics.relay.rift.blocked-areas")) {
            Object aw = area.get("world");
            if (aw == null || !aw.toString().equalsIgnoreCase(w)) continue;
            double x1 = num(area.get("x1")), z1 = num(area.get("z1")), x2 = num(area.get("x2")), z2 = num(area.get("z2"));
            if (Double.isNaN(x1) || Double.isNaN(z1) || Double.isNaN(x2) || Double.isNaN(z2)) continue;
            if (l.getX() >= Math.min(x1, x2) && l.getX() <= Math.max(x1, x2) + 1
                    && l.getZ() >= Math.min(z1, z2) && l.getZ() <= Math.max(z1, z2) + 1) {
                Object name = area.get("name");
                return "rifts can't anchor in " + (name == null ? "this area" : name.toString());
            }
        }
        return null;
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? Double.NaN : Double.parseDouble(o.toString()); } catch (NumberFormatException ex) { return Double.NaN; }
    }

    private void setAnchor(Player p, int slot) {
        if (!enabled() || !holdsRelay(p)) return;
        Location at = p.getLocation();
        String blocked = blockedReason(at);
        if (blocked != null) { p.sendMessage("§6Can't set anchor " + NAMES[slot] + ": " + blocked + "."); return; }
        Anchor a = Anchor.of(at);
        profile(p.getUniqueId()).anchors[slot] = a;
        save();
        p.sendMessage(RELAY + "Anchor " + NAMES[slot] + " set: §7" + a.describe());
        if (findSpot(a, List.of(), p) == null) p.sendMessage("§6Warning: there is no safe ground near here, so a rift could not land on it.");
        GemSounds.playTo(plugin, "relay.rift-set-anchor", p);
        dust(at.clone().add(0, 1, 0), 12, 0.3, 0.6, RIM);
    }

    // ------------------------------------------------------------------ casting

    private boolean holdsRelay(Player p) { return relay.mainHandIsRelay(p) || relay.offHandIsRelay(p); }

    private void travel(Player p, int slot) {
        UUID id = p.getUniqueId();
        if (!enabled() || p.isDead() || !holdsRelay(p) || revenant(p)) return;
        if (finaleRunning()) { p.sendMessage("§6Rifts are sealed while the finale runs."); return; }
        if (relay.inDomain(id) || p.getWorld().getName().equals(domainWorld()) || casts.containsKey(id)) return;
        String capture = plugin.captures() == null ? null : plugin.captures().riftBlocked(p.getLocation());
        if (capture != null) { p.sendMessage("\u00A76Rifts can't open inside the capture point " + capture + " while it runs."); return; }
        long cd = cooldownLeft(id);
        if (cd > 0) { p.sendMessage("§6Rift recharging (" + fmt(cd) + ")."); return; }
        long combat = combatLeft(id);
        if (combat > 0) { p.sendMessage("§6Rift unstable: in combat (" + fmt(combat) + ")"); return; }
        if (!relay.chargedFor(p, "rift")) return; // Tuned In must be full (used up only when the jump succeeds)
        Anchor a = profile(id).anchors[slot];
        if (a == null) { p.sendMessage("§6Anchor " + NAMES[slot] + " isn't set."); return; }
        Location target = a.location();
        if (target == null) { p.sendMessage("§6Anchor " + NAMES[slot] + "'s world (" + a.world() + ") isn't loaded."); return; }
        String blocked = blockedReason(target);
        if (blocked != null) { p.sendMessage("§6Anchor " + NAMES[slot] + " can't be reached: " + blocked + "."); return; }
        if (p.isInsideVehicle()) { p.sendMessage("§6Dismount before opening a rift."); return; }
        if (findSpot(a, List.of(), p) == null) {
            p.sendMessage("§6Rift collapsed: no safe ground near anchor " + NAMES[slot] + ". No cooldown used.");
            return;
        }
        Location origin = p.getLocation();
        Vector forward = origin.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) forward = new Vector(0, 0, 1);
        forward.normalize();
        Location base = origin.clone().add(forward.clone().multiply(1.8));
        base.setYaw(origin.getYaw());
        base.setPitch(0);
        Tear tear = new Tear(base, forward, cfgD("width", 2.5), cfgD("height", 6.0), true);
        tear.open = 0.04;
        tear.tall = 0.5;
        Cast c = new Cast(id, slot, a, origin.clone(), tear, hold(target));
        casts.put(id, c);
        World w = origin.getWorld();
        GemSounds.play(plugin, "relay.rift-channel", base);
        p.sendMessage(RELAY + "Opening a rift to " + NAMES[slot] + "… hold still.");
    }

    /** Breaks a channel. A penalty (damage, movement, death, logout, letting go) sets the short cooldown. */
    private void breakChannel(Cast c, String message, boolean penalty) {
        if (casts.remove(c.owner) == null) return;
        closeTear(c.tear);
        release(c.ticket);
        Player p = Bukkit.getPlayer(c.owner);
        if (penalty) {
            int seconds = Math.max(0, cfgI("cancel-cooldown-seconds", 10));
            if (seconds > 0) {
                Profile pr = profile(c.owner);
                pr.cooldownUntil = Math.max(pr.cooldownUntil, now() + seconds * 1000L);
                save();
            }
        }
        if (p != null && message != null) p.sendMessage(message);
    }

    private void complete(Cast c, Player p) {
        Location dest = findSpot(c.anchor, c.landed, p);
        if (dest == null) {
            breakChannel(c, "§6Rift collapsed: no safe ground near anchor " + NAMES[c.slot] + ". No cooldown used.", false);
            return;
        }
        p.setFallDistance(0);
        if (!p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            breakChannel(c, "§6Rift collapsed: something stopped the jump. No cooldown used.", false);
            return;
        }
        c.landed.add(dest);
        relay.spendFor(p, "rift");
        Profile pr = profile(c.owner);
        pr.cooldownUntil = now() + Math.max(0, cfgI("cooldown-seconds", 300)) * 1000L;
        save();
        protectLanding(p);
        arrival(dest, true);
        c.stage = Stage.OPEN;
        c.closesAt = tick + Math.max(1, Math.round(cfgD("open-seconds", 4) * 20));
        c.tear.open = 1;
        c.tear.tall = 1;
    }

    private void endOpen(Cast c) {
        if (casts.remove(c.owner) == null) return;
        closeTear(c.tear);
        release(c.ticket);
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        tick++;
        boolean blocked = !enabled() || finaleRunning();
        if (blocked && !sealed) {
            // Switched off, or the finale began: nothing in flight may carry on.
            for (Cast c : new ArrayList<>(casts.values())) {
                if (c.stage == Stage.CHANNEL) breakChannel(c, "§6The rift collapsed.", false);
                else endOpen(c);
            }
            followers.clear();
        }
        sealed = blocked;

        for (Cast c : new ArrayList<>(casts.values())) {
            c.age++;
            Player p = Bukkit.getPlayer(c.owner);
            if (c.stage == Stage.CHANNEL) {
                if (p == null) { breakChannel(c, null, true); continue; }
                if (p.isDead() || !p.getWorld().equals(c.origin.getWorld())
                        || p.getLocation().distanceSquared(c.origin) > square(cfgD("cancel-distance", 1.5))) {
                    breakChannel(c, "§6Rift broken: you moved.", true);
                    continue;
                }
                if (!holdsRelay(p)) { breakChannel(c, "§6Rift broken: you let go of the Relay.", true); continue; }
                if (relay.inDomain(c.owner)) { breakChannel(c, null, false); continue; }
                int total = channelTicks();
                if (c.age <= 10) {
                    double s = c.age / 10.0; // a crack tears open in the first half second
                    c.tear.open = 0.04 + 0.30 * s;
                    c.tear.tall = 0.5 + 0.2 * s;
                } else {
                    double s = Math.min(1, (c.age - 10) / (double) Math.max(1, total - 10));
                    c.tear.open = 0.34 + 0.66 * s;
                    c.tear.tall = 0.7 + 0.3 * s;
                }
                c.tear.tick();
                if (c.age >= total) complete(c, p);
            } else {
                if (tick >= c.closesAt || !c.tear.alive()) { endOpen(c); continue; }
                catchFollowers(c);
                c.tear.tick();
            }
        }

        for (Iterator<Tear> it = loose.iterator(); it.hasNext(); ) {
            Tear t = it.next();
            t.tick();
            if (!t.alive()) it.remove();
        }

        for (Follower f : new ArrayList<>(followers.values())) {
            if (tick < f.arriveAt()) continue;
            followers.remove(f.player());
            deliver(f);
        }

        if (tick % 200 == 0) {
            long n = now();
            combatUntil.values().removeIf(v -> v <= n);
            landingSafeUntil.values().removeIf(v -> v < tick);
        }
    }

    private static double square(double v) { return v * v; }

    // ------------------------------------------------------------------ following

    /** True when a point relative to a tear's foot is inside the opening: along it, near its plane, foot to top. */
    static boolean inside(double dx, double dy, double dz, double facingX, double facingZ, double width, double height, double depth) {
        double across = dx * facingX + dz * facingZ;
        double along = dz * facingX - dx * facingZ;
        return Math.abs(along) <= width / 2 && Math.abs(across) <= depth && dy >= -1.0 && dy <= height;
    }

    private void catchFollowers(Cast c) {
        Tear t = c.tear;
        World w = t.base.getWorld();
        for (Player o : w.getNearbyPlayers(t.base, Math.max(t.width, t.height) / 2 + 2)) {
            UUID id = o.getUniqueId();
            if (id.equals(c.owner) || followers.containsKey(id) || casts.containsKey(id) || revenant(o)) continue; // the Revenant can't follow
            if (o.isDead() || o.getGameMode() == GameMode.SPECTATOR || o.isInsideVehicle() || relay.inDomain(id)) continue;
            Location l = o.getLocation();
            if (!inside(l.getX() - t.base.getX(), l.getY() - t.base.getY(), l.getZ() - t.base.getZ(),
                    t.forward.getX(), t.forward.getZ(), t.width * t.open, t.height * t.tall, 0.8)) continue;
            followers.put(id, new Follower(id, c.anchor, tick + Math.max(1, cfgI("follow-delay-ticks", 20)), c.landed));
            dust(l.clone().add(0, 1, 0), 14, 0.3, 0.7, RIM);
        }
    }

    private void deliver(Follower f) {
        Player o = Bukkit.getPlayer(f.player());
        if (o == null || o.isDead() || !o.isValid() || o.isInsideVehicle() || o.getGameMode() == GameMode.SPECTATOR) return;
        if (relay.inDomain(f.player()) || finaleRunning()) return;
        Location spot = findSpot(f.anchor(), f.landed(), o);
        if (spot == null) { o.sendMessage("§6The rift spat you back out: nowhere safe to land."); return; }
        o.setFallDistance(0);
        if (!o.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN)) return;
        f.landed().add(spot);
        protectLanding(o);
        arrival(spot, false);
    }

    private void protectLanding(Player p) {
        p.setFallDistance(0);
        landingSafeUntil.put(p.getUniqueId(), tick + Math.max(1, cfgI("arrival-protection-ticks", 20)));
    }

    /** Particles everyone within flash-radius can see, a sound, and (for the holder) a short small tear. */
    private void arrival(Location at, boolean holder) {
        World w = at.getWorld();
        int radius = Math.max(16, cfgI("flash-radius", 64));
        Location mid = at.clone().add(0, 1.2, 0);
        new ParticleBuilder(Particle.DUST).location(mid).receivers(radius).count(holder ? 36 : 14)
                .offset(0.5, 1.1, 0.5).extra(0).data(new Particle.DustOptions(RIM, 1.6f)).force(true).spawn();
        new ParticleBuilder(Particle.DUST).location(mid).receivers(radius).count(holder ? 18 : 6)
                .offset(0.3, 1.4, 0.3).extra(0).data(new Particle.DustOptions(PALE, 1.2f)).force(true).spawn();
        if (holder) {
            for (int i = 0; i < 10; i++) { // a tall column so the spot reads from a distance
                new ParticleBuilder(Particle.DUST).location(at.clone().add(0, 2.5 + i * 0.8, 0)).receivers(radius).count(2)
                        .offset(0.08, 0.3, 0.08).extra(0).data(new Particle.DustOptions(i % 3 == 0 ? PALE : RIM, 1.4f)).force(true).spawn();
            }
            Vector facing = at.getDirection().setY(0);
            if (facing.lengthSquared() < 1.0e-4) facing = new Vector(0, 0, 1);
            Location base = at.clone().add(facing.clone().normalize().multiply(1.2));
            base.setPitch(0);
            Tear small = new Tear(base, facing.normalize(), cfgD("width", 2.5) * 0.5, cfgD("height", 6.0) * 0.5, false);
            small.life = 20;
            loose.add(small);
        }
        GemSounds.play(plugin, "relay.rift-arrival", at);
    }

    // ------------------------------------------------------------------ safe landing

    /** Block offsets within r, nearest first, preferring the same layer and above over below. */
    static List<int[]> searchOffsets(int r) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++)
                    if (dx * dx + dy * dy + dz * dz <= r * r) out.add(new int[]{dx, dy, dz});
        out.sort(Comparator.<int[]>comparingDouble(o -> o[0] * o[0] + o[2] * o[2] + 1.2 * o[1] * o[1])
                .thenComparingInt(o -> o[1] < 0 ? 1 : 0));
        return out;
    }

    private List<int[]> offsets() {
        int r = Math.max(0, Math.min(8, cfgI("safe-search-radius", 5)));
        if (r != offsetsRadius) { offsets = searchOffsets(r); offsetsRadius = r; }
        return offsets;
    }

    /** Nearest spot to the anchor with ground underfoot and room to stand, not on anyone already there. */
    private Location findSpot(Anchor a, List<Location> taken, Player traveller) {
        Location anchor = a.location();
        if (anchor == null || blockedReason(anchor) != null) return null;
        World w = anchor.getWorld();
        int bx = anchor.getBlockX(), by = anchor.getBlockY(), bz = anchor.getBlockZ();
        Collection<Player> near = w.getNearbyPlayers(anchor, offsetsRadius + 3.0);
        for (int[] o : offsets()) {
            int x = bx + o[0], y = by + o[1], z = bz + o[2];
            if (!standable(w, x, y, z)) continue;
            Location spot = new Location(w, x + 0.5, y, z + 0.5, a.yaw(), a.pitch());
            if (crowded(spot, taken, near, traveller)) continue;
            return spot;
        }
        return null;
    }

    private static boolean crowded(Location spot, List<Location> taken, Collection<Player> near, Player traveller) {
        for (Location l : taken) if (closeTo(spot, l)) return true;
        for (Player p : near) if (!p.equals(traveller) && closeTo(spot, p.getLocation())) return true;
        return false;
    }

    private static boolean closeTo(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld()) || Math.abs(a.getY() - b.getY()) > 1.8) return false;
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz < 0.9 * 0.9;
    }

    static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) return false;
        Block feet = w.getBlockAt(x, y, z);
        if (!w.getWorldBorder().isInside(feet.getLocation())) return false;
        Block head = feet.getRelative(BlockFace.UP), ground = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable() || head.isLiquid()) return false;
        if (hazard(feet.getType()) || hazard(head.getType())) return false;
        return ground.getType().isSolid() && !hazard(ground.getType());
    }

    private static boolean hazard(Material m) {
        return switch (m) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, POWDER_SNOW, SWEET_BERRY_BUSH,
                 WITHER_ROSE, COBWEB, POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ chunk tickets (the destination stays loaded)

    private ChunkRef hold(Location l) {
        ChunkRef ref = new ChunkRef(l.getWorld().getUID(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
        if (tickets.merge(ref, 1, Integer::sum) == 1) l.getWorld().getChunkAt(ref.x(), ref.z()).addPluginChunkTicket(plugin);
        return ref;
    }

    private void release(ChunkRef ref) {
        if (ref == null) return;
        Integer left = tickets.computeIfPresent(ref, (k, v) -> v > 1 ? v - 1 : null);
        if (left != null) return;
        World w = Bukkit.getWorld(ref.world());
        if (w != null) w.getChunkAt(ref.x(), ref.z()).removePluginChunkTicket(plugin);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity source = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : e.getDamager();
        if (!(source instanceof Player attacker) || attacker.equals(victim)) return;
        long until = now() + Math.max(0, cfgI("combat-tag-seconds", 15)) * 1000L;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    /** One second of fall-damage protection after arriving, and nothing else. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLanding(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && landingSafeUntil.getOrDefault(e.getEntity().getUniqueId(), -1L) >= tick) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Cast c = casts.get(e.getEntity().getUniqueId());
        if (c != null && c.stage == Stage.CHANNEL && e.getFinalDamage() > 0) breakChannel(c, "§6Rift broken: you were hurt.", true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        Cast c = casts.get(id);
        if (c != null && c.stage == Stage.CHANNEL) breakChannel(c, null, true);
        followers.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Cast c = casts.get(id);
        if (c != null) {
            if (c.stage == Stage.CHANNEL) breakChannel(c, null, true);
            else endOpen(c); // nobody new can follow; anyone already caught still arrives
        }
        followers.remove(id);
        landingSafeUntil.remove(id);
    }

    void shutdown() {
        for (Cast c : casts.values()) { c.tear.remove(); release(c.ticket); }
        casts.clear();
        loose.forEach(Tear::remove);
        loose.clear();
        followers.clear();
        for (ChunkRef ref : new ArrayList<>(tickets.keySet())) { tickets.put(ref, 1); release(ref); }
        save();
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file);
            for (String key : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                Profile pr = profile(UUID.fromString(key));
                pr.cooldownUntil = s.getLong("cooldown-until", 0L);
                for (int i = 0; i < 2; i++) {
                    ConfigurationSection a = s.getConfigurationSection("anchors." + NAMES[i].toLowerCase());
                    if (a == null || a.getString("world") == null) continue;
                    pr.anchors[i] = new Anchor(a.getString("world"), a.getDouble("x"), a.getDouble("y"), a.getDouble("z"),
                            (float) a.getDouble("yaw"), (float) a.getDouble("pitch"));
                }
            }
        } catch (Exception ex) {
            // Keep the unreadable file for repair instead of overwriting it on the next save.
            File broken = new File(file.getParentFile(), "relay-rift.broken-" + now() + ".yml");
            boolean kept = file.renameTo(broken);
            profiles.clear();
            plugin.getLogger().severe("relay-rift.yml could not be read (" + ex.getMessage() + "); "
                    + (kept ? "moved to " + broken.getName() : "left in place") + ". Rift anchors start empty.");
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        long n = now();
        profiles.forEach((id, pr) -> {
            if (pr.anchors[0] == null && pr.anchors[1] == null && pr.cooldownUntil <= n) return;
            ConfigurationSection s = y.createSection(id.toString());
            if (pr.cooldownUntil > n) s.set("cooldown-until", pr.cooldownUntil);
            for (int i = 0; i < 2; i++) {
                Anchor a = pr.anchors[i];
                if (a == null) continue;
                ConfigurationSection as = s.createSection("anchors." + NAMES[i].toLowerCase());
                as.set("world", a.world());
                as.set("x", a.x());
                as.set("y", a.y());
                as.set("z", a.z());
                as.set("yaw", a.yaw());
                as.set("pitch", a.pitch());
            }
        });
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
            plugin.getLogger().warning("Could not save relay-rift.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ visuals

    private ItemStack modelItem(String model) {
        return modelItems.computeIfAbsent(model, m -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.setItemModel(new NamespacedKey("blissgems", m));
            it.setItemMeta(meta);
            return it;
        }).clone();
    }

    private void dust(Location at, int count, double spreadXZ, double spreadY, Color color) {
        at.getWorld().spawnParticle(Particle.DUST, at, count, spreadXZ, spreadY, spreadXZ, 0, new Particle.DustOptions(color, 1.1f));
    }

    private void closeTear(Tear t) {
        t.startClosing();
        loose.add(t);
    }

    /**
     * One rift: a flat textured display standing on the ground (frames swapped every 2 ticks, with a
     * random stutter about once a second), a flat light-spill display on the ground, and dust
     * drifting in. At most two displays per tear.
     */
    private final class Tear {
        final Location base;
        final Vector forward, right;
        final double width, height;
        final boolean models;
        final ItemDisplay plane, light;
        final double[][] motes = new double[6][3]; // angle, start distance, age
        double open = 1, tall = 1;
        int age, life = -1, closing = -1, frame, jolt;
        double joltScale = 1, lastWidth, lastHeight;
        long nextStutter;
        boolean dead;

        Tear(Location base, Vector forward, double width, double height, boolean withLight) {
            this.base = base.clone();
            this.forward = forward.clone();
            this.right = new Vector(-forward.getZ(), 0, forward.getX());
            this.width = width;
            this.height = height;
            this.models = cfgB("display-models", true);
            this.nextStutter = tick + 10 + random.nextInt(14);
            for (double[] m : motes) m[2] = random.nextInt(20);
            ItemDisplay planeDisplay = null, lightDisplay = null;
            if (models) {
                try {
                    planeDisplay = base.getWorld().spawn(this.base, ItemDisplay.class, d -> {
                        setup(d, "rift_0", width + 1, height + 1);
                        d.setGlowing(true);
                        d.setGlowColorOverride(glowColor());
                        d.setTransformation(planeTransform(width * 0.04, height * 0.5));
                    });
                    if (withLight) {
                        lightDisplay = base.getWorld().spawn(this.base, ItemDisplay.class, d -> {
                            setup(d, "rift_light", width * 2, 1);
                            d.setTransformation(lightTransform(0.1));
                        });
                    }
                } catch (RuntimeException ex) {
                    if (planeDisplay != null) planeDisplay.remove();
                    planeDisplay = null;
                    lightDisplay = null;
                    plugin.getLogger().warning("Rift display fallback to particles: " + ex.getMessage());
                }
            }
            this.plane = planeDisplay;
            this.light = lightDisplay;
        }

        private void setup(ItemDisplay d, String model, double cullWidth, double cullHeight) {
            d.setPersistent(false); // never saved: a crash or restart cannot leave a rift behind
            d.setInvulnerable(true);
            d.setItemStack(modelItem(model));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0);
            d.setShadowStrength(0);
            d.setDisplayWidth((float) cullWidth);
            d.setDisplayHeight((float) cullHeight);
            d.setInterpolationDuration(2);
        }

        private Transformation planeTransform(double w, double h) {
            return new Transformation(new Vector3f(0f, (float) (h / 2), 0f), new Quaternionf(),
                    new Vector3f((float) Math.max(0.01, w), (float) Math.max(0.01, h), 1f), new Quaternionf());
        }

        private Transformation lightTransform(double strength) {
            float s = (float) Math.max(0.01, strength);
            return new Transformation(new Vector3f(0f, 0.03f, 0f), new Quaternionf(),
                    new Vector3f((float) (width * 1.7 * s), 1f, (float) (3.0 * s)), new Quaternionf());
        }

        boolean alive() {
            return !dead && (!models || plane == null || plane.isValid());
        }

        void startClosing() {
            if (closing < 0) closing = 0;
        }

        void tick() {
            if (dead) return;
            age++;
            if (life >= 0) {
                open = tall = Math.min(1, 0.3 + age / 4.0);
                if (age >= life) startClosing();
            }
            if (closing >= 0) {
                closing++;
                double k = Math.max(0, 1 - closing / 5.0); // snaps shut in a quarter second
                apply(lastWidth * k, lastHeight * (0.8 + 0.2 * k), open * k);
                if (closing >= 5) {
                    World w = base.getWorld();
                    GemSounds.play(plugin, "relay.rift-close", base);
                    Location mid = base.clone().add(0, height * tall / 2, 0);
                    w.spawnParticle(Particle.DUST, mid, 18, width * 0.3, height * 0.25, width * 0.3, 0, new Particle.DustOptions(RIM, 1.3f));
                    remove();
                }
                return;
            }
            if (age % 2 != 0) return;
            frame = (frame + 1) % FRAMES;
            double jitter = 0.96 + random.nextDouble() * 0.08;
            double heightJolt = 1;
            if (tick >= nextStutter) { // a dropped frame, a small size jolt and a crackle, about once a second
                frame = (frame + 1) % FRAMES;
                joltScale = 0.9 + random.nextDouble() * 0.16;
                jolt = 2;
                nextStutter = tick + 14 + random.nextInt(16);
            }
            if (jolt > 0) { jitter = joltScale; heightJolt = 0.97 + (joltScale - 1) * 0.4; jolt--; }
            lastWidth = width * open * jitter;
            lastHeight = height * tall * heightJolt;
            if (plane != null) plane.setItemStack(modelItem("rift_" + frame));
            apply(lastWidth, lastHeight, open);
            if (cfgB("particles", true)) motes();
        }

        private void apply(double w, double h, double lightStrength) {
            if (plane != null && plane.isValid()) {
                plane.setInterpolationDelay(0);
                plane.setTransformation(planeTransform(w, h));
            }
            if (light != null && light.isValid()) {
                light.setInterpolationDelay(0);
                light.setTransformation(lightTransform(lightStrength));
            }
            if (!models || plane == null) outline(w, h); // no resource pack: draw the tear in dust
        }

        /** Dust drifting into the tear from all round it. */
        private void motes() {
            Location centre = base.clone().add(0, height * tall / 2, 0);
            World w = base.getWorld();
            for (double[] m : motes) {
                m[2] += 2;
                if (m[2] >= 20) { m[0] = random.nextDouble() * Math.PI * 2; m[1] = 1.4 + random.nextDouble() * 1.4; m[2] = 0; }
                double r = m[1] * (1 - m[2] / 20.0);
                Location at = centre.clone()
                        .add(right.clone().multiply(Math.cos(m[0]) * r))
                        .add(0, Math.sin(m[0]) * r * 1.6, 0)
                        .add(forward.clone().multiply(Math.sin(m[0] * 3) * 0.3));
                Color c = m[0] < 2 ? PALE : m[0] < 4 ? DEEP : RIM;
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(c, 0.9f));
            }
        }

        private void outline(double w, double h) {
            if (w < 0.05) return;
            World world = base.getWorld();
            Particle.DustOptions dust = new Particle.DustOptions(RIM, 0.9f);
            for (int i = 0; i <= 14; i++) {
                double t = i / 14.0, half = w / 2 * Math.pow(Math.sin(Math.PI * t), 0.75);
                for (int side = -1; side <= 1; side += 2) {
                    Location at = base.clone().add(right.clone().multiply(side * half)).add(0, t * h, 0);
                    world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
                }
            }
        }

        void remove() {
            dead = true;
            if (plane != null) plane.remove();
            if (light != null) light.remove();
        }
    }
}
