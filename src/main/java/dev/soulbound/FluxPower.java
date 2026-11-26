package dev.soulbound;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Flux's charged powers (flux: in config.yml). Q with the Tier 2 gem in your main hand starts charging (the gem stays):
 * watts rise, with blue particles and a hum that grow with them. Left-click fires the Energy Beam, as strong and long as
 * the watts, and spends them all. At exactly 100 W, left-clicking a player Grounds them instead (a stun and a shuffled
 * inventory). Keep charging past 100 W and it's Overcharge: on toward 200 W, hurting you every second, for a beam of up
 * to flux.overcharge.max-damage. Kinetic Overdrive fills its own meter from fights, mining and movement nearby.
 * Shocking Chance: your arrows sometimes call down (harmless) lightning that stings and freezes what they hit.
 */
final class FluxPower implements Listener {
    private static final Color LIGHT = Color.fromRGB(0x7FD4FF), DEEP = Color.fromRGB(0x1E5BFF), WHITE = Color.fromRGB(0xFFFFFF);

    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey stunKey;
    private final Map<UUID, double[]> charging = new HashMap<>(); // charger -> {watts, plateau seconds left}
    private final Map<UUID, FluxMath.Meter> meters = new HashMap<>();
    private final Map<UUID, Long> shockedAt = new HashMap<>();
    private final Map<UUID, Integer> stunned = new HashMap<>(); // entity -> the tick its stun ends
    private final Set<UUID> selfHurt = new HashSet<>();
    private final List<UUID> holders = new ArrayList<>(); // Tier 2 Flux holders (refreshed each second), for the meter
    private final Random rnd = new Random();
    private BukkitTask task;
    private long ticks;

    FluxPower(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.stunKey = new NamespacedKey(plugin, "flux_stun");
    }

