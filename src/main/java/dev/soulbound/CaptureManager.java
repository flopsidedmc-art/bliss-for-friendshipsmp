package dev.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Capture points (CAPTURE.md): a box players hold to win a prize, usually a mythic. One player on it gains time;
 * players who aren't trusted with each other contest it and nobody gains; the first to the hold time wins.
 * Points are saved in capture-points.yml. A capture that was running when the server stopped is not resumed.
 */
public final class CaptureManager implements Listener {
    static final String PERMISSION = "soulbound.capture";
    private static final int LOGIC_TICKS = 5; // the timer, boss bar and action bar update this often
    private static final String ICON = "⚑"; // ⚑

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final Map<String, Point> points = new LinkedHashMap<>(); // lower-case name -> point
    private final List<String> notices = new ArrayList<>(); // captures stopped by a restart, for the next admin who joins
    private BukkitTask task;
    private long tick;

    /** One capture point: the saved box and colour, and the state of a capture while one runs. */
    static final class Point {
        final String name;
        final String world;
        final int[] box;
        int color;
        // a running capture
        boolean running;
        long goalTicks;
        GemType reward;
        UUID startedBy;
        final Map<UUID, Long> held = new HashMap<>(); // ticks each player has held it; kept when they leave
        final Map<UUID, String> names = new HashMap<>();
        Set<UUID> on = new HashSet<>();
        CaptureMath.State state = CaptureMath.State.EMPTY;
        long lastContestSound = Long.MIN_VALUE / 2;
        BossBar bar;
        final Set<UUID> barViewers = new HashSet<>();
        final Set<UUID> ownBar = new HashSet<>(); // players we sent the timer to on its own (no gem HUD)
        // visuals
        boolean loaded;
        final List<TextDisplay> floor = new ArrayList<>();
        boolean floorBright;
        long nextPulse;
        final List<Band> bands = new ArrayList<>();
        long nextBand;
        long afterglowUntil = -1; // a win's burst keeps the visuals on until then

        Point(String name, String world, int[] box, int color) { this.name = name; this.world = world; this.box = box; this.color = color; }
    }

    /** A band of light rising from an edge and fading. */
    private static final class Band {
        final TextDisplay d;
        final long born;
        boolean rising;
        Band(TextDisplay d, long born) { this.d = d; this.born = born; }
    }

