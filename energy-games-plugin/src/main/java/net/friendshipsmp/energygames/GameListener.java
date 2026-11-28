package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** Join + death handling (the "on join" and "on death of player" parts of the script). */
final class GameListener implements Listener {
    private final EnergyGamesPlugin plugin;

    GameListener(EnergyGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!plugin.state().energyGames) {
            plugin.setTeam(p, null);
            return;
        }
        Boolean status = plugin.state().participants.get(uuid);
        if (status == null) {
            plugin.setParticipant(uuid, true);
            plugin.state().save();
            plugin.setTeam(p, true);
            p.sendMessage(plugin.msg("joined"));
        } else {
            plugin.setTeam(p, status);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.state().energyGames) return;
        Player victim = event.getEntity();
        if (!Boolean.TRUE.equals(plugin.state().participants.get(victim.getUniqueId()))) return;
        plugin.setParticipant(victim.getUniqueId(), false);
        plugin.state().save();
        Bukkit.broadcastMessage(plugin.msg("eliminated"));
        plugin.setTeam(victim, false);
    }
}
