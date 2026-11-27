package dev.soulbound;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The Heretic's Games' kits (heretic-games-kits.yml): exact items in exact slots, and /hgames kits to set them. The list
 * menu: left-click edits a kit, right-click turns it on or off, shift-right-click deletes it (asks first), and "new kit"
 * asks for a name in chat. The editor is laid out like your inventory (armour and offhand on top, then the main rows,
 * then the hotbar) and holds copies only: clicking your own inventory puts a copy on the cursor, nothing of yours ever
 * moves, and the cursor is cleared when it closes. Gems and Halo fragments never go into a kit.
 */
final class GamesKits implements Listener {
    static final String ADMIN = "soulbound.hgames.admin";
    /** Every item a kit hands out carries this: taken back at the round's end, never leaves the tournament. */
    static final NamespacedKey KIT = new NamespacedKey("soulboundgems", "hg_kit");

    /** One kit: inventory index (GamesMath: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand) to item. */
    static final class Kit {
        final String id;
        String name;
        boolean enabled = true;
        final Map<Integer, ItemStack> slots = new TreeMap<>();

        Kit(String id, String name) { this.id = id; this.name = name; }

        Material icon() {
            for (int i = 0; i <= 8; i++) { ItemStack it = slots.get(i); if (it != null && !it.getType().isAir()) return it.getType(); }
            for (ItemStack it : slots.values()) if (it != null && !it.getType().isAir()) return it.getType();
            return Material.LEATHER_CHESTPLATE;
        }
    }

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, Long> naming = new HashMap<>(); // waiting for a new kit's name in chat

