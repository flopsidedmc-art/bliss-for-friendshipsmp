package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DecoratedPot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Prophet: Auratus and Heretic don't go in boxes. One tick after a player puts one in any container (a chest, an
 * ender chest, a shulker, a barrel, a hopper, a dropper, a dispenser, a crafter, a furnace, a bundle, an item frame,
 * an armor stand, an allay, a decorated pot ...) it's pulled back out to them (at their feet if they're full) with a
 * trail of particles, the screen dims, a deep bell rings and the Prophet speaks. His lines escalate with each try
 * (ProphetMath); the count is saved in prophet.yml for the season. Only gems that are newly inside something count:
 * where they were is noted when the click or interaction happens and compared one tick later, so a gem he let stay
 * (the "Fine." line) isn't pulled out by an unrelated click.
 */
final class Prophet implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;
    private final File file;
    private final YamlConfiguration data;

    /** Menus that hand their items back when closed: not storage. */
    private static final Set<String> NOT_STORAGE = Set.of("CRAFTING", "WORKBENCH", "ANVIL", "ENCHANTING", "GRINDSTONE",
            "SMITHING", "SMITHING_NEW", "STONECUTTER", "LOOM", "CARTOGRAPHY", "MERCHANT", "BEACON", "CREATIVE", "PLAYER");

    Prophet(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        this.file = new File(plugin.getDataFolder(), "prophet.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private boolean on() { return plugin.getConfig().getBoolean("prophet.enabled", true); }

    private boolean mythic(ItemStack it) { return LooseMythics.droppable(gems, it); }

    // ------------------------------------------------------------------ where the gems are

    /** One place a gem could be put: what's there now ("key|TYPE" -> how many), and taking one back out. */
    private interface Spot {
        Map<String, Integer> census();
        ItemStack take(String key, GemType type);
        Location where();
    }

    private void count(Map<String, Integer> m, String key, ItemStack it) {
        if (mythic(it)) m.merge(key + "|" + gems.getType(it).name(), 1, Integer::sum);
        if (it != null && it.getItemMeta() instanceof BundleMeta b) {
            for (ItemStack in : b.getItems()) if (mythic(in)) m.merge(key + "b|" + gems.getType(in).name(), 1, Integer::sum);
        }
    }

    /** One gem of {@code type} out of a bundle; the bundle is written back. */
    private ItemStack fromBundle(ItemStack bundle, GemType type, java.util.function.Consumer<ItemStack> writeBack) {
        if (bundle == null || !(bundle.getItemMeta() instanceof BundleMeta b)) return null;
        List<ItemStack> items = new ArrayList<>(b.getItems());
        for (int i = 0; i < items.size(); i++) {
            if (mythic(items.get(i)) && gems.getType(items.get(i)) == type) {
                ItemStack gem = items.remove(i);
                b.setItems(items);
                bundle.setItemMeta(b);
                writeBack.accept(bundle);
                return gem;
            }
        }
        return null;
    }

    private static int slotOf(String key, String prefix) {
        try { return Integer.parseInt(key.substring(prefix.length()).replace("b", "")); }
        catch (NumberFormatException e) { return -1; }
    }

    /** An open container (or a block's inventory), plus the bundles in the player's own inventory and on their cursor. */
    private final class InventorySpot implements Spot {
        final Player p;
        final Inventory top; // null: bundles only
        InventorySpot(Player p, Inventory top) { this.p = p; this.top = top != null && storage(top) ? top : null; }

        public Map<String, Integer> census() {
            Map<String, Integer> m = new HashMap<>();
            if (top != null) for (int i = 0; i < top.getSize(); i++) count(m, "t" + i, top.getItem(i));
            ItemStack[] inv = p.getInventory().getContents();
            for (int i = 0; i < inv.length; i++) bundleOnly(m, "p" + i, inv[i]); // a gem in your own inventory is fine; in a bundle there it isn't
            bundleOnly(m, "c", p.getItemOnCursor());
            return m;
        }

        private void bundleOnly(Map<String, Integer> m, String key, ItemStack it) {
            if (it == null || !(it.getItemMeta() instanceof BundleMeta b)) return;
            for (ItemStack in : b.getItems()) if (mythic(in)) m.merge(key + "b|" + gems.getType(in).name(), 1, Integer::sum);
        }

        public ItemStack take(String key, GemType type) {
            boolean bundle = key.endsWith("b");
            if (key.startsWith("t") && top != null) {
                int slot = slotOf(key, "t");
                if (slot < 0 || slot >= top.getSize()) return null;
                ItemStack it = top.getItem(slot);
                if (bundle) return fromBundle(it, type, b -> top.setItem(slot, b));
                if (!mythic(it) || gems.getType(it) != type) return null;
                top.setItem(slot, null);
                return it;
            }
            if (key.startsWith("p") && bundle) {
                int slot = slotOf(key, "p");
                return slot < 0 ? null : fromBundle(p.getInventory().getItem(slot), type, b -> p.getInventory().setItem(slot, b));
            }
            if (key.equals("cb")) return fromBundle(p.getItemOnCursor(), type, p::setItemOnCursor);
            return null;
        }

        public Location where() {
            Location l = top == null ? null : top.getLocation();
            if (l != null) return l.clone().add(0.5, 0.5, 0.5);
            Block b = p.getTargetBlockExact(6);
            return b != null ? b.getLocation().add(0.5, 0.5, 0.5) : p.getEyeLocation().add(p.getLocation().getDirection().multiply(1.5));
        }
    }

    /** An item frame, an armor stand, an allay (or any creature or cart that can hold items). */
    private final class EntitySpot implements Spot {
        final Entity e;
        EntitySpot(Entity e) { this.e = e; }

        public Map<String, Integer> census() {
            Map<String, Integer> m = new HashMap<>();
            if (e instanceof ItemFrame f) count(m, "frame", f.getItem());
            if (e instanceof LivingEntity le && le.getEquipment() != null) {
                for (EquipmentSlot s : EquipmentSlot.values()) {
                    try { count(m, "eq:" + s.name(), le.getEquipment().getItem(s)); } catch (RuntimeException ignored) { } // a slot it hasn't got
                }
            }
            if (e instanceof InventoryHolder h) {
                Inventory inv = h.getInventory();
                for (int i = 0; i < inv.getSize(); i++) count(m, "inv" + i, inv.getItem(i));
            }
            return m;
        }

        public ItemStack take(String key, GemType type) {
            boolean bundle = key.endsWith("b");
            String k = bundle ? key.substring(0, key.length() - 1) : key;
            if (k.equals("frame") && e instanceof ItemFrame f) {
                ItemStack it = f.getItem();
                if (bundle) return fromBundle(it, type, b -> f.setItem(b, false));
                if (!mythic(it) || gems.getType(it) != type) return null;
                f.setItem(null, false);
                return it;
            }
            if (k.startsWith("eq:") && e instanceof LivingEntity le && le.getEquipment() != null) {
                EntityEquipment eq = le.getEquipment();
                EquipmentSlot s;
                ItemStack it;
                try { s = EquipmentSlot.valueOf(k.substring(3)); it = eq.getItem(s); } catch (RuntimeException ex) { return null; }
                if (bundle) return fromBundle(it, type, b -> eq.setItem(s, b));
                if (!mythic(it) || gems.getType(it) != type) return null;
                eq.setItem(s, null);
                return it;
            }
            if (k.startsWith("inv") && e instanceof InventoryHolder h) {
                int slot = slotOf(k, "inv");
                Inventory inv = h.getInventory();
                if (slot < 0 || slot >= inv.getSize()) return null;
                ItemStack it = inv.getItem(slot);
                if (bundle) return fromBundle(it, type, b -> inv.setItem(slot, b));
                if (!mythic(it) || gems.getType(it) != type) return null;
                inv.setItem(slot, null);
                return it;
            }
            return null;
        }

        public Location where() { return e.getLocation().add(0, e.getHeight() / 2, 0); }
    }

    static boolean storage(Inventory top) {
        return top != null && !NOT_STORAGE.contains(top.getType().name());
    }

    // ------------------------------------------------------------------ what starts a check

    /** Notes where the gems are now and looks again next tick: anything newly inside is refused. */
    private void checkSoon(Player p, Spot spot) {
        if (!on()) return;
        Map<String, Integer> before = spot.census();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Map<String, Integer> after = spot.census();
            for (Map.Entry<String, Integer> en : after.entrySet()) {
                int added = en.getValue() - before.getOrDefault(en.getKey(), 0);
                if (added <= 0) continue;
                String[] kt = en.getKey().split("\\|");
                GemType type;
                try { type = GemType.valueOf(kt[1]); } catch (IllegalArgumentException ex) { continue; }
                for (int i = 0; i < added; i++) refuse(p, spot, kt[0], type);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean involved = mythic(e.getCurrentItem()) || mythic(e.getCursor())
                || e.getHotbarButton() >= 0 && mythic(p.getInventory().getItem(e.getHotbarButton()))
                || e.getClick() == ClickType.SWAP_OFFHAND && mythic(p.getInventory().getItemInOffHand());
        if (involved) checkSoon(p, new InventorySpot(p, e.getView().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && mythic(e.getOldCursor())) checkSoon(p, new InventorySpot(p, e.getView().getTopInventory()));
    }

    /** An item frame, an allay, a creature: right-clicked with a gem in that hand. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = e.getHand() == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (mythic(hand)) checkSoon(p, new EntitySpot(e.getRightClicked()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (mythic(e.getPlayerItem())) checkSoon(e.getPlayer(), new EntitySpot(e.getRightClicked()));
    }

    /** Blocks that take an item from your hand without a menu (a decorated pot). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !mythic(e.getItem())) return;
        BlockState st = e.getClickedBlock().getState();
        Inventory inv = st instanceof DecoratedPot pot ? pot.getInventory() : st instanceof InventoryHolder h ? h.getInventory() : null;
        if (inv != null) checkSoon(e.getPlayer(), new InventorySpot(e.getPlayer(), inv));
    }

    // ------------------------------------------------------------------ the refusal

    private void refuse(Player p, Spot spot, String key, GemType type) {
        var c = plugin.getConfig();
        List<String> lines = c.getStringList("prophet.lines");
        if (lines.isEmpty()) lines = List.of("You are not to do that.");
        String path = "players." + p.getUniqueId();
        long now = System.currentTimeMillis();
        ProphetMath.Reply r = ProphetMath.next(data.getInt(path + ".count", 0), data.getLong(path + ".last", 0L), now,
                (long) (c.getDouble("prophet.line-cooldown-seconds", 2.0) * 1000),
                (long) (c.getDouble("prophet.return-after-days", 7.0) * 86_400_000L),
                lines.size(), c.getInt("prophet.keep-on", 9), c.getInt("prophet.glow-on", 10));
        if (!r.keep()) {
            Location from = spot.where();
            ItemStack gem = spot.take(key, type);
            if (gem == null) return; // already gone (taken back out by hand, say)
            giveBack(p, gem);
            trail(from, p);
        }
        if (r.silent()) return;
        data.set(path + ".count", r.count());
        data.set(path + ".last", now);
        save();
        String text = r.welcomeBack() ? c.getString("prophet.welcome-back", "We have done this before.") : lines.get(r.line() - 1);
        speak(p, text);
        GemSounds.playTo(plugin, "prophet.refuse", p);
        dim(p);
        if (r.glow()) {
            int ticks = (int) Math.round(c.getDouble("prophet.glow-seconds", 30) * 20);
            if (ticks > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, true, false, true));
        }
    }

    private void giveBack(Player p, ItemStack gem) {
        for (ItemStack left : p.getInventory().addItem(gem).values()) {
            Item dropped = p.getWorld().dropItem(p.getLocation(), left);
            dropped.setThrower(p.getUniqueId());
            dropped.setPickupDelay(10);
        }
    }

    private void speak(Player p, String text) {
        var c = plugin.getConfig();
        TextColor col = TextColor.fromHexString(c.getString("prophet.color", "#B9A7D9"));
        if (col == null) col = TextColor.color(0xB9A7D9);
        Component line = Component.text(c.getString("prophet.glyph", ""), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" " + text, col).decoration(TextDecoration.ITALIC, c.getBoolean("prophet.italic", true)));
        p.sendMessage(line);
    }

    private void dim(Player p) {
        String how = plugin.getConfig().getString("prophet.dim", "darkness");
        int ticks = Math.max(1, plugin.getConfig().getInt("prophet.dim-ticks", 40));
        PotionEffectType type = "blindness".equalsIgnoreCase(how) ? PotionEffectType.BLINDNESS
                : "darkness".equalsIgnoreCase(how) ? PotionEffectType.DARKNESS : null;
        if (type != null) p.addPotionEffect(new PotionEffect(type, ticks, 0, true, false, false));
    }

    /** A short trail from the container to the player, drawn over 8 ticks. */
    private void trail(Location from, Player p) {
        if (from == null || from.getWorld() == null || !from.getWorld().equals(p.getWorld())) return;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(107, 63, 160), 1.1f);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final double t = i / (double) steps;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !p.getWorld().equals(from.getWorld())) return;
                Location to = p.getLocation().add(0, 1.0, 0);
                Vector d = to.toVector().subtract(from.toVector());
                Location at = from.clone().add(d.multiply(t));
                from.getWorld().spawnParticle(Particle.DUST, at, 3, 0.06, 0.06, 0.06, 0, dust);
                from.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 1, 0.02, 0.02, 0.02, 0.01);
            }, i);
        }
    }

    void save() {
        try { data.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Couldn't save prophet.yml: " + e.getMessage()); }
    }
}
