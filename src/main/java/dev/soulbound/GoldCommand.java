package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /goldgem: admins (soulbound.gold.admin) give <player>, remove <player>, ritual set; the Gold Gem holder mark <player>,
 * unmark <player>, marks, switch. Each player sees only what they can use.
 */
final class GoldCommand implements CommandExecutor, TabCompleter {
    static final String ADMIN = "soulbound.gold.admin";
    private final SoulboundGemsPlugin plugin;

    GoldCommand(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private GoldGem gold() { return plugin.gold(); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission(ADMIN), holder = s instanceof Player p && gold().isHolder(p);
        if (a.length == 0) { help(s, admin, holder); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "remove" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You lack permission."); return true; }
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /goldgem " + sub + " <player>"); return true; }
                String err;
                if (sub.equals("give")) {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(ChatColor.RED + a[1] + " isn't online."); return true; }
                    err = gold().give(t);
                } else err = gold().remove(Bukkit.getOfflinePlayer(a[1]));
                s.sendMessage(err == null ? ChatColor.GOLD + (sub.equals("give") ? "Gave " : "Took the Gold Gem from ") + a[1] + "." : ChatColor.RED + err);
            }
            case "ritual" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You lack permission."); return true; }
                if (!(s instanceof Player p) || a.length < 2 || !a[1].equalsIgnoreCase("set")) { s.sendMessage(ChatColor.RED + "Usage: /goldgem ritual set (stand on the centre block)"); return true; }
                gold().setRitual(p.getLocation().subtract(0, 1, 0)); // the block under your feet
                s.sendMessage(ChatColor.GOLD + "The ritual site is the block under you.");
            }
            case "mark", "unmark" -> {
                if (!(s instanceof Player p) || !holder) { s.sendMessage(ChatColor.RED + "Only the Gold Gem holder can do that."); return true; }
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /goldgem " + sub + " <player>"); return true; }
                String err;
                if (sub.equals("mark")) {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(ChatColor.RED + a[1] + " isn't online."); return true; }
                    err = gold().mark(p, t);
                } else err = gold().unmark(p, a[1]);
                s.sendMessage(err == null ? ChatColor.GOLD + (sub.equals("mark") ? "Marked " : "Unmarked ") + a[1] + "." : ChatColor.RED + err);
            }
            case "marks" -> {
                if (!(s instanceof Player p) || !holder) { s.sendMessage(ChatColor.RED + "Only the Gold Gem holder can do that."); return true; }
                List<String> m = gold().marks(p);
                s.sendMessage(ChatColor.GOLD + "Marked (" + m.size() + "): " + ChatColor.WHITE + (m.isEmpty() ? "nobody" : String.join(", ", m)));
            }
            case "switch" -> {
                if (!(s instanceof Player p) || !holder) { s.sendMessage(ChatColor.RED + "Only the Gold Gem holder can do that."); return true; }
                gold().openSwitch(p);
            }
            default -> help(s, admin, holder);
        }
        return true;
    }

    private static void help(CommandSender s, boolean admin, boolean holder) {
        if (holder) {
            s.sendMessage(ChatColor.GOLD + "/goldgem mark <player>" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/goldgem unmark <player>"
                    + ChatColor.GRAY + " | " + ChatColor.GOLD + "/goldgem marks" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/goldgem switch");
        }
        if (admin) {
            s.sendMessage(ChatColor.GOLD + "/goldgem give <player>" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/goldgem remove <player>"
                    + ChatColor.GRAY + " | " + ChatColor.GOLD + "/goldgem ritual set");
        }
        if (!holder && !admin) s.sendMessage(ChatColor.GRAY + "Only the Gold Gem holder and admins use /goldgem.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission(ADMIN), holder = s instanceof Player p && gold().isHolder(p);
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            if (holder) out.addAll(List.of("mark", "unmark", "marks", "switch"));
            if (admin) out.addAll(List.of("give", "remove", "ritual"));
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if ((admin && (sub.equals("give") || sub.equals("remove"))) || (holder && (sub.equals("mark") || sub.equals("unmark"))))
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            if (admin && sub.equals("ritual")) out.add("set");
        }
        String typed = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}
