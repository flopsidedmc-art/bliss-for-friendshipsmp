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

    /** One player's animated overlay (OverlayAnim): the Gold Gem repurpose. */
    private static final class Anim {
        String style;
        int frames, frameTicks, fadeIn, steps, tick;
        final int[] state = new int[2];
        boolean throughDeath;
    }

    private final Map<UUID, Anim> anims = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask animTask; // runs only while someone has an animation

    CameraOverlay(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    /** Shows {@code texture} (e.g. blissgems:overlay/gold_0, a path under textures/) to this player only. */
    void show(Player p, String texture) {
        if (p == null || !p.isOnline() || p.isDead() || texture == null) return;
        if (anims.containsKey(p.getUniqueId())) return; // their own animation (the repurpose) has their screen
        ItemStack fake = item(texture);
        if (fake == null) return;
        shown.put(p.getUniqueId(), texture);
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, fake);
    }

    /**
     * An animated overlay for this player only (OverlayAnim): fade in through textures/overlay/&lt;style&gt;_fade_1..steps
     * over fadeIn ticks, then loop &lt;style&gt;_0..frames-1, one every frameTicks, until it's cleared, they log out or
     * respawn. With {@code throughDeath} it keeps playing on the death screen.
     */
    void play(Player p, String style, int frames, int frameTicks, int fadeIn, int steps, boolean throughDeath) {
        if (p == null || !p.isOnline() || style == null || !plugin.isEnabled()) return;
        Anim a = new Anim();
        a.style = style;
        a.frames = Math.max(1, frames);
        a.frameTicks = Math.max(1, frameTicks);
        a.fadeIn = Math.max(0, fadeIn);
        a.steps = Math.max(0, steps);
        a.throughDeath = throughDeath;
        anims.put(p.getUniqueId(), a);
        advance(p, a); // the first step now
        if (animTask == null) animTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animTick, 1L, 1L);
    }

    boolean playing(Player p) { return p != null && anims.containsKey(p.getUniqueId()); }

    private void animTick() {
        for (UUID id : new ArrayList<>(anims.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Anim a = anims.get(id);
            if (p == null || a == null) { anims.remove(id); continue; }
            advance(p, a);
        }
        if (anims.isEmpty() && animTask != null) { animTask.cancel(); animTask = null; } // nothing left: no task keeps running
    }

    private void advance(Player p, Anim a) {
        a.tick++;
        String texture = OverlayAnim.step(a.tick, a.style, a.frames, a.frameTicks, a.fadeIn, a.steps, true, a.state);
        if (texture != null) send(p, texture);
    }

    /** Sends a texture even on the death screen (an animation that plays through death). */
    private void send(Player p, String texture) {
        ItemStack fake = item(texture);
        if (fake == null || !p.isOnline()) return;
        shown.put(p.getUniqueId(), texture);
        p.sendEquipmentChange(p, EquipmentSlot.HEAD, fake);
    }

    /** Ends this player's overlay: their real helmet (or nothing) is sent back. */
    void clear(Player p) {
        if (p == null) return;
        anims.remove(p.getUniqueId());
        if (shown.remove(p.getUniqueId()) == null) return;
        sendRealHelmet(p);
    }

    void clearAll() {
        anims.clear();
        if (animTask != null) { animTask.cancel(); animTask = null; }
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

    /** Death: the overlay goes, unless it's an animation that plays through the death screen (sent again after the death resets their helmet). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Anim a = anims.get(p.getUniqueId());
        if (a == null || !a.throughDeath) { clear(p); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            String now = shown.get(p.getUniqueId());
            if (anims.get(p.getUniqueId()) == a && now != null) send(p, now);
        });
    }

    /** Respawning: any animation ends and the real helmet comes back; a running overlay (the finale) comes back on its next frame. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        anims.remove(p.getUniqueId());
        if (shown.remove(p.getUniqueId()) != null) Bukkit.getScheduler().runTask(plugin, () -> sendRealHelmet(p));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { // nothing to send back: it's only on their screen
        anims.remove(e.getPlayer().getUniqueId());
        shown.remove(e.getPlayer().getUniqueId());
    }
}
