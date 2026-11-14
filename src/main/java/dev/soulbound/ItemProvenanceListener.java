package dev.soulbound;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Item provenance lore for weapons/tools/armor (user request):
 * "STOLEN by <player>" (red) on kill pickup or chest take,
 * "GIFTED by <player>" (yellow) on dropped-item pickup,
 * "STORED by <player>" (blue) only while inside storages.
 */
public class ItemProvenanceListener implements Listener {
    private final NamespacedKey kProvType;
    private final NamespacedKey kProvName;
    private final NamespacedKey kDropOwner;
    private final NamespacedKey kDropName;
    private final NamespacedKey kDeathVictim;
    private final NamespacedKey kDeathName;

    public ItemProvenanceListener(Plugin plugin) {
        kProvType = new NamespacedKey("soulbound-gems", "prov_type");
        kProvName = new NamespacedKey("soulbound-gems", "prov_name");
        kDropOwner = new NamespacedKey("soulbound-gems", "prov_drop_owner");
        kDropName = new NamespacedKey("soulbound-gems", "prov_drop_name");
        kDeathVictim = new NamespacedKey("soulbound-gems", "prov_death_victim");
        kDeathName = new NamespacedKey("soulbound-gems", "prov_death_name");
    }

    public static boolean isTracked(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        String n = item.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_HELMET")
                || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.equals("SHIELD") || n.equals("ELYTRA");
    }

    private static String strip(String s) {
        return ChatColor.stripColor(s == null ? "" : s).toLowerCase();
    }

