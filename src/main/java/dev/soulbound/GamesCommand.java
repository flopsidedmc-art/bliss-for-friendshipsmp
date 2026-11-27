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

/** /hgames: build the arena, set the kits, run the Heretic's Games; join and leave for everyone. */
final class GamesCommand implements CommandExecutor, TabCompleter {
    private final SoulboundGemsPlugin plugin;

    GamesCommand(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private static final String PLAYER_USAGE = "/hgames join | leave | status";
    private static final String ADMIN_USAGE = "/hgames pos1 | pos2 | arena set <name> | arena use <name> | arena list | spawn add | spawn clear"
            + " | lobby set | kits | open | start [force] | stop | join | leave | status";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        HereticGames g = plugin.games();
        if (g == null) { sender.sendMessage(ChatColor.RED + "The Heretic's Games are turned off (events.heretic-games.enabled)."); return true; }
        boolean admin = sender.hasPermission(HereticGames.ADMIN);
        if (args.length == 0) { sender.sendMessage(admin ? ADMIN_USAGE : PLAYER_USAGE); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "join", "leave" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                if (!p.hasPermission(HereticGames.PLAY)) { p.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
                say(p, sub.equals("join") ? g.join(p) : g.leave(p), sub.equals("join") ? "You're in. Everything but your gem is safe until you're out." : "You left; everything you had is back.");
                return true;
            }
            case "status" -> { for (String line : g.status()) sender.sendMessage(line); return true; }
            default -> { }
        }
        if (!admin) { sender.sendMessage(ChatColor.RED + "You do not have permission."); return true; }
        switch (sub) {
            case "pos1", "pos2" -> {
                if (p == null) { sender.sendMessage("Run this in-game."); return true; }
                g.pos(p, sub.equals("pos1") ? 0 : 1);
                p.sendMessage(ChatColor.GRAY + sub + " set at " + p.getLocation().getBlockX() + " " + p.getLocation().getBlockY() + " " + p.getLocation().getBlockZ() + ".");
            }
            case "arena" -> {
                String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
                if (what.equals("list")) { sender.sendMessage(ChatColor.GRAY + "Arenas: " + String.join(", ", g.arenaNames())); return true; }
                if (args.length < 3) { sender.sendMessage("Usage: /hgames arena <set|use> <name>"); return true; }
                if (what.equals("set")) {
                    if (p == null) { sender.sendMessage("Run this in-game."); return true; }
                    say(p, g.setArena(p, args[2]), "Arena " + args[2] + " saved from pos1 to pos2 (the bottom is where rising lava starts). Now /hgames spawn add, at least 8.");
                } else if (what.equals("use")) say(sender, g.useArena(args[2]), "The next tournament uses " + args[2] + ".");
                else sender.sendMessage("Usage: /hgames arena <set|use> <name>");
            }
            case "spawn" -> {
                String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "add";
                if (what.equals("clear")) { say(sender, g.clearSpawns(), "Spawns cleared."); return true; }
                if (p == null) { sender.sendMessage("Run this in-game, standing on the spawn."); return true; }
                say(p, g.addSpawn(p), "Spawn added.");
            }
            case "lobby" -> {
                if (p == null) { sender.sendMessage("Run this in-game, standing in the lobby."); return true; }
                say(p, g.setLobby(p), "Lobby set here. Players wait here and come back here when it's over.");
            }
            case "kits" -> {
                if (p == null) { sender.sendMessage("Run this in-game."); return true; }
                g.kits.openList(p);
            }
            case "open" -> say(sender, g.open(), "Signups are open: /hgames join.");
            case "start" -> say(sender, g.startGames(args.length >= 2 && args[1].equalsIgnoreCase("force")), "The Heretic's Games have begun.");
            case "stop" -> {
                if (g.phase() == GamesMath.Phase.IDLE) { sender.sendMessage(ChatColor.GRAY + "No tournament is open or running."); return true; }
                g.cancel("an admin stopped them");
                sender.sendMessage(ChatColor.GOLD + "Stopped: everyone's things are back, no Heretic and no points.");
            }
            default -> sender.sendMessage(ADMIN_USAGE);
        }
        return true;
    }

    private static void say(CommandSender to, String err, String ok) { to.sendMessage(err == null ? ChatColor.GOLD + ok : ChatColor.RED + err); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(HereticGames.ADMIN);
        List<String> pool;
        if (args.length == 1) pool = admin
                ? List.of("join", "leave", "status", "pos1", "pos2", "arena", "spawn", "lobby", "kits", "open", "start", "stop")
                : List.of("join", "leave", "status");
        else if (!admin) pool = List.of();
        else if (args.length == 2) pool = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "arena" -> List.of("set", "use", "list");
            case "spawn" -> List.of("add", "clear");
            case "lobby" -> List.of("set");
            case "start" -> List.of("force");
            default -> List.of();
        };
        else if (args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("use") && plugin.games() != null) pool = plugin.games().arenaNames();
        else pool = List.of();
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String s : pool) if (s.startsWith(typed)) out.add(s);
        return out;
    }
}
