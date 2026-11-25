package dev.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Relay Gem finale.
 *
 * The merge and flight play under the finale sky (red-gold sunset, rain, screen overlay and sparkles) The sky and overlay end when the battle begins (finale.sky.during-battle: true keeps them on).
 * The turmoil build-up itself stays under the vanilla sky.
 *
 *  0. DROP: two pedestals appear at Spawn. The Auratus and Heretic holders must drop their gems on them; a placed
 *     gem is locked there and cannot be picked back up. (finale.pedestal.timeout-seconds forces it after a wait.)
 *  1. MERGE: once both are placed, the gems rise, orbit faster and collide, a brown spider web closes over
 *     them, and it bursts as the Relay Gem forms (the optional screen flash fires at that exact moment).
 *  2. FLIGHT: the new Relay Gem streaks to the chosen player (the Pantheon leader).
 *  3. BATTLE: that player fights in their own gear, temporarily maxed (Protection IV, Sharpness V, Efficiency V,
 *     Unbreaking III, Mending). The originals are saved to disk and handed back afterwards.
 *     Everyone fights for the gem. A boss bar "Relay Gem Attunement: N%" fills only while somebody holds the gem,
 *     and a border closes in on the center as it fills. At 100% the border, the kit, and the turmoil all reset.
 */
public final class Finale implements Listener {
    private enum Phase { IDLE, DROP, MERGE, FLIGHT, BATTLE }

    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
    private static final int RISE_TICKS = 50;        // the gems lift off the pedestals
    private static final int MERGE_AT = 110;         // the gems collide (screen flash)
    private static final int FORM_AT = 160;          // the web bursts and the Relay Gem forms
    private static final int MERGE_TOTAL_TICKS = 175;
    private static final int FLIGHT_TICKS = 70;

    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;
    private final NamespacedKey kitKey;
    private final NamespacedKey dropKey; // the one-time id a pedestal drop stamps on its gem
    private final PedestalDrop drops = new PedestalDrop();
    private final File centerFile;
    private final File stashDir;
    private final File borderFile;

    private Phase phase = Phase.IDLE;
    private Location center;
    private UUID target;
    private int phaseTicks;
    private int ticks;
    private double progress;
    private long lastTickMs;
    private BukkitTask task;
    /** A short borrowed sky (the Revenant's phase 2), run while the finale itself is idle. */
    private BukkitTask pulseTask;
    private boolean pulseRed;
    private boolean skyOn;
    private int skyTicks;
    private int overlayFrame;
    private int fadeLevel; // the fade-in frame shown now (0 = none yet)
    private org.bukkit.scheduler.BukkitTask overlayFadeOut; // the fade-out after the sky stops

    private Location pedAuratus;
    private Location pedHeretic;
    private boolean placedAuratus;
    private boolean placedHeretic;
    private int dropTicks;
    private int bothAt = -1;
    private ItemDisplay gemAuratus;
    private ItemDisplay gemHeretic;
    /** Between the two pedestals, on the ground: where the gems orbit, merge and the Relay forms. */
    private Location mergeBase;
    private double mergeRadius = 6, mergeAngle = Math.PI;
    private Location startAuratus, startHeretic;
    /** The golden clock seal on the ground under the merge (pack model blissgems:finale_seal). */
    private ItemDisplay seal;
    private float sealSpin;
    private int flashUntil = -1;
    private final List<org.bukkit.entity.Entity> pedestalParts = new ArrayList<>();
    private final List<ItemDisplay> displays = new ArrayList<>();
    private ItemDisplay relayFly;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    private Location origCenter;
    private double origSize;
    private double origDamage;
    private double origBuffer;
    private int origWarning;

    public Finale(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        this.kitKey = new NamespacedKey(plugin, "finale_kit");
        this.dropKey = new NamespacedKey(plugin, "finale_drop");
        this.centerFile = new File(plugin.getDataFolder(), "turmoil.yml");
        this.stashDir = new File(plugin.getDataFolder(), "finale-stash");
        this.borderFile = new File(plugin.getDataFolder(), "finale-border.yml");
        this.stashDir.mkdirs();
    }

    // ------------------------------------------------------------------ config and state

    private double cfgD(String key, double def) {
        return plugin.getConfig().getDouble("finale." + key, def);
    }

