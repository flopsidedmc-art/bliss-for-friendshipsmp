package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Player Tracker compass: target picker GUI, compass updates and the "being tracked" action bar. */
final class TrackerManager implements Listener {
    private final EnergyGamesPlugin plugin;
    /** owner uuid -> active tracking session (one per player, like the script's session counter). */
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static final class Session {
        final UUID target;
        final String trackerId;
        final long expiresAt;

        Session(UUID target, String trackerId, long expiresAt) {
            this.target = target;
            this.trackerId = trackerId;
            this.expiresAt = expiresAt;
        }
    }

    /** Marks the target-picker inventory. */
    private static final class TrackerGui implements InventoryHolder {
        final String trackerId;
        final Map<Integer, UUID> slots = new HashMap<>();
        Inventory inventory;

        TrackerGui(String trackerId) {
            this.trackerId = trackerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    TrackerManager(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- right click

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItem(hand);
        if (!plugin.items().isTracker(item)) return;
        // Avoid handling the same click twice when both hands hold trackers.
        if (hand == EquipmentSlot.OFF_HAND && plugin.items().isTracker(p.getInventory().getItemInMainHand())) return;

        event.setCancelled(true);
        Items items = plugin.items();

        long expires = items.trackerExpires(item);
        if (expires > 0 && System.currentTimeMillis() >= expires) {
            p.getInventory().setItem(hand, null);
            p.sendMessage(plugin.msg("tracker-expired"));
            return;
        }

        String id = items.trackerId(item);
        if (id == null) {
            id = UUID.randomUUID().toString();
            items.setTrackerId(item, id);
            p.getInventory().setItem(hand, item);
        }

        Session current = sessions.get(p.getUniqueId());
        if (current != null && current.trackerId.equals(id)) {
            p.sendMessage(plugin.msg("tracker-already-active"));
            return;
        }

        openGui(p, id);
    }

    private void openGui(Player p, String trackerId) {
        TrackerGui gui = new TrackerGui(trackerId);
        Inventory inv = Bukkit.createInventory(gui, 54, plugin.msg("tracker-gui-title"));
        gui.inventory = inv;

        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (slot >= 54) break;
            if (target.equals(p)) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(plugin.msg("tracker-head-name").replace("{player}", target.getName()));
            List<String> lore = new ArrayList<>();
            lore.add(plugin.msg("tracker-head-lore"));
            meta.setLore(lore);
            head.setItemMeta(meta);
            inv.setItem(slot, head);
            gui.slots.put(slot, target.getUniqueId());
            slot++;
        }
        if (slot == 0) {
            p.sendMessage(plugin.msg("tracker-no-players"));
            return;
        }
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- GUI

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TrackerGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        UUID targetId = gui.slots.get(event.getSlot());
        if (targetId == null) return;

        Player target = Bukkit.getPlayer(targetId);
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        if (target == null || !target.isOnline()) {
            p.sendMessage(plugin.msg("tracker-player-offline"));
            return;
        }
        startTracking(p, gui.trackerId, target);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TrackerGui) event.setCancelled(true);
    }

    private void startTracking(Player p, String trackerId, Player target) {
        Items items = plugin.items();
        PlayerInventory inv = p.getInventory();
        int slot = findTracker(inv, trackerId);
        if (slot < 0) return; // tracker no longer in inventory

        ItemStack item = inv.getItem(slot);
        long expires = items.trackerExpires(item);
        long now = System.currentTimeMillis();
        if (expires > 0 && now >= expires) {
            inv.setItem(slot, null);
            p.sendMessage(plugin.msg("tracker-expired"));
            return;
        }
        if (expires == 0) {
            expires = now + plugin.seconds("tracker", 3600);
            items.activateTracker(item, expires);
            inv.setItem(slot, item);
        }

        sessions.put(p.getUniqueId(), new Session(target.getUniqueId(), trackerId, expires));
        p.setCompassTarget(target.getLocation());
        p.sendMessage(plugin.msg("tracker-started")
                .replace("{player}", target.getName())
                .replace("{time}", Text.human(expires - now)));
    }

    private int findTracker(PlayerInventory inv, String trackerId) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == Material.COMPASS && trackerId.equals(plugin.items().trackerId(it))) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- ticking

    /** Every 2 ticks: point compasses, handle expiry / offline targets. */
    void updateCompasses() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            Session s = e.getValue();
            Player owner = Bukkit.getPlayer(e.getKey());
            if (owner == null) {
                it.remove();
                continue;
            }
            if (now >= s.expiresAt) {
                owner.sendMessage(plugin.msg("tracker-expired"));
                int slot = findTracker(owner.getInventory(), s.trackerId);
                if (slot >= 0) owner.getInventory().setItem(slot, null);
                resetCompass(owner);
                it.remove();
                continue;
            }
            Player target = Bukkit.getPlayer(s.target);
            if (target == null) {
                owner.sendMessage(plugin.msg("tracker-target-offline"));
                resetCompass(owner);
                it.remove();
                continue;
            }
            owner.setCompassTarget(target.getLocation());
        }
    }

    void collectActionBars(Map<UUID, List<String>> bars) {
        long now = System.currentTimeMillis();
        Map<UUID, Long> longest = new HashMap<>();
        for (Session s : sessions.values()) longest.merge(s.target, s.expiresAt, Math::max);
        for (Map.Entry<UUID, Long> e : longest.entrySet()) {
            bars.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(Text.fillTime(plugin.msg("tracker-being-tracked"), e.getValue() - now));
        }
    }

    private void resetCompass(Player p) {
        p.setCompassTarget(p.getWorld().getSpawnLocation());
    }

    void shutdown() {
        for (UUID owner : sessions.keySet()) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null) resetCompass(p);
        }
        sessions.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof TrackerGui) p.closeInventory();
        }
    }
}
