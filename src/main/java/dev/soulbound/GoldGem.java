package dev.soulbound;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Gold Gem (gold: in config.yml, /goldgem). An admin turns a player's gem into a Gold Gem: their own gem is its CORE.
 * It's a gem item whose type is its ACTIVE gem (Tier 2, full energy), so every power, passive, control and action bar
 * works exactly as holding that gem. Its holder MARKS up to 3 Tier 2 players (red name, glow, tab and chat); killing a
 * marked player REPURPOSES them: 5 s frozen while gold chains hold their gem in the sky, then they die and their gem is
 * socketed (one of each type, 8 in all). Sneak + F cycles the active gem (or /goldgem switch picks one). The holder dying
 * sends every stolen gem home. With all 8, a ritual at the site awakens it: GOLD mode, with Snap and Golden Drill.
 * Saved to gold.yml: holders, cores, sockets, held gems, marks, gems waiting for offline owners, the ritual site.
 */
final class GoldGem implements Listener {
    private static final String TEAM = "sg_gold_marked";
    private static final Color GOLD_DUST = Color.fromRGB(0xFFC846), PALE = Color.fromRGB(0xFFF0B4);

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final NamespacedKey stunKey;
    private final Map<UUID, GoldMath.State> states = new HashMap<>(); // holder -> their Gold Gem
    private final Map<UUID, List<String>> pending = new HashMap<>(); // owner -> gems waiting for them (offline)
    private final Map<UUID, String> prevTeams = new HashMap<>();      // marked player -> the team they were on
    private final Set<UUID> markedNow = ConcurrentHashMap.newKeySet(); // read by async chat
    private final Set<UUID> tellTaken = new HashSet<>();               // told on respawn that their gem was taken
    private final Map<UUID, Object[]> lastHolderHit = new HashMap<>(); // victim -> {holder, time}
    private final Map<UUID, Scene> scenes = new HashMap<>();           // victim -> their repurpose
    private final Map<UUID, Ritual> rituals = new HashMap<>();         // holder -> the ritual running
    private final Map<UUID, Long> switchReady = new HashMap<>(), snapReady = new HashMap<>(), drillReady = new HashMap<>();
    private final Map<UUID, Long> bannerUntil = new HashMap<>(), noFallUntil = new HashMap<>();
    private final Random rnd = new Random();
    private Location ritualSite;

    GoldGem(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gold.yml");
        this.stunKey = new NamespacedKey(plugin, "gold_stun");
        GemManager.goldLook = this::refreshLook;
        load();
    }

    private String cfg(String path, String def) { return plugin.getConfig().getString("gold." + path, def); }

    private double cfgD(String path, double def) { return plugin.getConfig().getDouble("gold." + path, def); }

    private boolean cfgB(String path, boolean def) { return plugin.getConfig().getBoolean("gold." + path, def); }

    private GemManager gems() { return plugin.gems(); }

    /** Plugin disable: every repurpose finishes now, every ritual stops, everything is saved. */
    void shutdown() {
        for (Scene s : new ArrayList<>(scenes.values())) finish(s, true);
        for (Ritual r : new ArrayList<>(rituals.values())) r.task.cancel();
        rituals.clear();
        save();
    }

    // ------------------------------------------------------------------ items and saving

    static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

