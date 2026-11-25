package dev.soulbound;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Operator controls for the Turmoil build-up and Relay finale. */
public final class TurmoilCommand implements CommandExecutor, TabCompleter {
    private final SoulboundGemsPlugin plugin;
    public TurmoilCommand(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("soulbound.turmoil")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("/turmoil start | stop | status | spawn | setspawn | pedestal <1|2|clear> | overlay | finale <auto|player> [force] | finale stop");
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "start" -> sender.sendMessage(plugin.turmoil().start() ? "Turmoil started." : "Turmoil is already active.");
            case "stop" -> {
                if (plugin.finale().isActive()) plugin.finale().stop();
                sender.sendMessage(plugin.turmoil().stop() ? "Turmoil stopped." : "Turmoil is not active.");
            }
            case "status" -> sender.sendMessage("Turmoil: " + (plugin.turmoil().isActive() ? "active" : "inactive") + " | " + plugin.finale().statusLine());
            case "spawn" -> { plugin.turmoil().broadcastSpawn(); sender.sendMessage("Summons broadcast."); }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) sender.sendMessage("Run this in-game at Spawn.");
                else { plugin.finale().setCenter(player.getLocation()); sender.sendMessage("Finale center saved."); }
            }
            case "pedestal" -> {
                String what = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "show";
                Boolean auratus = switch (what) {
                    case "1", "auratus" -> Boolean.TRUE;
                    case "2", "heretic" -> Boolean.FALSE;
                    default -> null;
                };
                if (what.equals("clear")) {
                    plugin.finale().clearPedestals();
                    sender.sendMessage("Pedestals reset: they stand either side of the finale center again. " + plugin.finale().pedestalInfo());
                } else if (auratus == null) {
                    sender.sendMessage(plugin.finale().pedestalInfo() + GemNames.tx(". Set one with /turmoil pedestal <1|2> where it should stand (1 = Auratus, 2 = Heretic)."));
                } else if (!(sender instanceof Player player)) {
                    sender.sendMessage("Run this in-game, standing where the pedestal should go.");
                } else {
                    org.bukkit.Location at = plugin.finale().setPedestal(auratus, player.getLocation());
                    sender.sendMessage("Pedestal " + (auratus ? GemNames.tx("1 (Auratus)") : GemNames.tx("2 (Heretic)")) + " set at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                            + ", on the ground. It's used from the next finale on.");
                }
            }
            case "overlay" -> {
                if (!(sender instanceof Player player)) sender.sendMessage("Run this in-game.");
                else sender.sendMessage("Overlay " + (plugin.turmoil().toggleOverlay(player) ? "enabled" : "disabled") + ".");
            }
            case "finale" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
                    if (plugin.finale().isActive()) { plugin.finale().stop(); sender.sendMessage("Finale aborted."); }
                    else sender.sendMessage("No finale is running.");
                } else if (args.length < 2) {
                    sender.sendMessage("Usage: /turmoil finale <auto|player> [force]");
                } else {
                    String result = plugin.finale().start(args[1], args.length >= 3 && args[2].equalsIgnoreCase("force"));
                    sender.sendMessage(result == null ? "Finale started." : result);
                }
            }
            default -> sender.sendMessage("Unknown subcommand. Use /turmoil for help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("soulbound.turmoil")) return Collections.emptyList();
        if (args.length == 1) return filter(Arrays.asList("start", "stop", "status", "spawn", "setspawn", "pedestal", "overlay", "finale"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("pedestal")) return filter(Arrays.asList("1", "2", "clear"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("finale")) {
            List<String> options = new ArrayList<>(Arrays.asList("auto", "stop"));
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) options.add(p.getName());
            return filter(options, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("finale")) return filter(Collections.singletonList("force"), args[2]);
        return Collections.emptyList();
    }
    private List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(s -> s.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT))).toList();
    }
}
