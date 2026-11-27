package dev.soulbound;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /halo: hide the Lost Halo's fragments, start the hunt, see where they are, end it. */
final class HaloCommand implements CommandExecutor, TabCompleter {
    private final SoulboundGemsPlugin plugin;

    HaloCommand(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private static final String USAGE = "/halo give <1-5> | reliquary <1-5> | reliquary remove <1-5> | start [force] | status | stop";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(HaloHunt.ADMIN)) { sender.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
        HaloHunt halo = plugin.halo();
        if (halo == null) { sender.sendMessage(ChatColor.RED + "The Lost Halo is turned off (events.halo.enabled)."); return true; }
        if (args.length == 0) { sender.sendMessage(USAGE); return true; }
        String err;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Run this in-game."); return true; }
                Integer n = number(args, 1);
                if (n == null) { sender.sendMessage("Usage: /halo give <1-5>"); return true; }
                err = halo.give(p, n);
                sender.sendMessage(err != null ? ChatColor.RED + err : ChatColor.GOLD + "Halo Fragment " + n + " given. Hide it in a chest (any older fragment " + n + " is void now).");
            }
            case "reliquary" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
                    Integer n = number(args, 2);
                    if (n == null) { sender.sendMessage("Usage: /halo reliquary remove <1-5>"); return true; }
                    err = halo.removeReliquaryCommand(n);
                    sender.sendMessage(err != null ? ChatColor.RED + err : ChatColor.GOLD + "Reliquary " + n + " removed, and its fragment with it.");
                    return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("Run this in-game, looking at a block."); return true; }
                Integer n = number(args, 1);
                if (n == null) { sender.sendMessage("Usage: /halo reliquary <1-5>"); return true; }
                err = halo.placeReliquary(p, n);
                sender.sendMessage(err != null ? ChatColor.RED + err : ChatColor.GOLD + "Reliquary " + n + " placed on that block, holding Halo Fragment " + n + ".");
            }
            case "start" -> {
                err = halo.startHunt(args.length >= 2 && args[1].equalsIgnoreCase("force"));
                sender.sendMessage(err != null ? ChatColor.RED + err : ChatColor.GOLD + "The hunt has begun.");
            }
            case "status" -> { for (String line : halo.status()) sender.sendMessage(line); }
            case "stop" -> {
                halo.stopHunt();
                sender.sendMessage(ChatColor.GOLD + "The hunt is over: every fragment and reliquary is gone (unloaded chests are cleaned as they load).");
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private static Integer number(String[] args, int i) {
        if (args.length <= i) return null;
        try {
            int n = Integer.parseInt(args[i]);
            return n >= 1 && n <= 5 ? n : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(HaloHunt.ADMIN)) return out;
        List<String> pool;
        if (args.length == 1) pool = List.of("give", "reliquary", "start", "status", "stop");
        else if (args.length == 2 && args[0].equalsIgnoreCase("reliquary")) pool = List.of("1", "2", "3", "4", "5", "remove");
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) pool = List.of("1", "2", "3", "4", "5");
        else if (args.length == 3 && args[0].equalsIgnoreCase("reliquary") && args[1].equalsIgnoreCase("remove")) pool = List.of("1", "2", "3", "4", "5");
        else if (args.length == 2 && args[0].equalsIgnoreCase("start")) pool = List.of("force");
        else pool = List.of();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String s : pool) if (s.startsWith(typed)) out.add(s);
        return out;
    }
}
