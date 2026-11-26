package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Starter gems (starter-gem: in config.yml): a player joining for the first time gets a random Tier 1 standard gem
 * (never a mythic). Who has had one is kept in starter-gems.yml, so it happens once per player: losing a gem later never
 * hands out another. Players who already carry a gem (from before this) are just recorded.
 */
final class StarterGem implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final Set<UUID> given = new HashSet<>();
    private final Random rnd = new Random();

    StarterGem(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "starter-gems.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("given")) {
            try { given.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : given) ids.add(id.toString());
        y.set("given", ids);
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save starter-gems.yml: " + e.getMessage()); }
    }

    /** The standard gems a starter can be (never a mythic). */
    static List<GemType> choices() {
        List<GemType> out = new ArrayList<>();
        for (GemType t : GemType.values()) if (!t.isMythic()) out.add(t);
        return out;
    }

    /** Whether a starter gem is due: never had one, and carries no gem now. */
    static boolean due(boolean hadOne, boolean carriesGem) { return !hadOne && !carriesGem; }

    private boolean carriesGem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (plugin.gems().isGem(it)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { // once their inventory is loaded and other join work is done
            if (!p.isOnline() || !plugin.getConfig().getBoolean("starter-gem.enabled", true) || given.contains(p.getUniqueId())) return;
            boolean carries = carriesGem(p);
            given.add(p.getUniqueId());
            if (StarterGem.due(false, carries)) {
                List<GemType> types = choices();
                GemType type = types.get(rnd.nextInt(types.size()));
                ItemStack gem = plugin.gems().createGem(type, 1, plugin.getConfig().getInt("energy.start", 5));
                for (ItemStack left : p.getInventory().addItem(gem).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                p.sendMessage(ChatColor.YELLOW + "You were given a " + type.coloredName() + ChatColor.YELLOW + ".");
            }
            save();
        }, 10L);
    }
}
