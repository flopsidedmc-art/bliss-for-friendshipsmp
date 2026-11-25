package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /rules (soulbound.rules.admin): which combat rules are on and their numbers, config.yml read again, one rule
 * turned on or off, and the maces (give one, list them).
 */
final class RulesCommand implements CommandExecutor, TabCompleter {
    static final String ADMIN = "soulbound.rules.admin";
    private final SoulboundGemsPlugin plugin;
    private final RuleCore core;
    private final MaceRules maces;

    RulesCommand(SoulboundGemsPlugin plugin, RuleCore core, MaceRules maces) {
        this.plugin = plugin;
        this.core = core;
        this.maces = maces;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(ADMIN)) { s.sendMessage(ChatColor.RED + "You lack permission."); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(s);
            case "reload" -> {
                plugin.reloadConfig();
                s.sendMessage(ChatColor.GREEN + "config.yml read again; the rules use it now.");
            }
            case "toggle" -> {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /rules toggle <rule>  (" + String.join(", ", RuleMath.RULES) + ")"); return true; }
                String rule = a[1].toLowerCase(Locale.ROOT);
                if (!RuleMath.RULES.contains(rule)) { s.sendMessage(ChatColor.RED + "No rule called " + a[1] + ". Rules: " + String.join(", ", RuleMath.RULES)); return true; }
                boolean now = !core.on(rule);
                plugin.getConfig().set("rules." + rule + ".enabled", now);
                plugin.saveConfig();
                s.sendMessage(ChatColor.GOLD + rule + ChatColor.GRAY + " is now " + (now ? ChatColor.GREEN + "on" : ChatColor.RED + "off") + ChatColor.GRAY + ".");
            }
            case "mace" -> mace(s, a);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "/rules status " + ChatColor.GRAY + "| /rules reload | /rules toggle <rule>");
        s.sendMessage(ChatColor.GOLD + "/rules mace give <player> " + ChatColor.GRAY + "| /rules mace list");
    }

    /** Every rule, on or off, with its numbers. */
    private void status(CommandSender s) {
        var c = plugin.getConfig();
        s.sendMessage(ChatColor.GOLD + "Combat rules" + ChatColor.GRAY + " (exempt-revenant " + c.getBoolean("rules.exempt-revenant", false) + ")");
        for (String rule : RuleMath.RULES) {
            StringBuilder b = new StringBuilder();
            ConfigurationSection sec = c.getConfigurationSection("rules." + rule);
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    if (k.equals("enabled") || sec.isList(k) || sec.isConfigurationSection(k)) continue;
                    if (b.length() > 0) b.append(", ");
                    b.append(k).append(' ').append(sec.get(k));
                }
            }
            if (rule.equals("combat")) b.append(b.length() > 0 ? ", " : "").append(core.fights.size()).append(" in a fight now");
            if (rule.equals("mace")) b.append(b.length() > 0 ? ", " : "").append(maces.count()).append('/').append(maces.max()).append(" exist");
            if (rule.equals("debuff-potions")) b.append(b.length() > 0 ? ", " : "").append(c.getStringList("rules.debuff-potions.effects").size()).append(" effects banned");
            s.sendMessage((core.on(rule) ? ChatColor.GREEN + "on  " : ChatColor.RED + "off ") + ChatColor.WHITE + rule
                    + (b.length() > 0 ? ChatColor.GRAY + "  " + b : ""));
        }
        ConfigurationSection worlds = c.getConfigurationSection("rules.worlds");
        List<String> off = new ArrayList<>();
        if (worlds != null) for (String w : worlds.getKeys(false)) if (!worlds.getBoolean(w, true)) off.add(w);
        s.sendMessage(ChatColor.GRAY + "Worlds with every rule off: " + (off.isEmpty() ? "none" : String.join(", ", off)));
    }

    private void mace(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("give")) {
            if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /rules mace give <player>"); return; }
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { s.sendMessage(ChatColor.RED + a[2] + " isn't online."); return; }
            if (!maces.give(t)) { s.sendMessage(ChatColor.RED + "All " + maces.max() + " maces exist already (/rules mace list)."); return; }
            s.sendMessage(ChatColor.GREEN + "Gave " + t.getName() + " a mace (" + maces.count() + "/" + maces.max() + ").");
        } else if (sub.equals("list")) {
            List<String> all = maces.list();
            s.sendMessage(ChatColor.GOLD + "Maces: " + maces.count() + "/" + maces.max() + ChatColor.GRAY + " (mode " + maces.mode().name().toLowerCase(Locale.ROOT) + ")");
            if (all.isEmpty()) s.sendMessage(ChatColor.GRAY + "None yet.");
            for (String line : all) s.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + line);
        } else s.sendMessage(ChatColor.RED + "Usage: /rules mace <give <player>|list>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission(ADMIN)) return out;
        if (a.length == 1) out.addAll(List.of("status", "reload", "toggle", "mace"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("toggle")) out.addAll(RuleMath.RULES);
        else if (a.length == 2 && a[0].equalsIgnoreCase("mace")) out.addAll(List.of("give", "list"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("mace") && a[1].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        String typed = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
