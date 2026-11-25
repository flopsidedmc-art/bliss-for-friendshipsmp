package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What every rule shares: is it on (rules.&lt;rule&gt;.enabled, and not in a world rules.worlds turns off), who's
 * exempt (ops and creative from carry caps and bans, the Revenant holder with rules.exempt-revenant), the fights
 * (RuleMath.Fights), and the refusal messages: brief, in small caps, at most once every 2 s per rule per player.
 * Gem and mythic abilities are never these rules' business.
 */
final class RuleCore {
    final SoulboundGemsPlugin plugin;
    final RuleMath.Fights fights = new RuleMath.Fights();
    private final Map<String, Long> said = new HashMap<>();

    RuleCore(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    String path(String rule) { return "rules." + rule + "."; }

    boolean on(String rule) { return plugin.getConfig().getBoolean(path(rule) + "enabled", true); }

    /** The rule applies in this world (rules.worlds.&lt;world&gt;: false turns every rule off there, for event worlds). */
    boolean on(String rule, World w) {
        if (!on(rule)) return false;
        return w == null || plugin.getConfig().getBoolean("rules.worlds." + w.getName(), true);
    }

    double num(String rule, String key, double def) { return plugin.getConfig().getDouble(path(rule) + key, def); }

    /** Exempt from carry caps and bans: ops, creative (and spectator), and the Revenant holder if rules.exempt-revenant. */
    boolean exempt(Player p) {
        if (p.isOp() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        return plugin.getConfig().getBoolean("rules.exempt-revenant", false) && plugin.revenant() != null && plugin.revenant().isHolder(p);
    }

    /** Fight rules (cooldowns, counters) exempt only the Revenant holder (with rules.exempt-revenant) and creative. */
    boolean fightExempt(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        return plugin.getConfig().getBoolean("rules.exempt-revenant", false) && plugin.revenant() != null && plugin.revenant().isHolder(p);
    }

    boolean inFight(Player p) {
        return on("combat", p.getWorld()) && fights.inFight(p.getUniqueId(), System.currentTimeMillis());
    }

    /** A refusal, in small caps on the action bar, at most once every 2 s for this rule and player. */
    void say(Player p, String rule, String text) {
        String k = p.getUniqueId() + ":" + rule;
        long now = System.currentTimeMillis();
        Long last = said.get(k);
        if (last != null && now - last < 2000) return;
        said.put(k, now);
        p.sendActionBar(Component.text(SmallCaps.of(text), NamedTextColor.RED));
    }

    void forget(UUID id) { said.keySet().removeIf(k -> k.startsWith(id + ":")); }
}
