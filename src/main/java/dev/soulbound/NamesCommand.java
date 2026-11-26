package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /gems (soulbound.admin.names): rename gems and abilities, recolour gems, reset or reload names.yml, and see a
 * player's gems. /gem nickname: a player names their own gem (Nicknames). Every change shows at once: the gems in
 * everyone's inventories are rebuilt with the new names.
 */
final class NamesCommand implements CommandExecutor, TabCompleter {
    static final String ADMIN = "soulbound.admin.names", COLOUR = "soulbound.nickname.color";
    private final SoulboundGemsPlugin plugin;
    private final File file;
    private final NamespacedKey lastNick;

    NamesCommand(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "names.yml");
        this.lastNick = new NamespacedKey(plugin, "nickname_changed_at");
    }

    // ------------------------------------------------------------------ /gems

    /** A gem by its id (FIRE), its name now, or its first name. */
    static GemType gem(String s) {
        if (s == null) return null;
        for (GemType t : GemType.values()) {
            if (t.name().equalsIgnoreCase(s) || t.display.equalsIgnoreCase(s) || GemNames.plain(t.shown()).equalsIgnoreCase(s)) return t;
        }
        return null;
    }

    /** Every online player's gems rebuilt with the names as they are now. */
    void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    void refresh(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (plugin.gems().isGem(it)) plugin.gems().restyle(it);
        p.updateInventory();
    }

    private void changed(CommandSender s, String what) {
        GemNames.current().save(file);
        refreshAll();
        s.sendMessage(ChatColor.GREEN + what);
    }

    private boolean gems(CommandSender s, String[] a) {
        if (!s.hasPermission(ADMIN)) { s.sendMessage(ChatColor.RED + "You lack permission."); return true; }
        GemNames n = GemNames.current();
        if (a.length == 0) {
            s.sendMessage(ChatColor.GOLD + "/gems rename <gem> <name...>  " + ChatColor.GRAY + "| /gems rename <gem>.<ability> <name...>");
            s.sendMessage(ChatColor.GOLD + "/gems color <gem> <#hex or colour>  " + ChatColor.GRAY + "| /gems names reset [gem] | /gems names refresh | /gems info <player>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "rename" -> {
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /gems rename <gem>[.<ability>] <name...>"); return true; }
                String target = a[1], name = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                int dot = target.indexOf('.');
                GemType t = gem(dot < 0 ? target : target.substring(0, dot));
                if (t == null) { s.sendMessage(ChatColor.RED + "No gem called " + target + "."); return true; }
                if (dot < 0) {
                    if (!n.renameGem(t, name)) { s.sendMessage(ChatColor.RED + "That name is empty or longer than " + n.maxLength() + " letters."); return true; }
                    changed(s, t.name() + " is now called " + t.tint() + t.shown() + ChatColor.GREEN + ".");
                } else {
                    String id = ability(t, target.substring(dot + 1));
                    if (id == null) { s.sendMessage(ChatColor.RED + t.shown() + " has no ability " + target.substring(dot + 1) + ". Try: " + String.join(", ", n.abilities(t).keySet())); return true; }
                    if (!n.renameAbility(t, id, name)) { s.sendMessage(ChatColor.RED + "That name is empty or longer than " + n.maxLength() + " letters."); return true; }
                    changed(s, t.name() + "." + id + " is now called " + n.ability(t, id) + ChatColor.GREEN + ".");
                }
            }
            case "color", "colour" -> {
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /gems color <gem> <#hex or chat colour name>"); return true; }
                GemType t = gem(a[1]);
                if (t == null) { s.sendMessage(ChatColor.RED + "No gem called " + a[1] + "."); return true; }
                if (!n.recolor(t, a[2])) { s.sendMessage(ChatColor.RED + a[2] + " isn't a colour (#FF8A2A, gold, light_purple ...)."); return true; }
                changed(s, t.tint() + t.shown() + ChatColor.GREEN + " has its new colour.");
            }
            case "names" -> {
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("reset")) {
                    GemType t = a.length > 2 ? gem(a[2]) : null;
                    if (a.length > 2 && t == null) { s.sendMessage(ChatColor.RED + "No gem called " + a[2] + "."); return true; }
                    n.reset(t);
                    changed(s, (t == null ? "Every name" : t.shown() + "'s names") + " back to the defaults.");
                } else if (sub.equals("refresh")) {
                    GemNames.use(GemNames.load(file));
                    refreshAll();
                    s.sendMessage(ChatColor.GREEN + "names.yml read again; every gem shows the names it has now.");
                } else s.sendMessage(ChatColor.RED + "Usage: /gems names <reset [gem]|refresh>");
            }
            case "info" -> {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /gems info <player>"); return true; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { s.sendMessage(ChatColor.RED + a[1] + " isn't online."); return true; }
                int found = 0;
                for (ItemStack it : p.getInventory().getContents()) {
                    if (!plugin.gems().isGem(it)) continue;
                    GemType t = plugin.gems().getType(it);
                    String nick = plugin.gems().getNickname(it);
                    s.sendMessage(ChatColor.GRAY + "- " + (t == null ? "?" : t.tint() + t.shown()) + ChatColor.GRAY
                            + (t != null && !t.isMythic() ? " " + GemNames.current().tier(plugin.gems().getTier(it)) : "")
                            + (nick == null ? "" : ChatColor.GRAY + ", nicknamed " + ChatColor.WHITE + "\"" + nick + ChatColor.WHITE + "\""));
                    found++;
                }
                if (found == 0) s.sendMessage(ChatColor.GRAY + p.getName() + " has no gems.");
            }
            default -> s.sendMessage(ChatColor.RED + "Usage: /gems <rename|color|names|info> ...");
        }
        return true;
    }

    /** An ability by its id (crisp) or its name now (spaces as _). */
    private static String ability(GemType t, String s) {
        for (var e : GemNames.current().abilities(t).entrySet()) {
            if (e.getKey().equalsIgnoreCase(s) || GemNames.plain(e.getValue()).replace(' ', '_').equalsIgnoreCase(s)) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ /gem nickname

    Nicknames.Rules rules() {
        var c = plugin.getConfig();
        return new Nicknames.Rules(c.getBoolean("nicknames.enabled", true), c.getInt("nicknames.max-length", 16),
                c.getString("nicknames.allowed", "[A-Za-z0-9 .,!?'\"()_-]+"), c.getStringList("nicknames.blocked-words"),
                (long) (c.getDouble("nicknames.cooldown-minutes", 10) * 60_000));
    }

    /** May this gem have a nickname (mythics only with nicknames.mythic-nicknames)? */
    boolean nameable(ItemStack gem) {
        return plugin.gems().isGem(gem) && !plugin.gems().isGold(gem) && (!plugin.gems().isMythic(gem) || plugin.getConfig().getBoolean("nicknames.mythic-nicknames", true));
    }

    long cooldownLeft(Player p) {
        Long last = p.getPersistentDataContainer().get(lastNick, PersistentDataType.LONG);
        return Nicknames.cooldownLeft(last == null ? 0 : last, System.currentTimeMillis(), rules().cooldownMs());
    }

    void startCooldown(Player p) { p.getPersistentDataContainer().set(lastNick, PersistentDataType.LONG, System.currentTimeMillis()); }

    /** The gem a player names: the one in their hand, or else the first one in their inventory. */
    ItemStack ownGem(Player p) {
        ItemStack held = plugin.listener() == null ? null : plugin.listener().findGem(p);
        if (held != null && nameable(held)) return held;
        for (ItemStack it : p.getInventory().getContents()) if (nameable(it)) return it;
        return null;
    }

    private boolean gem(CommandSender s, String[] a) {
        if (a.length == 0 || !a[0].equalsIgnoreCase("nickname")) { s.sendMessage(ChatColor.RED + "Usage: /gem nickname <name...> | /gem nickname clear"); return true; }
        if (a.length >= 3 && a[1].equalsIgnoreCase("clear")) { // an admin clearing someone else's
            if (!s.hasPermission(ADMIN)) { s.sendMessage(ChatColor.RED + "You lack permission."); return true; }
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { s.sendMessage(ChatColor.RED + a[2] + " isn't online."); return true; }
            int n = 0;
            for (ItemStack it : t.getInventory().getContents()) {
                if (plugin.gems().isGem(it) && plugin.gems().getNickname(it) != null) { plugin.gems().setNickname(it, null, null); n++; }
            }
            t.updateInventory();
            s.sendMessage(ChatColor.GREEN + "Cleared " + n + " nickname" + (n == 1 ? "" : "s") + " from " + t.getName() + "'s gems.");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return true; }
        if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /gem nickname <name...> | /gem nickname clear"); return true; }
        ItemStack gem = ownGem(p);
        if (gem == null) { p.sendMessage(ChatColor.RED + "Hold the gem you want to name."); return true; }
        if (a[1].equalsIgnoreCase("clear") && a.length == 2) {
            plugin.gems().setNickname(gem, null, null);
            p.updateInventory();
            p.sendMessage(ChatColor.GREEN + "Your gem's nickname is gone.");
            return true;
        }
        long wait = cooldownLeft(p);
        if (wait > 0 && !p.hasPermission(ADMIN)) { p.sendMessage(ChatColor.RED + "You can rename your gem again in " + HudStatus.time(wait) + "."); return true; }
        Nicknames.Result r = Nicknames.check(String.join(" ", Arrays.copyOfRange(a, 1, a.length)), rules(), p.hasPermission(COLOUR));
        if (!r.ok()) { p.sendMessage(ChatColor.RED + r.error()); return true; }
        plugin.gems().setNickname(gem, r.value(), p.getUniqueId());
        startCooldown(p);
        p.updateInventory();
        p.sendMessage(ChatColor.GREEN + "Your gem is now " + gem.getItemMeta().getDisplayName() + ChatColor.GREEN + ".");
        return true;
    }

    // ------------------------------------------------------------------ plumbing

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        return cmd.getName().equalsIgnoreCase("gems") ? gems(s, a) : gem(s, a);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("gem")) {
            if (a.length == 1) out.add("nickname");
            else if (a.length == 2) out.add("clear");
            else if (a.length == 3 && a[1].equalsIgnoreCase("clear") && s.hasPermission(ADMIN)) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            return filter(out, a[a.length - 1]);
        }
        if (!s.hasPermission(ADMIN)) return out;
        if (a.length == 1) out.addAll(List.of("rename", "color", "names", "info"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "rename" -> {
                    for (GemType t : GemType.values()) {
                        out.add(t.name().toLowerCase(Locale.ROOT));
                        for (String id : GemNames.current().abilities(t).keySet()) out.add(t.name().toLowerCase(Locale.ROOT) + "." + id);
                    }
                }
                case "color", "colour" -> { for (GemType t : GemType.values()) out.add(t.name().toLowerCase(Locale.ROOT)); }
                case "names" -> out.addAll(List.of("reset", "refresh"));
                case "info" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("color")) {
            out.addAll(List.of("#FF8A2A", "gold", "red", "aqua", "light_purple", "green", "yellow", "white", "dark_purple", "blue"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("names") && a[1].equalsIgnoreCase("reset")) {
            for (GemType t : GemType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
        }
        return filter(out, a[a.length - 1]);
    }

    private static List<String> filter(List<String> all, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : all) if (s.toLowerCase(Locale.ROOT).startsWith(t)) out.add(s);
        return out;
    }
}
