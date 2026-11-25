package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Circle of Life's blessing: a pink ring on the ground around you; you and every trusted player inside it when it's
 * cast get +4 hearts for circle-of-life.blessing.seconds, full hunger and saturation, and Regeneration II for 10 s.
 * The hearts are one keyed max-health modifier that's never saved (a transient modifier), so they can't stack and
 * can't outlive the time, a death, a logout or a restart (LifeMath.Hearts).
 */
final class LifeBlessing implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey key;
    private final LifeMath.Hearts hearts = new LifeMath.Hearts();

    LifeBlessing(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "life_blessing");
    }

    private double cfgD(String k, double def) { return plugin.getConfig().getDouble("life.circle-of-life.blessing." + k, def); }

    void start() { for (Player p : Bukkit.getOnlinePlayers()) clear(p); } // a reload: nobody keeps hearts from before

    void shutdown() { for (Player p : Bukkit.getOnlinePlayers()) clear(p); }

    /** The blessing around {@code p}; {@code trusts}: the trust list (as Astral Void and Heart Drainer use it). Returns who got it. */
    List<Player> cast(Player p, BiPredicate<Player, Player> trusts) {
        double r = Math.max(1, cfgD("radius", 6));
        Location c = p.getLocation();
        List<Player> inRing = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            Location l = o.getLocation();
            double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
            if (dx * dx + dz * dz <= r * r && Math.abs(l.getY() - c.getY()) <= 4 && !o.isDead()) inRing.add(o);
        }
        List<Player> blessed = LifeMath.blessed(p, inRing, trusts);
        long ms = (long) (Math.max(1, cfgD("seconds", 30)) * 1000);
        int regen = (int) Math.round(Math.max(0, cfgD("regeneration-seconds", 10)) * 20);
        for (Player o : blessed) bless(o, ms, regen);
        ring(c, r);
        GemSounds.play(plugin, "life.blessing", c);
        return blessed;
    }

    private void bless(Player o, long ms, int regenTicks) {
        AttributeInstance a = o.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) {
            a.removeModifier(key); // never stacking: a second blessing replaces the first
            a.addTransientModifier(new AttributeModifier(key, 8.0, AttributeModifier.Operation.ADD_NUMBER));
        }
        hearts.give(o.getUniqueId(), System.currentTimeMillis(), ms);
        o.setFoodLevel(20);
        o.setSaturation(20f);
        if (regenTicks > 0) o.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regenTicks, 1));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (o.isOnline() && hearts.due(o.getUniqueId(), System.currentTimeMillis())) clear(o);
        }, ms / 50 + 2);
    }

    /** The extra hearts off (and health brought down to the new maximum). */
    void clear(Player o) {
        hearts.take(o.getUniqueId());
        AttributeInstance a = o.getAttribute(Attribute.MAX_HEALTH);
        if (a == null || a.getModifier(key) == null) return;
        a.removeModifier(key);
        if (!o.isDead() && o.getHealth() > a.getValue()) o.setHealth(a.getValue());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { clear(e.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(plugin, () -> clear(e.getPlayer())); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { clear(e.getPlayer()); }

    /** About 5 s of a pink ring flat on the ground, slowly turning, with hearts, a few sparkles and a soft shimmer at the edge. */
    private void ring(Location c, double r) {
        World w = c.getWorld();
        Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(0xFF, 0x7F, 0xBF), 1.2f);
        Particle.DustOptions pale = new Particle.DustOptions(Color.fromRGB(0xFF, 0xC0, 0xDE), 1.0f);
        double y = c.getY() + 0.1;
        int points = (int) Math.max(24, Math.round(r * 8));
        final int[] t = {0};
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 50 || w == null) { task[0].cancel(); return; } // 50 x 2 ticks: about 5 s
            double spin = t[0] * 0.04;
            for (int i = 0; i < points; i++) {
                double a = spin + i * Math.PI * 2 / points;
                Location at = new Location(w, c.getX() + Math.cos(a) * r, y, c.getZ() + Math.sin(a) * r);
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, i % 2 == 0 ? pink : pale);
                if (i % 6 == 0) w.spawnParticle(Particle.END_ROD, at, 0, 0, 1, 0, 0.03); // the shimmer drifts up
            }
            if (t[0] % 3 == 0) {
                double a = Math.random() * Math.PI * 2, d = Math.random() * r;
                w.spawnParticle(Particle.HEART, c.getX() + Math.cos(a) * d, y + 0.4, c.getZ() + Math.sin(a) * d, 1);
            }
            if (t[0] % 4 == 0) {
                double a = Math.random() * Math.PI * 2;
                w.spawnParticle(Particle.HAPPY_VILLAGER, c.getX() + Math.cos(a) * r, y + 0.3, c.getZ() + Math.sin(a) * r, 2, 0.2, 0.1, 0.2, 0);
            }
        }, 0L, 2L);
    }
}
