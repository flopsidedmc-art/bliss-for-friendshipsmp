package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /blissop — the admin console (renamed from /blissdev per dev request).
 *
 * Only admin args, no preview junk exposed to players. Mirrors the showcase's
 * admin command catalogue:
 *
 *   /blissop help                     — this list
 *   /blissop give <gem> [tier]        — standard gem, e.g. give flux 2
 *   /blissop mythic <auratus|heretic|relay> [player]
 *   /blissop progression <1-4>        — same as /progression
 *   /blissop border <blocks> <seconds> — "Growing the world border to X blocks over N seconds"
 *   /blissop stalker <player|stop>     — Shadow Stalker hunt
 *   /blissop pris [player]            — grant the Prismatic Edge sword
 *   /blissop mace [player]            — grant the showcase [Mace]
 *   /blissop reloadconfig
 * (assessment, energy and the test presets were taken out: unfinished or leftover.)
 *
 * Permission: bliss.admin. Register in plugin.yml:
 *
 *   blissop:
 *     description: Bliss admin operations console
 *     usage: /blissop help
 *     permission: bliss.admin
 */
public class BlissOpCommand implements CommandExecutor, TabCompleter {

    /** Only the finished ones (assessment, energy and the particle test presets are gone). */
    private static final List<String> SUBS = List.of(
            "help", "give", "mythic", "progression", "border", "stalker", "pris", "mace", "reloadconfig");

    private final SoulboundGemsPlugin plugin;
    private final PrismaticEdge edge;

    public BlissOpCommand(SoulboundGemsPlugin plugin, PrismaticEdge edge) {
        this.plugin = plugin;
        this.edge = edge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bliss.admin")) {
            sender.sendMessage(Component.text("You lack permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "give" -> give(sender, args);
            case "mythic" -> mythic(sender, args);
            case "progression" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/blissop progression <1|2|3|4>", NamedTextColor.RED));
                    return true;
                }
                Bukkit.dispatchCommand(sender, "progression " + args[1]);
            }
            case "border" -> border(sender, args);
            case "stalker" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/blissop stalker <player|stop>", NamedTextColor.RED));
                    return true;
                }
                if (args[1].equalsIgnoreCase("stop") && sender instanceof Player p) {
                    Bukkit.dispatchCommand(p, "stalker stop");
                } else if (sender instanceof Player p) {
                    Bukkit.dispatchCommand(p, "stalker " + args[1]);
                }
            }
            case "pris" -> {
                Player target = targetOrSelf(sender, args, 1);
                if (target != null) edge.give(target);
            }
            case "mace" -> {
                Player target = targetOrSelf(sender, args, 1);
                if (target != null) {
                    ItemStack mace = new ItemStack(MaterialSafe.MACE);
                    var meta = mace.getItemMeta();
                    meta.displayName(Component.text("Heirloom Mace", NamedTextColor.GOLD));
                    mace.setItemMeta(meta);
                    target.getInventory().addItem(mace);
                    target.sendMessage(Component.text("You were granted a [Mace].", NamedTextColor.GOLD));
                }
            }
            case "reloadconfig" -> {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("config.yml reloaded.", NamedTextColor.GREEN));
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        String[] lines = {
                "blissop — admin console",
                "/blissop give <gem> [1|2]  (to you)",
                "/blissop mythic <auratus|heretic|relay> [player]",
                "/blissop progression <1|2|3|4>",
                "/blissop border <blocks> <seconds>",
                "/blissop stalker <player|stop>",
                "/blissop pris [player]  (the Prismatic Edge)",
                "/blissop mace [player]  (an Heirloom Mace)",
                "/blissop reloadconfig"
        };
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2 || !(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/blissop give <gem> [1|2]", NamedTextColor.RED));
            return;
        }
        GemType type = GemType.match(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown gem: " + args[1], NamedTextColor.RED));
            return;
        }
        int tier = args.length >= 3 ? parseInt(args[2], 1) : 1;
        player.getInventory().addItem(plugin.gems().createGem(type, tier));
        player.sendMessage(Component.text("Granted " + type.shown() + " (tier " + tier + ")", NamedTextColor.GREEN));
    }

    private void mythic(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("/blissop mythic <auratus|heretic|relay> [player]",
                    NamedTextColor.RED));
            return;
        }
        GemType type = GemType.match(args[1]);
        if (type == null || !type.isMythic()) {
            sender.sendMessage(Component.text("Not a mythic gem.", NamedTextColor.RED));
            return;
        }
        Player target = targetOrSelf(sender, args, 2);
        if (target != null) {
            target.getInventory().addItem(plugin.gems().createMythic(type));
            target.sendMessage(Component.text("The " + type.shown() + " gem chooses you.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    /** "Growing the world border to 160000.0 blocks over 15000 seconds" */
    private void border(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("/blissop border <blocks> <seconds>", NamedTextColor.RED));
            return;
        }
        World world = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        double target = Double.parseDouble(args[1]);
        long seconds = Long.parseLong(args[2]);
        world.getWorldBorder().setSize(target, seconds);
        String msg = String.format("Growing the world border to %.1f blocks over %d seconds", target, seconds);
        Bukkit.getServer().sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    private Player targetOrSelf(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player named = Bukkit.getPlayerExact(args[idx]);
            if (named != null) return named;
        }
        return sender instanceof Player p ? p : null;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bliss.admin")) return List.of();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        List<String> out = switch (args.length) {
            case 1 -> SUBS;
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> java.util.Arrays.stream(GemType.values()).filter(g -> !g.isMythic()).map(g -> g.name().toLowerCase(Locale.ROOT)).toList();
                case "mythic" -> List.of("auratus", "heretic", "relay");
                case "progression" -> List.of("1", "2", "3", "4");
                case "border" -> List.of("1000", "5000", "10000", "160000");
                case "stalker" -> java.util.stream.Stream.concat(players.stream(), java.util.stream.Stream.of("stop")).toList();
                case "pris", "mace" -> players;
                default -> List.of();
            };
            case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> List.of("1", "2");
                case "mythic" -> players;
                case "border" -> List.of("60", "600", "3600", "15000");
                default -> List.of();
            };
            default -> List.of();
        };
        return out.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    /** small shim so MaterialSafe compiles on all 1.21.x APIs */
    private static final class MaterialSafe {
        static final org.bukkit.Material MACE = material();
        private static org.bukkit.Material material() {
            try {
                return org.bukkit.Material.valueOf("MACE");
            } catch (IllegalArgumentException e) {
                return org.bukkit.Material.NETHERITE_SWORD;
            }
        }
    }
}
