package dev.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * "The Heretic's Games" (events.heretic-games; GamesTest), how the Heretic enters a season: a free-for-all tournament.
 * Players sign up (their whole inventory goes to disk, their standard gem and the Gold Gem stay), then play rounds,
 * each with a random kit and a random curse, scored 5 / 3 / 2 for the top three plus 1 per kill. Falling in a round
 * makes you a spectator inside the arena until the next one. Between rounds nothing hurts. After the last round the top
 * two fight the Blood Duel, and the winner is chosen by the Heretic. A restart cancels the tournament cleanly: every
 * inventory, game mode, gem and block goes back, nobody gets the Heretic or points.
 */
final class HereticGames implements Listener {
    static final String ADMIN = GamesKits.ADMIN, PLAY = "soulbound.hgames.play";
    static final NamespacedKey ROULETTE = new NamespacedKey("soulboundgems", "hg_roulette");
    private static final TextColor BLOOD = TextColor.color(0xB0101C);

    private final SoulboundGemsPlugin plugin;
    final GamesKits kits;
    final Random rnd = new Random();
    private final File arenaFile, liveFile, stashDir;

    // ------------------------------------------------------------------ the arena (heretic-games.yml)

    static final class Arena {
        final String name;
        String world;
        int[] box;
        final List<double[]> spawns = new ArrayList<>(); // x, y, z, yaw, pitch in the arena's world (resolved when used)
        Arena(String name) { this.name = name; }
    }

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private String current;
    private String lobbyRaw; // "world,x,y,z,yaw,pitch", resolved when used
    private boolean awarded;
    private final Map<UUID, Location[]> selection = new HashMap<>();

    // ------------------------------------------------------------------ the running tournament

    /** What a player had when they signed up: everything goes back on leave, end, logout, stop or a crash. */
    static final class Stash {
        ItemStack[] contents;
        GameMode mode;
        int level;
        float exp;
        double health;
        int food;
        float saturation;
        boolean allowFlight;
        List<PotionEffect> effects = new ArrayList<>();
        Location back;
        ItemStack realGem; // Gem Roulette: their own gem while another one sits in its slot
        int realGemSlot = -1;
        Map<String, Long> cooldowns;
    }

    private GamesMath.Phase phase = GamesMath.Phase.IDLE;
    private Arena arena;
    private final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    private final Map<UUID, Stash> stashes = new HashMap<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final List<UUID> fell = new ArrayList<>();
    private final Map<UUID, Integer> points = new HashMap<>(), kills = new HashMap<>(), roundKills = new HashMap<>();
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private int round, left, elapsed, ticks;
    private String kitId, lastKit, curseId, lastCurse;
    private HereticCurses.Curse curse;
    private GamesMath.Phase afterRest;
    private UUID duelA, duelB, winner;
    private Location liftFrom;
    private SporeFountain fountain;
    private BossBar bar;
    private BukkitTask task;
    private boolean moving; // our own teleports pass the teleport guard

    HereticGames(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.kits = new GamesKits(plugin);
        this.arenaFile = new File(plugin.getDataFolder(), "heretic-games.yml");
        this.liveFile = new File(plugin.getDataFolder(), "heretic-games-live.yml");
        this.stashDir = new File(plugin.getDataFolder(), "heretic-games-stash");
        loadArenas();
    }

    SoulboundGemsPlugin plugin() { return plugin; }

    /** "world,x,y,z,yaw,pitch": places are saved as text, so a world that isn't loaded yet never breaks a file. */
    static String text(Location l) {
        return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch();
    }

    /** The place, or null if it's unreadable or its world isn't loaded. */
    static Location place(String s) {
        if (s == null) return null;
        int c = s.indexOf(',');
        if (c <= 0) return null;
        World w = Bukkit.getWorld(s.substring(0, c));
        double[] v = nums(s.substring(c + 1));
        return w == null || v == null ? null : new Location(w, v[0], v[1], v[2], (float) v[3], (float) v[4]);
    }

