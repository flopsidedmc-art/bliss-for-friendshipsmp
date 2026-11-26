package dev.soulbound;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * How Speed's Blur clones look (the rules and the damage stay in GemListener). Each clone is a Mannequin wearing the
 * caster's skin, armour and held item, with a yellow outline (its own scoreboard team, so no other glow colour
 * changes). One more appears with each hit in the run (left of the target, right, then behind), they follow the target
 * smoothly, swing just after each of your hits (that's when their damage lands), spark between each other, flicker
 * when you hit fast, and shatter when the run ends. Without Mannequins (before Paper 1.21.9) they're vexes, as before.
 * They can't be hit, pushed, targeted or collided with, make no sounds, and are never saved.
 */
final class BlurClones {
    static final String TEAM = "sg_blur_clones";
    private static final Particle.DustOptions YELLOW_SMALL = new Particle.DustOptions(Color.fromRGB(0xFFE14D), 0.5f);
    private static final Particle.DustOptions YELLOW = new Particle.DustOptions(Color.fromRGB(0xFFE14D), 1.0f);

    private final SoulboundGemsPlugin plugin;
    private final Map<UUID, Run> runs = new HashMap<>(); // caster -> their clones
    private final Set<UUID> cloneIds = new HashSet<>();
    private final Random rnd = new Random();
    private BukkitTask task;
    private long ticks;

    private static final class Run {
        final UUID victim;
        final List<LivingEntity> clones = new ArrayList<>();
        int hits;
        long lastHitMs;
        int lastHitTick = -1;

        Run(UUID victim) { this.victim = victim; }
    }

    BlurClones(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    private int max() { return plugin.getConfig().getInt("abilities.blur.max-clones", 3); }

    private double dist() { return plugin.getConfig().getDouble("abilities.blur.clone-distance", 1.2); }

    boolean isClone(Entity e) { return e != null && cloneIds.contains(e.getUniqueId()); }

    /** The caster whose clone this is, or null. */
    UUID ownerOf(Entity e) {
        if (!isClone(e)) return null;
        for (Map.Entry<UUID, Run> r : runs.entrySet()) if (r.getValue().clones.contains(e)) return r.getKey();
        return null;
    }

    // ------------------------------------------------------------------ a run

    void begin(Player owner, LivingEntity victim) {
        remove(owner.getUniqueId());
        runs.put(owner.getUniqueId(), new Run(victim.getUniqueId()));
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * A hit the caster landed in the run (the first one too): one more clone (up to the max), a flicker if it came fast,
     * and each clone swings a few ticks later, a little later each, when {@code onSwing} deals its damage. Counted once
     * per tick, however many listeners report the same hit.
     */
    void hit(Player owner, LivingEntity victim, BiConsumer<UUID, LivingEntity> onSwing) {
        Run run = runs.get(owner.getUniqueId());
        if (run == null || run.lastHitTick == Bukkit.getCurrentTick()) return;
        run.lastHitTick = Bukkit.getCurrentTick();
        long now = System.currentTimeMillis();
        boolean fast = BlurMath.fast(run.lastHitMs, now);
        run.lastHitMs = now;
        run.hits++;
        while (run.clones.size() < BlurMath.clones(run.hits, max())) {
            LivingEntity c = spawn(owner, spot(run, run.clones.size(), owner, victim));
            if (c == null) break;
            run.clones.add(c);
            cloneIds.add(c.getUniqueId());
        }
        if (fast) flicker(run);
        int base = plugin.getConfig().getInt("abilities.blur.clone-delay-ticks", 3), step = plugin.getConfig().getInt("abilities.blur.clone-delay-step-ticks", 2);
        UUID ownerId = owner.getUniqueId();
        for (int i = 0; i < run.clones.size(); i++) {
            LivingEntity c = run.clones.get(i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!c.isValid() || runs.get(ownerId) != run) return;
                swing(c);
                onSwing.accept(ownerId, c);
            }, Math.max(1, BlurMath.swingDelay(i, base, step)));
        }
    }

    /** The run is over (hit back, stopped hitting, switched away, died, changed worlds): every clone shatters at once. */
    void shatter(UUID owner) {
        Run run = runs.remove(owner);
        if (run == null) return;
        Location sound = null;
        for (LivingEntity c : run.clones) {
            if (c.isValid()) {
                Location at = c.getLocation().add(0, 1, 0);
                World w = at.getWorld();
                w.spawnParticle(Particle.DUST, at, 25, 0.3, 0.6, 0.3, 0, YELLOW);
                w.spawnParticle(Particle.ELECTRIC_SPARK, at, 20, 0.3, 0.6, 0.3, 0.3);
                if (sound == null) sound = at;
            }
            discard(c);
        }
        if (sound != null) GemSounds.play(plugin, "speed.blur-shatter", sound);
        stopIfIdle();
    }

    /** Gone without a show (logout, chunk unload, plugin disable). */
    void remove(UUID owner) {
        Run run = runs.remove(owner);
        if (run == null) return;
        for (LivingEntity c : run.clones) discard(c);
        stopIfIdle();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(runs.keySet())) remove(id);
        if (task != null) { task.cancel(); task = null; }
    }

    private void discard(LivingEntity c) {
        cloneIds.remove(c.getUniqueId());
        Team t = team(false);
        if (t != null) t.removeEntry(c.getUniqueId().toString());
        if (c.isValid()) c.remove();
    }

    private void stopIfIdle() {
        if (runs.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    // ------------------------------------------------------------------ the clones

    /** The yellow outline and no collisions or name tags: a team of its own on the main scoreboard. */
    private Team team(boolean create) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM);
        if (t == null && create) {
            t = sb.registerNewTeam(TEAM);
            t.color(NamedTextColor.YELLOW);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return t;
    }

    private LivingEntity spawn(Player owner, Location at) {
        LivingEntity c = null;
        if (MannequinBody.available()) {
            try { c = MannequinBody.spawnClone(owner, at); }
            catch (Throwable t) { plugin.getLogger().warning("Blur clone Mannequin failed (" + t + "), using a vex."); }
        }
        if (c == null) {
            try {
                org.bukkit.entity.Vex vex = at.getWorld().spawn(at, org.bukkit.entity.Vex.class);
                vex.setAI(false); // it stands where the clone goes; it never attacks on its own
                c = vex;
            } catch (Exception ex) { return null; }
        }
        c.setPersistent(false); // never saved: a crash can't leave clones behind
        c.setInvulnerable(true);
        c.setSilent(true);
        c.setCollidable(false);
        c.setGravity(false);
        c.setGlowing(true);
        c.setRemoveWhenFarAway(false);
        team(true).addEntry(c.getUniqueId().toString());
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), 12, 0.25, 0.5, 0.25, 0.15);
        return c;
    }

    /** Your armour and what's in your hands, as copies that can never drop (BlurClones never lets a clone die). */
    static void dress(LivingEntity c, Player p) {
        EntityEquipment eq = c.getEquipment();
        if (eq == null) return;
        eq.setHelmet(copy(p.getInventory().getHelmet()));
        eq.setChestplate(copy(p.getInventory().getChestplate()));
        eq.setLeggings(copy(p.getInventory().getLeggings()));
        eq.setBoots(copy(p.getInventory().getBoots()));
        eq.setItemInMainHand(copy(p.getInventory().getItemInMainHand()));
        eq.setItemInOffHand(copy(p.getInventory().getItemInOffHand()));
    }

    private static ItemStack copy(ItemStack it) { return it == null ? null : it.clone(); }

    private void swing(LivingEntity c) {
        if (c instanceof org.bukkit.entity.Vex vex) { // the old look: the vex's charge pose for a moment
            vex.setCharging(true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (vex.isValid()) vex.setCharging(false); }, 5L);
        } else c.swingMainHand();
    }

    /** Hit fast: every clone vanishes for two ticks. */
    private void flicker(Run run) {
        List<Player> seen = new ArrayList<>();
        for (LivingEntity c : run.clones) {
            if (!c.isValid()) continue;
            for (Player o : c.getWorld().getPlayers()) {
                if (o.getLocation().distanceSquared(c.getLocation()) > 64 * 64) continue;
                o.hideEntity(plugin, c);
                seen.add(o);
            }
        }
        List<LivingEntity> clones = new ArrayList<>(run.clones);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (LivingEntity c : clones) {
                if (!c.isValid()) continue;
                for (Player o : seen) if (o.isOnline()) o.showEntity(plugin, c);
            }
        }, 2L);
    }

    /** The target's front: toward the caster (they're fighting), or the way the target faces. */
    private static Vector front(Entity owner, LivingEntity victim) {
        Vector f = owner.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (f.lengthSquared() < 1e-4) f = victim.getLocation().getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        return f.normalize();
    }

    private Location spot(Run run, int i, Entity owner, LivingEntity victim) {
        Vector f = front(owner, victim);
        double[] o = BlurMath.slot(i, f.getX(), f.getZ(), dist());
        Location v = victim.getLocation();
        Location at = v.clone().add(o[0], 0, o[1]);
        at.setYaw(BlurMath.yaw(at.getX(), at.getZ(), v.getX(), v.getZ()));
        at.setPitch(0);
        return at;
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        ticks++;
        for (Map.Entry<UUID, Run> en : new ArrayList<>(runs.entrySet())) {
            Run run = en.getValue();
            Player owner = Bukkit.getPlayer(en.getKey());
            Entity t = Bukkit.getEntity(run.victim);
            if (owner == null || !(t instanceof LivingEntity victim) || !victim.isValid()) continue; // GemListener ends it
            for (int i = 0; i < run.clones.size(); i++) {
                LivingEntity c = run.clones.get(i);
                if (!c.isValid()) continue;
                Location want = spot(run, i, owner, victim), now = c.getLocation();
                boolean snap = !now.getWorld().equals(want.getWorld()) || now.distanceSquared(want) > 36;
                Location to = new Location(want.getWorld(), BlurMath.follow(now.getX(), want.getX(), snap),
                        BlurMath.follow(now.getY(), want.getY(), snap), BlurMath.follow(now.getZ(), want.getZ(), snap));
                to.setYaw(BlurMath.yaw(to.getX(), to.getZ(), victim.getLocation().getX(), victim.getLocation().getZ()));
                to.setPitch(0);
                c.teleport(to);
            }
            if (ticks % 4 == 0) spark(run, victim);
        }
    }

    /** A thin crackling arc between two clones (or the one clone and the target). */
    private void spark(Run run, LivingEntity victim) {
        List<LivingEntity> live = new ArrayList<>();
        for (LivingEntity c : run.clones) if (c.isValid()) live.add(c);
        if (live.isEmpty()) return;
        Location a, b;
        if (live.size() == 1) { a = live.get(0).getLocation(); b = victim.getLocation(); }
        else {
            int i = rnd.nextInt(live.size()), j = (i + 1 + rnd.nextInt(live.size() - 1)) % live.size();
            a = live.get(i).getLocation();
            b = live.get(j).getLocation();
        }
        a = a.clone().add(0, 1.1, 0);
        b = b.clone().add(0, 1.1, 0);
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        int n = Math.max(4, (int) (len / 0.2));
        World w = a.getWorld();
        for (int k = 0; k <= n; k++) {
            double u = k / (double) n, lift = 0.3 * Math.sin(Math.PI * u);
            Location at = a.clone().add(d.clone().multiply(u)).add((rnd.nextDouble() - 0.5) * 0.25, lift + (rnd.nextDouble() - 0.5) * 0.25, (rnd.nextDouble() - 0.5) * 0.25);
            w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, YELLOW_SMALL);
            if (k % 2 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
        }
    }
}