    private void setLore(ItemStack item, String kind, String name) {
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        String display;
        ChatColor color;
        switch (kind) {
            case "stolen" -> { display = "STOLEN"; color = ChatColor.RED; }
            case "gifted" -> { display = "GIFTED"; color = ChatColor.YELLOW; }
            default -> { display = "STORED"; color = ChatColor.BLUE; kind = "stored"; }
        }
        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.removeIf(line -> {
            String s = strip(line);
            return s.startsWith("stolen by ") || s.startsWith("gifted by ") || s.startsWith("stored by ");
        });
        if (name != null) {
            lore.add("" + color + display + " " + ChatColor.GRAY + "by " + name);
        }
        m.setLore(lore);
        m.getPersistentDataContainer().set(kProvType, PersistentDataType.STRING, kind == null ? "" : kind);
        m.getPersistentDataContainer().set(kProvName, PersistentDataType.STRING, name == null ? "" : name);
        item.setItemMeta(m);
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

    // ---------- drops: Q-drop = gift, death drop = steal ----------

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack s = e.getItemDrop().getItemStack();
        if (!isTracked(s)) return;
        e.getItemDrop().getPersistentDataContainer().set(kDropOwner, PersistentDataType.STRING,
                e.getPlayer().getUniqueId().toString());
        e.getItemDrop().getPersistentDataContainer().set(kDropName, PersistentDataType.STRING,
                e.getPlayer().getName());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        for (ItemStack drop : e.getDrops()) {
            if (!isTracked(drop)) continue;
            ItemMeta m = drop.getItemMeta();
            if (m == null) continue;
            m.getPersistentDataContainer().set(kDeathVictim, PersistentDataType.STRING,
                    victim.getUniqueId().toString());
            m.getPersistentDataContainer().set(kDeathName, PersistentDataType.STRING, victim.getName());
            drop.setItemMeta(m);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player picker)) return;
        Item entityItem = e.getItem();
        ItemStack s = entityItem.getItemStack();
        if (!isTracked(s)) return;
        String dropOwner = entityItem.getPersistentDataContainer().get(kDropOwner, PersistentDataType.STRING);
        if (dropOwner != null && !dropOwner.equals(picker.getUniqueId().toString())) {
            String nm = entityItem.getPersistentDataContainer().get(kDropName, PersistentDataType.STRING);
            if (nm == null) nm = "Unknown";
            setLore(s, "gifted", nm);
            entityItem.setItemStack(s);
            return;
        }
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            String dv = m.getPersistentDataContainer().get(kDeathVictim, PersistentDataType.STRING);
            if (dv != null && !dv.equals(picker.getUniqueId().toString())) {
                String nm = m.getPersistentDataContainer().get(kDeathName, PersistentDataType.STRING);
                if (nm == null) nm = nameOf(safeUuid(dv), "Unknown");
                setLore(s, "stolen", nm);
                // clear death tag so it doesn't re-trigger
                ItemMeta m2 = s.getItemMeta();
                m2.getPersistentDataContainer().remove(kDeathVictim);
                m2.getPersistentDataContainer().remove(kDeathName);
                s.setItemMeta(m2);
                entityItem.setItemStack(s);
            }
        }
    }

    private static UUID safeUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception ex) { return null; }
    }

    // ---------- storages ----------

    private static boolean isContainer(Inventory inv) {
        if (inv == null) return false;
        InventoryType t = inv.getType();
        return t == InventoryType.CHEST || t == InventoryType.ENDER_CHEST || t == InventoryType.BARREL
                || t == InventoryType.SHULKER_BOX || t == InventoryType.HOPPER
                || t == InventoryType.DISPENSER || t == InventoryType.DROPPER;
    }

    @EventHandler
    public void onChestClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getInventory();
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        boolean topIsContainer = isContainer(top);

        switch (e.getAction()) {
            case MOVE_TO_OTHER_INVENTORY -> {
                ItemStack moving = e.getCurrentItem();
                if (!isTracked(moving)) return;
                if (clicked.getType() == InventoryType.PLAYER && topIsContainer) {
                    // shift-click own item into storage -> stored by me
                    setLore(moving, "stored", p.getName());
                } else if (isContainer(clicked) && top.getType() == InventoryType.PLAYER) {
                    // shift-click taking from storage -> stolen from storer
                    takeFromStorage(p, moving);
                }
            }
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, COLLECT_TO_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (isContainer(clicked) && isTracked(e.getCurrentItem())
                        && (e.getCursor() == null || e.getCursor().getType() == Material.AIR)) {
                    takeFromStorage(p, e.getCurrentItem());
                } else if (!isContainer(clicked) && isContainer(top) && isTracked(e.getCursor())) {
                    // placing cursor item into container -> stored by me
                    setLore(e.getCursor(), "stored", p.getName());
                }
            }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                if (isContainer(clicked) && isTracked(e.getCursor())) {
                    setLore(e.getCursor(), "stored", p.getName());
                }
                if (isContainer(clicked) && isTracked(e.getCurrentItem())
                        && e.getCursor() != null && e.getCursor().getType() != Material.AIR) {
                    // swapping: the item coming out of storage is taken
                    takeFromStorage(p, e.getCurrentItem());
                }
            }
            default -> { }
        }
    }

    private void takeFromStorage(Player taker, ItemStack stack) {
        if (!isTracked(stack)) return;
        ItemMeta m = stack.getItemMeta();
        String storer = null;
        if (m != null) {
            String pt = m.getPersistentDataContainer().get(kProvType, PersistentDataType.STRING);
            String pn = m.getPersistentDataContainer().get(kProvName, PersistentDataType.STRING);
            if ("stored".equals(pt) && pn != null && !pn.isEmpty()) storer = pn;
        }
        if (storer != null && !storer.equals(taker.getName())) {
            setLore(stack, "stolen", storer);
        } else if (storer == null) {
            // took from a chest with no known storer: leave existing gift/steal lore, just drop "stored by"
            if (m != null && m.hasLore()) {
                boolean hadStored = false;
                for (String line : m.getLore()) {
                    if (strip(line).startsWith("stored by ")) { hadStored = true; break; }
                }
                if (hadStored) setLore(stack, null, null);
            }
        } else {
            // taking back your own stored item: clear the stored line
            setLore(stack, null, null);
        }
    }

    @EventHandler
    public void onDragIntoStorage(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isTracked(e.getOldCursor())) return;
        for (int slot : e.getRawSlots()) {
            Inventory inv = null;
            try { inv = e.getView().getInventory(slot); } catch (Exception ex) { inv = null; }
            if (isContainer(inv)) {
                setLore(e.getOldCursor(), "stored", p.getName());
                return;
            }
        }
    }
}
