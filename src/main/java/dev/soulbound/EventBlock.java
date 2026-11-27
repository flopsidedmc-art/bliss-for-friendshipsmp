package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Powers that don't work for a player in a running Pantheon event (asked through PantheonLink). Which ones is
 * event-blocked-powers in config.yml: "all" for every event plus one list per event (duels, teams, command, motion),
 * each a mix of groups (teleport, astral, repurpose, movement) and single powers. A refusal costs no cooldown and shows
 * "ɴᴏᴛ ɪɴ ᴀɴ ᴇᴠᴇɴᴛ" on the action bar, at most every 2 s.
 */
final class EventBlock {
    /** The groups and the powers in each. A power can be in two groups (Thunderstep: teleport and movement). */
    static final Map<String, List<String>> GROUPS = Map.of(
            "teleport", List.of("rewind", "rift", "thunderstep", "temporal-divinity", "revenant-plunge", "revenant-rising", "revenant-undertow"),
            "astral", List.of("projection", "unbounded"),
            "repurpose", List.of("repurpose"),
            "movement", List.of("dash", "double-jump", "drift", "golden-drill", "bloodlinking", "auratus-chains", "flagpole",
                    "overdrive-speed", "thunderstep", "blur", "speedy-storm", "speed-passive", "flow-speed"));

    /** Groups become their powers; anything else is a single power (lower case). */
    static Set<String> expand(Collection<String> entries) {
        Set<String> out = new LinkedHashSet<>();
        if (entries == null) return out;
        for (String e : entries) {
            if (e == null) continue;
            String k = e.trim().toLowerCase(Locale.ROOT);
            List<String> g = GROUPS.get(k);
            if (g != null) out.addAll(g); else out.add(k);
        }
        return out;
    }

    /** Whether {@code power} is blocked in {@code event} (null: not in an event, nothing is blocked). */
    static boolean blocked(String event, Collection<String> forAll, Collection<String> forEvent, String power) {
        if (event == null || power == null) return false;
        String p = power.toLowerCase(Locale.ROOT);
        return expand(forAll).contains(p) || expand(forEvent).contains(p);
    }

    private final SoulboundGemsPlugin plugin;
    private final Map<UUID, Long> said = new HashMap<>();

    EventBlock(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    /** The player's running Pantheon event, or null. */
    String event(Player p) { return plugin.pantheon() == null ? null : plugin.pantheon().eventName(p); }

    /** Blocked for this player right now (no message: passives and checks on other players). */
    boolean blockedFor(Player p, String power) {
        String ev = event(p);
        if (ev == null) return false;
        var c = plugin.getConfig();
        return blocked(ev, c.getStringList("event-blocked-powers.all"), c.getStringList("event-blocked-powers." + ev), power);
    }

    /** Blocked: tells the player (at most every 2 s) and returns true. Call it before any cooldown or charge is spent. */
    boolean refuse(Player p, String power) {
        if (!blockedFor(p, power)) return false;
        say(p);
        return true;
    }

    /** The refusal on the action bar, held 1.5 s so the gem HUD doesn't cover it. */
    void say(Player p) {
        long now = System.currentTimeMillis();
        Long last = said.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        said.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text(SmallCaps.of("Not in an event"), NamedTextColor.RED));
        plugin.holdActionBar(p, 1500L);
    }

    void forget(UUID id) { said.remove(id); }
}
