package dev.soulbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.DecoratedPot;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Item provenance (provenance: in config.yml). Every tracked item (armour, weapons, tools) has one OWNER and a status,
 * shown as one small-caps line at the bottom of its lore:
 * ORIGINAL with its owner, STOLEN from the owner, GIFTED by the owner, and STORED while it sits in a container.
 * The owner never changes; ProvenanceMath.arrive decides the status whenever the item reaches a player: picked up,
 * or found in their inventory after a click, a container, an armour stand, a frame or a pot. Anvils, smithing,
 * enchanting and grindstones keep the item's data, so they keep its provenance. Automation changes nothing.
 * Stored in the item's PDC (prov_owner, prov_name, prov_type); items from before get it the next time someone holds them.
 */
public class ItemProvenanceListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey kProvType;   // the status: original, stolen, gifted
    private final NamespacedKey kProvName;   // the owner's name when it became theirs
    private final NamespacedKey kOwner;      // the owner's UUID
    private final NamespacedKey kLine;       // the line this wrote (so it's replaced, never doubled)
    private final NamespacedKey kDropOwner;  // on a dropped item entity: who dropped it alive
    private final NamespacedKey kDropName;
    private final NamespacedKey kDeathVictim, kDeathName; // the old death-drop marks, removed when seen
    private final NamespacedKey kFinaleKit;
    private final Map<UUID, Inventory> pending = new HashMap<>(); // players to settle next tick, with the container open (or null)

    public ItemProvenanceListener(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        kProvType = new NamespacedKey("soulbound-gems", "prov_type");
        kProvName = new NamespacedKey("soulbound-gems", "prov_name");
        kOwner = new NamespacedKey("soulbound-gems", "prov_owner");
        kLine = new NamespacedKey("soulbound-gems", "prov_line");
        kDropOwner = new NamespacedKey("soulbound-gems", "prov_drop_owner");
        kDropName = new NamespacedKey("soulbound-gems", "prov_drop_name");
        kDeathVictim = new NamespacedKey("soulbound-gems", "prov_death_victim");
        kDeathName = new NamespacedKey("soulbound-gems", "prov_death_name");
        kFinaleKit = new NamespacedKey(plugin, "finale_kit");
    }

    /** Armour (elytra, shields and the turtle shell too), weapons and tools. Nothing else. */
    public static boolean isTracked(ItemStack item) {
        return item != null && isTracked(item.getType().name());
    }

    static boolean isTracked(String n) {
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_HELMET")
                || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.equals("SHIELD") || n.equals("ELYTRA")
                || n.equals("SHEARS") || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL") || n.equals("BRUSH");
    }

    /** A tracked item that isn't one of the plugin's own: gems, mythics, the Revenant, the Prismatic Edge, the finale's kit. */
    boolean tracked(ItemStack it) {
        if (!isTracked(it)) return false;
        if (plugin.gems() != null && plugin.gems().isGem(it)) return false;
        if (plugin.revenant() != null && plugin.revenant().isLiveItem(it)) return false;
        if (plugin.prismaticEdge() != null && plugin.prismaticEdge().isEdge(it)) return false;
        return !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(kFinaleKit, PersistentDataType.BYTE);
    }

    private boolean on() { return plugin.getConfig().getBoolean("provenance.enabled", true); }

    // ------------------------------------------------------------------ reading and writing

    /** The item's owner and status, or null if nobody owns it yet (new, or from before provenance). */
    ProvenanceMath.Prov read(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return read(it.getItemMeta().getPersistentDataContainer());
    }

    private ProvenanceMath.Prov read(PersistentDataContainer pdc) {
        UUID owner = safeUuid(pdc.get(kOwner, PersistentDataType.STRING));
        ProvenanceMath.Status st = ProvenanceMath.Status.of(pdc.get(kProvType, PersistentDataType.STRING));
        if (owner == null || st == null) return null;
        String name = pdc.get(kProvName, PersistentDataType.STRING);
        return new ProvenanceMath.Prov(owner, name == null || name.isEmpty() ? nameOf(owner, "?") : name, st);
    }

    private String word(String status, String def) { return plugin.getConfig().getString("provenance." + status + ".word", def); }

    private String joiner(String status, String def) { return plugin.getConfig().getString("provenance." + status + ".joiner", def); }

    private TextColor colour(String path, String def) {
        TextColor c = TextColor.fromHexString(plugin.getConfig().getString(path, def));
        return c == null ? TextColor.fromHexString(def) : c;
    }

    private static String defWord(String status) {
        return switch (status) { case "stolen" -> "Stolen"; case "gifted" -> "Gifted"; case "stored" -> "Stored"; default -> "Original"; };
    }

    private static String defJoiner(String status) {
        return switch (status) { case "stolen" -> "from"; case "gifted" -> "by"; default -> "·"; };
    }

    private static String defColour(String status) {
        return switch (status) { case "stolen" -> "#E04848"; case "gifted" -> "#FFC846"; case "stored" -> "#8FD3FF"; default -> "#D8C3A0"; };
    }

    private String shownOwner(String name) { return plugin.getConfig().getBoolean("provenance.name-small-caps", false) ? SmallCaps.of(name) : name; }

    /** The line for this owner and status (or STORED), with the words and colours in config. */
    Component line(ProvenanceMath.Prov p, boolean stored) {
        String st = stored ? "stored" : p.status().key();
        return line(word(st, defWord(st)), joiner(st, defJoiner(st)), shownOwner(p.name()),
                colour("provenance." + st + ".color", defColour(st)), colour("provenance.joiner-color", "#9A9A9A"), colour("provenance.name-color", "#FFFFFF"));
    }

    /** The status word in small caps in its colour, the joining word in small caps (gray), then the name (white). No italics. */
    static Component line(String word, String joiner, String name, TextColor wordColour, TextColor joinerColour, TextColor nameColour) {
        return Component.text().decoration(TextDecoration.ITALIC, false)
                .append(Component.text(SmallCaps.of(word), wordColour))
                .append(Component.text(" "))
                .append(Component.text(SmallCaps.of(joiner), joinerColour))
                .append(Component.text(" "))
                .append(Component.text(name, nameColour))
                .build();
    }

    private List<String> words() {
        List<String> w = new ArrayList<>();
        for (String st : new String[] {"original", "stolen", "gifted", "stored"}) w.add(word(st, defWord(st)));
        return w;
    }

    /**
     * Gives the item this provenance ({@code p}; null: nobody owns it yet) and the line for it, at the bottom of its
     * lore, replacing the old one (and any line from before the rework). True if anything changed.
     */
    boolean write(ItemStack it, ProvenanceMath.Prov p, boolean stored) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        boolean showLine = plugin.getConfig().getBoolean("provenance.show-line", true);
        Component want = p == null || !showLine ? null : line(p, stored);
        String wantText = want == null ? null : PLAIN.serialize(want), have = pdc.get(kLine, PersistentDataType.STRING);
        boolean oldMarks = pdc.has(kDeathVictim, PersistentDataType.STRING) || pdc.has(kDeathName, PersistentDataType.STRING)
                || (p == null && pdc.has(kProvType, PersistentDataType.STRING));
        if (Objects.equals(read(pdc), p) && Objects.equals(have, wantText) && !oldMarks && (p != null || !hasOldLine(m))) return false;
        List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
        List<String> words = words();
        lore.removeIf(c -> {
            String plain = PLAIN.serialize(c);
            return plain.equals(have) || ProvenanceMath.isLine(plain, words) || SmallCaps.isHistoryLine(LEGACY.serialize(c));
        });
        if (want != null) lore.add(want);
        m.lore(lore.isEmpty() ? null : lore);
        if (p == null) {
            pdc.remove(kOwner);
            pdc.remove(kProvName);
            pdc.remove(kProvType);
        } else {
            pdc.set(kOwner, PersistentDataType.STRING, p.owner().toString());
            pdc.set(kProvName, PersistentDataType.STRING, p.name());
            pdc.set(kProvType, PersistentDataType.STRING, p.status().key());
        }
        if (wantText == null) pdc.remove(kLine); else pdc.set(kLine, PersistentDataType.STRING, wantText);
        pdc.remove(kDeathVictim);
        pdc.remove(kDeathName);
        it.setItemMeta(m);
        return true;
    }

    /** A "STOLEN by X" line from before the rework. */
    private static boolean hasOldLine(ItemMeta m) {
        if (!m.hasLore()) return false;
        for (String line : m.getLore()) if (line != null && SmallCaps.isHistoryLine(line)) return true;
        return false;
    }

    private static String nameOf(UUID id, String fallback) {
        if (id == null) return fallback;
        try {
            String n = Bukkit.getOfflinePlayer(id).getName();
            return (n == null || n.isEmpty()) ? fallback : n;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static UUID safeUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (Exception ex) { return null; }
    }

    // ------------------------------------------------------------------ where items are

    /**
     * Every tracked item in a player's inventory (armour and off hand too) is theirs now: an item nobody owned becomes
     * theirs, ORIGINAL; their own is ORIGINAL; someone else's ORIGINAL item they hold is STOLEN; passed-on items keep
     * their status. Items they were given (GIFTED) are caught at the pickup, before this.
     */
    void settle(Player p) {
        if (!on() || !p.isOnline()) return;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!tracked(it)) continue;
            if (write(it, ProvenanceMath.arrive(read(it), p.getUniqueId(), p.getName(), false), false)) inv.setItem(i, it);
        }
    }

    /** A real container in the world (chest, barrel, shulker box, ender chest, hopper ...), never a plugin menu. */
    private static boolean storage(Inventory inv) {
        if (inv == null || inv.getLocation() == null) return false;
        InventoryType t = inv.getType();
        return t == InventoryType.CHEST || t == InventoryType.ENDER_CHEST || t == InventoryType.BARREL
                || t == InventoryType.SHULKER_BOX || t == InventoryType.HOPPER
                || t == InventoryType.DISPENSER || t == InventoryType.DROPPER || t == InventoryType.DECORATED_POT;
    }

    /** Everything tracked in a container reads STORED (its status underneath doesn't change). */
    private void markStored(Inventory inv) {
        if (!on() || inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (tracked(it) && write(it, read(it), true)) inv.setItem(i, it);
        }
    }

    private ItemStack stored(ItemStack it) {
        if (tracked(it)) write(it, read(it), true);
        return it;
    }

    /** Next tick (once per player per tick): settle their inventory, and mark the open container STORED. */
    private void soon(Player p, Inventory top) {
        if (!on()) return;
        boolean first = !pending.containsKey(p.getUniqueId());
        if (storage(top) || first) pending.put(p.getUniqueId(), storage(top) ? top : pending.get(p.getUniqueId()));
        if (!first) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory open = pending.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            settle(p);
            if (open != null) markStored(open);
        });
    }

    // ------------------------------------------------------------------ drops and pickups

    /** Dropped alive (Q, or out of the inventory): remember who, so the pickup can tell a gift. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack s = e.getItemDrop().getItemStack();
        if (!on() || !tracked(s)) return;
        e.getItemDrop().getPersistentDataContainer().set(kDropOwner, PersistentDataType.STRING,
                e.getPlayer().getUniqueId().toString());
        e.getItemDrop().getPersistentDataContainer().set(kDropName, PersistentDataType.STRING,
                e.getPlayer().getName());
    }

    /** A pickup: GIFTED if its owner dropped it for you, STOLEN if it's their death drop, ORIGINAL if it's yours or nobody's. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player picker) || !on()) return;
        Item entityItem = e.getItem();
        ItemStack s = entityItem.getItemStack();
        if (!tracked(s)) return;
        ProvenanceMath.Prov p = read(s);
        String dropper = entityItem.getPersistentDataContainer().get(kDropOwner, PersistentDataType.STRING);
        boolean given = p != null && p.owner().toString().equals(dropper);
        if (write(s, ProvenanceMath.arrive(p, picker.getUniqueId(), picker.getName(), given), false)) entityItem.setItemStack(s);
    }

    /** A hopper (or hopper minecart) picking up an item: it's in a container now. Nothing else changes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        ItemStack s = e.getItem().getItemStack();
        if (on() && tracked(s) && write(s, read(s), true)) e.getItem().setItemStack(s);
    }

    // ------------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player && storage(e.getInventory())) markStored(e.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) soon(p, e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) soon(p, e.getView().getTopInventory());
    }

    /** Closing: whatever was on the cursor is back in their inventory now. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) soon(p, e.getInventory());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> settle(p)); // items from before provenance become theirs
    }

    /**
     * Crafting a tracked item from one of the same kind (two worn tools repaired together, a dyed leather piece, a
     * shield with a banner) keeps the first one's provenance. Anything else crafted is new: it becomes the crafter's.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        ItemStack result = e.getCurrentItem();
        if (!on() || !tracked(result)) return;
        for (ItemStack in : e.getInventory().getMatrix()) {
            if (in == null || in.getType() != result.getType() || !tracked(in)) continue;
            ProvenanceMath.Prov p = read(in);
            if (p == null) return;
            ItemStack copy = result.clone();
            write(copy, p, false);
            e.setCurrentItem(copy);
            return;
        }
    }

    // ------------------------------------------------------------------ armour stands, item frames, pots

    /** Something taken off a stand is now yours (STOLEN if it was someone else's); what's put on it reads STORED. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (!on()) return;
        Player p = e.getPlayer();
        ArmorStand stand = e.getRightClicked();
        Bukkit.getScheduler().runTask(plugin, () -> {
            settle(p);
            if (!stand.isValid()) return;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue; // stands don't have these
                ItemStack it = stand.getEquipment().getItem(slot);
                if (tracked(it)) stand.getEquipment().setItem(slot, stored(it));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        if (!on() || !(e.getRightClicked() instanceof ItemFrame frame)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            settle(p);
            if (frame.isValid() && tracked(frame.getItem())) frame.setItem(stored(frame.getItem()), false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPot(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (!on() || e.getAction() != Action.RIGHT_CLICK_BLOCK || b == null || b.getType() != Material.DECORATED_POT) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            settle(p);
            if (b.getState() instanceof DecoratedPot pot) markStored(pot.getInventory());
        });
    }
}
