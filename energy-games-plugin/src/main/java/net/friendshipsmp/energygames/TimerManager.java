package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /glowing, /glowing-end, /double-energy, /endtimer.
 * Timers use real end timestamps saved in data.yml, so they survive restarts.
 */
final class TimerManager implements CommandExecutor {
    private final EnergyGamesPlugin plugin;

    TimerManager(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("energygames.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        GameState s = plugin.state();
        switch (command.getName().toLowerCase()) {
            case "glowing" -> {
                if (s.glowingActive()) {
                    sender.sendMessage(plugin.msg("timer-already-running"));
                    return true;
                }
                s.glowingEndsAt = System.currentTimeMillis() + plugin.seconds("glowing", 18000);
                s.save();
                plugin.syncVar("timer.running1", true);
            }
            case "glowing-end" -> {
                if (!s.glowingActive()) {
                    sender.sendMessage(plugin.msg("no-timer-running"));
                    return true;
                }
                finishGlowing();
                clearActionBars();
                sender.sendMessage(plugin.msg("timer-ended"));
            }
            case "double-energy" -> {
                if (s.doubleEnergyActive()) {
                    sender.sendMessage(plugin.msg("timer-already-running"));
                    return true;
                }
                s.doubleEnergyEndsAt = System.currentTimeMillis() + plugin.seconds("double-energy", 7200);
                s.save();
                plugin.syncVar("timer.running", true);
            }
            case "endtimer" -> {
                if (!s.doubleEnergyActive()) {
                    sender.sendMessage(plugin.msg("no-timer-running"));
                    return true;
                }
                finishDoubleEnergy();
                clearActionBars();
                sender.sendMessage(plugin.msg("timer-ended"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Called every 10 ticks. */
    void update() {
        GameState s = plugin.state();
        if (s.glowingEndsAt > 0) {
            if (s.glowingActive()) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.isGlowing()) p.setGlowing(true);
                }
            } else {
                finishGlowing();
            }
        }
        if (s.doubleEnergyEndsAt > 0 && !s.doubleEnergyActive()) finishDoubleEnergy();
    }

    void collectActionBars(Map<UUID, List<String>> bars) {
        GameState s = plugin.state();
        long now = System.currentTimeMillis();
        List<String> parts = new ArrayList<>();
        if (s.glowingActive()) parts.add(Text.fillTime(plugin.msg("glowing-actionbar"), s.glowingEndsAt - now));
        if (s.doubleEnergyActive()) parts.add(Text.fillTime(plugin.msg("double-energy-actionbar"), s.doubleEnergyEndsAt - now));
        if (parts.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            bars.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(parts);
        }
    }

    private void finishGlowing() {
        plugin.state().glowingEndsAt = 0;
        plugin.state().save();
        plugin.syncVar("timer.running1", null);
        for (Player p : Bukkit.getOnlinePlayers()) p.setGlowing(false);
    }

    private void finishDoubleEnergy() {
        plugin.state().doubleEnergyEndsAt = 0;
        plugin.state().save();
        plugin.syncVar("timer.running", null);
    }

    private void clearActionBars() {
        for (Player p : Bukkit.getOnlinePlayers()) Text.actionBar(p, "");
    }
}