    private GemListener gl() { return plugin.listener(); }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("flux." + path, def); }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }

    /** Plugin disable: charging stops (Overcharge back to 100), every stun comes off. */
    void shutdown() {
        if (task != null) task.cancel();
        for (UUID id : new ArrayList<>(charging.keySet())) stop(id);
        for (UUID id : new ArrayList<>(stunned.keySet())) unstun(Bukkit.getEntity(id));
        stunned.clear();
    }

    // ------------------------------------------------------------------ watts

    private double watts(UUID id) {
        double[] c = charging.get(id);
        return c != null ? c[0] : gl().fluxOf(gl().fluxWatts, id);
    }

    private void setWatts(UUID id, double w) {
        double[] c = charging.get(id);
        if (c != null) c[0] = w;
        gl().fluxWatts.put(id, (int) Math.floor(w));
    }

    /** flux.watts.per-ingot: what each ingot (or anything else listed) gives when fed. */
    int ingotWatts(org.bukkit.Material m) {
        Map<String, Integer> table = new LinkedHashMap<>();
        var sec = plugin.getConfig().getConfigurationSection("flux.watts.per-ingot");
        if (sec == null) { table.put("copper", 12); table.put("netherite", 10); table.put("gold", 8); table.put("iron", 5); }
        else for (String k : sec.getKeys(false)) table.put(k, sec.getInt(k));
        return FluxMath.ingotWatts(m.name(), table);
    }

    /** Feeding an ingot: its watts, up to 100. */
    int feed(Player p, int gain) {
        double now = FluxMath.fed(watts(p.getUniqueId()), gain);
        setWatts(p.getUniqueId(), now);
        return (int) Math.floor(now);
    }

    boolean charging(Player p) { return charging.containsKey(p.getUniqueId()); }

    // ------------------------------------------------------------------ charging (Q)

    /** Q with the gem: start charging, or stop (Overcharge drops back to 100). Banked ore energy goes in when you start. */
    void toggle(Player p) {
        UUID id = p.getUniqueId();
        if (charging.containsKey(id)) { stop(id); return; }
        int banked = gl().fluxOf(gl().fluxOre, id);
        if (banked > 0) { gl().fluxOre.put(id, 0); setWatts(id, FluxMath.fed(watts(id), banked)); }
        charging.put(id, new double[] {watts(id), Math.max(0, cfg("overcharge.hold-seconds", 1.0))});
        GemSounds.playScaled(plugin, "flux.beam-charge", p.getLocation(), p, 0.6, 1.0);
    }

    /** Charging stops; the watts stay, Overcharge drops back to 100. */
    void stop(UUID id) {
        double[] c = charging.remove(id);
        if (c != null) gl().fluxWatts.put(id, (int) Math.floor(FluxMath.stopped(c[0])));
    }

    private boolean holdsFlux(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        GemManager gems = plugin.gems();
        return gems.isGem(hand) && gems.getType(hand) == GemType.FLUX && gems.getTier(hand) >= 2
                && EnergyStage.allowsPowers(gems.getEnergy(hand)) && !gl().isGemDisabled(p);
    }

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) refreshHolders();
        for (Map.Entry<UUID, double[]> en : new ArrayList<>(charging.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead() || !holdsFlux(p)) { stop(en.getKey()); continue; } // switched away (or gone)
            double[] c = en.getValue();
            double[] next = FluxMath.charge(c[0], cfg("beam.charge-per-second", 12), 0.05, c[1]);
            c[1] = next[1];
            setWatts(p.getUniqueId(), next[0]);
            double w = next[0];
            if (FluxMath.overcharged(w) && ticks % 20 == 0) overchargeHurt(p);
            if (ticks % 2 == 0) chargeVisuals(p, w);
            if (ticks % 20 == 0) GemSounds.playScaled(plugin, "flux.beam-charge", p.getLocation(), null, 0.4 + w / 200 * 0.8, 0.8 + w / 200 * 0.7);
            if (FluxMath.overcharged(w) && ticks % 15 == 0) GemSounds.playScaled(plugin, "flux.overcharge", p.getLocation(), null, 0.5 + w / 400, 1.0 + (w - 100) / 200);
        }
        for (Map.Entry<UUID, Integer> s : new ArrayList<>(stunned.entrySet())) {
            if (Bukkit.getCurrentTick() < s.getValue()) continue;
            unstun(Bukkit.getEntity(s.getKey()));
            stunned.remove(s.getKey());
        }
    }

    /** Overcharge: flux.overcharge.damage-per-second, through armour, and it can kill you. */
    private void overchargeHurt(Player p) {
        selfHurt.add(p.getUniqueId());
        try { p.damage(cfg("overcharge.damage-per-second", 2.0), DamageSource.builder(DamageType.MAGIC).build()); }
        finally { selfHurt.remove(p.getUniqueId()); }
    }

    /** The charge above you, wilder with the watts (within 60 particles a tick). */
    private void chargeVisuals(Player p, double w) {
        World world = p.getWorld();
        Location base = p.getLocation();
        int n = FluxMath.particles(w);
        if (w < 50) { // a few above your head
            for (int i = 0; i < n; i++) world.spawnParticle(Particle.DUST, base.clone().add(rnd(0.35), 2.2 + rnd.nextDouble() * 0.5, rnd(0.35)), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(i % 2 == 0 ? LIGHT : DEEP, 0.7f));
            world.spawnParticle(Particle.ELECTRIC_SPARK, base.clone().add(0, 2.3, 0), 1, 0.2, 0.1, 0.2, 0.02);
            return;
        }
        double turn = ticks * 0.25;
        int sparks = w < 100 ? 3 : w < 150 ? 5 : 8;
        for (int i = 0; i < n; i++) { // swirls round your upper body
            double a = turn + i * (Math.PI * 2 / n), y = 1.0 + (i % 6) * 0.22, r = w < 100 ? 0.6 : 0.8 + 0.2 * Math.sin(turn + i);
            Color col = w >= 100 && i % 3 == 0 ? WHITE : i % 2 == 0 ? LIGHT : DEEP;
            world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, w >= 100 ? 0.9f : 0.75f));
        }
        world.spawnParticle(Particle.ELECTRIC_SPARK, base.clone().add(0, 1.4, 0), sparks, 0.5, 0.6, 0.5, 0.05);
        if (w >= 100 && w < 150 && ticks % 10 == 0) { // small sonic puffs swirling round you
            double a = turn * 1.5;
            world.spawnParticle(Particle.SONIC_BOOM, base.clone().add(Math.cos(a) * 1.2, 1.2, Math.sin(a) * 1.2), 1, 0, 0, 0, 0);
        } else if (w >= 150 && ticks % 4 == 0) { // the full storm
            for (int k = 0; k < 2; k++) {
                double a = turn * 1.5 + k * Math.PI;
                world.spawnParticle(Particle.SONIC_BOOM, base.clone().add(Math.cos(a) * 1.4, 1.0 + k * 0.5, Math.sin(a) * 1.4), 1, 0, 0, 0, 0);
            }
        }
    }

    private double rnd(double r) { return (rnd.nextDouble() * 2 - 1) * r; }

    // ------------------------------------------------------------------ firing (left-click)

    /**
     * Left-click: Ground at exactly 100 W with a player in range, otherwise the beam. Spends every watt and ends charging.
     * False if nothing happened (no watts), so the caller starts no cooldown.
     */
    boolean fire(Player p) {
        UUID id = p.getUniqueId();
        double w = watts(id);
        if (w < 1) {
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize("§7" + SmallCaps.of("No watts: charge with Q or feed ingots")));
            return false;
        }
        if (FluxMath.canGround(w)) {
            Player target = aimedPlayer(p, range(w));
            if (target != null && (gl().isTrusted(p, target))) {
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize("§7" + SmallCaps.of("You can't ground a trusted player")));
                return false;
            }
            if (target != null) {
                charging.remove(id);
                setWatts(id, 0);
                ground(p, target);
                return true;
            }
        }
        charging.remove(id);
        setWatts(id, 0);
        beam(p, w);
        return true;
    }

    private double range(double w) { return FluxMath.scale(w, cfg("beam.min-range", 12), cfg("beam.range-at-100", 30), cfg("overcharge.max-range", 48)); }

    private double damage(double w) { return FluxMath.scale(w, cfg("beam.min-damage", 3), cfg("beam.damage-at-100", 24), cfg("overcharge.max-damage", 90)); }

    private Player aimedPlayer(Player p, double range) {
        RayTraceResult r = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true, 0.4,
                e -> e instanceof Player o && !o.equals(p) && o.getGameMode() != org.bukkit.GameMode.SPECTATOR);
        return r != null && r.getHitEntity() instanceof Player o ? o : null;
    }

    /** The Energy Beam: damage and length from the watts, everyone along it hit once (never you or your trusted players). */
    private void beam(Player p, double w) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double len = range(w);
        RayTraceResult block = world.rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
        if (block != null) len = block.getHitPosition().distance(eye.toVector());
        double thick = FluxMath.thickness(w), dmg = damage(w);
        Set<UUID> hit = new HashSet<>();
        Location end = eye.clone().add(dir.clone().multiply(len));
        for (Entity e : world.getNearbyEntities(eye.clone().add(dir.clone().multiply(len / 2)), len / 2 + 2, len / 2 + 2, len / 2 + 2)) {
            if (!(e instanceof LivingEntity le) || e.equals(p) || (e instanceof Player o && gl().isTrusted(p, o))) continue;
            Vector to = le.getLocation().add(0, le.getHeight() / 2, 0).toVector().subtract(eye.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > len) continue;
            double off = to.clone().subtract(dir.clone().multiply(along)).length();
            if (off > thick + le.getWidth() / 2 + 0.6 || !hit.add(le.getUniqueId())) continue;
            gl().beamHit(le, dmg, p);
        }
        boolean max = w >= FluxMath.OVER_MAX - 0.5;
        drawBeam(eye, dir, len, w, max);
        if (max) {
            Particle flash = Particle.FLASH;
            if (flash.getDataType() == Color.class) world.spawnParticle(flash, end, 1, 0, 0, 0, 0, Color.WHITE);
            else world.spawnParticle(flash, end, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.EXPLOSION, end, 3, 0.4, 0.4, 0.4, 0);
            GemSounds.play(plugin, "flux.beam-max", end);
        } else GemSounds.playScaled(plugin, "flux.energy-beam", p.getLocation(), null, 0.6 + w / 200 * 0.8, 1.5 - w / 200 * 0.8);
        gl().beamUsed(p);
    }

    /** A thin blue line at low watts, thick and bright at 100, a huge blue-white beam with a sonic trail at 200; it glows a little after. */
    private void drawBeam(Location eye, Vector dir, double len, double w, boolean max) {
        int glow = FluxMath.afterglowTicks(w);
        for (int t = 0; t < glow; t++) {
            final int age = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> beamFrame(eye, dir, len, w, max, age, glow), t);
        }
    }

    private void beamFrame(Location eye, Vector dir, double len, double w, boolean max, int age, int glow) {
        World world = eye.getWorld();
        double thick = FluxMath.thickness(w);
        int perBlock = 3 + (int) (w / 25);
        float size = (float) (0.5 + w / 200 * 1.2);
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(dir).normalize();
        int steps = (int) Math.max(2, len * perBlock / (1 + age)); // thinner as it fades
        for (int i = 0; i <= steps; i++) {
            double d = len * i / steps, a = rnd.nextDouble() * Math.PI * 2, r = thick * Math.sqrt(rnd.nextDouble());
            Location at = eye.clone().add(dir.clone().multiply(d)).add(side.clone().multiply(Math.cos(a) * r)).add(up.clone().multiply(Math.sin(a) * r));
            Color c = w >= 100 && i % 3 == 0 ? WHITE : i % 2 == 0 ? LIGHT : DEEP;
            world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(c, Math.max(0.4f, size * (1 - age / (float) (glow + 1)))));
            if (age == 0 && i % 4 == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0.02);
            if (max && age == 0 && i % (perBlock * 2) == 0) world.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0);
        }
    }

    /** Ground: 100 W spent, they're stunned and their hotbar and inventory shuffled (armour and off hand stay), an arc between you. */
    private void ground(Player p, Player target) {
        stun(target, (int) Math.round(cfg("ground.stun-seconds", 1.5) * 20));
        PlayerInventory inv = target.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) items.add(inv.getItem(i));
        Collections.shuffle(items, rnd);
        for (int i = 0; i < 36; i++) inv.setItem(i, items.get(i));
        arc(p.getLocation().add(0, 1.2, 0), target.getLocation().add(0, 1.0, 0));
        target.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c" + SmallCaps.of("Grounded: your inventory scrambles")));
        p.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e" + SmallCaps.of("Grounded ") + "§f" + plugin.shownName(target)));
        GemSounds.play(plugin, "flux.ground", target.getLocation());
    }

    /** A jagged electric arc between two points. */
    private void arc(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        int n = Math.max(6, (int) (d.length() / 0.25));
        World w = a.getWorld();
        for (int k = 0; k <= n; k++) {
            Location at = a.clone().add(d.clone().multiply(k / (double) n)).add(rnd(0.2), rnd(0.2), rnd(0.2));
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
            if (k % 2 == 0) w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(LIGHT, 0.7f));
        }
    }

    // ------------------------------------------------------------------ stuns

    /** No moving or jumping for {@code ticks} (speed and jump strength to zero, and Slowness for the look of it). */
    void stun(LivingEntity v, int ticks) {
        unstun(v);
        for (Attribute at : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance inst = v.getAttribute(at);
            if (inst != null) inst.addTransientModifier(new AttributeModifier(stunKey, -1, AttributeModifier.Operation.ADD_SCALAR));
        }
        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 254, false, false, false));
        stunned.put(v.getUniqueId(), Bukkit.getCurrentTick() + Math.max(1, ticks));
        v.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, v.getLocation().add(0, v.getHeight() / 2, 0), 12, 0.3, 0.5, 0.3, 0.1);
    }

    private void unstun(Entity e) {
        if (!(e instanceof LivingEntity v)) return;
        for (Attribute at : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance inst = v.getAttribute(at);
            if (inst != null) inst.removeModifier(stunKey);
        }
    }

    // ------------------------------------------------------------------ the action bar

    /** The beam's entry while charging: the watts ("64W"), or Overcharge's % fading from the gem's colour to white. Null when not charging. */
    String beamEntry(Player p, String icon) {
        double[] c = charging.get(p.getUniqueId());
        if (c == null) return null;
        TextColor gem = LegacyComponentSerializer.legacySection().deserialize(GemType.FLUX.tint() + "x").color();
        if (gem == null) gem = TextColor.color(0x55FFFF);
        TextColor col = TextColor.lerp((float) FluxMath.whiteness(c[0]), gem, TextColor.color(0xFFFFFF));
        return legacy(col.value()) + icon + " " + FluxMath.readout(c[0]);
    }

    /** "§x§R§R§G§G§B§B": a hex colour as legacy codes. */
    static String legacy(int rgb) {
        StringBuilder b = new StringBuilder("§x");
        for (char ch : String.format("%06X", rgb & 0xFFFFFF).toCharArray()) b.append('§').append(ch);
        return b.toString();
    }

    /** Overdrive's entry: its meter in %, "FULL" when it's ready, "…" while it runs. */
    String overdriveEntry(Player p, String icon, boolean active) {
        if (active) return "§f" + icon + " §e…";
        FluxMath.Meter m = meters.get(p.getUniqueId());
        double v = m == null ? 0 : m.value;
        return v >= FluxMath.FULL ? "§f" + icon + " " + GemType.FLUX.tint() + "FULL" : "§7" + icon + " " + GemType.FLUX.tint() + (int) v + "%";
    }

    // ------------------------------------------------------------------ Overdrive's meter

    boolean overdriveFull(Player p) { FluxMath.Meter m = meters.get(p.getUniqueId()); return m != null && m.full(); }

    void emptyOverdrive(Player p) { FluxMath.Meter m = meters.get(p.getUniqueId()); if (m != null) m.empty(); }

    private void refreshHolders() {
        holders.clear();
        GemManager gems = plugin.gems();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack g = gl().findGem(p);
            if (g != null && gems.getType(g) == GemType.FLUX && gems.getTier(g) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(g)) && !gl().isGemDisabled(p))
                holders.add(p.getUniqueId());
        }
    }

    /** Something happened at {@code at}: every Tier 2 Flux holder within flux.overdrive.radius gets {@code amount}. */
    private void activity(Location at, double amount) {
        if (holders.isEmpty() || at == null || at.getWorld() == null || amount <= 0) return;
        double r = cfg("overdrive.radius", 12), cap = cfg("overdrive.max-per-second", 6);
        long now = System.currentTimeMillis();
        for (UUID id : holders) {
            Player h = Bukkit.getPlayer(id);
            if (h == null || !h.getWorld().equals(at.getWorld()) || h.getLocation().distanceSquared(at) > r * r) continue;
            meters.computeIfAbsent(id, k -> new FluxMath.Meter()).add(amount, now, cap);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent e) {
        if (selfHurt.contains(e.getEntity().getUniqueId())) return;
        activity(e.getEntity().getLocation(), cfg("overdrive.per-hit", 3)); // you hitting, being hit, or a fight nearby
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) { activity(e.getBlock().getLocation(), cfg("overdrive.per-block-mined", 1)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (holders.isEmpty() || e.getTo() == null || !e.getFrom().getWorld().equals(e.getTo().getWorld())) return;
        double d = e.getFrom().distance(e.getTo());
        if (d <= 0.01 || d > 10) return; // standing still, or a teleport
        activity(e.getTo(), d * cfg("overdrive.per-block-moved", 0.25));
    }

    // ------------------------------------------------------------------ Shocking Chance

    /** Your arrow or bolt hits: a flux.shock.chance chance of lightning (harmless to anyone else), a sting and a 1 s freeze. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        if (!(arrow.getShooter() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v) || v.equals(p)) return;
        ItemStack g = gl().findGem(p);
        GemManager gems = plugin.gems();
        if (g == null || gems.getType(g) != GemType.FLUX || !EnergyStage.allowsPassives(gems.getEnergy(g)) || gl().isGemDisabled(p)) return;
        if (v instanceof Player o && gl().isTrusted(p, o)) return; // trusted players are never shocked
        long now = System.currentTimeMillis();
        if (!FluxMath.shock(rnd.nextDouble(), cfg("shock.chance", 0.10), shockedAt.get(v.getUniqueId()), now, (long) (cfg("shock.cooldown-seconds", 3) * 1000))) return;
        shockedAt.put(v.getUniqueId(), now);
        v.getWorld().strikeLightningEffect(v.getLocation()); // only the look: no fire, nobody else hurt
        e.setDamage(e.getDamage() + Math.max(0, cfg("shock.damage", 2.0)));
        stun(v, (int) Math.round(cfg("shock.stun-seconds", 1.0) * 20));
        shockedAt.entrySet().removeIf(s -> now - s.getValue() > 60_000);
    }

    // ------------------------------------------------------------------ ending

    /** A big hit stops the charge (flux.beam.stop-on-damage, 0 = off). Your own Overcharge never does. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!charging.containsKey(id) || selfHurt.contains(id)) return;
        double big = cfg("beam.stop-on-damage", 0);
        if (big > 0 && e.getFinalDamage() >= big) stop(id);
    }

    /** True while the Overcharge itself is hurting them (Kinetic Burst isn't charged by it). */
    boolean selfHurting(Entity e) { return selfHurt.contains(e.getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        stop(id);
        meters.remove(id);
        unstun(e.getPlayer());
        stunned.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { stop(e.getEntity().getUniqueId()); }
}
