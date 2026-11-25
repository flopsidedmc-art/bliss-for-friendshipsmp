package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Energy / "Pristine" fuel stage system.
 *
 * Reproduces the showcase behaviour:
 *   - "You gained energy, your FLUX GEM was moved to Pristine +1"
 *   - "You lost energy, your FLUX GEM was moved to Pristine"
 * Stage 0 = "Pristine", stage 1..MAX shown as "Pristine +N".
 *
 * Energy scales the cooldown of abilities (Energy-stage multiplier) and
 * feeds the gem tooltip subtitle ("FUEL A EMPIRE (Pristine +3)").
 *
 * Hook points for SoulboundGemsPlugin#onEnable():
 *     Bukkit.getPluginManager().registerEvents(energy, plugin);
 *     // in GemListener#cooldownMs(String) multiply the result by
 *     //     energy.cooldownFactor(playerType)
 *     // in GemManager#refreshLore(ItemStack) append
 *     //     EnergyManager.stageSuffixLine(gemType, energy.stageOf(player, gemType))
 */
public class EnergyManager implements Listener {

    public static final int MAX_STAGE = 5;

    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    /** energy units per player per gem; stage = energy / 100 */
    private final Map<UUID, EnumMap<GemType, Integer>> energy = new HashMap<>();

    private final int unitsPerStage = 100;
    private final int gainPerKill;
    private final int gainPerMinuteHeld;
    private final double stageLossOnDeath; // fraction of current energy lost on death

    public EnergyManager(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "energy.yml");
        this.data = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("energy");
        this.gainPerKill = cs == null ? 25 : cs.getInt("gain-per-kill", 25);
        this.gainPerMinuteHeld = cs == null ? 10 : cs.getInt("gain-per-minute-held", 10);
        this.stageLossOnDeath = cs == null ? 1.0 : cs.getDouble("loss-fraction-on-death", 1.0);

        // restore
        ConfigurationSection root = data.getConfigurationSection("players");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    EnumMap<GemType, Integer> map = new EnumMap<>(GemType.class);
                    for (GemType type : GemType.values()) {
                        if (type.isMythic()) continue; // mythics ignore the energy system
                        map.put(type, root.getInt(key + "." + type.name(), 0));
                    }
                    energy.put(id, map);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    // ----- core API ---------------------------------------------------------

    /** Current stage 0..MAX_STAGE for the gem type (0 for standard gems only). */
    public int stageOf(Player player, GemType type) {
        if (type.isMythic()) return 0;
        EnumMap<GemType, Integer> map = energy.get(player.getUniqueId());
        if (map == null) return 0;
        Integer units = map.get(type);
        if (units == null) return 0;
        return Math.min(MAX_STAGE, units / unitsPerStage);
    }

    /** Adds energy units (may be negative) and returns the stage after the change. */
    public int addEnergy(Player player, GemType type, int units) {
        if (type.isMythic()) return 0;
        EnumMap<GemType, Integer> map =
                energy.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(GemType.class));
        int before = stageOf(player, type);
        int updated = Math.max(0, map.getOrDefault(type, 0) + units);
        map.put(type, updated);
        int after = stageOf(player, type);
        if (after != before) {
            announce(player, type, after, units >= 0 ? NamedTextColor.YELLOW : NamedTextColor.RED);
            if (units >= 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
        }
        return after;
    }

    public void save() {
        for (Map.Entry<UUID, EnumMap<GemType, Integer>> e : energy.entrySet()) {
            for (GemType type : GemType.values()) {
                Integer units = e.getValue().get(type);
                if (units != null && units > 0) {
                    data.set("players." + e.getKey() + "." + type.name(), units);
                }
            }
        }
        try {
            data.save(file);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save energy.yml: " + ex.getMessage());
        }
    }

    // ----- hook helpers -----------------------------------------------------

    /**
     * Multiplier for ability cooldowns: higher stage => shorter cooldowns.
     * Tune with  energy.stage-cooldown-factor: 0.9  (stage 1..5 => up to ~0.6x).
     */
    public double cooldownFactor(Player player, GemType type) {
        double factor = plugin.getConfig().getDouble("energy.stage-cooldown-factor", 0.9);
        return Math.pow(factor, stageOf(player, type));
    }

    /** "Pristine +3" style suffix, or just "Pristine" at stage 0. */
    public static String stageLabel(int stage) {
        return stage <= 0 ? "Pristine" : "Pristine +" + stage;
    }

    /**
     * One lore line for the gem tooltip:  "FUEL A EMPIRE (Pristine +2)"
     * (the subtitle wording is decorative; replace with the per-gem motto if wanted)
     */
    public static String stageSuffixLine(GemType type, int stage) {
        return net.kyori.adventure.text.format.TextColor.color(0xFFD966) + type.shown().toUpperCase()
                + " GEM (" + stageLabel(stage) + ")";
    }

    public static void announceStage(Player player, GemType type, int stage) {
        Component gem = Component.text(type.shown().toUpperCase(), NamedTextColor.AQUA)
                .append(Component.text(" GEM", NamedTextColor.GOLD));
        boolean gained = stage > 0;
        Component msg = Component.text(gained ? "You gained energy, your " : "You lost energy, your ",
                        gained ? NamedTextColor.WHITE : NamedTextColor.RED)
                .append(gem)
                .append(Component.text(" is now at ", NamedTextColor.WHITE))
                .append(Component.text(stageLabel(Math.max(stage, 0)),
                        gained ? NamedTextColor.BLUE : NamedTextColor.RED));
        player.sendMessage(msg);
    }

    private void announce(Player player, GemType type, int stage, NamedTextColor color) {
        announceStage(player, type, stage);
    }

    // ----- listeners --------------------------------------------------------

    @EventHandler
    public void onKill(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player killer = event.getEntity().getKiller();
        ItemStack gem = plugin.listener().findGem(killer);
        if (gem == null) return;
        GemType type = plugin.gems().getType(gem);
        if (type != null && !type.isMythic()) {
            addEnergy(killer, type, gainPerKill);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        EnumMap<GemType, Integer> map = energy.get(player.getUniqueId());
        if (map == null) return;
        for (GemType type : map.keySet().toArray(new GemType[0])) {
            Integer units = map.get(type);
            if (units == null || units <= 0) continue;
            int lost = (int) Math.round(units * stageLossOnDeath);
            map.put(type, Math.max(0, units - lost));
            if (map.get(type) < units) {
                announce(player, type, stageOf(player, type), NamedTextColor.RED);
            }
        }
    }

    /** Call from the plugin's repeating tick task every 20*60 ticks. */
    public void passiveTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            EnumMap<GemType, Integer> map = energy.get(player.getUniqueId());
            if (map == null) continue;
            for (GemType type : map.keySet().toArray(new GemType[0])) {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (plugin.gems().isGem(held) && plugin.gems().getType(held) == type) {
                    addEnergy(player, type, gainPerMinuteHeld);
                }
            }
        }
    }
}
