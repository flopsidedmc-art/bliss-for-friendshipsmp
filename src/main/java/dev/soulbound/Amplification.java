package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wealth's Amplification (sneak + right-click): +1 to every enchantment level for wealth.amplification.seconds (30) for
 * the caster and every trusted player within wealth.amplification.radius (8). The buff is real enchantment levels on
 * the items, so each buffed item carries its levels from before (amp_orig): it goes back
 * when the time ends, on death, logout and plugin disable, and wherever it turns up later (on join, in a container, a
 * drop or a pickup by someone who isn't buffed), so no item can stay enchanted higher. Buffs never stack: casting again
 * refreshes the time.
 */
final class Amplification implements Listener {
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xFFC83D), 0.6f);

    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey kOrig;
    private final Map<UUID, Long> until = new HashMap<>(); // buffed player -> when it ends
    private BukkitTask task;
    private long ticks;

    Amplification(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.kOrig = new NamespacedKey(plugin, "amp_orig");
    }

    private double radius() { return plugin.getConfig().getDouble("wealth.amplification.radius", 8); }

    private int seconds() { return Math.max(1, plugin.getConfig().getInt("wealth.amplification.seconds", 30)); }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L); }

    /** Plugin disable: every buffed item back to normal. */
    void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) restorePlayer(p);
        until.clear();
    }

    boolean buffed(Player p) {
        Long u = until.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ casting

    /** The caster and their trusted players within the radius: +1 enchantments, a message, sparkles. */
    void cast(Player caster) {
        List<AmpMath.Near> near = new ArrayList<>();
        Map<UUID, Player> byId = new HashMap<>();
        for (Player o : caster.getWorld().getPlayers()) {
            if (o.equals(caster) || o.isDead() || o.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            near.add(new AmpMath.Near(o.getUniqueId(), o.getLocation().distanceSquared(caster.getLocation()),
                    plugin.listener() != null && plugin.listener().isTrusted(caster, o)));
            byId.put(o.getUniqueId(), o);
        }
        byId.put(caster.getUniqueId(), caster);
        long end = AmpMath.refreshed(System.currentTimeMillis(), seconds() * 1000L);
        String by = plugin.shownName(caster);
        for (UUID id : AmpMath.targets(caster.getUniqueId(), near, radius())) {
            Player t = byId.get(id);
            until.put(id, end); // a second cast only refreshes the time
            boostPlayer(t);
            t.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§6" + SmallCaps.of("Amplified by ") + "§f" + by
                    + "§6" + SmallCaps.of(": +1 enchantments for " + seconds() + "s")));
            t.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, t.getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0);
            t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, 1, 0), 24, 0.5, 0.8, 0.5, 0, GOLD);
            GemSounds.play(plugin, "wealth.amplification", t.getLocation());
        }
    }

    /** Never one of the plugin's own items: gems, mythics, the Revenant, the Prismatic Edge. */
    private boolean boostable(ItemStack s) {
        if (s == null || s.getType().isAir() || s.getEnchantments().isEmpty()) return false;
        if (plugin.gems() != null && plugin.gems().isGem(s)) return false;
        if (plugin.revenant() != null && plugin.revenant().isLiveItem(s)) return false;
        return plugin.prismaticEdge() == null || !plugin.prismaticEdge().isEdge(s);
    }

    /** Every enchanted item they carry (armour and off hand too), once: an item already buffed stays as it is. */
    private void boostPlayer(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!boostable(s)) continue;
            ItemMeta m = s.getItemMeta();
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            if (pdc.has(kOrig, PersistentDataType.STRING)) continue; // never stacks
            Map<String, Integer> orig = new LinkedHashMap<>();
            for (Map.Entry<Enchantment, Integer> en : m.getEnchants().entrySet()) {
                int next = AmpMath.boosted(en.getValue());
                if (next == en.getValue()) continue;
                orig.put(en.getKey().getKey().toString(), en.getValue());
                m.addEnchant(en.getKey(), next, true);
            }
            if (orig.isEmpty()) continue;
            pdc.set(kOrig, PersistentDataType.STRING, AmpMath.encode(orig));
            s.setItemMeta(m);
            inv.setItem(i, s);
        }
    }

    // ------------------------------------------------------------------ taking it off

    /** One item back to its levels from before. True if it was buffed. */
    boolean restore(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return false;
        ItemMeta m = s.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        String rec = pdc.get(kOrig, PersistentDataType.STRING);
        if (rec == null) return false;
        for (Map.Entry<String, Integer> en : AmpMath.decode(rec).entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(en.getKey());
            Enchantment ench = key == null ? null : Registry.ENCHANTMENT.get(key);
            if (ench == null || !m.hasEnchant(ench)) continue;
            int back = AmpMath.restored(m.getEnchantLevel(ench), en.getValue());
            if (back != m.getEnchantLevel(ench)) { m.removeEnchant(ench); m.addEnchant(ench, back, true); }
        }
        pdc.remove(kOrig);
        s.setItemMeta(m);
        return true;
    }

    private void restoreAll(Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (restore(s)) inv.setItem(i, s);
        }
    }

    /** Their whole inventory (armour, off hand, cursor) back to normal, and the buff gone. */
    void restorePlayer(Player p) {
        until.remove(p.getUniqueId());
        restoreAll(p.getInventory());
        ItemStack cursor = p.getItemOnCursor();
        if (restore(cursor)) p.setItemOnCursor(cursor);
    }

    // ------------------------------------------------------------------ the time

    /** Every half second: the countdown on the action bar, and buffs that ran out come off. */
    private void tick() {
        ticks += 10;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(until.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { until.remove(id); continue; }
            long left = until.get(id) - now;
            if (left <= 0) {
                restorePlayer(p);
                p.sendActionBar(Component.text(SmallCaps.of("Amplification faded"), NamedTextColor.GRAY));
                continue;
            }
            if (!hasOwnHud(p) && !plugin.hudHeld(p)) p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(status(left)));
        }
    }

    private static String status(long leftMs) { return "§6✚ " + SmallCaps.of("Amplified") + " §f" + HudStatus.time(leftMs); }

    /** Added to the end of the gem, mythic and Revenant action bars while buffed. */
    String hudSuffix(Player p) {
        Long u = until.get(p.getUniqueId());
        long left = u == null ? 0 : u - System.currentTimeMillis();
        return left > 0 ? "  §8| " + status(left) : "";
    }

    private boolean hasOwnHud(Player p) {
        GemManager gems = plugin.gems();
        if (gems != null && (gems.isGem(p.getInventory().getItemInMainHand()) || gems.isGem(p.getInventory().getItemInOffHand()))) return true;
        return plugin.revenant() != null && plugin.revenant().hudActive(p);
    }

    // ------------------------------------------------------------------ wherever a buffed item turns up

    /** Logging out: everything back to normal before the inventory is saved, so rejoining finds it normal. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { restorePlayer(e.getPlayer()); }

    /** After a crash: anything still buffed from before goes back. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && !buffed(p)) restorePlayer(p); });
    }

    /** Dying: the buff ends, and what drops (or is kept) is normal. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        for (ItemStack s : e.getDrops()) restore(s);
        restorePlayer(e.getEntity());
    }

    /** An item leaving a buffed player goes back as it leaves. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack s = e.getItemDrop().getItemStack();
        if (restore(s)) e.getItemDrop().setItemStack(s);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || buffed(p)) return;
        ItemStack s = e.getItem().getItemStack();
        if (restore(s)) e.getItem().setItemStack(s);
    }

    /** A container (or anything else opened): what's in it is never buffed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getLocation() != null) restoreAll(e.getInventory());
        if (e.getPlayer() instanceof Player p && !buffed(p)) restorePlayer(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getLocation() == null && !(e.getWhoClicked() instanceof Player p && !buffed(p))) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (top.getLocation() != null) restoreAll(top); // put into a chest: it goes back
            if (e.getWhoClicked() instanceof Player p && p.isOnline() && !buffed(p)) restorePlayer(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getLocation() != null) restoreAll(e.getInventory());
    }
}
