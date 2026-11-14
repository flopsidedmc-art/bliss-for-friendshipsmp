package dev.soulbound;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Custom resource-pack sounds (assets/blissgems/sounds.json). Events are played by name, so players
 * without the pack simply hear nothing from these; the vanilla sounds already in the code still play.
 */
final class Sfx {
    private Sfx() {}

    /** Only events listed in sounds.enabled-events play (default: just the Auratus parry). */
    private static boolean enabled(SoulboundGemsPlugin plugin, String event) {
        if (!plugin.getConfig().getBoolean("sounds.custom-enabled", true)) return false;
        if (!plugin.getConfig().isList("sounds.enabled-events")) return "auratus.parry".equals(event);
        return plugin.getConfig().getStringList("sounds.enabled-events").contains(event);
    }

    private static float vol(SoulboundGemsPlugin plugin, float base) {
        return (float) (base * plugin.getConfig().getDouble("sounds.volume", 1.0));
    }

    /** Heard by everyone nearby. */
    static void play(SoulboundGemsPlugin plugin, Location loc, String event, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null) return;
        if (!enabled(plugin, event)) return;
        loc.getWorld().playSound(loc, "blissgems:" + event, SoundCategory.PLAYERS, vol(plugin, volume), pitch);
    }

    /** Heard only by one player. */
    static void playTo(SoulboundGemsPlugin plugin, Player p, String event, float volume, float pitch) {
        if (p == null) return;
        if (!enabled(plugin, event)) return;
        p.playSound(p.getLocation(), "blissgems:" + event, SoundCategory.PLAYERS, vol(plugin, volume), pitch);
    }
}
