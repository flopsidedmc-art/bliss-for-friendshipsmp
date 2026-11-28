package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EnergyGamesPlugin extends JavaPlugin {
    private GameState state;
    private SkriptBridge skript;
    private Items items;
    private TrackerManager tracker;
    private TimerManager timers;
    private BukkitTask ticker;
    private long tick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        skript = new SkriptBridge(getLogger());
        state = new GameState(this);
        state.load();
        items = new Items(this);
        tracker = new TrackerManager(this);
        timers = new TimerManager(this);

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(tracker, this);

        EnergyGamesCommand main = new EnergyGamesCommand(this);
        bind("energygames", main);
        bind("glowing", timers);
        bind("glowing-end", timers);
        bind("double-energy", timers);
        bind("endtimer", timers);

        // Runs every 2 ticks: compass targets; every 10 ticks: glowing + action bars.
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 2L);

        // After the server has finished starting (Skript has loaded its variables), push our state to Skript.
        Bukkit.getScheduler().runTask(this, this::syncAllToSkript);
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        if (tracker != null) tracker.shutdown();
        if (state != null) state.save();
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) return;
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter tc) cmd.setTabCompleter(tc);
    }

    private void tick() {
        tick++;
        tracker.updateCompasses();
        if (tick % 5 != 0) return;

        timers.update();
        Map<UUID, List<String>> bars = new HashMap<>();
        timers.collectActionBars(bars);
        tracker.collectActionBars(bars);
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> parts = bars.get(p.getUniqueId());
            if (parts == null || parts.isEmpty() || busyHand(p)) continue;
            Text.actionBar(p, String.join(ChatColor.RESET + "  ", parts));
        }
    }

    // ---------------------------------------------------------------- accessors

    GameState state() {
        return state;
    }

    SkriptBridge skript() {
        return skript;
    }

    Items items() {
        return items;
    }

    String msg(String key) {
        return Text.color(getConfig().getString("messages." + key, key));
    }

    long seconds(String path, long def) {
        return getConfig().getLong("durations." + path, def) * 1000L;
    }

    // ---------------------------------------------------------------- busy hand

    /** True when the player holds a gem / mythical item in either hand (action bars are skipped). */
    boolean busyHand(Player p) {
        return isBusyItem(p.getInventory().getItemInMainHand()) || isBusyItem(p.getInventory().getItemInOffHand());
    }

    private boolean isBusyItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (getConfig().getBoolean("busy-hand.use-skript-functions", true)) {
            if (Boolean.TRUE.equals(skript.callFunction("IsGem", item))) return true;
            if (Boolean.TRUE.equals(skript.callFunction("isMythical", item))) return true;
        }
        for (String m : getConfig().getStringList("busy-hand.materials")) {
            if (item.getType() == Material.matchMaterial(m)) return true;
        }
        List<String> names = getConfig().getStringList("busy-hand.name-contains");
        if (!names.isEmpty() && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                String plain = ChatColor.stripColor(meta.getDisplayName()).toLowerCase();
                for (String n : names) {
                    if (plain.contains(ChatColor.stripColor(Text.color(n)).toLowerCase())) return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- teams

    private Team team(String path, ChatColor defColor) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = getConfig().getString("teams." + path + ".name", "energygames_" + path);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            ChatColor color;
            try {
                color = ChatColor.valueOf(getConfig().getString("teams." + path + ".color", defColor.name()).toUpperCase());
            } catch (IllegalArgumentException e) {
                color = defColor;
            }
            team.setColor(color);
        }
        return team;
    }

    Team participantTeam() {
        return team("participant", ChatColor.AQUA);
    }

    Team eliminatedTeam() {
        return team("eliminated", ChatColor.RED);
    }

    void setTeam(Player p, Boolean participant) {
        Team blue = participantTeam();
        Team red = eliminatedTeam();
        blue.removeEntry(p.getName());
        red.removeEntry(p.getName());
        if (participant == null) return;
        (participant ? blue : red).addEntry(p.getName());
    }

    // ---------------------------------------------------------------- game state changes

    void setParticipant(UUID uuid, Boolean value) {
        if (value == null) state.participants.remove(uuid);
        else state.participants.put(uuid, value);
        syncVar("participant::" + uuid, value);
    }

    void startGames() {
        state.energyGames = true;
        syncVar("EnergyGames", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            setParticipant(p.getUniqueId(), true);
            setTeam(p, true);
        }
        state.save();
        Bukkit.broadcastMessage(msg("started"));
    }

    void endGames() {
        state.energyGames = false;
        syncVar("EnergyGames", null);
        for (UUID uuid : new ArrayList<>(state.participants.keySet())) setParticipant(uuid, null);
        for (Team t : new Team[]{participantTeam(), eliminatedTeam()}) {
            for (String entry : new ArrayList<>(t.getEntries())) t.removeEntry(entry);
        }
        state.save();
        Bukkit.broadcastMessage(msg("ended"));
    }

    boolean dragonGamesRunning() {
        String var = getConfig().getString("skript.dragon-games-variable", "DragonGames");
        if (var == null || var.isEmpty()) return false;
        return Boolean.TRUE.equals(skript.getVariable(var));
    }

    void syncVar(String name, Object value) {
        if (getConfig().getBoolean("skript.sync-variables", true)) skript.setVariable(name, value);
    }

    private void syncAllToSkript() {
        if (!skript.isAvailable()) return;
        syncVar("EnergyGames", state.energyGames ? Boolean.TRUE : null);
        for (Map.Entry<UUID, Boolean> e : state.participants.entrySet()) syncVar("participant::" + e.getKey(), e.getValue());
        syncVar("timer.running", state.doubleEnergyActive() ? Boolean.TRUE : null);
        syncVar("timer.running1", state.glowingActive() ? Boolean.TRUE : null);
    }
}
