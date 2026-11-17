package dev.soulbound;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.*;

/** Four active powers (Rewind, Time Dilate, Temporal Divinity, Rift); Tuned In is exclusively a server-accounted combat meter. */
public final class RelayGem implements Listener {
    private static final PotionEffectType[] CLEANSE = {
        PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
        PotionEffectType.MINING_FATIGUE, PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS,
        PotionEffectType.NAUSEA, PotionEffectType.HUNGER, PotionEffectType.LEVITATION, PotionEffectType.DARKNESS
    };
    private record Snap(long t, Location loc, double health) {}
    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey quickenedKey = new NamespacedKey("soulbound-gems", "relay_quickened");
    private final NamespacedKey chargeKey = new NamespacedKey("soulbound-gems", "relay_charge_v2");
    private final Map<UUID, Deque<Snap>> history = new HashMap<>();
    private final Map<UUID, long[]> rewindSlots = new HashMap<>();
    private final Map<UUID, Long> lastRewind = new HashMap<>(), dilationReady = new HashMap<>(), divinityReady = new HashMap<>();
    private final Map<UUID, Long> lastGain = new HashMap<>(), lastCombat = new HashMap<>(), lastInput = new HashMap<>(), lastActive = new HashMap<>();
    private final Map<UUID, Integer> tunedCharge = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> tags = new HashMap<>();
    private final TemporalFlow flow;
    private final TemporalDomain domain;
    private final RelayRift rift;
    private long ticks;