    static ItemStack decode(String s) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }

    GoldMath.State stateOf(Player p) { return p == null ? null : states.get(p.getUniqueId()); }

    boolean isHolder(Player p) { return stateOf(p) != null; }

    private static GemType type(String name) { return GemType.match(name); }

    private static TextColor colourOf(GemType t) {
        TextColor c = LegacyComponentSerializer.legacySection().deserialize(t.tint() + "x").color();
        return c == null ? TextColor.color(0xFFFFFF) : c;
    }

    /** A new Gold Gem item for this state: the active gem's type (Tier 2, full energy; GOLD mode: passives only). */
    private ItemStack make(GoldMath.State s) {
        GemType active = type(s.goldMode() ? s.coreType : s.active);
        ItemStack it = gems().createGem(active, 2, s.goldMode() ? 2 : 10); // energy 2 in GOLD mode: no other gem's powers
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(gems().keyGold, PersistentDataType.STRING, s.holder.toString());
        it.setItemMeta(m);
        applyLook(it, s);
        return it;
    }

    /** GemManager asks: rebuild this Gold Gem's look and lore from its state. */
    private void refreshLook(ItemStack it) {
        String id = it.getItemMeta().getPersistentDataContainer().get(gems().keyGold, PersistentDataType.STRING);
        GoldMath.State s;
        try { s = id == null ? null : states.get(UUID.fromString(id)); } catch (IllegalArgumentException e) { s = null; }
        if (s != null) applyLook(it, s);
    }

    private void applyLook(ItemStack it, GoldMath.State s) {
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(null);
        m.setItemModel(new NamespacedKey("blissgems", GoldMath.model(s.types(), s.awakened)));
        m.setDisplayName(s.awakened ? "§6§lAwakened Gold Gem" : "§6§lGold Gem");
        List<String> lore = new ArrayList<>();
        GemType core = type(s.coreType);
        lore.add("§7" + SmallCaps.of("Core") + ": " + core.tint() + core.shown());
        StringBuilder sock = new StringBuilder();
        for (String t : s.types()) {
            if (t.equals(s.coreType)) continue;
            GemType g = type(t);
            if (sock.length() > 0) sock.append("§8, ");
            sock.append(g.tint()).append(g.shown());
        }
        lore.add("§7" + SmallCaps.of("Socketed") + ": " + (sock.length() == 0 ? "§8" + SmallCaps.of("none") : sock));
        lore.add("§7" + SmallCaps.of("Active") + ": " + (s.goldMode() ? "§6" + SmallCaps.of("Gold") : type(s.active).tint() + type(s.active).shown()));
        lore.add("");
        lore.add("§8" + SmallCaps.of("Sneak + F: next gem") + " §8· §7/goldgem switch");
        if (s.awakened) {
            lore.add("§6✦ " + SmallCaps.of("Awakened"));
            lore.add("§7" + SmallCaps.of("Gold mode") + ": §fsneak + left-click §6Snap§7, sneak + right-click §6Golden Drill");
        } else if (s.full()) lore.add("§6" + SmallCaps.of("All eight: awaken it at the ritual site"));
        lore.add("§8" + SmallCaps.of("Soulbound · one of a kind"));
        m.setLore(lore);
        it.setItemMeta(m);
    }

    /** The holder's Gold Gem in their inventory (or null). */
    private int goldSlot(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (gems().isGold(inv.getItem(i))) return i;
        return -1;
    }

    /** Their Gold Gem item is rebuilt from its state; a Gold Gem that isn't theirs (or no longer exists) is taken away. */
    void sync(Player p) {
        if (p == null || !p.isOnline()) return;
        var inv = p.getInventory();
        GoldMath.State s = stateOf(p);
        boolean placed = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!gems().isGold(it)) continue;
            String id = it.getItemMeta().getPersistentDataContainer().get(gems().keyGold, PersistentDataType.STRING);
            if (s == null || placed || !s.holder.toString().equals(id)) { inv.setItem(i, null); continue; } // a stale or copied Gold Gem
            inv.setItem(i, make(s));
            placed = true;
        }
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection hs = y.getConfigurationSection("holders");
        if (hs != null) for (String k : hs.getKeys(false)) {
            ConfigurationSection h = hs.getConfigurationSection(k);
            try {
                GoldMath.State s = new GoldMath.State(UUID.fromString(k), h.getString("name", "?"), h.getString("core-type"), h.getString("core-item"));
                s.active = h.getString("active", s.coreType);
                s.awakened = h.getBoolean("awakened", false);
                for (Map<?, ?> m : h.getMapList("sockets")) s.sockets.add(socket(m));
                for (Map<?, ?> m : h.getMapList("held")) s.held.add(socket(m));
                ConfigurationSection ms = h.getConfigurationSection("marks");
                if (ms != null) for (String v : ms.getKeys(false)) {
                    s.marks.put(UUID.fromString(v), ms.getLong(v + ".at"));
                    String prev = ms.getString(v + ".prev-team");
                    if (prev != null) prevTeams.put(UUID.fromString(v), prev);
                    markedNow.add(UUID.fromString(v));
                }
                if (!s.cycle().contains(s.active)) s.active = s.coreType;
                states.put(s.holder, s);
            } catch (RuntimeException e) { plugin.getLogger().warning("gold.yml: skipping holder " + k + " (" + e.getMessage() + ")"); }
        }
        ConfigurationSection ps = y.getConfigurationSection("pending");
        if (ps != null) for (String k : ps.getKeys(false)) pending.put(UUID.fromString(k), new ArrayList<>(ps.getStringList(k)));
        for (String t : y.getStringList("tell-taken")) tellTaken.add(UUID.fromString(t));
        if (y.isConfigurationSection("ritual") && Bukkit.getWorld(y.getString("ritual.world", "")) != null)
            ritualSite = new Location(Bukkit.getWorld(y.getString("ritual.world")), y.getInt("ritual.x"), y.getInt("ritual.y"), y.getInt("ritual.z"));
    }

    private static GoldMath.Socket socket(Map<?, ?> m) {
        return new GoldMath.Socket(String.valueOf(m.get("type")), UUID.fromString(String.valueOf(m.get("owner"))), String.valueOf(m.get("name")), String.valueOf(m.get("item")));
    }

    private static Map<String, Object> map(GoldMath.Socket s) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", s.type());
        m.put("owner", s.owner().toString());
        m.put("name", s.ownerName());
        m.put("item", s.item());
        return m;
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (GoldMath.State s : states.values()) {
            String p = "holders." + s.holder + ".";
            y.set(p + "name", s.holderName);
            y.set(p + "core-type", s.coreType);
            y.set(p + "core-item", s.coreItem);
            y.set(p + "active", s.active);
            y.set(p + "awakened", s.awakened);
            y.set(p + "sockets", s.sockets.stream().map(GoldGem::map).toList());
            y.set(p + "held", s.held.stream().map(GoldGem::map).toList());
            for (Map.Entry<UUID, Long> m : s.marks.entrySet()) {
                y.set(p + "marks." + m.getKey() + ".at", m.getValue());
                y.set(p + "marks." + m.getKey() + ".prev-team", prevTeams.get(m.getKey()));
            }
        }
        for (Map.Entry<UUID, List<String>> e : pending.entrySet()) if (!e.getValue().isEmpty()) y.set("pending." + e.getKey(), e.getValue());
        y.set("tell-taken", tellTaken.stream().map(UUID::toString).toList());
        if (ritualSite != null) {
            y.set("ritual.world", ritualSite.getWorld().getName());
            y.set("ritual.x", ritualSite.getBlockX());
            y.set("ritual.y", ritualSite.getBlockY());
            y.set("ritual.z", ritualSite.getBlockZ());
        }
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save gold.yml: " + e.getMessage()); }
    }

    /** The owner of gems added by the admin test commands: nobody, so they never go "home" anywhere. */
    static final UUID TEST_OWNER = new UUID(0L, 0L);

    /** A gem back to its owner: in their inventory now, or kept until they next join. */
    private void giveBack(UUID owner, String item) {
        if (TEST_OWNER.equals(owner)) return; // a test gem: it just goes
        Player o = Bukkit.getPlayer(owner);
        tellTaken.remove(owner);
        if (o != null) {
            for (ItemStack left : o.getInventory().addItem(decode(item)).values()) o.getWorld().dropItemNaturally(o.getLocation(), left);
            o.sendMessage(ChatColor.GOLD + SmallCaps.of("Your gem came home."));
        } else pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);
    }

    // ------------------------------------------------------------------ /goldgem give, remove

    private int standardGemSlot(Player p) {
        var inv = p.getInventory();
        int hand = inv.getHeldItemSlot();
        ItemStack h = inv.getItem(hand);
        if (gems().isGem(h) && !gems().isMythic(h) && !gems().isGold(h)) return hand;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (gems().isGem(it) && !gems().isMythic(it) && !gems().isGold(it)) return i;
        }
        return -1;
    }

    String give(Player target) {
        if (states.containsKey(target.getUniqueId())) return target.getName() + " already holds a Gold Gem.";
        if (states.size() >= Math.max(1, plugin.getConfig().getInt("gold.max-on-server", 1))) return "A Gold Gem already exists (gold.max-on-server).";
        int slot = standardGemSlot(target);
        if (slot < 0) return target.getName() + " has no gem to make the core.";
        ItemStack core = target.getInventory().getItem(slot);
        GoldMath.State s = new GoldMath.State(target.getUniqueId(), target.getName(), gems().getType(core).name(), encode(core));
        states.put(target.getUniqueId(), s);
        for (GoldMath.State o : states.values()) if (o != s && o.marks.containsKey(target.getUniqueId())) unmark(o, target.getUniqueId());
        target.getInventory().setItem(slot, make(s));
        target.sendMessage(ChatColor.GOLD + SmallCaps.of("Your gem is the core of a Gold Gem now."));
        GemSounds.play(plugin, "gold.awaken", target.getLocation());
        save();
        return null;
    }

    String remove(OfflinePlayer target) {
        GoldMath.State s = states.remove(target.getUniqueId());
        if (s == null) return (target.getName() == null ? "They" : target.getName()) + " don't hold a Gold Gem.";
        for (UUID v : new ArrayList<>(s.marks.keySet())) unmarkVisuals(v);
        s.marks.clear();
        for (GoldMath.Socket g : s.release(false)) giveBack(g.owner(), g.item());
        Ritual r = rituals.remove(s.holder);
        if (r != null) r.task.cancel();
        Player p = target.getPlayer();
        if (p != null) {
            int slot = goldSlot(p);
            if (slot >= 0) p.getInventory().setItem(slot, decode(s.coreItem)); else giveBack(p.getUniqueId(), s.coreItem);
            sync(p); // any other copy goes
        } else pending.computeIfAbsent(s.holder, k -> new ArrayList<>()).add(s.coreItem); // their Gold Gem is taken away when they join
        save();
        return null;
    }

    // ------------------------------------------------------------------ admin test commands (soulbound.admin)

    /** /goldgem socket <gem>: a test gem straight into your Gold Gem (the normal rules: no duplicates, not the core). */
    String testSocket(Player p, String gem) {
        GoldMath.State s = stateOf(p);
        if (s == null) return "You don't hold a Gold Gem.";
        GemType t = GemType.match(gem);
        if (t == null || t.isMythic()) return "No gem called " + gem + ".";
        if (t.name().equals(s.coreType)) return t.shown() + " is your core.";
        if (!s.canSocket(t.name())) return t.shown() + " is already socketed.";
        s.take(t.name(), TEST_OWNER, "Test", encode(gems().createGem(t, 2, 10)));
        sync(p);
        save();
        return null;
    }

    /** /goldgem unsocket <gem>: out of your Gold Gem (a real player's gem goes home to them). */
    String testUnsocket(Player p, String gem) {
        GoldMath.State s = stateOf(p);
        if (s == null) return "You don't hold a Gold Gem.";
        GemType t = GemType.match(gem);
        if (t == null) return "No gem called " + gem + ".";
        if (t.name().equals(s.coreType)) return "The core can't come out.";
        GoldMath.Socket found = null;
        for (GoldMath.Socket g : s.sockets) if (g.type().equals(t.name())) found = g;
        if (found == null) return t.shown() + " isn't socketed.";
        s.sockets.remove(found);
        giveBack(found.owner(), found.item());
        if (!s.cycle().contains(s.active)) s.active = s.coreType;
        sync(p);
        save();
        return null;
    }

    /** /goldgem awaken | unawaken: the awakened state without the ritual. */
    String testAwaken(Player p, boolean on) {
        GoldMath.State s = stateOf(p);
        if (s == null) return "You don't hold a Gold Gem.";
        s.awakened = on;
        if (!s.cycle().contains(s.active)) s.active = s.coreType;
        sync(p);
        save();
        return null;
    }

    /** /goldgem testrepurpose [gem]: the whole repurpose on a Mannequin dummy holding that gem (no overlay: it has no screen). */
    String testRepurpose(Player p, String gem) {
        GoldMath.State s = stateOf(p);
        if (s == null) return "You don't hold a Gold Gem.";
        GemType t;
        if (gem == null) {
            List<GemType> open = new ArrayList<>();
            for (String o : GoldMath.ORDER) if (s.canSocket(o)) open.add(type(o));
            if (open.isEmpty()) for (String o : GoldMath.ORDER) open.add(type(o));
            t = open.get(rnd.nextInt(open.size()));
        } else {
            t = GemType.match(gem);
            if (t == null || t.isMythic()) return "No gem called " + gem + ".";
        }
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
        at.setYaw(p.getLocation().getYaw() + 180);
        at.setPitch(0);
        ItemStack held = gems().createGem(t, 2, 10);
        org.bukkit.entity.LivingEntity dummy;
        if (MannequinBody.available()) {
            dummy = at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, m -> {
                m.setImmovable(true);
                m.setDescription(Component.empty());
            });
        } else dummy = at.getWorld().spawn(at, org.bukkit.entity.ArmorStand.class);
        dummy.setPersistent(false);
        dummy.setInvulnerable(true);
        dummy.setSilent(true);
        dummy.customName(Component.text("Dummy"));
        dummy.setCustomNameVisible(true);
        if (dummy.getEquipment() != null) dummy.getEquipment().setItemInMainHand(held.clone());
        Scene sc = new Scene(dummy, s.holder, held);
        sc.dummy = true;
        scenes.put(dummy.getUniqueId(), sc);
        if (dummy.getEquipment() != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (dummy.isValid()) dummy.getEquipment().setItemInMainHand(null); }, 1L); // torn out
        play(sc, dummy.getWorld());
        return null;
    }

    /** /goldgem testsnap: Snap, with you as the only one it can hit (you see the flash and the gem disable). No cooldown. */
    void testSnap(Player p) {
        snapEffect(p, List.of(p.getUniqueId()), true);
    }

    // ------------------------------------------------------------------ marks

    private int tierOf(Player p) {
        int slot = standardGemSlot(p);
        return slot < 0 ? 0 : gems().getTier(p.getInventory().getItem(slot));
    }

    String mark(Player holder, Player target) {
        GoldMath.State s = stateOf(holder);
        if (s == null) return "Only the Gold Gem holder can mark.";
        if (states.containsKey(target.getUniqueId())) return target.getName() + " holds a Gold Gem.";
        switch (GoldMath.canMark(s, target.getUniqueId(), tierOf(target), (int) cfgD("marks.slots", 3))) {
            case SELF -> { return "You can't mark yourself."; }
            case ALREADY -> { return target.getName() + " is already marked."; }
            case NOT_TIER_2 -> { return "Only Tier 2 gem users can be marked."; }
            case FULL -> { return "Every mark is in use (" + (int) cfgD("marks.slots", 3) + ")."; }
            default -> { }
        }
        s.marks.put(target.getUniqueId(), System.currentTimeMillis());
        markVisuals(target);
        GemSounds.playTo(plugin, "gold.mark", holder);
        save();
        return null;
    }

    String unmark(Player holder, String name) {
        GoldMath.State s = stateOf(holder);
        if (s == null) return "Only the Gold Gem holder can unmark.";
        OfflinePlayer t = Bukkit.getOfflinePlayer(name);
        Long at = s.marks.get(t.getUniqueId());
        if (at == null) return name + " isn't marked.";
        long wait = GoldMath.unmarkWait(at, System.currentTimeMillis(), (long) (cfgD("marks.lock-minutes", 30) * 60_000));
        if (wait > 0) return "That mark can't come off for " + HudStatus.time(wait) + ".";
        unmark(s, t.getUniqueId());
        save();
        return null;
    }

    List<String> marks(Player holder) {
        List<String> out = new ArrayList<>();
        GoldMath.State s = stateOf(holder);
        if (s == null) return out;
        long lock = (long) (cfgD("marks.lock-minutes", 30) * 60_000), now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> m : s.marks.entrySet()) {
            String n = Bukkit.getOfflinePlayer(m.getKey()).getName();
            long wait = GoldMath.unmarkWait(m.getValue(), now, lock);
            out.add((n == null ? m.getKey().toString() : n) + (wait > 0 ? " (locked " + HudStatus.time(wait) + ")" : ""));
        }
        return out;
    }

    private void unmark(GoldMath.State s, UUID victim) {
        s.marks.remove(victim);
        boolean still = false;
        for (GoldMath.State o : states.values()) if (o.marks.containsKey(victim)) still = true;
        if (!still) unmarkVisuals(victim);
    }

    private Team markTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM);
        if (t == null) {
            t = sb.registerNewTeam(TEAM);
            t.color(NamedTextColor.RED);
        }
        return t;
    }

    /** Red: name tag and glow (their own team, their old one remembered), the tab list, chat. */
    private void markVisuals(Player p) {
        markedNow.add(p.getUniqueId());
        if (cfgB("marks.red-name", true) || cfgB("marks.glow", true)) {
            Team cur = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
            if (cur != null && !cur.getName().equals(TEAM)) prevTeams.put(p.getUniqueId(), cur.getName());
            markTeam().addEntry(p.getName());
        }
        if (cfgB("marks.glow", true)) p.setGlowing(true);
        if (cfgB("marks.red-tab", true)) p.playerListName(Component.text(p.getName(), NamedTextColor.RED));
    }

    private void unmarkVisuals(UUID id) {
        markedNow.remove(id);
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(TEAM);
        if (op.getName() != null && t != null) t.removeEntry(op.getName());
        String prev = prevTeams.remove(id);
        if (prev != null && op.getName() != null) {
            Team back = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(prev);
            if (back != null) back.addEntry(op.getName());
        }
        Player p = op.getPlayer();
        if (p != null) {
            p.setGlowing(false);
            p.playerListName(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!markedNow.contains(e.getPlayer().getUniqueId()) || !cfgB("marks.red-chat", true)) return;
        var before = e.renderer();
        e.renderer((source, name, message, viewer) -> before.render(source, name.color(NamedTextColor.RED), message, viewer));
    }

    // ------------------------------------------------------------------ switching

    /** Sneak + F with the Gold Gem in your main hand: the next gem (F alone swaps hands as normal). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (scenes.containsKey(p.getUniqueId())) { e.setCancelled(true); return; }
        if (!p.isSneaking() || !gems().isGold(e.getOffHandItem()) || stateOf(p) == null) return;
        e.setCancelled(true);
        switchTo(p, stateOf(p).next());
    }

    void switchTo(Player p, String entry) {
        GoldMath.State s = stateOf(p);
        if (s == null || !s.cycle().contains(entry) || entry.equals(s.active)) return;
        long now = System.currentTimeMillis();
        if (switchReady.getOrDefault(p.getUniqueId(), 0L) > now) return;
        switchReady.put(p.getUniqueId(), now + (long) (cfgD("switch-cooldown-seconds", 2) * 1000));
        s.active = entry;
        sync(p);
        bannerUntil.put(p.getUniqueId(), now + 1500);
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(banner(s)));
        GemSounds.playTo(plugin, "gold.switch", p);
        save();
    }

    private static String banner(GoldMath.State s) {
        if (s.goldMode()) return "§6✦ " + SmallCaps.of("Gold");
        GemType t = type(s.active);
        return "§6" + SmallCaps.of("Gold Gem") + " §8· " + t.tint() + t.shown();
    }

    private static final class SwitchMenu implements InventoryHolder {
        final List<String> entries = new ArrayList<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** /goldgem switch: pick the active gem. */
    void openSwitch(Player p) {
        GoldMath.State s = stateOf(p);
        if (s == null) return;
        SwitchMenu h = new SwitchMenu();
        h.inv = Bukkit.createInventory(h, 9, Component.text("Gold Gem"));
        for (String e : s.cycle()) {
            ItemStack icon = GoldMath.GOLD.equals(e) ? new ItemStack(Material.GOLD_BLOCK) : gems().createGem(type(e), 2, 10);
            ItemMeta m = icon.getItemMeta();
            if (GoldMath.GOLD.equals(e)) m.setDisplayName("§6§l" + SmallCaps.of("Gold"));
            if (e.equals(s.active)) m.setLore(List.of("§a" + SmallCaps.of("Active")));
            icon.setItemMeta(m);
            h.inv.setItem(h.entries.size(), icon);
            h.entries.add(e);
        }
        p.openInventory(h.inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SwitchMenu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getSlot() < 0 || e.getSlot() >= h.entries.size()) return;
        String pick = h.entries.get(e.getSlot());
        Bukkit.getScheduler().runTask(plugin, () -> { p.closeInventory(); switchTo(p, pick); });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SwitchMenu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ the action bar, cooldowns

    /** The Gold Gem's own action bar (GOLD mode, or the name of the gem you just switched to). True if it drew one. */
    boolean hud(Player p, ItemStack gem) {
        if (!gems().isGold(gem)) return false;
        GoldMath.State s = stateOf(p);
        if (s == null) return false;
        long now = System.currentTimeMillis();
        if (bannerUntil.getOrDefault(p.getUniqueId(), 0L) > now) return true; // the switch banner stays a moment
        if (!s.goldMode()) return false;
        String bar = "§6✦ " + SmallCaps.of("Gold") + "  " + HudStatus.entry("§6" + SmallCaps.of("Snap"), snapReady.getOrDefault(p.getUniqueId(), 0L) - now, false)
                + "  " + HudStatus.entry("§e" + SmallCaps.of("Drill"), drillReady.getOrDefault(p.getUniqueId(), 0L) - now, false);
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(bar));
        return true;
    }

    /** Nothing on the Gold Gem cools down longer than gold.max-cooldown-seconds. */
    long cap(Player p, long ms) {
        if (p == null || stateOf(p) == null || plugin.listener() == null) return ms;
        ItemStack g = plugin.listener().findGem(p);
        return gems().isGold(g) ? GoldMath.cap(ms, (long) (cfgD("max-cooldown-seconds", 170) * 1000)) : ms;
    }

    // ------------------------------------------------------------------ clicks (GemListener asks first)

    /** Clicks GemListener must leave alone: a repurposed player, the ritual site, and GOLD mode's Snap and Golden Drill. */
    boolean handleInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (scenes.containsKey(p.getUniqueId())) { e.setCancelled(true); return true; }
        if (e.getHand() != EquipmentSlot.HAND) return false;
        GoldMath.State s = stateOf(p);
        if (s == null || !gems().isGold(p.getInventory().getItemInMainHand())) return false;
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK, right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        Block b = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && b != null && ritualSite != null && b.getLocation().equals(ritualSite.getBlock().getLocation())
                && s.full() && !s.awakened && !rituals.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            startRitual(p, s);
            return true;
        }
        if (!s.goldMode()) return false;
        if (p.isSneaking() && left) snap(p);
        else if (p.isSneaking() && right) drill(p);
        return left || right; // in GOLD mode no other gem's powers or controls work
    }

    // ------------------------------------------------------------------ the repurpose

    private final class Scene {
        final UUID victim, holder;
        final String victimName;
        final ItemStack gem;
        final GemType type;
        final Location base;
        boolean dummy; // /goldgem testrepurpose: a Mannequin, not a player
        ItemDisplay display;
        final List<ItemDisplay> chains = new ArrayList<>();
        final Set<Integer> boltTicks = new HashSet<>();
        int age;
        boolean holderDied;
        BukkitTask task;

        Scene(org.bukkit.entity.LivingEntity v, UUID holder, ItemStack gem) {
            this.victim = v.getUniqueId();
            this.victimName = v.getName();
            this.holder = holder;
            this.gem = gem;
            this.type = gem == null ? null : gems().getType(gem);
            this.base = v.getLocation().clone();
        }

        Location gemAt() { return base.clone().add(0, 7.5, 0); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHolderHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v) || states.isEmpty()) return;
        Player by = CombatRules.attacker(e.getDamager());
        if (by == null) return;
        GoldMath.State s = stateOf(by);
        if (s != null && s.marks.containsKey(v.getUniqueId())) lastHolderHit.put(v.getUniqueId(), new Object[] {by.getUniqueId(), System.currentTimeMillis()});
    }

    /** A killing blow on a marked player, credited to their marker: no death, the repurpose instead. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        if (scenes.containsKey(v.getUniqueId())) { e.setCancelled(true); return; } // effectively dead: nothing reaches them
        if (states.isEmpty() || e.getFinalDamage() < v.getHealth() + v.getAbsorptionAmount()) return;
        Player direct = e instanceof EntityDamageByEntityEvent ed ? CombatRules.attacker(ed.getDamager()) : null;
        Object[] last = lastHolderHit.get(v.getUniqueId());
        for (GoldMath.State s : states.values()) {
            if (!s.marks.containsKey(v.getUniqueId())) continue;
            long lastAt = last != null && s.holder.equals(last[0]) ? (Long) last[1] : 0;
            if (!GoldMath.credited(direct != null && direct.getUniqueId().equals(s.holder), lastAt, System.currentTimeMillis(), 10_000)) continue;
            e.setCancelled(true);
            startScene(v, s);
            return;
        }
    }

    private void startScene(Player v, GoldMath.State s) {
        int slot = standardGemSlot(v);
        ItemStack gem = null;
        if (slot >= 0) { gem = v.getInventory().getItem(slot).clone(); v.getInventory().setItem(slot, null); } // torn out
        Scene sc = new Scene(v, s.holder, gem);
        scenes.put(v.getUniqueId(), sc);
        lastHolderHit.remove(v.getUniqueId());
        v.setInvulnerable(true);
        v.setVelocity(new Vector());
        stun(v);
        if (sc.type != null && plugin.overlay() != null) plugin.overlay().show(v, cfg("repurpose.overlay." + sc.type.name().toLowerCase(), "blissgems:overlay/gold_0"));
        play(sc, v.getWorld());
    }

    /** The scene itself (the gem rising, the chains, the lightning), for a real victim or the test dummy. */
    private void play(Scene sc, World world) {
        ItemStack gem = sc.gem;
        int seconds = (int) Math.max(1, cfgD("repurpose.seconds", 5)), bolts = (int) cfgD("repurpose.lightning", 4);
        for (int i = 0; i < bolts; i++) sc.boltTicks.add(10 + rnd.nextInt(seconds * 20 - 10));
        if (gem != null) {
            ItemStack shown = gem.clone();
            sc.display = world.spawn(sc.base.clone().add(0, 1.4, 0), ItemDisplay.class, d -> {
                d.setItemStack(shown);
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setTeleportDuration(20); // it rises over a second
            });
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (sc.display != null && sc.display.isValid()) sc.display.teleport(sc.gemAt()); }, 1L);
        }
        GemSounds.play(plugin, "gold.repurpose", sc.base);
        sc.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickScene(sc, seconds * 20), 1L, 1L);
        save();
    }

    private void tickScene(Scene sc, int total) {
        sc.age++;
        org.bukkit.entity.Entity v = Bukkit.getEntity(sc.victim);
        if (v == null || !v.isValid()) { finish(sc, true); return; }
        if (v.getLocation().distanceSquared(sc.base) > 0.04) v.teleport(sc.base); // frozen where they stand
        World w = sc.base.getWorld();
        if (sc.age == 20 && sc.display != null) { // the chains shoot out of the ground and latch on
            int n = (int) Math.max(1, cfgD("repurpose.chains", 4));
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                Location from = sc.base.clone().add(Math.cos(a) * 3.2, 0.1, Math.sin(a) * 3.2);
                from.setY(sc.base.getBlockY() + 0.1);
                sc.chains.addAll(AuratusGem.stillChain(from, sc.gemAt(), 1.0f));
            }
            GemSounds.play(plugin, "gold.chains", sc.base);
        }
        if (sc.boltTicks.contains(sc.age)) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 2 + rnd.nextDouble() * 3;
            w.strikeLightningEffect(sc.base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r)); // only the look: no fire, no damage
        }
        if (sc.age % 2 == 0 && sc.display != null && sc.age > 15) {
            Location g = sc.gemAt();
            Color gc = sc.type == null ? PALE : Color.fromRGB(colourOf(sc.type).value());
            for (int i = 0; i < 6; i++) {
                double a = sc.age * 0.2 + i * Math.PI / 3;
                w.spawnParticle(Particle.DUST, g.clone().add(Math.cos(a) * 0.9, Math.sin(a * 0.7) * 0.4, Math.sin(a) * 0.9), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 2 == 0 ? GOLD_DUST : gc, 1.2f));
            }
        }
        if (sc.age >= total) finish(sc, false);
    }

    /** The end: the chains snap, the gem flies into the holder, the victim dies (dropping their items, not their gem). */
    private void finish(Scene sc, boolean early) {
        if (scenes.remove(sc.victim) == null) return;
        if (sc.task != null) sc.task.cancel();
        World w = sc.base.getWorld();
        for (ItemDisplay d : sc.chains) {
            if (d.isValid()) { w.spawnParticle(Particle.CRIT, d.getLocation(), 3, 0.1, 0.1, 0.1, 0.1); d.remove(); }
        }
        if (!sc.chains.isEmpty()) GemSounds.play(plugin, "gold.chains-snap", sc.gemAt());
        Player holder = Bukkit.getPlayer(sc.holder);
        if (sc.display != null && sc.display.isValid()) {
            Location from = sc.display.getLocation();
            if (holder != null && holder.getWorld().equals(w)) { // a streak into the Gold Gem
                Vector d = holder.getLocation().add(0, 1.2, 0).toVector().subtract(from.toVector());
                int n = (int) Math.max(8, d.length() * 3);
                for (int i = 0; i <= n; i++) w.spawnParticle(Particle.DUST, from.clone().add(d.clone().multiply(i / (double) n)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 2 == 0 ? GOLD_DUST : PALE, 1.0f));
                GemSounds.play(plugin, "gold.absorb", holder.getLocation());
            }
            sc.display.remove();
        }
        GoldMath.State s = states.get(sc.holder);
        if (sc.dummy) { // the test: socket it as a test gem, the dummy simply goes
            if (sc.gem != null && s != null) s.take(sc.type.name(), TEST_OWNER, "Dummy", encode(sc.gem));
            org.bukkit.entity.Entity d = Bukkit.getEntity(sc.victim);
            if (d != null) { w.spawnParticle(Particle.CLOUD, d.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02); d.remove(); }
            Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§6" + cfg("repurpose.message", "{victim}'s soul was repurposed.").replace("{victim}", "Dummy")));
            if (holder != null) sync(holder);
            save();
            return;
        }
        if (sc.gem != null) {
            if (s != null && !sc.holderDied) {
                s.take(sc.type.name(), sc.victim, sc.victimName, encode(sc.gem));
                tellTaken.add(sc.victim);
            } else pending.computeIfAbsent(sc.victim, k -> new ArrayList<>()).add(encode(sc.gem)); // the holder fell first: it goes home
        }
        for (GoldMath.State o : states.values()) if (o.marks.containsKey(sc.victim)) unmark(o, sc.victim);
        Player v = Bukkit.getPlayer(sc.victim);
        String who = v != null ? plugin.shownName(v) : sc.victimName;
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§6" + cfg("repurpose.message", "{victim}'s soul was repurposed.").replace("{victim}", who)));
        if (v != null) {
            if (plugin.overlay() != null) plugin.overlay().clear(v);
            unstun(v);
            v.setInvulnerable(false);
            if (holder != null) v.setKiller(holder);
            v.setHealth(0); // a normal death: their items drop, their gem doesn't (it's gone from their inventory)
        }
        if (holder != null) sync(holder);
        save();
    }

    private void stun(Player p) {
        for (Attribute at : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance inst = p.getAttribute(at);
            if (inst != null) { inst.removeModifier(stunKey); inst.addTransientModifier(new AttributeModifier(stunKey, -1, AttributeModifier.Operation.ADD_SCALAR)); }
        }
    }

    private void unstun(Player p) {
        for (Attribute at : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance inst = p.getAttribute(at);
            if (inst != null) inst.removeModifier(stunKey);
        }
    }

    // Nothing a repurposed player does goes through: no attacks, items, abilities, healing, totems or escapes.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneHit(EntityDamageByEntityEvent e) {
        Player by = CombatRules.attacker(e.getDamager());
        if (by != null && scenes.containsKey(by.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneEntity(PlayerInteractEntityEvent e) { if (scenes.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneEat(PlayerItemConsumeEvent e) { if (scenes.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneDrop(PlayerDropItemEvent e) { if (scenes.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneHeal(EntityRegainHealthEvent e) { if (scenes.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneTotem(EntityResurrectEvent e) { if (scenes.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneTeleport(PlayerTeleportEvent e) {
        if (scenes.containsKey(e.getPlayer().getUniqueId()) && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSceneShoot(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent e) {
        if (scenes.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ deaths, joins, quits

    /** The Gold Gem holder dies: every stolen soul returns home; core only, not awakened; marks go (unless kept). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHolderDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        GoldMath.State s = stateOf(p);
        if (s == null) return;
        Ritual r = rituals.remove(p.getUniqueId());
        if (r != null) r.task.cancel();
        for (Scene sc : scenes.values()) if (sc.holder.equals(p.getUniqueId())) sc.holderDied = true;
        boolean keep = cfgB("keep-marks-on-death", false);
        if (!keep) for (UUID v : new ArrayList<>(s.marks.keySet())) unmark(s, v);
        boolean had = !s.sockets.isEmpty() || !s.held.isEmpty() || s.awakened;
        for (GoldMath.Socket g : s.release(keep)) giveBack(g.owner(), g.item());
        if (had) {
            Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§6" + cfg("release-message", "The Gold Gem shatters. Stolen souls return home.")));
            Location at = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.DUST, at, 60, 1.2, 1.2, 1.2, 0, new Particle.DustOptions(GOLD_DUST, 1.6f));
            p.getWorld().spawnParticle(Particle.END_ROD, at, 30, 0.6, 0.8, 0.6, 0.15);
            GemSounds.play(plugin, "gold.shatter", at);
        }
        save();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (isHolder(p)) sync(p); // the Gold Gem came back with them (core only, after a death)
            if (tellTaken.contains(p.getUniqueId()) && takenBySomeone(p.getUniqueId()))
                p.sendMessage(ChatColor.GOLD + cfg("repurpose.respawn-message", "Your gem was taken by the Gold Gem holder. Kill them to take it back."));
        }, 10L);
    }

    private boolean takenBySomeone(UUID owner) {
        for (GoldMath.State s : states.values()) if (s.hasGemOf(owner)) return true;
        return false;
    }

    /** Whose gem the Gold Gem holds: they get no new gem while it's there. */
    boolean gemTaken(Player p) { return p != null && takenBySomeone(p.getUniqueId()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            sync(p); // their Gold Gem as it is now (or a stale one taken away)
            List<String> mine = pending.remove(p.getUniqueId());
            if (mine != null) {
                for (String it : mine) for (ItemStack left : p.getInventory().addItem(decode(it)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                p.sendMessage(ChatColor.GOLD + SmallCaps.of("Your gem came home."));
                save();
            }
            if (markedNow.contains(p.getUniqueId())) markVisuals(p);
        }, 5L);
    }

    /** Logging out of the repurpose finishes it at once. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Scene sc = scenes.get(id);
        if (sc != null) finish(sc, true);
        Ritual r = rituals.remove(id);
        if (r != null) { r.task.cancel(); }
        bannerUntil.remove(id);
        noFallUntil.remove(id);
    }

    // ------------------------------------------------------------------ the ritual

    private static final class Ritual {
        int age;
        BukkitTask task;
    }

    void setRitual(Location at) {
        ritualSite = at.getBlock().getLocation();
        save();
    }

    Location ritualSite() { return ritualSite; }

    private void startRitual(Player p, GoldMath.State s) {
        Ritual r = new Ritual();
        rituals.put(p.getUniqueId(), r);
        int total = (int) (Math.max(1, cfgD("ritual.seconds", 30)) * 20);
        double radius = cfgD("ritual.radius", 6);
        Title t = Title.title(LegacyComponentSerializer.legacySection().deserialize("§6" + cfg("ritual.title", "The Gold Gem awakens")),
                Component.text(plugin.shownName(p), NamedTextColor.YELLOW), Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) { o.showTitle(t); GemSounds.playTo(plugin, "gold.ritual", o); }
        Location centre = ritualSite.clone().add(0.5, 1, 0.5);
        r.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            r.age++;
            Player h = Bukkit.getPlayer(s.holder);
            double dist = h == null || !h.getWorld().equals(centre.getWorld()) ? Double.MAX_VALUE : h.getLocation().distance(centre);
            switch (GoldMath.ritual(h != null, h != null && !h.isDead(), dist, radius, r.age, total)) {
                case CANCELLED -> {
                    r.task.cancel();
                    rituals.remove(s.holder);
                    if (h != null) h.sendMessage(ChatColor.GRAY + SmallCaps.of("The ritual breaks."));
                }
                case DONE -> {
                    r.task.cancel();
                    rituals.remove(s.holder);
                    s.awakened = true;
                    flashAll((int) cfgD("ritual.flash-ticks", 20));
                    for (Player o : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "gold.awaken", o);
                    if (h != null) { sync(h); h.sendMessage(ChatColor.GOLD + SmallCaps.of("The Gold Gem is awakened.")); }
                    save();
                }
                default -> { if (r.age % 2 == 0) beams(centre, h, r.age / (double) total); }
            }
        }, 1L, 1L);
    }

    /** Eight beams, one per gem colour, rising around the site and slowly bending into a gold column above the holder. */
    private void beams(Location centre, Player h, double t) {
        World w = centre.getWorld();
        Location apex = (h == null ? centre : h.getLocation()).clone().add(0, 9, 0);
        for (int i = 0; i < GoldMath.ORDER.size(); i++) {
            double a = Math.PI * 2 * i / GoldMath.ORDER.size();
            Location from = centre.clone().add(Math.cos(a) * 5, -0.9, Math.sin(a) * 5);
            Location top = from.clone().add(0, 10, 0);
            Vector to = top.toVector().multiply(1 - t).add(apex.toVector().multiply(t));
            Vector d = to.subtract(from.toVector());
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(colourOf(type(GoldMath.ORDER.get(i))).value()), 1.4f);
            for (int k = 0; k <= 14; k++) w.spawnParticle(Particle.DUST, from.clone().add(d.clone().multiply(k / 14.0)), 1, 0, 0, 0, 0, dust);
        }
        if (t > 0.6 && h != null) for (int k = 0; k <= 10; k++)
            w.spawnParticle(Particle.DUST, h.getLocation().add(0, 2 + k * 0.7, 0), 1, 0.08, 0, 0.08, 0, new Particle.DustOptions(GOLD_DUST, 1.6f));
    }

    /** The gold flash for everyone: the finale's title-flash glyph in gold (gold.flash.glyph). */
    private void flashAll(int ticks) {
        TextColor col = TextColor.fromHexString(cfg("flash.color", "#FFC846"));
        Title t = Title.title(Component.text(cfg("flash.glyph", "")).color(col == null ? TextColor.color(0xFFC846) : col), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(Math.max(1, ticks) * 50L), Duration.ofMillis(Math.max(1, ticks) * 50L)));
        for (Player o : Bukkit.getOnlinePlayers()) o.showTitle(t);
    }

    // ------------------------------------------------------------------ Snap and Golden Drill (GOLD mode)

    private void snap(Player p) {
        long now = System.currentTimeMillis();
        if (snapReady.getOrDefault(p.getUniqueId(), 0L) > now) return;
        snapReady.put(p.getUniqueId(), now + cap(p, (long) (cfgD("snap.cooldown-seconds", 175) * 1000)));
        List<UUID> online = new ArrayList<>();
        Set<UUID> excluded = new HashSet<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            online.add(o.getUniqueId());
            if (cfgB("snap.exclude-trusted", false) && plugin.listener() != null && plugin.listener().isTrusted(p, o)) excluded.add(o.getUniqueId());
        }
        snapEffect(p, GoldMath.snapTargets(online, p.getUniqueId(), excluded, (int) cfgD("snap.targets", 3), rnd), false);
    }

    /** Snap's effect: the gold flash and the sound for everyone, then these players' gems fall silent. */
    private void snapEffect(Player p, List<UUID> targets, boolean test) {
        flashAll((int) cfgD("snap.flash-ticks", 4));
        for (Player o : Bukkit.getOnlinePlayers()) GemSounds.playTo(plugin, "gold.snap", o);
        long ms = (long) (cfgD("snap.disable-minutes", 5) * 60_000);
        int n = 0;
        for (UUID id : targets) {
            Player v = Bukkit.getPlayer(id);
            if (v == null || plugin.listener() == null) continue;
            plugin.listener().disableGemFor(v, ms, "Snap");
            v.sendMessage(ChatColor.GOLD + SmallCaps.of("The Gold Gem snapped: your gem falls silent for " + HudStatus.time(ms) + "."));
            n++;
        }
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize((test ? "§7[test] " : "") + "§6" + cfg("snap.message", "{holder} snapped. {count} gems fall silent.")
                .replace("{holder}", plugin.shownName(p)).replace("{count}", String.valueOf(n))));
    }

    private void drill(Player p) {
        long now = System.currentTimeMillis();
        if (drillReady.getOrDefault(p.getUniqueId(), 0L) > now) return;
        drillReady.put(p.getUniqueId(), now + cap(p, (long) (cfgD("drill.cooldown-seconds", 30) * 1000)));
        double mult = Math.max(0, plugin.getConfig().getDouble("ability-damage.multiplier", 3.0));
        Vector dir = p.getLocation().getDirection().normalize();
        p.setVelocity(dir.clone().multiply(cfgD("drill.speed", 3.0)));
        p.startRiptideAttack(20, (float) (cfgD("drill.damage", 8.0) * mult), null); // the spin: the first one you hit takes it, with knockback
        noFallUntil.put(p.getUniqueId(), now + 3000); // the launch, then 2 s
        GemSounds.play(plugin, "gold.drill", p.getLocation());
        List<GemType> ring = new ArrayList<>();
        for (String t : GoldMath.ORDER) ring.add(type(t));
        new org.bukkit.scheduler.BukkitRunnable() {
            int t;

            @Override public void run() {
                if (!p.isOnline() || t++ >= 20) { cancel(); return; }
                Vector v = p.getVelocity().lengthSquared() > 1e-4 ? p.getVelocity().normalize() : dir;
                Vector side = v.clone().crossProduct(new Vector(0, 1, 0));
                if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
                side.normalize();
                Vector up = side.clone().crossProduct(v).normalize();
                Location c = p.getLocation().add(0, 0.9, 0);
                for (int i = 0; i < ring.size(); i++) { // a ring in all 8 gem colours, turning: a spiral along the path
                    double a = t * 0.6 + Math.PI * 2 * i / ring.size();
                    Location at = c.clone().add(side.clone().multiply(Math.cos(a) * 1.1)).add(up.clone().multiply(Math.sin(a) * 1.1));
                    p.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(colourOf(ring.get(i)).value()), 1.1f));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrillFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (noFallUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) e.setCancelled(true);
    }
}