    private int cfgI(String key, int def) {
        return plugin.getConfig().getInt("finale." + key, def);
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    public String statusLine() {
        if (phase == Phase.IDLE) return "Finale: idle.";
        return "Finale: " + phase + (target != null ? " | target " + nameOf(target) : "")
                + (phase == Phase.DROP ? " | Auratus " + (placedAuratus ? "placed" : "waiting") + ", Heretic " + (placedHeretic ? "placed" : "waiting") : "")
                + (phase == Phase.BATTLE ? " | attunement " + (int) Math.floor(progress * 100) + "%" : "");
    }

    /** The finale center: set with /turmoil setspawn, otherwise the main world's spawn. */
    public Location center() {
        if (center != null) return center;
        if (centerFile.exists()) {
            Location saved = YamlConfiguration.loadConfiguration(centerFile).getLocation("center");
            if (saved != null && saved.getWorld() != null) return saved;
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void setCenter(Location loc) {
        saveSpot("center", loc);
    }

    /** Saves one location in turmoil.yml, keeping the others (the center and the two pedestal spots). */
    private void saveSpot(String key, Location loc) {
        YamlConfiguration y = centerFile.exists() ? YamlConfiguration.loadConfiguration(centerFile) : new YamlConfiguration();
        y.set(key, loc);
        try {
            y.save(centerFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save turmoil.yml: " + ex.getMessage());
        }
    }

    private Location savedSpot(String key) {
        if (!centerFile.exists()) return null;
        Location l = YamlConfiguration.loadConfiguration(centerFile).getLocation(key);
        return l != null && l.getWorld() != null ? l : null;
    }

    private static String spotKey(boolean auratus) { return auratus ? "pedestal-auratus" : "pedestal-heretic"; }

    /** /turmoil pedestal 1|2: the pedestal stands here (on the ground) in the next finale. Returns where it will be. */
    public Location setPedestal(boolean auratus, Location at) {
        Location spot = onGround(at);
        saveSpot(spotKey(auratus), spot);
        return spot;
    }

    /** /turmoil pedestal clear: both pedestals go back to Spawn +- finale.pedestal.distance. */
    public void clearPedestals() {
        saveSpot(spotKey(true), null);
        saveSpot(spotKey(false), null);
    }

    /** /turmoil pedestal: where each pedestal will stand, and whether it was set or is automatic. */
    public String pedestalInfo() {
        StringBuilder out = new StringBuilder();
        for (boolean aur : new boolean[]{true, false}) {
            Location l = pedestalLoc(aur, center == null ? center() : center);
            out.append(aur ? "Pedestal 1 (Auratus): " : " | Pedestal 2 (Heretic): ")
                    .append(l.getBlockX()).append(' ').append(l.getBlockY()).append(' ').append(l.getBlockZ())
                    .append(savedSpot(spotKey(aur)) != null ? " (set)" : " (automatic)");
        }
        return out.toString();
    }

    /** The first open block above solid ground in this column: down from the sky, or up out of the ground. */
    static Location onGround(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        int y = FinaleMath.surfaceY(l.getBlockY(), w.getMinHeight() + 1, w.getMaxHeight() - 2, yy -> {
            Block b = w.getBlockAt(x, yy, z);
            return b.getType().isSolid() && !b.isPassable();
        });
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    // ------------------------------------------------------------------ starting

    /** @return an error message for the host, or null if the finale started. */
    public String start(String targetArg, boolean force) {
        if (phase != Phase.IDLE) return "The finale is already running (/turmoil finale stop).";
        Location c = center();
        if (c.getWorld() == null) return "The finale center has no world. Run /turmoil setspawn.";

        UUID tid;
        if (targetArg.equalsIgnoreCase("auto")) {
            Object r = pantheonLeader();
            if (r instanceof String err) return err;
            tid = (UUID) r;
        } else {
            Player p = Bukkit.getPlayerExact(targetArg);
            if (p == null) return "Player not online: " + targetArg;
            tid = p.getUniqueId();
        }
        Player t = Bukkit.getPlayer(tid);
        if (t == null) return "The chosen player is offline.";

        if (!force) {
            double r = cfgD("gather-radius", 80.0);
            List<String> away = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("soulbound.turmoil")) continue;
                if (!p.getWorld().equals(c.getWorld()) || p.getLocation().distanceSquared(c) > r * r) away.add(p.getName());
            }
            if (!away.isEmpty()) {
                return "Not everyone is at Spawn yet: " + String.join(", ", away) + ". Add 'force' to start anyway.";
            }
        }

        center = c;
        target = tid;
        progress = 0.0;
        phase = Phase.DROP;
        phaseTicks = 0;
        ticks = 0;
        startDrop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "The Signal calls its champions. Drop your gems on the pedestals.");
        return null;
    }

    /** Reads the Pantheon plugin's points.yml and returns the top eligible player (UUID) or an error String. */
    private Object pantheonLeader() {
        File dir = new File(plugin.getDataFolder().getParentFile(), "Pantheon");
        File pts = new File(dir, "points.yml");
        if (!pts.exists()) return "Pantheon's points.yml was not found. Use /turmoil finale <player> instead.";
        File cfgFile = new File(dir, "config.yml");
        YamlConfiguration pc = cfgFile.exists() ? YamlConfiguration.loadConfiguration(cfgFile) : new YamlConfiguration();
        int bestOf = pc.getInt("scoring.best-of", 4);
        int minTracks = pc.getInt("scoring.min-tracks", 4);

        ConfigurationSection root = YamlConfiguration.loadConfiguration(pts).getConfigurationSection("players");
        if (root == null) return "Pantheon has no scores yet.";
        int best = -1;
        List<UUID> leaders = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            int entered = 0;
            List<Integer> values = new ArrayList<>();
            for (String track : new String[] {"DUELS", "TEAMS", "COMMAND", "MOTION", "SIGNAL"}) {
                if (s.getBoolean("entered." + track, false)) entered++;
                values.add(s.getInt("points." + track, 0));
            }
            if (entered < minTracks) continue;
            values.sort(java.util.Comparator.reverseOrder());
            int total = 0;
            for (int i = 0; i < Math.min(bestOf, values.size()); i++) total += values.get(i);
            if (total > best) {
                best = total;
                leaders.clear();
                leaders.add(id);
            } else if (total == best) {
                leaders.add(id);
            }
        }
        if (leaders.isEmpty()) return "No eligible player in Pantheon yet (needs " + minTracks + " tracks entered).";
        if (leaders.size() > 1) {
            List<String> names = new ArrayList<>();
            for (UUID id : leaders) names.add(nameOf(id));
            return "Tied for first: " + String.join(", ", names) + ". Settle it, then run /turmoil finale <player>.";
        }
        return leaders.get(0);
    }

    // ------------------------------------------------------------------ finale sky, overlay and sparkles

    private boolean cfgB(String key, boolean def) {
        return plugin.getConfig().getBoolean("finale." + key, def);
    }

    /** Reads finale.<key>, falling back to the older turmoil.<key> so existing configs keep working. */
    private long skyL(String key, long def) {
        String f = "finale." + key;
        return plugin.getConfig().isSet(f) ? plugin.getConfig().getLong(f) : plugin.getConfig().getLong("turmoil." + key, def);
    }

    private boolean skyB(String key, boolean def) {
        String f = "finale." + key;
        return plugin.getConfig().isSet(f) ? plugin.getConfig().getBoolean(f) : plugin.getConfig().getBoolean("turmoil." + key, def);
    }

    private String skyS(String key, String def) {
        String f = "finale." + key;
        return plugin.getConfig().isSet(f) ? plugin.getConfig().getString(f, def) : plugin.getConfig().getString("turmoil." + key, def);
    }

    private void startSky() {
        skyOn = true;
        skyTicks = 0;
        overlayFrame = 0;
        fadeLevel = 0;
        flashUntil = -1;
        cancelOverlayFadeOut();
        for (Player p : Bukkit.getOnlinePlayers()) applySky(p);
    }

    private void stopSky() {
        if (!skyOn) return;
        skyOn = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.resetPlayerTime();
            p.resetPlayerWeather();
        }
        // The overlay fades out through the fainter frames over fade-out-ticks, then the real helmets go back.
        int fadeOut = Math.max(0, (int) skyL("overlay.fade-out-ticks", 30L));
        int steps = fadeSteps();
        if (!skyB("overlay.enabled", true) || fadeOut <= 0 || steps <= 0) { clearOverlay(); return; }
        String style = overlayStyle(); // captured now: the Revenant's pulse fades out red
        int[] t = {0};
        int[] shownLevel = {steps + 1};
        cancelOverlayFadeOut();
        overlayFadeOut = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            int level = FinaleMath.fadeOutLevel(t[0], fadeOut, steps);
            if (level <= 0) { cancelOverlayFadeOut(); clearOverlay(); return; }
            if (level != shownLevel[0]) { shownLevel[0] = level; showOverlay(fadeTexture(style, level)); }
        }, 1L, 1L);
    }

    // The overlay: camera overlays (CameraOverlay), the pack's textures/overlay/<style>_<frame>.png. Fades step through
    // <style>_fade_1..N (N = overlay.fade-steps, 1 the faintest), because camera overlays have no alpha setting.

    private String overlayStyle() { return pulseRed || "red".equalsIgnoreCase(skyS("overlay.style", "gold")) ? "red" : "gold"; }

    private String overlayTexture() { return "blissgems:overlay/" + overlayStyle() + "_" + (overlayFrame % overlayFrames(overlayStyle())); }

    /** Frames in the pack for a style (finale.overlay.frame-count.<style>): textures/overlay/<style>_0..N-1.png. */
    private int overlayFrames(String style) { return overlayFrames(plugin, style); }

    static int overlayFrames(SoulboundGemsPlugin plugin, String style) {
        int fallback = "gold".equals(style) ? 5 : 6;
        return Math.max(1, Math.min(16, plugin.getConfig().getInt("finale.overlay.frame-count." + style, fallback)));
    }

    private static String fadeTexture(String style, int level) { return "blissgems:overlay/" + style + "_fade_" + level; }

    /** Fainter frames in the pack for fading; 0 = no fade (pop in, cut out). */
    private int fadeSteps() { return (int) Math.max(0, Math.min(8, skyL("overlay.fade-steps", 4L))); }

    /** Everyone who has the overlay on sees {@code texture}; anyone who switched it off sees their own helmet. */
    private void showOverlay(String texture) {
        CameraOverlay o = plugin.overlay();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.turmoil().overlayAllowed(p)) o.show(p, texture);
            else o.clear(p);
        }
    }

    private void clearOverlay() {
        for (Player p : Bukkit.getOnlinePlayers()) plugin.overlay().clear(p);
    }

    private void cancelOverlayFadeOut() {
        if (overlayFadeOut != null) overlayFadeOut.cancel();
        overlayFadeOut = null;
    }

    private void applySky(Player p) {
        p.setPlayerTime(skyL("sky.time", 12600L), false);
        if (skyB("sky.rain", true)) p.setPlayerWeather(WeatherType.DOWNFALL);
    }

    private void skyTick() {
        skyTicks++;
        if (skyTicks % 20 == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) applySky(p);
        }
        int frameTicks = Math.max(1, (int) skyL("overlay.frame-ticks", 4L));
        int steps = fadeSteps();
        int fadeIn = steps > 0 ? Math.max(0, (int) skyL("overlay.fade-in-ticks", 30L)) : 0;
        if (skyB("overlay.enabled", true)) {
            String texture = null;
            if (fadeIn > 0 && skyTicks <= fadeIn) { // fade in through the fainter frames, then animate
                int level = FinaleMath.fadeInLevel(skyTicks, fadeIn, steps);
                if (level != fadeLevel) { fadeLevel = level; texture = fadeTexture(overlayStyle(), level); }
            } else if (fadeIn <= 0 && skyTicks == 1) {
                texture = overlayTexture(); // no fade: the first frame at once
            } else if (skyTicks % frameTicks == 0 && skyTicks >= flashUntil) {
                overlayFrame = (overlayFrame + 1) % overlayFrames(overlayStyle()); // every frame-ticks, the next frame's helmet
                texture = overlayTexture();
            }
            if (texture != null) showOverlay(texture);
        }
        if (skyTicks % 10 == 0 && skyB("sparkles", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 6, 6.0, 3.0, 6.0, 0.01);
            }
        }
    }

    // ------------------------------------------------------------------ phase 1: merge

    private boolean isMergeGem(ItemStack it) {
        if (it == null || !gems.isGem(it) || !gems.isMythic(it)) return false;
        GemType t = gems.getType(it);
        return t == GemType.AURATUS || t == GemType.HERETIC;
    }

    private ItemDisplay spawnDisplay(Location at, ItemStack stack) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setTeleportDuration(1);
            d.setPersistent(false);
            d.setTransformation(new org.bukkit.util.Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f()));
        });
    }

    // ------------------------------------------------------------------ phase 0: the pedestals

    private static double smooth(double u) {
        u = Math.max(0.0, Math.min(1.0, u));
        return u * u * (3 - 2 * u);
    }

    private static double mix(double a, double b, double u) {
        return a + (b - a) * u;
    }

    /** Where a pedestal stands: the spot set with /turmoil pedestal, or Spawn +- finale.pedestal.distance; always on the ground. */
    private Location pedestalLoc(boolean auratus, Location around) {
        Location saved = savedSpot(spotKey(auratus));
        if (saved != null) return onGround(saved);
        double d = cfgD("pedestal.distance", 6.0);
        return onGround(around.clone().add(auratus ? -d : d, 0, 0));
    }

    private boolean customPedestals() { return cfgB("pedestal.custom-model", true); }

    /** How high above the pedestal's base a placed gem floats. */
    private double gemHeight() { return customPedestals() ? 2.35 : 1.7; }

    private ItemStack packModel(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("blissgems", model));
        it.setItemMeta(m);
        return it;
    }

    private void pedestalPart(Location at, Material m, float sx, float sy, float sz, float y) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new org.bukkit.util.Transformation(new Vector3f(-sx / 2f, y, -sz / 2f),
                    new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f()));
            e.setPersistent(false);
        });
        pedestalParts.add(d);
    }

    private void buildPedestal(Location at, boolean auratus) {
        if (customPedestals()) {
            // The pack's pedestal model (blackstone with gold or red trim and a four-prong cradle), base on the ground.
            ItemStack model = packModel(auratus ? "finale_pedestal_auratus" : "finale_pedestal_heretic");
            pedestalParts.add(at.getWorld().spawn(at.clone().add(0, 0.5, 0), ItemDisplay.class, d -> {
                d.setItemStack(model);
                d.setPersistent(false);
            }));
        } else {
            pedestalPart(at, Material.POLISHED_BLACKSTONE_BRICKS, 1.3f, 0.45f, 1.3f, 0.0f);
            pedestalPart(at, Material.CHISELED_POLISHED_BLACKSTONE, 0.8f, 0.85f, 0.8f, 0.45f);
            pedestalPart(at, auratus ? Material.GOLD_BLOCK : Material.REDSTONE_BLOCK, 1.0f, 0.18f, 1.0f, 1.3f);
        }
        TextDisplay label = at.getWorld().spawn(at.clone().add(0, gemHeight() + 0.9, 0), TextDisplay.class, d -> {
            d.text(Component.text(auratus ? "AURATUS" : "HERETIC")
                    .color(auratus ? NamedTextColor.GOLD : NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
        });
        pedestalParts.add(label);
    }

    private void startDrop() {
        drops.clear();
        dropTicks = 0;
        bothAt = -1;
        placedAuratus = false;
        placedHeretic = false;
        pedAuratus = pedestalLoc(true, center);
        pedHeretic = pedestalLoc(false, center);
        double[] f = FinaleMath.mergeFrame(pedAuratus.getX(), pedAuratus.getZ(), pedHeretic.getX(), pedHeretic.getZ());
        mergeBase = onGround(new Location(center.getWorld(), f[0], (pedAuratus.getY() + pedHeretic.getY()) / 2, f[1]));
        mergeBase.setX(f[0]);
        mergeBase.setZ(f[1]);
        mergeRadius = Math.max(1.5, f[2]);
        mergeAngle = f[3];
        buildPedestal(pedAuratus, true);
        buildPedestal(pedHeretic, false);
        playAll(Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.6f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (!isMergeGem(it)) continue;
                String name = gems.getType(it) == GemType.AURATUS ? "Auratus" : "Heretic";
                p.sendMessage(ChatColor.LIGHT_PURPLE + "The Signal calls for your " + name + " gem. Drop it on the "
                        + name + " pedestal. Once it is placed, you cannot take it back.");
            }
        }
    }

    private void removePedestals() {
        for (org.bukkit.entity.Entity en : pedestalParts) {
            if (en.isValid()) en.remove();
        }
        pedestalParts.clear();
        pedAuratus = null;
        pedHeretic = null;
        placedAuratus = false;
        placedHeretic = false;
        bothAt = -1;
        dropTicks = 0;
    }

    private void beam(Location base, boolean done, Color c) {
        if (done || base == null) return;
        World w = base.getWorld();
        Particle.DustOptions d = new Particle.DustOptions(c, 1.6f);
        for (int i = 0; i < 12; i++) {
            w.spawnParticle(Particle.DUST, base.clone().add(0, gemHeight() - 0.1 + i * 0.5, 0), 1, 0.05, 0, 0.05, 0, d);
        }
    }

    private String arrowTo(Player from, Location to) {
        if (!from.getWorld().equals(to.getWorld())) return "?";
        double dx = to.getX() - from.getLocation().getX();
        double dz = to.getZ() - from.getLocation().getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((targetYaw - from.getLocation().getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
        int idx = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
        return ARROWS[idx] + " " + (int) Math.sqrt(dx * dx + dz * dz) + "m";
    }

    private void guideHolders() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] all = inv.getContents();
            for (int i = 0; i < all.length; i++) { // a stray pedestal id (the gem changed hands that tick, say) comes off
                if (!anyStamp(all[i])) continue;
                String token = all[i].getItemMeta().getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
                if (token == null || !drops.waiting(token)) inv.setItem(i, unstamped(all[i]));
            }
            for (ItemStack it : p.getInventory().getContents()) {
                if (!isMergeGem(it)) continue;
                boolean aur = gems.getType(it) == GemType.AURATUS;
                if (aur ? placedAuratus : placedHeretic) continue;
                Location ped = aur ? pedAuratus : pedHeretic;
                p.sendActionBar(Component.text("Drop the " + (aur ? "Auratus" : "Heretic") + " gem on its pedestal  "
                        + arrowTo(p, ped)).color(aur ? NamedTextColor.GOLD : NamedTextColor.RED));
                break;
            }
        }
    }

    /** Takes one gem of this type out of an inventory or off the ground. Used by the timeout fallback. */
    private boolean forcePull(GemType type) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                if (isMergeGem(c[i]) && gems.getType(c[i]) == type) {
                    inv.setItem(i, null);
                    return true;
                }
            }
        }
        for (World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                ItemStack st = item.getItemStack();
                if (isMergeGem(st) && gems.getType(st) == type) {
                    item.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /** Locks a gem onto its pedestal. It is a display now, so nobody can pick it back up. Never twice. */
    private void place(GemType type, String who) {
        boolean aur = type == GemType.AURATUS;
        if (aur ? placedAuratus : placedHeretic) return;
        Location ped = aur ? pedAuratus : pedHeretic;
        Location top = ped.clone().add(0, gemHeight(), 0);
        ItemDisplay d = spawnDisplay(top, gems.createMythic(type));
        displays.add(d);
        if (aur) {
            placedAuratus = true;
            gemAuratus = d;
            startAuratus = top.clone();
        } else {
            placedHeretic = true;
            gemHeretic = d;
            startHeretic = top.clone();
        }
        World w = ped.getWorld();
        w.spawnParticle(Particle.END_ROD, top, 40, 0.3, 0.3, 0.3, 0.1);
        playAll(Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, aur ? 1.2f : 0.8f);
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + who + " has given up the " + (aur ? "Auratus" : "Heretic") + " gem.");
    }

    // ------------------------------------------------------------------ pedestal drops (PedestalDrop)

    private PedestalDrop.Facts facts(Player p, ItemStack st) {
        boolean merge = isMergeGem(st);
        GemType type = merge ? gems.getType(st) : null;
        boolean aur = type == GemType.AURATUS;
        Location ped = aur ? pedAuratus : pedHeretic;
        double reach = cfgD("pedestal.reach", 4.0);
        boolean near = merge && ped != null && p.getWorld().equals(ped.getWorld()) && p.getLocation().distanceSquared(ped) <= reach * reach;
        return new PedestalDrop.Facts(phase == Phase.DROP, merge, merge && (aur ? placedAuratus : placedHeretic), near);
    }

    /**
     * True while the finale alone handles this drop: a merge gem dropped near its pedestal before it's placed. The
     * "gems can't be dropped" rule checks this and stays out of it (the Auratus chain release is on F now, not Q).
     */
    public boolean claimsDrop(Player p, ItemStack st) {
        return p != null && phase == Phase.DROP && PedestalDrop.owns(facts(p, st));
    }

    /**
     * Highest priority, cancelled or not: every merge gem dropped in the drop phase is cancelled here, so the server
     * gives it back whatever the other listeners did. A drop at its pedestal is stamped first and placed next tick,
     * once exactly that gem has been taken back out (PedestalDrop).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Item drop = e.getItemDrop();
        ItemStack st = drop.getItemStack();
        GemType type = isMergeGem(st) ? gems.getType(st) : null;
        boolean couldNotReturn = p.getInventory().firstEmpty() == -1;
        PedestalDrop.Verdict v = drops.onDrop(type, facts(p, st), () -> e.setCancelled(true), () -> stamp(drop), couldNotReturn);
        if (v == PedestalDrop.Verdict.TOO_FAR) {
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Bring the " + (type == GemType.AURATUS ? "Auratus" : "Heretic") + " gem to its pedestal to place it.");
        } else if (v == PedestalDrop.Verdict.CLAIMED) {
            Bukkit.getScheduler().runTask(plugin, () -> settleDrop(p, type));
        }
    }

    /** The tick after a claimed drop: the server has given the gem back by now, so take exactly it out, then place it. */
    private void settleDrop(Player p, GemType type) {
        boolean placed = type == GemType.AURATUS ? placedAuratus : placedHeretic;
        if (!drops.settle(type, phase == Phase.DROP, placed, token -> find(p, token, true), token -> find(p, token, false))) return;
        place(type, p.getName());
        if (type == GemType.AURATUS && p.isOnline() && plugin.auratus() != null) plugin.auratus().letGo(p); // a chain that's out goes with it
    }

    /** Stamps the dropped gem with a fresh id (on the drop entity's stack, which is what the server gives back). */
    private String stamp(Item drop) {
        ItemStack st = drop.getItemStack();
        ItemMeta m = st.getItemMeta();
        if (m == null) return null;
        String token = UUID.randomUUID().toString();
        m.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, token);
        st.setItemMeta(m);
        drop.setItemStack(st);
        return token;
    }

    private boolean stamped(ItemStack it, String token) {
        return it != null && it.hasItemMeta()
                && token.equals(it.getItemMeta().getPersistentDataContainer().get(dropKey, PersistentDataType.STRING));
    }

    private boolean anyStamp(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(dropKey, PersistentDataType.STRING);
    }

    /** The same gem without its one-time id. */
    private ItemStack unstamped(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().remove(dropKey);
        it.setItemMeta(m);
        return it;
    }

    /**
     * Finds the one stack carrying {@code token}: any slot of the player's inventory (hotbar, storage, armour, off
     * hand), their cursor, the inventory they have open, or the ground of their world. {@code take}: it's removed;
     * otherwise it stays where it is and only the id comes off. True if it was found.
     */
    private boolean find(Player p, String token, boolean take) {
        if (p.isOnline()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                if (stamped(c[i], token)) { inv.setItem(i, take ? null : unstamped(c[i])); return true; }
            }
            ItemStack cursor = p.getItemOnCursor();
            if (stamped(cursor, token)) { p.setItemOnCursor(take ? null : unstamped(cursor)); return true; }
            org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
            for (int i = 0; i < top.getSize(); i++) {
                ItemStack it = top.getItem(i);
                if (stamped(it, token)) { top.setItem(i, take ? null : unstamped(it)); return true; }
            }
        }
        for (Item item : p.getWorld().getEntitiesByClass(Item.class)) {
            if (!item.isValid() || !stamped(item.getItemStack(), token)) continue;
            if (take) item.remove();
            else item.setItemStack(unstamped(item.getItemStack()));
            return true;
        }
        return false;
    }

    /** Someone who logged off while a drop of theirs was waiting: the one-time id comes off when they're back. */
    private void unstampAll(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (anyStamp(c[i])) inv.setItem(i, unstamped(c[i]));
    }

    private void dropTick() {
        dropTicks++;
        if (dropTicks % 4 == 0) {
            beam(pedAuratus, placedAuratus, Color.fromRGB(255, 196, 60));
            beam(pedHeretic, placedHeretic, Color.fromRGB(220, 36, 36));
        }
        if (dropTicks % 20 == 0) guideHolders();
        int timeout = cfgI("pedestal.timeout-seconds", 0);
        if (timeout > 0 && dropTicks >= timeout * 20 && dropTicks % 20 == 0) {
            // a gem being dropped right now is left to its drop (settleDrop), so it can't be pulled as well
            if (!placedAuratus && !drops.isPending(GemType.AURATUS) && forcePull(GemType.AURATUS)) place(GemType.AURATUS, "The Signal");
            if (!placedHeretic && !drops.isPending(GemType.HERETIC) && forcePull(GemType.HERETIC)) place(GemType.HERETIC, "The Signal");
        }
        if (placedAuratus && placedHeretic) {
            if (bothAt < 0) bothAt = dropTicks;
            if (dropTicks - bothAt >= 40) beginMerge();
        }
    }

    private void beginMerge() {
        phase = Phase.MERGE;
        phaseTicks = 0;
        startSky();
        spawnSeal();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "Both champions have given up their gems...");
    }

    /**
     * Optional full-screen flash at the moment the Relay Gem forms. It shows a title whose glyph (finale.flash.glyph)
     * and color (finale.flash.color) a shader or a solid-color texture can pick up, held for hold-ticks and then
     * faded out over fade-ticks. Off unless finale.flash.enabled is true.
     */
    private void flashScreen() {
        if (!cfgB("flash.enabled", false)) return;
        String glyph = plugin.getConfig().getString("finale.flash.glyph", "\uE120");
        TextColor col = TextColor.fromHexString(plugin.getConfig().getString("finale.flash.color", "#5A3A1C"));
        if (col == null) col = TextColor.color(0x5A3A1C);
        int hold = Math.max(1, cfgI("flash.hold-ticks", 20));
        int fade = Math.max(1, cfgI("flash.fade-ticks", 60));
        Title t = Title.title(Component.text(glyph).color(col), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(hold * 50L), Duration.ofMillis(fade * 50L)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
        flashUntil = skyTicks + hold + fade;
    }

    /** The point the gems merge at: 4 blocks above the ground between the two pedestals. */
    private Location mergeHub() {
        return (mergeBase != null ? mergeBase : center).clone().add(0, 4, 0);
    }

    /** A brown spider-web cocoon: spokes plus sagging rings, closing over the gems from the top down. */
    private void drawWeb(double growth) {
        if (growth <= 0.02) return;
        World w = center.getWorld();
        Location hub = mergeHub();
        double r = cfgD("web.radius", 3.2);
        Particle.DustOptions strand = new Particle.DustOptions(Color.fromRGB(126, 84, 44), 1.1f);
        Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(78, 50, 26), 1.0f);
        double lowest = 90.0 - growth * 175.0;
        int spokes = 10;
        double spin = ticks * 0.01;
        for (int s = 0; s < spokes; s++) {
            double a = spin + s * (Math.PI * 2 / spokes);
            for (double deg = 88; deg >= Math.max(lowest, -85); deg -= 11) {
                double ph = Math.toRadians(deg);
                w.spawnParticle(Particle.DUST, hub.clone().add(Math.cos(a) * r * Math.cos(ph), r * Math.sin(ph),
                        Math.sin(a) * r * Math.cos(ph)), 1, 0, 0, 0, 0, strand);
            }
        }
        for (int deg : new int[] {72, 52, 32, 12, -8, -28, -48, -68}) {
            if (deg < lowest) continue;
            double ph = Math.toRadians(deg);
            for (int s = 0; s < spokes; s++) {
                for (int k = 0; k < 2; k++) {
                    double f = k / 2.0;
                    double a = spin + (s + f) * (Math.PI * 2 / spokes);
                    double rr = r * (1.0 - 0.07 * Math.sin(Math.PI * f));
                    w.spawnParticle(Particle.DUST, hub.clone().add(Math.cos(a) * rr * Math.cos(ph), rr * Math.sin(ph),
                            Math.sin(a) * rr * Math.cos(ph)), 1, 0, 0, 0, 0, dark);
                }
            }
        }
    }

    private Location lerp(Location a, Location b, double t) {
        return new Location(b.getWorld(), a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t, a.getZ() + (b.getZ() - a.getZ()) * t);
    }

    private void playAll(Sound sound, float volume, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    /** Orbits a gem around the merge point; while it lifts off, it eases out of the spot it sat on. */
    private void orbit(ItemDisplay d, Location from, double lift, double angle, double radius, double y) {
        if (d == null || !d.isValid()) return;
        Location base = mergeBase != null ? mergeBase : center;
        Location pos = base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        if (from != null && lift < 1) pos = lerp(from, pos, lift);
        d.teleport(pos);
        center.getWorld().spawnParticle(Particle.END_ROD, pos, 2, 0.1, 0.1, 0.1, 0.0);
    }

    private void mergeTick() {
        phaseTicks++;
        World w = center.getWorld();
        Location hub = mergeHub();
        int t = phaseTicks;
        tickSeal(t);

        if (t == 1) playAll(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);

        if (t < MERGE_AT) {
            double rise = smooth(t / (double) RISE_TICKS);
            double su = t <= RISE_TICKS ? 0.0 : (t - RISE_TICKS) / (double) (MERGE_AT - RISE_TICKS);
            double radius = mix(mergeRadius, 3.0, rise) * (1.0 - 0.92 * smooth(su));
            double y = mix(gemHeight(), 4.0, rise) + 0.25 * Math.sin(t * 0.2);
            double phi = 0.04 * t + 0.30 * (MERGE_AT - RISE_TICKS) * su * su * su / 3.0;
            orbit(gemAuratus, startAuratus, rise, mergeAngle + phi, radius, y);
            orbit(gemHeretic, startHeretic, rise, mergeAngle + Math.PI + phi, radius, y);
            if (t % 10 == 0) playAll(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.6f + 0.8f * (float) (t / (double) MERGE_AT));
        }
        if (t == MERGE_AT) collide(hub);
        if (t >= MERGE_AT && t < FORM_AT) {
            if (t % 3 == 0) drawWeb(smooth((t - MERGE_AT) / 25.0));
            w.spawnParticle(Particle.END_ROD, hub, 3, 0.25, 0.25, 0.25, 0.02);
            if (t % 8 == 0) playAll(Sound.BLOCK_BEACON_AMBIENT, 1.0f, 0.5f + 0.5f * (float) ((t - MERGE_AT) / 50.0));
        }
        if (t == FORM_AT) form(hub);
        if (t >= MERGE_TOTAL_TICKS) startFlight(hub);
    }

    // ---- the seal: a golden clock face on the ground under the merge, spinning faster as the gems close in

    private void spawnSeal() {
        removeSeal();
        if (!cfgB("seal.enabled", true) || mergeBase == null) return;
        ItemStack model = packModel("finale_seal");
        seal = mergeBase.getWorld().spawn(mergeBase.clone().add(0, 0.06, 0), ItemDisplay.class, d -> {
            d.setItemStack(model);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setInterpolationDuration(2);
        });
        sealSpin = 0;
        layFlat(seal, 0.05f, 0);
    }

    private void tickSeal(int t) {
        if (seal == null || !seal.isValid()) return;
        double size = cfgD("seal.size", 7.0);
        float scale = (float) (size * smooth(t / 40.0));
        if (t >= MERGE_AT && t < MERGE_AT + 6) scale *= 1.0f + 0.12f * (MERGE_AT + 6 - t); // the collision kicks it outward
        double speed = t < MERGE_AT ? 0.01 + 0.22 * Math.pow(t / (double) MERGE_AT, 2) : 0.05;
        sealSpin += (float) speed;
        layFlat(seal, Math.max(0.05f, scale), sealSpin);
        if (t % 4 == 0 && t < MERGE_AT) {
            World w = seal.getWorld();
            for (int i = 0; i < 12; i++) {
                double a = sealSpin + i * Math.PI / 6;
                w.spawnParticle(Particle.DUST, mergeBase.clone().add(Math.cos(a) * scale * 0.45, 0.15, Math.sin(a) * scale * 0.45), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(Color.fromRGB(240, 196, 140), 1.2f));
            }
        }
    }

    /** The Relay forms: the seal bursts into dust and is gone. */
    private void shatterSeal() {
        if (seal != null && seal.isValid()) {
            double r = cfgD("seal.size", 7.0) * 0.45;
            World w = seal.getWorld();
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI / 24;
                w.spawnParticle(Particle.DUST, mergeBase.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 3, 0.15, 0.1, 0.15, 0,
                        new Particle.DustOptions(i % 3 == 0 ? Color.fromRGB(240, 196, 140) : Color.fromRGB(176, 112, 58), 1.6f));
            }
        }
        removeSeal();
    }

    private void removeSeal() {
        if (seal != null && seal.isValid()) seal.remove();
        seal = null;
    }

    /** Lays a flat pack model level at the display's height, scaled and turned. */
    private static void layFlat(ItemDisplay d, float scale, float spin) {
        d.setInterpolationDelay(0);
        d.setTransformation(new org.bukkit.util.Transformation(new Vector3f(0, 0.5f * scale, 0),
                new org.joml.Quaternionf().rotationY(spin), new Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
    }

    /** The two gems collide: they vanish into a core, the screen can flash, and the web starts closing. */
    private void collide(Location hub) {
        World w = hub.getWorld();
        for (ItemDisplay d : displays) d.remove();
        displays.clear();
        gemAuratus = null;
        gemHeretic = null;
        w.spawnParticle(Particle.END_ROD, hub, 120, 0.6, 0.6, 0.6, 0.35);
        w.spawnParticle(Particle.DUST, hub, 60, 0.8, 0.8, 0.8, 0.0, new Particle.DustOptions(Color.fromRGB(126, 84, 44), 1.6f));
        playAll(Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        playAll(Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.5f);
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "The champions merge...");
    }

    /** The web bursts and the Relay Gem forms. Visual-only blast: nearby players are thrown back, nothing is harmed. */
    private void form(Location hub) {
        World w = hub.getWorld();
        removePedestals();
        shatterSeal();
        flashScreen();
        relayFly = spawnDisplay(hub, gems.createMythic(GemType.RELAY));
        w.spawnParticle(Particle.EXPLOSION_EMITTER, hub, 3, 1.0, 1.0, 1.0, 0.0);
        w.spawnParticle(Particle.END_ROD, hub, 400, 2.0, 2.0, 2.0, 0.9);
        w.spawnParticle(Particle.FIREWORK, hub, 200, 1.0, 1.0, 1.0, 0.4);
        w.spawnParticle(Particle.ITEM, hub, 80, 1.5, 1.5, 1.5, 0.2, new ItemStack(Material.COBWEB));
        w.spawnParticle(Particle.DUST, hub, 120, 2.0, 2.0, 2.0, 0.0, new Particle.DustOptions(Color.fromRGB(110, 72, 36), 1.8f));
        w.strikeLightningEffect(hub);
        playAll(Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.7f);
        playAll(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4.0f, 0.9f);
        for (Player p : w.getPlayers()) {
            Vector push = p.getLocation().toVector().subtract(hub.toVector());
            if (push.lengthSquared() > 400 || push.lengthSquared() < 0.0001) continue;
            p.setVelocity(push.normalize().multiply(0.9).setY(0.45));
        }
    }

    // ------------------------------------------------------------------ phase 2: flight

    private void startFlight(Location hub) {
        phase = Phase.FLIGHT;
        phaseTicks = 0;
        if (relayFly == null || !relayFly.isValid()) relayFly = spawnDisplay(hub, gems.createMythic(GemType.RELAY));
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "THE SIGNAL HAS CHOSEN " + nameOf(target) + ".");
        playAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 1.4f);
    }

    private void flightTick() {
        phaseTicks++;
        World w = center.getWorld();
        Location hub = mergeHub();
        Player t = target == null ? null : Bukkit.getPlayer(target);
        Location dest = t != null && !t.isDead() && t.getWorld().equals(w) ? t.getLocation().add(0, 1.2, 0) : hub;

        double u = Math.min(1.0, phaseTicks / (double) FLIGHT_TICKS);
        double ease = u * u * (3 - 2 * u);
        Location pos = lerp(hub, dest, ease).add(0, Math.sin(Math.PI * u) * 6.0, 0);
        if (relayFly != null && relayFly.isValid()) relayFly.teleport(pos);
        w.spawnParticle(Particle.END_ROD, pos, 4, 0.15, 0.15, 0.15, 0.0);
        w.spawnParticle(Particle.FIREWORK, pos, 2, 0.1, 0.1, 0.1, 0.02);
        if (phaseTicks % 5 == 0) playAll(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.7f + 0.8f * (float) u);

        if (phaseTicks >= FLIGHT_TICKS) arrive(t, hub);
    }

    private void arrive(Player t, Location hub) {
        if (relayFly != null) relayFly.remove();
        relayFly = null;
        ItemStack relay = gems.createMythic(GemType.RELAY);
        World w = center.getWorld();

        if (t != null && t.isOnline() && !t.isDead()) {
            giveKitAndRelay(t, relay);
            w.spawnParticle(Particle.END_ROD, t.getLocation().add(0, 1, 0), 120, 0.6, 1.0, 0.6, 0.2);
            playAll(Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f);
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + t.getName() + " holds the Relay Gem. Take it from them!");
        } else {
            w.dropItem(hub.clone().add(0, 0.5, 0), relay);
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "The Relay Gem lies loose at Spawn. First come, first served!");
        }
        beginBattle();
    }

    // ------------------------------------------------------------------ the loaner kit

    private ItemStack kit(Material mat, int amount, Enchantment ench, int level) {
        ItemStack it = new ItemStack(mat, amount);
        ItemMeta m = it.getItemMeta();
        if (ench != null && level > 0) m.addEnchant(ench, level, true);
        m.setUnbreakable(true);
        m.getPersistentDataContainer().set(kitKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isKit(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(kitKey, PersistentDataType.BYTE);
    }

    private boolean isRelay(ItemStack it) {
        return it != null && gems.isGem(it) && gems.getType(it) == GemType.RELAY;
    }

    /** What the attunement kit raises each of the player's own pieces to, where the enchantment fits that item. */
    private static final Map<Enchantment, Integer> MAXED = new java.util.LinkedHashMap<>();
    static {
        MAXED.put(Enchantment.PROTECTION, 4);
        MAXED.put(Enchantment.SHARPNESS, 5);
        MAXED.put(Enchantment.EFFICIENCY, 5);
        MAXED.put(Enchantment.POWER, 5);
        MAXED.put(Enchantment.UNBREAKING, 3);
        MAXED.put(Enchantment.MENDING, 1);
    }

    /** Armor, weapons and tools the player owns. Gems and the plugin's special items are never touched. */
    private boolean isOwnGear(ItemStack it) {
        return it != null && it.getType() != Material.AIR && it.getType().getMaxDurability() > 0 && it.getAmount() == 1
                && !gems.isGem(it) && gems.getKind(it) == null && !isKit(it);
    }

    /** A kit-tagged copy of one of the player's own pieces with everything in MAXED applied. */
    private ItemStack maxedCopy(ItemStack original) {
        ItemStack copy = original.clone();
        ItemMeta m = copy.getItemMeta();
        for (Map.Entry<Enchantment, Integer> want : MAXED.entrySet()) {
            Enchantment ench = want.getKey();
            if (!ench.canEnchantItem(copy)) continue;
            // Protection and Sharpness replace their rivals (Blast Protection, Smite, ...); anything else
            // that clashes with what the piece already has (Mending on an Infinity bow) is skipped.
            boolean replacesRivals = ench.equals(Enchantment.PROTECTION) || ench.equals(Enchantment.SHARPNESS);
            boolean blocked = false;
            for (Enchantment had : new ArrayList<>(m.getEnchants().keySet())) {
                if (had.equals(ench) || !ench.conflictsWith(had)) continue;
                if (replacesRivals) m.removeEnchant(had);
                else blocked = true;
            }
            if (!blocked && m.getEnchantLevel(ench) < want.getValue()) m.addEnchant(ench, want.getValue(), true);
        }
        m.getPersistentDataContainer().set(kitKey, PersistentDataType.BYTE, (byte) 1);
        copy.setItemMeta(m);
        return copy;
    }

    /**
     * Saves the player's own gear to disk and swaps each piece, in its slot, for a maxed kit copy.
     * Everything else in the inventory (gems, food, blocks) is left as it is.
     */
    private boolean stashGearAndUpgrade(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        YamlConfiguration y = new YamlConfiguration();
        y.set("size", contents.length);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            if (!isOwnGear(contents[i])) continue;
            y.set("slot." + i, contents[i]);
            slots.add(i);
        }
        try {
            y.save(stashFile(p.getUniqueId()));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not stash gear for " + p.getName() + ": " + ex.getMessage());
            return false;
        }
        for (int i : slots) inv.setItem(i, maxedCopy(contents[i]));
        p.updateInventory();
        return true;
    }

    private void giveKitAndRelay(Player t, ItemStack relay) {
        PlayerInventory inv = t.getInventory();
        boolean kitEnabled = plugin.getConfig().getBoolean("finale.kit.enabled", true);
        if (kitEnabled && plugin.getConfig().getBoolean("finale.kit.own-gear", true)) {
            if (stashGearAndUpgrade(t) && plugin.getConfig().getBoolean("finale.kit.food", true)) {
                // The inventory is no longer emptied, so the food goes wherever there is room.
                inv.addItem(kit(Material.GOLDEN_APPLE, 64, null, 0), kit(Material.GOLDEN_CARROT, 64, null, 0));
            }
        } else if (kitEnabled && stashAndClear(t)) {
            int prot = cfgI("kit.protection", 3);
            int sharp = cfgI("kit.sharpness", 3);
            inv.setHelmet(kit(Material.NETHERITE_HELMET, 1, Enchantment.PROTECTION, prot));
            inv.setChestplate(kit(Material.NETHERITE_CHESTPLATE, 1, Enchantment.PROTECTION, prot));
            inv.setLeggings(kit(Material.NETHERITE_LEGGINGS, 1, Enchantment.PROTECTION, prot));
            inv.setBoots(kit(Material.NETHERITE_BOOTS, 1, Enchantment.PROTECTION, prot));
            inv.setItem(0, kit(Material.NETHERITE_SWORD, 1, Enchantment.SHARPNESS, sharp));
            inv.setItem(1, kit(Material.NETHERITE_AXE, 1, Enchantment.SHARPNESS, sharp));
            inv.setItem(2, kit(Material.NETHERITE_PICKAXE, 1, Enchantment.EFFICIENCY, 3));
            inv.setItem(4, kit(Material.GOLDEN_APPLE, 64, null, 0));
            inv.setItem(5, kit(Material.GOLDEN_CARROT, 64, null, 0));
        }
        // Gems work from the off-hand, so the Relay goes there.
        ItemStack off = inv.getItemInOffHand();
        inv.setItemInOffHand(relay);
        if (off != null && off.getType() != Material.AIR) {
            for (ItemStack left : inv.addItem(off).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
        }
        t.setHealth(t.getMaxHealth());
        t.setFoodLevel(20);
        t.setSaturation(20f);
    }

    // ---- inventory stash (same idea as the Command trial: save to disk, clear, restore afterwards)

    private File stashFile(UUID id) {
        return new File(stashDir, id + ".yml");
    }

    private boolean stashAndClear(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        YamlConfiguration y = new YamlConfiguration();
        y.set("size", contents.length);
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) y.set("slot." + i, contents[i]);
        }
        try {
            y.save(stashFile(p.getUniqueId()));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not stash inventory for " + p.getName() + ": " + ex.getMessage());
            return false;
        }
        p.getInventory().clear();
        p.updateInventory();
        return true;
    }

    /** Gives a player their saved items back, each to its original slot if that is free, otherwise wherever fits. */
    private void restoreStash(Player p) {
        File f = stashFile(p.getUniqueId());
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        int size = y.getInt("size", p.getInventory().getContents().length);
        ItemStack[] saved = new ItemStack[size];
        for (int i = 0; i < size; i++) saved[i] = y.getItemStack("slot." + i);

        PlayerInventory inv = p.getInventory();
        int slots = inv.getContents().length;
        for (int i = 0; i < size; i++) {
            if (saved[i] == null) continue;
            ItemStack there = i < slots ? inv.getItem(i) : null;
            if (i < slots && (there == null || there.getType() == Material.AIR)) {
                inv.setItem(i, saved[i]); // back where it was: armor goes straight back on
            } else {
                for (ItemStack left : inv.addItem(saved[i]).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        p.updateInventory();
        if (!f.delete()) plugin.getLogger().warning("Could not delete stash file " + f.getName());
    }

    private void removeKitItems() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isKit(contents[i])) inv.setItem(i, null);
            }
            purgeKit(p.getEnderChest());
            p.updateInventory();
        }
        for (World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (isKit(item.getItemStack())) item.remove();
            }
        }
    }

    // ------------------------------------------------------------------ phase 3: battle

    private void beginBattle() {
        phase = Phase.BATTLE;
        phaseTicks = 0;
        ticks = 0;
        lastTickMs = System.currentTimeMillis();
        applyBorder();
        if (!cfgB("sky.during-battle", false)) stopSky();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "Attunement has begun. Whoever holds the Relay Gem fills the bar. Hold it until 100%.");
    }

    private void battleTick() {
        long now = System.currentTimeMillis();
        double dt = now - lastTickMs;
        lastTickMs = now;

        Player holder = findHolder();
        if (holder != null) {
            double durationMs = Math.max(10, cfgI("duration-seconds", 600)) * 1000.0;
            progress = Math.min(1.0, progress + dt / durationMs);
        }
        updateBars(holder);
        if (ticks % 20 == 0) updateBorder();
        rescueLooseRelay();

        if (progress >= 1.0) finish(holder);
    }

    private Player findHolder() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            for (ItemStack it : p.getInventory().getContents()) {
                if (isRelay(it)) return p;
            }
        }
        return null;
    }

    private void updateBars(Player holder) {
        int pct = (int) Math.floor(progress * 100.0);
        TextColor col = TextColor.lerp((float) progress, NamedTextColor.RED, NamedTextColor.DARK_PURPLE);
        BossBar.Color bc = progress < 0.34 ? BossBar.Color.RED : (progress < 0.67 ? BossBar.Color.PINK : BossBar.Color.PURPLE);
        if (holder == null) bc = (ticks / 10) % 2 == 0 ? BossBar.Color.YELLOW : BossBar.Color.RED;

        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar bar = bars.computeIfAbsent(p.getUniqueId(),
                    k -> BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
            String suffix;
            NamedTextColor suffixColor = NamedTextColor.GRAY;
            if (holder == null) {
                suffix = "  The Relay is loose!";
                suffixColor = NamedTextColor.RED;
            } else if (holder.getUniqueId().equals(p.getUniqueId())) {
                suffix = "  You hold the Relay!";
                suffixColor = NamedTextColor.LIGHT_PURPLE;
            } else {
                suffix = "  " + direction(p, holder);
            }
            bar.name(Component.text("Relay Gem Attunement: " + pct + "%", col, TextDecoration.BOLD)
                    .append(Component.text(suffix, suffixColor)));
            bar.progress((float) Math.max(0.0, Math.min(1.0, progress)));
            bar.color(bc);
            p.showBossBar(bar);
        }
    }

    /** Arrow and distance from a player to the holder. */
    private String direction(Player from, Player to) {
        if (!from.getWorld().equals(to.getWorld())) return "?";
        double dx = to.getLocation().getX() - from.getLocation().getX();
        double dz = to.getLocation().getZ() - from.getLocation().getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((targetYaw - from.getLocation().getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
        int idx = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
        return ARROWS[idx] + " " + (int) Math.sqrt(dx * dx + dz * dz) + "m";
    }

    // ---- border

    private void applyBorder() {
        World w = center.getWorld();
        WorldBorder b = w.getWorldBorder();
        origCenter = b.getCenter();
        origSize = b.getSize();
        origDamage = b.getDamageAmount();
        origBuffer = b.getDamageBuffer();
        origWarning = b.getWarningDistance();
        saveBorder(w);

        b.setCenter(center);
        b.setSize(cfgD("border.start-size", 400.0));
        b.setDamageBuffer(0.0);
        b.setDamageAmount(cfgD("border.damage", 2.0));
        b.setWarningDistance(16);
    }

    private void updateBorder() {
        double start = cfgD("border.start-size", 400.0);
        double end = cfgD("border.end-size", 60.0);
        center.getWorld().getWorldBorder().setSize(start + (end - start) * progress, 2L);
    }

    private void saveBorder(World w) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", w.getName());
        y.set("x", origCenter.getX());
        y.set("z", origCenter.getZ());
        y.set("size", origSize);
        y.set("damage", origDamage);
        y.set("buffer", origBuffer);
        y.set("warning", origWarning);
        try {
            y.save(borderFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save finale-border.yml: " + ex.getMessage());
        }
    }

    private void restoreBorder() {
        if (center == null || origCenter == null) return;
        WorldBorder b = center.getWorld().getWorldBorder();
        b.setCenter(origCenter);
        b.setSize(origSize);
        b.setDamageAmount(origDamage);
        b.setDamageBuffer(origBuffer);
        b.setWarningDistance(origWarning);
        origCenter = null;
        if (borderFile.exists() && !borderFile.delete()) plugin.getLogger().warning("Could not delete finale-border.yml");
    }

    /** If the server stopped mid-finale, put the border back the next time the plugin loads. */
    public void recoverAfterCrash() {
        if (!borderFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(borderFile);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w != null) {
            WorldBorder b = w.getWorldBorder();
            b.setCenter(y.getDouble("x"), y.getDouble("z"));
            b.setSize(y.getDouble("size", 59999968.0));
            b.setDamageAmount(y.getDouble("damage", 0.2));
            b.setDamageBuffer(y.getDouble("buffer", 5.0));
            b.setWarningDistance(y.getInt("warning", 5));
            plugin.getLogger().info("Restored the world border after an interrupted finale.");
        }
        if (!borderFile.delete()) plugin.getLogger().warning("Could not delete finale-border.yml");
    }

    // ---- a loose Relay never despawns, burns, or falls out of the world

    private void rescueLooseRelay() {
        for (World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (!isRelay(item.getItemStack())) continue;
                if (item.getLocation().getY() < w.getMinHeight() + 2) {
                    item.teleport(center.clone().add(0, 4, 0));
                    item.setVelocity(new Vector(0, 0.2, 0));
                }
            }
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (!isRelay(item.getItemStack())) return;
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setGlowing(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent e) {
        if (isRelay(e.getItem().getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ ending

    /** Normal ending (holder = who keeps the gem) or a host abort (holder = null). */
    public void finish(Player holder) {
        if (phase == Phase.IDLE) return;
        boolean completed = progress >= 1.0;
        phase = Phase.IDLE;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (ItemDisplay d : displays) d.remove();
        displays.clear();
        removePedestals();
        removeSeal();
        mergeBase = null;
        startAuratus = startHeretic = null;
        if (relayFly != null) relayFly.remove();
        relayFly = null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar bar = bars.get(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
        }
        bars.clear();

        stopSky();
        restoreBorder();
        removeKitItems();
        Player t = target == null ? null : Bukkit.getPlayer(target);
        if (t != null) restoreStash(t);
        if (plugin.turmoil().isActive()) plugin.turmoil().stop();
        target = null;

        if (completed && holder != null) {
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + holder.getName()
                    + " has attuned the Relay Gem. The world is quiet again.");
            playAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else {
            Bukkit.broadcastMessage(ChatColor.GRAY + "The finale has ended.");
        }
    }

    /** Host abort. */
    public void stop() {
        finish(null);
    }

    public void shutdown() {
        cancelOverlayFadeOut();
        endPulse();
        if (phase != Phase.IDLE) finish(null);
    }

    /**
     * The finale's sky and overlay, in red, for a few seconds (the Revenant's phase 2). Does nothing while the
     * finale runs; if the finale starts during a pulse, the finale takes the sky over.
     */
    public void skyPulse(int seconds) {
        if (phase != Phase.IDLE || skyOn) return;
        pulseRed = true;
        startSky();
        int[] left = {Math.max(1, seconds) * 20};
        pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != Phase.IDLE) { pulseRed = false; cancelPulseTask(); return; }
            skyTick();
            if (--left[0] <= 0) endPulse();
        }, 1L, 1L);
    }

    private void endPulse() {
        if (pulseTask == null) return;
        cancelPulseTask();
        if (phase == Phase.IDLE) stopSky(); // before clearing pulseRed, so the fade-out frame stays red
        pulseRed = false;
    }

    private void cancelPulseTask() {
        if (pulseTask != null) pulseTask.cancel();
        pulseTask = null;
    }

    // ------------------------------------------------------------------ events

    private void tick() {
        ticks++;
        if (skyOn) skyTick();
        switch (phase) {
            case DROP -> dropTick();
            case MERGE -> mergeTick();
            case FLIGHT -> flightTick();
            case BATTLE -> {
                if (ticks % 5 == 0) battleTick();
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        unstampAll(p);
        if (skyOn) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && skyOn) applySky(p);
            }, 10L);
        }
        if (phase == Phase.IDLE) removeKitFrom(p); // anyone who logged off holding kit copies loses them now
        if (!stashFile(p.getUniqueId()).exists()) return;
        if (phase != Phase.IDLE && p.getUniqueId().equals(target)) return; // still mid-finale: keep the kit
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                removeKitFrom(p);
                restoreStash(p);
            }
        }, 20L);
    }

    private void removeKitFrom(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isKit(contents[i])) inv.setItem(i, null);
        }
        purgeKit(p.getEnderChest());
    }

    private void purgeKit(org.bukkit.inventory.Inventory box) {
        ItemStack[] contents = box.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isKit(contents[i])) box.setItem(i, null);
        }
    }

    /** Kit copies hidden in a chest or barrel during the fight vanish the first time it is opened afterwards. */
    @EventHandler
    public void onOpenAfterFinale(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (phase == Phase.IDLE) purgeKit(e.getInventory());
    }

    /** A holder who disconnects drops the Relay where they stand, so the fight goes on. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (phase != Phase.BATTLE) return;
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isRelay(contents[i])) {
                p.getWorld().dropItemNaturally(p.getLocation(), contents[i]);
                inv.setItem(i, null);
            }
        }
    }

    /** The loaner kit disappears with its owner instead of becoming loot. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (phase == Phase.IDLE) return;
        e.getDrops().removeIf(this::isKit);
    }
}
