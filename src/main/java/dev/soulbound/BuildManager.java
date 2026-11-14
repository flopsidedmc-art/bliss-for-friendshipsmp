package dev.soulbound;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Build pocket: teleport to a void workspace, raise a platform of chosen
 * size, then save the build to disk and paste exact copies anywhere.
 */
public class BuildManager {
    private final SoulboundGemsPlugin plugin;

    private static class Session {
        String name;
        int size;
        Location corner;
        Location origin;
        Location returnTo;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    public BuildManager(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        startWorldUpkeep();
    }

    /** Eternal midnight, clear skies, peaceful: the build dimension keeps its own sky. */
    private void startWorldUpkeep() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = buildWorld();
            if (w == null) return;
            if (w.getTime() != 18000L) w.setTime(18000L);
            if (w.hasStorm() || w.isThundering()) {
                w.setStorm(false);
                w.setThundering(false);
            }
            if (w.getDifficulty() != org.bukkit.Difficulty.PEACEFUL) {
                w.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
            }
        }, 100L, 100L);
    }

    private World buildWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getKey().toString().equals("soulbound:controlroom")) return w;
        }
        return null;
    }

    private File fileFor(String name) {
        return new File(plugin.getDataFolder(), "builds/" + name.toLowerCase() + ".yml");
    }

    public List<String> buildList() {
        File dir = new File(plugin.getDataFolder(), "builds");
        String[] files = dir.list((d, n) -> n.endsWith(".yml"));
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (String f : files) out.add(f.substring(0, f.length() - 4));
        }
        return out;
    }

    public void create(Player p, String name, int size) {
        World w = buildWorld();
        if (w == null) {
            p.sendMessage(ChatColor.RED + "Build dimension is not loaded (datapack missing?).");
            return;
        }
        if (size < 5 || size > 65 || size % 2 == 0) {
            p.sendMessage(ChatColor.RED + "Size must be an odd number from 5 to 65.");
            return;
        }
        // One pocket per builder, spread along +X.
        int index = Math.abs(p.getUniqueId().hashCode() % 20);
        int cx = 20000 + index * 500;
        int y = 100;
        for (int dx = -size / 2; dx <= size / 2; dx++) {
            for (int dz = -size / 2; dz <= size / 2; dz++) {
                w.getBlockAt(cx + dx, y, dz).setType(Material.WHITE_CONCRETE, false);
            }
        }
        Session s = new Session();
        s.name = name.toLowerCase();
        s.size = size;
        s.corner = new Location(w, cx - size / 2, y + 1, -size / 2);
        s.origin = new Location(w, cx + 0.5, y + 1, 0.5);
        s.returnTo = p.getLocation().clone();
        sessions.put(p.getUniqueId(), s);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "execute in soulbound:controlroom run tp " + p.getName() + " " + (cx + 0.5) + " " + (y + 1) + " 0.5");
        p.sendMessage(ChatColor.GREEN + "Build pocket '" + s.name + "' ready (" + size + "x" + size
                + "). Build, then /build save " + s.name + ". /build exit abandons it.");
    }

    public void save(Player p, String name) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !s.name.equalsIgnoreCase(name)) {
            p.sendMessage(ChatColor.RED + "No open session for '" + name + "'. Create one with /build create.");
            return;
        }
        YamlConfiguration data = new YamlConfiguration();
        data.set("name", s.name);
        data.set("size", s.size);
        List<String> blocks = new ArrayList<>();
        for (int x = 0; x < s.size; x++) {
            for (int y = 0; y < 64; y++) {
                for (int z = 0; z < s.size; z++) {
                    Block b = s.corner.clone().add(x, y, z).getBlock();
                    if (b.getType() == Material.AIR) continue;
                    blocks.add(x + "," + y + "," + z + "," + b.getType().name() + ","
                            + b.getBlockData().getAsString().replace(",", ";"));
                }
            }
        }
        data.set("blocks", blocks);
        try {
            data.save(fileFor(s.name));
        } catch (Exception ex) {
            p.sendMessage(ChatColor.RED + "Could not save build: " + ex.getMessage());
            return;
        }
        p.sendMessage(ChatColor.GREEN + "Saved '" + s.name + "' (" + blocks.size() + " blocks). Paste it with /build paste " + s.name + ".");
    }

    public void paste(Player p, String name) {
        File f = fileFor(name);
        if (!f.exists()) {
            p.sendMessage(ChatColor.RED + "No saved build '" + name + "'.");
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(f);
        int size = data.getInt("size", 0);
        List<String> blocks = data.getStringList("blocks");
        if (size <= 0) {
            p.sendMessage(ChatColor.RED + "Build file is corrupt.");
            return;
        }
        Location origin = p.getLocation().getBlock().getLocation();
        World w = origin.getWorld();
        int ox = origin.getBlockX() - size / 2;
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ() - size / 2;
        // Clear first so pastes are exact.
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < 64; y++) {
                for (int z = 0; z < size; z++) {
                    w.getBlockAt(ox + x, oy + y, oz + z).setType(Material.AIR, false);
                }
            }
        }
        int placed = 0;
        for (String line : blocks) {
            String[] parts = line.split(",", 5);
            if (parts.length < 5) continue;
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Material m = Material.valueOf(parts[3]);
                Block b = w.getBlockAt(ox + x, oy + y, oz + z);
                b.setType(m, false);
                try {
                    b.setBlockData(Bukkit.createBlockData(parts[4].replace(";", ",")), false);
                } catch (Exception ignored) { }
                placed++;
            } catch (Exception ignored) { }
        }
        p.sendMessage(ChatColor.GREEN + "Pasted '" + name + "' (" + placed + " blocks).");
    }

    public void exit(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null && s.returnTo != null) {
            p.teleport(s.returnTo);
            return;
        }
        p.sendMessage(ChatColor.GRAY + "No build session open.");
    }
}
