package dev.soulbound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /bliss give|info|setenergy|withdraw|giveitem */
public class BlissCommand implements CommandExecutor, TabCompleter {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    public BlissCommand(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
    }

    private static final List<String> STANDARD_GEMS =
            List.of("Strength", "Fire", "Speed", "Wealth", "Flux", "Astra", "Life", "Puff");

    /** Tab-complete choices: AstraT1, AstraT2, ... plus bare Mythic names. */
    private static List<String> gemTierChoices() {
        List<String> out = new ArrayList<>();
        for (String g : STANDARD_GEMS) {
            out.add(g + "T1");
            out.add(g + "T2");
        }
        out.add("Heretic");
        out.add("Auratus");
        out.add("Relay");
        return out;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("givegem")) {
            if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
            if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /givegem <player> <AstraT1|AstraT2|...>"); return true; }
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
            String want = a[1];
            GemType parsedType = null;
            int tier = 1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]+)T([12])$").matcher(want);
            if (m.matches()) {
                parsedType = GemType.match(m.group(1));
                tier = Integer.parseInt(m.group(2));
            } else {
                parsedType = GemType.match(want);
            }
            final GemType type = parsedType;
            if (type == null || (!type.isMythic() && STANDARD_GEMS.stream().noneMatch(g -> g.equalsIgnoreCase(type.display)))) {
                s.sendMessage(ChatColor.RED + "Unknown gem. Tab-complete shows every choice.");
                return true;
            }
            int energy = plugin.getConfig().getInt("energy.start", 5);
            ItemStack gem = gems.createGem(type, tier, energy);
            t.getInventory().addItem(gem);
            s.sendMessage(ChatColor.GREEN + "Gave " + type.shown() + (type.isMythic() ? "" : " T" + tier) + " to " + t.getName());
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("progression")) {
            if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
            if (a.length < 1) {
                s.sendMessage(ChatColor.YELLOW + "Progression: " + plugin.progression().progressionLevel());
                s.sendMessage(ChatColor.YELLOW + "Usage: /progression <1|2|3|4>");
                return true;
            }
            int n = parseInt(a[0], -1);
            if (n < 1 || n > 4) { s.sendMessage(ChatColor.RED + "Usage: /progression <1|2|3|4>"); return true; }
            plugin.progression().setProgression(n);
            return true;
        }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /bliss give <player> <gem> [tier] [energy]  (includes admin-test Mythics)"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                GemType type = GemType.match(a[2]);
                if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
                if (type == null) { s.sendMessage(ChatColor.RED + "Unknown gem. Options: " + Arrays.stream(GemType.values()).map(g -> g.name().toLowerCase()).collect(Collectors.joining(","))); return true; }
                int tier = a.length >= 4 ? parseInt(a[3], 1) : 1;
                int energy = a.length >= 5 ? parseInt(a[4], plugin.getConfig().getInt("energy.start", 5)) : plugin.getConfig().getInt("energy.start", 5);
                ItemStack gem = gems.createGem(type, tier, energy);
                if (plugin.getConfig().getBoolean("protection.single-gem-only", true) && hasGem(t)) {
                    s.sendMessage(ChatColor.RED + t.getName() + " already holds a gem.");
                    return true;
                }
                t.getInventory().addItem(gem);
                if (type.isMythic()) {
                    s.sendMessage(ChatColor.GREEN + "Gave Mythic " + type.shown() + " to " + t.getName());
                    t.sendMessage(ChatColor.GOLD + "You received the Mythic " + type.shown() + " Gem!");
                } else {
                    s.sendMessage(ChatColor.GREEN + "Gave " + type.shown() + " T" + tier + " (" + energy + "/10) to " + t.getName());
                    t.sendMessage(ChatColor.YELLOW + "You received a " + type.coloredName() + ChatColor.YELLOW + "!");
                }
                return true;
            }
            case "info" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                ItemStack gem = plugin.listener().findGem(p);
                if (gem == null) { p.sendMessage(ChatColor.GRAY + "No gem found."); return true; }
                GemType type = gems.getType(gem);
                if (type != null && type.isMythic()) {
                    p.sendMessage(ChatColor.YELLOW + "Gem: " + type.coloredName() + ChatColor.GOLD + " Mythic (no energy system)");
                } else {
                    p.sendMessage(ChatColor.YELLOW + "Gem: " + type.coloredName() + ChatColor.GRAY + " T" + gems.getTier(gem)
                            + ChatColor.YELLOW + " Energy " + gems.getEnergy(gem) + "/10 (" + EnergyStage.name(gems.getEnergy(gem)) + ")");
                }
                if (type == GemType.FLUX) {
                    p.sendMessage(ChatColor.YELLOW + "Charge " + plugin.listener().fluxOf(
                            plugin.listener().fluxCharge, p.getUniqueId()) + "/100 • Watts "
                            + plugin.listener().fluxOf(plugin.listener().fluxWatts, p.getUniqueId())
                            + "/100 • Banked ore "
                            + plugin.listener().fluxOf(plugin.listener().fluxOre, p.getUniqueId()) + "/100");
                }
                if (plugin.listener().isGemDisabled(p)) p.sendMessage(ChatColor.DARK_PURPLE + "Your gem is DISABLED.");
                Player bounty = plugin.bounty() == null ? null : plugin.bounty().target(p);
                if (bounty != null) p.sendMessage(ChatColor.RED + "Hunting: " + plugin.shownName(bounty));
                return true;
            }
            case "setenergy" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /bliss setenergy <player> <0-10>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
                ItemStack gem = plugin.listener().findGem(t);
                if (gem == null) { s.sendMessage(ChatColor.RED + "Target has no gem."); return true; }
                if (gems.isMythic(gem)) { s.sendMessage(ChatColor.RED + "Mythic gems ignore energy."); return true; }
                gems.setEnergy(gem, parseInt(a[2], 5));
                s.sendMessage(ChatColor.GREEN + "Set to " + gems.getEnergy(gem) + "/10");
                return true;
            }
            case "withdraw" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                ItemStack gem = plugin.listener().findGem(p);
                if (gem == null) { p.sendMessage(ChatColor.RED + "No gem."); return true; }
                if (gems.isMythic(gem)) { p.sendMessage(ChatColor.RED + "Mythic gems do not use Energy Bottles."); return true; }
                int en = gems.getEnergy(gem);
                if (en <= 1) { p.sendMessage(ChatColor.RED + "Need at least 2 energy to withdraw (keeps 1+)."); return true; }
                gems.setEnergy(gem, en - 1);
                p.getInventory().addItem(gems.createEnergyBottle());
                p.sendMessage(ChatColor.YELLOW + "Withdrew 1 energy into a bottle → " + gems.getEnergy(gem) + "/10");
                return true;
            }
            case "giveitem" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /bliss giveitem <player> <" + String.join("|", ITEMS) + ">"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
                ItemStack it = switch (a[2].toLowerCase()) {
                    case "upgrader" -> gems.createUpgrader();
                    case "trader" -> gems.createTrader();
                    case "repairkit", "repair_kit", "repair" -> gems.createRepairKit();
                    case "eobsidian", "obsidian", "enchantedobsidian" -> gems.createEnchantedObsidian();
                    case "energybottle", "bottle", "energy" -> gems.createEnergyBottle();
                    default -> null; // only items that do something (the revive, dagger, fragment, token and remote items don't yet)
                };
                if (it == null) {
                    s.sendMessage(ChatColor.RED + "Unknown item. Items: " + String.join(", ", ITEMS));
                    return true;
                }
                t.getInventory().addItem(it);
                s.sendMessage(ChatColor.GREEN + "Gave " + a[2] + " to " + t.getName());
                return true;
            }
            case "menu" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                plugin.menu().openMenu(p);
                return true;
            }
            case "build" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.YELLOW + "Usage: /bliss build <create <name> [size]|save <name>|paste <name>|list|exit>");
                    return true;
                }
                if (a[1].equalsIgnoreCase("list")) {
                    s.sendMessage(ChatColor.GREEN + "Builds: " + String.join(", ", plugin.builds().buildList()));
                    return true;
                }
                if (a[1].equalsIgnoreCase("exit")) {
                    plugin.builds().exit(p);
                    return true;
                }
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /bliss build <create <name> [size]|save <name>|paste <name>>"); return true; }
                if (a[1].equalsIgnoreCase("create")) {
                    int size = 17;
                    if (a.length >= 4) {
                        try { size = Integer.parseInt(a[3]); } catch (Exception ignored) { }
                    }
                    plugin.builds().create(p, a[2], size);
                    return true;
                } else if (a[1].equalsIgnoreCase("save")) {
                    plugin.builds().save(p, a[2]);
                    return true;
                } else if (a[1].equalsIgnoreCase("paste")) {
                    plugin.builds().paste(p, a[2]);
                    return true;
                }
                s.sendMessage(ChatColor.RED + "Usage: /bliss build <create <name> [size]|save <name>|paste <name>|list|exit>");
                return true;
            }
            case "config" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                plugin.configGui().openHub(p);
                return true;
            }
            case "pedestal" -> {
                if (!s.hasPermission("bliss.admin")) { s.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.YELLOW + "Usage: /bliss pedestal <activate|deactivate>");
                    return true;
                }
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (a[1].equalsIgnoreCase("activate")) {
                    plugin.pedestal().activate(p);
                    return true;
                } else if (a[1].equalsIgnoreCase("deactivate")) {
                    plugin.pedestal().deactivate(p);
                    return true;
                }
                s.sendMessage(ChatColor.RED + "Usage: /bliss pedestal <activate|deactivate>");
                return true;
            }
            default -> {
                if (a[0].equalsIgnoreCase("track")) {
                    if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                    if (a.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /bliss track <player>"); return true; }
                    ItemStack gem = plugin.listener().findGem(p);
                    if (gem == null || gems.getType(gem) != GemType.STRENGTH) { p.sendMessage(ChatColor.RED + GemNames.tx("You need a Strength gem.")); return true; }
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
                    if (t.equals(p)) { p.sendMessage(ChatColor.RED + "You can't hunt yourself."); return true; }
                    long wait = plugin.listener().bountyCooldownLeft(p);
                    if (wait > 0) { p.sendMessage(ChatColor.GRAY + GemNames.tx("Bounty Hunter is ready in ") + HudStatus.time(wait) + "."); return true; }
                    if (plugin.bounty() != null) plugin.bounty().openOffer(p, t); // the same offering as the Bounty Board's
                    return true;
                }
                if (a[0].equalsIgnoreCase("trust")) {
                    if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                    if (a.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /bliss trust <add|remove|list> [player]"); return true; }
                    if (a[1].equalsIgnoreCase("list")) {
                        var ids = plugin.listener().getTrusted(p);
                        if (ids.isEmpty()) { p.sendMessage(ChatColor.GRAY + "Trusted: none."); return true; }
                        String names = ids.stream().map(id -> {
                            Player o = Bukkit.getPlayer(id);
                            return (o == null) ? id.toString().substring(0, 8) : o.getName();
                        }).collect(Collectors.joining(", "));
                        p.sendMessage(ChatColor.GREEN + "Trusted: " + names);
                        return true;
                    }
                    if (a.length < 3) { p.sendMessage(ChatColor.RED + "Usage: /bliss trust <add|remove> <player>"); return true; }
                    Player t = Bukkit.getPlayerExact(a[2]);
                    if (t == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
                    if (a[1].equalsIgnoreCase("add")) {
                        plugin.listener().addTrust(p, t);
                        p.sendMessage(ChatColor.GREEN + "Trusted " + t.getName() + GemNames.tx(" (Nullify skips them, Chad buffs them)."));
                        return true;
                    } else if (a[1].equalsIgnoreCase("remove")) {
                        plugin.listener().removeTrust(p, t);
                        p.sendMessage(ChatColor.GRAY + "Untrusted " + t.getName());
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Usage: /bliss trust <add|remove|list> [player]");
                    return true;
                }
                s.sendMessage(ChatColor.RED + "Unknown subcommand.");
                return true;
            }
        }
    }

    private boolean hasGem(Player p) {
        return plugin.listener().findGem(p) != null;
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (cmd.getName().equalsIgnoreCase("givegem")) {
            if (a.length <= 1) return null; // player names
            if (a.length == 2) return filter(gemTierChoices(), a[1]);
            return List.of();
        }
        if (cmd.getName().equalsIgnoreCase("progression")) {
            if (a.length <= 1) return filter(List.of("1", "2", "3", "4"), a.length == 0 ? "" : a[0]);
            return List.of();
        }
        // Everyone sees every subcommand and its arguments; the admin ones still need bliss.admin to run.
        if (a.length == 1) return filter(List.of("info", "withdraw", "track", "trust", "give", "setenergy", "giveitem", "menu", "config", "pedestal", "build"), a[0]);
        String sub = a[0].toLowerCase();
        int n = a.length;
        String typed = a[n - 1];
        return switch (sub) {
            case "give" -> n == 2 ? players(typed) : n == 3 ? filter(Arrays.stream(GemType.values()).map(g -> g.name().toLowerCase()).collect(Collectors.toList()), typed)
                    : n == 4 ? filter(List.of("1", "2"), typed) : n == 5 ? filter(numbers(0, 10), typed) : List.of();
            case "setenergy" -> n == 2 ? players(typed) : n == 3 ? filter(numbers(0, 10), typed) : List.of();
            case "giveitem" -> n == 2 ? players(typed) : n == 3 ? filter(ITEMS, typed) : List.of();
            case "track" -> n == 2 ? players(typed) : List.of();
            case "trust" -> n == 2 ? filter(List.of("add", "remove", "list"), typed)
                    : n == 3 && (a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("remove")) ? players(typed) : List.of();
            case "pedestal" -> n == 2 ? filter(List.of("activate", "deactivate"), typed) : List.of();
            case "build" -> n == 2 ? filter(List.of("create", "save", "paste", "list", "exit"), typed)
                    : n == 3 && (a[1].equalsIgnoreCase("save") || a[1].equalsIgnoreCase("paste")) ? filter(plugin.builds().buildList(), typed)
                    : n == 4 && a[1].equalsIgnoreCase("create") ? filter(List.of("17", "33", "65"), typed) : List.of();
            default -> List.of();
        };
    }

    /** What /bliss giveitem hands out: only items that do something. */
    static final List<String> ITEMS = List.of("upgrader", "trader", "repairkit", "energybottle", "eobsidian");

    /** /bliss with nothing after it: every subcommand with its full arguments, for everyone (admin ones marked). */
    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "/bliss info" + ChatColor.GRAY + " — your gem, tier and energy");
        s.sendMessage(ChatColor.GOLD + "/bliss withdraw" + ChatColor.GRAY + " — 1 energy into an Energy Bottle");
        s.sendMessage(ChatColor.GOLD + GemNames.tx("/bliss track <player>") + ChatColor.GRAY + GemNames.tx(" — Bounty Hunter's offering for them"));
        s.sendMessage(ChatColor.GOLD + "/bliss trust <add <player>|remove <player>|list>");
        String op = s.hasPermission("bliss.admin") ? "" : ChatColor.DARK_GRAY + " (admin)";
        s.sendMessage(ChatColor.GOLD + "/bliss give <player> <gem> [tier] [energy]" + op);
        s.sendMessage(ChatColor.GOLD + "/bliss setenergy <player> <0-10>" + op);
        s.sendMessage(ChatColor.GOLD + "/bliss giveitem <player> <" + String.join("|", ITEMS) + ">" + op);
        s.sendMessage(ChatColor.GOLD + "/bliss menu" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/bliss config" + op);
        s.sendMessage(ChatColor.GOLD + "/bliss pedestal <activate|deactivate>" + op);
        s.sendMessage(ChatColor.GOLD + "/bliss build <create <name> [size]|save <name>|paste <name>|list|exit>" + op);
    }

    private List<String> players(String typed) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return filter(out, typed);
    }

    private static List<String> numbers(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add(String.valueOf(i));
        return out;
    }

    private List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