    CaptureManager(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "capture-points.yml");
    }

    // ------------------------------------------------------------------ config

    private String path(String k) { return "capture." + k; }
    private double cfgD(String k, double d) { return plugin.getConfig().getDouble(path(k), d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt(path(k), d); }
    private boolean cfgB(String k, boolean d) { return plugin.getConfig().getBoolean(path(k), d); }
    private int ticks(String k, double seconds) { return Math.max(1, (int) Math.round(cfgD(k, seconds) * 20)); }
    long defaultHoldSeconds() { return Math.max(1, cfgI("hold-seconds", 120)); }
    int[] maxSize() { return new int[] {cfgI("max-size.x", 64), cfgI("max-size.y", 32), cfgI("max-size.z", 64)}; }

    // ------------------------------------------------------------------ lifecycle

    void enable() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Plugin disable or server stop: every display and boss bar goes. A running capture stays marked as running in
     *  capture-points.yml, so the next start stops it cleanly and tells an admin. */
    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Point pt : points.values()) {
            clearVisuals(pt);
            hideBar(pt);
            pt.on.clear();
            pt.ownBar.clear();
        }
    }

    private void load() {
        points.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("points");
        if (ps != null) for (String name : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(name);
            if (s == null) continue;
            List<Integer> a = s.getIntegerList("from"), b = s.getIntegerList("to");
            Integer rgb = CaptureMath.color(s.getString("color", "gold"));
            if (a.size() != 3 || b.size() != 3 || s.getString("world") == null) {
                plugin.getLogger().warning("capture-points.yml: point '" + name + "' is incomplete, skipped.");
                continue;
            }
            int[] box = CaptureMath.box(a.get(0), a.get(1), a.get(2), b.get(0), b.get(1), b.get(2));
            points.put(name.toLowerCase(Locale.ROOT), new Point(name, s.getString("world"), box, rgb == null ? 0xFFAA00 : rgb));
        }
        ConfigurationSection run = y.getConfigurationSection("running");
        if (run != null && !run.getKeys(false).isEmpty()) {
            for (String name : run.getKeys(false)) {
                String msg = "The capture at '" + name + "' was running when the server stopped. It was stopped with no winner; "
                        + "start it again with /capture start " + name + ".";
                plugin.getLogger().warning(msg);
                notices.add(msg);
            }
            save(); // running entries are written from live state, so they're gone now
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Soulbound Gems capture points (/capture). \"running\" lists captures that were running",
                "when this was saved: after a restart they are stopped, not resumed."));
        for (Point pt : points.values()) {
            String k = "points." + pt.name;
            y.set(k + ".world", pt.world);
            y.set(k + ".from", List.of(pt.box[0], pt.box[1], pt.box[2]));
            y.set(k + ".to", List.of(pt.box[3], pt.box[4], pt.box[5]));
            y.set(k + ".color", CaptureMath.hex(pt.color));
            if (pt.running) y.set("running." + pt.name, pt.startedBy == null ? "console" : pt.startedBy.toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save capture-points.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the commands' side

    Point get(String name) { return name == null ? null : points.get(name.toLowerCase(Locale.ROOT)); }
    Collection<Point> all() { return points.values(); }

    /** null when made, otherwise why not. */
    String create(String name, World w, int[] box, int rgb) {
        if (!CaptureMath.validName(name)) return "Names are letters, digits, - and _ (up to 32).";
        if (get(name) != null) return "A capture point called '" + name + "' already exists.";
        int[] max = maxSize(), s = CaptureMath.size(box);
        if (!CaptureMath.fits(box, max[0], max[1], max[2]))
            return "That box is " + s[0] + "x" + s[1] + "x" + s[2] + "; the most is " + max[0] + "x" + max[1] + "x" + max[2] + " (capture.max-size).";
        if (box[1] < w.getMinHeight() || box[4] >= w.getMaxHeight()) return "That box goes outside the world's height.";
        points.put(name.toLowerCase(Locale.ROOT), new Point(name, w.getName(), box, rgb));
        save();
        return null;
    }

    boolean setColor(String name, int rgb) {
        Point pt = get(name);
        if (pt == null) return false;
        pt.color = rgb;
        for (TextDisplay d : pt.floor) {
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);
            d.setBackgroundColor(argb(rgb, pt.floorBright ? cfgD("floor.alpha-max", 0.55) : cfgD("floor.alpha-min", 0.35)));
        }
        pt.nextPulse = tick + 12; // let the colour change finish first
        if (pt.bar != null) updateBar(pt);
        save();
        return true;
    }

    /** Where a mythic of this type already is (online players, their ender chests, dropped items), or null. */
    String mythicWhere(GemType type) {
        GemManager gems = plugin.gems();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents())
                if (gems.isGem(it) && gems.getType(it) == type) return "in " + p.getName() + "'s inventory";
            for (ItemStack it : p.getEnderChest().getContents())
                if (gems.isGem(it) && gems.getType(it) == type) return "in " + p.getName() + "'s ender chest";
        }
        for (World w : Bukkit.getWorlds())
            for (Item item : w.getEntitiesByClass(Item.class)) {
                ItemStack it = item.getItemStack();
                if (gems.isGem(it) && gems.getType(it) == type) {
                    Location l = item.getLocation();
                    return "dropped at " + w.getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
                }
            }
        return null;
    }

    /** null when started, otherwise why not. */
    String start(String name, long holdSeconds, GemType reward, UUID by, boolean force) {
        Point pt = get(name);
        if (pt == null) return "There's no capture point called '" + name + "'.";
        if (pt.running) return "'" + pt.name + "' is already running.";
        if (Bukkit.getWorld(pt.world) == null) return "Its world (" + pt.world + ") isn't loaded.";
        if (reward != null && !force) {
            String where = mythicWhere(reward);
            if (where != null) return "The " + reward.display + " gem already exists (" + where + "). Add \"force\" at the end to start anyway.";
        }
        pt.running = true;
        pt.goalTicks = Math.max(1, holdSeconds) * 20L;
        pt.reward = reward;
        pt.startedBy = by;
        pt.held.clear();
        pt.names.clear();
        pt.on = new HashSet<>();
        pt.state = CaptureMath.State.EMPTY;
        pt.afterglowUntil = -1;
        pt.nextBand = tick;
        pt.bar = BossBar.bossBar(Component.empty(), 0f, bossColor(pt.color), BossBar.Overlay.PROGRESS);
        updateBar(pt);
        save();
        return null;
    }

    /** Ends a capture with no winner. */
    boolean stop(String name) {
        Point pt = get(name);
        if (pt == null || !pt.running) return false;
        endRun(pt);
        pt.afterglowUntil = -1;
        clearBands(pt);
        if (!cfgB("glow-when-idle", false)) clearFloor(pt);
        return true;
    }

    boolean remove(String name) {
        Point pt = get(name);
        if (pt == null) return false;
        if (pt.running) endRun(pt);
        clearVisuals(pt);
        points.remove(name.toLowerCase(Locale.ROOT));
        save();
        return true;
    }

    /** Standing spot in the middle of the box, on its floor. */
    Location centre(Point pt) {
        World w = Bukkit.getWorld(pt.world);
        if (w == null) return null;
        double cx = (pt.box[0] + pt.box[3] + 1) / 2.0, cz = (pt.box[2] + pt.box[5] + 1) / 2.0;
        return new Location(w, cx, floorAt(w, (int) Math.floor(cx), (int) Math.floor(cz), pt.box), cz);
    }

    /** The leader of a running capture (most time held, on the point or not), or null. */
    UUID leader(Point pt) {
        UUID best = null;
        long t = 0;
        for (Map.Entry<UUID, Long> e : pt.held.entrySet()) if (e.getValue() > t) { t = e.getValue(); best = e.getKey(); }
        return best;
    }

    // ------------------------------------------------------------------ hooks for other features

    /** The running capture point at this spot, for Rifts: they can't anchor there, open there or arrive there. */
    String riftBlocked(Location l) {
        if (l == null || l.getWorld() == null) return null;
        for (Point pt : points.values())
            if (pt.running && pt.world.equals(l.getWorld().getName()) && CaptureMath.inside(pt.box, l.getX(), l.getY(), l.getZ())) return pt.name;
        return null;
    }

    /** Added to the end of the gem, mythic and Revenant action bars while you're on a running point: your own time. */
    String hudSuffix(Player p) {
        Point pt = pointOf(p);
        if (pt == null) return "";
        String s = "  §8| §6" + ICON + " §f" + secs(pt.held.getOrDefault(p.getUniqueId(), 0L)) + "§7/" + secs(pt.goalTicks);
        return pt.state == CaptureMath.State.CONTESTED ? s + " §cCONTESTED" : s;
    }

    private Point pointOf(Player p) {
        for (Point pt : points.values()) if (pt.running && pt.on.contains(p.getUniqueId())) return pt;
        return null;
    }

    // ------------------------------------------------------------------ the tick

    private void tick() {
        tick++;
        for (Point pt : points.values()) {
            World w = Bukkit.getWorld(pt.world);
            if (w == null) { clearVisuals(pt); continue; }
            if (tick % 20 == 1) pt.loaded = areaLoaded(w, pt.box);
            boolean glow = pt.loaded && (pt.running || pt.afterglowUntil > tick || cfgB("glow-when-idle", false));
            if (glow && pt.floor.isEmpty()) spawnFloor(w, pt);
            else if (!glow && !pt.floor.isEmpty()) clearFloor(pt);
            if (!pt.floor.isEmpty() && tick >= pt.nextPulse) pulse(pt);
            tickBands(pt);
            if (pt.running && pt.loaded) {
                if (tick >= pt.nextBand) {
                    pt.nextBand = tick + ticks("bands.interval-seconds", 0.4);
                    if (pt.bands.size() < cfgI("bands.max", 24)) spawnBand(w, pt, randomEdge(pt));
                }
                if (cfgB("particles", false) && tick % 10 == 0) edgeDust(w, pt);
            }
            if (pt.running && tick % LOGIC_TICKS == 0) logic(w, pt);
        }
    }

    private void logic(World w, Point pt) {
        List<Player> on = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (canCapture(p) && CaptureMath.inside(pt.box, l.getX(), l.getY(), l.getZ())) on.add(p);
        }
        Set<UUID> ids = new HashSet<>();
        List<UUID> order = new ArrayList<>();
        for (Player p : on) {
            UUID id = p.getUniqueId();
            ids.add(id);
            order.add(id);
            pt.names.put(id, p.getName());
            if (!pt.on.contains(id)) GemSounds.playTo(plugin, "capture.step-on", p);
        }
        for (UUID id : pt.on) if (!ids.contains(id)) clearOwnBar(pt, id);
        pt.on = ids;
        CaptureMath.Step step = CaptureMath.step(order, this::friends, pt.held, LOGIC_TICKS, pt.goalTicks);
        pt.state = step.state();
        if (pt.state == CaptureMath.State.CONTESTED && tick - pt.lastContestSound >= 40) {
            pt.lastContestSound = tick;
            for (Player p : on) GemSounds.playTo(plugin, "capture.contested", p);
        }
        updateBar(pt);
        updateBarViewers(w, pt);
        for (Player p : on) {
            if (hasOwnHud(p)) { pt.ownBar.remove(p.getUniqueId()); continue; } // that HUD shows hudSuffix
            p.sendActionBar(ownBar(pt, p));
            pt.ownBar.add(p.getUniqueId());
        }
        if (step.winner() != null) win(w, pt, step.winner());
    }

    /** Alive, in survival or adventure, not vanished, not the Revenant. */
    private boolean canCapture(Player p) {
        if (p.isDead() || !p.isValid()) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return false; // vanish plugins set this
        return plugin.revenant() == null || !plugin.revenant().isHolder(p);
    }

    /** Same side: either one trusts the other (the rule the gems use for friend or foe). */
    private boolean friends(UUID a, UUID b) {
        Player pa = Bukkit.getPlayer(a), pb = Bukkit.getPlayer(b);
        if (pa == null || pb == null) return false;
        return plugin.listener().isTrusted(pa, pb) || plugin.listener().isTrusted(pb, pa);
    }

    /** Holding a gem (main or off hand) or the Revenant: that action bar is already showing, with hudSuffix on it. */
    private boolean hasOwnHud(Player p) {
        GemManager gems = plugin.gems();
        if (gems.isGem(p.getInventory().getItemInMainHand()) || gems.isGem(p.getInventory().getItemInOffHand())) return true;
        return plugin.revenant() != null && plugin.revenant().hudActive(p);
    }

    private Component ownBar(Point pt, Player p) {
        Component c = Component.text(ICON + " ", TextColor.color(pt.color))
                .append(Component.text(secs(pt.held.getOrDefault(p.getUniqueId(), 0L)), NamedTextColor.WHITE))
                .append(Component.text("/" + secs(pt.goalTicks), NamedTextColor.GRAY));
        return pt.state == CaptureMath.State.CONTESTED ? c.append(Component.text("  CONTESTED", NamedTextColor.RED, TextDecoration.BOLD)) : c;
    }

    private void clearOwnBar(Point pt, UUID id) {
        if (!pt.ownBar.remove(id)) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null && !hasOwnHud(p)) p.sendActionBar(Component.empty());
    }

    private void win(World w, Point pt, UUID winnerId) {
        Player winner = Bukkit.getPlayer(winnerId);
        String who = pt.names.getOrDefault(winnerId, winner == null ? "Someone" : winner.getName());
        GemType reward = pt.reward;
        endRun(pt);
        if (winner != null && reward != null) {
            ItemStack gem = plugin.gems().createMythic(reward);
            for (ItemStack left : winner.getInventory().addItem(gem).values()) winner.getWorld().dropItemNaturally(winner.getLocation(), left);
        }
        TextColor col = TextColor.color(pt.color);
        Component title = Component.text(who, col, TextDecoration.BOLD);
        Component sub = Component.text("captured " + pt.name + (reward == null ? "" : " and won the " + reward.display + " gem"), NamedTextColor.WHITE);
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
        Bukkit.broadcast(Component.text(ICON + " ", col).append(Component.text(who, col, TextDecoration.BOLD))
                .append(Component.text(" captured " + pt.name + (reward == null ? "!" : " and won the " + reward.display + " gem!"), NamedTextColor.WHITE)));
        if (winner != null) GemSounds.playTo(plugin, "capture.win", winner);
        burst(w, pt);
        pt.afterglowUntil = tick + 40; // the burst plays out, then the visuals turn off
    }

    /** A capture ends (win, stop or removal): no more timer, boss bar or own action bar. */
    private void endRun(Point pt) {
        pt.running = false;
        hideBar(pt);
        pt.bar = null;
        for (UUID id : new ArrayList<>(pt.ownBar)) clearOwnBar(pt, id);
        pt.on = new HashSet<>();
        pt.held.clear();
        pt.names.clear();
        pt.state = CaptureMath.State.EMPTY;
        pt.reward = null;
        pt.startedBy = null;
        save();
    }

    // ------------------------------------------------------------------ boss bar

    private static BossBar.Color bossColor(int rgb) { return BossBar.Color.valueOf(CaptureMath.barColor(rgb)); }

    private void updateBar(Point pt) {
        if (pt.bar == null) return;
        TextColor col = TextColor.color(pt.color);
        Component name = Component.text(pt.name + ": ", col);
        UUID lead = leader(pt);
        long t = lead == null ? 0 : pt.held.get(lead);
        if (pt.state == CaptureMath.State.CONTESTED) name = name.append(Component.text("CONTESTED", NamedTextColor.RED, TextDecoration.BOLD));
        else name = name.append(Component.text((lead == null ? "nobody" : pt.names.getOrDefault(lead, "?")) + " " + secs(t) + "/" + secs(pt.goalTicks), NamedTextColor.WHITE));
        pt.bar.name(name);
        pt.bar.progress((float) Math.max(0, Math.min(1, t / (double) pt.goalTicks)));
        pt.bar.color(bossColor(pt.color));
    }

    private void updateBarViewers(World w, Point pt) {
        if (pt.bar == null) return;
        double r = cfgD("bossbar-radius", 64);
        Location c = new Location(w, (pt.box[0] + pt.box[3] + 1) / 2.0, (pt.box[1] + pt.box[4] + 1) / 2.0, (pt.box[2] + pt.box[5] + 1) / 2.0);
        Set<UUID> want = new HashSet<>();
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= r * r) want.add(p.getUniqueId());
        for (Iterator<UUID> it = pt.barViewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (want.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(pt.bar);
            it.remove();
        }
        for (UUID id : want) if (pt.barViewers.add(id)) { Player p = Bukkit.getPlayer(id); if (p != null) p.showBossBar(pt.bar); }
    }

    private void hideBar(Point pt) {
        if (pt.bar != null) for (UUID id : pt.barViewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(pt.bar); }
        pt.barViewers.clear();
    }

    // ------------------------------------------------------------------ visuals

    private static Color argb(int rgb, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return Color.fromARGB(a, rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
    }

    /** All the box's chunks are loaded (only then are blocks read and displays spawned). */
    private static boolean areaLoaded(World w, int[] b) {
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++)
            for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    /** The y a player would stand at in this column of the box: on top of the highest floor block (or the box's bottom). */
    private static int floorAt(World w, int x, int z, int[] b) {
        for (int y = b[4]; y >= b[1] - 1; y--)
            if (w.getBlockAt(x, y, z).getType().isSolid() && !w.getBlockAt(x, y + 1, z).getType().isSolid()) return y + 1;
        return b[1];
    }

    private TextDisplay plate(Location at, int rgb, double alpha, Transformation pose, Display.Billboard billboard) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Component.text(" ")); // one space gives the background its 1/8 x 1/4 block box; the colour does the rest
            d.setBackgroundColor(argb(rgb, alpha));
            d.setDefaultBackground(false);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setBillboard(billboard);
            d.setTransformation(pose);
            d.setPersistent(false);
        });
    }

    /** The floor: flat coloured plates lying 0.02 above the ground, one per tile, each at its tile's highest ground. */
    private void spawnFloor(World w, Point pt) {
        clearFloor(pt);
        int[] b = pt.box, s = CaptureMath.size(b);
        Quaternionf flat = new Quaternionf().rotationX((float) (-Math.PI / 2)); // the text's face turned to the sky
        double alpha = cfgD("floor.alpha-min", 0.35);
        for (int[] t : CaptureMath.tiles(s[0], s[2], cfgI("floor.max-displays", 32))) {
            int x0 = b[0] + t[0], z0 = b[2] + t[1], tw = t[2], td = t[3];
            int y = Integer.MIN_VALUE;
            for (int[] c : new int[][] {{0, 0}, {tw - 1, 0}, {0, td - 1}, {tw - 1, td - 1}, {tw / 2, td / 2}})
                y = Math.max(y, floorAt(w, x0 + c[0], z0 + c[1], b));
            // A 1-space background is 1/8 x 1/4 block and sits 0.0125 right of centre, from the origin up: scale it to the
            // tile, centre it, then lay it flat.
            Vector3f centre = new Vector3f(-0.1f * tw, -0.5f * td, 0).rotate(flat);
            Transformation pose = new Transformation(centre, flat, new Vector3f(8f * tw, 4f * td, 1f), new Quaternionf());
            pt.floor.add(plate(new Location(w, x0 + tw / 2.0, y + 0.02, z0 + td / 2.0), pt.color, alpha, pose, Display.Billboard.FIXED));
        }
        pt.floorBright = false;
        pt.nextPulse = tick + 2; // the client needs the first state before an interpolation
    }

    /** One half of the slow breath: fade every plate to the other alpha over half the pulse. */
    private void pulse(Point pt) {
        int half = Math.max(1, (int) Math.round(cfgD("floor.pulse-seconds", 3.0) * 10));
        pt.floorBright = !pt.floorBright;
        Color c = argb(pt.color, pt.floorBright ? cfgD("floor.alpha-max", 0.55) : cfgD("floor.alpha-min", 0.35));
        for (TextDisplay d : pt.floor) {
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(half);
            d.setBackgroundColor(c);
        }
        pt.nextPulse = tick + half;
    }

    /** A random spot on the box's four edges: {x, z}. */
    private static double[] randomEdge(Point pt) {
        double w = pt.box[3] + 1 - pt.box[0], d = pt.box[5] + 1 - pt.box[2];
        return edgeAt(pt, ThreadLocalRandom.current().nextDouble() * 2 * (w + d));
    }

    /** The spot {@code r} blocks round the edge from the min corner. */
    private static double[] edgeAt(Point pt, double r) {
        double x0 = pt.box[0], z0 = pt.box[2], x1 = pt.box[3] + 1, z1 = pt.box[5] + 1, w = x1 - x0, d = z1 - z0;
        r = ((r % (2 * (w + d))) + 2 * (w + d)) % (2 * (w + d));
        if (r < w) return new double[] {x0 + r, z0};
        if (r < w + d) return new double[] {x1, z0 + (r - w)};
        if (r < 2 * w + d) return new double[] {x1 - (r - w - d), z1};
        return new double[] {x0, z1 - (r - 2 * w - d)};
    }

    /** A thin band of light standing on the edge; it starts rising and fading two ticks later (tickBands). */
    private void spawnBand(World w, Point pt, double[] xz) {
        float bw = (float) cfgD("bands.width", 0.15), bl = (float) cfgD("bands.length", 1.5);
        int bx = Math.min(pt.box[3], (int) Math.floor(xz[0])), bz = Math.min(pt.box[5], (int) Math.floor(xz[1]));
        int y = floorAt(w, bx, bz, pt.box);
        Transformation pose = new Transformation(new Vector3f(-0.1f * bw, 0, 0), new Quaternionf(), new Vector3f(8f * bw, 4f * bl, 1f), new Quaternionf());
        TextDisplay d = plate(new Location(w, xz[0], y, xz[1]), pt.color, cfgD("bands.alpha", 0.7), pose, Display.Billboard.VERTICAL);
        pt.bands.add(new Band(d, tick));
    }

    private void tickBands(Point pt) {
        int life = ticks("bands.lifetime-seconds", 2.0);
        for (Iterator<Band> it = pt.bands.iterator(); it.hasNext(); ) {
            Band b = it.next();
            long age = tick - b.born;
            if (!b.d.isValid() || age >= life + 3) { if (b.d.isValid()) b.d.remove(); it.remove(); continue; }
            if (!b.rising && age >= 2) { // set the end once; the client rises and fades it over the lifetime
                b.rising = true;
                Transformation now = b.d.getTransformation();
                Vector3f up = new Vector3f(now.getTranslation()).add(0, (float) cfgD("bands.height", 4.0), 0);
                b.d.setInterpolationDelay(0);
                b.d.setInterpolationDuration(life);
                b.d.setTransformation(new Transformation(up, now.getLeftRotation(), now.getScale(), now.getRightRotation()));
                b.d.setBackgroundColor(argb(pt.color, 0));
            }
        }
    }

    /** The win: a ring of bands all round the edge and a burst of coloured dust. */
    private void burst(World w, Point pt) {
        if (pt.loaded) {
            double per = 2.0 * (pt.box[3] + 1 - pt.box[0] + pt.box[5] + 1 - pt.box[2]);
            int n = Math.min(12, Math.max(0, cfgI("bands.max", 24) - pt.bands.size()));
            for (int i = 0; i < n; i++) spawnBand(w, pt, edgeAt(pt, per * i / n));
        }
        Location c = centre(pt);
        if (c == null) return;
        int[] s = CaptureMath.size(pt.box);
        w.spawnParticle(Particle.DUST, c.clone().add(0, 1, 0), 160, s[0] / 3.0, 1.2, s[2] / 3.0, 0, new Particle.DustOptions(Color.fromRGB(pt.color), 1.6f));
        w.spawnParticle(Particle.END_ROD, c.clone().add(0, 1, 0), 40, s[0] / 4.0, 1.0, s[2] / 4.0, 0.05);
    }

    /** Optional (capture.particles): coloured dust along the edges, for players who can't see displays well. */
    private void edgeDust(World w, Point pt) {
        double per = 2.0 * (pt.box[3] + 1 - pt.box[0] + pt.box[5] + 1 - pt.box[2]);
        int n = (int) Math.min(64, Math.max(8, per / 2));
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(pt.color), 1.1f);
        double y = (pt.floor.isEmpty() ? pt.box[1] : pt.floor.get(0).getLocation().getY()) + 0.2;
        for (int i = 0; i < n; i++) {
            double[] xz = edgeAt(pt, per * (i + ThreadLocalRandom.current().nextDouble()) / n);
            w.spawnParticle(Particle.DUST, xz[0], y, xz[1], 1, 0, 0.1, 0, 0, dust);
        }
    }

    private void clearFloor(Point pt) {
        for (TextDisplay d : pt.floor) if (d.isValid()) d.remove();
        pt.floor.clear();
    }

    private void clearBands(Point pt) {
        for (Band b : pt.bands) if (b.d.isValid()) b.d.remove();
        pt.bands.clear();
    }

    private void clearVisuals(Point pt) {
        clearFloor(pt);
        clearBands(pt);
    }

    // ------------------------------------------------------------------ events

    /** A chunk under a point unloads: its displays go now (they're never saved) and come back when it's loaded again. */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        String w = e.getWorld().getName();
        for (Point pt : points.values()) {
            if (!pt.world.equals(w) || cx < pt.box[0] >> 4 || cx > pt.box[3] >> 4 || cz < pt.box[2] >> 4 || cz > pt.box[5] >> 4) continue;
            clearVisuals(pt);
            pt.loaded = false;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Point pt : points.values()) {
            if (pt.bar != null && pt.barViewers.remove(id)) e.getPlayer().hideBossBar(pt.bar);
            pt.on.remove(id);
            pt.ownBar.remove(id);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (notices.isEmpty() || !e.getPlayer().hasPermission(PERMISSION)) return;
        for (String n : notices) e.getPlayer().sendMessage("§6" + ICON + " §e" + n);
        notices.clear();
    }

    static String secs(long ticks) { return Long.toString(ticks / 20); }
}
