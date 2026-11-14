package dev.soulbound;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Server progression (levels 1-4): world border, Nether/End locks, enchant and potion
 * caps, the enchanted-obsidian portal event, and the config-driven recipe gates for the
 * Upgrader, Trader and Repair Kit.
 */
public class ProgressionManager implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    private final File dataFile;
    private final YamlConfiguration data;

    private int tickCount;
    private int obsidianCount;

    public ProgressionManager(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
        // Older builds stored the obsidian counter in assessment-data.yml; keep reading it once.
        File current = new File(plugin.getDataFolder(), "progression-data.yml");
        File legacy = new File(plugin.getDataFolder(), "assessment-data.yml");
        this.dataFile = current;
        this.data = YamlConfiguration.loadConfiguration(current.exists() || !legacy.exists() ? current : legacy);
        loadData();
    }

    // ---------- progressions 1-4 ----------

    public int progressionLevel() {
        return Math.max(1, Math.min(4, plugin.getConfig().getInt("progression.level", 4)));
    }

    /** Server-wide challenge jingle + pastel title. */
    private void fanfare(String titleMain, TextColor mainColor, String subtitle, float pitch) {
        Component title = Component.text(titleMain, mainColor);
        Component sub = Component.text(subtitle, NamedTextColor.GRAY);
        Title.Times times = Title.Times.times(
                net.kyori.adventure.util.Ticks.duration(10L),
                net.kyori.adventure.util.Ticks.duration(60L),
                net.kyori.adventure.util.Ticks.duration(20L));
        Title show = Title.title(title, sub, times);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(show);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, pitch);
        }
    }

    public void setProgression(int n) {
        n = Math.max(1, Math.min(4, n));
        plugin.getConfig().set("progression.level", n);
        plugin.saveConfig();
        applyBorder(borderFor(n));
        applyProgressionRecipes();
        Bukkit.broadcastMessage(ChatColor.GOLD + "Progression " + n + " begins.");
        fanfare("PROGRESSION " + n, TextColor.color(0xBAE1FF), "A new era begins", 1.0f);
    }

    private double borderFor(int prog) {
        // Borders: P1-3 = 6000, P4 = 30000
        return prog <= 3 ? 6000 : 30000;
    }

    private void applyBorder(double size) {
        for (World w : Bukkit.getWorlds()) {
            w.getWorldBorder().setCenter(0, 0);
            w.getWorldBorder().setSize(size);
        }
    }

    /** Called on enable: border for the stored progression, recipes for it. */
    public void applyProgressionState() {
        applyProgressionRecipes();
    }

    private final Set<String> recipesOn = new HashSet<>();

    public void applyProgressionRecipes() { applyRecipes(); }

    /** Recompute registered recipes from config gates (min-progression). */
    public void applyRecipes() {
        Set<String> want = new HashSet<>();
        var root = plugin.getConfig().getConfigurationSection("recipes");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                var sec = root.getConfigurationSection(name);
                if (sec == null || !sec.getBoolean("enabled", true)) continue;
                if (progressionLevel() < sec.getInt("min-progression", 3)) continue;
                want.add(name);
            }
        }
        try {
            for (String name : new HashSet<>(recipesOn)) {
                if (!want.contains(name)) {
                    Bukkit.removeRecipe(new NamespacedKey("soulbound-gems", name));
                    recipesOn.remove(name);
                }
            }
            for (String name : want) {
                if (recipesOn.contains(name)) continue;
                org.bukkit.inventory.Recipe recipe = buildRecipe(name);
                if (recipe == null) continue;
                Bukkit.addRecipe(recipe);
                recipesOn.add(name);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Recipes failed: " + ex.getMessage());
        }
    }

    private ItemStack recipeResult(String name, org.bukkit.configuration.ConfigurationSection sec) {
        if (sec != null && sec.isSet("result-kind")) {
            ItemStack it = switch (sec.getString("result-kind", "")) {
                case "upgrader" -> gems.createUpgrader();
                case "trader" -> gems.createTrader();
                case "repairkit" -> gems.createRepairKit();
                default -> null;
            };
            if (it != null) return it;
        }
        if (sec != null && sec.isSet("result-item")) {
            try {
                return new ItemStack(Material.valueOf(sec.getString("result-item", "STONE")),
                        Math.max(1, sec.getInt("result-count", 1)));
            } catch (Exception ignored) { return null; }
        }
        return switch (name) {
            case "upgrader" -> gems.createUpgrader();
            case "trader" -> gems.createTrader();
            case "repairkit" -> gems.createRepairKit();
            default -> null;
        };
    }

    private org.bukkit.inventory.Recipe buildRecipe(String name) {
        var sec = plugin.getConfig().getConfigurationSection("recipes." + name);
        ItemStack result = recipeResult(name, sec);
        if (result == null) return null;
        NamespacedKey key = new NamespacedKey("soulbound-gems", name);
        List<String> shape = new ArrayList<>();
        Map<String, String> ingNames = new HashMap<>();
        String type = "shaped";
        if (sec != null) {
            type = sec.getString("type", "shaped");
            if (sec.isSet("shape")) shape = sec.getStringList("shape");
            var im = sec.getConfigurationSection("ingredients");
            if (im != null) for (String k : im.getKeys(false)) ingNames.put(k, im.getString(k, "STONE"));
        }
        if (shape.isEmpty() || ingNames.isEmpty()) {
            // Hardcoded defaults when config omits a recipe.
            switch (name) {
                case "upgrader" -> {
                    shape = List.of("DGD", "GSG", "DGD");
                    ingNames.put("D", "DIAMOND_BLOCK"); ingNames.put("G", "AMETHYST_SHARD"); ingNames.put("S", "NETHER_STAR");
                }
                case "trader" -> {
                    shape = List.of("D D", " S ", "D D");
                    ingNames.put("D", "DIAMOND_BLOCK"); ingNames.put("S", "SCULK_CATALYST");
                }
                case "repairkit" -> {
                    shape = List.of("IAI", "ANA", "IAI");
                    ingNames.put("I", "IRON_BLOCK"); ingNames.put("A", "AMETHYST_SHARD"); ingNames.put("N", "NETHERITE_INGOT");
                }
                default -> { return null; }
            }
        }
        Map<Character, Material> ings = new HashMap<>();
        for (var e : ingNames.entrySet()) {
            if (e.getKey().length() != 1) continue;
            try { ings.put(e.getKey().charAt(0), Material.valueOf(e.getValue())); }
            catch (Exception ignored) { }
        }
        if (type.equalsIgnoreCase("shapeless")) {
            ShapelessRecipe r = new ShapelessRecipe(key, result);
            for (Material m : new HashSet<>(ings.values())) r.addIngredient(m);
            return r;
        }
        if (shape.size() < 1 || shape.size() > 3) return null;
        ShapedRecipe r = new ShapedRecipe(key, result);
        r.shape(shape.toArray(new String[0]));
        for (var e : ings.entrySet()) r.setIngredient(e.getKey(), e.getValue());
        return r;
    }

    private int progProtCap() {
        return switch (progressionLevel()) {
            case 1 -> 0; // P1: no enchantments at all
            case 2 -> 3;
            case 3 -> 3;
            case 4 -> 4;
            default -> 4;
        };
    }

    private int progSharpCap() {
        return switch (progressionLevel()) {
            case 1 -> 0; // P1: no enchantments at all
            case 2 -> 3;
            case 3 -> 3;
            case 4 -> 5;
            default -> 5;
        };
    }

    /** Kit limits per progression: enchant caps, potion tiers, chorus caps, egg buffs. */
    private void enforceProgression(Player p) {
        int prog = progressionLevel();
        int protCap = progProtCap();
        int sharpCap = progSharpCap();
        List<ItemStack> all = new ArrayList<>(List.of(p.getInventory().getContents()));
        for (ItemStack s : p.getInventory().getArmorContents()) all.add(s);
        all.add(p.getInventory().getItemInOffHand());
        for (ItemStack s : all) {
            if (s == null || s.getType() == Material.AIR || !s.hasItemMeta()) continue;
            ItemMeta m = s.getItemMeta();
            boolean changed = false;
            if (prog <= 1) {
                // P1: no enchantments at all.
                if (!m.getEnchants().isEmpty()) {
                    for (Enchantment e : new HashSet<>(m.getEnchants().keySet())) m.removeEnchant(e);
                    changed = true;
                }
            } else {
                if (m.getEnchantLevel(Enchantment.PROTECTION) > protCap) {
                    m.addEnchant(Enchantment.PROTECTION, protCap, true);
                    changed = true;
                }
                if (m.getEnchantLevel(Enchantment.SHARPNESS) > sharpCap) {
                    m.addEnchant(Enchantment.SHARPNESS, sharpCap, true);
                    changed = true;
                }
            }
            // P1+P2: no potions at all. P1: no loose obsidian (quest items exempt).
            if ((prog <= 2 && (s.getType().name().contains("POTION") || s.getType() == Material.TIPPED_ARROW))
                    || (prog <= 1 && s.getType() == Material.OBSIDIAN && !"eobsidian".equals(gems.getKind(s)))) {
                s.setAmount(0);
                changed = true;
            }
            if (changed) s.setItemMeta(m);
        }
        // P3 chorus cap: 16 carried. P4+: unlimited.
        if (prog == 3) {
            int chorus = 0;
            for (ItemStack s : p.getInventory().getContents()) {
                if (s != null && s.getType() == Material.CHORUS_FRUIT) chorus += s.getAmount();
            }
            if (chorus > 16) {
                int drop = chorus - 16;
                for (ItemStack s : p.getInventory().getContents()) {
                    if (drop <= 0) break;
                    if (s != null && s.getType() == Material.CHORUS_FRUIT) {
                        int take = Math.min(drop, s.getAmount());
                        s.setAmount(s.getAmount() - take);
                        drop -= take;
                    }
                }
                p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(Material.CHORUS_FRUIT, chorus - 16));
                p.sendMessage(ChatColor.GRAY + "Chorus limit is 16 this progression.");
            }
        }
        // Dragon egg holder (P3+): 40 max hearts while held.
        if (prog >= 3) {
            boolean hasEgg = p.getInventory().contains(Material.DRAGON_EGG);
            var attr = p.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                if (hasEgg && attr.getBaseValue() < 40.0) attr.setBaseValue(40.0);
                else if (!hasEgg && attr.getBaseValue() == 40.0) attr.setBaseValue(20.0);
            }
        }
    }

    /** Nether locked in P1, End locked below P3. */
    @EventHandler
    public void onProgTravel(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && progressionLevel() < 2) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "The Nether is locked.");
        }
        if ((cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY)
                && progressionLevel() < 3) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "The End is locked.");
        }
    }


    // ---------- enchanted obsidian event (P1 -> P2) ----------

    private final NamespacedKey keyObsidianMarker = new NamespacedKey("soulbound-gems", "obsidian-marker");

    /** P1: lava + water makes stone, never obsidian. */
    @EventHandler
    public void onObsidianForm(BlockFormEvent e) {
        if (progressionLevel() > 1) return;
        if (e.getNewState().getType() == Material.OBSIDIAN) {
            e.setCancelled(true);
            e.getBlock().setType(Material.STONE, false);
        }
    }

    /** P1: loose obsidian cannot be picked up (quest pieces exempt). */
    @EventHandler
    public void onObsidianPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (progressionLevel() > 1 || !(e.getEntity() instanceof Player)) return;
        ItemStack s = e.getItem().getItemStack();
        if (s.getType() == Material.OBSIDIAN && !"eobsidian".equals(gems.getKind(s))) {
            e.setCancelled(true);
        }
    }

    private boolean nearPortalFrame(Block b) {
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -12; dy <= 12; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    if (b.getRelative(dx, dy, dz).getType() == Material.REINFORCED_DEEPSLATE) return true;
                }
            }
        }
        return false;
    }

    /** Seating enchanted obsidian in the ancient portal frame. Eight opens the Nether. */
    @EventHandler
    public void onObsidianPlace(BlockPlaceEvent e) {
        if (progressionLevel() != 1) return;
        ItemStack hand = e.getItemInHand();
        if (hand == null || !"eobsidian".equals(gems.getKind(hand))) return;
        if (!nearPortalFrame(e.getBlockPlaced())) return;
        Location at = e.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
        at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setGlowing(true);
            stand.getPersistentDataContainer().set(keyObsidianMarker, PersistentDataType.BYTE, (byte) 1);
        });
        obsidianCount++;
        saveData();
        if (obsidianCount >= 8) {
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "The ancient portal wakes. The Nether is open.");
            setProgression(2);
        } else {
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "Enchanted obsidian seated (" + obsidianCount + "/8).");
        }
    }

    /** Breaking a seated piece dims its marker and refunds progress. */
    @EventHandler
    public void onObsidianBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.OBSIDIAN) return;
        boolean unseated = false;
        for (Entity en : e.getBlock().getWorld().getNearbyEntities(e.getBlock().getLocation(), 2, 2, 2)) {
            if (en instanceof ArmorStand stand
                    && stand.getPersistentDataContainer().has(keyObsidianMarker, PersistentDataType.BYTE)) {
                stand.remove();
                unseated = true;
            }
        }
        if (unseated && obsidianCount > 0 && progressionLevel() <= 1) {
            obsidianCount--;
            saveData();
        }
    }


    private void saveData() {
        try {
            data.set("obsidian", obsidianCount);
            data.save(dataFile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save progression data: " + ex.getMessage());
        }
    }

    private void loadData() {
        obsidianCount = data.getInt("obsidian", 0);
    }

    // ---------- tick ----------

    public void tick() {
        tickCount++;
        if (tickCount % 100 == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) enforceProgression(p);
        }
    }
}
