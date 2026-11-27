package net.friendshipsmp.energygames;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Persistent state (replaces the Skript global variables), stored in data.yml. */
final class GameState {
    private final EnergyGamesPlugin plugin;
    private final File file;

    boolean energyGames;
    /** true = still in the game, false = eliminated. Missing = never joined this round. */
    final Map<UUID, Boolean> participants = new HashMap<>();
    long glowingEndsAt;
    long doubleEnergyEndsAt;
    ItemStack savedToken;

    GameState(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        energyGames = yml.getBoolean("energy-games", false);
        glowingEndsAt = yml.getLong("glowing-ends-at", 0);
        doubleEnergyEndsAt = yml.getLong("double-energy-ends-at", 0);
        savedToken = yml.getItemStack("energy-token");
        participants.clear();
        ConfigurationSection sec = yml.getConfigurationSection("participants");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    participants.put(UUID.fromString(key), sec.getBoolean(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("energy-games", energyGames);
        yml.set("glowing-ends-at", glowingEndsAt);
        yml.set("double-energy-ends-at", doubleEnergyEndsAt);
        yml.set("energy-token", savedToken);
        for (Map.Entry<UUID, Boolean> e : participants.entrySet()) {
            yml.set("participants." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    boolean glowingActive() {
        return glowingEndsAt > System.currentTimeMillis();
    }

    boolean doubleEnergyActive() {
        return doubleEnergyEndsAt > System.currentTimeMillis();
    }
}
