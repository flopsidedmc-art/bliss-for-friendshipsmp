package dev.soulbound;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /capture: create, colour, start, stop, list, info, remove and visit capture points (CAPTURE.md). */
public final class CaptureCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("create", "color", "start", "stop", "list", "info", "remove", "tp");
    private static final List<String> REWARDS = List.of("heretic", "auratus", "relay", "none");
    private final SoulboundGemsPlugin plugin;

    public CaptureCommand(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private CaptureManager caps() { return plugin.captures(); }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (!s.hasPermission(CaptureManager.PERMISSION)) { s.sendMessage("§cYou lack permission."); return true; }
        if (a.length == 0) { usage(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(s, a);
            case "color", "colour" -> color(s, a);
            case "start" -> start(s, a);
            case "stop" -> {
                if (a.length < 2) { s.sendMessage("§e/capture stop <name>"); return true; }
                CaptureManager.Point pt = caps().get(a[1]);
                if (pt == null) { s.sendMessage("§cNo capture point called '" + a[1] + "'."); return true; }
                s.sendMessage(caps().stop(a[1]) ? "§6Stopped " + pt.name + " with no winner." : "§e" + pt.name + " isn't running.");
            }
            case "list" -> {
                if (caps().all().isEmpty()) { s.sendMessage("§eNo capture points yet. /capture create <name> <x1> <y1> <z1> <x2> <y2> <z2> [color]"); return true; }
                s.sendMessage("§6Capture points:");
                for (CaptureManager.Point pt : caps().all()) {
                    int[] z = CaptureMath.size(pt.box);
                    s.sendMessage(" §f" + pt.name + " §7" + pt.world + " " + z[0] + "x" + z[1] + "x" + z[2] + " " + CaptureMath.hex(pt.color)
                            + (pt.running ? " §arunning" : ""));
                }
            }
            case "info" -> info(s, a);
            case "remove" -> {
                if (a.length < 2) { s.sendMessage("§e/capture remove <name>"); return true; }
                CaptureManager.Point pt = caps().get(a[1]);
                s.sendMessage(pt != null && caps().remove(a[1]) ? "§6Removed " + pt.name + "." : "§cNo capture point called '" + a[1] + "'.");
            }
            case "tp" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cOnly players can teleport."); return true; }
                if (a.length < 2) { s.sendMessage("§e/capture tp <name>"); return true; }
                CaptureManager.Point pt = caps().get(a[1]);
                if (pt == null) { s.sendMessage("§cNo capture point called '" + a[1] + "'."); return true; }
                Location c = caps().centre(pt);
                if (c == null) { s.sendMessage("§cIts world (" + pt.world + ") isn't loaded."); return true; }
                c.setYaw(p.getLocation().getYaw());
                c.setPitch(p.getLocation().getPitch());
                p.teleport(c);
            }
            default -> usage(s);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("§6/capture create <name> <x1> <y1> <z1> <x2> <y2> <z2> [color]");
        s.sendMessage("§6/capture color <name> <color> §7| §6start <name> [hold-seconds] [reward] [force] §7| §6stop <name>");
        s.sendMessage("§6/capture list §7| §6info <name> §7| §6remove <name> §7| §6tp <name>");
    }

    /** Where ~ coordinates are measured from: the player, the command block, or the main world's spawn for the console. */
    private static Location origin(CommandSender s) {
        if (s instanceof Entity e) return e.getLocation();
        if (s instanceof BlockCommandSender b) return b.getBlock().getLocation().add(0.5, 0, 0.5);
        World w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation();
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 8) { s.sendMessage("§e/capture create <name> <x1> <y1> <z1> <x2> <y2> <z2> [color]"); return; }
        Location o = origin(s);
        int[] c = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                double here = switch (i % 3) { case 0 -> o.getX(); case 1 -> o.getY(); default -> o.getZ(); };
                c[i] = CaptureMath.coord(a[2 + i], here);
            }
        } catch (IllegalArgumentException ex) {
            s.sendMessage("§c" + ex.getMessage() + ".");
            return;
        }
        Integer rgb = a.length > 8 ? CaptureMath.color(a[8]) : Integer.valueOf(NamedTextColor.GOLD.value());
        if (rgb == null) { s.sendMessage("§c'" + a[8] + "' isn't a colour: use a hex code like #B0703A or a chat colour like gold."); return; }
        int[] box = CaptureMath.box(c[0], c[1], c[2], c[3], c[4], c[5]);
        String err = caps().create(a[1], o.getWorld(), box, rgb);
        if (err != null) { s.sendMessage("§c" + err); return; }
        int[] z = CaptureMath.size(box);
        s.sendMessage("§6Created " + a[1] + ": " + z[0] + "x" + z[1] + "x" + z[2] + " from " + box[0] + " " + box[1] + " " + box[2]
                + " to " + box[3] + " " + box[4] + " " + box[5] + " in " + o.getWorld().getName() + ". Start it with /capture start " + a[1] + ".");
    }

    private void color(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("§e/capture color <name> <#hex or chat colour>"); return; }
        Integer rgb = CaptureMath.color(a[2]);
        if (rgb == null) { s.sendMessage("§c'" + a[2] + "' isn't a colour: use a hex code like #B0703A or a chat colour like gold."); return; }
        CaptureManager.Point pt = caps().get(a[1]);
        if (pt == null || !caps().setColor(a[1], rgb)) { s.sendMessage("§cNo capture point called '" + a[1] + "'."); return; }
        s.sendMessage("§6" + pt.name + " is now " + CaptureMath.hex(rgb) + ".");
    }

    /** start <name> [hold-seconds] [reward] [force]: the optional parts can be left out from the right. */
    private void start(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§e/capture start <name> [hold-seconds] [heretic|auratus|relay|none] [force]"); return; }
        long hold = caps().defaultHoldSeconds();
        GemType reward = null;
        boolean force = false;
        for (int i = 2; i < a.length; i++) {
            String t = a[i].toLowerCase(Locale.ROOT);
            if (t.matches("\\d{1,6}")) hold = Math.max(1, Long.parseLong(t));
            else if (t.equals("force")) force = true;
            else if (t.equals("none")) reward = null;
            else if (REWARDS.contains(t)) reward = GemType.match(t);
            else { s.sendMessage("§c'" + a[i] + "' isn't a hold time, a reward (heretic, auratus, relay, none) or force."); return; }
        }
        UUID by = s instanceof Player p ? p.getUniqueId() : null;
        String err = caps().start(a[1], hold, reward, by, force);
        if (err != null) { s.sendMessage("§c" + err); return; }
        CaptureManager.Point pt = caps().get(a[1]);
        s.sendMessage("§6Started " + pt.name + ": hold it for " + hold + " s" + (reward == null ? "" : " to win the " + reward.display + " gem") + ".");
    }

    private void info(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§e/capture info <name>"); return; }
        CaptureManager.Point pt = caps().get(a[1]);
        if (pt == null) { s.sendMessage("§cNo capture point called '" + a[1] + "'."); return; }
        int[] b = pt.box, z = CaptureMath.size(b);
        s.sendMessage("§6" + pt.name + " §7(" + pt.world + ") " + b[0] + " " + b[1] + " " + b[2] + " to " + b[3] + " " + b[4] + " " + b[5]
                + ", " + z[0] + "x" + z[1] + "x" + z[2] + ", " + CaptureMath.hex(pt.color));
        if (!pt.running) { s.sendMessage("§7Not running."); return; }
        s.sendMessage("§aRunning: §fhold " + CaptureManager.secs(pt.goalTicks) + " s, reward " + (pt.reward == null ? "none" : pt.reward.display)
                + ", " + pt.state.name().toLowerCase(Locale.ROOT) + ", " + pt.on.size() + " on the point.");
        List<Map.Entry<UUID, Long>> top = new ArrayList<>(pt.held.entrySet());
        top.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        for (int i = 0; i < Math.min(5, top.size()); i++)
            s.sendMessage(" §f" + pt.names.getOrDefault(top.get(i).getKey(), "?") + " §7" + CaptureManager.secs(top.get(i).getValue()) + " s");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] a) {
        if (!s.hasPermission(CaptureManager.PERMISSION)) return Collections.emptyList();
        if (a.length == 1) return filter(SUBS, a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (CaptureManager.Point pt : caps().all()) names.add(pt.name);
        if (a.length == 2) return sub.equals("create") || sub.equals("list") ? Collections.emptyList() : filter(names, a[1]);
        switch (sub) {
            case "create" -> {
                if (a.length <= 8) return filter(coordSuggestions(s, (a.length - 3) % 3), a[a.length - 1]);
                if (a.length == 9) return filter(colors(), a[8]);
            }
            case "color", "colour" -> { if (a.length == 3) return filter(colors(), a[2]); }
            case "start" -> {
                if (a.length == 3) {
                    List<String> o = new ArrayList<>(List.of("30", "60", "120", "300"));
                    o.addAll(REWARDS);
                    return filter(o, a[2]);
                }
                if (a.length == 4) { List<String> o = new ArrayList<>(REWARDS); o.add("force"); return filter(o, a[3]); }
                if (a.length == 5) return filter(List.of("force"), a[4]);
            }
            default -> { }
        }
        return Collections.emptyList();
    }

    /** As /fill suggests them: the block you're looking at, or ~. {@code axis} 0, 1 or 2 is the coordinate being typed. */
    private static List<String> coordSuggestions(CommandSender s, int axis) {
        List<String> out = new ArrayList<>();
        if (s instanceof Player p) {
            Block b = p.getTargetBlockExact(5);
            if (b != null) {
                int[] v = {b.getX(), b.getY(), b.getZ()};
                StringBuilder sb = new StringBuilder();
                for (int i = axis; i < 3; i++) { if (i > axis) sb.append(' '); sb.append(v[i]); out.add(sb.toString()); }
            }
        }
        StringBuilder t = new StringBuilder();
        for (int i = axis; i < 3; i++) { if (i > axis) t.append(' '); t.append('~'); out.add(t.toString()); }
        return out;
    }

    private static List<String> colors() {
        List<String> out = new ArrayList<>(NamedTextColor.NAMES.keys());
        out.add("#B0703A");
        return out;
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String v : values) if (v.toLowerCase(Locale.ROOT).startsWith(p)) out.add(v);
        return out;
    }
}
