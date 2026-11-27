package net.friendshipsmp.energygames;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Creates and recognises the plugin's items. */
final class Items {
    private final EnergyGamesPlugin plugin;
    final NamespacedKey typeKey;
    final NamespacedKey trackerIdKey;
    final NamespacedKey trackerExpiresKey;

    static final String TYPE_TRACKER = "tracker";
    static final String TYPE_RULE = "rule_request";

    Items(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "item_type");
        this.trackerIdKey = new NamespacedKey(plugin, "tracker_id");
        this.trackerExpiresKey = new NamespacedKey(plugin, "tracker_expires");
    }

    String trackerName() {
        return Text.color(plugin.getConfig().getString("items.tracker-name"));
    }

    String ruleRequestName() {
        return Text.color(plugin.getConfig().getString("items.rule-request-name"));
    }

    ItemStack tracker() {
        return named(Material.COMPASS, trackerName(), TYPE_TRACKER);
    }

    ItemStack ruleRequest() {
        return named(Material.PAPER, ruleRequestName(), TYPE_RULE);
    }

    private ItemStack named(Material material, String name, String type) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    /** Tracker made by this plugin, or a compass with the tracker name (e.g. made by the old script). */
    boolean isTracker(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String type = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (TYPE_TRACKER.equals(type)) return true;
        return meta.hasDisplayName() && meta.getDisplayName().contains(trackerName());
    }

    String trackerId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(trackerIdKey, PersistentDataType.STRING);
    }

    long trackerExpires(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Long v = item.getItemMeta().getPersistentDataContainer().get(trackerExpiresKey, PersistentDataType.LONG);
        return v == null ? 0 : v;
    }

    /** Marks a tracker as used: expiry time + unbreaking glint (like the original script). */
    void activateTracker(ItemStack item, long expiresAt) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(trackerExpiresKey, PersistentDataType.LONG, expiresAt);
        pdc.set(typeKey, PersistentDataType.STRING, TYPE_TRACKER);
        Enchantment unbreaking = unbreaking();
        if (unbreaking != null) meta.addEnchant(unbreaking, 1, true);
        item.setItemMeta(meta);
    }

    void setTrackerId(ItemStack item, String id) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(trackerIdKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    @SuppressWarnings("deprecation")
    private static Enchantment unbreaking() {
        NamespacedKey key = NamespacedKey.minecraft("unbreaking");
        try {
            Enchantment e = Registry.ENCHANTMENT.get(key);
            if (e != null) return e;
        } catch (Throwable ignored) {
        }
        return Enchantment.getByKey(key);
    }

    /** The trade currency. Saved item → Skript getEnergyToken() → config fallback. */
    ItemStack energyToken() {
        ItemStack saved = plugin.state().savedToken;
        if (saved != null && saved.getType() != Material.AIR) {
            ItemStack copy = saved.clone();
            copy.setAmount(1);
            return copy;
        }
        if (plugin.skript().hasFunction("getEnergyToken")) {
            ItemStack fromSkript = SkriptBridge.toItemStack(plugin.skript().callFunction("getEnergyToken"));
            if (fromSkript != null && fromSkript.getType() != Material.AIR) {
                fromSkript.setAmount(1);
                return fromSkript;
            }
        }
        Material mat = Material.matchMaterial(plugin.getConfig().getString("energy-token.fallback.material", "NETHER_STAR"));
        if (mat == null || mat.isAir()) mat = Material.NETHER_STAR;
        ItemStack item = new ItemStack(mat);
        String name = plugin.getConfig().getString("energy-token.fallback.name", "");
        if (!name.isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(Text.color(name));
            item.setItemMeta(meta);
        }
        return item;
    }
}
