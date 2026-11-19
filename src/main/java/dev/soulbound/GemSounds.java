package dev.soulbound;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every gem sound, read from {@code sounds.<gem>.<ability>} in config.yml so it can be tuned or muted without a
 * rebuild. Each entry is one sound plus at most one layer, played on the same tick:
 *
 * <pre>
 *   chad-strength: {event: ENTITY_RAVAGER_ROAR, volume: 0.9, pitch: 1.2, enabled: true,
 *                   layer: {event: ITEM_MACE_SMASH_GROUND_HEAVY, volume: 1.0, pitch: 0.6}}
 * </pre>
 *
 * {@code event} is a Bukkit sound name (ENTITY_RAVAGER_ROAR) or a sound key (minecraft:entity.ravager.roar, or a
 * resource-pack sound such as blissgems:relay.rewind). A pack sound replaces the vanilla one; when
 * sounds.custom-enabled is false the entry's {@code fallback} (same fields) plays instead, or nothing. Pitch is kept
 * within 0.5 to 2.0, sounds.volume scales every volume, and everything plays in SoundCategory.PLAYERS.
 */
final class GemSounds {
    private GemSounds() {}

    /** Bukkit sound names resolved to their keys once. */
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();

    /** Heard by everyone near {@code at}. */
    static void play(SoulboundGemsPlugin plugin, String id, Location at) { emit(plugin, id, at, null); }

    /** Heard only by {@code p}. */
    static void playTo(SoulboundGemsPlugin plugin, String id, Player p) {
        if (p != null) emit(plugin, id, p.getLocation(), p);
    }

    /** Whether sounds.&lt;id&gt; has a layer that would play (callers that make the layer's sound some other way skip it). */
    static boolean enabled(SoulboundGemsPlugin plugin, String id) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("sounds." + id);
        return s != null && s.getBoolean("enabled", true);
    }

    /** Plays only the main sound of an entry, without its layer. */
    static void playMain(SoulboundGemsPlugin plugin, String id, Location at) {
        ConfigurationSection s = entry(plugin, id);
        if (s != null && at != null && at.getWorld() != null) one(plugin, s, at, null);
    }

    private static void emit(SoulboundGemsPlugin plugin, String id, Location at, Player only) {
        if (at == null || at.getWorld() == null) return;
        ConfigurationSection s = entry(plugin, id);
        if (s == null) return;
        one(plugin, s, at, only);
        ConfigurationSection layer = s.getConfigurationSection("layer");
        if (layer != null && layer.getBoolean("enabled", true) && playable(plugin, layer.getString("event"))) one(plugin, layer, at, only);
    }

    /** The entry to play, or its fallback when it names a pack sound and pack sounds are off. Null when muted. */
    private static ConfigurationSection entry(SoulboundGemsPlugin plugin, String id) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("sounds." + id);
        if (s == null || !s.getBoolean("enabled", true)) return null;
        if (playable(plugin, s.getString("event"))) return s;
        ConfigurationSection fb = s.getConfigurationSection("fallback");
        return fb != null && fb.getBoolean("enabled", true) && playable(plugin, fb.getString("event")) ? fb : null;
    }

    private static boolean playable(SoulboundGemsPlugin plugin, String event) {
        return event != null && !event.isBlank() && (!isPack(event) || plugin.getConfig().getBoolean("sounds.custom-enabled", true));
    }

    /** A resource-pack sound: a namespaced key outside minecraft:. */
    static boolean isPack(String event) {
        int c = event == null ? -1 : event.indexOf(':');
        return c > 0 && !event.substring(0, c).equalsIgnoreCase("minecraft");
    }

    private static void one(SoulboundGemsPlugin plugin, ConfigurationSection s, Location at, Player only) {
        String key = resolve(s.getString("event"));
        if (key == null) return;
        float volume = (float) Math.max(0, s.getDouble("volume", 1.0) * plugin.getConfig().getDouble("sounds.volume", 1.0));
        float pitch = (float) clampPitch(s.getDouble("pitch", 1.0));
        if (only != null) only.playSound(at, key, SoundCategory.PLAYERS, volume, pitch);
        else at.getWorld().playSound(at, key, SoundCategory.PLAYERS, volume, pitch);
    }

    static double clampPitch(double pitch) { return Math.max(0.5, Math.min(2.0, pitch)); }

    /** The sound key for a config value: a Bukkit name (ENTITY_RAVAGER_ROAR) or a key as written. Null if unknown. */
    static String resolve(String event) {
        if (event == null || event.isBlank()) return null;
        String e = event.trim();
        if (e.indexOf(':') >= 0 || e.indexOf('.') >= 0) return e.toLowerCase(Locale.ROOT);
        String name = e.toUpperCase(Locale.ROOT);
        String cached = KEYS.get(name);
        if (cached != null) return cached;
        try {
            Object o = Sound.class.getField(name).get(null);
            if (o instanceof Sound sound) {
                String key = sound.key().asString();
                KEYS.put(name, key);
                return key;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not a Bukkit sound name
        }
        return null;
    }

    /** Every sounds.* entry whose event (or layer, or fallback) isn't a known sound, for a warning at startup. */
    static List<String> unknown(SoulboundGemsPlugin plugin) {
        List<String> bad = new ArrayList<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("sounds");
        if (root == null) return bad;
        for (String gem : root.getKeys(false)) {
            ConfigurationSection g = root.getConfigurationSection(gem);
            if (g == null) continue;
            for (String ability : g.getKeys(false)) {
                ConfigurationSection s = g.getConfigurationSection(ability);
                if (s == null) continue;
                for (String part : new String[]{"", "layer.", "fallback."}) {
                    String ev = s.getString(part + "event");
                    if (ev != null && !ev.isBlank() && !isPack(ev) && resolve(ev) == null) bad.add(gem + "." + ability + "." + part + "event: " + ev);
                }
            }
        }
        return bad;
    }
}
