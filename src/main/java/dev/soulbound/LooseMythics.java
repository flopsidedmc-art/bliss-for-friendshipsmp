package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Auratus and Heretic are no longer soulbound: they can be dropped and handed over (and still drop on death). On the
 * ground they never despawn, glow, and can't be destroyed: lava, fire, cactus and the void send them back to whoever
 * last had them (or to the main world's spawn if that player is offline or has no room). Hoppers and mobs can't take
 * them. The Relay and the Revenant mace aren't touched here.
 */
final class LooseMythics implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;
    private final NamespacedKey holderKey; // on the item entity: who last had it
    private final Set<UUID> tracked = new HashSet<>();
    private final Map<GemType, UUID> dying = new EnumMap<>(GemType.class); // whose death is dropping it this tick
    private final Map<GemType, Integer> dyingTick = new EnumMap<>(GemType.class);
    private final Set<UUID> rescuing = new HashSet<>(); // sent back next tick (not from inside its own damage tick)
    private BukkitTask task;

    LooseMythics(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        this.holderKey = new NamespacedKey(plugin, "mythic_last_holder");
    }

    /** Auratus or Heretic (not the Relay, not the Revenant mace). */
    static boolean droppable(GemManager gems, ItemStack it) {
        if (it == null || !gems.isGem(it) || !gems.isMythic(it)) return false;
        GemType t = gems.getType(it);
        return t == GemType.AURATUS || t == GemType.HERETIC;
    }

    boolean droppable(ItemStack it) { return droppable(gems, it); }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Item item : w.getEntitiesByClass(Item.class)) if (droppable(item.getItemStack())) protect(item);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20L, 5L);
    }

    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        tracked.clear();
        rescuing.clear();
    }

    private void protect(Item item) {
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        tracked.add(item.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        ItemStack st = item.getItemStack();
        if (!droppable(st)) return;
        protect(item);
        UUID holder = item.getThrower();
        GemType type = gems.getType(st);
        if (holder == null && dyingTick.getOrDefault(type, -1) == Bukkit.getCurrentTick()) holder = dying.get(type);
        if (holder != null) item.getPersistentDataContainer().set(holderKey, PersistentDataType.STRING, holder.toString());
    }

    /** Death drops have no thrower: remember whose they are for the spawn that follows in the same tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        for (ItemStack d : e.getDrops()) {
            if (!droppable(d)) continue;
            GemType t = gems.getType(d);
            dying.put(t, e.getEntity().getUniqueId());
            dyingTick.put(t, Bukkit.getCurrentTick());
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Item item && droppable(item.getItemStack())) protect(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDespawn(ItemDespawnEvent e) {
        if (droppable(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    /** Never destroyed. Lava, fire, cactus and the void send it back; anything else (an explosion) just misses. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item item) || !droppable(item.getItemStack())) return;
        e.setCancelled(true);
        switch (e.getCause()) {
            case LAVA, FIRE, FIRE_TICK, CONTACT, VOID, CAMPFIRE, HOT_FLOOR -> rescueSoon(item);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent e) {
        if (e.getEntity() instanceof Item item && droppable(item.getItemStack())) e.setCancelled(true);
    }

    /** Hoppers (and hopper minecarts) don't take one off the ground. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopper(InventoryPickupItemEvent e) {
        if (droppable(e.getItem().getItemStack())) e.setCancelled(true);
    }

    /** Only players pick them up: a mob or an allay that walked off with one could despawn with it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player) && droppable(e.getItem().getItemStack())) e.setCancelled(true);
    }

    /** Every 5 ticks: one that fell below the world goes back before the void deletes it. */
    private void sweep() {
        for (UUID id : new HashSet<>(tracked)) {
            Entity en = Bukkit.getEntity(id);
            if (!(en instanceof Item item) || !item.isValid()) { tracked.remove(id); continue; } // picked up, or unloaded (it comes back on load)
            if (item.getLocation().getY() < item.getWorld().getMinHeight()) rescueSoon(item);
        }
    }

    private void rescueSoon(Item item) {
        if (!rescuing.add(item.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            rescuing.remove(item.getUniqueId());
            rescue(item);
        });
    }

    /** Back to whoever last had it: into their inventory, or (offline, or no room) to the main world's spawn. */
    private void rescue(Item item) {
        if (!item.isValid()) return;
        ItemStack st = item.getItemStack();
        String name = gems.getType(st) == GemType.AURATUS ? GemNames.tx("Auratus") : GemNames.tx("Heretic");
        Player holder = lastHolder(item);
        if (holder != null && holder.getInventory().firstEmpty() >= 0) {
            item.remove();
            tracked.remove(item.getUniqueId());
            holder.getInventory().addItem(st);
            holder.sendMessage(ChatColor.GOLD + "The " + name + " gem refused to be destroyed. It has come back to you.");
            return;
        }
        World main = Bukkit.getWorlds().get(0);
        Location spawn = main.getSpawnLocation().add(0.5, 0.5, 0.5);
        item.setFireTicks(0);
        item.setVelocity(new Vector());
        item.teleport(spawn);
        if (holder != null) holder.sendMessage(ChatColor.GOLD + "The " + name + " gem refused to be destroyed. Your inventory is full: it waits at spawn.");
    }

    private Player lastHolder(Item item) {
        String s = item.getPersistentDataContainer().get(holderKey, PersistentDataType.STRING);
        UUID id = null;
        try { if (s != null) id = UUID.fromString(s); } catch (IllegalArgumentException ignored) { }
        if (id == null) id = item.getThrower();
        Player p = id == null ? null : Bukkit.getPlayer(id);
        return p != null && p.isOnline() && !p.isDead() ? p : null;
    }
}