    /** "x,y,z[,yaw,pitch]". */
    static double[] nums(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length < 3) return null;
        double[] v = new double[5];
        try { for (int i = 0; i < Math.min(5, p.length); i++) v[i] = Double.parseDouble(p[i].trim()); }
        catch (NumberFormatException ex) { return null; }
        return v;
    }

    private Location lobby() { return place(lobbyRaw); }

    /** The arena's spawns as places (none while its world isn't loaded). */
    private static List<Location> locs(Arena a) {
        List<Location> out = new ArrayList<>();
        World w = a == null ? null : Bukkit.getWorld(a.world);
        if (w != null) for (double[] v : a.spawns) out.add(new Location(w, v[0], v[1], v[2], (float) v[3], (float) v[4]));
        return out;
    }

    World world() { return arena == null ? null : Bukkit.getWorld(arena.world); }

    int[] box() { return arena == null ? null : arena.box; }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("events.heretic-games");
        return c != null ? c : new YamlConfiguration();
    }

    ConfigurationSection curseConfig(String id) { return cfg().getConfigurationSection("curses." + id); }

    GamesMath.Phase phase() { return phase; }

    /** Signed up for (or playing) the running tournament. */
    boolean playing(Player p) { return p != null && phase != GamesMath.Phase.IDLE && players.contains(p.getUniqueId()); }

    /** The tournament's event-blocked-powers list for this player now: its rests block more. Null if not playing. */
    String blockList(Player p) {
        if (!playing(p)) return null;
        return phase.resting() ? "heretic-games-rest" : "heretic-games";
    }

    /** On enable: a tournament a restart or crash cut off is cancelled (whoever is online gets everything back now). */
    void start() {
        if (liveFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(liveFile);
            if (y.getBoolean("running", false)) plugin.getLogger().info("The Heretic's Games were cut off by a restart: cancelled, every player gets their things back.");
            if (!liveFile.delete()) plugin.getLogger().warning("Couldn't remove heretic-games-live.yml.");
        }
        for (Player p : Bukkit.getOnlinePlayers()) { if (stashFile(p.getUniqueId()).exists()) restore(p, true); else cleanUp(p); } // a reload
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        if (phase != GamesMath.Phase.IDLE) cancel("the server is stopping");
        if (task != null) task.cancel();
        task = null;
        kits.shutdown();
    }

    // ------------------------------------------------------------------ setup

    private void loadArenas() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arenaFile);
        current = y.getString("current");
        awarded = y.getBoolean("awarded", false);
        lobbyRaw = y.getString("lobby");
        ConfigurationSection all = y.getConfigurationSection("arenas");
        if (all != null) for (String n : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(n);
            if (s == null) continue;
            Arena a = new Arena(n);
            a.world = s.getString("world");
            List<Integer> b = s.getIntegerList("box");
            if (b.size() == 6) a.box = GamesMath.box(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5));
            for (String sp : s.getStringList("spawns")) { double[] v = nums(sp); if (v != null) a.spawns.add(v); }
            arenas.put(n, a);
        }
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("current", current);
        y.set("awarded", awarded);
        y.set("lobby", lobbyRaw);
        for (Arena a : arenas.values()) {
            String k = "arenas." + a.name + ".";
            y.set(k + "world", a.world);
            if (a.box != null) y.set(k + "box", List.of(a.box[0], a.box[1], a.box[2], a.box[3], a.box[4], a.box[5]));
            List<String> sp = new ArrayList<>();
            for (double[] v : a.spawns) sp.add(v[0] + "," + v[1] + "," + v[2] + "," + v[3] + "," + v[4]);
            y.set(k + "spawns", sp);
        }
        try { y.save(arenaFile); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save heretic-games.yml: " + ex.getMessage()); }
    }

    String pos(Player p, int which) {
        Location[] s = selection.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        s[which] = p.getLocation().getBlock().getLocation();
        return null;
    }

    String setArena(Player p, String name) {
        if (phase != GamesMath.Phase.IDLE) return "Not while a tournament is on.";
        Location[] s = selection.get(p.getUniqueId());
        if (s == null || s[0] == null || s[1] == null) return "Set /hgames pos1 and pos2 first.";
        if (s[0].getWorld() != s[1].getWorld()) return "pos1 and pos2 are in different worlds.";
        Arena a = arenas.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), Arena::new);
        if (a.world != null && !a.world.equals(s[0].getWorld().getName())) a.spawns.clear();
        a.world = s[0].getWorld().getName();
        a.box = GamesMath.box(s[0].getBlockX(), s[0].getBlockY(), s[0].getBlockZ(), s[1].getBlockX(), s[1].getBlockY(), s[1].getBlockZ());
        a.spawns.removeIf(v -> !GamesMath.inside(v[0], v[1], v[2], a.box, 0));
        current = a.name;
        saveArenas();
        return null;
    }

    String useArena(String name) {
        if (phase != GamesMath.Phase.IDLE) return "Not while a tournament is on.";
        if (!arenas.containsKey(name.toLowerCase(java.util.Locale.ROOT))) return "No arena called " + name + ".";
        current = name.toLowerCase(java.util.Locale.ROOT);
        saveArenas();
        return null;
    }

    List<String> arenaNames() { return new ArrayList<>(arenas.keySet()); }

    String addSpawn(Player p) {
        Arena a = arenas.get(current);
        if (a == null) return "Make an arena first (/hgames arena set <name>).";
        Location l = p.getLocation();
        if (!l.getWorld().getName().equals(a.world) || !GamesMath.inside(l.getX(), l.getY(), l.getZ(), a.box, 0)) return "Stand inside the arena " + a.name + ".";
        a.spawns.add(new double[] {l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()});
        saveArenas();
        return null;
    }

    String clearSpawns() {
        Arena a = arenas.get(current);
        if (a == null) return "There's no arena yet.";
        if (phase != GamesMath.Phase.IDLE) return "Not while a tournament is on.";
        a.spawns.clear();
        saveArenas();
        return null;
    }

    String setLobby(Player p) {
        lobbyRaw = text(p.getLocation());
        saveArenas();
        return null;
    }

    /** Why the current arena can't host a tournament, or null. */
    private String notReady() {
        Arena a = arenas.get(current);
        if (a == null || a.box == null) return "There's no arena (/hgames pos1, pos2, arena set <name>).";
        if (Bukkit.getWorld(a.world) == null) return "The arena's world " + a.world + " isn't loaded.";
        if (a.spawns.size() < 8) return "The arena needs at least 8 spawns (/hgames spawn add), it has " + a.spawns.size() + ".";
        if (lobby() == null) return lobbyRaw == null ? "Set the lobby first (/hgames lobby set)." : "The lobby's world isn't loaded.";
        if (kits.enabledIds().isEmpty()) return "Every kit is turned off (/hgames kits).";
        return null;
    }

    List<String> status() {
        List<String> out = new ArrayList<>();
        Arena a = arenas.get(current);
        out.add("§4" + SmallCaps.of("The Heretic's Games") + "§7: " + phase.name().toLowerCase(java.util.Locale.ROOT)
                + (phase.fighting() || phase == GamesMath.Phase.TITLE ? " (round " + round + "/" + rounds() + ")" : "")
                + ", " + players.size() + " signed up" + (awarded ? "§7, a Heretic was already given (start needs 'force')" : ""));
        out.add("§7Arena: " + (a == null ? "§cnone" : a.name + " in " + a.world + ", " + a.spawns.size() + " spawns") + "§7, lobby: " + (lobbyRaw == null ? "§cnot set" : lobby() == null ? "§cits world isn't loaded" : "set"));
        String why = notReady();
        if (why != null) out.add("§c" + why);
        if (!players.isEmpty()) {
            List<UUID> s = GamesMath.standings(new ArrayList<>(players), points, kills, new Random(0));
            StringBuilder b = new StringBuilder("§7");
            for (int i = 0; i < s.size(); i++) b.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(name(s.get(i))).append(" ").append(points.getOrDefault(s.get(i), 0));
            out.add(b.toString());
        }
        return out;
    }

    private int rounds() { return Math.max(1, cfg().getInt("rounds", 5)); }

    // ------------------------------------------------------------------ signing up

    String open() {
        if (phase != GamesMath.Phase.IDLE) return "A tournament is already open or running.";
        String why = notReady();
        if (why != null) return why;
        arena = arenas.get(current);
        phase = GamesMath.Phase.OPEN;
        round = 0;
        lastKit = lastCurse = null;
        writeLive();
        bar = BossBar.bossBar(Component.text(SmallCaps.of("The Heretic's Games")), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        Bukkit.broadcast(Component.text(SmallCaps.of("The Heretic's Games are open"), BLOOD)
                .append(Component.text("  /hgames join", NamedTextColor.GRAY)));
        for (Player o : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "heretic-games.open", o);
        return null;
    }

    String join(Player p) {
        if (phase != GamesMath.Phase.OPEN) return phase == GamesMath.Phase.IDLE ? "The Heretic's Games aren't open." : "The tournament has already started.";
        if (players.contains(p.getUniqueId())) return "You're already signed up.";
        if (stashFile(p.getUniqueId()).exists()) return "Your things from the last tournament are still being put back; rejoin the server first.";
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return "Join in survival.";
        if (p.isDead()) return "Not while you're dead.";
        if (plugin.pantheon() != null && plugin.pantheon().inEvent(p) || plugin.isInSoulboundEvent(p)) return "You're in another event.";
        for (ItemStack it : p.getInventory().getContents()) if (HaloHunt.isFragment(it)) return "You can't bring a Halo fragment.";
        if (!stash(p)) return "Couldn't save your inventory, so you can't join (see the console).";
        players.add(p.getUniqueId());
        names.put(p.getUniqueId(), p.getName());
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (c[i] != null && !kept(c[i])) inv.setItem(i, null); // mythics included: on disk
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        refresh(p);
        p.setGameMode(GameMode.ADVENTURE);
        move(p, lobby());
        bar(p, true);
        writeLive();
        for (UUID id : players) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) o.sendMessage(ChatColor.DARK_RED + plugin.shownName(p) + ChatColor.GRAY + " joined the Heretic's Games (" + players.size() + ").");
        }
        GemSounds.playTo(plugin, "heretic-games.open", p);
        return null;
    }

    /** What stays with you in the tournament: your standard gem and the Gold Gem. Everything else (mythics too) is stashed. */
    private boolean kept(ItemStack it) { return plugin.gems().isGem(it) && !plugin.gems().isMythic(it); }

    private boolean standardGem(ItemStack it) {
        return plugin.gems().isGem(it) && !plugin.gems().isMythic(it) && !plugin.gems().isGold(it) && !isRoulette(it);
    }

    String leave(Player p) {
        if (!players.contains(p.getUniqueId())) return "You're not in the Heretic's Games.";
        drop(p, "left");
        return null;
    }

    /** Out of the tournament (left, logged out): everything back, and the tournament carries on if it can. */
    private void drop(Player p, String why) {
        UUID id = p.getUniqueId();
        boolean wasAlive = alive.remove(id);
        if (wasAlive && phase.fighting()) {
            fell.add(id);
            credit(id); // a combat logger: the last hit counts as the kill
        }
        players.remove(id);
        frozen.remove(id);
        if (curse != null) curse.off(this, p);
        restore(p, true);
        bar(p, false);
        writeLive();
        for (UUID o : players) {
            Player q = Bukkit.getPlayer(o);
            if (q != null) q.sendMessage(ChatColor.GRAY + names.getOrDefault(id, p.getName()) + " " + why + " the Heretic's Games.");
        }
        if (phase == GamesMath.Phase.OPEN) return;
        if (phase == GamesMath.Phase.DUEL || phase == GamesMath.Phase.DUEL_TITLE) {
            if (id.equals(duelA) || id.equals(duelB)) { // a duelist who leaves forfeits
                UUID other = id.equals(duelA) ? duelB : duelA;
                if (other != null && players.contains(other)) { finish(other, id); return; }
            }
        }
        if (phase != GamesMath.Phase.FINISH && players.size() < 2) cancel("too few players are left");
    }

    // ------------------------------------------------------------------ the stash (heretic-games-stash/<uuid>.yml)

    private File stashFile(UUID id) { return new File(stashDir, id + ".yml"); }

    private boolean stash(Player p) {
        Stash s = new Stash();
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        s.contents = new ItemStack[c.length];
        for (int i = 0; i < c.length; i++) s.contents[i] = c[i] == null || kept(c[i]) ? null : c[i].clone();
        s.mode = p.getGameMode();
        s.level = p.getLevel();
        s.exp = p.getExp();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.allowFlight = p.getAllowFlight();
        s.effects.addAll(p.getActivePotionEffects());
        s.back = p.getLocation().clone();
        stashes.put(p.getUniqueId(), s);
        return saveStash(p.getUniqueId(), s);
    }

    private boolean saveStash(UUID id, Stash s) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("size", s.contents.length);
        for (int i = 0; i < s.contents.length; i++) if (s.contents[i] != null) y.set("items." + i, s.contents[i]);
        y.set("mode", s.mode.name());
        y.set("level", s.level);
        y.set("exp", (double) s.exp);
        y.set("health", s.health);
        y.set("food", s.food);
        y.set("saturation", (double) s.saturation);
        y.set("allow-flight", s.allowFlight);
        y.set("effects", new ArrayList<>(s.effects));
        y.set("back", s.back == null ? null : text(s.back));
        y.set("real-gem", s.realGem);
        y.set("real-gem-slot", s.realGemSlot);
        if (s.cooldowns != null) for (Map.Entry<String, Long> e : s.cooldowns.entrySet()) y.set("cooldowns." + e.getKey(), e.getValue());
        try {
            if (!stashDir.exists() && !stashDir.mkdirs()) throw new IOException("can't make " + stashDir);
            y.save(stashFile(id));
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save the Heretic's Games stash of " + id + ": " + ex.getMessage());
            return false;
        }
    }

    private Stash loadStash(UUID id) {
        File f = stashFile(id);
        if (!f.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Stash s = new Stash();
        s.contents = new ItemStack[Math.max(41, y.getInt("size", 41))];
        ConfigurationSection items = y.getConfigurationSection("items");
        if (items != null) for (String k : items.getKeys(false)) {
            try { int i = Integer.parseInt(k); if (i >= 0 && i < s.contents.length) s.contents[i] = items.getItemStack(k); }
            catch (RuntimeException ex) { plugin.getLogger().warning("heretic-games-stash/" + id + ".yml: slot " + k + " (" + ex.getMessage() + ")"); }
        }
        try { s.mode = GameMode.valueOf(y.getString("mode", "SURVIVAL")); } catch (IllegalArgumentException ex) { s.mode = GameMode.SURVIVAL; }
        s.level = y.getInt("level");
        s.exp = (float) y.getDouble("exp");
        s.health = y.getDouble("health", 20);
        s.food = y.getInt("food", 20);
        s.saturation = (float) y.getDouble("saturation", 5);
        s.allowFlight = y.getBoolean("allow-flight", false);
        for (Object o : y.getList("effects", List.of())) if (o instanceof PotionEffect pe) s.effects.add(pe);
        s.back = place(y.getString("back"));
        s.realGem = y.getItemStack("real-gem");
        s.realGemSlot = y.getInt("real-gem-slot", -1);
        ConfigurationSection cd = y.getConfigurationSection("cooldowns");
        if (cd != null) { s.cooldowns = new HashMap<>(); for (String k : cd.getKeys(false)) s.cooldowns.put(k, cd.getLong(k)); }
        return s;
    }

    /**
     * Everything back as it was at sign-up: kit items and roulette gems out, their own gem back (with its cooldowns),
     * the stashed inventory into its slots, game mode, health, food, XP and effects; then the lobby (or where they were).
     */
    private void restore(Player p, boolean teleport) {
        UUID id = p.getUniqueId();
        Stash s = stashes.remove(id);
        if (s == null) s = loadStash(id);
        cleanUp(p);
        if (s == null) return;
        PlayerInventory inv = p.getInventory();
        Map<Integer, ItemStack> leftovers = new LinkedHashMap<>();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (c[i] != null && !c[i].getType().isAir()) leftovers.put(i, c[i]); // their gems
        if (s.realGem != null) {
            leftovers.put(s.realGemSlot >= 0 && !leftovers.containsKey(s.realGemSlot) ? s.realGemSlot : 1000, s.realGem);
            if (s.cooldowns != null && plugin.listener() != null) plugin.listener().restoreCooldowns(id, s.cooldowns);
        }
        inv.clear();
        for (int i = 0; i < s.contents.length && i < inv.getSize(); i++) if (s.contents[i] != null) inv.setItem(i, s.contents[i]);
        for (Map.Entry<Integer, ItemStack> e : leftovers.entrySet()) {
            int slot = e.getKey();
            if (slot < inv.getSize() && (inv.getItem(slot) == null || inv.getItem(slot).getType().isAir())) { inv.setItem(slot, e.getValue()); continue; }
            for (ItemStack over : inv.addItem(e.getValue()).values()) {
                p.getWorld().dropItem(p.getLocation(), over);
                plugin.getLogger().warning("The Heretic's Games: no room for " + over.getType() + " of " + p.getName() + " on restore; dropped at their feet.");
            }
        }
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        for (PotionEffect ef : s.effects) p.addPotionEffect(ef);
        p.setGameMode(s.mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : s.mode);
        p.setAllowFlight(s.allowFlight || p.getGameMode() == GameMode.CREATIVE);
        double max = p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.max(1, Math.min(max, s.health)));
        p.setFoodLevel(s.food);
        p.setSaturation(s.saturation);
        p.setLevel(s.level);
        p.setExp(Math.max(0, Math.min(0.9999f, s.exp)));
        p.setFireTicks(0);
        p.setFallDistance(0);
        Location lb = lobby();
        Location to = lb != null ? lb : s.back;
        if (teleport && to != null) move(p, to);
        File f = stashFile(id);
        if (f.exists() && !f.delete()) plugin.getLogger().warning("Couldn't remove " + f.getName() + " (already restored).");
    }

    /** Whatever the tournament could have left on anyone: kit items, roulette gems, curse modifiers. Safe on anyone. */
    private void cleanUp(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (GamesKits.isKitItem(c[i]) || isRoulette(c[i])) inv.setItem(i, null);
        if (GamesKits.isKitItem(p.getItemOnCursor()) || isRoulette(p.getItemOnCursor())) p.setItemOnCursor(null);
        HereticCurses.clearModifiers(p);
    }

    private void writeLive() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("running", phase != GamesMath.Phase.IDLE);
        List<String> ids = new ArrayList<>();
        for (UUID id : players) ids.add(id.toString());
        y.set("players", ids);
        try { y.save(liveFile); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save heretic-games-live.yml: " + ex.getMessage()); }
    }

    // ------------------------------------------------------------------ Gem Roulette

    static boolean isRoulette(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ROULETTE, PersistentDataType.BYTE);
    }

    /** Their standard gem (never the Gold Gem) becomes another one; their own waits in the stash file with its cooldowns. */
    void roulette(Player p) {
        Stash s = stashes.get(p.getUniqueId());
        if (s == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        int slot = -1;
        for (int i = 0; i < c.length; i++) if (isRoulette(c[i])) { slot = i; break; }
        if (slot < 0) {
            for (int i = 0; i < c.length; i++) if (standardGem(c[i])) { slot = i; break; }
            if (slot < 0) return; // no standard gem (the Gold Gem alone): nothing to spin
            s.realGem = c[slot].clone();
            s.realGemSlot = slot;
            s.cooldowns = plugin.listener() == null ? new HashMap<>() : plugin.listener().cooldownSnapshot(p.getUniqueId());
            saveStash(p.getUniqueId(), s); // on disk before the gem leaves the inventory
        } else if (s.cooldowns != null && plugin.listener() != null) {
            plugin.listener().restoreCooldowns(p.getUniqueId(), s.cooldowns);
        }
        GemType now = plugin.gems().getType(c[slot]);
        List<GemType> pool = new ArrayList<>();
        for (GemType t : GemType.values()) if (!t.isMythic() && t != now) pool.add(t);
        if (pool.isEmpty()) return;
        GemType pick = pool.get(rnd.nextInt(pool.size()));
        ItemStack g = plugin.gems().createGem(pick, plugin.gems().getTier(s.realGem), plugin.gems().getEnergy(s.realGem));
        ItemMeta m = g.getItemMeta();
        m.getPersistentDataContainer().set(ROULETTE, PersistentDataType.BYTE, (byte) 1);
        g.setItemMeta(m);
        inv.setItem(slot, g);
        GemSounds.playTo(plugin, "heretic-games.roulette", p);
        p.sendActionBar(Component.text(SmallCaps.of("Gem Roulette: " + pick.name().charAt(0) + pick.name().substring(1).toLowerCase(java.util.Locale.ROOT)), BLOOD));
        plugin.holdActionBar(p, 1500L);
    }

    /** Their own gem back where the roulette gem was, and their cooldowns as they were. */
    void rouletteRestore(Player p) {
        Stash s = stashes.get(p.getUniqueId());
        if (s == null || s.realGem == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        int slot = -1;
        for (int i = 0; i < c.length; i++) if (isRoulette(c[i])) { inv.setItem(i, null); if (slot < 0) slot = i; }
        if (slot < 0) slot = s.realGemSlot;
        if (slot >= 0 && slot < inv.getSize() && (inv.getItem(slot) == null || inv.getItem(slot).getType().isAir())) inv.setItem(slot, s.realGem);
        else for (ItemStack over : inv.addItem(s.realGem).values()) p.getWorld().dropItem(p.getLocation(), over);
        if (s.cooldowns != null && plugin.listener() != null) plugin.listener().restoreCooldowns(p.getUniqueId(), s.cooldowns);
        s.realGem = null;
        s.realGemSlot = -1;
        s.cooldowns = null;
        saveStash(p.getUniqueId(), s);
    }

    // ------------------------------------------------------------------ starting

    String startGames(boolean force) {
        if (phase != GamesMath.Phase.OPEN) return phase == GamesMath.Phase.IDLE ? "Open signups first (/hgames open)." : "The tournament is already running.";
        int min = Math.max(2, cfg().getInt("min-players", 4));
        if (players.size() < min) return "At least " + min + " players have to sign up (" + players.size() + " so far).";
        String why = notReady();
        if (why != null) return why;
        if (!force) {
            String h = hereticExists();
            if (h != null) return h + " Add 'force' to start anyway.";
        }
        Bukkit.broadcast(Component.text(SmallCaps.of("The Heretic's Games begin"), BLOOD));
        title();
        return null;
    }

    /** Why there's already a Heretic: online (inventory or ender chest), lying loose, in a stash, or given out before. */
    private String hereticExists() {
        if (awarded) return "An earlier tournament already gave out the Heretic.";
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) if (isHeretic(it)) return p.getName() + " has a Heretic.";
            for (ItemStack it : p.getEnderChest().getContents()) if (isHeretic(it)) return p.getName() + " has a Heretic in their ender chest.";
        }
        for (World w : Bukkit.getWorlds()) for (Item it : w.getEntitiesByClass(Item.class)) if (isHeretic(it.getItemStack())) return "A Heretic is lying in " + w.getName() + ".";
        for (Map.Entry<UUID, Stash> e : stashes.entrySet()) for (ItemStack it : e.getValue().contents) if (isHeretic(it)) return name(e.getKey()) + " brought a Heretic (it's in their stash).";
        return null;
    }

    private boolean isHeretic(ItemStack it) { return plugin.gems().isMythic(it) && plugin.gems().getType(it) == GemType.HERETIC; }

    // ------------------------------------------------------------------ the phases

    private List<Player> online(Iterable<UUID> ids) {
        List<Player> out = new ArrayList<>();
        for (UUID id : ids) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    private List<String> enabledCurses() {
        List<String> out = new ArrayList<>();
        for (String id : HereticCurses.NAMES.keySet()) if (cfg().getBoolean("curses." + id + ".enabled", true)) out.add(id);
        return out;
    }

    /** The round's title: everyone at a random spawn, held there for title-seconds, the kit and curse announced. */
    private void title() {
        round++;
        kitId = GamesMath.nextKit(kits.enabledIds(), lastKit, rnd);
        curseId = GamesMath.nextCurse(enabledCurses(), lastCurse, rnd);
        lastKit = kitId;
        lastCurse = curseId;
        curse = HereticCurses.make(curseId);
        phase = GamesMath.Phase.TITLE;
        left = Math.max(1, cfg().getInt("title-seconds", 5));
        List<Player> ps = online(players);
        List<Location> spots = spawns(ps.size());
        GamesKits.Kit kit = kits.get(kitId);
        String kitName = curse != null && curse.replacesKit() ? "Bow" : kit == null ? "Fists" : kit.name;
        Title t = Title.title(Component.text(SmallCaps.of("Round " + round + ": " + kitName + (curse == null ? "" : " · " + curse.name())), BLOOD),
                Component.text(curse == null ? "" : SmallCaps.of(curse.hint()), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(left), Duration.ofMillis(700)));
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            ready(p, GameMode.ADVENTURE);
            move(p, spots.get(i));
            frozen.put(p.getUniqueId(), spots.get(i));
            p.showTitle(t);
            GemSounds.playTo(plugin, "heretic-games.round", p);
        }
    }

    /** Different spawns for everyone while there are enough (then repeats), in a random order. */
    private List<Location> spawns(int n) {
        List<Location> pool = locs(arena);
        java.util.Collections.shuffle(pool, rnd);
        List<Location> out = new ArrayList<>();
        if (pool.isEmpty()) { for (int i = 0; i < n; i++) out.add(lobby()); return out; } // the arena's world went away
        for (int i = 0; i < n; i++) out.add(pool.get(i % pool.size()).clone());
        return out;
    }

    private void beginRound() {
        phase = GamesMath.Phase.ROUND;
        left = Math.max(10, cfg().getInt("round-seconds", 180));
        elapsed = 0;
        alive.clear();
        fell.clear();
        roundKills.clear();
        frozen.clear();
        List<Player> ps = online(players);
        for (Player p : ps) alive.add(p.getUniqueId());
        GamesKits.Kit kit = curse != null && curse.replacesKit() ? null : kits.get(kitId);
        for (Player p : ps) {
            kits.give(p, kit, true);
            if (curse != null) curse.afterKit(this, p);
        }
        if (curse != null) curse.begin(this, ps);
    }

    private void roundSecond() {
        elapsed++;
        left--;
        if (curse != null) curse.second(this, online(alive), elapsed);
        if (alive.size() <= 1 || left <= 0) endRound();
    }

    /** Placings, points, everything taken back; then the rest (and the next round, or the Blood Duel). */
    private void endRound() {
        if (curse != null) curse.end(this, online(players));
        Map<UUID, Double> health = new HashMap<>();
        for (Player p : online(alive)) health.put(p.getUniqueId(), p.getHealth());
        List<UUID> order = GamesMath.roundOrder(new ArrayList<>(alive), fell, health, roundKills);
        order.retainAll(players);
        List<Integer> table = cfg().getIntegerList("round-points");
        if (table.isEmpty()) table = List.of(5, 3, 2);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            int pts = GamesMath.placePoints(table, i + 1);
            if (pts > 0) points.merge(order.get(i), pts, Integer::sum);
            if (i < 3) line.append(i == 0 ? "" : "   ").append(i + 1).append(". ").append(name(order.get(i))).append(pts > 0 ? " +" + pts : "");
        }
        for (Player p : online(players)) {
            p.sendMessage(Component.text(SmallCaps.of("Round " + round) + "  ", BLOOD).append(Component.text(line.toString(), NamedTextColor.GRAY)));
            cleanUp(p);
            ready(p, GameMode.ADVENTURE);
        }
        alive.clear();
        curse = null;
        afterRest = round < rounds() ? GamesMath.Phase.TITLE : GamesMath.Phase.DUEL_TITLE;
        phase = GamesMath.Phase.INTERMISSION;
        left = Math.max(3, cfg().getInt("intermission-seconds", 15));
    }

    /** Healed, fed and cleansed, in this game mode (spectators back in the game), standing on solid ground. */
    private void ready(Player p, GameMode mode) {
        if (p.getGameMode() == GameMode.SPECTATOR && mode != GameMode.SPECTATOR) move(p, spawns(1).get(0));
        p.setGameMode(mode);
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        HereticCurses.clearModifiers(p);
        refresh(p);
    }

    private static void refresh(Player p) {
        double max = p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(max);
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setFreezeTicks(0);
    }

    private void restSecond() {
        left--;
        for (Player p : online(players)) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            refresh(p);
            for (PotionEffect ef : p.getActivePotionEffects()) if (isBad(ef)) p.removePotionEffect(ef.getType());
        }
        if (left > 0) return;
        if (afterRest == GamesMath.Phase.DUEL_TITLE) duelTitle(); else title();
    }

    private static boolean isBad(PotionEffect ef) {
        return ef.getType().getEffectCategory() == org.bukkit.potion.PotionEffectType.Category.HARMFUL;
    }

    /** The Blood Duel: the top two (ties for second by kills, then a coin flip); everyone else watches. */
    private void duelTitle() {
        List<UUID> pair = GamesMath.duelPair(new ArrayList<>(players), points, kills, rnd);
        if (pair.size() < 2) { finish(pair.isEmpty() ? null : pair.get(0), null); return; }
        duelA = pair.get(0);
        duelB = pair.get(1);
        phase = GamesMath.Phase.DUEL_TITLE;
        left = Math.max(1, cfg().getInt("title-seconds", 5));
        Location[] far = farthest();
        Title t = Title.title(Component.text(SmallCaps.of("Blood Duel"), BLOOD),
                Component.text(SmallCaps.of(name(duelA) + " vs " + name(duelB)), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(left), Duration.ofMillis(700)));
        for (Player p : online(players)) {
            boolean duelist = p.getUniqueId().equals(duelA) || p.getUniqueId().equals(duelB);
            ready(p, duelist ? GameMode.ADVENTURE : GameMode.SPECTATOR);
            if (duelist) {
                Location at = p.getUniqueId().equals(duelA) ? far[0] : far[1];
                move(p, at);
                frozen.put(p.getUniqueId(), at);
            }
            p.showTitle(t);
            GemSounds.playTo(plugin, "heretic-games.duel", p);
        }
    }

    /** The two spawns farthest apart. */
    private Location[] farthest() {
        List<Location> s = locs(arena);
        if (s.isEmpty()) return new Location[] {lobby(), lobby()};
        Location a = s.get(0), b = s.get(Math.min(1, s.size() - 1));
        double best = -1;
        for (int i = 0; i < s.size(); i++) for (int j = i + 1; j < s.size(); j++) {
            double d = s.get(i).distanceSquared(s.get(j));
            if (d > best) { best = d; a = s.get(i); b = s.get(j); }
        }
        return new Location[] {a.clone(), b.clone()};
    }

    private void beginDuel() {
        phase = GamesMath.Phase.DUEL;
        left = Math.max(10, cfg().getInt("duel-seconds", 180));
        frozen.clear();
        alive.clear();
        fell.clear();
        GamesKits.Kit kit = kits.get(cfg().getString("duel-kit", "sword"));
        if (kit == null || !kit.enabled) { List<String> on = kits.enabledIds(); kit = on.isEmpty() ? null : kits.get(on.get(rnd.nextInt(on.size()))); }
        for (UUID id : new UUID[] {duelA, duelB}) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            alive.add(id);
            kits.give(p, kit, true);
        }
    }

    private void duelSecond() {
        left--;
        if (alive.size() == 1) {
            UUID w = alive.iterator().next();
            finish(w, w.equals(duelA) ? duelB : duelA);
        } else if (alive.isEmpty()) {
            UUID w = GamesMath.duelTimeout(duelA, 0, duelB, 0, points, rnd);
            finish(w, w.equals(duelA) ? duelB : duelA);
        } else if (left <= 0) {
            Player a = Bukkit.getPlayer(duelA), b = Bukkit.getPlayer(duelB);
            UUID w = GamesMath.duelTimeout(duelA, a == null ? 0 : a.getHealth(), duelB, b == null ? 0 : b.getHealth(), points, rnd);
            finish(w, w.equals(duelA) ? duelB : duelA);
        }
    }

    /** Chosen by the Heretic: lifted three blocks in a blood fountain, a heartbeat for everyone, the title for everyone. */
    private void finish(UUID w, UUID loser) {
        if (w == null) { cancel("nobody is left to win"); return; }
        winner = w;
        duelB = loser;
        duelA = w;
        phase = GamesMath.Phase.FINISH;
        left = Math.max(3, cfg().getInt("finish-seconds", 6));
        ticks = 0;
        frozen.clear();
        alive.clear();
        for (Player p : online(players)) { cleanUp(p); ready(p, p.getUniqueId().equals(w) ? GameMode.ADVENTURE : GameMode.SPECTATOR); }
        Player wp = Bukkit.getPlayer(w);
        liftFrom = wp == null ? null : wp.getLocation().clone();
        if (liftFrom != null) fountain = SporeFountain.start(plugin, liftFrom, 3.0, System.currentTimeMillis() + left * 1000L);
        Component who = wp == null ? Component.text(name(w)) : plugin.shownNameComponent(wp);
        Title t = Title.title(who.color(BLOOD).append(Component.text(" " + SmallCaps.of("has been chosen by the Heretic"), BLOOD)), Component.empty(),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(left), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) o.showTitle(t);
    }

    private void finishTick() {
        ticks++;
        Player wp = Bukkit.getPlayer(winner);
        if (wp != null && liftFrom != null) { // up three blocks over two seconds, then held there
            double lift = cfg().getDouble("finish-lift", 3.0) * Math.min(1.0, ticks / 40.0);
            Location at = liftFrom.clone().add(0, lift, 0);
            at.setYaw(wp.getLocation().getYaw());
            at.setPitch(wp.getLocation().getPitch());
            if (wp.getLocation().distanceSquared(at) > 0.0025) move(wp, at);
            wp.setVelocity(new Vector());
            wp.setFallDistance(0);
        }
        if (ticks % 20 == 1) for (Player o : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "heretic-games.heartbeat", o);
        if (ticks % 20 == 0 && --left <= 0) end();
    }

    /** The end: season points, everyone's things back, the Heretic into the winner's hand, everyone to the lobby. */
    private void end() {
        List<UUID> rest = new ArrayList<>(players);
        rest.remove(winner);
        rest.remove(duelB);
        List<UUID> order = GamesMath.finalOrder(winner, players.contains(duelB) ? duelB : null, GamesMath.standings(rest, points, kills, rnd));
        List<Integer> table = cfg().getIntegerList("season-points");
        if (table.isEmpty()) table = List.of(10, 6, 4);
        int finisher = cfg().getInt("season-points-finisher", 1);
        String track = cfg().getString("season-track", "heretic-games");
        boolean linked = plugin.pantheon() != null && plugin.pantheon().present();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            int sp = GamesMath.seasonPoints(table, finisher, i + 1);
            if (linked && sp > 0) plugin.pantheon().awardEventPoints(order.get(i), track, sp);
            if (i < 3) line.append(i == 0 ? "" : "   ").append(i + 1).append(". ").append(name(order.get(i)));
        }
        UUID w = winner;
        for (Player p : online(players)) { restore(p, true); bar(p, false); }
        Player wp = w == null ? null : Bukkit.getPlayer(w);
        if (wp != null) {
            ItemStack heretic = plugin.gems().createMythic(GemType.HERETIC);
            PlayerInventory inv = wp.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand.getType().isAir()) inv.setItemInMainHand(heretic);
            else if (inv.firstEmpty() >= 0) { inv.setItem(inv.firstEmpty(), hand); inv.setItemInMainHand(heretic); }
            else for (ItemStack over : inv.addItem(heretic).values()) wp.getWorld().dropItemNaturally(wp.getLocation(), over);
            awarded = true;
            saveArenas();
        }
        Bukkit.broadcast(Component.text(SmallCaps.of("The Heretic's Games are over") + "  ", BLOOD).append(Component.text(line.toString(), NamedTextColor.GRAY)));
        reset();
    }

    /** Stopped (an admin, a restart, too few players): everything back, no Heretic, no points. */
    void cancel(String why) {
        if (phase == GamesMath.Phase.IDLE) return;
        if (curse != null) curse.end(this, online(players));
        for (Player p : online(players)) { restore(p, true); bar(p, false); p.sendMessage(ChatColor.GRAY + "The Heretic's Games were stopped: " + why + ". Everything you had is back."); }
        reset();
    }

    private void reset() {
        if (fountain != null) fountain.stop();
        fountain = null;
        players.clear();
        stashes.clear();
        alive.clear();
        fell.clear();
        points.clear();
        kills.clear();
        roundKills.clear();
        frozen.clear();
        lastHitBy.clear();
        lastHitAt.clear();
        names.clear();
        curse = null;
        duelA = duelB = winner = null;
        liftFrom = null;
        arena = null;
        bar = null;
        phase = GamesMath.Phase.IDLE;
        if (liveFile.exists() && !liveFile.delete()) plugin.getLogger().warning("Couldn't remove heretic-games-live.yml.");
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        if (phase == GamesMath.Phase.IDLE) return;
        if (phase == GamesMath.Phase.FINISH) { finishTick(); return; }
        ticks++;
        if (ticks % 10 == 0) bounds();
        if (ticks % 20 != 0) return;
        switch (phase) {
            case TITLE -> { if (--left <= 0) beginRound(); }
            case ROUND -> roundSecond();
            case INTERMISSION -> restSecond();
            case DUEL_TITLE -> { if (--left <= 0) beginDuel(); }
            case DUEL -> duelSecond();
            default -> { }
        }
        updateBar();
    }

    private void updateBar() {
        if (bar == null) return;
        String text;
        float progress = 1f;
        switch (phase) {
            case OPEN -> text = "The Heretic's Games · " + players.size() + " signed up · /hgames join";
            case TITLE -> text = "Round " + round + "/" + rounds() + " · starting in " + left;
            case ROUND -> {
                text = "Round " + round + "/" + rounds() + " · " + (curse == null ? "" : curse.name() + " · ") + GamesMath.clock(left) + " · " + alive.size() + " left";
                progress = left / (float) Math.max(1, cfg().getInt("round-seconds", 180));
            }
            case INTERMISSION -> {
                List<UUID> s = GamesMath.standings(new ArrayList<>(players), points, kills, new Random(round));
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(3, s.size()); i++) b.append(i == 0 ? "" : " · ").append(i + 1).append(". ").append(name(s.get(i))).append(" ").append(points.getOrDefault(s.get(i), 0));
                text = b + " · next in " + left;
                progress = left / (float) Math.max(1, cfg().getInt("intermission-seconds", 15));
            }
            case DUEL_TITLE -> text = "Blood Duel · " + name(duelA) + " vs " + name(duelB);
            case DUEL -> {
                text = "Blood Duel · " + name(duelA) + " vs " + name(duelB) + " · " + GamesMath.clock(left);
                progress = left / (float) Math.max(1, cfg().getInt("duel-seconds", 180));
            }
            default -> text = "The Heretic's Games";
        }
        bar.name(Component.text(SmallCaps.of(text)));
        bar.progress(Math.max(0f, Math.min(1f, progress)));
    }

    private void bar(Player p, boolean show) {
        if (bar == null || p == null) return;
        if (show) p.showBossBar(bar); else p.hideBossBar(bar);
    }

    /**
     * The arena keeps its players and nobody else: anyone else inside is set down just outside it; a player outside is
     * brought back (to a spawn). While signups are open, signed-up players stay near the lobby.
     */
    private void bounds() {
        if (arena == null) return;
        World w = Bukkit.getWorld(arena.world);
        if (w == null) return;
        double lobbyRange = cfg().getDouble("lobby-radius", 16);
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (phase == GamesMath.Phase.OPEN) {
                Location lb = lobby();
                if (lb != null && (p.getWorld() != lb.getWorld() || p.getLocation().distanceSquared(lb) > lobbyRange * lobbyRange)) move(p, lb);
                continue;
            }
            Location l = p.getLocation();
            if (l.getWorld() != w || !GamesMath.inside(l.getX(), l.getY(), l.getZ(), arena.box, 0)) {
                Location back = frozen.containsKey(id) ? frozen.get(id) : spawns(1).get(0);
                move(p, back);
            }
        }
        if (phase == GamesMath.Phase.OPEN) return;
        for (Player p : w.getPlayers()) {
            if (players.contains(p.getUniqueId())) continue;
            if (p.hasPermission(ADMIN) && (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE)) continue; // an admin watching
            Location l = p.getLocation();
            if (!GamesMath.inside(l.getX(), l.getY(), l.getZ(), arena.box, 0.5)) continue;
            double[] out = GamesMath.pushOut(l.getX(), l.getZ(), arena.box, 1.5);
            int y = w.getHighestBlockYAt((int) Math.floor(out[0]), (int) Math.floor(out[1])) + 1;
            Location to = new Location(w, out[0], y, out[1], l.getYaw(), l.getPitch());
            move(p, to);
            p.sendActionBar(Component.text(SmallCaps.of("The Heretic's Games are being played here"), BLOOD));
        }
    }

    /** Our own teleports (spawns, swaps, the lobby) get past the teleport guard. */
    void move(Player p, Location to) {
        if (p == null || to == null || to.getWorld() == null) return;
        moving = true;
        try { p.teleport(to); } finally { moving = false; }
    }

    // ------------------------------------------------------------------ falling, kills

    private String name(UUID id) {
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p != null) return plugin.shownName(p);
        return names.getOrDefault(id, "?");
    }

    /** The player who gets this player's fall: the last to hit them in the last 10 seconds. */
    private UUID credit(UUID victim) {
        UUID by = lastHitBy.get(victim);
        Long at = lastHitAt.get(victim);
        if (by == null || at == null || System.currentTimeMillis() - at > 10_000 || by.equals(victim) || !players.contains(by)) return null;
        roundKills.merge(by, 1, Integer::sum);
        kills.merge(by, 1, Integer::sum);
        points.merge(by, Math.max(0, cfg().getInt("kill-points", 1)), Integer::sum);
        return by;
    }

    /** Out for the round: a spectator inside the arena until the next one. */
    private void fall(Player p) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        fell.add(id);
        UUID by = credit(id);
        if (curse != null) curse.off(this, p);
        cleanUp(p);
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        refresh(p);
        p.setGameMode(GameMode.SPECTATOR);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0xB0101C), 1.4f));
        p.showTitle(Title.title(Component.text(SmallCaps.of("You fell"), BLOOD), Component.text(SmallCaps.of(phase == GamesMath.Phase.DUEL ? "The duel is over" : "Back next round"), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        GemSounds.playTo(plugin, "heretic-games.fall", p);
        String msg = name(id) + (by == null ? " fell" : " was slain by " + name(by)) + " (" + alive.size() + " left)";
        for (Player o : online(players)) o.sendMessage(ChatColor.DARK_RED + "✕ " + ChatColor.GRAY + msg);
    }

    /** A lethal hit never kills: the player falls (a spectator for the rest of the round) with everything kept. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !playing(p)) return;
        if (!phase.fighting() || !alive.contains(p.getUniqueId())) { e.setCancelled(true); return; } // rests and spectators: nothing hurts
        if (e.getFinalDamage() < p.getHealth()) return;
        if (hasTotem(p)) return;
        e.setCancelled(true);
        fall(p);
    }

    private static boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING;
    }

    /** Players and outsiders can't hurt each other; a fallen player can't hurt anyone. Hits are remembered for kills. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player by = attacker(e.getDamager());
        Player victim = e.getEntity() instanceof Player v ? v : null;
        boolean byIn = by != null && playing(by), victimIn = victim != null && playing(victim);
        if (!byIn && !victimIn) return;
        boolean cancel = byIn && victim != null && !victimIn // a player hurting someone outside the tournament
                || victimIn && by != null && !byIn // someone outside hurting a player
                || byIn && !alive.contains(by.getUniqueId()); // a fallen player
        if (cancel) { e.setCancelled(true); return; }
        if (victimIn && byIn && !by.equals(victim)) {
            lastHitBy.put(victim.getUniqueId(), by.getUniqueId());
            lastHitAt.put(victim.getUniqueId(), System.currentTimeMillis());
            if (curse != null && phase == GamesMath.Phase.ROUND) curse.hit(this, by);
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    /** The backup for a death nothing could stop (a plugin setting health to 0): nothing drops, they fall on respawn. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!playing(p)) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!playing(p) || arena == null) return;
        Location at = spawns(1).get(0);
        if (at != null) e.setRespawnLocation(at);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !playing(p)) return;
            if (alive.contains(p.getUniqueId()) && phase.fighting()) fall(p);
            else if (!phase.fighting() && phase != GamesMath.Phase.OPEN) ready(p, GameMode.ADVENTURE);
        });
    }

    // ------------------------------------------------------------------ the curses' hooks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !playing(p) || curse == null || !curse.reverseKnockback() || phase != GamesMath.Phase.ROUND) return;
        Vector k = e.getKnockback();
        e.setKnockback(new Vector(-k.getX(), k.getY(), -k.getZ()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent e) {
        if (e.isSprinting() && playing(e.getPlayer()) && curse != null && curse.noSprint() && phase == GamesMath.Phase.ROUND) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || !playing(p) || curse == null || !curse.noRegen() || phase != GamesMath.Phase.ROUND) return;
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p) || !playing(p)) return;
        if (!phase.fighting() || curse != null && curse.noProjectiles() && phase == GamesMath.Phase.ROUND) e.setCancelled(true);
        else if (e.getEntity() instanceof org.bukkit.entity.AbstractArrow a && !(a instanceof org.bukkit.entity.Trident)) a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.CREATIVE_ONLY); // no arrows lying around
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && playing(p) && phase.resting() && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    /** The trident kit: Riptide works anywhere, not only in water or rain. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(io.papermc.paper.event.player.PlayerStopUsingItemEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (!playing(p) || !phase.fighting() || !alive.contains(p.getUniqueId()) || it == null || it.getType() != org.bukkit.Material.TRIDENT || !GamesKits.isKitItem(it)) return;
        int level = it.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.RIPTIDE);
        if (level <= 0 || e.getTicksHeldFor() < 10 || p.isInWaterOrRain()) return; // vanilla does it in water and rain
        float f = 3.0f * ((1.0f + level) / 4.0f);
        Vector push = p.getLocation().getDirection().normalize().multiply(f);
        if (p.isOnGround()) move(p, p.getLocation().add(0, 1.2, 0));
        p.setVelocity(p.getVelocity().add(push));
        p.startRiptideAttack(20, 8.0f, it);
        org.bukkit.Sound s = level >= 3 ? org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_3 : level == 2 ? org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_2 : org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_1;
        p.getWorld().playSound(p.getLocation(), s, org.bukkit.SoundCategory.PLAYERS, 1f, 1f);
    }

    // ------------------------------------------------------------------ nothing in or out

    /** Held at their spawn while the round's title shows (looking around is fine). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location spot = frozen.get(e.getPlayer().getUniqueId());
        if (spot == null || e.getTo() == null) return;
        if (Math.abs(e.getTo().getX() - spot.getX()) < 0.1 && Math.abs(e.getTo().getZ() - spot.getZ()) < 0.1 && e.getTo().getY() <= spot.getY() + 0.1) return;
        Location back = spot.clone();
        back.setYaw(e.getTo().getYaw());
        back.setPitch(e.getTo().getPitch());
        e.setTo(back);
    }

    /** No teleporting out (commands, pearls, other plugins); our own moves and moves inside the arena are fine. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (moving || !playing(e.getPlayer()) || arena == null || e.getTo() == null) return;
        Location to = e.getTo();
        if (phase == GamesMath.Phase.OPEN) {
            double r = cfg().getDouble("lobby-radius", 16);
            Location lb = lobby();
            if (lb != null && to.getWorld() == lb.getWorld() && to.distanceSquared(lb) <= r * r) return;
        } else if (to.getWorld() != null && to.getWorld().getName().equals(arena.world) && GamesMath.inside(to.getX(), to.getY(), to.getZ(), arena.box, 0)) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text(SmallCaps.of("Not during the Heretic's Games"), BLOOD));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (playing(e.getPlayer())) e.setCancelled(true); }

    /** Players pick up nothing but their own kit's arrows; nobody else ever keeps a kit item. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (e.getEntity() instanceof Player p && playing(p)) { if (!GamesKits.isKitItem(it) || !alive.contains(p.getUniqueId())) e.setCancelled(true); return; }
        if (GamesKits.isKitItem(it) || isRoulette(it)) { e.setCancelled(true); e.getItem().remove(); }
    }

    /** No chests, ender chests, furnaces or the like while playing (our menus are fine). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !playing(p)) return;
        org.bukkit.event.inventory.InventoryType t = e.getInventory().getType();
        if (t == org.bukkit.event.inventory.InventoryType.CRAFTING || t == org.bukkit.event.inventory.InventoryType.PLAYER || GemListener.pluginMenu(e.getInventory())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (playing(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (playing(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (players.contains(p.getUniqueId())) drop(p, "logged out of");
    }

    /** Back after a crash or a logout mid-tournament: everything they had comes back now. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || players.contains(p.getUniqueId())) return;
            if (stashFile(p.getUniqueId()).exists()) {
                restore(p, true);
                p.sendMessage(ChatColor.GRAY + "The Heretic's Games you were in ended while you were away. Everything you had is back.");
            } else cleanUp(p);
        }, 5L);
    }
}
