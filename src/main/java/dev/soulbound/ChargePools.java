package dev.soulbound;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Charge pools for standard-gem abilities that hold several uses. Speedy Storm is the one
 * ability using a pool; its HUD slot shows "2/3 16s": charges left and seconds to the next one.
 *
 *   - tryUse() consumes a charge if one is available
 *   - each charge refills independently, recharge-seconds after it was used
 *   - hud() renders the HUD segment
 *
 * Settings: abilities.<ability>.charges and abilities.<ability>.charge-recharge-seconds.
 */
public final class ChargePools {

    private ChargePools() {
    }

    private static final Map<UUID, Map<String, Deque<Long>>> POOLS = new HashMap<>();

    public static int maxCharges(dev.soulbound.SoulboundGemsPlugin plugin, String ability) {
        String path = "abilities." + ability + ".charges";
        return Math.max(1, plugin.getConfig().getInt(path, defaultCharges(ability)));
    }

    private static int defaultCharges(String ability) {
        return "storm".equals(ability) ? 3 : 1;
    }

    private static long rechargeMs(dev.soulbound.SoulboundGemsPlugin plugin, String ability) {
        // Without an explicit setting, a charge takes as long as that ability's ordinary cooldown.
        int fallback = plugin.getConfig().getInt("cooldowns." + ability, 15);
        return Math.max(1, plugin.getConfig().getInt("abilities." + ability + ".charge-recharge-seconds", fallback)) * 1000L;
    }

    /** @return true if a charge was consumed. */
    public static boolean tryUse(dev.soulbound.SoulboundGemsPlugin plugin, Player player, String ability) {
        UUID id = player.getUniqueId();
        Deque<Long> times = POOLS.computeIfAbsent(id, k -> new HashMap<>())
                .computeIfAbsent(ability, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        // drop exhausted timestamps; remaining size = available charges
        long window = rechargeMs(plugin, ability);
        while (!times.isEmpty() && now - times.getFirst() >= window) {
            times.removeFirst();
        }
        int max = maxCharges(plugin, ability);
        if (times.size() >= max) return false;
        times.addLast(now);
        return true;
    }

    public static int available(dev.soulbound.SoulboundGemsPlugin plugin, Player player, String ability) {
        Map<String, Deque<Long>> mine = POOLS.get(player.getUniqueId());
        Deque<Long> times = mine == null ? null : mine.get(ability);
        if (times == null) return maxCharges(plugin, ability);
        long now = System.currentTimeMillis();
        long window = rechargeMs(plugin, ability);
        while (!times.isEmpty() && now - times.getFirst() >= window) {
            times.removeFirst();
        }
        return maxCharges(plugin, ability) - times.size();
    }

    /** Seconds until the next charge refills, 0 if all are up. */
    public static long nextRefillIn(dev.soulbound.SoulboundGemsPlugin plugin, Player player, String ability) {
        Map<String, Deque<Long>> mine = POOLS.get(player.getUniqueId());
        Deque<Long> times = mine == null ? null : mine.get(ability);
        if (times == null || times.isEmpty()) return 0;
        long window = rechargeMs(plugin, ability);
        return Math.max(0, (times.getFirst() + window - System.currentTimeMillis()) / 1000L);
    }

    /** HUD segment like the showcase: "1/3 16s", or plain "Ready!" at full pool. */
    public static String hud(dev.soulbound.SoulboundGemsPlugin plugin, Player player, String ability) {
        int max = maxCharges(plugin, ability);
        int have = available(plugin, player, ability);
        long secs = nextRefillIn(plugin, player, ability);
        if (have > 0 && secs <= 0) {
            return org.bukkit.ChatColor.RED.toString() + have + "/" + max + " " + org.bukkit.ChatColor.WHITE + "Ready!";
        }
        return org.bukkit.ChatColor.RED.toString() + have + "/" + max
                + " " + org.bukkit.ChatColor.AQUA + (secs > 0 ? secs + "s" : "Ready!");
    }

    public static void clear(UUID playerId) {
        POOLS.remove(playerId);
    }
}
