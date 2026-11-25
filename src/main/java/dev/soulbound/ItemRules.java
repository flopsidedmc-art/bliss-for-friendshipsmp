package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The item rules (rules: in config.yml): carry caps on golden apples and cobwebs, tipped arrows and debuff potions
 * banned (and turned into plain arrows / water bottles wherever they're touched), each with its own
 * rules.&lt;rule&gt;.enabled. Ops and creative players are exempt; gem and mythic abilities never come into it.
 */
final class ItemRules implements Listener {
    private final RuleCore core;
    private final SoulboundGemsPlugin plugin;

    private static final Map<Material, String> CAPPED = Map.of(Material.GOLDEN_APPLE, "golden-apples", Material.COBWEB, "cobwebs");

    ItemRules(SoulboundGemsPlugin plugin, RuleCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    // ------------------------------------------------------------------ what's banned

    private boolean tippedBanned(org.bukkit.World w) { return core.on("tipped-arrows", w); }

    /** A drinkable, splash or lingering potion with any effect on rules.debuff-potions.effects. */
    boolean debuffPotion(ItemStack it) {
        if (it == null) return false;
        Material m = it.getType();
        if (m != Material.POTION && m != Material.SPLASH_POTION && m != Material.LINGERING_POTION) return false;
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return false;
        List<String> keys = new ArrayList<>();
        PotionType base = pm.getBasePotionType();
        if (base != null) for (PotionEffect e : base.getPotionEffects()) keys.add(e.getType().getKey().getKey());
        for (PotionEffect e : pm.getCustomEffects()) keys.add(e.getType().getKey().getKey());
        return RuleMath.debuff(keys, plugin.getConfig().getStringList("rules.debuff-potions.effects"));
    }

    /** Banned here: a tipped arrow, or a debuff potion. */
    boolean banned(ItemStack it, org.bukkit.World w) {
        if (it == null) return false;
        if (it.getType() == Material.TIPPED_ARROW) return tippedBanned(w);
        return core.on("debuff-potions", w) && debuffPotion(it);
    }

    /** What a banned item becomes: plain arrows, or plain water bottles (same amount). */
    static ItemStack plain(ItemStack it) {
        if (it.getType() == Material.TIPPED_ARROW) return new ItemStack(Material.ARROW, it.getAmount());
        ItemStack water = new ItemStack(Material.POTION, it.getAmount());
        if (water.getItemMeta() instanceof PotionMeta pm) { pm.setBasePotionType(PotionType.WATER); water.setItemMeta(pm); }
        return water;
    }

    /** Every banned item in an inventory becomes its plain version. True if anything changed. */
    private boolean clean(Inventory inv, org.bukkit.World w) {
        boolean changed = false;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (banned(c[i], w)) { inv.setItem(i, plain(c[i])); changed = true; }
        }
        return changed;
    }

    private void cleanPlayer(Player p) {
        if (core.exempt(p)) return;
        clean(p.getInventory(), p.getWorld());
        ItemStack cursor = p.getItemOnCursor();
        if (banned(cursor, p.getWorld())) p.setItemOnCursor(plain(cursor));
    }

    // ------------------------------------------------------------------ carry caps

    /** How many of {@code m} a player carries: inventory, off hand, armour, cursor and inside bundles. */
    static int carried(Player p, Material m) {
        int n = 0;
        List<ItemStack> all = new ArrayList<>(java.util.Arrays.asList(p.getInventory().getContents())); // empty slots are null
        all.add(p.getItemOnCursor());
        for (ItemStack it : all) {
            if (it == null) continue;
            if (it.getType() == m) n += it.getAmount();
            if (it.getItemMeta() instanceof BundleMeta b) for (ItemStack in : b.getItems()) if (in.getType() == m) n += in.getAmount();
        }
        return n;
    }

    private int cap(String rule) { return (int) core.num(rule, "max-carried", 128); }

    /** Anything over a cap goes back into {@code back} (the container it came from) or drops at their feet. */
    private void enforce(Player p, Inventory back) {
        if (!p.isOnline() || core.exempt(p)) return;
        for (Map.Entry<Material, String> c : CAPPED.entrySet()) {
            if (!core.on(c.getValue(), p.getWorld())) continue;
            int over = RuleMath.over(carried(p, c.getKey()), cap(c.getValue()));
            if (over <= 0) continue;
            int left = over;
            ItemStack cursor = p.getItemOnCursor(); // what they just picked up goes first
            if (cursor != null && cursor.getType() == c.getKey()) {
                int take = Math.min(left, cursor.getAmount());
                cursor.setAmount(cursor.getAmount() - take);
                p.setItemOnCursor(cursor.getAmount() <= 0 ? null : cursor);
                left -= take;
            }
            ItemStack[] inv = p.getInventory().getContents();
            for (int i : RuleMath.TAKE_ORDER) { // the main inventory, then the hotbar, the off hand last
                if (left <= 0) break;
                if (i >= inv.length || inv[i] == null || inv[i].getType() != c.getKey()) continue;
                int take = Math.min(left, inv[i].getAmount());
                inv[i].setAmount(inv[i].getAmount() - take);
                p.getInventory().setItem(i, inv[i].getAmount() <= 0 ? null : inv[i]);
                left -= take;
            }
            int moved = over - left;
            if (moved <= 0) continue;
            ItemStack extra = new ItemStack(c.getKey(), moved);
            Map<Integer, ItemStack> rest = back != null ? back.addItem(extra) : Map.of(0, extra);
            for (ItemStack r : rest.values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
            core.say(p, c.getValue(), "you can carry at most " + cap(c.getValue()) + " " + (c.getKey() == Material.COBWEB ? "cobwebs" : "golden apples"));
        }
    }

    private static final java.util.Set<InventoryType> STORAGE = java.util.EnumSet.of(InventoryType.CHEST, InventoryType.ENDER_CHEST,
            InventoryType.BARREL, InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER);

    /**
     * A real container in the world (a chest, barrel, shulker box, hopper ...): extra goes back into it. Not a crafting
     * table, anvil or villager, and not a plugin menu (those have no location), so menus are never touched.
     */
    private static boolean storage(Inventory top) {
        return top != null && STORAGE.contains(top.getType()) && top.getLocation() != null;
    }

    private void enforceSoon(Player p, Inventory top) {
        Inventory back = storage(top) ? top : null;
        Bukkit.getScheduler().runTask(plugin, () -> enforce(p, back));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || core.exempt(p)) return;
        Item item = e.getItem();
        ItemStack st = item.getItemStack();
        if (banned(st, p.getWorld())) { item.setItemStack(plain(st)); st = item.getItemStack(); } // it comes up plain
        String rule = CAPPED.get(st.getType());
        if (rule == null || !core.on(rule, p.getWorld())) return;
        int fit = RuleMath.fits(carried(p, st.getType()), st.getAmount(), cap(rule));
        if (fit >= st.getAmount()) return;
        e.setCancelled(true);
        if (fit > 0) { // take what fits, leave the rest on the ground
            ItemStack part = st.clone();
            part.setAmount(fit);
            int kept = 0;
            for (ItemStack left : p.getInventory().addItem(part).values()) kept += left.getAmount(); // a full inventory keeps some
            st.setAmount(st.getAmount() - fit + kept);
            item.setItemStack(st);
        }
        core.say(p, rule, "you can carry at most " + cap(rule) + " " + (st.getType() == Material.COBWEB ? "cobwebs" : "golden apples"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent e) {
        if (core.exempt(e.getPlayer()) || !tippedBanned(e.getPlayer().getWorld())) return;
        ItemStack st = e.getItem().getItemStack();
        if (st.getType() == Material.TIPPED_ARROW) e.getItem().setItemStack(plain(st));
    }

    /** Clicks: banned items touched turn plain; afterwards, anything over a carry cap goes back. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || core.exempt(p)) return;
        if (banned(e.getCurrentItem(), p.getWorld())) e.setCurrentItem(plain(e.getCurrentItem()));
        if (banned(e.getCursor(), p.getWorld())) p.setItemOnCursor(plain(e.getCursor()));
        enforceSoon(p, e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !core.exempt(p)) enforceSoon(p, e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && !core.exempt(p)) enforceSoon(p, null); // crafted into your inventory: over the cap drops
    }

    /** Opening a container (or your inventory): the banned items in it turn plain; a villager's banned trades go. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || core.exempt(p)) return;
        Inventory top = e.getInventory();
        if (top.getHolder() instanceof Merchant m) removeTrades(m, p.getWorld());
        else if (storage(top)) clean(top, p.getWorld());
        cleanPlayer(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItem(e.getNewSlot());
        if (!core.exempt(p) && banned(it, p.getWorld())) p.getInventory().setItem(e.getNewSlot(), plain(it));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isOnline()) cleanPlayer(e.getPlayer()); }); }

    // ------------------------------------------------------------------ 4. tipped arrows

    @EventHandler(priority = EventPriority.HIGH)
    public void onRecipe(PrepareItemCraftEvent e) {
        ItemStack r = e.getInventory().getResult();
        if (r == null || r.getType() != Material.TIPPED_ARROW || !(e.getView().getPlayer() instanceof Player p)) return;
        if (!tippedBanned(p.getWorld()) || core.exempt(p)) return;
        e.getInventory().setResult(null);
        core.say(p, "tipped-arrows", "tipped arrows are banned");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (e.getResult().getType() == Material.TIPPED_ARROW && tippedBanned(e.getBlock().getWorld())) e.setCancelled(true);
    }

    private static boolean tippedProjectile(org.bukkit.entity.Entity e) {
        if (!(e instanceof Arrow a)) return false;
        return (a.getBasePotionType() != null && a.getBasePotionType() != PotionType.WATER) || a.hasCustomEffects();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !tippedBanned(p.getWorld()) || core.exempt(p)) return;
        boolean tipped = (e.getConsumable() != null && e.getConsumable().getType() == Material.TIPPED_ARROW) || tippedProjectile(e.getProjectile());
        if (!tipped) return;
        e.setCancelled(true);
        cleanPlayer(p);
        ItemStack bow = e.getBow();
        if (bow != null && bow.getItemMeta() instanceof CrossbowMeta cm && cm.hasChargedProjectiles()) { // a crossbow loaded with one
            List<ItemStack> loaded = new ArrayList<>();
            for (ItemStack it : cm.getChargedProjectiles()) loaded.add(it.getType() == Material.TIPPED_ARROW ? plain(it) : it);
            cm.setChargedProjectiles(loaded);
            bow.setItemMeta(cm);
        }
        core.say(p, "tipped-arrows", "tipped arrows are banned");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoad(io.papermc.paper.event.entity.EntityLoadCrossbowEvent e) {
        if (e.getEntity() instanceof Player p && tippedBanned(p.getWorld()) && !core.exempt(p)) cleanPlayer(p); // it loads a plain arrow
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!banned(e.getItem(), e.getBlock().getWorld())) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getBlock().getState() instanceof Container c) clean(c.getInventory(), e.getBlock().getWorld());
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(VillagerAcquireTradeEvent e) {
        if (banned(e.getRecipe().getResult(), e.getEntity().getWorld())) e.setCancelled(true);
    }

    private void removeTrades(Merchant m, org.bukkit.World w) {
        List<MerchantRecipe> keep = new ArrayList<>();
        boolean changed = false;
        for (MerchantRecipe r : m.getRecipes()) {
            if (banned(r.getResult(), w)) changed = true; else keep.add(r);
        }
        if (changed) m.setRecipes(keep);
    }

    // ------------------------------------------------------------------ 5. debuff potions

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        if (!core.on("debuff-potions", e.getBlock().getWorld())) return;
        List<ItemStack> out = e.getResults();
        for (int i = 0; i < out.size(); i++) if (debuffPotion(out.get(i))) out.set(i, plain(out.get(i))); // the brew comes out as water
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (core.exempt(p) || !core.on("debuff-potions", p.getWorld()) || !debuffPotion(e.getItem())) return;
        e.setCancelled(true);
        e.setItem(plain(e.getItem()));
        cleanPlayer(p);
        core.say(p, "debuff-potions", "debuff potions are banned");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onThrow(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        if (it == null || (it.getType() != Material.SPLASH_POTION && it.getType() != Material.LINGERING_POTION)) return;
        if (core.exempt(p) || !core.on("debuff-potions", p.getWorld()) || !debuffPotion(it)) return;
        e.setCancelled(true);
        cleanPlayer(p);
        core.say(p, "debuff-potions", "debuff potions are banned");
    }
}
