package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
import java.util.Set;
import java.util.UUID;

/**
 * "The Lost Halo" (events.halo; HaloTest), how the Auratus enters a season. An admin hides five Halo Fragments (in
 * chests, or on Halo Reliquaries: a small shrine with the fragment floating over it) and starts the hunt. Finding one is
 * announced without a name; holders are never revealed; fragments can't be stored, dropped or traded, and drop where
 * their holder dies or logs out. The first to hold three is "haloed" where they stand: gold chains lower the Auratus into
 * their hand, and every other fragment vanishes. Saved to halo.yml: a restart resumes the hunt.
 */
final class HaloHunt implements Listener {
    static final String ADMIN = "soulbound.halo.admin";
    static final NamespacedKey KEY_N = new NamespacedKey("soulboundgems", "halo_n");
    static final NamespacedKey KEY_ID = new NamespacedKey("soulboundgems", "halo_id");
    static final NamespacedKey KEY_GEN = new NamespacedKey("soulboundgems", "halo_gen");
    static final NamespacedKey KEY_RELIQ = new NamespacedKey("soulboundgems", "halo_reliquary");
    private static final Color GOLD = Color.fromRGB(0xFFC846);

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private boolean active, awarded;
    private int generation = 1;
    private final Map<String, Integer> registered = new LinkedHashMap<>(); // fragment id -> its number
    private final Map<String, String> where = new HashMap<>(); // fragment id -> where it was last seen
    private final Set<String> found = new LinkedHashSet<>();
    private final Map<Integer, Reliquary> reliquaries = new LinkedHashMap<>();
    private final Set<UUID> groundItems = new HashSet<>(); // fragment item entities being looked after
    private final Set<UUID> scanSoon = new HashSet<>();
    private Claim claim;
    private BukkitTask task;
    private int tick;

    /** A shrine holding fragment n: a barrier block with displays and a click box (never saved with the chunk). */
    private static final class Reliquary {
        final int n;
        final String id, world;
        final int x, y, z;
        UUID shrine, frag, hit;

        Reliquary(int n, String id, String world, int x, int y, int z) {
            this.n = n; this.id = id; this.world = world; this.x = x; this.y = y; this.z = z;
        }

        Location block() { World w = Bukkit.getWorld(world); return w == null ? null : new Location(w, x, y, z); }
    }

    /** The claim scene: the Auratus lowered on four chains into their hand. */
    private static final class Claim {
        UUID player;
        Location spot;
        ItemDisplay auratus;
        final List<ItemDisplay> chains = new ArrayList<>();
        int age;
        BukkitTask task;
    }

