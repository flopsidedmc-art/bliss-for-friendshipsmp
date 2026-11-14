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

    private static final int[] T1_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17};
    private static final GemType[] ORDER = {
            GemType.STRENGTH, GemType.FIRE, GemType.SPEED, GemType.WEALTH,
            GemType.FLUX, GemType.ASTRA, GemType.LIFE, GemType.PUFF};
    private static final int[] T2_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26};

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
        for (int i = 0; i < ORDER.length; i++) {
            menu.setItem(T1_SLOTS[i], gems.createGem(ORDER[i], 1, 5));
            menu.setItem(T2_SLOTS[i], gems.createGem(ORDER[i], 2, 5));
        }
        menu.setItem(29, gems.createMythic(GemType.HERETIC));
        menu.setItem(31, gems.createMythic(GemType.AURATUS));
        menu.setItem(33, gems.createMythic(GemType.RELAY));
        menu.setItem(46, action(Material.NETHER_STAR, "Upgrade Gem", "Tier 1 held gem -> Tier 2."));
        menu.setItem(47, action(Material.GHAST_TEAR, "Set Pristine", "Held gem energy -> 5."));
        menu.setItem(48, action(Material.EXPERIENCE_BOTTLE, "+1 Energy", "Held gem energy +1 (max 10)."));
        menu.setItem(49, action(Material.ANVIL, "Repair Gem", "Damaged held gem -> Pristine."));
        menu.setItem(50, action(Material.CLOCK, "Trader", "Instantly randomize held gem type."));
        p.openInventory(menu);
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
        if (slot == 29) { give(p, gems.createMythic(GemType.HERETIC)); return; }
        if (slot == 31) { give(p, gems.createMythic(GemType.AURATUS)); return; }
        if (slot == 33) { give(p, gems.createMythic(GemType.RELAY)); return; }
        ItemStack gem = heldGem(p);
        if (slot == 46) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem) || gems.getTier(gem) >= 2) { p.sendMessage(ChatColor.GRAY + "Already max tier."); return; }
            gems.setTier(gem, 2);
            p.sendMessage(ChatColor.GREEN + "Upgraded to Tier 2.");
            return;
        }
        if (slot == 47) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            gems.setEnergy(gem, 5);
            p.sendMessage(ChatColor.GREEN + "Set to Pristine (5).");
            return;
        }
        if (slot == 48) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            gems.setEnergy(gem, Math.min(10, gems.getEnergy(gem) + 1));
            p.sendMessage(ChatColor.GREEN + "Energy +1.");
            return;
        }
        if (slot == 49) {
            if (gem == null) { p.sendMessage(ChatColor.RED + "Hold a gem first."); return; }
            if (gems.isMythic(gem)) { p.sendMessage(ChatColor.GRAY + "Mythics ignore energy."); return; }
            int en = gems.getEnergy(gem);
            if (en <= 0) { p.sendMessage(ChatColor.RED + "Broken gems need a Restoration, not a repair."); return; }
            if (en >= 5) { p.sendMessage(ChatColor.GRAY + "Already Pristine or higher."); return; }
            gems.setEnergy(gem, 5);
            p.sendMessage(ChatColor.GREEN + "Repaired to Pristine.");
            return;
        }
        if (slot == 50) {
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
            p.sendMessage(ChatColor.AQUA + "Traded: " + cur.display + " -> " + next.display + ".");
        }
    }
}
