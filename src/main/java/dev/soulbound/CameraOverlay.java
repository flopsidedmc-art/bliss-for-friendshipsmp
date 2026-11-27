package dev.soulbound;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Full-screen overlays (the finale's haze, the Turmoil's red haze), drawn the way a carved pumpkin's blur is: a fake
 * helmet sent only to that player (sendEquipmentChange) whose equippable component has a camera_overlay and no
 * asset_id, so no helmet model is drawn. The player's real helmet is never touched; it's sent back when the overlay
 * ends, on death, on respawn, on a world change and on plugin disable. Titles can't do this: they're drawn at the GUI
 * scale, so one full-screen glyph only fits one GUI scale. Camera overlays are stretched to the screen at any scale,
 * shown in first person only, and have no alpha setting (fades use fainter textures).
 */
final class CameraOverlay implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final Map<String, ItemStack> items = new HashMap<>(); // texture -> its fake helmet
    private final Map<UUID, String> shown = new HashMap<>(); // player -> the texture they see now

    CameraOverlay(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    /** Shows {@code texture} (e.g. blissgems:overlay/gold_0, a path under textures/) to this player only. */
    void show(Player p, String texture) {
        if (p == null || !p.isOnline() || p.isDead() || texture == null) return;
        ItemStack fake = item(texture);
        if (fake == null) return;
        shown.put(p.getUniqueId(), texture);
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, fake);
    }

    /**
     * The same, while they're dead: shown on the death screen until they respawn (onRespawn sends the real helmet
     * back). Sent after the death, since the death itself resets their helmet slot on their screen.
     */
    void showUntilRespawn(Player p, String texture) {
        if (p == null || !p.isOnline() || texture == null) return;
        ItemStack fake = item(texture);
        if (fake == null) return;
        shown.put(p.getUniqueId(), texture);
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, fake);
    }

    /** Ends this player's overlay: their real helmet (or nothing) is sent back. */
    void clear(Player p) {
        if (p == null || shown.remove(p.getUniqueId()) == null) return;
        sendRealHelmet(p);
    }

    void clearAll() {
        for (UUID id : new ArrayList<>(shown.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) sendRealHelmet(p);
        }
        shown.clear();
    }

    boolean showing(Player p) { return p != null && shown.containsKey(p.getUniqueId()); }

    private static void sendRealHelmet(Player p) {
        if (!p.isOnline()) return;
        ItemStack real = p.getInventory().getHelmet();
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, real == null ? new ItemStack(Material.AIR) : real);
    }

    /** A plain item with an equippable HEAD component: the overlay texture, no model (no asset_id), no sound worth hearing. */
    private ItemStack item(String texture) {
        ItemStack cached = items.get(texture);
        if (cached != null) return cached;
        NamespacedKey key = NamespacedKey.fromString(texture);
        if (key == null) {
            plugin.getLogger().warning("Bad overlay texture id: " + texture);
            return null;
        }
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setCameraOverlay(key);
        eq.setModel(null); // no asset_id: nothing is drawn on the head, not even in third person
        eq.setDispensable(false);
        eq.setSwappable(false);
        eq.setDamageOnHurt(false);
        m.setEquippable(eq);
        m.setHideTooltip(true);
        it.setItemMeta(m);
        items.put(texture, it);
        return it;
    }

    // ------------------------------------------------------------------ keeping it right

    /** The real helmet changed (put on, taken off, damaged): the server sends it, so the overlay goes back on next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent e) {
        if (e.getSlot() != EquipmentSlot.HEAD) return;
        Player p = e.getPlayer();
        String texture = shown.get(p.getUniqueId());
        if (texture == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (texture.equals(shown.get(p.getUniqueId()))) show(p, texture); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { clear(e.getEntity()); }

    /** Respawning: the real helmet; a running overlay comes back on its next frame. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (shown.remove(p.getUniqueId()) != null) Bukkit.getScheduler().runTask(plugin, () -> sendRealHelmet(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { shown.remove(e.getPlayer().getUniqueId()); } // nothing to send back: it's only on their screen
}