    HaloHunt(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "halo.yml");
        load();
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        for (Reliquary r : reliquaries.values()) spawn(r); // a restart: the shrines come back (where loaded)
        for (World w : Bukkit.getWorlds()) for (Item it : w.getEntitiesByClass(Item.class)) if (isFragment(it.getItemStack())) protect(it);
    }

    void shutdown() {
        if (claim != null) finishClaim(false); // a restart mid-scene: the Auratus is theirs now, not lost
        for (Reliquary r : reliquaries.values()) despawn(r);
        if (task != null) task.cancel();
        task = null;
        save();
    }

    private String cfg(String k, String d) { return plugin.getConfig().getString("events.halo." + k, d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt("events.halo." + k, d); }

    // ------------------------------------------------------------------ the file

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        active = y.getBoolean("active", false);
        awarded = y.getBoolean("awarded", false);
        generation = Math.max(1, y.getInt("generation", 1));
        ConfigurationSection r = y.getConfigurationSection("fragments");
        if (r != null) for (String id : r.getKeys(false)) {
            registered.put(id, r.getInt(id + ".n"));
            String w = r.getString(id + ".where");
            if (w != null) where.put(id, w);
            if (r.getBoolean(id + ".found", false)) found.add(id);
        }
        ConfigurationSection q = y.getConfigurationSection("reliquaries");
        if (q != null) for (String k : q.getKeys(false)) {
            ConfigurationSection s = q.getConfigurationSection(k);
            if (s == null) continue;
            int n = s.getInt("n");
            reliquaries.put(n, new Reliquary(n, s.getString("id"), s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z")));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("active", active);
        y.set("awarded", awarded);
        y.set("generation", generation);
        for (Map.Entry<String, Integer> e : registered.entrySet()) {
            String k = "fragments." + e.getKey() + ".";
            y.set(k + "n", e.getValue());
            y.set(k + "where", where.get(e.getKey()));
            y.set(k + "found", found.contains(e.getKey()));
        }
        for (Reliquary r : reliquaries.values()) {
            String k = "reliquaries." + r.n + ".";
            y.set(k + "n", r.n); y.set(k + "id", r.id); y.set(k + "world", r.world); y.set(k + "x", r.x); y.set(k + "y", r.y); y.set(k + "z", r.z);
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save halo.yml: " + ex.getMessage()); }
    }

    // ------------------------------------------------------------------ the fragments

    static boolean isFragment(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY_ID, PersistentDataType.STRING);
    }

    static HaloMath.Fragment read(ItemStack it) {
        if (!isFragment(it)) return null;
        PersistentDataContainer c = it.getItemMeta().getPersistentDataContainer();
        return new HaloMath.Fragment(c.getOrDefault(KEY_N, PersistentDataType.INTEGER, 0), c.get(KEY_ID, PersistentDataType.STRING),
                c.getOrDefault(KEY_GEN, PersistentDataType.INTEGER, 0));
    }

    private boolean valid(ItemStack it) { return HaloMath.valid(read(it), registered, generation); }

    /** Halo Fragment n: the pack's model, gold, fire-proof (it floats on lava like netherite), one per stack. */
    private ItemStack fragment(int n, String id) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("blissgems", "halo/fragment_" + n));
        m.setDisplayName("§6" + SmallCaps.of("Halo Fragment") + " §e" + n);
        m.setLore(List.of("§7" + SmallCaps.of("A piece of the Lost Halo."), "§8" + SmallCaps.of("Hold three to be haloed.")));
        m.setMaxStackSize(1);
        m.setDamageResistant(org.bukkit.tag.DamageTypeTags.IS_FIRE);
        PersistentDataContainer c = m.getPersistentDataContainer();
        c.set(KEY_N, PersistentDataType.INTEGER, n);
        c.set(KEY_ID, PersistentDataType.STRING, id);
        c.set(KEY_GEN, PersistentDataType.INTEGER, generation);
        it.setItemMeta(m);
        return it;
    }

    /** One new id for fragment n (any older one of that number is void from now on). */
    private String register(int n) {
        registered.values().removeIf(v -> v == n);
        where.keySet().retainAll(registered.keySet());
        found.retainAll(registered.keySet());
        String id = HaloMath.newId();
        registered.put(id, n);
        return id;
    }

    // ------------------------------------------------------------------ admin

    String give(Player admin, int n) {
        if (n < 1 || n > 5) return "Fragments are numbered 1 to 5.";
        Reliquary r = reliquaries.remove(n);
        if (r != null) removeReliquary(r, false); // a new fragment n replaces the one on its shrine
        String id = register(n);
        where.put(id, "holder:" + admin.getName());
        for (ItemStack left : admin.getInventory().addItem(fragment(n, id)).values()) admin.getWorld().dropItemNaturally(admin.getLocation(), left);
        save();
        return null;
    }

    String placeReliquary(Player admin, int n) {
        if (n < 1 || n > 5) return "Fragments are numbered 1 to 5.";
        Block target = admin.getTargetBlockExact(6);
        if (target == null) return "Look at the block to put it on (within 6 blocks).";
        Block spot = target.getRelative(BlockFace.UP);
        if (!spot.isEmpty()) return "There's no room on top of that block.";
        Reliquary old = reliquaries.remove(n);
        if (old != null) removeReliquary(old, false);
        String id = register(n);
        Reliquary r = new Reliquary(n, id, spot.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ());
        reliquaries.put(n, r);
        where.put(id, "reliquary " + spot.getWorld().getName() + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
        spot.setType(Material.BARRIER, false); // solid, like the shrine looks
        spawn(r);
        save();
        return null;
    }

    String removeReliquaryCommand(int n) {
        Reliquary r = reliquaries.remove(n);
        if (r == null) return "There's no reliquary for fragment " + n + ".";
        removeReliquary(r, true);
        save();
        return null;
    }

    /** The shrine, its displays and its barrier go; with {@code unregister} its fragment is void too. */
    private void removeReliquary(Reliquary r, boolean unregister) {
        despawn(r);
        Location b = r.block();
        if (b != null && b.getBlock().getType() == Material.BARRIER) b.getBlock().setType(Material.AIR, false);
        if (unregister) { registered.remove(r.id); where.remove(r.id); found.remove(r.id); }
    }

    /** The hunt begins: three bell tolls, a golden sky fading out, the title for everyone. */
    String startHunt(boolean force) {
        if (active) return "The hunt is already on.";
        if (registered.isEmpty()) return "Hide some fragments first (/halo give or /halo reliquary).";
        if (!force) {
            String why = auratusExists();
            if (why != null) return why + " Add 'force' to start anyway.";
        }
        active = true;
        found.clear();
        save();
        bells();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.overlay() != null) plugin.overlay().fadeOut(p, cfg("sky-style", "gold"), 4, cfgI("sky-ticks", 60));
            p.showTitle(Title.title(Component.text(SmallCaps.of(cfg("title", "The Halo has fallen"))).color(net.kyori.adventure.text.format.TextColor.color(0xFFC846)),
                    Component.text(SmallCaps.of(cfg("subtitle", "Find its pieces"))).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(4), Duration.ofSeconds(2))));
        }
        return null;
    }

    /** Why /halo start shouldn't run: an Auratus online, lying loose, or already won by an earlier hunt. Null if none. */
    private String auratusExists() {
        if (awarded) return "An earlier hunt already gave out the Auratus.";
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) if (isAuratus(it)) return p.getName() + " has an Auratus.";
            for (ItemStack it : p.getEnderChest().getContents()) if (isAuratus(it)) return p.getName() + " has an Auratus in their ender chest.";
        }
        for (World w : Bukkit.getWorlds()) for (Item it : w.getEntitiesByClass(Item.class)) if (isAuratus(it.getItemStack())) return "An Auratus is lying in " + w.getName() + ".";
        return null;
    }

    private boolean isAuratus(ItemStack it) { return plugin.gems().isMythic(it) && plugin.gems().getType(it) == GemType.AURATUS; }

    /** /halo stop: the hunt ends and every fragment, wherever it is, is gone. */
    void stopHunt() {
        if (claim != null) finishClaim(true);
        endHunt();
    }

    private void endHunt() {
        active = false;
        generation++; // every fragment of this hunt is void from now on: deleted wherever it's seen
        registered.clear();
        where.clear();
        found.clear();
        for (Reliquary r : new ArrayList<>(reliquaries.values())) removeReliquary(r, false);
        reliquaries.clear();
        save();
        purge();
    }

    /** Removes void fragments from everyone online, the ground and every loaded container (the rest as they load). */
    private void purge() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            cleanInventory(p.getInventory());
            cleanInventory(p.getEnderChest());
        }
        for (World w : Bukkit.getWorlds()) {
            for (Item it : w.getEntitiesByClass(Item.class)) if (isFragment(it.getItemStack()) && !valid(it.getItemStack())) it.remove();
            for (Chunk c : w.getLoadedChunks()) cleanChunk(c);
        }
    }

    private int cleanInventory(Inventory inv) {
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (isFragment(c[i]) && !valid(c[i])) { inv.setItem(i, null); n++; }
        return n;
    }

    private void cleanChunk(Chunk c) {
        for (BlockState st : c.getTileEntities()) if (st instanceof Container ct) cleanInventory(ct.getInventory());
    }

    List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("§6" + SmallCaps.of("The Lost Halo") + "§7: " + (active ? "§ahunting" : "§7not running") + "§7, found " + found.size() + "/" + registered.size()
                + (awarded ? "§7, an Auratus was already won (start needs 'force')" : ""));
        Map<Integer, String> byN = new java.util.TreeMap<>();
        for (Map.Entry<String, Integer> e : registered.entrySet())
            byN.put(e.getValue(), where.getOrDefault(e.getKey(), "unknown") + (found.contains(e.getKey()) ? " §8(found)" : ""));
        for (int n = 1; n <= 5; n++) out.add("§e" + n + "§7: " + byN.getOrDefault(n, "§8not hidden"));
        return out;
    }

    // ------------------------------------------------------------------ the bell, the flash

    private void bells() {
        for (int i = 0; i < 3; i++)
            Bukkit.getScheduler().runTaskLater(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "halo.bell", p); }, i * 30L);
    }

    // ------------------------------------------------------------------ who has what (finds, copies, the claim)

    /** Looks at a player's fragments next tick (after a pickup, a click, a join). */
    private void scanSoon(Player p) {
        if (!scanSoon.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> { scanSoon.remove(p.getUniqueId()); if (p.isOnline()) scan(p); });
    }

    private void scan(Player p) {
        Inventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        List<HaloMath.Fragment> held = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < c.length; i++) if (isFragment(c[i])) { held.add(read(c[i])); slots.add(i); }
        if (held.isEmpty()) return;
        Set<String> elsewhere = new HashSet<>();
        for (HaloMath.Fragment f : held) {
            String w = where.get(f.id());
            if (w == null || !w.startsWith("holder:") || w.equals("holder:" + p.getName())) continue;
            Player other = Bukkit.getPlayerExact(w.substring(7));
            if (other != null && other.isOnline() && carries(other, f.id())) elsewhere.add(f.id()); // they really have it: this is a copy
        }
        for (int i : HaloMath.toDelete(held, registered, generation, elsewhere)) inv.setItem(slots.get(i), null);
        Set<String> mine = HaloMath.distinct(held, registered, generation);
        mine.removeAll(elsewhere);
        boolean changed = false;
        for (String id : mine) {
            changed |= !("holder:" + p.getName()).equals(where.put(id, "holder:" + p.getName()));
            if (p.getGameMode() == GameMode.CREATIVE) continue; // an admin handling them never "finds" one
            if (HaloMath.newlyFound(active, found, id)) {
                changed = true;
                Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§6" + SmallCaps.of(cfg("found-message", "A Halo fragment has been found"))
                        + " §e" + HaloMath.count(found.size(), registered.size())));
                for (Player o : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "halo.found", o);
            }
        }
        if (changed) save();
        if (claim == null && p.getGameMode() != GameMode.CREATIVE && HaloMath.claims(active, mine.size(), cfgI("fragments-needed", 3))) startClaim(p);
    }

    private boolean carries(Player p, String id) {
        for (ItemStack it : p.getInventory().getContents()) {
            HaloMath.Fragment f = read(it);
            if (f != null && id.equals(f.id())) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!isFragment(e.getItem().getItemStack())) return;
        if (!(e.getEntity() instanceof Player p)) { e.setCancelled(true); return; } // no mob takes one
        groundItems.remove(e.getItem().getUniqueId());
        scanSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) scanSoon(p); }

    /** Closing a chest an admin hid one in: where it is now. Opening one: void fragments in it are gone. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        scanSoon(p);
        Inventory top = e.getView().getTopInventory();
        Location l = top.getLocation();
        if (l == null || l.getWorld() == null) return;
        boolean changed = false;
        for (ItemStack it : top.getContents()) {
            HaloMath.Fragment f = read(it);
            if (f == null || !valid(it)) continue;
            where.put(f.id(), "container " + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
            changed = true;
        }
        if (changed) save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) { cleanInventory(e.getView().getTopInventory()); }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        cleanChunk(e.getChunk());
        for (Reliquary r : reliquaries.values())
            if (r.world.equals(e.getWorld().getName()) && (r.x >> 4) == e.getChunk().getX() && (r.z >> 4) == e.getChunk().getZ()) spawn(r);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            cleanInventory(p.getInventory());
            cleanInventory(p.getEnderChest());
            scan(p);
        }, 5L);
    }

    // ------------------------------------------------------------------ they can't be kept safe: no storing, dropping or trading

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isFragment(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text(SmallCaps.of("A Halo fragment can't be dropped")).color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /** Admins hide fragments: before the hunt in any mode, during it only in creative. Nobody else can store one. */
    private boolean mayStore(Player p) { return p.hasPermission(ADMIN) && (!active || p.getGameMode() == GameMode.CREATIVE); }

    /** The gem guards' rules, for fragments: no container, workstation or bundle however it's moved (click, key, shift). */
    @EventHandler(priority = EventPriority.LOW)
    public void onStore(InventoryClickEvent e) {
        if (e.getClickedInventory() == null || !(e.getWhoClicked() instanceof Player p)) return;
        Inventory topInv = e.getView().getTopInventory();
        if (GemListener.pluginMenu(topInv)) return; // our own menus cancel their clicks (the kit editor refuses fragments)
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        boolean bundle = isBundle(cursor) && isFragment(cur) || isBundle(cur) && isFragment(cursor);
        org.bukkit.event.inventory.InventoryType top = topInv.getType();
        boolean guarded = GemListener.storage(top) && !mayStore(p) || GemListener.workstation(top);
        boolean into = false;
        if (guarded && e.getClickedInventory().equals(topInv)) {
            into = isFragment(cur) || isFragment(cursor)
                    || e.getHotbarButton() >= 0 && isFragment(p.getInventory().getItem(e.getHotbarButton()))
                    || e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && isFragment(p.getInventory().getItemInOffHand());
        } else if (guarded && e.isShiftClick() && top != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            into = isFragment(cur);
        }
        if (!into && !bundle) return;
        e.setCancelled(true);
        p.sendActionBar(Component.text(SmallCaps.of(bundle ? "A Halo fragment can't go in a bundle" : "A Halo fragment can't be stored or used"))
                .color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    private static boolean isBundle(ItemStack it) { return it != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta; }

    @EventHandler(priority = EventPriority.LOW)
    public void onStoreDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!isFragment(e.getOldCursor()) || !(e.getWhoClicked() instanceof Player p)) return;
        Inventory topInv = e.getView().getTopInventory();
        if (GemListener.pluginMenu(topInv)) return;
        org.bukkit.event.inventory.InventoryType top = topInv.getType();
        if (!(GemListener.storage(top) && !mayStore(p) || GemListener.workstation(top))) return;
        for (int raw : e.getRawSlots()) if (raw < topInv.getSize()) { e.setCancelled(true); return; }
    }

    /** No item frame, allay, armour stand, decorated pot or shelf takes one. */
    @EventHandler(priority = EventPriority.LOW)
    public void onToEntity(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof org.bukkit.entity.ItemFrame || e.getRightClicked() instanceof org.bukkit.entity.Allay)) return;
        ItemStack hand = e.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND ? e.getPlayer().getInventory().getItemInOffHand() : e.getPlayer().getInventory().getItemInMainHand();
        if (isFragment(hand)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onToStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) { if (isFragment(e.getPlayerItem())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onToBlock(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && GemListener.handInBlock(e.getClickedBlock().getType()) && isFragment(e.getItem())) e.setCancelled(true);
    }

    /** Never crafted, smithed or used up in an anvil (a fragment is paper: books, maps, rockets). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (isFragment(it)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmith(org.bukkit.event.inventory.PrepareSmithingEvent e) {
        for (ItemStack it : e.getInventory().getContents()) if (isFragment(it) && it != e.getResult()) { e.setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(org.bukkit.event.inventory.PrepareAnvilEvent e) {
        if (isFragment(e.getInventory().getItem(0)) || isFragment(e.getInventory().getItem(1))) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHopper(InventoryPickupItemEvent e) { if (isFragment(e.getItem().getItemStack())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onHopperMove(InventoryMoveItemEvent e) { if (isFragment(e.getItem())) e.setCancelled(true); }

    // ------------------------------------------------------------------ deaths and logouts: they drop where you were

    /** Every fragment drops at the death spot, whatever keeps the inventory. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!e.getKeepInventory()) return; // vanilla drops them with the rest (onSpawn looks after them)
        dropAll(p, p.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (claim != null && claim.player.equals(p.getUniqueId())) { finishClaim(false); return; } // they get it now
        dropAll(p, p.getLocation());
        scanSoon.remove(p.getUniqueId());
    }

    private void dropAll(Player p, Location at) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            if (!isFragment(c[i])) continue;
            ItemStack it = c[i].clone();
            p.getInventory().setItem(i, null);
            if (!valid(it)) continue;
            protect(at.getWorld().dropItemNaturally(at, it));
        }
    }

    // ------------------------------------------------------------------ on the ground: no despawn, lava, fire or loss

    /** A void fragment never appears; a good one is looked after from the moment it lands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        if (!isFragment(e.getEntity().getItemStack())) return;
        if (!valid(e.getEntity().getItemStack())) { e.setCancelled(true); return; }
        protect(e.getEntity());
    }

    private void protect(Item it) {
        if (it == null) return;
        if (!valid(it.getItemStack())) { it.remove(); return; }
        it.setUnlimitedLifetime(true);
        it.setInvulnerable(true); // no cactus, explosions; fire and lava can't touch it anyway (it floats on lava)
        it.setGlowing(false);
        groundItems.add(it.getUniqueId());
        HaloMath.Fragment f = read(it.getItemStack());
        Location l = it.getLocation();
        where.put(f.id(), "ground " + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDespawn(ItemDespawnEvent e) { if (isFragment(e.getEntity().getItemStack())) e.setCancelled(true); }

    /** A fragment on the ground can't be destroyed; the player being haloed can't be hit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item it && isFragment(it.getItemStack())) e.setCancelled(true);
        else if (claim != null && e.getEntity().getUniqueId().equals(claim.player)) e.setCancelled(true);
    }

    /** Fallen out of the world: back at that world's spawn. */
    @EventHandler
    public void onRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item it) || !isFragment(it.getItemStack()) || e.getCause() != EntityRemoveEvent.Cause.OUT_OF_WORLD) return;
        ItemStack copy = it.getItemStack().clone();
        World w = it.getWorld();
        groundItems.remove(it.getUniqueId());
        if (valid(copy) && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> protect(w.dropItem(w.getSpawnLocation(), copy)));
    }

    // ------------------------------------------------------------------ every 2 ticks: shrines, the void, beams

    private void tick() {
        tick++;
        for (Reliquary r : reliquaries.values()) animate(r);
        if (tick % 5 != 0) return;
        for (UUID id : new ArrayList<>(groundItems)) {
            if (!(Bukkit.getEntity(id) instanceof Item it) || !it.isValid()) { groundItems.remove(id); continue; }
            World w = it.getWorld();
            if (HaloMath.inVoid(it.getLocation().getY(), w.getMinHeight())) { it.teleport(w.getSpawnLocation()); continue; }
            if (tick % 10 == 0 && HaloMath.beam(it.getTicksLived(), cfgI("beam-after-seconds", 60))) { // a faint gold beam: a lost one can be found
                Location base = it.getLocation();
                for (int i = 0; i < 10; i++)
                    w.spawnParticle(Particle.DUST, base.clone().add(0, 0.5 + i * 1.2, 0), 1, 0.03, 0.2, 0.03, 0, new Particle.DustOptions(GOLD, 0.8f));
            }
        }
    }

    // ------------------------------------------------------------------ the reliquary

    private boolean spawned(Reliquary r) {
        return r.frag != null && Bukkit.getEntity(r.frag) instanceof ItemDisplay d && d.isValid();
    }

    /** The shrine, the floating fragment and the click box, if its chunk is loaded and they aren't there yet. */
    private void spawn(Reliquary r) {
        Location b = r.block();
        if (b == null || !b.getWorld().isChunkLoaded(r.x >> 4, r.z >> 4) || spawned(r)) return;
        despawn(r); // anything half there goes first: never two of anything
        if (b.getBlock().getType() != Material.BARRIER) b.getBlock().setType(Material.BARRIER, false);
        World w = b.getWorld();
        Location c = b.clone().add(0.5, 0.5, 0.5);
        ItemStack shrine = new ItemStack(Material.PAPER);
        ItemMeta sm = shrine.getItemMeta();
        sm.setItemModel(new NamespacedKey("blissgems", "halo/reliquary"));
        shrine.setItemMeta(sm);
        r.shrine = w.spawn(c, ItemDisplay.class, d -> setUp(d, shrine, r.n)).getUniqueId();
        r.frag = w.spawn(c.clone().add(0, 0.95, 0), ItemDisplay.class, d -> {
            setUp(d, fragment(r.n, r.id), r.n);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        }).getUniqueId();
        r.hit = w.spawn(c.clone().add(0, 0.55, 0), Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.8f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(KEY_RELIQ, PersistentDataType.INTEGER, r.n);
        }).getUniqueId();
    }

    private static void setUp(ItemDisplay d, ItemStack it, int n) {
        d.setItemStack(it);
        d.setPersistent(false); // never saved with the chunk: rebuilt from halo.yml, never doubled
        d.setBillboard(Display.Billboard.FIXED);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.getPersistentDataContainer().set(KEY_RELIQ, PersistentDataType.INTEGER, n);
    }

    private void despawn(Reliquary r) {
        for (UUID id : new UUID[] {r.shrine, r.frag, r.hit}) {
            if (id == null) continue;
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.remove();
        }
        r.shrine = r.frag = r.hit = null;
    }

    /** The fragment spins slowly and bobs; small gold sparkles only for players within 8 blocks (no beam, no glow). */
    private void animate(Reliquary r) {
        if (!(r.frag != null && Bukkit.getEntity(r.frag) instanceof ItemDisplay d)) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(new Vector3f(0, (float) HaloMath.bob(tick), 0), new Quaternionf().rotateY(HaloMath.spin(tick)),
                new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        if (tick % 5 != 0) return;
        Location at = d.getLocation();
        double range = cfgI("sparkle-range", 8);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > range * range) continue;
            p.spawnParticle(Particle.WAX_OFF, at, 2, 0.25, 0.25, 0.25, 0.4);
            p.spawnParticle(Particle.END_ROD, at, 1, 0.2, 0.2, 0.2, 0.0);
        }
    }

    /** Right-clicking the floating fragment: it flies into your hand and the shrine crumbles. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTake(PlayerInteractEntityEvent e) {
        Integer n = e.getRightClicked().getPersistentDataContainer().get(KEY_RELIQ, PersistentDataType.INTEGER);
        if (n == null) return;
        e.setCancelled(true);
        Reliquary r = reliquaries.get(n);
        if (r == null || !(e.getRightClicked() instanceof Interaction)) return;
        Player p = e.getPlayer();
        if (!active && !p.hasPermission(ADMIN)) { // hidden ahead of the hunt: nobody takes it early
            p.sendActionBar(Component.text(SmallCaps.of("The Halo hasn't fallen yet")).color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
            return;
        }
        Location at = r.block().add(0.5, 0.5, 0.5);
        reliquaries.remove(n);
        removeReliquary(r, false);
        ItemStack frag = fragment(r.n, r.id);
        if (p.getInventory().getItemInMainHand().getType().isAir()) p.getInventory().setItemInMainHand(frag);
        else for (ItemStack left : p.getInventory().addItem(frag).values()) protect(p.getWorld().dropItemNaturally(p.getLocation(), left));
        GemSounds.playTo(plugin, "halo.chime", p);
        GemSounds.play(plugin, "halo.crumble", at);
        at.getWorld().spawnParticle(Particle.BLOCK, at, 30, 0.3, 0.3, 0.3, 0, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
        at.getWorld().spawnParticle(Particle.BLOCK, at, 20, 0.3, 0.3, 0.3, 0, Material.GOLD_BLOCK.createBlockData());
        where.put(r.id, "holder:" + p.getName());
        save();
        scanSoon(p);
    }

    /** The shrine's barrier can't be broken while it holds a fragment. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BARRIER) return;
        for (Reliquary r : reliquaries.values())
            if (r.world.equals(b.getWorld().getName()) && r.x == b.getX() && r.y == b.getY() && r.z == b.getZ()) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(org.bukkit.event.block.BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (isShrine(b)) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(org.bukkit.event.block.BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (isShrine(b)) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlast(org.bukkit.event.entity.EntityExplodeEvent e) { e.blockList().removeIf(this::isShrine); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBlast(org.bukkit.event.block.BlockExplodeEvent e) { e.blockList().removeIf(this::isShrine); }

    private boolean isShrine(Block b) {
        for (Reliquary r : reliquaries.values()) if (r.world.equals(b.getWorld().getName()) && r.x == b.getX() && r.y == b.getY() && r.z == b.getZ()) return true;
        return false;
    }

    // ------------------------------------------------------------------ the claim

    /** Three fragments: held where they stand, the Auratus lowered on four gold chains into their hand. */
    private void startClaim(Player p) {
        Claim c = new Claim();
        c.player = p.getUniqueId();
        c.spot = p.getLocation().clone();
        claim = c;
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) if (isFragment(inv[i])) p.getInventory().setItem(i, null); // spent
        bells(); // unhittable for the scene: onDamage cancels (no Invulnerable flag a crash could leave on them)
        if (plugin.gold() != null) plugin.gold().flashAll(cfgI("flash-ticks", 20));
        String who = plugin.shownName(p);
        Title t = Title.title(LegacyComponentSerializer.legacySection().deserialize("§6" + who + " §e" + SmallCaps.of(cfg("claim-title", "has been haloed"))),
                Component.empty(), Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) o.showTitle(t);
        Location top = c.spot.clone().add(0, 1.4 + cfgI("chain-drop", 12), 0);
        World w = c.spot.getWorld();
        c.auratus = w.spawn(top, ItemDisplay.class, d -> {
            d.setItemStack(plugin.gems().createMythic(GemType.AURATUS));
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);
        });
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + Math.PI / 4;
            Vector off = new Vector(Math.cos(a) * 0.45, 0, Math.sin(a) * 0.45);
            c.chains.addAll(AuratusGem.hangingChain(top.clone().add(off.clone().multiply(3)).add(0, 14, 0), top.clone().add(off), 0.8f));
        }
        int total = Math.max(20, cfgI("claim-seconds", 3) * 20);
        double step = (top.getY() - (c.spot.getY() + 1.2)) / total;
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            c.age++;
            Player h = Bukkit.getPlayer(c.player);
            if (h == null) { finishClaim(true); return; }
            if (h.getLocation().distanceSquared(c.spot) > 0.04) h.teleport(c.spot); // held in place
            Vector down = new Vector(0, -step, 0);
            if (c.auratus.isValid()) c.auratus.teleport(c.auratus.getLocation().add(down));
            for (ItemDisplay d : c.chains) if (d.isValid()) d.teleport(d.getLocation().add(down));
            if (c.age % 4 == 0) w.spawnParticle(Particle.DUST, c.auratus.getLocation(), 6, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(GOLD, 1.3f));
            if (c.age >= total) finishClaim(false);
        }, 1L, 1L);
    }

    /** The Auratus is theirs (or, aborted, nobody's yet), the scene goes, the hunt ends and every other fragment vanishes. */
    private void finishClaim(boolean aborted) {
        Claim c = claim;
        if (c == null) return;
        claim = null;
        if (c.task != null) c.task.cancel();
        if (c.auratus != null && c.auratus.isValid()) c.auratus.remove();
        for (ItemDisplay d : c.chains) if (d.isValid()) d.remove();
        Player p = Bukkit.getPlayer(c.player);
        if (!aborted && p != null) {
            ItemStack auratus = plugin.gems().createMythic(GemType.AURATUS);
            if (p.getInventory().getItemInMainHand().getType().isAir()) p.getInventory().setItemInMainHand(auratus);
            else for (ItemStack left : p.getInventory().addItem(auratus).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            GemSounds.play(plugin, "halo.chime", p.getLocation());
            awarded = true;
        }
        endHunt();
    }

    boolean active() { return active; }
}
