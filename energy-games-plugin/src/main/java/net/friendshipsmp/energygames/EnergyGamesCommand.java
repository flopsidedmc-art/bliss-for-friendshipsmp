package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /energygames <start|end|tracker|rule|trade|settoken|cleartoken|reload> */
final class EnergyGamesCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN = List.of("Start", "End", "tracker", "rule", "trade", "settoken", "cleartoken", "reload");
    private static final List<String> PLAYER = List.of("Trade");

    private final EnergyGamesPlugin plugin;

    EnergyGamesCommand(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("incorrect-argument"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("trade")) {
            trade(sender);
            return true;
        }
        if (!sender.hasPermission("energygames.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        switch (sub) {
            case "start" -> {
                if (plugin.dragonGamesRunning()) {
                    sender.sendMessage(plugin.msg("dragon-games"));
                } else {
                    plugin.startGames();
                }
            }
            case "end" -> plugin.endGames();
            case "tracker" -> give(sender, plugin.items().tracker());
            case "rule" -> give(sender, plugin.items().ruleRequest());
            case "settoken" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) {
                    p.sendMessage(plugin.msg("token-need-item"));
                    return true;
                }
                ItemStack token = hand.clone();
                token.setAmount(1);
                plugin.state().savedToken = token;
                plugin.state().save();
                p.sendMessage(plugin.msg("token-saved"));
            }
            case "cleartoken" -> {
                plugin.state().savedToken = null;
                plugin.state().save();
                sender.sendMessage(plugin.msg("token-cleared"));
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(plugin.msg("reloaded"));
            }
            default -> sender.sendMessage(plugin.msg("incorrect-argument"));
        }
        return true;
    }

    private void give(CommandSender sender, ItemStack item) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("players-only"));
            return;
        }
        p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
    }

    // ---------------------------------------------------------------- Energy Merchant

    private void trade(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("players-only"));
            return;
        }
        if (!p.hasPermission("energygames.trade")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (!plugin.state().energyGames) {
            p.sendMessage(plugin.msg("not-running"));
            return;
        }

        Location loc = p.getLocation();
        Villager v = p.getWorld().spawn(loc, Villager.class);
        v.setPersistent(false); // never saved to disk, so it can't be left behind by a restart
        v.setCustomName(plugin.msg("villager-name"));
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.TOOLSMITH);
        v.setVillagerLevel(5);
        v.setVillagerExperience(250); // keeps the profession locked

        Vector dir = loc.getDirection();
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        if (flat.lengthSquared() > 0) v.setVelocity(flat.normalize().multiply(0.4));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!v.isValid()) return;
            v.setRecipes(recipes());
            if (p.isOnline()) p.openMerchant(v, true);
        }, 5L);

        long life = Math.max(1, plugin.getConfig().getLong("durations.trade-villager", 8)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (v.isValid()) v.remove();
        }, 5L + life);
    }

    private List<MerchantRecipe> recipes() {
        int maxUses = plugin.getConfig().getInt("trades.max-uses", 999999);
        List<MerchantRecipe> list = new ArrayList<>();

        MerchantRecipe tracker = new MerchantRecipe(plugin.items().tracker(), maxUses);
        tracker.addIngredient(token(plugin.getConfig().getInt("trades.tracker-cost", 3)));
        list.add(tracker);

        MerchantRecipe rule = new MerchantRecipe(plugin.items().ruleRequest(), maxUses);
        rule.addIngredient(token(plugin.getConfig().getInt("trades.rule-request-cost", 2)));
        list.add(rule);
        return list;
    }

    private ItemStack token(int amount) {
        ItemStack t = plugin.items().energyToken();
        t.setAmount(Math.max(1, Math.min(amount, t.getMaxStackSize())));
        return t;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> source = sender.hasPermission("energygames.admin") ? ADMIN : PLAYER;
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
