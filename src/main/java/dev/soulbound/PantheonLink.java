package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Soft link to the Pantheon plugin (not compiled against it), checked on every call: which running event a player is
 * in (Duels, Teams, Command, Motion), whether that event draws its own action bar, the arena points land effects keep
 * away from, and the Solstice season leader for the Finale. Without Pantheon, or if a call fails: nobody is in an
 * event, no points, no leader (null), and each failure is logged once.
 */
final class PantheonLink {
    private final SoulboundGemsPlugin plugin;
    private Plugin cached;
    private final Map<String, Method> methods = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    PantheonLink(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private Plugin pantheon() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Pantheon");
        if (p == null || !p.isEnabled()) return null;
        if (p != cached) { cached = p; methods.clear(); } // a reload: look everything up again
        return p;
    }

    boolean present() { return pantheon() != null; }

    private Object call(String name, Class<?>[] types, Object... args) {
        Plugin p = pantheon();
        if (p == null) return null;
        String key = name + types.length;
        Method m = methods.get(key);
        if (m == null && !methods.containsKey(key)) {
            try { m = p.getClass().getMethod(name, types); } catch (ReflectiveOperationException | RuntimeException ex) { warn(name, ex); }
            methods.put(key, m);
        }
        if (m == null) return null;
        try { return m.invoke(p, args); } catch (ReflectiveOperationException | RuntimeException ex) { warn(name, ex); return null; }
    }

    private void warn(String what, Exception ex) {
        if (warned.add(what)) plugin.getLogger().warning("Couldn't use Pantheon's " + what + " (" + ex.getClass().getSimpleName() + ").");
    }

    /** "duels", "teams", "command" or "motion" while the player is in that running Pantheon event, else null. */
    String eventName(Player p) {
        if (p == null) return null;
        Object o = call("eventName", new Class<?>[] {Player.class}, p);
        return o instanceof String s ? s : null;
    }

    boolean inEvent(Player p) { return eventName(p) != null; }

    /** The player's event draws its own action bar (a Motion run, a Teams match): our HUDs stay off it. */
    boolean eventHud(Player p) {
        return p != null && Boolean.TRUE.equals(call("eventHud", new Class<?>[] {Player.class}, p));
    }

    /** Every point Pantheon's events use (spawns, lobbies, zones, course points, checkpoints). */
    List<Location> arenaPoints() {
        Object o = call("arenaPoints", new Class<?>[0]);
        List<Location> out = new ArrayList<>();
        if (o instanceof List<?> list) for (Object x : list) if (x instanceof Location l) out.add(l);
        return out;
    }

    /** The Solstice season leader(s) (eligible, best season score), or null if Pantheon isn't there. */
    List<UUID> seasonLeaders() {
        Object o = call("seasonLeaders", new Class<?>[0]);
        if (!(o instanceof List<?> list)) return null;
        List<UUID> out = new ArrayList<>();
        for (Object x : list) if (x instanceof UUID u) out.add(u);
        return out;
    }

    int seasonScore(UUID id) {
        Object o = call("seasonScore", new Class<?>[] {UUID.class}, id);
        return o instanceof Integer i ? i : 0;
    }

    /** Season points from a Soulbound event (the Heretic's Games: track "heretic-games"). False without Pantheon. */
    boolean awardEventPoints(UUID id, String track, int points) {
        if (id == null || track == null || points == 0 || pantheon() == null) return false;
        call("awardEventPoints", new Class<?>[] {UUID.class, String.class, int.class}, id, track, points);
        return true;
    }
}
