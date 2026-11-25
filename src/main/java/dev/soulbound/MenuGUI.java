package dev.soulbound;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Admin Menu GUI: a 54-slot chest (no block placed) with gem buttons plus
 * Upgrade / Pristine / +1 Energy / Repair / Trader actions for the held gem.
 */
public class MenuGUI implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private static class MenuHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    // Row 1 = Tier 1, row 2 = Tier 2: four gems, a centre label, four gems (9 slots a row).
    private static final int[] T1_SLOTS = {9, 10, 11, 12, 14, 15, 16, 17};
    private static final GemType[] ORDER = {
            GemType.STRENGTH, GemType.FIRE, GemType.SPEED, GemType.WEALTH,
            GemType.FLUX, GemType.ASTRA, GemType.LIFE, GemType.PUFF};
    private static final int[] T2_SLOTS = {18, 19, 20, 21, 23, 24, 25, 26};
    private static final int SLOT_UPGRADE = 47, SLOT_PRISTINE = 48, SLOT_ENERGY = 49, SLOT_REPAIR = 50, SLOT_TRADER = 51;

    public MenuGUI(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
    }

    public void openMenu(Player p) {
        Inventory menu = Bukkit.createInventory(new MenuHolder(), 54, "Menu");
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.setDisplayName(" ");
        filler.setItemMeta(fm);
        for (int i = 0; i < 54; i++) menu.setItem(i, filler);
        ItemStack frame = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta frm = frame.getItemMeta();
        frm.setDisplayName(" ");
        frame.setItemMeta(frm);
        for (int i = 0; i < 9; i++) { menu.setItem(i, frame); menu.setItem(45 + i, frame); } // dark top and bottom rows
        menu.setItem(4, label(Material.AMETHYST_CLUSTER, ChatColor.LIGHT_PURPLE + "Soulbound Gems", "Click a gem to take it."));
        GemNames names = GemNames.current();
        menu.setItem(13, label(Material.IRON_NUGGET, ChatColor.WHITE + names.tier(1), "The row of " + (names.tiersChanged() ? names.tier(1) : "Tier 1") + " gems."));
        menu.setItem(22, label(Material.GOLD_NUGGET, ChatColor.GOLD + names.tier(2), "The row of " + (names.tiersChanged() ? names.tier(2) : "Tier 2") + " gems."));
        menu.setItem(31, label(Material.ECHO_SHARD, ChatColor.DARK_AQUA + "Mythics", GemNames.tx("Heretic, Auratus and Relay.")));
        for (int i = 0; i < ORDER.length; i++) {
            menu.setItem(T1_SLOTS[i], gems.createGem(ORDER[i], 1, 5));
            menu.setItem(T2_SLOTS[i], gems.createGem(ORDER[i], 2, 5));
        }
        menu.setItem(38, gems.createMythic(GemType.HERETIC));
        menu.setItem(40, gems.createMythic(GemType.AURATUS));
        menu.setItem(42, gems.createMythic(GemType.RELAY));
        menu.setItem(SLOT_UPGRADE, action(Material.NETHER_STAR, "Upgrade Gem", "Tier 1 held gem -> Tier 2."));
        menu.setItem(SLOT_PRISTINE, action(Material.GHAST_TEAR, "Set Pristine", "Held gem energy -> 5."));
        menu.setItem(SLOT_ENERGY, action(Material.EXPERIENCE_BOTTLE, "+1 Energy", "Held gem energy +1 (max 10)."));
        menu.setItem(SLOT_REPAIR, action(Material.ANVIL, "Repair Gem", "Damaged held gem -> Pristine."));
        menu.setItem(SLOT_TRADER, action(Material.CLOCK, "Trader", "Instantly randomize held gem type."));
        p.openInventory(menu);
    }

    /** A non-clickable heading item. */
    private ItemStack label(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(name);
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GRAY + lore);
        m.setLore(lines);
        item.setItemMeta(m);
        return item;
    }

    private ItemStack action(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + name);
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GRAY + lore);
        m.setLore(lines);
        item.setItemMeta(m);
        return item;
    }

    private ItemStack heldGem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (gems.isGem(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off)) return off;
        return null;
    }

    private void give(Player p, ItemStack item) {
        if (p.getInventory().firstEmpty() == -1) p.getWorld().dropItemNaturally(p.getLocation(), item);
        else p.getInventory().addItem(item);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof MenuHolder)) return;
        int slot = e.getSlot();
        // Standard gem buttons.
        for (int i = 0; i < ORDER.length; i++) {
            if (slot == T1_SLOTS[i]) { give(p, gems.createGem(ORDER[i], 1, 5)); return; }
            if (slot == T2_SLOTS[i]) { give(p, gems.createGem(ORDER[i], 2, 5)); return; }
        }
        if (slot == 38) { give(p, gems.createMythic(GemType.HERETIC)); return; }
        if (slot == 40) { give(p, gems.createMythic(GemType.AURATUS)); return; }
        if (slot == 42) { give(p, gems.createMythic(GemType.RELAY)); return; }
        ItemStack gem = heldGem(p);
        if (slot == SLOT_UPGRADE) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem) || gems.getTier(gem) >= 2) { p.sendMessage(ChatColor.GRAY + "Already max tier."); return; }
            gems.setTier(gem, 2);
            p.sendMessage(ChatColor.GREEN + "Upgraded to Tier 2.");
            return;
        }
        if (slot == SLOT_PRISTINE) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            gems.setEnergy(gem, 5);
            p.sendMessage(ChatColor.GREEN + "Set to Pristine (5).");
            return;
        }
        if (slot == SLOT_ENERGY) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            gems.setEnergy(gem, Math.min(10, gems.getEnergy(gem) + 1));
            p.sendMessage(ChatColor.GREEN + "Energy +1.");
            return;
        }
        if (slot == SLOT_REPAIR) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            int en = gems.getEnergy(gem);
            if (en <= 0) { p.sendMessage(ChatColor.RED + "Broken gems need a Restoration, not a repair."); return; }
            if (en >= 5) { p.sendMessage(ChatColor.GRAY + "Already Pristine or higher."); return; }
            gems.setEnergy(gem, 5);
            p.sendMessage(ChatColor.GREEN + "Repaired to Pristine.");
            return;
        }
        if (slot == SLOT_TRADER) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics cannot be traded."); return; }
            GemType cur = gems.getType(gem);
            List<GemType> pool = new ArrayList<>(List.of(ORDER));
            pool.remove(cur);
            GemType next = pool.get((int) (Math.random() * pool.size()));
            ItemStack replacement = gems.createGem(next, gems.getTier(gem), gems.getEnergy(gem));
            if (p.getInventory().getItemInMainHand().equals(gem)) {
                p.getInventory().setItemInMainHand(replacement);
            } else {
                p.getInventory().setItemInOffHand(replacement);
            }
            p.sendMessage(ChatColor.AQUA + "Traded: " + cur.shown() + " -> " + next.shown() + ".");
        }
    }
}
