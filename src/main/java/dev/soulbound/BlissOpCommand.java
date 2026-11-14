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
 *   /blissop mythic <auratus|heretic|gold|relay> [player]
 *   /blissop progression <1-4>        — same as /progression
 *   /blissop assessment <on|off>      — Progression 5 toggle
 *   /blissop energy <set|add> <player> <gem> <units>
 *   /blissop border <blocks> <seconds> — "Growing the world border to X blocks over N seconds"
 *   /blissop stalker <player|stop>     — Shadow Stalker hunt
 *   /blissop pris [player]            — grant the Prismatic Edge sword
 *   /blissop mace [player]            — grant the showcase [Mace]
 *   /blissop test <rising|sky|wire|wisp|mana|textfx>  — effect presets (admin preview)
 *   /blissop reloadconfig
 *
 * Permission: bliss.admin. Register in plugin.yml:
 *
 *   blissop:
 *     description: Bliss admin operations console
 *     usage: /blissop help
 *     permission: bliss.admin
 */
public class BlissOpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "give", "mythic", "progression", "assessment", "energy",
            "border", "stalker", "pris", "mace", "test", "reloadconfig");

    private static final List<String> TESTS = List.of(
            "rising", "sky", "wire", "wisp", "mana", "textfx");

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
                    sender.sendMessage(Component.text("/blissop progression <1-4>", NamedTextColor.RED));
                    return true;
                }
                Bukkit.dispatchCommand(sender, "progression " + args[1]);
            }
            case "assessment" -> {
                String arg = args.length >= 2 ? args[1] : "on";
                Bukkit.dispatchCommand(sender, "bliss assessment " + arg);
            }
            case "energy" -> energy(sender, args);
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
            case "test" -> test(sender, args);
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
                "/blissop give <gem> [tier]",
                "/blissop mythic <auratus|heretic|gold|relay> [player]",
                "/blissop progression <1-4> | assessment <on|off>",
                "/blissop energy set|add <player> <gem> <units>",
                "/blissop border <blocks> <seconds>",
                "/blissop stalker <player|stop>",
                "/blissop pris | mace [player]",
                "/blissop test <rising|sky|wire|wisp|mana|textfx>",
                "/blissop reloadconfig"
        };
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2 || !(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/blissop give <gem> [tier]", NamedTextColor.RED));
            return;
        }
        GemType type = GemType.match(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown gem: " + args[1], NamedTextColor.RED));
            return;
        }
        int tier = args.length >= 3 ? parseInt(args[2], 1) : 1;
        player.getInventory().addItem(plugin.gems().createGem(type, tier));
        player.sendMessage(Component.text("Granted " + type.display + " (tier " + tier + ")", NamedTextColor.GREEN));
    }

    private void mythic(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("/blissop mythic <auratus|heretic|gold|relay> [player]",
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
            target.sendMessage(Component.text("The " + type.display + " gem chooses you.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void energy(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("/blissop energy set|add <player> <gem> <units>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        GemType type = GemType.match(args[3]);
        if (target == null || type == null) {
            sender.sendMessage(Component.text("Unknown player or gem.", NamedTextColor.RED));
            return;
        }
        int units = parseInt(args[4], 0);
        if (args[1].equalsIgnoreCase("set")) {
            plugin.energy.addEnergy(target, type, units - plugin.energy.stageOf(target, type) * 100);
        } else {
            plugin.energy.addEnergy(target, type, units);
        }
        sender.sendMessage(Component.text(target.getName() + " → " + type.display
                + " " + EnergyManager.stageLabel(plugin.energy.stageOf(target, type)), NamedTextColor.GREEN));
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

    private void test(CommandSender sender, String[] args) {
        if (args.length < 2 || !(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/blissop test <" + String.join("|", TESTS) + ">", NamedTextColor.RED));
            return;
        }
        Location loc = player.getLocation();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "rising" -> MythicFx.fountain(loc, 4000);        // red spiral canopy
            case "sky" -> MythicFx.chainSparks(loc.clone(), loc.clone().add(0, 12, 4));
            case "wire" -> {
                for (int i = 0; i < 12; i++) {
                    loc.getWorld().spawnParticle(org.bukkit.Particle.CRIT, loc.clone().add(
                            StrictMath.cos(i) * 1.5, 0.5 + (i % 4) * 0.4, StrictMath.sin(i) * 1.5), 1, 0, 0, 0, 0);
                }
            }
            case "wisp" -> MythicFx.curls(player, true, true, 0.8);
            case "mana" -> MythicFx.tripleSpin(loc);
            case "textfx" -> player.sendActionBar(Component.text("~ text preview ~", NamedTextColor.LIGHT_PURPLE));
            default -> sender.sendMessage(Component.text("Unknown preset.", NamedTextColor.RED));
        }
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
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> java.util.Arrays.stream(GemType.values())
                        .filter(g -> !g.isMythic()).map(g -> g.name().toLowerCase(Locale.ROOT)).toList();
                case "mythic" -> List.of("auratus", "heretic", "gold", "relay");
                case "assessment" -> List.of("on", "off");
                case "energy" -> List.of("set", "add");
                case "stalker" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                case "test" -> TESTS;
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("energy")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("energy")) {
            return java.util.Arrays.stream(GemType.values()).map(g -> g.name().toLowerCase(Locale.ROOT)).toList();
        }
        return List.of();
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
