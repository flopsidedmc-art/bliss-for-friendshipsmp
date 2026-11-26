package dev.soulbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Passives tick, actionbar, powers, energy kill/death, protection. */
public class GemListener implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final GemManager gems;

    /** Cooldown end times per player, per ability key ("nullify", "chad", "crisp", ...). Nothing is shared. */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    // Flux has four HUD entries with independent status: beam/ground, burst charge, wattage, overdrive.
    private final Map<UUID, Long> fluxBeamCooldown = new HashMap<>();
    private final Map<UUID, Long> fluxBurstCooldown = new HashMap<>();
    private final Map<UUID, Integer> hitCount = new HashMap<>();
    private final Map<UUID, Boolean> chadArmed = new HashMap<>();
    private final Map<UUID, ItemStack> pendingRespawnGem = new HashMap<>();
    // Strength systems (user-supplied kit)
    private final Map<UUID, Long> nullifiedUntil = new HashMap<>();
    private final Map<UUID, Long> chadUntil = new HashMap<>();
    private final Map<UUID, Integer> critCount = new HashMap<>();
    private final Map<UUID, Long> frailUntil = new HashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    // Fire systems (user-supplied kit)
    private final Map<UUID, Long> fireballChargeStart = new HashMap<>();
    private final Map<UUID, Double> fireballCharge = new HashMap<>(); // 0..1, grown every 2 ticks while charging (FireballCharge)
    private final Map<UUID, Long> meteorUntil = new HashMap<>();
    private final Map<UUID, Long> meteorLastShot = new HashMap<>();
    private final Map<UUID, Long> cozyCooldown = new HashMap<>();
    private final Map<UUID, FireCamp> campfires = new HashMap<>();
    private final Map<UUID, UUID> fireballOwner = new HashMap<>();
    /** Explosion power carried by each launched Fireball, set from its charge level. */
    private final Map<UUID, Float> fireballPower = new HashMap<>();
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> fireballBars = new HashMap<>();
    /** True only while a Fireball's own explosion is being resolved; its damage is dealt by fireballBlastDamage instead. */
    private boolean fireballBlasting;

    /** Which player launched a tracked fireball, or null. */
    UUID fireballOwnerOf(UUID projectileId) {
        return fireballOwner.get(projectileId);
    }
    // Wealth systems (user-supplied kit). Abilities are offhand-right-click only.
    private final Map<UUID, org.bukkit.inventory.Inventory> pockets = new HashMap<>();
    private final Map<UUID, Long> unfortunateUntil = new HashMap<>();
    private final Map<UUID, Long> itemLockUntil = new HashMap<>();
    private final Map<UUID, Long> richRushUntil = new HashMap<>();
    private final Map<UUID, Integer> oreCount = new HashMap<>();
    // Speed systems (user-supplied kit). Thunderstep is offhand-right-click only.
    /** One running Blur: who it is on and when its caster last hit them. Its clones are BlurClones'. */
    private static final class BlurRun {
        UUID victim;
        long lastHit;
        int energy;
    }
    private final Map<UUID, BlurRun> blurRuns = new HashMap<>();
    private final Map<UUID, PuffMath.Combo> puffCombos = new HashMap<>(); // Puff holder -> their hit combo
    private final Map<UUID, Long> cloneLastHit = new HashMap<>(); // Blur clone -> its last hit (ms)
    private BlurClones blurClones;

    BlurClones blurClones() {
        if (blurClones == null) blurClones = new BlurClones(plugin);
        return blurClones;
    }
    private final Map<UUID, SpeedStorm> storms = new HashMap<>();
    private final Map<UUID, Long> stormLastStrike = new HashMap<>();
    private final Map<UUID, Long> amplifyCooldown = new HashMap<>();
    // Astra systems (user-supplied kit). Abilities are offhand-right-click only.
    private final Map<UUID, Long> gemDisabledUntil = new HashMap<>();
    private final Map<UUID, Long> unboundedLast = new HashMap<>(); // Unbounded: 30 s between uses (AstralSpectate does the rest)
    private final Map<UUID, Long> voidCooldown = new HashMap<>();
    private final Map<UUID, Integer> astraRightTick = new HashMap<>(); // one Astra right-click a tick (a click on a player arrives twice)
    private final Set<UUID> daggerShots = new HashSet<>();
    // Astra Daggers (T2): a fan of 5 behind you, summoned by the first throw (Volley)
    private final Map<UUID, Volley> volleys = new HashMap<>();
    private final Map<UUID, Long> astraDaggerFireCooldown = new HashMap<>();
    private final Set<ItemDisplay> flyingDaggers = new HashSet<>(); // dagger models following their arrows, or fading out
    private static final int ASTRA_DAGGER_CMD = 12350; // the pack's Astra dagger (blissgems:custom/astra_dagger), not the Spirit Dagger
    // Life systems (user-supplied kit). Vortex is offhand-right-click only.
    private final Map<UUID, Double> heartOrig = new HashMap<>();
    private final Map<UUID, Long> heartUntil = new HashMap<>();
    // Flux systems (user-supplied kit). Burst is offhand-right-click only.
    final Map<UUID, Integer> fluxCharge = new HashMap<>();
    final Map<UUID, Integer> fluxOre = new HashMap<>();
    final Map<UUID, Integer> fluxWatts = new HashMap<>();
    private final Map<UUID, Integer> flowMove = new HashMap<>();
    private final Map<UUID, Integer> flowMine = new HashMap<>();
    private final Map<UUID, Location> flowLastLoc = new HashMap<>();
    private final Map<UUID, Long> overdriveUntil = new HashMap<>();
    private final Map<UUID, Integer> overdriveTaken = new HashMap<>();
    private final Map<UUID, Boolean> overdriveArmed = new HashMap<>();

    private static class SpeedStorm {
        Location loc;
        long until;
        int radius;
        SpeedStorm(Location loc, long until, int radius) { this.loc = loc; this.until = until; this.radius = radius; }
    }

    private static class FireCamp {
        Location loc;
        long until;
        int radius;
        FireCamp(Location loc, long until, int radius) { this.loc = loc; this.until = until; this.radius = radius; }
    }

    public GemListener(SoulboundGemsPlugin plugin) {
        this.plugin = plugin;
        this.gems = plugin.gems();
    }

    // ---------- helpers ----------

    /** Active gem: only a gem actually held in main/offhand grants gameplay effects. */
    public ItemStack findGem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (gems.isGem(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off)) return off;
        return null;
    }

    private ItemStack heldGem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (gems.isGem(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off)) return off;
        return null;
    }

    /** Per-ability base cooldowns (seconds), x1.5 while Cracked/Scratched. */
    private long cooldownMs(String which, int energy) {
        int base = switch (which) {
            case "crisp", "thunderstep", "blur" -> 15;
            case "pockets", "burst" -> 10;
            case "doublejump" -> 5;
            case "vortex", "bash" -> 60;
            case "dash" -> 40;
            case "daggers" -> 90;
            case "projection" -> 60; // its tags lock gems
            case "chad", "nullify", "itemlock", "storm", "heartdrainer", "circle", "beam", "void" -> 120;
            case "fireball" -> 120;
            case "overdrive", "cozy" -> 180;
            case "amp" -> 240;
            case "richrush" -> 300;
            case "ability", "bounty" -> plugin.getConfig().getInt("cooldowns-seconds.tier2-ability", 35);
            default -> plugin.getConfig().getInt("cooldowns-seconds.power", 20);
        };
        // Per-ability overrides live under cooldowns.<key> (see /config).
        base = plugin.getConfig().getInt("cooldowns." + which, base);
        double mult = EnergyStage.cooldownMultiplier(energy);
        return (long) (base * mult * 1000L);
    }

    /** Above zero only while abilityDamage is dealing its hit, so the cap applies to that hit alone. */
    private int abilityHits;
    private int uncappedHits; // the Energy Beam: an ability hit past the cap (Overcharge reaches flux.overcharge.max-damage)

    /**
     * Flat damage from a standard-gem ability (Crisp, Breezy Bash, Dash, Kinetic Burst, Energy Beam).
     * The base numbers were written for unarmored targets and barely scratch enchanted diamond, so they
     * are scaled by ability-damage.multiplier; onAbilityDamageCap then limits the total after the
     * holder's on-hit bonuses (Overdrive, Ground) so no single ability hit can one-shot through armor.
     */
    private void abilityDamage(LivingEntity victim, double base, Player source) {
        abilityDamage(victim, base, source, true);
    }

    /**
     * Ability damage: base x ability-damage.multiplier, capped (onAbilityDamageCap). With ability-damage.pierce-armor
     * and {@code pierce}, it skips armour points the way magic does; Protection and Resistance still count. Without,
     * it lands like a hit from the caster.
     */
    private void abilityDamage(LivingEntity victim, double base, Player source, boolean pierce) {
        double scaled = base * Math.max(0.0, plugin.getConfig().getDouble("ability-damage.multiplier", 3.0));
        abilityHits++;
        try {
            if (pierce && plugin.getConfig().getBoolean("ability-damage.pierce-armor", true)) victim.damage(scaled, piercing(source));
            else victim.damage(scaled, source);
        } finally {
            abilityHits--;
        }
    }

    /** The same scaled, capped ability damage, with armour counting. */
    void abilityHit(LivingEntity victim, double base, Player source) {
        abilityDamage(victim, base, source, false);
    }

    /** An Energy Beam hit (FluxPower): raw HP before armour, past the ability cap. */
    void beamHit(LivingEntity victim, double raw, Player source) {
        abilityHits++;
        uncappedHits++;
        try { victim.damage(Math.max(0, raw), source); } finally { uncappedHits--; abilityHits--; }
    }

    void beamUsed(Player p) { used(p, GemNames.tx("Energy Beam")); }

    /** Left-click with Tier 2 Flux: the beam (or Ground), on its short cooldown, which starts only if something fired. */
    private void fluxFire(Player p, int energy) {
        if (remain(fluxBeamCooldown, p.getUniqueId()) > 0 || plugin.flux() == null) return;
        if (plugin.flux().fire(p)) fluxBeamCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("beam", energy));
    }

    /** The Auratus' chains, Aegis and slam: raw HP (no multiplier), the ability-damage cap, armour counts (AuratusGem drops Protection). */
    void rawAbilityHit(LivingEntity victim, double raw, Player source) {
        abilityHits++;
        try { victim.damage(Math.max(0, raw), source); } finally { abilityHits--; }
    }

    /** Raw damage (no multiplier or cap) that skips armour points when ability-damage.pierce-armor is on: the Heretic's saws and slams. */
    void pierceHit(LivingEntity victim, double amount, Player source) {
        if (source == null) { victim.damage(amount); return; }
        if (plugin.getConfig().getBoolean("ability-damage.pierce-armor", true)) victim.damage(amount, piercing(source));
        else victim.damage(amount, source);
    }

    /** Magic from the caster: armour points don't reduce it, Protection does. Shields don't block it. */
    private static DamageSource piercing(Player source) {
        return DamageSource.builder(DamageType.INDIRECT_MAGIC).withCausingEntity(source).withDirectEntity(source).build();
    }

    /** Runs after onHit (NORMAL) has applied the holder's bonuses. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAbilityDamageCap(EntityDamageByEntityEvent e) {
        if (abilityHits <= 0 || uncappedHits > 0) return;
        double cap = plugin.getConfig().getDouble("ability-damage.cap", 40.0);
        if (cap > 0 && e.getDamage() > cap) e.setDamage(cap);
    }

    /** Milliseconds left on one ability's own cooldown. */
    private long cdRemain(Player p, String ability) {
        Map<String, Long> mine = cooldowns.get(p.getUniqueId());
        Long until = mine == null ? null : mine.get(ability);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    private void cdStart(Player p, String ability, long durationMs) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(ability, System.currentTimeMillis() + durationMs);
    }

    private void cdClear(Player p, String ability) {
        Map<String, Long> mine = cooldowns.get(p.getUniqueId());
        if (mine != null) mine.remove(ability);
    }

    private long remain(Map<UUID, Long> map, UUID id) {
        Long until = map.get(id);
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    private final Map<String, Long> announceThrottle = new HashMap<>();

    /** Tier icon from the pack font: T1 triangle / T2 crystal, else plain diamond. */
    private String tierDiamond(Player p) {
        ItemStack gem = heldGem(p);
        if (gem == null) gem = findGem(p);
        if (gem != null && gems.getTier(gem) >= 2) return "🔮";
        return "🔺";
    }

    /** Standard-gem usage line: "◆ Used Crisp." (tier-colored diamond). */
    private void used(Player p, String ability) {
        // Silenced — no chat spam, keep the wonder
    }

    /** Standard-gem targeted line: "◆ Used Bounty Hunter on Mugm." (throttled 5s). */
    private void usedOn(Player p, String ability, String target) {
        // Silenced — no chat spam
    }

    /** Standard-gem group line: "◆ Used Circle of Life Group (3)." (throttled 5s). */
    private void usedGroup(Player p, String ability, int count) {
        // Silenced — no chat spam
    }

    // ---------- tick: passives + actionbar ----------
    /** Applies the official Bliss Pack v3.5 visual binding to existing gems when a player joins. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (int i = 0; i < p.getInventory().getStorageContents().length; i++) {
            ItemStack item = p.getInventory().getStorageContents()[i];
            if (gems.isGem(item) && !gems.isMythic(item)) gems.refreshVisuals(item);
            gems.styleLore(item);
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off) && !gems.isMythic(off)) gems.refreshVisuals(off);
        gems.styleLore(off);
    }


    public void tick() {
        long now = System.currentTimeMillis();
        fireTick(now);
        stormTick(now);
        blurTick(now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            drawChadDots(p, now);
            ItemStack gem = findGem(p);
            if (gem == null) continue;
            gems.styleLore(gem); // gems made before small-caps lore switch over when held
            GemType type = gems.getType(gem);
            int tier = gems.getTier(gem);
            int energy = gems.getEnergy(gem);
            if (type == null) continue;
            if (type.isMythic()) continue;

            // Passives (blocked at Broken/Ruined)
            if (EnergyStage.allowsPassives(energy)) {
                applyPassive(p, type, tier);
            }

            // Mythics alone glow (togglable). Standard gems never do.
            if (type.isMythic() && plugin.getConfig().getBoolean("mythics.glow", true)) {
                if (!p.hasPotionEffect(PotionEffectType.GLOWING)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, true, false, false));
                }
            }

            // Disabled gems show dark status and grant nothing
            if (isGemDisabled(p)) {
                ItemStack heldD = heldGem(p);
                if (heldD != null && gems.getType(heldD) == type) {
                    Long until = gemDisabledUntil.get(p.getUniqueId());
                    long secs = (until == null) ? 0 : Math.max(0, (until - now) / 1000);
                    p.sendActionBar(Component.text(ChatColor.DARK_PURPLE + "✦ GEM DISABLED " + secs + "s"));
                }
                continue;
            }

            // Puff Double Jump: grant flight while grounded so space-double-tap can jump
            if (type == GemType.PUFF && EnergyStage.allowsPassives(energy)
                    && (p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE)) {
                if (p.isOnGround() && !p.getAllowFlight()) p.setAllowFlight(true);
            }

            // Flux Flow State: momentum builds speed + mining haste, then decays
            if (type == GemType.FLUX && EnergyStage.allowsPassives(energy)) {
                Location last = flowLastLoc.put(p.getUniqueId(), p.getLocation().clone());
                int move = fluxOf(flowMove, p.getUniqueId());
                if (last != null && last.getWorld().equals(p.getWorld()) && last.distanceSquared(p.getLocation()) > 0.25) {
                    move = Math.min(21, move + 1);
                } else if (move > 0) {
                    move = Math.max(0, move - 1);
                }
                flowMove.put(p.getUniqueId(), move);
                int mine = Math.max(0, fluxOf(flowMine, p.getUniqueId()) - 1);
                flowMine.put(p.getUniqueId(), mine);
                if (move >= 7) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, Math.min(2, move / 7 - 1), true, false, false));
                if (mine >= 7) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, Math.min(2, mine / 7 - 1), true, false, false));
            }

            // Actionbar for held gem (accurate live status)
            ItemStack held = heldGem(p);
            if (held != null) {
                if (plugin.revenant() == null || !plugin.revenant().hudActive(p)) sendActionbar(p, held); // the Revenant HUD has the bar
                if (type == GemType.STRENGTH && (plugin.bounty() == null || !plugin.bounty().hunting(p)))
                    proximityHint(p, energy); // a hunt shows its own time instead (BountyHunt)
            }
        }
    }

    private void applyPassive(Player p, GemType type, int tier) {
        int ampStr = plugin.getConfig().getInt("passives.strength-level", 1) - 1;
        int ampSpd = plugin.getConfig().getInt("passives.speed-level", 1) - 1;
        switch (type) {
            case STRENGTH:
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, Math.max(0, ampStr), true, false, false));
                // User kit: Sharpness 3 (T1) / Sharpness 5 (T2) on swords and axes while held
                int wantSharp = (tier >= 2) ? 5 : 3;
                for (ItemStack w : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
                    if (w == null || gems.isGem(w)) continue;
                    String mn = w.getType().name();
                    if (mn.endsWith("_SWORD") || mn.endsWith("_AXE")) {
                        int cur = w.getEnchantmentLevel(Enchantment.SHARPNESS);
                        if (cur < wantSharp) {
                            w.addUnsafeEnchantment(Enchantment.SHARPNESS, wantSharp);
                        }
                    }
                }
                break;
            case FIRE:
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 80, 0, true, false, false));
                // User kit: T1 Flame 1 on bows; T2 Fire Aspect 2 (swords) + Flame 1 (bows)
                for (ItemStack w : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
                    if (w == null || gems.isGem(w)) continue;
                    String mn = w.getType().name();
                    if (mn.equals("BOW") || mn.equals("CROSSBOW")) {
                        if (w.getEnchantmentLevel(Enchantment.FLAME) < 1) w.addUnsafeEnchantment(Enchantment.FLAME, 1);
                    }
                    if (tier >= 2 && mn.endsWith("_SWORD")) {
                        if (w.getEnchantmentLevel(Enchantment.FIRE_ASPECT) < 2) w.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
                    }
                }
                break;
            case SPEED:
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, Math.max(0, ampSpd), true, false, false));
                // Dolphin's Grace only in water with no boots
                boolean inWater = p.getLocation().getBlock().isLiquid();
                boolean noBoots = p.getInventory().getBoots() == null || p.getInventory().getBoots().getType() == Material.AIR;
                if (inWater && noBoots) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 80, 0, true, false, false));
                }
                // User kit: Efficiency 2 (T1) / 5 (T2) on digging tools, Soul Speed on boots
                int effWant = (tier >= 2) ? 5 : 2;
                for (ItemStack tool : p.getInventory().getContents()) {
                    if (tool == null || gems.isGem(tool)) continue;
                    String mn = tool.getType().name();
                    if (mn.endsWith("_PICKAXE") || mn.endsWith("_SHOVEL") || mn.endsWith("_AXE") || mn.endsWith("_HOE")) {
                        if (tool.getEnchantmentLevel(Enchantment.EFFICIENCY) < effWant)
                            tool.addUnsafeEnchantment(Enchantment.EFFICIENCY, effWant);
                    }
                }
                for (ItemStack boot : p.getInventory().getArmorContents()) {
                    if (boot == null || boot.getType() == Material.AIR) continue;
                    if (boot.getType().name().endsWith("_BOOTS")) {
                        if (boot.getEnchantmentLevel(Enchantment.SOUL_SPEED) < 3)
                            boot.addUnsafeEnchantment(Enchantment.SOUL_SPEED, 3);
                    }
                }
                break;
            case WEALTH:
                // User kit: Luck 1/HotV 1 (T1), Luck 2/HotV 2 (T2) while held
                int luckAmp = (tier >= 2) ? 1 : 0;
                p.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, 120, luckAmp, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 120, luckAmp, true, false, false));
                // Auto-enchants apply when the gem itself is in the offhand
                ItemStack offW = p.getInventory().getItemInOffHand();
                if (offW != null && gems.isGem(offW) && gems.getType(offW) == GemType.WEALTH) {
                    ItemStack tool = p.getInventory().getItemInMainHand();
                    if (tool != null && !gems.isGem(tool)) {
                        String mn = tool.getType().name();
                        if (mn.endsWith("_SWORD")) {
                            int want = (tier >= 2) ? 3 : 1;
                            if (tool.getEnchantmentLevel(Enchantment.LOOTING) < want)
                                tool.addUnsafeEnchantment(Enchantment.LOOTING, want);
                            if (tier >= 2 && tool.getEnchantmentLevel(Enchantment.MENDING) < 1)
                                tool.addUnsafeEnchantment(Enchantment.MENDING, 1);
                        }
                        if (mn.endsWith("_PICKAXE") || mn.endsWith("_SHOVEL") || mn.endsWith("_HOE")) {
                            int wantF = (tier >= 2) ? 3 : 1;
                            if (tool.getEnchantmentLevel(Enchantment.FORTUNE) < wantF)
                                tool.addUnsafeEnchantment(Enchantment.FORTUNE, wantF);
                            if (tier >= 2 && tool.getEnchantmentLevel(Enchantment.MENDING) < 1)
                                tool.addUnsafeEnchantment(Enchantment.MENDING, 1);
                        }
                    }
                }
                break;
            case FLUX:
                // User kit: Tireless — immune to Hunger, Slowness, Weakness (scrubbed here + blocked new).
                // Flow State speed/haste is applied in tick() from movement/mining momentum.
                p.removePotionEffect(PotionEffectType.HUNGER);
                p.removePotionEffect(PotionEffectType.SLOWNESS);
                p.removePotionEffect(PotionEffectType.WEAKNESS);
                break;
            case ASTRA:
                // User kit: dodge handled on hit (Phasing). No aura potion.
                break;
            case LIFE:
                // User kit: regen + endless Absorption 2, bonus saturation, wither scrubbed
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 0, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 1, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 80, 0, true, false, false));
                p.removePotionEffect(PotionEffectType.WITHER);
                break;
            case PUFF: // no Slow Falling any more: Puff has no fall damage anyway
                // Sculk Silence: unable to alert sculk shriekers
                // (handled via player move event — no sculk shrieker alert when holding Puff)
                // User kit: Power 3/Punch 1 (T1), Power 5/Punch 2 (T2) on bows
                int powWant = (tier >= 2) ? 5 : 3;
                int punWant = (tier >= 2) ? 2 : 1;
                for (ItemStack bow : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
                    if (bow == null || gems.isGem(bow)) continue;
                    String mn = bow.getType().name();
                    if (mn.equals("BOW") || mn.equals("CROSSBOW")) {
                        if (bow.getEnchantmentLevel(Enchantment.POWER) < powWant)
                            bow.addUnsafeEnchantment(Enchantment.POWER, powWant);
                        if (bow.getEnchantmentLevel(Enchantment.PUNCH) < punWant)
                            bow.addUnsafeEnchantment(Enchantment.PUNCH, punWant);
                    }
                }
                break;
        }
    }

    private void sendActionbar(Player p, ItemStack gem) {
        // Tracking alerts replace the cooldown bar for 3s.
        Long alert = trackAlertUntil.get(p.getUniqueId());
        if (alert != null) {
            if (alert > System.currentTimeMillis()) return;
            trackAlertUntil.remove(p.getUniqueId());
        }
        GemType type = gems.getType(gem);
        int tier = gems.getTier(gem);
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        // Bliss-style HUD: no gem name. Each entry is one ability's own icon followed by that
        // ability's own status, so nothing on the bar repeats and no two entries share a timer.
        // Tier 2: left-click power, the ability, right-click power (HudStatus.gemBar). Tier 1: the ability only. Each
        // entry is built with its own icon and its own timer first, so reordering never moves a timer to another icon.
        boolean t2 = tier >= 2;
        String ability = abilityIcon(type, tier);
        List<String> bar = new ArrayList<>();
        switch (type) {
            case STRENGTH -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "nullify"), false),
                    hudEntry(ability, cdRemain(p, "bounty"), false),
                    hudEntry(icon(type, "right"), cdRemain(p, "chad"), chadUntil.getOrDefault(id, 0L) > now)));
            case FIRE -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "fireball"), fireballChargeStart.containsKey(id)),
                    hudEntry(ability, cdRemain(p, "crisp"), false),
                    hudEntry(icon(type, "right"), remain(cozyCooldown, id), isLiveCampfire(id, now))));
            case SPEED -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "blur"), blurRuns.containsKey(id)),
                    hudEntry(ability, cdRemain(p, "thunderstep"), false),
                    ChargePools.hud(plugin, p, "storm", icon(type, "right"))));
            case WEALTH -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "itemlock"), false),
                    hudEntry(ability, cdRemain(p, "pockets"), pockets.containsKey(id)
                            && p.getOpenInventory() != null && p.getOpenInventory().getTopInventory().equals(pockets.get(id))),
                    hudEntry(icon(type, "right"), cdRemain(p, "richrush"), richRushUntil.getOrDefault(id, 0L) > now)));
            case PUFF -> {
                bar.addAll(HudStatus.gemBar(t2,
                        hudEntry(icon(type, "left"), cdRemain(p, "bash"), false),
                        hudEntry(ability, cdRemain(p, "doublejump"), false),
                        hudEntry(icon(type, "right"), cdRemain(p, "dash"), false)));
                if (t2 && cdRemain(p, "bash-hit") > 0) bar.add(hudEntry(icon(type, "left") + "⇣", cdRemain(p, "bash-hit"), false)); // the hit version (launch and slam)
            }
            case ASTRA -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "daggers"), volleys.containsKey(id)),
                    hudEntry(ability, cdRemain(p, "drift"), plugin.astral() != null && plugin.astral().unbounded(p)),
                    hudEntry(icon(type, "right"), cdRemain(p, "projection"), isProjecting(p))));
            case LIFE -> bar.addAll(HudStatus.gemBar(t2,
                    hudEntry(icon(type, "left"), cdRemain(p, "heartdrainer"), false),
                    hudEntry(ability, cdRemain(p, "vortex"), false),
                    hudEntry(icon(type, "right"), cdRemain(p, "circle"), false)));
            case FLUX -> {
                sendFluxActionbar(p, now, tier);
                return;
            }
            default -> {
                return;
            }
        }
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(String.join("  ", bar) + captureHud(p)));
    }

    /** Your time on a running capture point, at the end of the bar (empty when you're not on one). */
    private String captureHud(Player p) {
        return (plugin.captures() == null ? "" : plugin.captures().hudSuffix(p)) + (plugin.bounty() == null ? "" : plugin.bounty().hudSuffix(p))
                + (plugin.amplification() == null ? "" : plugin.amplification().hudSuffix(p));
    }

    /** One ability on the action bar: icons and timers only, no words (HudStatus). */
    private String hudEntry(String icon, long remainMs, boolean active) {
        return HudStatus.entry(icon, remainMs, active);
    }

    /** Flux: beam, Kinetic Burst (the ability), watts, overdrive at Tier 2; the burst only at Tier 1. */
    private void sendFluxActionbar(Player p, long now, int tier) {
        UUID id = p.getUniqueId();
        String charging = plugin.flux() == null ? null : plugin.flux().beamEntry(p, icon(GemType.FLUX, "left")); // the watts while charging
        String beam = charging != null ? charging : hudEntry(icon(GemType.FLUX, "left"), remain(fluxBeamCooldown, id), false);
        int charge = fluxOf(fluxCharge, id);
        long burstCooldown = remain(fluxBurstCooldown, id);
        String burstIcon = abilityIcon(GemType.FLUX, tier);
        // Kinetic Burst: a timer while it cools down, then its charge in % (dimmed) until it is full.
        String burst = burstCooldown > 0 ? hudEntry(burstIcon, burstCooldown, false)
                : (charge >= 100 ? hudEntry(burstIcon, 0, false) : "§7" + burstIcon + " §b" + charge + "%");
        String watts = "§f" + tier2Glyph() + " §3" + String.format(java.util.Locale.ROOT, "%.1f", (double) fluxOf(fluxWatts, id));
        boolean odActive = overdriveUntil.getOrDefault(id, 0L) > now;
        String overdrive = plugin.flux() == null ? hudEntry(icon(GemType.FLUX, "right"), 0, odActive)
                : plugin.flux().overdriveEntry(p, icon(GemType.FLUX, "right"), odActive); // its meter, FULL when ready

        String msg = (tier >= 2 ? beam + "  " + burst + "  " + watts + "  " + overdrive : burst) + captureHud(p);
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));
    }

    private String tier2Glyph() {
        return "\uA43A"; // pack icon: blissgems:gems/icons/tier2.png
    }

    private boolean isLiveStorm(UUID ownerId, long now) {
        SpeedStorm storm = storms.get(ownerId);
        return storm != null && storm.until > now;
    }

    private boolean isLiveCampfire(UUID ownerId, long now) {
        FireCamp camp = campfires.get(ownerId);
        return camp != null && camp.until > now;
    }

    /** Uses the real glyph code points from the user-provided Bliss Pack, unless overridden in config. */
    /**
     * HUD icon for one of a gem's three slots: "left" (left-click power), "right" (right-click
     * power) or "ability" (offhand ability). Defaults are the Bliss Pack v3.5 font glyphs: the
     * pack's own per-ability icons for the powers and the gem's crystal for its ability.
     * Override per gem under actionbar.icons.
     */
    private String icon(GemType type, String slot) {
        String custom = plugin.getConfig().getString("actionbar.icons." + type.name() + "." + slot, "");
        if (custom != null && !custom.isEmpty()) return custom;
        int index = "left".equals(slot) ? 0 : "right".equals(slot) ? 1 : 2;
        String[] icons = switch (type) {
            case STRENGTH -> new String[]{"\uA437", "\uA436", "\uA449"}; // muscle, giga, crystal
            case FIRE -> new String[]{"\uA425", "\uA426", "\uA43F"};     // fireball, campfire, crystal
            case SPEED -> new String[]{"\uA433", "\uA432", "\uA447"};    // injection, bolt, crystal
            case WEALTH -> new String[]{"\uA43B", "\uA43C", "\uA44B"};   // clover, money, crystal
            case PUFF -> new String[]{"\uA430", "\uA42F", "\uA445"};     // fist, arrow, crystal
            case ASTRA -> new String[]{"\uA41F", "\uA420", "\uA43D"};    // dagger, projection, crystal
            case LIFE -> new String[]{"\uA42A", "\uA42B", "\uA443"};     // heart, rose, crystal
            case FLUX -> new String[]{"\uA427", "\uA428", "\uA441"};     // beam, domain, crystal
            default -> new String[]{type.powerIcon, type.abilityIcon, type.abilityIcon};
        };
        return icons[index];
    }

    /** The ability's crystal for this tier: actionbar.icons.<GEM>.ability (Tier 1) or ability-tier2 (HudStatus.abilityIcon). */
    private String abilityIcon(GemType type, int tier) {
        return HudStatus.abilityIcon(type, tier, icon(type, "ability"),
                plugin.getConfig().getString("actionbar.icons." + type.name() + ".ability-tier2", ""));
    }

    private final Map<UUID, Long> chadFlashUntil = new HashMap<>(); // Chad's 4th-hit crit: the dots are red until then
    private static final org.bukkit.Color CHAD_RED = org.bukkit.Color.fromRGB(0xD01A1A), DARK_RED = org.bukkit.Color.fromRGB(0x8B0000),
            NEAR_BLACK = org.bukkit.Color.fromRGB(0x111111), NULLIFY_RED = org.bukkit.Color.fromRGB(0xC81E1E);

    /** Chad Strength's double-damage hit: the dots turn red (a little bigger) for a moment, with a red burst on the target. */
    private void chadCritFlash(Player p, Entity target) {
        chadFlashUntil.put(p.getUniqueId(), System.currentTimeMillis() + 400);
        drawChadDots(p, System.currentTimeMillis()); // now, not at the next 10-tick redraw
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) drawChadDots(p, System.currentTimeMillis()); }, 4L);
        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        target.getWorld().spawnParticle(Particle.DUST, at, 24, 0.35, 0.45, 0.35, 0, new Particle.DustOptions(CHAD_RED, 1.4f));
    }

    /** Frailer: two thin intertwined lines, dark red and near-black, from you to them for about 6 ticks (like the hunt's thread). */
    private void frailerLine(Player p, LivingEntity v) {
        for (int t = 0; t < 6; t++) {
            final long age = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !v.isValid() || !v.getWorld().equals(p.getWorld())) return;
                Vector from = p.getLocation().add(0, 1.1, 0).toVector(), to = v.getLocation().add(0, v.getHeight() * 0.55, 0).toVector();
                double d = from.distance(to);
                if (d < 0.6) return;
                BountyMath.Trail tr = BountyMath.trail(from, to, 0.4, d, 0.25, age * 5);
                World w = p.getWorld();
                for (Vector a : tr.black()) w.spawnParticle(Particle.DUST, a.getX(), a.getY(), a.getZ(), 1, 0, 0, 0, 0, new Particle.DustOptions(NEAR_BLACK, 0.6f));
                for (Vector a : tr.red()) w.spawnParticle(Particle.DUST, a.getX(), a.getY(), a.getZ(), 1, 0, 0, 0, 0, new Particle.DustOptions(DARK_RED, 0.6f));
            }, t);
        }
    }

    /** Nullify landing on someone: a red ring at their feet, widening a little and fading over about 10 ticks. */
    private void nullifyRing(Player o) {
        for (int t = 0; t < 10; t++) {
            final int age = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!o.isOnline()) return;
                Location feet = o.getLocation().add(0, 0.1, 0);
                double r = 0.6 + age * 0.09;
                float size = 1.2f - age * 0.08f;
                for (int i = 0; i < 18; i++) {
                    double a = Math.PI * 2 * i / 18;
                    o.getWorld().spawnParticle(Particle.DUST, feet.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(NULLIFY_RED, Math.max(0.3f, size)));
                }
            }, t);
        }
    }

    /** Chad dots: 3 black dots ahead of the player; each crit turns one red; the 4th-hit crit flashes them all red. */
    private void drawChadDots(Player p, long now) {
        Long until = chadUntil.get(p.getUniqueId());
        if (until == null || until < now) return;
        boolean flash = chadFlashUntil.getOrDefault(p.getUniqueId(), 0L) > now;
        int n = critCount.getOrDefault(p.getUniqueId(), 0);
        int lit = n % 3;
        if (lit == 0 && n > 0) lit = 3;
        Location eye = p.getEyeLocation();
        Vector fwd = eye.getDirection().setY(0).normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX()).normalize();
        Location base = eye.clone().add(fwd.clone().multiply(1.5));
        base.setY(eye.getY() + 0.3);
        for (int i = -1; i <= 1; i++) {
            Location dot = base.clone().add(right.clone().multiply(0.45 * i));
            boolean red = (i + 1) < lit;
            org.bukkit.Color col = flash ? CHAD_RED : red ? org.bukkit.Color.RED : org.bukkit.Color.BLACK;
            p.getWorld().spawnParticle(Particle.DUST, dot, 1,
                    new Particle.DustOptions(col, flash ? 1.3f : 1.0f));
        }
    }

    /** Holding Strength with no hunt running: the nearest player within 50 blocks, in pure red, over the cooldown bar for 3s. */
    private void proximityHint(Player p, int energy) {
        if (!EnergyStage.allowsPowers(energy)) return;
        long alertUntil = System.currentTimeMillis() + 3000L;
        Player nearest = null;
        double best = 100 * 100;
        for (Entity e : p.getNearbyEntities(100, 60, 100)) {
            if (e instanceof Player o && !o.getUniqueId().equals(p.getUniqueId())) {
                double d = p.getLocation().distanceSquared(o.getLocation());
                if (d < best) {
                    best = d;
                    nearest = o;
                }
            }
        }
        if (nearest != null && best < 50 * 50) {
            p.sendActionBar(Component.text("§cYou feel your victim nearby... (" + (int) Math.sqrt(best) + "m)"));
            nearest.sendActionBar(Component.text("§cSomeone is watching you…"));
            trackAlertUntil.put(p.getUniqueId(), alertUntil);
            trackAlertUntil.put(nearest.getUniqueId(), alertUntil);
        }
    }

    public boolean isTrusted(Player owner, Player other) {
        if (owner.getUniqueId().equals(other.getUniqueId())) return true;
        Set<UUID> s = trusted.get(owner.getUniqueId());
        return s != null && s.contains(other.getUniqueId());
    }

    public boolean addTrust(Player owner, Player other) {
        return trusted.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>()).add(other.getUniqueId());
    }

    public boolean removeTrust(Player owner, Player other) {
        Set<UUID> s = trusted.get(owner.getUniqueId());
        return s != null && s.remove(other.getUniqueId());
    }

    public Set<UUID> getTrusted(Player owner) {
        return trusted.getOrDefault(owner.getUniqueId(), Set.of());
    }

    // ---------- Bounty Hunter (the board, the offering and the hunts are in BountyHunt) ----------

    private final Map<UUID, Long> trackAlertUntil = new HashMap<>();

    /** A hunt began (BountyHunt): the Bounty Hunter cooldown starts now, with its sound. */
    void bountyBegan(Player p) {
        ItemStack bountyGem = findGem(p);
        int bountyEnergy = bountyGem == null ? 5 : gems.getEnergy(bountyGem);
        cdStart(p, "bounty", cooldownMs("bounty", bountyEnergy));
        GemSounds.playTo(plugin, "strength.bounty-hunter", p);
    }

    /** Milliseconds until Bounty Hunter can be used again. */
    long bountyCooldownLeft(Player p) { return cdRemain(p, "bounty"); }

    @EventHandler
    public void onLeave(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        restoreHearts(p); // a heart lock running out while they're away would never put them back
        puffCombos.remove(p.getUniqueId());
        if (!blurRuns.isEmpty()) endBlursOf(p.getUniqueId(), false); // no clones left behind
        trackAlertUntil.remove(p.getUniqueId());
        clearAstraDaggers(p.getUniqueId());
    }

    private boolean isCriticalHit(Player p) {
        return p.getFallDistance() > 0 && !p.isOnGround() && !p.isInWater() && p.getVehicle() == null;
    }

    // ---------- powers ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        boolean left = (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK);
        boolean right = (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK);
        if (!left && !right) return;
        Player p = e.getPlayer();
        // With the Revenant in the main hand, clicks belong to its powers, not to a gem in the other hand.
        if (plugin.revenant() != null && plugin.revenant().holdsMace(p)) return;

        // Offhand gem activation: Crisp + Bounty Hunter are offhand-hold right-click.
        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            if (!right) return;
            ItemStack off = p.getInventory().getItemInOffHand();
            if (!gems.isGem(off)) return;
            if (gems.isMythic(off)) { plugin.mythics().handleInteract(e); return; }
            handleOffhandRightClick(p, off, a, e.getClickedBlock());
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack inHand = p.getInventory().getItemInMainHand();

        // Special items first (right-click only, main hand or offhand, consumed in place)
        String kind = gems.getKind(inHand);
        ItemStack special = inHand;
        if (kind == null) {
            ItemStack offCheck = p.getInventory().getItemInOffHand();
            String offKind = gems.getKind(offCheck);
            if (offKind != null) {
                kind = offKind;
                special = offCheck;
            }
        }
        if (kind != null) {
            if (right) handleSpecial(p, special, kind);
            return;
        }

        if (gems.isMythic(inHand)) {
            plugin.mythics().handleInteract(e);
            return;
        }
        if (!gems.isGem(inHand)) {
            // Main empty (or holding a material) but gem in offhand: offhand abilities + ingot taps.
            if (right) {
                ItemStack off = p.getInventory().getItemInOffHand();
                if (gems.isGem(off)) {
                    if (gems.isMythic(off)) { plugin.mythics().handleInteract(e); return; }
                    if (gems.getType(off) == GemType.FLUX && tryIngotCollect(p, inHand)) return;
                    handleOffhandRightClick(p, off, a, e.getClickedBlock());
                } else {
                    String offKind = gems.getKind(off);
                    if (offKind != null) handleSpecial(p, off, offKind);
                }
            }
            return;
        }
        GemType type = gems.getType(inHand);
        int tier = gems.getTier(inHand);
        int energy = gems.getEnergy(inHand);
        if (type == null) return;

        if (isGemDisabled(p)) {
            p.sendMessage(ChatColor.DARK_PURPLE + "Your gem is disabled.");
            return;
        }

        if (!EnergyStage.allowsPowers(energy)) {
            if (right || (left && type == GemType.STRENGTH)) {
                // Silenced — no chat spam
                GemSounds.playTo(plugin, "general.not-ready", p);
            }
            return;
        }

        // Strength Nullify on LEFT click (T2 power)
        if (left && type == GemType.STRENGTH) {
            if (tier < 2) return;
            long rem = cdRemain(p, "nullify");
            if (rem > 0) {
                // Silenced — no chat spam
                return;
            }
            cdStart(p, "nullify", cooldownMs("nullify", energy));
            useNullify(p, energy);
            return;
        }
        if (left && type != GemType.FIRE && type != GemType.WEALTH && type != GemType.PUFF && type != GemType.ASTRA && type != GemType.LIFE && type != GemType.FLUX) return;

        // Strength routing on RIGHT click: Bounty Hunter is offhand-only (BountyHunt).
        // Chad Strength (power) works from the main hand.
        if (type == GemType.STRENGTH) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            long rem = cdRemain(p, "chad");
            if (rem > 0) {
                // Silenced — no chat spam
                return;
            }
            cdStart(p, "chad", cooldownMs("chad", energy));
            useChadStrength(p, energy);
            return;
        }

        // Fire routing: Fireball [Left-click x2] + Cozy Campfire [Right-click block] (T2 powers)
        if (type == GemType.FIRE) {
            if (left) {
                if (tier < 2) return;
                handleFireballClick(p, energy);
                return;
            }
            if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                if (tier < 2) {
                    // Silenced — no chat spam
                    return;
                }
                long rem = remain(cozyCooldown, p.getUniqueId());
                if (rem > 0) { /* Silenced */ return; }
                cozyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("cozy", energy));
                useCozyCampfire(p, e.getClickedBlock(), energy);
                return;
            }
            return;
        }

        // Wealth routing: Item Lock [Left-click] + Rich Rush [Right-click] + Amplification [Sneak + Right-click] (T2 powers).
        // Pockets (ability) is offhand-only.
        if (type == GemType.WEALTH) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = cdRemain(p, "itemlock");
                if (rem > 0) { /* Silenced */ return; }
                cdStart(p, "itemlock", cooldownMs("itemlock", energy));
                useItemLock(p, energy);
                return;
            }
            if (p.isSneaking()) { castAmplification(p, energy); return; }
            long rem = cdRemain(p, "richrush");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "richrush", cooldownMs("richrush", energy));
            useRichRush(p, energy);
            return;
        }

        // Speed routing: Speedy Storm [Right-click] (T2 power). Blur is a melee hit with the gem (see onHit);
        // Thunderstep is offhand-only.
        if (type == GemType.SPEED) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (!right) return;
            // Speedy Storm holds several charges, each recharging on its own.
            if (!ChargePools.tryUse(plugin, p, "storm")) { /* Silenced */ return; }
            useSpeedyStorm(p, energy);
            return;
        }

        // Puff routing: Breezy Bash AoE [Left-click] + Dash [Right-click] (T2 powers).
        // Double Jump (ability) is offhand-only.
        if (type == GemType.PUFF) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = cdRemain(p, "bash");
                if (rem > 0) { /* Silenced */ return; }
                cdStart(p, "bash", cooldownMs("bash", energy));
                useWindBlast(p);
                return;
            }
            if (!right) return;
            long rem2 = cdRemain(p, "dash");
            if (rem2 > 0) { /* Silenced */ return; }
            cdStart(p, "dash", cooldownMs("dash", energy));
            useDash(p, energy);
            return;
        }

        // Astra routing (T2 powers, AstraMath.route): Daggers [Left-click]; Unbounded [Right-click a player];
        // Astral Void [Right-click anywhere else]; Astral Projection [Sneak + right-click]. Drift is off-hand only.
        if (type == GemType.ASTRA) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = cdRemain(p, "daggers");
                if (rem > 0) { /* Silenced */ return; }
                handleAstraDaggers(p, energy); // the cooldown starts after the fifth, or when the rest fade
                return;
            }
            astraRight(p, targetedEntity(p, 5), energy);
            return;
        }

        // Life routing: Heart Drainer [Left-click] + Circle of Life [Right-click] (T2 powers).
        // Vitality Vortex (ability) is offhand-only.
        if (type == GemType.LIFE) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = cdRemain(p, "heartdrainer");
                if (rem > 0) { /* Silenced */ return; }
                cdStart(p, "heartdrainer", cooldownMs("heartdrainer", energy));
                useHeartDrainer(p, energy);
                return;
            }
            long rem = cdRemain(p, "circle");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "circle", cooldownMs("circle", energy)); // one cooldown for both versions
            useCircleOfLife(p, energy, targetedEntity(p, 12));
            return;
        }

        // Flux routing: Energy Beam / Ground [Left-click] + Kinetic Overdrive [Right-click, meter full] (T2 powers). Q charges (onDrop).
        // Kinetic Burst (ability) is offhand-only.
        if (type == GemType.FLUX) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) { fluxFire(p, energy); return; }
            // Kinetic Overdrive: only when its meter is full (FluxPower fills it from what happens around you); it empties
            if (plugin.flux() == null || !plugin.flux().overdriveFull(p)) { /* Silenced */ return; }
            plugin.flux().emptyOverdrive(p);
            useOverdrive(p, energy);
            return;
        }

        boolean sneak = p.isSneaking();
        if (sneak && tier < 2) {
            // Silenced — no chat spam
            return;
        }
        if (sneak && tier >= 2) {
            long rem = cdRemain(p, "ability:" + type.name());
            if (rem > 0) {
                // Silenced — no chat spam
                return;
            }
            cdStart(p, "ability:" + type.name(), cooldownMs("ability", energy));
            useTier2(p, type, energy);
            return;
        }

        long rem = cdRemain(p, "power:" + type.name());
        if (rem > 0) {
            // Silenced — no chat spam
            return;
        }
        cdStart(p, "power:" + type.name(), cooldownMs("power", energy));
        usePower(p, type, tier, energy);
    }

    /** Offhand-hold right-click abilities: Fire Crisp + Strength Bounty Hunter. */
    private void handleOffhandRightClick(Player p, ItemStack offGem, Action a, Block clicked) {
        GemType type = gems.getType(offGem);
        int tier = gems.getTier(offGem);
        int energy = gems.getEnergy(offGem);
        if (type == null) return;
        if (isGemDisabled(p)) { /* Silenced */ return; }
        if (!EnergyStage.allowsPowers(energy)) {
            // Silenced — no chat spam
            return;
        }
        if (type == GemType.FIRE) {
            long rem = cdRemain(p, "crisp");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "crisp", cooldownMs("crisp", energy));
            useCrisp(p, tier, energy);
            return;
        }
        if (type == GemType.STRENGTH) {
            long rem = cdRemain(p, "bounty");
            if (rem > 0) { /* Silenced */ return; }
            if (plugin.bounty() != null) plugin.bounty().openBoard(p); // the cooldown starts when a hunt begins
            return;
        }
        if (type == GemType.WEALTH) {
            long rem = cdRemain(p, "pockets");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "pockets", cooldownMs("pockets", energy));
            openPockets(p);
            return;
        }
        if (type == GemType.SPEED) {
            // Thunderstep has an ordinary cooldown; the charge pool belongs to Speedy Storm.
            long rem = cdRemain(p, "thunderstep");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "thunderstep", cooldownMs("thunderstep", energy));
            useThunderstep(p, energy);
            return;
        }
        if (type == GemType.PUFF) {
            long rem = cdRemain(p, "doublejump");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "doublejump", cooldownMs("doublejump", energy));
            useDoubleJump(p);
            return;
        }
        if (type == GemType.ASTRA) {
            if (isGemDisabled(p)) { /* Silenced */ return; }
            long rem = cdRemain(p, "drift");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "drift", 15_000L);
            useDimensionalDrift(p, tier, energy);
            return;
        }
        if (type == GemType.LIFE) {
            long rem = cdRemain(p, "vortex");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "vortex", cooldownMs("vortex", energy));
            useVitalityVortex(p, tier, energy);
            return;
        }
        if (type == GemType.FLUX) {
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main != null && main.getType() != Material.AIR && !gems.isGem(main)) {
                if (tryIngotCollect(p, main)) return;
            }
            long rem = remain(fluxBurstCooldown, p.getUniqueId());
            if (rem > 0) { /* Silenced */ return; }
            fluxBurstCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("burst", energy));
            useKineticBurst(p, tier, energy);
        }
    }

    /** Nullify [Left Click]: strip Strength, Speed, and Fire Resistance from untrusted opponents in radius. */
    private void useNullify(Player p, int energy) {
        int radius = 12;
        int cleared = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (isTrusted(p, o)) continue;
            nullifyRing(o);
            boolean stripped = false;
            for (PotionEffect fx : new java.util.ArrayList<>(o.getActivePotionEffects())) {
                String typeName = fx.getType().getName();
                if (typeName.equals("STRENGTH") || typeName.equals("SPEED") || typeName.equals("FIRE_RESISTANCE")) {
                    o.removePotionEffect(fx.getType());
                    stripped = true;
                }
            }
            if (stripped) {
                cleared++;
                o.sendMessage(ChatColor.RED + "Nullified: Strength, Speed, and Fire Resistance stripped.");
            }
        }
        GemSounds.play(plugin, "strength.nullify", p.getLocation());
        usedGroup(p, GemNames.tx("Nullify"), cleared);
    }

    /** Chad Strength [Right Click]: self + trusted in radius deal double damage on every 4th hit. */
    private void useChadStrength(Player p, int energy) {
        int radius = 15;
        long dur = 30_000L;
        long until = System.currentTimeMillis() + dur;
        chadUntil.put(p.getUniqueId(), until);
        hitCount.put(p.getUniqueId(), 0);
        int allies = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (!isTrusted(p, o)) continue;
            chadUntil.put(o.getUniqueId(), until);
            hitCount.put(o.getUniqueId(), 0);
            o.sendMessage(ChatColor.RED + GemNames.tx("Chad Strength: your 4th hit deals double damage!"));
            allies++;
        }
        GemSounds.play(plugin, "strength.chad-strength", p.getLocation());
        usedGroup(p, GemNames.tx("Chad Strength"), allies + 1);
    }

    // ================= FIRE (user kit) =================

    /** Fireball [Left Click]: first click starts charge, second click unleashes. No self damage. */
    private void handleFireballClick(Player p, int energy) {
        UUID id = p.getUniqueId();
        Long started = fireballChargeStart.get(id);
        if (started == null) {
            if (cdRemain(p, "fireball") > 0) return; // still cooling down: cannot start a new charge
            fireballChargeStart.put(id, System.currentTimeMillis());
            p.sendMessage(ChatColor.GOLD + GemNames.tx("Fireball charging... left-click again to unleash."));
            GemSounds.play(plugin, "fire.fireball-charge", p.getLocation());
            startFireballCharge(p);
            return;
        }
        fireballChargeStart.remove(id);
        hideFireballBar(p);
        double level = fireballCharge.getOrDefault(id, 0.0);
        fireballCharge.remove(id);
        if (level < 0.15) {
            p.sendMessage(ChatColor.GRAY + GemNames.tx("Fireball fizzled (too quick). Hold the charge a little longer."));
            return;
        }
        var fb = p.launchProjectile(org.bukkit.entity.SmallFireball.class);
        fb.setYield((float) (1.0 + level * 2.0));
        fb.setIsIncendiary(true);
        fireballOwner.put(fb.getUniqueId(), id);
        if (plugin.getConfig().getBoolean("fire.fireball.explode", true)) {
            double min = Math.max(0.0, plugin.getConfig().getDouble("fire.fireball.explosion-power-min", 1.0));
            double max = Math.max(min, Math.min(8.0, plugin.getConfig().getDouble("fire.fireball.explosion-power-max", 3.0)));
            fireballPower.put(fb.getUniqueId(), (float) (min + (max - min) * level));
        }
        if (plugin.getConfig().getBoolean("fire.fireball.model.enabled", true)) fireballModel(fb, level);
        GemSounds.play(plugin, "fire.fireball-launch", p.getLocation());
        used(p, GemNames.tx("Fireball"));
        cdStart(p, "fireball", cooldownMs("fireball", energy));
    }

    /**
     * The fireball's look (fire.fireball.model): a fire charge sized by the charge it was fired with, the same size
     * for its whole flight. The vanilla small fireball is hidden under it so there aren't two, and a little smoke
     * follows it (the vanilla trail is drawn by the client only for a fireball it can see). Visual only: the fireball
     * itself, its damage, speed, range, explosion and cooldown are untouched.
     */
    private void fireballModel(org.bukkit.entity.SmallFireball fb, double level) {
        double min = plugin.getConfig().getDouble("fire.fireball.model.scale-min", 0.75);
        double max = Math.max(min, plugin.getConfig().getDouble("fire.fireball.model.scale-max", 1.5));
        float scale = (float) FireballLook.scale(level, min, max);
        fb.setVisibleByDefault(false);
        ItemDisplay model = fb.getWorld().spawn(fb.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.FIRE_CHARGE));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setTeleportDuration(1);
            d.setPersistent(false);
        });
        new org.bukkit.scheduler.BukkitRunnable() {
            private int age;
            @Override public void run() {
                if (!fb.isValid() || !model.isValid() || ++age > 20 * 60) { // hit, gone, or a minute out
                    if (model.isValid()) model.remove();
                    cancel();
                    return;
                }
                model.teleport(fb.getLocation());
                fb.getWorld().spawnParticle(Particle.SMOKE, fb.getLocation(), 1, 0.05, 0.05, 0.05, 0.0);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Charging: every 2 ticks the charge grows (FireballCharge): a full charge in fire.fireball.charge-seconds, twice
     * as fast while you stand on or in a fire block. Stepping off slows it again from where it is. While boosted, the
     * boss bar (if on) says " x2" in a brighter orange and a few flames rise from your feet.
     */
    private void startFireballCharge(Player p) {
        UUID id = p.getUniqueId();
        Long token = fireballChargeStart.get(id);
        hideFireballBar(p);
        fireballCharge.put(id, 0.0);
        net.kyori.adventure.bossbar.BossBar bar = null;
        if (plugin.getConfig().getBoolean("fire.fireball.charge-bossbar", true)) {
            bar = net.kyori.adventure.bossbar.BossBar.bossBar(Component.empty(), 0f,
                    net.kyori.adventure.bossbar.BossBar.Color.YELLOW, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            fireballBars.put(id, bar);
            p.showBossBar(bar);
        }
        final net.kyori.adventure.bossbar.BossBar shown = bar;
        new org.bukkit.scheduler.BukkitRunnable() {
            private int runs;
            @Override public void run() {
                if (token == null || !token.equals(fireballChargeStart.get(id)) || !p.isOnline()) { // released, or a new charge
                    if (shown != null) { p.hideBossBar(shown); fireballBars.remove(id, shown); }
                    cancel();
                    return;
                }
                boolean fire = isOnFireThemedBlock(p);
                double level = FireballCharge.step(fireballCharge.getOrDefault(id, 0.0), 0.1, fire,
                        plugin.getConfig().getDouble("fire.fireball.charge-seconds", 20.0),
                        plugin.getConfig().getDouble("fire.fireball.charge-seconds-on-fire", 10.0));
                fireballCharge.put(id, level);
                if (++runs % 15 == 0) GemSounds.play(plugin, "fire.fireball-charge", p.getLocation()); // the burn keeps going while charging
                if (fire && level < 1.0) p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.1, 0), 3, 0.25, 0.05, 0.25, 0.02);
                if (shown != null) fireballBarShow(shown, level, fire);
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private static final net.kyori.adventure.text.format.TextColor BOOSTED = net.kyori.adventure.text.format.TextColor.color(0xFFA62B);

    private void fireballBarShow(net.kyori.adventure.bossbar.BossBar bar, double level, boolean boosted) {
        int percent = (int) Math.round(level * 100);
        String x2 = boosted ? " x2" : "";
        bar.progress((float) Math.max(0.0, Math.min(1.0, level)));
        if (level < 0.15) {
            bar.color(net.kyori.adventure.bossbar.BossBar.Color.WHITE);
            bar.name(Component.text(GemNames.tx("Fireball charging ") + percent + "% (too weak to fire)" + x2,
                    boosted ? BOOSTED : net.kyori.adventure.text.format.NamedTextColor.GRAY));
        } else if (level >= 1.0) {
            bar.color(net.kyori.adventure.bossbar.BossBar.Color.RED);
            bar.name(Component.text(GemNames.tx("Fireball FULLY CHARGED - left-click to unleash"),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        } else {
            bar.color(net.kyori.adventure.bossbar.BossBar.Color.YELLOW); // boss bars have no orange: the text is
            bar.name(Component.text(GemNames.tx("Fireball charging ") + percent + "%" + x2,
                    boosted ? BOOSTED : net.kyori.adventure.text.format.NamedTextColor.GOLD));
        }
    }

    private void hideFireballBar(Player p) {
        var bar = fireballBars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    /** A charged Fireball explodes where it lands; power was fixed at launch from the charge level. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireballImpact(org.bukkit.event.entity.ProjectileHitEvent e) {
        UUID projectileId = e.getEntity().getUniqueId();
        Float power = fireballPower.remove(projectileId);
        UUID owner = fireballOwner.get(projectileId);
        // Forget the projectile once this tick's direct-hit damage has been handled.
        if (owner != null) Bukkit.getScheduler().runTask(plugin, () -> fireballOwner.remove(projectileId));
        if (power == null || power <= 0f) return;
        boolean setFire = plugin.getConfig().getBoolean("fire.fireball.set-fire", true);
        boolean breakBlocks = plugin.getConfig().getBoolean("fire.fireball.break-blocks", true);
        Location center = e.getEntity().getLocation();
        // Damage first, while cover is still standing, then the explosion for blocks, fire, knockback and effects.
        fireballBlastDamage(e.getEntity(), center, power, owner);
        fireballBlasting = true;
        try {
            center.getWorld().createExplosion(center, power, setFire, breakBlocks, e.getEntity());
        } finally {
            fireballBlasting = false;
        }
    }

    /**
     * Blast damage for a Fireball, using the vanilla explosion curve but dealt by the plugin, because
     * vanilla explosion damage to players scales with difficulty (halved on Easy, zero on Peaceful).
     * The owner is never hurt, matching the Fireball's no-self-damage rule.
     */
    private void fireballBlastDamage(Entity fireball, Location center, float power, UUID owner) {
        double multiplier = plugin.getConfig().getDouble("fire.fireball.damage-multiplier", 1.0);
        if (multiplier <= 0) return;
        double reach = power * 2.0;
        Player caster = owner == null ? null : Bukkit.getPlayer(owner);
        var source = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MOB_PROJECTILE)
                .withDirectEntity(fireball).withDamageLocation(center);
        if (caster != null) source.withCausingEntity(caster);
        var built = source.build();
        for (Entity near : center.getWorld().getNearbyEntities(center, reach, reach, reach)) {
            if (!(near instanceof LivingEntity victim) || near instanceof org.bukkit.entity.ArmorStand || near.isDead()) continue;
            if (owner != null && owner.equals(near.getUniqueId())) continue;
            double distance = victim.getLocation().add(0, victim.getHeight() / 2, 0).distance(center);
            if (distance >= reach) continue;
            double impact = (1.0 - distance / reach) * fireballExposure(center, victim);
            if (impact <= 0) continue;
            victim.damage(((impact * impact + impact) / 2.0 * 7.0 * reach + 1.0) * multiplier, built);
        }
    }

    /** Share of the victim (feet, middle, head) with a clear line to the blast: 0, 1/3, 2/3 or 1. */
    private double fireballExposure(Location center, LivingEntity victim) {
        if (center.getBlock().getType().isSolid()) return 1.0; // blast point is inside a block: cannot judge cover
        double height = victim.getHeight();
        int clear = 0;
        for (double y : new double[]{0.1, height / 2, Math.max(0.1, height - 0.1)}) {
            Vector to = victim.getLocation().add(0, y, 0).toVector().subtract(center.toVector());
            double length = to.length();
            if (length < 1.0e-3 || center.getWorld().rayTraceBlocks(center, to.multiply(1 / length), length,
                    org.bukkit.FluidCollisionMode.NEVER, true) == null) clear++;
        }
        return clear / 3.0;
    }

    /** The explosion itself only moves things; living entities take fireballBlastDamage instead (never twice). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireballBlast(EntityDamageEvent e) {
        if (!fireballBlasting || !(e.getEntity() instanceof LivingEntity)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) e.setCancelled(true);
    }

    @EventHandler
    public void onFireballChargeQuit(PlayerQuitEvent e) {
        fireballChargeStart.remove(e.getPlayer().getUniqueId());
        fireballCharge.remove(e.getPlayer().getUniqueId());
        hideFireballBar(e.getPlayer());
    }

    @EventHandler
    public void onFireballChargeDeath(PlayerDeathEvent e) {
        fireballChargeStart.remove(e.getEntity().getUniqueId());
        fireballCharge.remove(e.getEntity().getUniqueId());
        hideFireballBar(e.getEntity());
    }

    /** fire.fireball.fire-blocks: what you stand on or in to charge twice as fast (Crisp's blocks count). */
    private boolean isOnFireThemedBlock(Material m) {
        return FireballCharge.fireBlock(m.name(), plugin.getConfig().getStringList("fire.fireball.fire-blocks"));
    }

    private boolean isOnFireThemedBlock(Player p) {
        Block under = p.getLocation().getBlock().getRelative(0, -1, 0);
        Block at = p.getLocation().getBlock();
        return isOnFireThemedBlock(under.getType()) || isOnFireThemedBlock(at.getType());
    }

    /**
     * Crisp [offhand right-click]: the land catches fire for a while (CrispLand: Nether patches, fire, rare lava pits,
     * water steamed away, all cooling back exactly as it was), untrusted players and mobs are hurt and set alight, and
     * drops are smelted (both as before, in the old 6 / 8 block radius).
     */
    private void useCrisp(Player p, int tier, int energy) {
        int radius = (tier >= 2) ? 8 : 6;
        Location c = p.getLocation();
        World w = p.getWorld();
        if (plugin.crisp() != null) plugin.crisp().cast(p);
        // damage untrusted in area
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && !o.getUniqueId().equals(p.getUniqueId()) && !isTrusted(p, o)) {
                abilityDamage(o, 4.0, p);
                o.setFireTicks(100);
            }
            if (e instanceof LivingEntity le && !(e instanceof Player)) {
                abilityDamage(le, 4.0, p);
                le.setFireTicks(100);
            }
        }
        // smelt drops in area (preserve stack size — avoids the stack-merge bug)
        for (Entity e : p.getNearbyEntities(radius, 4, radius)) {
            if (e instanceof org.bukkit.entity.Item item) {
                ItemStack s = item.getItemStack();
                Material smelted = smeltResult(s.getType());
                if (smelted != null) {
                    item.setItemStack(new ItemStack(smelted, s.getAmount()));
                }
            }
        }
        GemSounds.play(plugin, "fire.crisp", c);
        w.spawnParticle(Particle.FLAME, c, 80, radius / 2.0, 2, radius / 2.0, 0.1);
        used(p, GemNames.tx("Crisp"));
    }

    private Material smeltResult(Material m) {
        return switch (m) {
            case RAW_IRON -> Material.IRON_INGOT;
            case RAW_GOLD -> Material.GOLD_INGOT;
            case RAW_COPPER -> Material.COPPER_INGOT;
            case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
            case BEEF -> Material.COOKED_BEEF;
            case PORKCHOP -> Material.COOKED_PORKCHOP;
            case CHICKEN -> Material.COOKED_CHICKEN;
            case COD -> Material.COOKED_COD;
            case SALMON -> Material.COOKED_SALMON;
            case RABBIT -> Material.COOKED_RABBIT;
            case MUTTON -> Material.COOKED_MUTTON;
            case POTATO -> Material.BAKED_POTATO;
            case KELP -> Material.DRIED_KELP;
            case COBBLESTONE -> Material.STONE;
            case SAND -> Material.GLASS;
            case CLAY_BALL -> Material.BRICK;
            default -> null;
        };
    }

    /** Cozy Campfire [Right-click block]: campfire with particle ring; burns others, regen self. */
    private void useCozyCampfire(Player p, Block clicked, int energy) {
        Block place = clicked.getRelative(0, 1, 0);
        if (!place.getType().isAir() && place.getType() != Material.FIRE) {
            place = clicked;
        }
        if (!place.getType().isAir() && place.getType() != Material.FIRE) {
            p.sendMessage(ChatColor.GRAY + "No room for a campfire here.");
            return;
        }
        place.setType(Material.CAMPFIRE, false);
        if (place.getBlockData() instanceof org.bukkit.block.data.type.Campfire camp) {
            camp.setLit(true);
            place.setBlockData(camp, false);
        }
        int radius = 6;
        campfires.put(p.getUniqueId(), new FireCamp(place.getLocation(), System.currentTimeMillis() + 60_000L, radius));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
        GemSounds.play(plugin, "fire.cozy-campfire", p.getLocation());
        used(p, GemNames.tx("Cozy Campfire"));
    }

    /** Per-tick fire visuals: meteors, campfires, (chad dots + tracking line handled in tick). */
    private void fireTick(long now) {
        // Meteor Shower: fireballs rain on the marked victim for 30s
        for (var entry : new java.util.HashMap<>(meteorUntil).entrySet()) {
            UUID victimId = entry.getKey();
            if (entry.getValue() < now) { meteorUntil.remove(victimId); meteorLastShot.remove(victimId); continue; }
            Player v = Bukkit.getPlayer(victimId);
            if (v == null || !v.isOnline()) continue;
            Long last = meteorLastShot.get(victimId);
            if (last != null && now - last < 2000) continue;
            meteorLastShot.put(victimId, now);
            Location above = v.getLocation().clone().add((Math.random() - 0.5) * 8, 6 + Math.random() * 3, (Math.random() - 0.5) * 8);
            var fb = v.getWorld().spawn(above, org.bukkit.entity.SmallFireball.class);
            Vector dir = v.getLocation().toVector().subtract(above.toVector()).normalize().multiply(0.8);
            fb.setVelocity(dir);
            fb.setIsIncendiary(true);
            fb.setYield(1.5f);
        }
        // Cozy campfires: particle ring + burn untrusted + regen owner/trusted
        for (var entry : new java.util.HashMap<>(campfires).entrySet()) {
            UUID ownerId = entry.getKey();
            FireCamp camp = entry.getValue();
            if (camp.until < now) {
                campfires.remove(ownerId);
                if (camp.loc.getBlock().getType() == Material.CAMPFIRE) camp.loc.getBlock().setType(Material.AIR, false);
                continue;
            }
            Player owner = Bukkit.getPlayer(ownerId);
            World w = camp.loc.getWorld();
            if (w == null) continue;
            // ring of particles shows radius
            for (int i = 0; i < 24; i++) {
                double ang = (Math.PI * 2 * i) / 24;
                Location ring = camp.loc.clone().add(Math.cos(ang) * camp.radius, 0.5, Math.sin(ang) * camp.radius);
                w.spawnParticle(Particle.FLAME, ring, 1, 0, 0, 0, 0);
            }
            for (Entity e : w.getNearbyEntities(camp.loc, camp.radius, 4, camp.radius)) {
                if (e instanceof Player o) {
                    if (owner != null && (o.getUniqueId().equals(ownerId) || isTrusted(owner, o))) {
                        o.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, false));
                    } else {
                        o.setFireTicks(Math.max(o.getFireTicks(), 60));
                    }
                }
            }
            if (owner != null && owner.isOnline() && owner.getWorld().equals(w)
                    && owner.getLocation().distanceSquared(camp.loc) < camp.radius * camp.radius) {
                owner.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, true, false, false));
            }
        }
    }

    // ---------- Astra Daggers (T2): no daggers until you use them ----------

    /** One volley: the daggers still waiting behind you (left to right; null once thrown) and when you last threw. */
    private static final class Volley {
        final ItemDisplay[] waiting = new ItemDisplay[AstraMath.DAGGERS];
        int thrown;
        long lastAt;
        float yaw;
    }

    private static final float FAN_SCALE = 0.5f;
    private long daggerTicks;

    /**
     * Every tick: a gem swap, a projection or daggers.linger-seconds without a throw ends the volley (the cooldown
     * starts as if all 5 were thrown). Every 2 ticks the waiting daggers glide after you (each move smoothed by the
     * client over 2 ticks) and turn with you (an interpolated pose), instead of snapping every tick.
     */
    void tickAstraDaggers() { // its own every-tick task (SoulboundGemsPlugin): the gem tick only runs every 10 ticks
        if (volleys.isEmpty()) return;
        long now = System.currentTimeMillis();
        long linger = (long) (plugin.getConfig().getDouble("astra.daggers.linger-seconds", 8.0) * 1000);
        boolean move = (++daggerTicks & 1) == 0; // every 2 ticks of this task, whatever the server's tick count
        for (UUID id : new ArrayList<>(volleys.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Volley v = volleys.get(id);
            if (p == null || v == null) { endVolley(id, false); continue; }
            ItemStack gem = p.getInventory().getItemInMainHand();
            boolean holding = gems.isGem(gem) && gems.getType(gem) == GemType.ASTRA && gems.getTier(gem) >= 2;
            if (!holding || p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) { endVolley(id, true); continue; }
            if (AstraMath.lingered(v.lastAt, now, linger)) { endVolley(id, true); continue; }
            if (move) follow(p, v, false);
        }
    }

    /** The fan of 5 appears behind your shoulders, tips forward, growing in over a few ticks. */
    private Volley summonVolley(Player p) {
        Volley v = new Volley();
        v.lastAt = System.currentTimeMillis();
        v.yaw = p.getLocation().getYaw();
        Location anchor = unturned(p.getLocation());
        for (int i = 0; i < AstraMath.DAGGERS; i++) {
            final int slot = i;
            v.waiting[i] = p.getWorld().spawn(anchor, ItemDisplay.class, d -> {
                d.setItemStack(createAstraDaggerItem());
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
                d.setTransformation(fanPose(slot, v.yaw, 0f)); // nothing yet: it grows in (the fade-in)
            });
        }
        volleys.put(p.getUniqueId(), v);
        Bukkit.getScheduler().runTaskLater(plugin, () -> follow(p, v, true), 1L);
        GemSounds.play(plugin, "astra.daggers-summon", p.getLocation());
        return v;
    }

    /** Dagger {@code i} of the fan at this yaw: its spot behind you (AstraMath.fanSlot), tip pointing the way you face. */
    static Transformation fanPose(int i, float yaw, float scale) {
        Vector off = AstraMath.turned(AstraMath.fanSlot(i), yaw);
        double r = Math.toRadians(yaw);
        Transformation pose = daggerPose(new Vector(-Math.sin(r), 0, Math.cos(r)), scale);
        Vector3f t = new Vector3f((float) off.getX(), (float) off.getY(), (float) off.getZ()).add(pose.getTranslation());
        return new Transformation(t, pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
    }

    /** The waiting daggers move with you (a 2-tick client-side glide) and turn with you (an interpolated pose). */
    private void follow(Player p, Volley v, boolean grow) {
        if (volleys.get(p.getUniqueId()) != v || !p.isOnline()) return;
        Location anchor = unturned(p.getLocation());
        float yaw = p.getLocation().getYaw();
        float turn = (yaw - v.yaw) % 360f;
        if (turn > 180) turn -= 360;
        if (turn < -180) turn += 360;
        boolean pose = grow || Math.abs(turn) > 1.5f;
        for (int i = 0; i < v.waiting.length; i++) {
            ItemDisplay d = v.waiting[i];
            if (d == null || !d.isValid()) continue;
            if (!d.getWorld().equals(p.getWorld())) { d.remove(); v.waiting[i] = null; continue; }
            d.teleport(anchor);
            if (pose) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(grow ? 4 : 3);
                d.setTransformation(fanPose(i, yaw, FAN_SCALE));
            }
        }
        if (pose) v.yaw = yaw;
    }

    /** The volley is over: the daggers still waiting fade out (or just go), and the cooldown starts as if all 5 were thrown. */
    private void endVolley(UUID id, boolean fade) {
        Volley v = volleys.remove(id);
        if (v == null) return;
        for (ItemDisplay d : v.waiting) {
            if (d == null || !d.isValid()) continue;
            if (!fade) { d.remove(); continue; }
            Transformation t = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(5);
            d.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(), new Vector3f(), t.getRightRotation()));
            flyingDaggers.add(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.isValid()) d.remove(); flyingDaggers.remove(d); }, 6L);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null && cdRemain(p, "daggers") <= 0) {
            ItemStack gem = findGem(p);
            cdStart(p, "daggers", cooldownMs("daggers", gem != null ? gems.getEnergy(gem) : 10));
        }
    }

    private ItemStack createAstraDaggerItem() {
        ItemStack item = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(ASTRA_DAGGER_CMD);
        item.setItemMeta(meta);
        return item;
    }

    private void clearAstraDaggers(UUID id) {
        endVolley(id, false);
        astraDaggerFireCooldown.remove(id);
    }

    /** Throws the next waiting dagger from behind you, tip first, at where you're looking. False if none was thrown. */
    public boolean fireAstraDagger(Player p) {
        Volley v = volleys.get(p.getUniqueId());
        if (v == null) return false;
        Long spacing = astraDaggerFireCooldown.get(p.getUniqueId());
        if (spacing != null && spacing > System.currentTimeMillis()) return false; // one click can arrive as two events
        int index = -1;
        for (int i = 0; i < v.waiting.length; i++) if (v.waiting[i] != null && v.waiting[i].isValid()) { index = i; break; }
        if (index < 0) return false;
        ItemDisplay dagger = v.waiting[index];
        v.waiting[index] = null;
        World w = p.getWorld();
        Location launchLoc = unturned(p.getLocation()).add(AstraMath.turned(AstraMath.fanSlot(index), p.getLocation().getYaw()));
        dagger.remove();
        // Aim at what's under the crosshair (it starts behind you, so a throw parallel to your look would miss it)
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        var hit = w.rayTrace(eye, look, 64, org.bukkit.FluidCollisionMode.NEVER, true, 0.2,
                en -> en instanceof LivingEntity && !en.getUniqueId().equals(p.getUniqueId()));
        Vector aim = hit != null ? hit.getHitPosition() : eye.toVector().add(look.clone().multiply(64));
        Vector dir = aim.subtract(launchLoc.toVector());
        if (dir.lengthSquared() < 1e-6) dir = look.clone();
        dir.normalize();
        // A spectral arrow does the flying and the hit, hidden from everyone; the pack's dagger model flies in its place.
        org.bukkit.entity.SpectralArrow arrow = w.spawn(launchLoc, org.bukkit.entity.SpectralArrow.class, a -> {
            a.setVisibleByDefault(false);
            a.setShooter(p); // the hit handler needs the shooter to disable their gem
            a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            a.setDamage(1.0); // the hit itself is replaced by astra.dagger-hit-damage (onHit)
            a.setCritical(true);
        });
        double speed = plugin.getConfig().getDouble("astra.dagger-speed", 3.0);
        arrow.setVelocity(dir.multiply(speed));
        daggerShots.add(arrow.getUniqueId());
        flyDaggerModel(arrow);
        v.thrown++;
        v.lastAt = System.currentTimeMillis();
        GemSounds.play(plugin, "astra.daggers", launchLoc);
        w.spawnParticle(Particle.PORTAL, launchLoc, 10, 0.2, 0.2, 0.2, 0.1);
        astraDaggerFireCooldown.put(p.getUniqueId(), System.currentTimeMillis() + 300L);
        return true;
    }

    /** The pack's Astra dagger, tip first, following its (hidden) arrow; it sticks in a wall for a second, then goes. */
    private void flyDaggerModel(org.bukkit.entity.AbstractArrow arrow) {
        float scale = 0.8f;
        ItemDisplay model = arrow.getWorld().spawn(unturned(arrow.getLocation()), ItemDisplay.class, d -> {
            d.setItemStack(createAstraDaggerItem());
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setTransformation(daggerPose(arrow.getVelocity(), scale));
        });
        flyingDaggers.add(model);
        new org.bukkit.scheduler.BukkitRunnable() {
            int age, stuck;
            @Override public void run() {
                if (!model.isValid() || !arrow.isValid() || ++age > 200) { finish(); return; }
                if (arrow.isInBlock()) { if (++stuck > 20) finish(); return; } // stays stuck where it hit, then goes
                model.teleport(unturned(arrow.getLocation()));
                Vector v = arrow.getVelocity();
                if (v.lengthSquared() > 1.0e-4) { model.setInterpolationDelay(0); model.setTransformation(daggerPose(v, scale)); }
            }
            private void finish() {
                if (model.isValid()) model.remove();
                flyingDaggers.remove(model);
                if (arrow.isValid() && arrow.isInBlock()) arrow.remove();
                cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Turns the Astra dagger model (blade along +Y, tip at the top, flat face toward +Z) so the tip points along
     * {@code v} with the flat face sideways, then centres the model on the display.
     */
    static Transformation daggerPose(Vector v, float scale) {
        Vector3f tip = new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ());
        if (tip.lengthSquared() < 1.0e-6f) tip.set(0, 1, 0);
        tip.normalize();
        Vector3f side = new Vector3f(-tip.z, 0, tip.x);
        if (side.lengthSquared() < 1.0e-6f) side.set(1, 0, 0); else side.normalize();
        Vector3f x = new Vector3f(tip).cross(side);
        Quaternionf rot = new Quaternionf().setFromNormalized(new Matrix3f(x, tip, side));
        // The model's middle sits at (8.5, 5.5, 6.5) px, not the item's centre (8, 8, 8): move it back onto the display.
        Vector3f centre = new Vector3f(-0.5f / 16, 2.5f / 16, 1.5f / 16).mul(scale).rotate(rot);
        return new Transformation(centre, rot, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Location unturned(Location at) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    /** Plugin disable: the waiting, fading and flying dagger models go (they're never saved, but a reload would leave them). */
    /** A heart lock ends now (logout, plugin disable): their own max health back. */
    private void restoreHearts(Player p) {
        Double orig = heartOrig.remove(p.getUniqueId());
        heartUntil.remove(p.getUniqueId());
        if (orig != null) setMaxHealth(p, orig);
    }

    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) restoreHearts(p);
        blurRuns.clear();
        if (blurClones != null) blurClones.removeAll(); // every Blur clone is gone
        for (UUID id : new ArrayList<>(volleys.keySet())) clearAstraDaggers(id);
        for (ItemDisplay d : flyingDaggers) if (d.isValid()) d.remove();
        flyingDaggers.clear();
    }

    // ================= WEALTH (user kit, abilities offhand-only) =================

    private void openPockets(Player p) {
        org.bukkit.inventory.Inventory inv = pockets.get(p.getUniqueId());
        if (inv == null) {
            inv = Bukkit.createInventory(null, 9, GemNames.tx("Pockets"));
            pockets.put(p.getUniqueId(), inv);
        }
        p.openInventory(inv);
        GemSounds.play(plugin, "wealth.pockets", p.getLocation());
        used(p, GemNames.tx("Pockets"));
    }

    /** Item Lock [Left Click]: lock held consumables of nearby untrusted. */
    private void useItemLock(Player p, int energy) {
        int radius = 10;
        int locked = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (isTrusted(p, o)) continue;
            ItemStack held = o.getInventory().getItemInMainHand();
            ItemStack off = o.getInventory().getItemInOffHand();
            if (isLockableConsumable(held) || isLockableConsumable(off)) {
                itemLockUntil.put(o.getUniqueId(), System.currentTimeMillis() + 10_000L);
                o.sendMessage(ChatColor.RED + "Item Locked: consumables blocked for 10s.");
                locked++;
            }
        }
        GemSounds.play(plugin, "wealth.item-lock", p.getLocation());
        usedGroup(p, GemNames.tx("Item Lock"), locked);
    }

    private boolean isLockableConsumable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        Material m = item.getType();
        if (m.isEdible()) return true;
        String n = m.name();
        return n.contains("POTION") || n.equals("MILK_BUCKET") || n.equals("HONEY_BOTTLE")
                || n.equals("CHORUS_FRUIT") || n.equals("GOLDEN_APPLE") || n.equals("ENCHANTED_GOLDEN_APPLE");
    }

    /** Rich Rush [Right Click]: double ores/mob drops for 8 minutes + Haste. */
    private void useRichRush(Player p, int energy) {
        richRushUntil.put(p.getUniqueId(), System.currentTimeMillis() + 8 * 60_000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 8 * 60 * 20, 0, true, false, false));
        GemSounds.play(plugin, "wealth.rich-rush", p.getLocation());
        used(p, GemNames.tx("Rich Rush"));
    }

    /** Amplification [Sneak + Right-click]: +1 to every enchantment for you and trusted players nearby (Amplification). */
    private void castAmplification(Player p, int energy) {
        long rem = remain(amplifyCooldown, p.getUniqueId());
        if (rem > 0) { p.sendMessage(ChatColor.RED + GemNames.tx("Amplification on cooldown: ") + (rem / 1000) + "s"); return; }
        if (plugin.amplification() == null) return;
        amplifyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("amp", energy));
        plugin.amplification().cast(p);
        used(p, GemNames.tx("Amplification"));
    }

    private Player targetedEntity(Player p, double maxDist) {
        Vector dir = p.getEyeLocation().getDirection();
        Player best = null;
        double bestD = maxDist;
        for (Entity e : p.getNearbyEntities(maxDist, maxDist, maxDist)) {
            if (!(e instanceof Player o) || o.getUniqueId().equals(p.getUniqueId())) continue;
            Vector to = o.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
            double d = to.length();
            if (d > maxDist) continue;
            double dot = to.normalize().dot(dir);
            if (dot > 0.9 && d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    private boolean isOre(Material m) {
        String n = m.name();
        return n.contains("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    // ================= SPEED (user kit, Thunderstep offhand-only) =================

    /** Thunderstep [Prog 4+]: teleport a few blocks in the direction of motion. Hit reduces cooldown. */
    private void useThunderstep(Player p, int energy) {
        Vector dir = p.getLocation().getDirection();
        // Teleport a few blocks forward in the direction of motion
        Location target = p.getLocation().add(dir.multiply(3));
        // Ensure we don't teleport into a wall
        if (!target.getBlock().getType().isAir()) {
            target = p.getLocation().add(dir.multiply(1.5));
        }
        p.teleport(target);
        p.setFallDistance(0);
        GemSounds.play(plugin, "speed.thunderstep", p.getLocation());
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 40, 0.5, 1, 0.5, 0.2);
        used(p, GemNames.tx("Thunderstep"));
    }

    private Player aimPlayerWithin(Player p, double maxDist) {
        Vector dir = p.getEyeLocation().getDirection();
        Player best = null;
        double bestD = maxDist;
        for (Entity e : p.getNearbyEntities(maxDist, maxDist, maxDist)) {
            if (!(e instanceof Player o) || o.getUniqueId().equals(p.getUniqueId())) continue;
            Vector to = o.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
            double d = to.length();
            if (d > maxDist) continue;
            if (to.normalize().dot(dir) > 0.95 && d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    /** Speedy Storm [Right Click]: summon up to 3 cloud projectiles that create AoE zones. */
    private void useSpeedyStorm(Player p, int energy) {
        storms.put(p.getUniqueId(), new SpeedStorm(p.getLocation().clone(), System.currentTimeMillis() + 30_000L, 8));
        stormLastStrike.put(p.getUniqueId(), 0L);
        // Store cloud count in a field so subsequent clicks can summon more
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 600, 1, true, false, false));
        GemSounds.play(plugin, "speed.speedy-storm", p.getLocation());
        p.sendMessage(ChatColor.AQUA + GemNames.tx("Speedy Storm unleashed. Charges left: ")
                + ChargePools.available(plugin, p, "storm") + "/" + ChargePools.maxCharges(plugin, "storm") + ".");
        used(p, GemNames.tx("Speedy Storm"));
    }

    /**
     * Speedy Storm's bolt, drawn in particles: a vanilla lightning bolt (even a visual one) makes the client play
     * world-wide thunder, which can't be turned down. A jagged white-blue line from a cloud puff down to a spark burst.
     */
    private void particleBolt(Location ground) {
        World w = ground.getWorld();
        if (w == null) return;
        double height = 9 + Math.random() * 3;
        Location top = ground.clone().add(0, height, 0);
        w.spawnParticle(Particle.CLOUD, top, 14, 0.9, 0.25, 0.9, 0.01);
        Particle.DustOptions core = new Particle.DustOptions(Color.fromRGB(235, 246, 255), 1.6f);
        Particle.DustOptions glow = new Particle.DustOptions(Color.fromRGB(140, 205, 255), 1.1f);
        Location a = top.clone();
        int segments = 7;
        for (int i = 1; i <= segments; i++) {
            double f = i / (double) segments;
            Location b = i == segments ? ground.clone().add(0, 0.1, 0)
                    : ground.clone().add((Math.random() - 0.5) * 1.6 * (1 - f), height * (1 - f), (Math.random() - 0.5) * 1.6 * (1 - f));
            Vector step = b.toVector().subtract(a.toVector());
            int dots = Math.max(2, (int) (step.length() / 0.22));
            for (int d = 0; d <= dots; d++) {
                Location at = a.clone().add(step.clone().multiply(d / (double) dots));
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, core);
                if (d % 2 == 0) w.spawnParticle(Particle.DUST, at, 1, 0.08, 0.08, 0.08, 0, glow);
            }
            a = b;
        }
        w.spawnParticle(Particle.ELECTRIC_SPARK, ground.clone().add(0, 0.3, 0), 30, 0.4, 0.2, 0.4, 0.6);
        w.spawnParticle(Particle.END_ROD, ground.clone().add(0, 0.2, 0), 10, 0.3, 0.1, 0.3, 0.12);
    }

    /** A low grey storm cloud hanging over the storm while it lasts. */
    private void stormClouds(SpeedStorm storm) {
        World w = storm.loc.getWorld();
        if (w == null) return;
        for (int i = 0; i < 6; i++) {
            Location c = storm.loc.clone().add((Math.random() - 0.5) * storm.radius * 2, 9 + Math.random() * 2, (Math.random() - 0.5) * storm.radius * 2);
            w.spawnParticle(Particle.CLOUD, c, 6, 1.2, 0.3, 1.2, 0.005);
            w.spawnParticle(Particle.LARGE_SMOKE, c, 2, 1.0, 0.2, 1.0, 0.002);
        }
    }

    private void stormTick(long now) {
        for (var entry : new java.util.HashMap<>(storms).entrySet()) {
            UUID ownerId = entry.getKey();
            SpeedStorm storm = entry.getValue();
            if (storm.until < now) { storms.remove(ownerId); stormLastStrike.remove(ownerId); continue; }
            Player owner = Bukkit.getPlayer(ownerId);
            World w = storm.loc.getWorld();
            if (w == null) continue;
            if (owner != null && owner.isOnline()) {
                storm.loc = owner.getLocation().clone();
                owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, true, false, false));
            }
            stormClouds(storm);
            Long last = stormLastStrike.get(ownerId);
            if (last != null && now - last < 2000) continue;
            stormLastStrike.put(ownerId, now);
            for (int i = 0; i < 3; i++) {
                Location at = storm.loc.clone().add((Math.random() - 0.5) * storm.radius * 2, 0,
                        (Math.random() - 0.5) * storm.radius * 2);
                at.setY(w.getHighestBlockYAt(at) + 1);
                particleBolt(at);
                GemSounds.play(plugin, "speed.storm-bolt", at);
                for (Entity e : w.getNearbyEntities(at, 3, 4, 3)) {
                    if (!(e instanceof Player o)) continue;
                    if (owner != null && (o.getUniqueId().equals(ownerId) || isTrusted(owner, o))) {
                        o.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2, true, false, false));
                        o.sendMessage(ChatColor.AQUA + "Storm surge: Speed 3!");
                    } else if (owner == null || !o.getUniqueId().equals(ownerId)) {
                        o.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2, true, false, false));
                        o.setCooldown(Material.WIND_CHARGE, 100);
                        o.sendMessage(ChatColor.GRAY + "The storm roots you and disables your wind charges...");
                    }
                }
            }
        }
    }

    /**
     * Blur [melee hit with the gem]: clones appear around the victim and attack. It has no fixed
     * length: it runs until the victim hits the caster back, or the caster goes too long without
     * landing another melee hit on them (abilities.blur.combo-window-seconds).
     */
    private void useBlur(Player p, LivingEntity victim, int energy) {
        UUID id = p.getUniqueId();
        if (blurRuns.containsKey(id)) return; // already running; onBlurCombat keeps it alive
        if (cdRemain(p, "blur") > 0) return;
        BlurRun run = new BlurRun();
        run.victim = victim.getUniqueId();
        run.lastHit = System.currentTimeMillis();
        run.energy = energy;
        blurRuns.put(id, run);
        blurClones().begin(p, victim);
        blurClones().hit(p, victim, this::cloneSwing); // the first hit brings the first clone
        GemSounds.play(plugin, "speed.blur", p.getLocation());
        usedOn(p, GemNames.tx("Blur"), victim.getName());
    }

    /** Ends a Blur: the clones shatter and the cooldown starts now, not when it was activated. */
    private void endBlur(UUID ownerId) { endBlur(ownerId, true); }

    /** {@code shatter} false: the clones just go (logout, chunk unload, plugin disable). */
    private void endBlur(UUID ownerId, boolean shatter) {
        BlurRun run = blurRuns.remove(ownerId);
        if (run == null) return;
        if (shatter) blurClones().shatter(ownerId); else blurClones().remove(ownerId);
        cloneLastHit.keySet().removeIf(c -> Bukkit.getEntity(c) == null); // only clones still standing keep a timer
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null) cdStart(owner, "blur", cooldownMs("blur", run.energy));
    }

    /** Every Blur on or by this player ends now. */
    private void endBlursOf(UUID player, boolean shatter) {
        endBlur(player, shatter);
        for (var en : new ArrayList<>(blurRuns.entrySet())) if (en.getValue().victim.equals(player)) endBlur(en.getKey(), shatter);
    }

    /** A clone swings (BlurClones): its damage lands now, abilities.blur.clone-damage, at most once a second per clone. */
    private void cloneSwing(UUID ownerId, LivingEntity clone) {
        BlurRun run = blurRuns.get(ownerId);
        Player owner = Bukkit.getPlayer(ownerId);
        if (run == null || owner == null || !(Bukkit.getEntity(run.victim) instanceof LivingEntity target) || target.isDead()) return;
        long n = System.currentTimeMillis();
        if (n - cloneLastHit.getOrDefault(clone.getUniqueId(), 0L) < 1000) return;
        cloneLastHit.put(clone.getUniqueId(), n);
        abilityDamage(target, plugin.getConfig().getDouble("abilities.blur.clone-damage", 1.0), owner);
    }

    /** Blur has no fixed length: it lasts while the caster keeps landing hits inside the combo window. */
    private void blurTick(long now) {
        if (blurRuns.isEmpty()) return;
        long window = (long) (Math.max(0.5, plugin.getConfig().getDouble("abilities.blur.combo-window-seconds", 3.0)) * 1000);
        for (var entry : new HashMap<>(blurRuns).entrySet()) {
            BlurRun run = entry.getValue();
            Player owner = Bukkit.getPlayer(entry.getKey());
            Entity target = Bukkit.getEntity(run.victim);
            if (owner == null || owner.isDead() || !(target instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()
                    || !victim.getWorld().equals(owner.getWorld()) || now - run.lastHit > window) {
                endBlur(entry.getKey());
            }
        }
    }

    /** Keeps a Blur alive on each new melee hit its caster lands with the gem, and ends it when the target hits back. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlurCombat(EntityDamageByEntityEvent e) {
        if (blurRuns.isEmpty() || e.getFinalDamage() <= 0) return;
        Entity attacker = e.getDamager() instanceof org.bukkit.entity.Projectile shot
                && shot.getShooter() instanceof Entity shooter ? shooter : e.getDamager();
        BlurRun struck = blurRuns.get(e.getEntity().getUniqueId());
        if (struck != null && struck.victim.equals(attacker.getUniqueId())) {
            endBlur(e.getEntity().getUniqueId());
            return;
        }
        BlurRun mine = blurRuns.get(e.getDamager().getUniqueId());
        if (mine == null || !(e.getDamager() instanceof Player caster) || !mine.victim.equals(e.getEntity().getUniqueId())) return;
        boolean melee = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
        // Only a hit made with the Speed gem itself keeps Blur going; switching to a weapon lets it lapse.
        ItemStack striking = caster.getInventory().getItemInMainHand();
        if (melee && abilityHits <= 0 && gems.isGem(striking) && gems.getType(striking) == GemType.SPEED) {
            mine.lastHit = System.currentTimeMillis();
            if (e.getEntity() instanceof LivingEntity victim) blurClones().hit(caster, victim, this::cloneSwing); // one more clone, and they swing
        }
    }

    /** Puff's hit combo: the one you're comboing hitting you back starts your combo over. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPuffComboBroken(EntityDamageByEntityEvent e) {
        if (puffCombos.isEmpty()) return;
        PuffMath.Combo c = puffCombos.get(e.getEntity().getUniqueId());
        if (c == null) return;
        Entity by = e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity s ? s : e.getDamager();
        c.hitBy(by.getUniqueId());
    }

    /** Blur clones are only part of the effect: they can't be hurt, hit by projectiles or targeted. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlurCloneHurt(EntityDamageEvent e) {
        if (blurClones != null && blurClones.isClone(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlurCloneShot(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (blurClones != null && blurClones.isClone(e.getHitEntity())) e.setCancelled(true); // it flies through
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlurCloneTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        if (blurClones != null && blurClones.isClone(e.getTarget())) e.setCancelled(true);
    }

    /** Nothing a clone wears or holds can ever drop. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlurCloneDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (blurClones == null || !blurClones.isClone(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        UUID owner = blurClones.ownerOf(e.getEntity());
        if (owner != null) endBlur(owner, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlurDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (!blurRuns.isEmpty()) endBlursOf(e.getEntity().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlurWorld(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        if (!blurRuns.isEmpty()) endBlursOf(e.getPlayer().getUniqueId(), true);
    }

    /** A clone's chunk unloading: that Blur's clones go (they'd vanish unsaved anyway). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlurUnload(org.bukkit.event.world.EntitiesUnloadEvent e) {
        if (blurClones == null || blurRuns.isEmpty()) return;
        for (Entity en : e.getEntities()) {
            UUID owner = blurClones.ownerOf(en);
            if (owner != null) endBlur(owner, false);
        }
    }

    // ================= PUFF (user kit, Double Jump offhand-only) =================

    /** Double Jump: mid-air second jump. Works from a click or double-tap space. */
    private void useDoubleJump(Player p) {
        Vector jump = p.getLocation().getDirection().multiply(0.6);
        jump.setY(0.9);
        p.setVelocity(jump);
        p.setFallDistance(0);
        GemSounds.play(plugin, "puff.double-jump", p.getLocation());
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15, 0.4, 0.2, 0.4, 0.05);
    }

    /** Breezy Bash [Hit]: launch the victim straight up, then crash them into the ground. */
    private void useBreezyHit(Player attacker, LivingEntity victim) {
        victim.setVelocity(new Vector(0, 1.8, 0));
        victim.setFallDistance(0);
        GemSounds.play(plugin, "puff.breezy-bash", victim.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isValid()) return;
            victim.setVelocity(new Vector(0, -2.5, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (victim.isValid()) abilityDamage(victim, 6.0, attacker);
            }, 10L);
        }, 15L);
        usedOn(attacker, GemNames.tx("Breezy Bash"), victim.getName());
    }

    /**
     * Breezy Bash [Left Click]: a wind blast that shoves everyone within abilities.breezy-bash.radius away from you,
     * flat (not up into the sky), harder the closer they are, with light damage. Trusted players are left alone.
     */
    private void useWindBlast(Player p) {
        var c = plugin.getConfig();
        double radius = Math.max(1, c.getDouble("abilities.breezy-bash.radius", 6)), push = c.getDouble("abilities.breezy-bash.push", 1.8);
        double up = c.getDouble("abilities.breezy-bash.up", 0.35), dmg = c.getDouble("abilities.breezy-bash.damage", 2.0);
        boolean trusted = c.getBoolean("abilities.breezy-bash.affect-trusted", false);
        Location at = p.getLocation();
        for (Entity e : p.getNearbyEntities(radius, 3, radius)) {
            if (!(e instanceof LivingEntity le) || e.equals(p) || e instanceof org.bukkit.entity.ArmorStand) continue;
            if (e instanceof Player o && !trusted && isTrusted(p, o)) continue;
            double dx = le.getLocation().getX() - at.getX(), dz = le.getLocation().getZ() - at.getZ();
            double[] v = PuffMath.repel(dx, dz, radius, push, up);
            if (v[0] == 0 && v[1] == 0 && v[2] == 0) continue;
            if (dmg > 0) abilityDamage(le, dmg, p);
            le.setVelocity(new Vector(v[0], v[1], v[2]));
        }
        GemSounds.play(plugin, "puff.breezy-bash", at);
        windRing(p, radius);
        used(p, GemNames.tx("Breezy Bash"));
    }

    /** The blast's look: a ring of gusts and white cloud spreading out from you at chest height, with streaks flying outward. */
    private void windRing(Player p, double radius) {
        World w = p.getWorld();
        Location centre = p.getLocation().add(0, 1.1, 0);
        int steps = 8;
        for (int t = 0; t < steps; t++) {
            final double r = 0.6 + (radius - 0.6) * t / (steps - 1);
            final int tick = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int points = (int) Math.max(12, r * 7);
                for (int i = 0; i < points; i++) {
                    double a = Math.PI * 2 * i / points;
                    double cx = Math.cos(a), cz = Math.sin(a);
                    Location at = centre.clone().add(cx * r, 0, cz * r);
                    w.spawnParticle(Particle.CLOUD, at, 0, cx, 0.02, cz, 0.25); // drifting outward
                    if (i % 4 == tick % 4) w.spawnParticle(i % 8 == 0 ? Particle.GUST_EMITTER_SMALL : Particle.GUST, at, 1, 0, 0, 0, 0);
                }
                if (tick < 3) for (int k = 0; k < 6; k++) { // white streaks flying out
                    double a = Math.random() * Math.PI * 2;
                    w.spawnParticle(Particle.CLOUD, centre.clone().add(Math.cos(a) * 0.5, (Math.random() - 0.5) * 0.6, Math.sin(a) * 0.5), 0,
                            Math.cos(a), 0, Math.sin(a), 0.9);
                }
            }, t);
        }
    }

    /**
     * Dash [Right Click]: a fast dash where you look, about abilities.dash.distance (16) blocks in any direction, up
     * included (at most abilities.dash.max-rise), and it stops there: no momentum carries you on. Anyone whose hitbox
     * touches yours on the way takes abilities.dash.contact-damage, at most once per target every 10 ticks.
     */
    private void useDash(Player p, int energy) {
        var c = plugin.getConfig();
        double distance = Math.max(1, c.getDouble("abilities.dash.distance", 16)), maxRise = Math.max(0, c.getDouble("abilities.dash.max-rise", 16));
        int ticks = Math.max(2, c.getInt("abilities.dash.ticks", 8));
        double speed = PuffMath.dashSpeed(distance, ticks), dmg = Math.max(0, c.getDouble("abilities.dash.contact-damage", 4.0));
        Vector dir = p.getLocation().getDirection().normalize();
        double startY = p.getLocation().getY();
        p.setFallDistance(0);
        GemSounds.play(plugin, "puff.dash", p.getLocation());
        UUID id = p.getUniqueId();
        final double[] travelled = {0};
        final Location[] last = {p.getLocation()};
        final org.bukkit.util.BoundingBox[] lastBox = {p.getBoundingBox()};
        new org.bukkit.scheduler.BukkitRunnable() {
            int tick;

            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                Location now = p.getLocation();
                if (now.getWorld().equals(last[0].getWorld())) travelled[0] += now.distance(last[0]);
                last[0] = now;
                org.bukkit.util.BoundingBox box = p.getBoundingBox();
                dashContact(p, lastBox[0], box, dmg);
                lastBox[0] = box;
                double risen = now.getY() - startY;
                if (PuffMath.dashDone(travelled[0], distance, risen, maxRise, tick, ticks)) {
                    Vector v = p.getVelocity();
                    p.setVelocity(new Vector(v.getX() * 0.2, Math.min(0, v.getY()), v.getZ() * 0.2)); // it stops here: nothing carries you on
                    cancel();
                    return;
                }
                double[] step = PuffMath.dashStep(dir.getX(), dir.getY(), dir.getZ(), speed, distance - travelled[0], maxRise - risen);
                p.setVelocity(new Vector(step[0], step[1], step[2]));
                p.setFallDistance(0);
                p.getWorld().spawnParticle(Particle.CLOUD, now, 10, 0.3, 0.3, 0.3, 0.05);
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        used(p, GemNames.tx("Dash"));
    }

    private final Map<String, Long> dashHits = new HashMap<>(); // "dasher:target" -> the tick it was last hit

    /** The dash's hit: anyone whose hitbox your hitbox touched since last tick (never you or your trusted players). */
    private void dashContact(Player p, org.bukkit.util.BoundingBox from, org.bukkit.util.BoundingBox to, double dmg) {
        double[] me = PuffMath.swept(box(from), box(to));
        long tick = Bukkit.getCurrentTick();
        for (Entity e : p.getNearbyEntities(4, 4, 4)) {
            if (!(e instanceof LivingEntity le) || e.equals(p) || e instanceof org.bukkit.entity.ArmorStand) continue;
            if (e instanceof Player o && isTrusted(p, o)) continue;
            if (!PuffMath.touching(me, box(le.getBoundingBox()))) continue;
            String k = p.getUniqueId() + ":" + le.getUniqueId();
            if (!PuffMath.contactReady(dashHits.get(k), tick, 10)) continue;
            dashHits.put(k, (long) tick);
            abilityDamage(le, dmg, p);
        }
        if (dashHits.size() > 256) dashHits.values().removeIf(t -> tick - t > 200);
    }

    private static double[] box(org.bukkit.util.BoundingBox b) {
        return new double[] {b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()};
    }

    // ================= ASTRA (user kit, Drift offhand-only) =================

    public boolean isGemDisabled(Player p) {
        Long until = gemDisabledUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void disableGem(Player p, long millis, String reason) {
        if (plugin.mythics().heldMythicType(p) == GemType.RELAY) return; // Relay: Clean Signal
        gemDisabledUntil.put(p.getUniqueId(), System.currentTimeMillis() + millis);
        p.sendMessage(ChatColor.DARK_PURPLE + "Your gem is disabled (" + reason + ")!");
        GemSounds.playTo(plugin, "general.gem-disabled", p);
    }

    /**
     * Refreshing disable used by Relay's Time Dilation / Time Lock. The longest timer wins and the
     * message/sound only play when the gem was not already disabled. Relay holders are immune.
     */
    public void applyGemDisable(Player p, long millis, String reason) {
        if (plugin.mythics().heldMythicType(p) == GemType.RELAY) return; // Relay: Clean Signal
        boolean already = isGemDisabled(p);
        long until = System.currentTimeMillis() + millis;
        Long cur = gemDisabledUntil.get(p.getUniqueId());
        if (cur == null || cur < until) gemDisabledUntil.put(p.getUniqueId(), until);
        if (!already) {
            p.sendMessage(ChatColor.DARK_PURPLE + "Your gem is disabled (" + reason + ")!");
            GemSounds.playTo(plugin, "general.gem-disabled", p);
        }
    }

    /** Dimensional Drift [offhand right-click]: the ghost horse ride for 5s, at both tiers. */
    private void useDimensionalDrift(Player p, int tier, int energy) {
        GemSounds.play(plugin, "astra.dimensional-drift", p.getLocation());
        org.bukkit.entity.Horse horse = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.Horse.class);
        horse.setAdult();
        horse.setTamed(true);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 140, 0, false, false, false));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 140, 3, true, false, false));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 140, 3, true, false, false));
        horse.addPassenger(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
        used(p, GemNames.tx("Dimensional Drift"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            horse.remove();
            p.sendMessage(ChatColor.GRAY + "The drift fades...");
        }, 5 * 20L);
    }

    /** Daggers [Left-click]: the first click summons the fan of 5 behind you and throws one; each click after throws the next. */
    private void handleAstraDaggers(Player p, int energy) {
        Volley v = volleys.get(p.getUniqueId());
        if (v == null) v = summonVolley(p);
        if (!fireAstraDagger(p)) return;
        used(p, GemNames.tx("Daggers"));
        if (v.thrown >= AstraMath.DAGGERS) endVolley(p.getUniqueId(), false); // the fifth: the cooldown starts
    }

    /**
     * Astra's Tier 2 right-click (AstraMath.route), from either click event: a right-click on a player can arrive as
     * an entity click and an air click in the same tick, so only the first counts.
     */
    private void astraRight(Player p, Player onPlayer, int energy) {
        int tick = Bukkit.getCurrentTick();
        Integer last = astraRightTick.put(p.getUniqueId(), tick);
        if (last != null && last == tick) return;
        switch (AstraMath.route(false, true, p.isSneaking(), onPlayer != null)) {
            case PROJECTION -> useProjection(p, energy);
            case UNBOUNDED -> useUnbounded(p, onPlayer);
            case VOID -> tryAstralVoid(p, energy);
            default -> { }
        }
    }

    /** Unbounded [Right-click a player] T2: your camera goes inside them for unbounded.seconds (AstralSpectate). 30 s between uses. */
    private void useUnbounded(Player p, Player host) {
        long now = System.currentTimeMillis();
        Long last = unboundedLast.get(p.getUniqueId());
        if (last != null && now - last < 30_000) return;
        if (plugin.astral() == null || !plugin.astral().startUnbounded(p, host)) return;
        unboundedLast.put(p.getUniqueId(), now);
        usedOn(p, GemNames.tx("Unbounded"), host.getName());
    }

    /** Coming out of Unbounded darkens your own gem for 30 s (as before). */
    public boolean isProjecting(Player p) {
        return plugin.astral() != null && plugin.astral().projecting(p);
    }

    /** Astral Projection [Sneak + right-click] T2: out of your body as a spectator, your body left behind (AstralSpectate). */
    private void useProjection(Player p, int energy) {
        if (cdRemain(p, "projection") > 0) return; // Silenced
        if (plugin.astral() == null || !plugin.astral().startProjection(p)) return;
        endVolley(p.getUniqueId(), false);
        cdStart(p, "projection", cooldownMs("projection", energy));
        used(p, GemNames.tx("Astral Projection"));
    }

    /** Astral Void [Right-click anywhere that isn't a player] T2: the same mass gem disable as before, around you. */
    private void tryAstralVoid(Player p, int energy) {
        if (remain(voidCooldown, p.getUniqueId()) > 0) return; // Silenced: right-clicking the air is common
        voidCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("void", energy));
        useAstralVoid(p, p.getLocation(), energy);
    }

    private static final EquipmentSlot[] PHASE_SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Phasing: a ring flashes at your waist and you're gone for astra.phasing.vanish-ticks, armour and held items too.
     * The items are hidden only on other players' screens (client-side equipment changes), then the real ones are
     * sent back; your own items are never touched.
     */
    private void phase(Player p) {
        Location c = p.getLocation().add(0, 1.0, 0);
        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(0x9B, 0x5C, 0xE6), 1.0f);
        Particle.DustOptions white = new Particle.DustOptions(Color.WHITE, 0.8f);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            Location at = c.clone().add(Math.cos(a) * 0.85, 0, Math.sin(a) * 0.85);
            p.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, i % 3 == 0 ? white : purple);
            if (i % 2 == 0) p.getWorld().spawnParticle(Particle.PORTAL, at, 1, 0, 0, 0, 0.05);
        }
        GemSounds.play(plugin, "astra.phasing", c);
        int ticks = plugin.getConfig().getInt("astra.phasing.vanish-ticks", 8);
        if (ticks <= 0) return;
        if (!p.hasPotionEffect(PotionEffectType.INVISIBILITY))
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, true, false, false));
        Map<EquipmentSlot, ItemStack> none = new java.util.EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot s : PHASE_SLOTS) none.put(s, new ItemStack(Material.AIR));
        List<Player> viewers = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || o.getLocation().distanceSquared(p.getLocation()) > 128 * 128) continue;
            o.sendEquipmentChange(p, none);
            viewers.add(o);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Map<EquipmentSlot, ItemStack> real = new java.util.EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot s : PHASE_SLOTS) {
                ItemStack it = p.getInventory().getItem(s);
                real.put(s, it == null ? new ItemStack(Material.AIR) : it);
            }
            for (Player o : viewers) if (o.isOnline() && o.getWorld().equals(p.getWorld())) o.sendEquipmentChange(p, real);
        }, ticks);
    }

    /** Astral Void: disable untrusted gems in a radius. */
    private void useAstralVoid(Player p, Location around, int energy) {
        int radius = 10;
        int hit = 0;
        for (Entity e : around.getWorld().getNearbyEntities(around, radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o)) continue;
            disableGem(o, 20_000L, GemNames.tx("Astral Void"));
            hit++;
        }
        GemSounds.play(plugin, "astra.astral-void", p.getLocation());
        p.getWorld().spawnParticle(Particle.DRAGON_BREATH, around, 60, 3, 2, 3, 0.1);
        usedGroup(p, GemNames.tx("Astral Void"), hit);
    }

    // ================= LIFE (user kit, Vortex offhand-only) =================

    private void setMaxHealth(Player p, double v) {
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(v);
        if (p.getHealth() > v) p.setHealth(v);
    }

    /**
     * The player's own max health, without bonuses (the blessing's +4 hearts, gear): the heart locks set and restore
     * this, so a bonus is never baked into it for good.
     */
    private double baseMaxHealth(Player p) {
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return (attr == null) ? 20.0 : attr.getBaseValue();
    }

    /** Heart lock: cap max HP for a while, then restore the original. */
    private void lockHearts(Player v, double newMax, long durMs) {
        long now = System.currentTimeMillis();
        if (!heartOrig.containsKey(v.getUniqueId())) {
            heartOrig.put(v.getUniqueId(), baseMaxHealth(v));
        }
        newMax = Math.max(2.0, Math.min(60.0, newMax));
        setMaxHealth(v, newMax);
        heartUntil.put(v.getUniqueId(), Math.max(heartUntil.getOrDefault(v.getUniqueId(), 0L), now + durMs));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long until = heartUntil.get(v.getUniqueId());
            if (until == null || until > System.currentTimeMillis()) return;
            Double orig = heartOrig.remove(v.getUniqueId());
            heartUntil.remove(v.getUniqueId());
            if (orig == null || !v.isOnline()) return;
            setMaxHealth(v, orig);
            v.sendMessage(ChatColor.GREEN + "Your hearts settle back to normal.");
        }, (durMs / 50) + 5);
    }

    /**
     * Vitality Vortex [offhand right-click]: works anywhere. It drains the life out of the land around you (VortexDrain:
     * patchy dead blocks that grow back) and turns it into Resistance, Regeneration and Absorption, longer and
     * stronger the more it drained (as before, from 20 s and Resistance I).
     */
    private void useVitalityVortex(Player p, int tier, int energy) {
        World w = p.getWorld();
        Location c = p.getLocation();
        int absorbed = plugin.vortex() == null ? 0 : plugin.vortex().drain(p);
        int dur = Math.min(60, 20 + absorbed / 2);
        int resAmp = Math.min(2, absorbed / 25);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, dur * 20, resAmp));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, dur * 20, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, dur * 20, 1));
        w.spawnParticle(Particle.HEART, c, 40, 2, 2, 2, 0.1);
        GemSounds.play(plugin, "life.vitality-vortex", c);
        used(p, GemNames.tx("Vitality Vortex"));
    }

    /** Heart Drainer [Left Click]: remove 4 hearts from untrusted enemies for 20s. */
    private void useHeartDrainer(Player p, int energy) {
        int radius = 10;
        int hit = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o) || o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o)) continue;
            double base = heartOrig.getOrDefault(o.getUniqueId(), baseMaxHealth(o));
            lockHearts(o, Math.max(2.0, base - 8.0), 20_000L);
            o.sendMessage(ChatColor.RED + GemNames.tx("Heart Drainer: -4 hearts for 20s!"));
            hit++;
        }
        GemSounds.play(plugin, "life.heart-drainer", p.getLocation());
        usedGroup(p, GemNames.tx("Heart Drainer"), hit);
    }

    /**
     * Circle of Life [Right Click] (LifeMath.circle), both versions mending your armour first:
     * an untrusted player in front of you -> the steal: 5 hearts from every untrusted player within 12 blocks, 5 to
     * you for each (30 s, at most 15 hearts);
     * nobody untrusted in front of you -> the blessing (LifeBlessing): you and your trusted players in the ring get
     * +4 hearts, full hunger and Regeneration II.
     */
    private void useCircleOfLife(Player p, int energy, Player inFront) {
        int radius = 12;
        boolean steal = LifeMath.circle(inFront != null, inFront != null && isTrusted(p, inFront)) == LifeMath.Circle.STEAL;
        List<Player> targets = new ArrayList<>();
        if (steal) for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && !o.getUniqueId().equals(p.getUniqueId()) && !isTrusted(p, o)) targets.add(o); // never your own side
        }

        // Always mend own armor first
        for (ItemStack armor : p.getInventory().getArmorContents()) {
            if (armor == null || armor.getType() == Material.AIR) continue;
            if (armor.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.hasDamage()) {
                dmg.setDamage(Math.max(0, dmg.getDamage() - 20));
                armor.setItemMeta(dmg);
            }
        }

        if (targets.isEmpty()) {
            // The blessing: you and your trusted players in the ring
            if (plugin.blessing() != null) plugin.blessing().cast(p, this::isTrusted);
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation(), 10, 1, 1, 1, 0.05);
            used(p, GemNames.tx("Circle of Life"));
            return;
        }

        // Has targets: steal 5 hearts from each, give 5 to user (30s duration)
        int stolenFrom = 0;
        for (Player t : targets) {
            double targetBase = heartOrig.getOrDefault(t.getUniqueId(), baseMaxHealth(t));
            double targetNewMax = Math.max(2.0, targetBase - 10.0); // lose 5 hearts = 10 HP
            if (!heartOrig.containsKey(t.getUniqueId())) {
                heartOrig.put(t.getUniqueId(), targetBase);
            }
            lockHearts(t, targetNewMax, 30_000L);
            t.sendMessage(ChatColor.RED + GemNames.tx("Circle of Life: ") + p.getName() + " stole 5 hearts from you!");
            stolenFrom++;
        }

        // User gains 5 hearts per target (capped at 15 hearts / 30 HP max)
        double userBase = heartOrig.getOrDefault(p.getUniqueId(), baseMaxHealth(p));
        double userGain = stolenFrom * 10.0; // 5 hearts = 10 HP each
        double userNewMax = Math.min(30.0, userBase + userGain); // cap at 15 hearts
        if (!heartOrig.containsKey(p.getUniqueId())) {
            heartOrig.put(p.getUniqueId(), userBase);
        }
        lockHearts(p, userNewMax, 30_000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, true, false, false));
        p.sendMessage(ChatColor.GREEN + GemNames.tx("Circle of Life: Stole 5 hearts from ") + stolenFrom + " target(s)!");
        GemSounds.play(plugin, "life.circle-of-life", p.getLocation());
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation(), 30, 2, 1, 2, 0.1);
        usedGroup(p, GemNames.tx("Circle of Life"), stolenFrom);
    }

    // ================= FLUX (user kit, Burst offhand-only) =================

    int fluxOf(Map<UUID, Integer> map, UUID id) {
        return map.getOrDefault(id, 0);
    }

    private void fluxAdd(Map<UUID, Integer> map, UUID id, int amount) {
        map.put(id, Math.max(0, Math.min(100, fluxOf(map, id) + amount)));
    }

    /** Kinetic Burst [offhand hold right-click]: spend all charge as AoE + knockback. */
    private void useKineticBurst(Player p, int tier, int energy) {
        int charge = fluxOf(fluxCharge, p.getUniqueId());
        if (charge < 10) {
            p.sendMessage(ChatColor.GRAY + GemNames.tx("Kinetic Burst: not charged yet (take hits to charge: ") + charge + "/100).");
            fluxBurstCooldown.remove(p.getUniqueId()); // a failed burst costs no cooldown (this used to clear the wrong timer)
            return;
        }
        int radius = 6 + charge / 20;
        double dmg = 2.0 + charge / 12.0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && (o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o))) continue;
            if (e instanceof LivingEntity le) {
                abilityDamage(le, dmg, p);
                Vector away = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = p.getLocation().getDirection().setY(0);
                away.normalize().multiply(1.2 + charge / 60.0);
                away.setY(0.9);
                le.setVelocity(away);
            }
        }
        fluxCharge.put(p.getUniqueId(), 0);
        GemSounds.play(plugin, "flux.kinetic-burst", p.getLocation());
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation(), 60, 2, 1, 2, 0.2);
        used(p, GemNames.tx("Kinetic Burst"));
    }

    /** Kinetic Overdrive [Right Click]: 30s buff; every 3 hits taken arms a big next hit. */
    private void useOverdrive(Player p, int energy) {
        overdriveUntil.put(p.getUniqueId(), System.currentTimeMillis() + 30_000L);
        overdriveTaken.put(p.getUniqueId(), 0);
        overdriveArmed.put(p.getUniqueId(), false);
        GemSounds.play(plugin, "flux.overdrive", p.getLocation());
        used(p, GemNames.tx("Kinetic Overdrive"));
    }

    /** T2 ingot tap: gem in offhand + ingot/block in main + right-click converts it to watts. */
    private boolean tryIngotCollect(Player p, ItemStack main) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (!gems.isGem(off) || gems.getType(off) != GemType.FLUX || gems.getTier(off) < 2) return false;
        if (!EnergyStage.allowsPowers(gems.getEnergy(off)) || isGemDisabled(p)) return false;
        int gain = plugin.flux() == null ? 0 : plugin.flux().ingotWatts(main.getType()); // flux.watts.per-ingot: copper is best
        if (gain <= 0) return false;
        if (main.getAmount() > 1) main.setAmount(main.getAmount() - 1);
        else p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        int now = plugin.flux().feed(p, gain);
        GemSounds.playTo(plugin, "flux.ingot-to-watts", p);
        p.sendMessage(ChatColor.YELLOW + GemNames.tx("Conduction: +") + gain + " watts (" + now + "/100).");
        return true;
    }

    private void usePower(Player p, GemType type, int tier, int energy) {
        switch (type) {
            case STRENGTH -> useChadStrength(p, energy);
            case FIRE -> handleFireballClick(p, energy);
            case SPEED -> useSpeedyStorm(p, energy);
            case WEALTH -> useRichRush(p, energy);
            case FLUX -> fluxFire(p, energy);
            case ASTRA -> handleAstraDaggers(p, energy);
            case LIFE -> {
                double heal = Math.min(p.getMaxHealth() - p.getHealth(), 4.0);
                p.setHealth(p.getHealth() + heal);
                for (Entity en : p.getNearbyEntities(5, 5, 5)) {
                    if (en instanceof LivingEntity le && !en.getUniqueId().equals(p.getUniqueId())) {
                        le.damage(2.0, p);
                    }
                }
                GemSounds.play(plugin, "life.siphon", p.getLocation());
                p.sendMessage(ChatColor.RED + "Siphon: +" + ((int) heal / 2) + " hearts drained.");
            }
            case PUFF -> useDash(p, energy);
        }
    }

    private void useTier2(Player p, GemType type, int energy) {
        switch (type) {
            case STRENGTH -> {
                Player t = plugin.bounty() == null ? null : plugin.bounty().target(p);
                if (t == null) p.sendMessage(ChatColor.GRAY + GemNames.tx("Bounty Hunter: Strength in your off hand + right-click opens the Bounty Board."));
                else p.sendMessage(ChatColor.RED + GemNames.tx("Bounty Hunter: ") + plugin.shownName(t) + ChatColor.GRAY + " — follow the thread.");
            }
            case FIRE -> {
                Block target = p.getTargetBlockExact(6);
                if (target == null) {
                    p.sendMessage(ChatColor.GRAY + GemNames.tx("Cozy Campfire: aim at a block within 6m (or right-click a block)."));
                } else {
                    long rem = remain(cozyCooldown, p.getUniqueId());
                    if (rem > 0) {
                        p.sendMessage(ChatColor.RED + GemNames.tx("Cozy Campfire on cooldown: ") + (rem / 1000) + "s");
                    } else {
                        cozyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("cozy", energy));
                        useCozyCampfire(p, target, energy);
                    }
                }
            }
            case SPEED -> {
                GemSounds.play(plugin, "speed.speed-boost", p.getLocation());
                // Silenced — no chat spam
            }
            case WEALTH -> {
                // Silenced — no chat spam
            }
            case FLUX -> {
                // Silenced — no chat spam
            }
            case ASTRA -> {
                // Silenced — no chat spam
            }
            case LIFE -> {
                p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + 6.0));
                for (Entity en : p.getNearbyEntities(8, 6, 8)) {
                    if (en instanceof Player ally && !ally.getUniqueId().equals(p.getUniqueId())) {
                        ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                    }
                }
                // Silenced — no chat spam
            }
            case PUFF -> {
                // Silenced — no chat spam
            }
        }
    }

    private Player nearestPlayer(Player p, int radius) {
        Player best = null;
        double bd = radius * radius;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && !o.getUniqueId().equals(p.getUniqueId())) {
                double d = p.getLocation().distanceSquared(o.getLocation());
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
        }
        return best;
    }

    // ---------- special items ----------

    private void handleSpecial(Player p, ItemStack inHand, String kind) {
        switch (kind) {
            case "energybottle" -> {
                ItemStack gem = offOrMainGem(p);
                if (gem == null) {
                    p.sendMessage(ChatColor.RED + "Hold a gem (or keep one in offhand) to absorb this.");
                    return;
                }
                int en = gems.getEnergy(gem);
                if (en <= 0 || en >= 10) {
                    p.sendMessage(ChatColor.RED + "Cannot absorb: gem is Broken or already Pristine +5.");
                    return;
                }
                gems.setEnergy(gem, en + 1);
                consumeOne(p, inHand);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.4f);
                p.sendMessage(ChatColor.YELLOW + "Absorbed +1 energy → " + gems.getEnergy(gem) + "/10 (" + EnergyStage.name(gems.getEnergy(gem)) + ")");
            }
            case "upgrader" -> {
                // The Upgrader must be in the main hand, but the target gem may be
                // anywhere in the player's inventory (including the offhand).
                ItemStack gem = findInventoryGemForUpgrade(p);
                if (gem == null) {
                    p.sendMessage(ChatColor.RED + "Keep a Tier 1 gem somewhere in your inventory to upgrade it.");
                    return;
                }
                if (gems.getTier(gem) >= 2) {
                    p.sendMessage(ChatColor.GRAY + "That gem is already Tier 2.");
                    return;
                }
                gems.setTier(gem, 2);
                consumeOne(p, inHand);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
                p.sendMessage(ChatColor.YELLOW + "Upgraded your gem to Tier 2! Check tablist glow.");
            }
            case "trader" -> {
                ItemStack gem = null;
                boolean gemMain = false;
                ItemStack mainGem = p.getInventory().getItemInMainHand();
                ItemStack offGem = p.getInventory().getItemInOffHand();
                if (gems.isGem(mainGem)) { gem = mainGem; gemMain = true; }
                else if (gems.isGem(offGem)) { gem = offGem; }
                if (gem == null) {
                    p.sendMessage(ChatColor.RED + "Hold a gem in either hand to trade.");
                    return;
                }
                GemType cur = gems.getType(gem);
                if (cur == null || cur.isMythic()) { // mythics are one of a kind: no trading them (the Trader isn't used up)
                    p.sendMessage(ChatColor.RED + "Mythic gems can't be traded.");
                    return;
                }
                List<GemType> pool = GemType.tradePool(cur); // standard gems only: a Trader never makes a mythic
                if (pool.isEmpty()) return;
                GemType next = pool.get((int) (Math.random() * pool.size()));
                int tier = gems.getTier(gem);
                int en = gems.getEnergy(gem);
                ItemStack replacement = gems.createGem(next, tier, en);
                if (gemMain) p.getInventory().setItemInMainHand(replacement);
                else p.getInventory().setItemInOffHand(replacement);
                consumeOne(p, inHand);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1, 1);
                p.sendMessage(ChatColor.AQUA + "Time for a change: " + cur.shown() + " → " + next.shown());
            }
            case "repairkit" -> {
                ItemStack gem = offOrMainGem(p);
                // A standing Pedestal claims all repairs as rites.
                if (plugin.pedestal().exists()) {
                    plugin.pedestal().tryRepair(p, gem, inHand);
                    return;
                }
                if (gem == null) {
                    p.sendMessage(ChatColor.RED + "Hold a gem or keep one in offhand to repair.");
                    return;
                }
                int en = gems.getEnergy(gem);
                if (en <= 0) {
                    p.sendMessage(ChatColor.RED + "Broken gems need a Restoration (not a Repair Kit).");
                    return;
                }
                if (en >= 5) {
                    p.sendMessage(ChatColor.GRAY + "Already Pristine or higher. No repair needed.");
                    return;
                }
                gems.setEnergy(gem, en + 1);
                // if repair kit was in main hand and gem in offhand, consume from offhand? we consumed inHand which is main;
                // handle offhand case below
                if (p.getInventory().getItemInOffHand().equals(inHand)) {
                    consumeOffhand(p);
                } else {
                    // inHand is main but gem might be offhand: we already consumed? main hand IS the kit then, ok
                    if (!gems.isGem(inHand)) consumeOne(p, inHand);
                }
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
                p.sendMessage(ChatColor.GREEN + "Repaired +1 → " + gems.getEnergy(gem) + "/10 (" + EnergyStage.name(gems.getEnergy(gem)) + ")");
            }
            default -> { }
        }
    }


    /**
     * Finds the player's gem anywhere in normal inventory storage or offhand.
     * The Upgrader itself is held in the main hand, so the main-hand slot is
     * naturally skipped unless it somehow contains a gem (which cannot happen
     * during normal Upgrader use). Armor slots are intentionally not searched.
     */
    private ItemStack findInventoryGemForUpgrade(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off)) return off;

        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (gems.isGem(stack)) return stack;
        }
        return null;
    }

    private ItemStack offOrMainGem(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (gems.isGem(off)) return off;
        ItemStack main = p.getInventory().getItemInMainHand();
        if (gems.isGem(main)) return main;
        return findGem(p);
    }

    private void consumeOne(Player p, ItemStack stack) {
        if (stack.getAmount() > 1) stack.setAmount(stack.getAmount() - 1);
        else {
            // remove from whichever hand holds it
            if (p.getInventory().getItemInMainHand().equals(stack)) p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            else if (p.getInventory().getItemInOffHand().equals(stack)) p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            else p.getInventory().remove(stack);
        }
    }

    private void consumeOffhand(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getAmount() > 1) off.setAmount(off.getAmount() - 1);
        else p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
    }

    // ---------- combat tweaks ----------

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        // Fireball deals no damage to self (user kit)
        if (e.getEntity() instanceof Player selfVictim) {
            UUID owner = fireballOwner.get(e.getDamager().getUniqueId());
            if (owner != null && owner.equals(selfVictim.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
        }
        // Flamestrike: enemies that hit a Fire holder may ignite (both tiers)
        if (e.getEntity() instanceof Player fireVictim && e.getDamager() instanceof LivingEntity attacker) {
            ItemStack vGem = heldGem(fireVictim);
            if (vGem == null) vGem = findGem(fireVictim);
            if (vGem != null && gems.getType(vGem) == GemType.FIRE
                    && EnergyStage.allowsPassives(gems.getEnergy(vGem)) && Math.random() < 0.3) {
                attacker.setFireTicks(60);
            }
        }
        // Phasing: Astra holders dodge hits (10% T1 / 15% T2)
        if (e.getEntity() instanceof Player phaseVictim && !isGemDisabled(phaseVictim)) {
            ItemStack vGem = heldGem(phaseVictim);
            if (vGem == null) vGem = findGem(phaseVictim);
            if (vGem != null && gems.getType(vGem) == GemType.ASTRA
                    && EnergyStage.allowsPassives(gems.getEnergy(vGem))) {
                double dodge = (gems.getTier(vGem) >= 2) ? 0.15 : 0.10;
                if (Math.random() < dodge) {
                    e.setCancelled(true);
                    phase(phaseVictim);
                    return;
                }
            }
        }
        // Dagger shots disable gems on hit
        if (e.getDamager() instanceof org.bukkit.entity.Projectile proj && daggerShots.remove(proj.getUniqueId())) {
            // The arrow's own damage (base x speed, through armour) is replaced by astra.dagger-hit-damage as an ability hit.
            e.setCancelled(true);
            proj.remove(); // a cancelled hit would bounce the arrow back
            if (proj.getShooter() instanceof Player shooter && e.getEntity() instanceof LivingEntity le
                    && !le.getUniqueId().equals(shooter.getUniqueId())) {
                abilityDamage(le, plugin.getConfig().getDouble("astra.dagger-hit-damage", 2.5), shooter);
                le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0)); // what the spectral arrow did
                if (le instanceof Player hit) disableGem(hit, 8_000L, GemNames.tx("Daggers"));
            }
            return;
        }
        if (!(e.getDamager() instanceof Player p)) return;
        // Unfortunate victims fizzle 50% of their own attacks
        Long un = unfortunateUntil.get(p.getUniqueId());
        if (un != null && un > System.currentTimeMillis() && Math.random() < 0.5) {
            e.setCancelled(true);
            return;
        }
        // Frailed victims take more damage from anyone (armor-cut flavor)
        if (e.getEntity() instanceof Player victim) {
            Long until = frailUntil.get(victim.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) {
                e.setDamage(e.getDamage() * 1.2);
            }
        }
        ItemStack gem = heldGem(p);
        if (gem == null) return;
        if (isGemDisabled(p)) return;
        GemType gtype = gems.getType(gem);
        if (gtype == GemType.FIRE) {
            // Meteor Shower [Hit] T2: fireballs rain around the hit player for 30s
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof Player hit) {
                meteorUntil.put(hit.getUniqueId(), System.currentTimeMillis() + 30_000L);
                meteorLastShot.put(hit.getUniqueId(), 0L);
                usedOn(p, GemNames.tx("Meteor Shower"), hit.getName());
            }
            return;
        }
        if (gtype == GemType.WEALTH) {
            int wTier = gems.getTier(gem);
            int wEnergy = gems.getEnergy(gem);
            // Unfortunate [Hit] T2: 50% of the victim's actions fizzle for 15s
            if (wTier >= 2 && EnergyStage.allowsPowers(wEnergy) && e.getEntity() instanceof Player hit) {
                unfortunateUntil.put(hit.getUniqueId(), System.currentTimeMillis() + 15_000L);
                hit.sendMessage(ChatColor.RED + GemNames.tx("Unfortunate: half your actions will fail for 15s!"));
                usedOn(p, GemNames.tx("Unfortunate"), hit.getName());
            }
            // Durability Chip T2: mend own armor, chew victim armor
            if (wTier >= 2 && EnergyStage.allowsPassives(wEnergy)) {
                for (ItemStack armor : p.getInventory().getArmorContents()) {
                    if (armor == null || armor.getType() == Material.AIR) continue;
                    if (armor.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.hasDamage()) {
                        dmg.setDamage(Math.max(0, dmg.getDamage() - 5));
                        armor.setItemMeta(dmg);
                    }
                }
                if (e.getEntity() instanceof Player vp) {
                    for (ItemStack armor : vp.getInventory().getArmorContents()) {
                        if (armor == null || armor.getType() == Material.AIR) continue;
                        if (armor.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                            int max = armor.getType().getMaxDurability();
                            dmg.setDamage(Math.min(Math.max(0, max - 1), dmg.getDamage() + 15));
                            armor.setItemMeta(dmg);
                        }
                    }
                }
            }
            return;
        }
        if (gtype == GemType.SPEED) {
            // Blur T2: melee only, and only when the hit is made with the Speed gem itself in the main hand
            // (a sword hit with the gem in the offhand does not count). Clones appear around the victim.
            ItemStack striking = p.getInventory().getItemInMainHand();
            boolean melee = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
            if (melee && gems.isGem(striking) && gems.getType(striking) == GemType.SPEED
                    && gems.getTier(striking) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(striking))
                    && e.getEntity() instanceof LivingEntity le) {
                useBlur(p, le, gems.getEnergy(striking));
            }
            return;
        }
        if (gtype == GemType.PUFF) {
            // Hit combo (both tiers): puff.combo.hits melee hits in a row on one target levitate them briefly (juggle)
            boolean meleeHit = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
            if (EnergyStage.allowsPassives(gems.getEnergy(gem)) && meleeHit && abilityHits <= 0 && e.getEntity() instanceof LivingEntity le) {
                var cfg = plugin.getConfig();
                if (puffCombos.computeIfAbsent(p.getUniqueId(), k -> new PuffMath.Combo()).hit(le.getUniqueId(), System.currentTimeMillis(),
                        Math.max(1, cfg.getInt("puff.combo.hits", 5)), (long) (cfg.getDouble("puff.combo.window-seconds", 1.5) * 1000),
                        (long) (cfg.getDouble("puff.combo.cooldown-seconds", 6) * 1000))) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, Math.max(1, cfg.getInt("puff.combo.levitation-ticks", 20)), 0));
                    le.getWorld().spawnParticle(Particle.CLOUD, le.getLocation().add(0, 0.2, 0), 12, 0.3, 0.1, 0.3, 0.02);
                }
            }
            // Breezy Bash [Hit] T2: launch up + slam down
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof LivingEntity le2 && cdRemain(p, "bash-hit") <= 0) {
                cdStart(p, "bash-hit", (long) (Math.max(0, plugin.getConfig().getDouble("abilities.breezy-bash.hit-cooldown-seconds", 8))
                        * EnergyStage.cooldownMultiplier(gems.getEnergy(gem)) * 1000));
                useBreezyHit(p, le2);
            }
            return;
        }
        if (gtype == GemType.ASTRA) return; // Unbounded is a right-click on a player now
        if (gtype == GemType.LIFE) {
            // Heartlock [Hit] T2: freeze the victim at their current hearts for 15s
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof Player hit) {
                lockHearts(hit, Math.max(2.0, hit.getHealth()), 15_000L);
                hit.sendMessage(ChatColor.RED + GemNames.tx("Heartlock: your hearts freeze for 15s!"));
                usedOn(p, GemNames.tx("Heartlock"), hit.getName());
            }
            return;
        }
        if (gtype == GemType.FLUX) {
            int fTier = gems.getTier(gem);
            int fEnergy = gems.getEnergy(gem);
            // Overdrive payoff: armed hit lands hard
            if (Boolean.TRUE.equals(overdriveArmed.remove(p.getUniqueId()))) {
                e.setDamage(e.getDamage() * 2.0);
                p.sendMessage(ChatColor.YELLOW + "OVERDRIVE hit!");
            }
            return; // Ground is a left-click at 100 W now (FluxPower), not every hit
        }
        if (gtype != GemType.STRENGTH) return;
        int energy = gems.getEnergy(gem);
        int tier = gems.getTier(gem);
        if (!EnergyStage.allowsPassives(energy) && !EnergyStage.allowsPowers(energy)) return;

        // Bloodthorns (user kit): the less HP, the more damage. Up to +60% at 1 HP.
        if (EnergyStage.allowsPassives(energy)) {
            double missing = 1.0 - (p.getHealth() / p.getMaxHealth());
            e.setDamage(e.getDamage() * (1.0 + missing * 0.6));
        }

        if (!EnergyStage.allowsPowers(energy) || tier < 2) return;
        // Frailer [Hit]: victim takes more damage (armor effectiveness cut) + Weakness
        if (e.getEntity() instanceof LivingEntity le) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0));
            usedOn(p, GemNames.tx("Frailer"), le.getName());
            frailerLine(p, le);
            if (le instanceof Player vp) {
                frailUntil.put(vp.getUniqueId(), System.currentTimeMillis() + 8_000L);
            } else {
                e.setDamage(e.getDamage() * 1.15);
            }
        }
        // Chad Strength [Right Click buff]: every 4th hit deals double damage while active
        Long chad = chadUntil.get(p.getUniqueId());
        if (chad != null && chad > System.currentTimeMillis()) {
            int n = hitCount.getOrDefault(p.getUniqueId(), 0) + 1;
            if (n == 4) {
                e.setDamage(e.getDamage() * 2.0);
                p.sendMessage(ChatColor.RED + GemNames.tx("CHAD STRENGTH x2! (hit 4)"));
                GemSounds.play(plugin, "strength.chad-fourth-hit", p.getLocation());
                chadCritFlash(p, e.getEntity()); // the dots flash red, and a red burst on them
                n = 0;
            }
            hitCount.put(p.getUniqueId(), n);
        }
    }

    @EventHandler
    public void onNullifiedPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        // Nullify no longer blocks potions — it only strips Strength/Speed/FireRes from opponents
    }

    @EventHandler
    public void onNullifiedConsume(PlayerItemConsumeEvent e) {
        // Relay uses native item-use completion as input; it is not food or drink.
        if (gems.isGem(e.getItem()) && gems.getType(e.getItem()) == GemType.RELAY) return;
        Player p = e.getPlayer();
        // Item Lock blocks consumables; Unfortunate fizzles 50% of eats/drinks
        Long lock = itemLockUntil.get(p.getUniqueId());
        if (lock != null && lock > System.currentTimeMillis() && isLockableConsumable(e.getItem())) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Item Locked!");
            return;
        }
        Long un2 = unfortunateUntil.get(p.getUniqueId());
        if (un2 != null && un2 > System.currentTimeMillis() && Math.random() < 0.5) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.GRAY + GemNames.tx("Unfortunate: it slips from your grasp..."));
        }
    }

    /** Right-clicking an entity: Wealth's Amplification when sneaking; Astra's Unbounded / Astral Void. */
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof LivingEntity)) return;
        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!gems.isGem(inHand)) return;
        if (gems.getType(inHand) == GemType.WEALTH) {
            if (p.isSneaking() && gems.getTier(inHand) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(inHand)) && !isGemDisabled(p))
                castAmplification(p, gems.getEnergy(inHand)); // sneak + right-click on someone counts too
            return;
        }
        // Astra T2 right-click on someone (AstraMath.route): a player is Unbounded, anything else Astral Void; sneaking, Projection
        if (gems.getType(inHand) == GemType.ASTRA && gems.getTier(inHand) >= 2
                && EnergyStage.allowsPowers(gems.getEnergy(inHand)) && !isGemDisabled(p)) {
            astraRight(p, e.getRightClicked() instanceof Player o ? o : null, gems.getEnergy(inHand));
        }
    }

    /** The waiting daggers go on death and on a world change (and the cooldown starts, as if they were thrown). */
    @EventHandler
    public void onAstraVolleyDeath(PlayerDeathEvent e) { endVolley(e.getEntity().getUniqueId(), false); }

    @EventHandler
    public void onAstraVolleyWorld(org.bukkit.event.player.PlayerChangedWorldEvent e) { endVolley(e.getPlayer().getUniqueId(), false); }

    /** Wealth ores: T2 every 3rd ore doubled, Rich Rush doubles, Bonus Ores + Extra EXP. */
    @EventHandler
    public void onOreBreak(BlockBreakEvent e) {
        if (!isOre(e.getBlock().getType())) return;
        Player p = e.getPlayer();
        ItemStack gem = findGem(p);
        if (gem == null) return;
        if (gems.getType(gem) == GemType.FLUX) {
            // T2 Flux banks ore energy (drop the gem to convert it into wattage)
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPassives(gems.getEnergy(gem)) && !isGemDisabled(p)) {
                fluxAdd(fluxOre, p.getUniqueId(), 5);
            }
            // Flow State mining momentum for any Flux holder
            if (EnergyStage.allowsPassives(gems.getEnergy(gem))) {
                flowMine.put(p.getUniqueId(), Math.min(21, fluxOf(flowMine, p.getUniqueId()) + 3));
            }
            return;
        }
        if (gems.getType(gem) != GemType.WEALTH) return;
        if (!EnergyStage.allowsPassives(gems.getEnergy(gem))) return;
        int tier = gems.getTier(gem);
        int c = oreCount.getOrDefault(p.getUniqueId(), 0) + 1;
        oreCount.put(p.getUniqueId(), c);
        boolean dbl = tier >= 2 && c % 3 == 0;
        Long rush = richRushUntil.get(p.getUniqueId());
        boolean rushOn = rush != null && rush > System.currentTimeMillis();
        if (dbl || rushOn) {
            for (ItemStack drop : e.getBlock().getDrops(p.getInventory().getItemInMainHand())) {
                p.getWorld().dropItemNaturally(e.getBlock().getLocation(), drop.clone());
            }
            if (dbl) p.sendMessage(ChatColor.GREEN + "Bonus Ores: every 3rd ore doubled!");
        } else if (Math.random() < 0.15) {
            var drops = e.getBlock().getDrops(p.getInventory().getItemInMainHand());
            if (!drops.isEmpty()) {
                p.getWorld().dropItemNaturally(e.getBlock().getLocation(), drops.iterator().next().clone());
            }
        }
        // Extra EXP
        p.getWorld().spawn(e.getBlock().getLocation(), org.bukkit.entity.ExperienceOrb.class)
                .setExperience(3 + (int) (Math.random() * 5));
    }

    /** Double Debris T2: smelting ancient debris near a T2 Wealth holder yields 2 scrap. */
    @EventHandler
    public void onDebrisSmelt(FurnaceSmeltEvent e) {
        if (e.getSource().getType() != Material.ANCIENT_DEBRIS) return;
        if (e.getResult().getType() != Material.NETHERITE_SCRAP) return;
        for (Entity en : e.getBlock().getWorld().getNearbyEntities(e.getBlock().getLocation(), 10, 10, 10)) {
            if (!(en instanceof Player p)) continue;
            ItemStack gem = findGem(p);
            if (gem != null && gems.getType(gem) == GemType.WEALTH && gems.getTier(gem) >= 2
                    && EnergyStage.allowsPassives(gems.getEnergy(gem))) {
                e.setResult(new ItemStack(Material.NETHERITE_SCRAP, 2));
                return;
            }
        }
    }

    /** Unfortunate fizzles 50% of clicks/placements/breaks; Item Lock blocks consumable use. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onUnfortunateUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Long un = unfortunateUntil.get(p.getUniqueId());
        if (un != null && un > System.currentTimeMillis() && Math.random() < 0.5) {
            Action a = e.getAction();
            if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK
                    || a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
                e.setCancelled(true);
                return;
            }
        }
        Long lock = itemLockUntil.get(p.getUniqueId());
        if (lock != null && lock > System.currentTimeMillis()
                && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            ItemStack held = p.getInventory().getItemInMainHand();
            if (isLockableConsumable(held) || isLockableConsumable(p.getInventory().getItemInOffHand())) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Item Locked!");
            }
        }
    }

    @EventHandler
    public void onUnfortunateBlock(BlockPlaceEvent e) {
        Long un = unfortunateUntil.get(e.getPlayer().getUniqueId());
        if (un != null && un > System.currentTimeMillis() && Math.random() < 0.5) e.setCancelled(true);
    }

    /** Mythic gems (Heretic/Auratus) use a command block material; never let them be placed as blocks. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMythicPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        if (gems.isGem(inHand) && gems.isMythic(inHand)) {
            e.setBuild(false);
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onUnfortunateBreak(BlockBreakEvent e) {
        Long un = unfortunateUntil.get(e.getPlayer().getUniqueId());
        if (un != null && un > System.currentTimeMillis() && Math.random() < 0.5) e.setCancelled(true);
    }

    /** Flux: any mining feeds Flow State momentum. */
    @EventHandler
    public void onFluxMine(BlockBreakEvent e) {
        if (e.isCancelled()) return;
        Player p = e.getPlayer();
        ItemStack gem = findGem(p);
        if (gem == null || gems.getType(gem) != GemType.FLUX) return;
        if (!EnergyStage.allowsPassives(gems.getEnergy(gem)) || isGemDisabled(p)) return;
        flowMine.put(p.getUniqueId(), Math.min(21, fluxOf(flowMine, p.getUniqueId()) + 1));
    }

    /** Flux: hits taken charge the gem; Overdrive counts them toward an empowered hit. */
    @EventHandler
    public void onFluxHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack gem = findGem(p);
        if (gem == null || gems.getType(gem) != GemType.FLUX) return;
        if (!EnergyStage.allowsPassives(gems.getEnergy(gem)) || isGemDisabled(p)) return;
        if (plugin.flux() != null && plugin.flux().selfHurting(p)) return; // Overcharge hurting you doesn't charge the Burst
        fluxAdd(fluxCharge, p.getUniqueId(), 8);
        if (fluxOf(fluxCharge, p.getUniqueId()) >= 100) {
            p.sendMessage(ChatColor.YELLOW + GemNames.tx("Flux fully charged (100)! Unleash Kinetic Burst!"));
            fluxCharge.put(p.getUniqueId(), 100);
        }
        Long od = overdriveUntil.get(p.getUniqueId());
        if (od != null && od > System.currentTimeMillis()) {
            int n = overdriveTaken.getOrDefault(p.getUniqueId(), 0) + 1;
            overdriveTaken.put(p.getUniqueId(), n);
            if (n % 3 == 0) {
                overdriveArmed.put(p.getUniqueId(), true);
                p.sendMessage(ChatColor.YELLOW + "Overdrive charged: your next hit lands hard!");
            }
        }
    }

    /** Flux Tireless: Hunger, Slowness and Weakness never stick. */
    @EventHandler
    public void onFluxImmune(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getNewEffect() == null) return;
        PotionEffectType t = e.getNewEffect().getType();
        if (t != PotionEffectType.HUNGER && t != PotionEffectType.SLOWNESS && t != PotionEffectType.WEAKNESS) return;
        ItemStack gem = findGem(p);
        if (gem != null && gems.getType(gem) == GemType.FLUX
                && EnergyStage.allowsPassives(gems.getEnergy(gem)) && !isGemDisabled(p)) {
            e.setCancelled(true);
        }
    }

    /** Double-tap space Double Jump for Puff holders (consumes the ability cooldown). */
    @EventHandler
    public void onToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL && p.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;
        ItemStack gem = findGem(p);
        if (gem == null || gems.getType(gem) != GemType.PUFF) return;
        if (!EnergyStage.allowsPowers(gems.getEnergy(gem))) return;
        e.setCancelled(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        long rem = cdRemain(p, "doublejump");
        if (rem > 0) return;
        cdStart(p, "doublejump", cooldownMs("doublejump", gems.getEnergy(gem)));
        useDoubleJump(p);
    }

    /** Crop Tramp-Less: Puff holders never trample farmland. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.FARMLAND) return;
        ItemStack gem = findGem(e.getPlayer());
        if (gem != null && gems.getType(gem) == GemType.PUFF && EnergyStage.allowsPassives(gems.getEnergy(gem))) {
            e.setCancelled(true);
        }
    }

    /** Sculk Silence: Puff holders don't set off sculk sensors. */
    @EventHandler
    public void onSculkHear(org.bukkit.event.block.BlockReceiveGameEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack gem = findGem(p);
        if (gem != null && gems.getType(gem) == GemType.PUFF && EnergyStage.allowsPassives(gems.getEnergy(gem))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        if (isProjecting(p)) { e.setCancelled(true); return; }
        if (!plugin.getConfig().getBoolean("passives.puff-no-fall", true)) return;
        ItemStack gem = findGem(p);
        if (gem != null && gems.getType(gem) == GemType.PUFF && EnergyStage.allowsPassives(gems.getEnergy(gem))) {
            e.setCancelled(true);
        }
    }

    // ---------- energy kill/death ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();

        ItemStack heldMythic = null;
        ItemStack mainHeld = victim.getInventory().getItemInMainHand();
        ItemStack offHeld = victim.getInventory().getItemInOffHand();
        if (gems.isMythic(mainHeld)) heldMythic = mainHeld.clone();
        else if (gems.isMythic(offHeld)) heldMythic = offHeld.clone();
        if (heldMythic != null && plugin.getConfig().getBoolean("mythics.drop-on-death", true)) {
            boolean inDrops = false;
            for (ItemStack d : e.getDrops()) {
                if (gems.isMythic(d) && gems.getType(d) == gems.getType(heldMythic)) { inDrops = true; break; }
            }
            if (!inDrops) e.getDrops().add(heldMythic);
            if (gems.isMythic(mainHeld)) victim.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            if (gems.isMythic(offHeld)) victim.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }

        // keep standard gems (soulbound)
        if (plugin.getConfig().getBoolean("protection.keep-on-death", true)) {
            List<ItemStack> toKeep = new ArrayList<>();
            for (ItemStack d : new ArrayList<>(e.getDrops())) {
                if (gems.isGem(d) && !gems.isMythic(d)) {
                    toKeep.add(d.clone());
                    e.getDrops().remove(d);
                }
            }
            if (!toKeep.isEmpty()) {
                pendingRespawnGem.put(victim.getUniqueId(), toKeep.get(0));
            }
        }

        ItemStack vGem = findGem(victim);
        if (vGem != null && !gems.isMythic(vGem)) {
            int en = gems.getEnergy(vGem);
            gems.setEnergy(vGem, en - plugin.getConfig().getInt("energy.death-loss", 1));
        }

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            ItemStack kGem = findGem(killer);
            if (kGem != null && !gems.isMythic(kGem) && !plugin.mythics().hasMythic(killer)) {
                int en = gems.getEnergy(kGem);
                if (en >= plugin.getConfig().getInt("energy.max", 10)
                        && plugin.getConfig().getBoolean("energy.drop-bottle-at-max", true)) {
                    e.getDrops().add(gems.createEnergyBottle());
                    killer.sendMessage(ChatColor.YELLOW + "Max energy: your victim dropped an Energy Bottle.");
                } else {
                    gems.setEnergy(kGem, en + plugin.getConfig().getInt("energy.kill-gain", 1));
                    killer.sendMessage(ChatColor.YELLOW + "+1 energy → " + gems.getEnergy(kGem) + "/10");
                }
                // Soul Healing: Astra killers drink in a little life
                if (gems.getType(kGem) == GemType.ASTRA && EnergyStage.allowsPassives(gems.getEnergy(kGem))) {
                    killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + 4.0));
                }
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        ItemStack kept = pendingRespawnGem.remove(e.getPlayer().getUniqueId());
        if (kept != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (findGem(e.getPlayer()) == null) e.getPlayer().getInventory().addItem(kept);
                else e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), kept);
            }, 5L);
        }
    }

    // ---------- protection ----------

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (fluxCharge(e)) return; // Flux's Q: the Energy Beam's charge, not a drop
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (!plugin.getConfig().getBoolean("protection.prevent-drop", true)) return;
        if (!gems.isGem(dropped)) return;
        if (LooseMythics.droppable(gems, dropped)) return; // Auratus and Heretic can be dropped and handed over now
        if (plugin.finale() != null && plugin.finale().claimsDrop(e.getPlayer(), dropped)) return; // a pedestal drop: the finale's alone
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "Gems are soulbound and cannot be dropped.");
    }

    /** Flux T2: Q starts (or stops) charging the Energy Beam; the gem never leaves your hand (banked ore energy goes in too). */
    private boolean fluxCharge(PlayerDropItemEvent e) {
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (!gems.isGem(dropped) || gems.getType(dropped) != GemType.FLUX || gems.getTier(dropped) < 2 || plugin.flux() == null
                || !EnergyStage.allowsPowers(gems.getEnergy(dropped)) || isGemDisabled(e.getPlayer())) return false;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) plugin.flux().toggle(p); }); // once it's back in hand
        return true;
    }

    @EventHandler
    public void onChestStore(InventoryClickEvent e) {
        if (!plugin.getConfig().getBoolean("protection.prevent-chest-store", true)) return;
        if (e.getClickedInventory() == null) return;
        InventoryType t = e.getClickedInventory().getType();
        boolean isContainer = t == InventoryType.CHEST || t == InventoryType.ENDER_CHEST
                || t == InventoryType.BARREL || t == InventoryType.SHULKER_BOX
                || t == InventoryType.HOPPER || t == InventoryType.DISPENSER || t == InventoryType.DROPPER;
        if (!isContainer) return;
        ItemStack moving = e.getCurrentItem();
        // Auratus and Heretic are the Prophet's: they go in and he pulls them back out (Prophet)
        boolean normal = gems.isGem(moving) && !LooseMythics.droppable(gems, moving)
                || gems.isGem(e.getCursor()) && !LooseMythics.droppable(gems, e.getCursor());
        if (normal) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage(ChatColor.RED + "Gems cannot be stored in containers.");
        }
    }
}