    public RelayGem(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        flow = new TemporalFlow(plugin);
        domain = new TemporalDomain(plugin, flow);
        Bukkit.getPluginManager().registerEvents(domain, plugin);
        rift = new RelayRift(plugin, this);
        Bukkit.getPluginManager().registerEvents(rift, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
    }
    private long now() { return System.currentTimeMillis(); }
    private double cfgD(String k, double d) { return plugin.getConfig().getDouble("mythics.relay." + k, d); }
    private long cfgL(String k, long d) { return plugin.getConfig().getLong("mythics.relay." + k, d); }
    private int cfgI(String k, int d) { return plugin.getConfig().getInt("mythics.relay." + k, d); }
    private boolean cfgB(String k, boolean d) { return plugin.getConfig().getBoolean("mythics.relay." + k, d); }
    private boolean holds(Player p) { return plugin.mythics().heldMythicType(p) == GemType.RELAY; }
    private boolean isRelay(ItemStack s) { return plugin.gems().isGem(s) && plugin.gems().getType(s) == GemType.RELAY; }
    public boolean mainHandIsRelay(Player p) { return isRelay(p.getInventory().getItemInMainHand()); }
    public boolean offHandIsRelay(Player p) { return isRelay(p.getInventory().getItemInOffHand()); }
    private int maxCharge() { return Math.max(1, cfgI("temporal.charge.maximum", 100)); }
    private int charge(UUID id) { return Math.min(maxCharge(), tunedCharge.getOrDefault(id, 0)); }
    private void spend(UUID id, int amount) { tunedCharge.put(id, Math.max(0, charge(id) - Math.max(0, amount))); }
    public double timeScale(Location at, UUID owner) { return flow.scaleAt(at, owner); }
    public boolean inDomain(UUID id) { return domain.contains(id); }
    private static String fmt(long ms) { return Math.max(1, (ms + 999) / 1000) + "s"; }
    private boolean safe(Location l) {
        World w = l.getWorld();
        return w != null && l.getY() >= w.getMinHeight() + 1 && l.getY() < w.getMaxHeight() - 2
            && w.getWorldBorder().isInside(l) && w.getChunkAt(l).load()
            && l.getBlock().isPassable() && l.clone().add(0,1,0).getBlock().isPassable()
            && l.getBlock().getType() != Material.LAVA;
    }

    /** Main-hand actions only. Offhand Relay still grants passives while using a sword. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void handleInteract(PlayerInteractEvent e) {
        if (!mainHandIsRelay(e.getPlayer()) || e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isLeftClick() && !e.getAction().isRightClick()) return;
        // Air interactions may be pre-cancelled by vanilla; block protection DENY is respected.
        if (e.getClickedBlock() != null && e.useInteractedBlock() == Event.Result.DENY) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isDead() || !cfgB("enabled", true) || !plugin.getConfig().getBoolean("mythics.enabled", true)) return;
        if (e.getAction().isLeftClick()) {
            // Sneak + left-click is Rift. useRewind ignores sneaking, so the swing listener cannot also Rewind.
            if (p.isSneaking()) rift.openMenu(p); else useRewind(p);
            return;
        }
        long n = now();
        if (n - lastInput.getOrDefault(p.getUniqueId(), 0L) < 250) return;
        lastInput.put(p.getUniqueId(), n);
        if (p.isSneaking()) useDivinity(p); else useDilation(p);
    }
    public void useDilation(Player p) {
        UUID id = p.getUniqueId(); long n = now();
        int cost = cfgI("temporal.charge.dilate-cost", 0);
        if (domain.contains(id) || dilationReady.getOrDefault(id, 0L) > n || charge(id) < cost) return;
        if (!flow.start(p)) return;
        spend(id, cost);
        dilationReady.put(id, n + Math.max(0, cfgL("temporal.dilate.cooldown-seconds", 65)) * 1000);
        Sfx.play(plugin, p.getLocation(), "relay.dilation", 1, 1);
    }
    private void useDivinity(Player p) {
        UUID id = p.getUniqueId(); long n = now();
        int required = Math.min(maxCharge(), Math.max(1, cfgI("temporal.divinity.required-charge", 100)));
        if (divinityReady.getOrDefault(id, 0L) > n || charge(id) < required || domain.contains(id)) return;
        if (!domain.start(p)) return; // transaction: journal + all teleports must succeed first
        spend(id, required);
        divinityReady.put(id, n + Math.max(0, cfgL("temporal.divinity.cooldown-seconds", 90)) * 1000);
        persist(p);
        p.saveData();
        flow.removeOwner(id);
        history.remove(id);
    }
    public void tick() {
        ticks++;
        domain.tick();
        flow.tick();
        rift.tick();
        if (ticks % 2 != 0) return;
        long n = now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            for (ItemStack item : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()})
                if (isRelay(item)) GemManager.prepareRelayItem(item);
            if (!p.isDead() && holds(p) && !domain.contains(id)) {
                Deque<Snap> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
                h.addLast(new Snap(n, p.getLocation().clone(), p.getHealth()));
                while (!h.isEmpty() && n - h.peekFirst().t() > cfgL("rewind.window-ms", 5000) + 2000) h.removeFirst();
            } else history.remove(id);
            applyQuickened(p, !p.isDead() && holds(p));
            if (ticks % 20 == 0) {
                long grace = Math.max(0, cfgL("temporal.charge.decay-grace-seconds", 15)) * 1000;
                if (n - lastCombat.getOrDefault(id, n) > grace) spend(id, cfgI("temporal.charge.decay-per-second", 0));
                persist(p);
            }
        }
        if (ticks % 200 == 0) {
            tags.values().forEach(m -> m.values().removeIf(v -> v <= n));
            tags.values().removeIf(Map::isEmpty);
        }
    }
    private void applyQuickened(Player p, boolean active) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;
        AttributeModifier existing = null;
        for (AttributeModifier m : attr.getModifiers()) {
            if (quickenedKey.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        double bonus = Math.max(0.0, cfgD("passives.attack-speed-multiplier", 1.2) - 1.0);
        if (!active || bonus <= 0.0) {
            if (existing != null) attr.removeModifier(existing);
            return;
        }
        if (existing != null && Math.abs(existing.getAmount() - bonus) < 1.0e-6) return;
        if (existing != null) attr.removeModifier(existing);
        attr.addTransientModifier(new AttributeModifier(quickenedKey, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    public void useRewind(Player p) {
        if (!mainHandIsRelay(p) || p.isSneaking() || p.isDead() || domain.contains(p.getUniqueId())) return;
        UUID id = p.getUniqueId();
        long now = now();
        // Main-hand and off-hand interact events can both fire for one click.
        Long last = lastRewind.get(id);
        if (last != null && now - last < 250L) return;

        int charges = Math.max(1, cfgI("rewind.charges", 2));
        long[] slots = rewindSlots.get(id);
        if (slots == null || slots.length != charges) {
            slots = new long[charges];
            rewindSlots.put(id, slots);
        }
        int slot = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] <= now) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return;

        Deque<Snap> h = history.get(id);
        if (h == null || h.isEmpty()) return;
        long target = now - cfgL("rewind.window-ms", 5000L);
        Snap best = h.peekFirst();
        for (Snap s : h) {
            if (s.t() <= target) best = s;
            else break;
        }

        Location from = p.getLocation();
        Location to = best.loc().clone();
        if (to.getWorld() == null || !to.getWorld().equals(from.getWorld())) return;
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());

        int cost = cfgI("temporal.charge.rewind-cost", 0);
        if (charge(id) < cost || !safe(to) || !p.teleport(to)) return;
        spend(id, cost);
        lastRewind.put(id, now);
        slots[slot] = now + cfgL("rewind.regen-ms", 20000L);
        drawRewindTrail(from, to);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.setHealth(Math.min(p.getMaxHealth(), Math.max(p.getHealth(), best.health())));
        p.setFireTicks(0);
        for (PotionEffectType t : CLEANSE) {
            if (t != null) p.removePotionEffect(t);
        }
        p.getWorld().playSound(to, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.6f);
        Sfx.play(plugin, to, "relay.rewind", 1.0f, 1.0f);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
    }

    private void drawRewindTrail(Location from, Location to) {
        World w = from.getWorld();
        Vector line = to.toVector().subtract(from.toVector());
        double len = line.length();
        if (len < 0.5) return;
        Vector step = line.clone().normalize().multiply(0.6);
        Location cur = from.clone().add(0, 1, 0);
        int n = (int) Math.min(60, len / 0.6);
        for (int i = 0; i < n; i++) {
            w.spawnParticle(Particle.END_ROD, cur, 1, 0.05, 0.05, 0.05, 0.0);
            cur.add(step);
        }
    }


    /** Animation catches entity/air swings; 250ms guard deduplicates the interact event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING && mainHandIsRelay(e.getPlayer())
            && plugin.getConfig().getBoolean("mythics.enabled", true)) useRewind(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void tagDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !holds(p) || !(e.getEntity() instanceof Player v)
            || plugin.listener().isTrusted(p, v)) return;
        Map<UUID, Long> mine = tags.get(p.getUniqueId());
        if (mine != null && mine.getOrDefault(v.getUniqueId(), 0L) > now())
            e.setDamage(e.getDamage() * (1 + cfgD("passives.tag-damage-bonus", 0.05)));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("mythics.enabled", true) || e.getFinalDamage() < cfgD("temporal.charge.minimum-damage", 0.5)) return;
        Player attacker = e.getDamager() instanceof Player p ? p :
            e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker == null || !(e.getEntity() instanceof LivingEntity victim) || victim.equals(attacker)
            || domain.contains(attacker.getUniqueId()) || domain.contains(victim.getUniqueId())) return;
        if (victim instanceof Player v) {
            if (v.getGameMode() != GameMode.SURVIVAL && v.getGameMode() != GameMode.ADVENTURE) return;
            if (plugin.listener().isTrusted(attacker, v) || plugin.listener().isTrusted(v, attacker)) return;
            var team = attacker.getScoreboard().getEntryTeam(attacker.getName());
            if (team != null && team.hasEntry(v.getName())) return;
            long cutoff = now() - cfgL("temporal.charge.afk-timeout-seconds", 30) * 1000;
            if (lastActive.getOrDefault(v.getUniqueId(), 0L) < cutoff || lastActive.getOrDefault(attacker.getUniqueId(), 0L) < cutoff) return;
            gain(v, cfgI("temporal.charge.received", 4));
            if (holds(attacker)) tags.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>()).put(v.getUniqueId(), now() + cfgL("passives.tag-duration-ms", 30000));
        } else if (!cfgB("temporal.charge.allow-mobs", false)) return;
        gain(attacker, cfgI("temporal.charge.hit", 8));
    }
    private void gain(Player p, int value) {
        if (!holds(p) || value <= 0) return;
        UUID id = p.getUniqueId(); long n = now();
        lastCombat.put(id, n);
        if (n - lastGain.getOrDefault(id, 0L) < Math.max(50, cfgL("temporal.charge.minimum-spacing-ms", 400))) return;
        tunedCharge.put(id, Math.min(maxCharge(), charge(id) + value)); lastGain.put(id, n);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void active(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()))
            lastActive.put(e.getPlayer().getUniqueId(), now()); // knockback alone does not count as activity
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void activeSwing(PlayerAnimationEvent e) { lastActive.put(e.getPlayer().getUniqueId(), now()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void activeInput(PlayerInteractEvent e) { lastActive.put(e.getPlayer().getUniqueId(), now()); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void oldConsume(PlayerItemConsumeEvent e) { if (isRelay(e.getItem())) e.setCancelled(true); }
    private void load(Player p) {
        UUID id = p.getUniqueId();
        if (cfgB("temporal.charge.persist-restart", true)) tunedCharge.put(id, p.getPersistentDataContainer().getOrDefault(chargeKey, PersistentDataType.INTEGER, 0));
        loadCooldown(p, "dilate", dilationReady); loadCooldown(p, "divinity", divinityReady);
        int count = Math.max(1, cfgI("rewind.charges", 2)); long[] slots = new long[count];
        for (int i=0;i<count;i++) slots[i] = p.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin,"relay_rewind_"+i), PersistentDataType.LONG, 0L);
        rewindSlots.put(id, slots); lastCombat.put(id, now());
    }
    private void loadCooldown(Player p, String name, Map<UUID, Long> map) {
        map.put(p.getUniqueId(), p.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin,"relay_cd_"+name), PersistentDataType.LONG, 0L));
    }
    private void persist(Player p) {
        p.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, cfgB("temporal.charge.persist-restart", true) ? charge(p.getUniqueId()) : 0);
        p.getPersistentDataContainer().set(new NamespacedKey(plugin,"relay_cd_dilate"), PersistentDataType.LONG, dilationReady.getOrDefault(p.getUniqueId(),0L));
        p.getPersistentDataContainer().set(new NamespacedKey(plugin,"relay_cd_divinity"), PersistentDataType.LONG, divinityReady.getOrDefault(p.getUniqueId(),0L));
        long[] slots=rewindSlots.get(p.getUniqueId());
        if(slots!=null) for(int i=0;i<slots.length;i++) p.getPersistentDataContainer().set(new NamespacedKey(plugin,"relay_rewind_"+i),PersistentDataType.LONG,slots[i]);
    }
    @EventHandler public void join(PlayerJoinEvent e) { load(e.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId(); history.remove(id); flow.removeOwner(id);
        if (!cfgB("temporal.charge.persist-death", false)) tunedCharge.remove(id);
        persist(e.getEntity());
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); UUID id=p.getUniqueId();
        if (!cfgB("temporal.charge.persist-logout", true)) tunedCharge.remove(id);
        persist(p); applyQuickened(p,false); history.remove(id); lastActive.remove(id); lastCombat.remove(id);
        lastGain.remove(id); lastInput.remove(id); lastRewind.remove(id); tags.remove(id); flow.removeOwner(id);
        if (cfgB("temporal.charge.persist-restart", true) || !cfgB("temporal.charge.persist-logout", true)) tunedCharge.remove(id);
        rewindSlots.remove(id); dilationReady.remove(id); divinityReady.remove(id);
    }
    public void shutdown() {
        rift.shutdown(); domain.shutdown(); flow.shutdown();
        for(Player p:Bukkit.getOnlinePlayers()) { persist(p); applyQuickened(p,false); }
    }
    public String hud(Player p) {
        UUID id=p.getUniqueId(); long n=now(); int max=Math.max(1,cfgI("rewind.charges",2)), ready=0; long next=Long.MAX_VALUE;
        long[] slots=rewindSlots.get(id);
        for(int i=0;i<max;i++) { long t=slots!=null && i<slots.length?slots[i]:0; if(t<=n)ready++; else next=Math.min(next,t-n); }
        String rw=ready>0?"§b"+ready+"/"+max:"§b"+fmt(next);
        String dl=flow.active(id)?"§cActive…":status(dilationReady.getOrDefault(id,0L)-n);
        String dv=domain.contains(id)?"§cActive…":status(divinityReady.getOrDefault(id,0L)-n);
        String rs=rift.hudStatus(p);
        boolean glyphs=cfgB("temporal.hud.use-glyphs",true);
        String rg=glyphs?plugin.getConfig().getString("mythics.relay.hud.rewind-glyph","\uE040"):"Rewind";
        String dg=glyphs?plugin.getConfig().getString("mythics.relay.hud.dilation-glyph","\uE041"):"Dilate";
        String rfg=glyphs?plugin.getConfig().getString("mythics.relay.hud.rift-glyph","\uE043"):"Rift";
        String dvg=glyphs?plugin.getConfig().getString("mythics.relay.hud.divinity-glyph","\uE044"):"Divinity";
        List<String> frames=plugin.getConfig().getStringList("mythics.relay.hud.tuned-charge-glyphs");
        int frame=Math.min(5,charge(id)*5/maxCharge());
        String meter=glyphs&&frames.size()>frame?frames.get(frame)+" ":"";
        String inlineRift=rs==null||riftByHotbar()?"":"  §f"+rfg+" "+rs;
        // With the pack's icons the bar shows icons and timers only: no ability names, and Tuned In is just its meter.
        String tuned=glyphs&&!meter.isEmpty()?meter.trim():"Tuned In §b"+charge(id)+"/"+maxCharge();
        return "§f"+rg+" "+rw+"  §f"+dg+" "+dl+inlineRift+"  §f"+dvg+" "+dv+"  §f"+tuned;
    }
    /** The Rift slot sits beside the hotbar unless glyphs are off (no pack) or rift-position is "inline". */
    private boolean riftByHotbar() {
        return cfgB("temporal.hud.use-glyphs",true)
            && "hotbar".equalsIgnoreCase(plugin.getConfig().getString("mythics.relay.hud.rift-position","hotbar"));
    }
    /**
     * The Relay action bar: the usual centred line, plus the Rift icon and status drawn just right of
     * the hotbar. That part uses the pack's blissgems:hud_low font (the same glyphs drawn lower) and
     * moves the pen there and back, so it adds no width and the centred line stays exactly where it was.
     */
    public net.kyori.adventure.text.Component hudComponent(Player p) {
        String main=hud(p);
        var legacy=net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        var bar=legacy.deserialize(main);
        String rs=rift.hudStatus(p);
        if(rs==null||!riftByHotbar())return bar;
        String status=rs.replace("\u2026","...");
        int w=HudText.width(main);
        // The client starts the line at -(w/2) from screen centre, so it ends at +ceil(w/2). The hotbar's right
        // edge is +91 and the icon's frame starts 5px into its glyph, so the glyph goes at +90: a 4px gap.
        int toIcon=90+plugin.getConfig().getInt("mythics.relay.hud.rift-hotbar-offset",0)-(w-w/2);
        int slot=HudText.advance('\uE043')+HudText.advance(' ')+HudText.width(status);
        String s=HudText.shift(toIcon)+"\uE043 "+status+HudText.shift(-(toIcon+slot));
        return bar.append(legacy.deserialize(s).font(net.kyori.adventure.key.Key.key("blissgems","hud_low")));
    }
    private String status(long ms) { return ms>0?"§b"+fmt(ms):"§aReady"; }
}
