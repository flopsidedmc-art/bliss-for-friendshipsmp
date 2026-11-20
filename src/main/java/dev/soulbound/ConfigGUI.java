package dev.soulbound;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /config hub: gem icons as gateways to per-gem tunables. Standards edit
 * their cooldowns live; Mythics delegate to the mythic editor.
 */
public class ConfigGUI implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private static class HubHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static class SubHolder implements InventoryHolder {
        final GemType gem;
        SubHolder(GemType gem) { this.gem = gem; }
        @Override public Inventory getInventory() { return null; }
    }

    private static final GemType[] ROW_A = {GemType.STRENGTH, GemType.FIRE, GemType.SPEED, GemType.WEALTH};
    private static final int[] SLOTS_A = {18, 19, 20, 21}; // one row: four gems, a centre label, four gems
    private static final GemType[] ROW_B = {GemType.FLUX, GemType.ASTRA, GemType.LIFE, GemType.PUFF};
    private static final int[] SLOTS_B = {23, 24, 25, 26};
    private static final GemType[] MYTHICS = {GemType.HERETIC, GemType.AURATUS};
    private static final int[] SLOTS_M = {39, 41}; // centred, one row of space under the gems

    private record StdSetting(String path, Material icon, String label, int step, int big, int min, int max) {}

    public ConfigGUI(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
    }

    public void openHub(Player p) {
        Inventory gui = Bukkit.createInventory(new HubHolder(), 54, "Config");
        fill(gui);
        for (int i = 0; i < ROW_A.length; i++) gui.setItem(SLOTS_A[i], gems.createGem(ROW_A[i], 2, 5));
        for (int i = 0; i < ROW_B.length; i++) gui.setItem(SLOTS_B[i], gems.createGem(ROW_B[i], 2, 5));
        for (int i = 0; i < MYTHICS.length; i++) gui.setItem(SLOTS_M[i], gems.createMythic(MYTHICS[i]));
        gui.setItem(4, label(Material.COMPARATOR, ChatColor.GOLD + "Config", "Click a gem to tune it."));
        gui.setItem(22, label(Material.GOLD_NUGGET, ChatColor.YELLOW + "Gems", "Cooldowns and powers."));
        gui.setItem(40, label(Material.ECHO_SHARD, ChatColor.DARK_AQUA + "Mythics", "Opens the live tuner."));
        p.openInventory(gui);
    }

    private void fill(Inventory gui) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = glass.getItemMeta();
        m.setDisplayName(" ");
        glass.setItemMeta(m);
        for (int i = 0; i < 54; i++) gui.setItem(i, glass);
        ItemStack frame = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = frame.getItemMeta();
        fm.setDisplayName(" ");
        frame.setItemMeta(fm);
        for (int i = 0; i < 9; i++) { gui.setItem(i, frame); gui.setItem(45 + i, frame); } // dark top and bottom rows
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

    /** Settings sit every other slot on row 2, centred on the middle slot (22) whatever their count. */
    static int settingSlot(int index, int count) {
        return 22 - (count - 1) + index * 2;
    }

    private List<StdSetting> settingsFor(GemType gem) {
        String c = "cooldowns.";
        return switch (gem) {
            case STRENGTH -> List.of(
                    new StdSetting(c + "chad", Material.REDSTONE, "Chad Strength", 5, 30, 0, 900),
                    new StdSetting(c + "nullify", Material.MILK_BUCKET, "Nullify", 5, 30, 0, 900),
                    new StdSetting(c + "bounty", Material.COMPASS, "Bounty Hunter", 5, 30, 0, 900),
                    new StdSetting("passives.strength-level", Material.IRON_SWORD, "Strength Level", 1, 1, 0, 3));
            case FIRE -> List.of(
                    new StdSetting(c + "fireball", Material.FIRE_CHARGE, "Fireball", 5, 30, 0, 900),
                    new StdSetting(c + "cozy", Material.CAMPFIRE, "Cozy Campfire", 5, 30, 0, 900),
                    new StdSetting(c + "crisp", Material.NETHERRACK, "Crisp", 5, 30, 0, 900));
            case SPEED -> List.of(
                    new StdSetting(c + "blur", Material.VEX_SPAWN_EGG, "Blur", 5, 30, 0, 900),
                    new StdSetting(c + "storm", Material.LIGHTNING_ROD, "Speedy Storm", 5, 30, 0, 900),
                    new StdSetting(c + "thunderstep", Material.ENDER_PEARL, "Thunderstep", 5, 30, 0, 900),
                    new StdSetting("passives.speed-level", Material.FEATHER, "Speed Level", 1, 1, 0, 3));
            case WEALTH -> List.of(
                    new StdSetting(c + "itemlock", Material.CHEST, "Item Lock", 5, 30, 0, 900),
                    new StdSetting(c + "richrush", Material.GOLD_INGOT, "Rich Rush", 5, 30, 0, 900),
                    new StdSetting(c + "amp", Material.ENCHANTING_TABLE, "Amplification", 5, 30, 0, 900),
                    new StdSetting(c + "pockets", Material.BARREL, "Pockets", 5, 30, 0, 900));
            case FLUX -> List.of(
                    new StdSetting(c + "beam", Material.BEACON, "Energy Beam", 5, 30, 0, 900),
                    new StdSetting(c + "overdrive", Material.REPEATER, "Overdrive", 5, 30, 0, 900),
                    new StdSetting(c + "burst", Material.TNT, "Kinetic Burst", 5, 30, 0, 900));
            case ASTRA -> List.of(
                    new StdSetting(c + "daggers", Material.ARROW, "Daggers", 5, 30, 0, 900),
                    new StdSetting(c + "projection", Material.ENDER_EYE, "Projection", 5, 30, 0, 900),
                    new StdSetting(c + "void", Material.DRAGON_BREATH, "Astral Void", 5, 30, 0, 900),
                    new StdSetting(c + "drift", Material.CHORUS_FRUIT, "Dimensional Drift", 5, 30, 0, 900));
            case LIFE -> List.of(
                    new StdSetting(c + "heartdrainer", Material.REDSTONE_BLOCK, "Heart Drainer", 5, 30, 0, 900),
                    new StdSetting(c + "circle", Material.APPLE, "Circle of Life", 5, 30, 0, 900),
                    new StdSetting(c + "vortex", Material.VINE, "Vitality Vortex", 5, 30, 0, 900));
            case PUFF -> List.of(
                    new StdSetting(c + "bash", Material.BREEZE_ROD, "Breezy Bash", 5, 30, 0, 900),
                    new StdSetting(c + "dash", Material.WIND_CHARGE, "Dash", 5, 30, 0, 900),
                    new StdSetting(c + "doublejump", Material.RABBIT_FOOT, "Double Jump", 5, 30, 0, 900));
            default -> List.of();
        };
    }

    public void openSubmenu(Player p, GemType gem) {
        if (gem == GemType.HERETIC) {
            plugin.mythics().openMythicConfig(p, "mythics.heretic.");
            return;
        }
        if (gem == GemType.AURATUS) {
            plugin.mythics().openMythicConfig(p, "mythics.auratus.");
            return;
        }
        Inventory gui = Bukkit.createInventory(new SubHolder(gem), 54, gem.display + " Config");
        fill(gui);
        List<StdSetting> settings = settingsFor(gem);
        for (int i = 0; i < settings.size(); i++) gui.setItem(settingSlot(i, settings.size()), renderStd(settings.get(i)));
        gui.setItem(4, label(Material.COMPARATOR, ChatColor.GOLD + gem.display + " Config", "Left +, right -, shift for big steps."));
        if (settings.isEmpty()) {
            ItemStack info = new ItemStack(Material.GOLD_INGOT);
            ItemMeta m = info.getItemMeta();
            m.setDisplayName(ChatColor.GOLD + "No tunable settings");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "This gem has nothing to tune.");
            m.setLore(lore);
            info.setItemMeta(m);
            gui.setItem(22, info);
        }
        gui.setItem(49, backButton());
        p.openInventory(gui);
    }

    private ItemStack renderStd(StdSetting s) {
        ItemStack item = new ItemStack(s.icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + s.label);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.WHITE + "Current: " + plugin.getConfig().getInt(s.path, 0) + "s");
        lore.add(ChatColor.GRAY + "Left/Right: +/-" + s.step + "s   Shift: +/-" + s.big + "s");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(ChatColor.RED + "Back");
        item.setItemMeta(m);
        return item;
    }

    private GemType gemAtSlot(int slot) {
        for (int i = 0; i < ROW_A.length; i++) {
            if (SLOTS_A[i] == slot) return ROW_A[i];
            if (SLOTS_B[i] == slot) return ROW_B[i];
        }
        for (int i = 0; i < MYTHICS.length; i++) {
            if (SLOTS_M[i] == slot) return MYTHICS[i];
        }
        return null;
    }

    @EventHandler
    public void onConfigClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof HubHolder) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof HubHolder)) return;
            GemType gem = gemAtSlot(e.getSlot());
            if (gem != null) openSubmenu(p, gem);
            return;
        }
        if (!(e.getInventory().getHolder() instanceof SubHolder sub)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof SubHolder)) return;
        if (e.getSlot() == 49) {
            openHub(p);
            return;
        }
        List<StdSetting> settings = settingsFor(sub.gem);
        int first = settingSlot(0, settings.size());
        int index = (e.getSlot() - first) / 2;
        if (e.getSlot() < first || (e.getSlot() - first) % 2 != 0 || index >= settings.size()) return;
        StdSetting s = settings.get(index);
        boolean right = e.getClick().isRightClick();
        boolean shift = e.getClick().isShiftClick();
        int delta = (shift ? s.big : s.step) * (right ? -1 : 1);
        int v = Math.max(s.min, Math.min(s.max, plugin.getConfig().getInt(s.path, 0) + delta));
        plugin.getConfig().set(s.path, v);
        plugin.saveConfig();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        e.getInventory().setItem(e.getSlot(), renderStd(s));
    }
}