    GamesKits(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "heretic-games-kits.yml");
        if (!file.exists()) { defaults(); save(); } // the six kits, written once
        else load();
    }

    static boolean isKitItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KIT, PersistentDataType.BYTE);
    }

    static ItemStack tagged(ItemStack it) {
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        m.getPersistentDataContainer().set(KIT, PersistentDataType.BYTE, (byte) 1);
        c.setItemMeta(m);
        return c;
    }

    Kit get(String id) { return id == null ? null : kits.get(id); }

    List<String> enabledIds() {
        List<String> out = new ArrayList<>();
        for (Kit k : kits.values()) if (k.enabled) out.add(k.id);
        return out;
    }

    List<String> ids() { return new ArrayList<>(kits.keySet()); }

    // ------------------------------------------------------------------ the file

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("kits");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            Kit k = new Kit(id, s.getString("name", id));
            k.enabled = s.getBoolean("enabled", true);
            ConfigurationSection sl = s.getConfigurationSection("slots");
            if (sl != null) for (String i : sl.getKeys(false)) {
                try {
                    ItemStack it = sl.getItemStack(i);
                    int idx = Integer.parseInt(i);
                    if (it != null && idx >= 0 && idx <= GamesMath.OFFHAND) k.slots.put(idx, it);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("heretic-games-kits.yml: skipping " + id + " slot " + i + " (" + ex.getMessage() + ")");
                }
            }
            kits.put(id, k);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("The Heretic's Games kits. Edit them in game with /hgames kits.",
                "Slots: 0-8 hotbar, 9-35 main inventory, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand."));
        for (Kit k : kits.values()) {
            String p = "kits." + k.id + ".";
            y.set(p + "name", k.name);
            y.set(p + "enabled", k.enabled);
            for (Map.Entry<Integer, ItemStack> e : k.slots.entrySet()) y.set(p + "slots." + e.getKey(), e.getValue());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save heretic-games-kits.yml: " + ex.getMessage()); }
    }

    /** Archer, trident, mace, axe and shield, naked, sword. */
    private void defaults() {
        Kit a = put("archer", "Archer");
        a.slots.put(0, new ItemStack(Material.BOW));
        a.slots.put(1, new ItemStack(Material.CROSSBOW));
        a.slots.put(9, new ItemStack(Material.ARROW, 32));
        armour(a, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
        Kit t = put("trident", "Trident");
        ItemStack trident = new ItemStack(Material.TRIDENT);
        trident.addEnchantment(Enchantment.RIPTIDE, 3);
        t.slots.put(0, trident);
        armour(t, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
        Kit m = put("mace", "Mace");
        m.slots.put(0, new ItemStack(Material.MACE));
        m.slots.put(1, new ItemStack(Material.WIND_CHARGE, 16));
        armour(m, Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
        Kit x = put("axe", "Axe and Shield");
        x.slots.put(0, new ItemStack(Material.IRON_AXE));
        x.slots.put(GamesMath.OFFHAND, new ItemStack(Material.SHIELD));
        armour(x, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
        Kit n = put("naked", "Naked");
        n.slots.put(8, new ItemStack(Material.COOKED_BEEF, 8));
        Kit s = put("sword", "Sword");
        s.slots.put(0, new ItemStack(Material.IRON_SWORD));
        armour(s, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
    }

    private Kit put(String id, String name) { Kit k = new Kit(id, name); kits.put(id, k); return k; }

    private static void armour(Kit k, Material helmet, Material chest, Material legs, Material boots) {
        k.slots.put(GamesMath.HELMET, new ItemStack(helmet));
        k.slots.put(GamesMath.CHEST, new ItemStack(chest));
        k.slots.put(GamesMath.LEGS, new ItemStack(legs));
        k.slots.put(GamesMath.BOOTS, new ItemStack(boots));
    }

    /** Never in a kit: a gem of any kind (a copy would be a second gem) or a Halo fragment. */
    private boolean refused(ItemStack it) { return it != null && (plugin.gems().isGem(it) || HaloHunt.isFragment(it)); }

    // ------------------------------------------------------------------ handing a kit out

    /**
     * Puts the kit on a player whose inventory holds only what they keep (their gems): every kit item tagged, in its
     * slot; a gem in the way moves to a free slot.
     */
    void give(Player p, Kit k, boolean tag) {
        PlayerInventory inv = p.getInventory();
        Map<Integer, ItemStack> kept = new LinkedHashMap<>();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) if (c[i] != null && !c[i].getType().isAir() && !isKitItem(c[i])) kept.put(i, c[i]);
        inv.clear();
        if (k != null) for (Map.Entry<Integer, ItemStack> e : k.slots.entrySet()) {
            if (e.getValue() == null || e.getValue().getType().isAir() || refused(e.getValue())) continue;
            inv.setItem(e.getKey(), tag ? tagged(e.getValue()) : e.getValue().clone());
        }
        for (Map.Entry<Integer, ItemStack> e : kept.entrySet()) {
            ItemStack at = inv.getItem(e.getKey());
            if (at == null || at.getType().isAir()) { inv.setItem(e.getKey(), e.getValue()); continue; }
            int free = inv.firstEmpty();
            if (free >= 0) inv.setItem(free, e.getValue());
            else inv.setItem(e.getKey(), e.getValue()); // no room at all: the gem wins over the kit item
        }
    }

    // ------------------------------------------------------------------ the list menu

    static final class ListMenu implements InventoryHolder {
        final List<String> order = new ArrayList<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    static final class EditMenu implements InventoryHolder {
        final Kit kit; // a working copy: saved only with Save
        final boolean isNew;
        Inventory inv;
        EditMenu(Kit kit, boolean isNew) { this.kit = kit; this.isNew = isNew; }
        @Override public Inventory getInventory() { return inv; }
    }

    static final class ConfirmMenu implements InventoryHolder {
        final String id;
        Inventory inv;
        ConfirmMenu(String id) { this.id = id; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int NEW_SLOT = 49;

    void openList(Player p) {
        ListMenu h = new ListMenu();
        h.inv = Bukkit.createInventory(h, 54, "ʜᴇʀᴇᴛɪᴄ'ꜱ ɢᴀᴍᴇꜱ · ᴋɪᴛꜱ");
        int slot = 0;
        for (Kit k : kits.values()) {
            if (slot >= 45) break;
            h.order.add(k.id);
            h.inv.setItem(slot++, button(k.icon(), (k.enabled ? "§a" : "§7") + k.name,
                    "§8" + k.id, k.enabled ? "§aOn: rounds can pick it" : "§cOff: never picked", "",
                    "§eLeft-click§7: edit", "§eRight-click§7: turn " + (k.enabled ? "off" : "on"), "§eShift + right-click§7: delete"));
        }
        h.inv.setItem(NEW_SLOT, button(Material.NETHER_STAR, "§6New kit", "§7Click, then type its name in chat."));
        p.openInventory(h.inv);
    }

    private void openEditor(Player p, Kit working, boolean isNew) {
        EditMenu h = new EditMenu(working, isNew);
        h.inv = Bukkit.createInventory(h, 54, "ᴋɪᴛ · " + SmallCaps.of(working.name));
        drawEditor(h);
        p.openInventory(h.inv);
    }

    private void drawEditor(EditMenu h) {
        for (int s = 0; s < 54; s++) {
            int idx = GamesMath.editorToInventory(s);
            if (idx >= 0) { ItemStack it = h.kit.slots.get(idx); h.inv.setItem(s, it == null ? null : it.clone()); }
        }
        h.inv.setItem(5, button(Material.GRAY_STAINED_GLASS_PANE, "§7← armour · offhand", "§8Helmet, chestplate, leggings, boots, offhand",
                "§8Below: the main inventory, then the hotbar."));
        h.inv.setItem(6, button(Material.CHEST, "§bCopy my inventory", "§7Your inventory as it is now (copies; gems skipped)."));
        h.inv.setItem(7, button(Material.BARRIER, "§cClear", "§7Empty every slot of this kit."));
        h.inv.setItem(8, button(Material.ARMOR_STAND, "§eGive me this kit", "§7Copies into your inventory, to try it."));
        for (int s = 45; s < 54; s++) h.inv.setItem(s, button(Material.BLACK_STAINED_GLASS_PANE, " "));
        h.inv.setItem(45, button(Material.RED_CONCRETE, "§cCancel", "§7Back to the list; nothing saved."));
        h.inv.setItem(49, button(h.kit.icon(), "§6" + h.kit.name, "§8" + h.kit.id, h.kit.enabled ? "§aOn" : "§cOff",
                "§7Click your own items to pick up a copy,", "§7then click a slot here to put it in."));
        h.inv.setItem(53, button(Material.LIME_CONCRETE, "§aSave", "§7Save this kit and go back to the list."));
    }

    private void openConfirm(Player p, String id) {
        ConfirmMenu h = new ConfirmMenu(id);
        h.inv = Bukkit.createInventory(h, 27, "ᴅᴇʟᴇᴛᴇ " + SmallCaps.of(kits.get(id).name) + "?");
        h.inv.setItem(11, button(Material.RED_CONCRETE, "§cDelete " + kits.get(id).name, "§7This can't be undone."));
        h.inv.setItem(15, button(Material.LIME_CONCRETE, "§aKeep it"));
        p.openInventory(h.inv);
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static Kit copy(Kit k) {
        Kit c = new Kit(k.id, k.name);
        c.enabled = k.enabled;
        for (Map.Entry<Integer, ItemStack> e : k.slots.entrySet()) c.slots.put(e.getKey(), e.getValue().clone());
        return c;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder(false);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (holder instanceof ListMenu h) { e.setCancelled(true); listClick(p, h, e); }
        else if (holder instanceof ConfirmMenu h) { e.setCancelled(true); confirmClick(p, h, e.getRawSlot()); }
        else if (holder instanceof EditMenu h) editClick(p, h, e);
    }

    private void listClick(Player p, ListMenu h, InventoryClickEvent e) {
        if (!p.hasPermission(ADMIN)) { p.closeInventory(); return; }
        int s = e.getRawSlot();
        if (s == NEW_SLOT) {
            naming.put(p.getUniqueId(), System.currentTimeMillis());
            p.closeInventory();
            p.sendMessage(ChatColor.GOLD + "Type the new kit's name in chat (or 'cancel').");
            return;
        }
        if (s < 0 || s >= h.order.size()) return;
        Kit k = kits.get(h.order.get(s));
        if (k == null) return;
        if (e.getClick() == ClickType.SHIFT_RIGHT) { openConfirm(p, k.id); return; }
        if (e.isRightClick()) { k.enabled = !k.enabled; save(); openList(p); return; }
        if (e.isLeftClick()) openEditor(p, copy(k), false);
    }

    private void confirmClick(Player p, ConfirmMenu h, int s) {
        if (s == 11 && p.hasPermission(ADMIN) && kits.remove(h.id) != null) {
            save();
            p.sendMessage(ChatColor.GOLD + "Kit " + h.id + " deleted.");
        }
        if (s == 11 || s == 15) openList(p);
    }

    /**
     * The editor: the kit slots take and give items like normal slots (the cursor only ever holds copies), every other
     * menu slot is a button or a filler, and your own inventory is never changed: a click there puts a copy of that
     * item on the cursor (an empty slot, or a click with something on the cursor, clears it).
     */
    private void editClick(Player p, EditMenu h, InventoryClickEvent e) {
        int raw = e.getRawSlot();
        int top = e.getView().getTopInventory().getSize();
        ClickType click = e.getClick();
        if (!p.hasPermission(ADMIN)) { e.setCancelled(true); p.closeInventory(); return; }
        if (raw < 0 || raw >= top) { // your own inventory, or outside the window
            e.setCancelled(true);
            if (raw < 0) return; // never dropped into the world
            ItemStack it = e.getCurrentItem();
            if (e.isShiftClick()) { // straight into the first free kit slot
                if (it == null || it.getType().isAir() || refused(it)) return;
                for (int s = 0; s < top; s++) {
                    if (GamesMath.editorToInventory(s) >= 0 && (h.inv.getItem(s) == null || h.inv.getItem(s).getType().isAir())) {
                        h.inv.setItem(s, it.clone());
                        break;
                    }
                }
                return;
            }
            if (it != null && !it.getType().isAir() && refused(it)) { p.sendMessage(ChatColor.RED + "Gems and Halo fragments can't go in a kit."); return; }
            ItemStack cursor = e.getCursor();
            p.setItemOnCursor(cursor != null && !cursor.getType().isAir() || it == null ? null : it.clone());
            return;
        }
        int idx = GamesMath.editorToInventory(raw);
        if (idx < 0) { e.setCancelled(true); button(p, h, raw); return; }
        // A kit slot: ordinary placing and picking up. Anything that would reach your own inventory is turned off.
        switch (click) {
            case LEFT, RIGHT -> {
                if (refused(e.getCursor())) { e.setCancelled(true); p.setItemOnCursor(null); }
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> { e.setCancelled(true); h.inv.setItem(raw, null); } // shift-click: empties the slot
            case NUMBER_KEY -> { // a copy of that hotbar item into this slot
                e.setCancelled(true);
                ItemStack hot = p.getInventory().getItem(e.getHotbarButton());
                if (hot != null && !hot.getType().isAir() && !refused(hot)) h.inv.setItem(raw, hot.clone());
            }
            case MIDDLE, CREATIVE -> {
                if (refused(e.getCursor())) e.setCancelled(true);
            }
            default -> e.setCancelled(true); // double-click collect, drops, the offhand key
        }
    }

    private void button(Player p, EditMenu h, int raw) {
        switch (raw) {
            case 6 -> { // copy my inventory
                ItemStack[] c = p.getInventory().getContents();
                for (int i = 0; i < c.length && i <= GamesMath.OFFHAND; i++) {
                    int s = GamesMath.inventoryToEditor(i);
                    if (s >= 0) h.inv.setItem(s, c[i] == null || refused(c[i]) ? null : c[i].clone());
                }
            }
            case 7 -> { for (int s = 0; s < 54; s++) if (GamesMath.editorToInventory(s) >= 0) h.inv.setItem(s, null); }
            case 8 -> {
                read(h);
                for (ItemStack it : h.kit.slots.values()) for (ItemStack left : p.getInventory().addItem(it.clone()).values()) p.getWorld().dropItem(p.getLocation(), left);
                p.sendMessage(ChatColor.GOLD + "A copy of " + h.kit.name + " is in your inventory.");
            }
            case 45 -> openList(p);
            case 53 -> {
                read(h);
                Kit k = h.kit;
                kits.put(k.id, k);
                save();
                p.sendMessage(ChatColor.GOLD + "Kit " + k.name + " saved (" + k.slots.size() + " items).");
                openList(p);
            }
            default -> { }
        }
    }

    /** The menu's kit slots back into the working kit. */
    private void read(EditMenu h) {
        h.kit.slots.clear();
        for (int s = 0; s < 54; s++) {
            int idx = GamesMath.editorToInventory(s);
            ItemStack it = h.inv.getItem(s);
            if (idx >= 0 && it != null && !it.getType().isAir() && !refused(it)) h.kit.slots.put(idx, it.clone());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder(false);
        if (holder instanceof ListMenu || holder instanceof ConfirmMenu) { e.setCancelled(true); return; }
        if (!(holder instanceof EditMenu)) return;
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw >= top || GamesMath.editorToInventory(raw) < 0) { e.setCancelled(true); return; } // only into kit slots
        }
        if (refused(e.getOldCursor())) e.setCancelled(true);
    }

    /** The cursor only ever held a copy: it goes, and never lands in your inventory. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof EditMenu) || !(e.getPlayer() instanceof Player p)) return;
        p.setItemOnCursor(null);
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && !(p.getOpenInventory().getTopInventory().getHolder(false) instanceof EditMenu)) p.setItemOnCursor(null); });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { naming.remove(e.getPlayer().getUniqueId()); }

    /** The new kit's name, typed in chat. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Long at = naming.get(p.getUniqueId());
        if (at == null) return;
        e.setCancelled(true);
        naming.remove(p.getUniqueId());
        if (System.currentTimeMillis() - at > 120_000) return;
        String typed = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (typed.equalsIgnoreCase("cancel")) { openList(p); return; }
            String id = GamesMath.kitId(typed);
            if (id == null) { p.sendMessage(ChatColor.RED + "That name has nothing usable in it (letters, numbers, - and _)."); return; }
            if (kits.containsKey(id)) { p.sendMessage(ChatColor.RED + "There's already a kit called " + id + "."); openList(p); return; }
            openEditor(p, new Kit(id, typed.length() > 24 ? typed.substring(0, 24) : typed), true);
        });
    }

    /** On disable: anyone in the editor loses the copy on their cursor. */
    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder(false);
            if (h instanceof EditMenu || h instanceof ListMenu || h instanceof ConfirmMenu) { p.setItemOnCursor(null); p.closeInventory(); }
        }
        naming.clear();
    }
}
