package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Starter gems (starter-gem: in config.yml). On a player's first join: a standard gem (never a mythic), random or a
 * fixed type, minus the excluded ones, at starter-gem.tier and starter-gem.energy. It goes in the off hand if that's
 * empty, else the inventory, else it's held (starter-gems.yml) until there's room: tried every 30 s and on join, never
 * dropped. Once per player (by UUID, on disk): leaving and rejoining, losing the gem, a Broken gem or a gem socketed in
 * the Gold Gem never hands out another. Admins: /bliss starter give and reset.
 */
final class StarterGem implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final Set<UUID> given = new HashSet<>();
    private final Map<UUID, String> waiting = new HashMap<>(); // gems held until there's room (encoded items)
    private final Random rnd = new Random();
    private BukkitTask retry;

    StarterGem(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "starter-gems.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("given")) {
            try { given.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection w = y.getConfigurationSection("waiting");
        if (w != null) for (String k : w.getKeys(false)) {
            try { waiting.put(UUID.fromString(k), w.getString(k)); } catch (IllegalArgumentException ignored) { }
        }
    }

    void start() {
        retry = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : new ArrayList<>(waiting.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !p.isDead()) deliverWaiting(p);
            }
        }, 600L, 600L);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : given) ids.add(id.toString());
        y.set("given", ids);
        for (Map.Entry<UUID, String> e : waiting.entrySet()) y.set("waiting." + e.getKey(), e.getValue());
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save starter-gems.yml: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ the rules (StarterGemTest)

    /** The standard gems a starter can be (never a mythic). */
    static List<GemType> choices() { return choices(List.of()); }

    /** The standard gems a starter can be, minus {@code exclude} (gem names, any case). */
    static List<GemType> choices(Collection<String> exclude) {
        Set<String> ex = new HashSet<>();
        for (String e : exclude) if (e != null) ex.add(e.trim().toUpperCase(Locale.ROOT));
        List<GemType> out = new ArrayList<>();
        for (GemType t : GemType.values()) if (!t.isMythic() && !ex.contains(t.name())) out.add(t);
        return out;
    }

    /** "random" (or unknown) picks from {@code pool}; a gem name picks that gem (if it's a standard gem). Null: nothing to give. */
    static GemType pick(String type, List<GemType> pool, Random rnd) {
        GemType fixed = type == null || type.equalsIgnoreCase("random") ? null : GemType.match(type);
        if (fixed != null && !fixed.isMythic()) return fixed;
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    /** Whether a starter gem is due: never had one, and carries no gem now. */
    static boolean due(boolean hadOne, boolean carriesGem) { return !hadOne && !carriesGem; }

    enum Spot { OFF_HAND, INVENTORY, HELD }

    /** Where it goes: the off hand if empty, else the inventory if there's room, else held until there is. */
    static Spot spot(boolean offHandEmpty, boolean inventoryHasRoom) {
        return offHandEmpty ? Spot.OFF_HAND : inventoryHasRoom ? Spot.INVENTORY : Spot.HELD;
    }

    // ------------------------------------------------------------------ giving

    private boolean carriesGem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (plugin.gems().isGem(it)) return true; // a Broken one counts
        return false;
    }

    /** No gem now for a legit reason: it's in the Gold Gem. */
    private boolean gemTaken(Player p) { return plugin.gold() != null && plugin.gold().gemTaken(p); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { // once their inventory is loaded and other join work is done
            if (!p.isOnline()) return;
            if (waiting.containsKey(p.getUniqueId())) { deliverWaiting(p); return; }
            if (!plugin.getConfig().getBoolean("starter-gem.enabled", true) || given.contains(p.getUniqueId())) return;
            given.add(p.getUniqueId());
            if (due(false, carriesGem(p) || gemTaken(p))) give(p); // already carrying one (from before): just recorded
            save();
        }, 10L);
    }

    /** One starter gem for {@code p}, by the config's rules. Returns the gem type, or null if there was none to give. */
    GemType give(Player p) {
        var c = plugin.getConfig();
        GemType type = pick(c.getString("starter-gem.type", "random"), choices(c.getStringList("starter-gem.exclude")), rnd);
        if (type == null) return null;
        int tier = Math.max(1, Math.min(2, c.getInt("starter-gem.tier", 1)));
        int energy = EnergyStage.clamp(c.getInt("starter-gem.energy", c.getInt("energy.start", 5)));
        ItemStack gem = plugin.gems().createGem(type, tier, energy);
        deliver(p, gem);
        p.showTitle(Title.title(Component.text("You got a " + type.shown() + " Gem", NamedTextColor.GOLD),
                Component.text("Check #gem-guide to learn it", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        p.sendMessage(ChatColor.YELLOW + "You were given a " + type.coloredName() + ChatColor.YELLOW + ". Check #gem-guide to learn how it works.");
        return type;
    }

    private void deliver(Player p, ItemStack gem) {
        switch (spot(p.getInventory().getItemInOffHand().getType().isAir(), p.getInventory().firstEmpty() >= 0)) {
            case OFF_HAND -> p.getInventory().setItemInOffHand(gem);
            case INVENTORY -> p.getInventory().addItem(gem);
            case HELD -> {
                waiting.put(p.getUniqueId(), GoldGem.encode(gem)); // never on the ground: it comes once there's room
                save();
                p.sendMessage(ChatColor.YELLOW + "Your inventory is full: your starter gem waits until there's room.");
            }
        }
    }

    private void deliverWaiting(Player p) {
        String enc = waiting.get(p.getUniqueId());
        if (enc == null) return;
        if (!p.getInventory().getItemInOffHand().getType().isAir() && p.getInventory().firstEmpty() < 0) return; // still full
        ItemStack gem;
        try { gem = GoldGem.decode(enc); } catch (RuntimeException ex) { waiting.remove(p.getUniqueId()); save(); return; }
        waiting.remove(p.getUniqueId());
        deliver(p, gem);
        save();
        p.sendMessage(ChatColor.YELLOW + "Your starter gem is here.");
    }

    // ------------------------------------------------------------------ admin (/bliss starter)

    /** /bliss starter give: by the same rules; never to someone who carries a gem or whose gem is in the Gold Gem. */
    String adminGive(Player p) {
        if (carriesGem(p)) return p.getName() + " already carries a gem.";
        if (gemTaken(p)) return p.getName() + "'s gem is socketed in the Gold Gem.";
        if (waiting.containsKey(p.getUniqueId())) return p.getName() + " already has a starter gem waiting for room.";
        GemType t = give(p);
        if (t == null) return "No gem type is allowed (check starter-gem.type and exclude).";
        given.add(p.getUniqueId());
        save();
        return null;
    }

    /** /bliss starter reset: forget who has had one (one player, or everyone with null). */
    int reset(UUID id) {
        int n;
        if (id == null) { n = given.size(); given.clear(); }
        else n = given.remove(id) ? 1 : 0;
        save();
        return n;
    }
}
