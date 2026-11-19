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
import org.bukkit.event.inventory.InventoryCloseEvent;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
    private final Map<UUID, Long> fluxOverdriveCooldown = new HashMap<>();
    private final Map<UUID, Integer> hitCount = new HashMap<>();
    private final Map<UUID, Boolean> chadArmed = new HashMap<>();
    private final Map<UUID, ItemStack> pendingRespawnGem = new HashMap<>();
    // Strength systems (user-supplied kit)
    private final Map<UUID, UUID> bountyTarget = new HashMap<>();
    private final Map<UUID, Long> nullifiedUntil = new HashMap<>();
    private final Map<UUID, Long> chadUntil = new HashMap<>();
    private final Map<UUID, Integer> critCount = new HashMap<>();
    private final Map<UUID, Long> frailUntil = new HashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    // Fire systems (user-supplied kit)
    private final Map<UUID, Long> fireballChargeStart = new HashMap<>();
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
    /** One running Blur: who it is on, when its caster last hit them, and the clones attacking them. */
    private static final class BlurRun {
        UUID victim;
        long lastHit;
        int energy;
        final List<org.bukkit.entity.Vex> clones = new ArrayList<>();
    }
    private final Map<UUID, BlurRun> blurRuns = new HashMap<>();
    private final Map<UUID, UUID> blurCloneVictim = new HashMap<>();
    private final Map<UUID, SpeedStorm> storms = new HashMap<>();
    private final Map<UUID, Long> stormLastStrike = new HashMap<>();
    private final Map<UUID, Long> amplifyCooldown = new HashMap<>();
    // Astra systems (user-supplied kit). Abilities are offhand-right-click only.
    private final Map<UUID, Long> gemDisabledUntil = new HashMap<>();
    private final Map<UUID, UUID> unboundedHost = new HashMap<>();
    private final Map<UUID, Long> unboundedLast = new HashMap<>();
    private final Map<UUID, org.bukkit.entity.Zombie> projectionBody = new HashMap<>();
    private final Map<UUID, Long> voidCooldown = new HashMap<>();
    private final Set<UUID> daggerShots = new HashSet<>();
    // Astra Daggers (T2): hovering semi-circle system
    private final Map<UUID, List<ItemDisplay>> astraDaggers = new HashMap<>();
    private final Map<UUID, Integer> astraDaggerIndex = new HashMap<>();
    private final Map<UUID, Long> astraDaggerFireCooldown = new HashMap<>();
    private static final int ASTRA_DAGGER_CMD = 230; // nautilus shell spiritdagger model
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
    private final Set<UUID> stunShots = new HashSet<>();

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
            case "chad", "nullify", "itemlock", "storm", "heartdrainer", "circle", "beam", "void" -> 120;
            case "fireball", "overdrive", "cozy" -> 180;
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

    /**
     * Flat damage from a standard-gem ability (Crisp, Breezy Bash, Dash, Kinetic Burst, Energy Beam).
     * The base numbers were written for unarmored targets and barely scratch enchanted diamond, so they
     * are scaled by ability-damage.multiplier; onAbilityDamageCap then limits the total after the
     * holder's on-hit bonuses (Overdrive, Ground) so no single ability hit can one-shot through armor.
     */
    private void abilityDamage(LivingEntity victim, double base, Player source) {
        double scaled = base * Math.max(0.0, plugin.getConfig().getDouble("ability-damage.multiplier", 3.0));
        abilityHits++;
        try {
            victim.damage(scaled, source);
        } finally {
            abilityHits--;
        }
    }

    /** The same scaled, capped ability damage for mythic powers (the Auratus' chains and slam). */
    void abilityHit(LivingEntity victim, double base, Player source) {
        abilityDamage(victim, base, source);
    }

    /** Runs after onHit (NORMAL) has applied the holder's bonuses. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAbilityDamageCap(EntityDamageByEntityEvent e) {
        if (abilityHits <= 0) return;
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
                if (type == GemType.STRENGTH) {
                    updateBountyCompass(p);
                    drawTrackingLine(p);
                    proximityHint(p, energy);
                }
            }
        }
        tickAstraDaggers();
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
            case PUFF:
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, false, false));
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
        boolean t2 = tier >= 2;
        List<String> bar = new ArrayList<>();
        switch (type) {
            case STRENGTH -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "nullify"), false));
                    bar.add(hudEntry(icon(type, "right"), cdRemain(p, "chad"), chadUntil.getOrDefault(id, 0L) > now));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "bounty"), false));
            }
            case FIRE -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "fireball"), fireballChargeStart.containsKey(id)));
                    bar.add(hudEntry(icon(type, "right"), remain(cozyCooldown, id), isLiveCampfire(id, now)));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "crisp"), false));
            }
            case SPEED -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "blur"), blurRuns.containsKey(id)));
                    bar.add(ChargePools.hud(plugin, p, "storm", icon(type, "right")));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "thunderstep"), false));
            }
            case WEALTH -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "itemlock"), false));
                    bar.add(hudEntry(icon(type, "right"), cdRemain(p, "richrush"), richRushUntil.getOrDefault(id, 0L) > now));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "pockets"), pockets.containsKey(id)
                        && p.getOpenInventory() != null && p.getOpenInventory().getTopInventory().equals(pockets.get(id))));
            }
            case PUFF -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "bash"), false));
                    bar.add(hudEntry(icon(type, "right"), cdRemain(p, "dash"), false));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "doublejump"), false));
            }
            case ASTRA -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "daggers"), false));
                    bar.add(hudEntry(icon(type, "right"), cdRemain(p, "projection"), projectionBody.containsKey(id)));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "drift"), unboundedHost.containsKey(id)));
            }
            case LIFE -> {
                if (t2) {
                    bar.add(hudEntry(icon(type, "left"), cdRemain(p, "heartdrainer"), false));
                    bar.add(hudEntry(icon(type, "right"), cdRemain(p, "circle"), false));
                }
                bar.add(hudEntry(icon(type, "ability"), cdRemain(p, "vortex"), false));
            }
            case FLUX -> {
                sendFluxActionbar(p, now);
                return;
            }
            default -> {
                return;
            }
        }
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(String.join("  ", bar)));
    }

    /** One ability on the action bar: icons and timers only, no words (HudStatus). */
    private String hudEntry(String icon, long remainMs, boolean active) {
        return HudStatus.entry(icon, remainMs, active);
    }

    private void sendFluxActionbar(Player p, long now) {
        UUID id = p.getUniqueId();
        String beam = hudEntry(icon(GemType.FLUX, "left"), remain(fluxBeamCooldown, id), false);
        int charge = fluxOf(fluxCharge, id);
        long burstCooldown = remain(fluxBurstCooldown, id);
        String burstIcon = icon(GemType.FLUX, "ability");
        // Kinetic Burst: a timer while it cools down, then its charge in % (dimmed) until it is full.
        String burst = burstCooldown > 0 ? hudEntry(burstIcon, burstCooldown, false)
                : (charge >= 100 ? hudEntry(burstIcon, 0, false) : "§7" + burstIcon + " §b" + charge + "%");
        String watts = "§f" + tier2Glyph() + " §3" + String.format(java.util.Locale.ROOT, "%.1f", (double) fluxOf(fluxWatts, id));
        String overdrive = hudEntry(icon(GemType.FLUX, "right"), remain(fluxOverdriveCooldown, id), overdriveUntil.getOrDefault(id, 0L) > now);

        String msg = beam + "  " + burst + "  " + watts + "  " + overdrive;
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

    /** Chad dots: 3 black dots ahead of the player; each crit turns one red. */
    private void drawChadDots(Player p, long now) {
        Long until = chadUntil.get(p.getUniqueId());
        if (until == null || until < now) return;
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
            org.bukkit.Color col = red ? org.bukkit.Color.RED : org.bukkit.Color.BLACK;
            p.getWorld().spawnParticle(Particle.DUST, dot, 1,
                    new Particle.DustOptions(col, 1.0f));
        }
    }

    /** Tracking line: plain dotted line from tracker toward the bounty target. */
    private void drawTrackingLine(Player p) {
        UUID tracked = bountyTarget.get(p.getUniqueId());
        if (tracked == null) return;
        Player t = Bukkit.getPlayer(tracked);
        if (t == null || !t.isOnline() || !t.getWorld().equals(p.getWorld())) return;
        Location from = p.getEyeLocation();
        Location to = t.getEyeLocation();
        double dist = from.distance(to);
        if (dist < 3 || dist > 200) return;
        Vector dir = to.toVector().subtract(from.toVector()).normalize();
        int points = Math.min(50, (int) (dist / 2));
        for (int i = 1; i <= points; i++) {
            Location at = from.clone().add(dir.clone().multiply(i * 2));
            p.getWorld().spawnParticle(Particle.CRIT, at, 1, 0, 0, 0, 0);
        }
    }

    /** Bounty tracking alerts in pure red, overriding the cooldown bar for 3s. */
    private void proximityHint(Player p, int energy) {
        if (!EnergyStage.allowsPowers(energy)) return;
        long alertUntil = System.currentTimeMillis() + 3000L;
        UUID tracked = bountyTarget.get(p.getUniqueId());
        Player t = tracked == null ? null : Bukkit.getPlayer(tracked);
        if (t != null && t.isOnline() && t.getWorld().equals(p.getWorld())) {
            double d2 = p.getLocation().distanceSquared(t.getLocation());
            if (d2 < 50 * 50) {
                p.sendActionBar(Component.text("§cYou can feel your victim closer…"));
                t.sendActionBar(Component.text("§cYour hunter is nearby"));
            } else {
                int m = (int) Math.sqrt(d2);
                p.sendActionBar(Component.text("§cYou feel your victim nearby... (" + m + "m)"));
                t.sendActionBar(Component.text("§cSomeone is watching you…"));
            }
            trackAlertUntil.put(p.getUniqueId(), alertUntil);
            trackAlertUntil.put(t.getUniqueId(), alertUntil);
            return;
        }
        // No bounty: fall back to the nearest player, same red treatment.
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

    private void updateBountyCompass(Player p) {
        UUID tracked = bountyTarget.get(p.getUniqueId());
        if (tracked == null) return;
        Player t = Bukkit.getPlayer(tracked);
        if (t != null && t.isOnline() && t.getWorld().equals(p.getWorld())) {
            p.setCompassTarget(t.getLocation());
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

    public void setBounty(Player tracker, Player target) {
        bountyTarget.put(tracker.getUniqueId(), target.getUniqueId());
        tracker.setCompassTarget(target.getLocation());
    }

    public Player getBounty(Player tracker) {
        UUID id = bountyTarget.get(tracker.getUniqueId());
        return (id == null) ? null : Bukkit.getPlayer(id);
    }

    // ---------- bounty board (Strength tracker UI) ----------

    private final Map<UUID, UUID> bountyOffer = new HashMap<>();
    private final Map<UUID, Map<Integer, UUID>> boardSlots = new HashMap<>();
    private final Map<UUID, Long> trackAlertUntil = new HashMap<>();

    private static class BoardHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static class OfferHolder implements InventoryHolder {
        final UUID target;
        OfferHolder(UUID target) { this.target = target; }
        @Override public Inventory getInventory() { return null; }
    }

    /** Large chest of player heads; clicking one opens the hopper offering. */
    public void openBountyBoard(Player p) {
        Inventory board = Bukkit.createInventory(new BoardHolder(), 54, "Bounty Board");
        Map<Integer, UUID> slots = new HashMap<>();
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId()) || slot >= 54) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(o);
            meta.setDisplayName(ChatColor.RED + o.getName());
            head.setItemMeta(meta);
            board.setItem(slot, head);
            slots.put(slot, o.getUniqueId());
            slot++;
        }
        if (slots.isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "No one to hunt right now.");
            return;
        }
        boardSlots.put(p.getUniqueId(), slots);
        p.openInventory(board);
    }

    private void openBountyOffer(Player p, Player target) {
        bountyOffer.put(p.getUniqueId(), target.getUniqueId());
        Inventory hopper = Bukkit.createInventory(new OfferHolder(target.getUniqueId()), InventoryType.HOPPER,
                "Offer for " + target.getName());
        p.openInventory(hopper);
        p.sendMessage(ChatColor.GRAY + "Place something " + target.getName() + " owned, then close.");
    }

    /** Owner name from provenance lore ("STOLEN/GIFTED/STORED by X"), or null. */
    private String provenanceName(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getLore() == null) return null;
        for (String line : item.getItemMeta().getLore()) {
            String s = ChatColor.stripColor(line).trim();
            String low = s.toLowerCase();
            for (String prefix : new String[]{"stolen by ", "gifted by ", "stored by "}) {
                if (low.startsWith(prefix)) return s.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    @EventHandler
    public void onBountyClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof BoardHolder) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof BoardHolder)) return;
            UUID targetId = boardSlots.getOrDefault(p.getUniqueId(), Map.of()).get(e.getSlot());
            if (targetId == null) return;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                p.sendMessage(ChatColor.GRAY + "They slipped away.");
                p.closeInventory();
                return;
            }
            openBountyOffer(p, target);
            return;
        }
        // Offer hopper: let players arrange items freely; validated on close.
    }

    @EventHandler
    public void onBountyClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof OfferHolder offer)) return;
        UUID targetId = bountyOffer.remove(p.getUniqueId());
        if (targetId == null || !targetId.equals(offer.target)) targetId = offer.target;
        Player target = Bukkit.getPlayer(targetId);
        List<ItemStack> leftovers = new ArrayList<>();
        boolean valid = false;
        for (ItemStack s : e.getInventory().getContents()) {
            if (s == null || s.getType() == Material.AIR) continue;
            leftovers.add(s.clone());
            if (valid || target == null) continue;
            if (s.getType() == Material.PLAYER_HEAD && s.getItemMeta() instanceof SkullMeta skull
                    && skull.getOwningPlayer() != null && skull.getOwningPlayer().getUniqueId() != null
                    && skull.getOwningPlayer().getUniqueId().equals(targetId)) {
                valid = true;
            } else {
                String owner = provenanceName(s);
                if (owner != null && target != null && owner.equalsIgnoreCase(target.getName())) valid = true;
            }
        }
        e.getInventory().clear();
        for (ItemStack s : leftovers) {
            if (p.getInventory().firstEmpty() == -1) p.getWorld().dropItemNaturally(p.getLocation(), s);
            else p.getInventory().addItem(s);
        }
        if (target == null || !target.isOnline()) {
            p.sendMessage(ChatColor.GRAY + "They slipped away.");
            return;
        }
        if (!valid) {
            p.sendMessage(ChatColor.RED + "The trail needs something " + target.getName() + " owned.");
            return;
        }
        ItemStack bountyGem = findGem(p);
        int bountyEnergy = bountyGem == null ? 5 : gems.getEnergy(bountyGem);
        cdStart(p, "bounty", cooldownMs("bounty", bountyEnergy));
        setBounty(p, target);
        usedOn(p, "Bounty Hunter", target.getName());
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_EYE_DEATH, 1, 1);
        target.sendMessage(ChatColor.GRAY + "You feel like you are being watched...");
    }

    @EventHandler
    public void onBountyQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        bountyOffer.remove(p.getUniqueId());
        boardSlots.remove(p.getUniqueId());
        trackAlertUntil.remove(p.getUniqueId());
        clearAstraDaggers(p.getUniqueId());
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof OfferHolder) {
            for (ItemStack s : p.getOpenInventory().getTopInventory().getContents()) {
                if (s != null && s.getType() != Material.AIR) p.getWorld().dropItemNaturally(p.getLocation(), s);
            }
            p.getOpenInventory().getTopInventory().clear();
        }
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

        // Projecting ghosts return on any right-click with the gem
        if (right && isProjecting(p)) {
            returnToBody(p);
            return;
        }
        if (isGemDisabled(p)) {
            p.sendMessage(ChatColor.DARK_PURPLE + "Your gem is disabled.");
            return;
        }

        if (!EnergyStage.allowsPowers(energy)) {
            if (right || (left && type == GemType.STRENGTH)) {
                // Silenced — no chat spam
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
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

        // Strength routing on RIGHT click: abilities are offhand-only, so main-hand
        // gem + head only hints. Chad Strength (power) works from the main hand.
        if (type == GemType.STRENGTH) {
            Player headTarget = headTargetFromOffhand(p);
            if (headTarget != null) {
                // Silenced — no chat spam
                return;
            }
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

        // Wealth routing: Item Lock [Left-click] + Rich Rush [Right-click] (T2 powers).
        // Pockets (ability) is offhand-only; Amplification is right-click on entity.
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
            if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                if (targetedEntity(p, 5) != null) return;
            } else if (targetedEntity(p, 5) != null) {
                return;
            }
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
                useBreezySlam(p, energy);
                return;
            }
            if (!right) return;
            long rem2 = cdRemain(p, "dash");
            if (rem2 > 0) { /* Silenced */ return; }
            cdStart(p, "dash", cooldownMs("dash", energy));
            useDash(p, energy);
            return;
        }

        // Astra routing: Daggers [Left-click] + Projection [Right-click] (T2 powers).
        // Drift (ability) is offhand-only; Void is right-click on entity.
        if (type == GemType.ASTRA) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = cdRemain(p, "daggers");
                if (rem > 0) { /* Silenced */ return; }
                cdStart(p, "daggers", cooldownMs("daggers", energy));
                handleAstraDaggers(p, energy);
                return;
            }
            if (targetedEntity(p, 5) != null) return;
            long rem = cdRemain(p, "projection");
            if (rem > 0) { /* Silenced */ return; }
            cdStart(p, "projection", cooldownMs("projection", energy));
            useProjection(p, energy);
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
            cdStart(p, "circle", cooldownMs("circle", energy));
            useCircleOfLife(p, energy);
            return;
        }

        // Flux routing: Energy Beam [Left-click] + Kinetic Overdrive [Right-click] (T2 powers).
        // Kinetic Burst (ability) is offhand-only.
        if (type == GemType.FLUX) {
            if (tier < 2) {
                // Silenced — no chat spam
                return;
            }
            if (left) {
                long rem = remain(fluxBeamCooldown, p.getUniqueId());
                if (rem > 0) { /* Silenced */ return; }
                fluxBeamCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("beam", energy));
                useEnergyBeam(p, energy);
                return;
            }
            long rem = remain(fluxOverdriveCooldown, p.getUniqueId());
            if (rem > 0) { /* Silenced */ return; }
            fluxOverdriveCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("overdrive", energy));
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
            openBountyBoard(p);
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

    private Player headTargetFromMainhand(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main == null || gems.isGem(main)) return null;
        if ((main.getType() == Material.PLAYER_HEAD || main.getType().name().endsWith("_HEAD"))
                && main.getItemMeta() instanceof SkullMeta skull && skull.getOwningPlayer() != null) {
            String name = skull.getOwningPlayer().getName();
            if (name != null) {
                Player t = Bukkit.getPlayerExact(name);
                if (t != null) return t;
            }
            if (skull.getOwningPlayer().getUniqueId() != null) {
                return Bukkit.getPlayer(skull.getOwningPlayer().getUniqueId());
            }
        }
        return null;
    }

    private Player headTargetFromOffhand(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off == null) return null;
        if ((off.getType() == Material.PLAYER_HEAD || off.getType().name().endsWith("_HEAD"))
                && off.getItemMeta() instanceof SkullMeta skull) {
            if (skull.getOwningPlayer() != null && skull.getOwningPlayer().getUniqueId() != null) {
                Player t = Bukkit.getPlayer(skull.getOwningPlayer().getUniqueId());
                if (t != null) return t;
                // offline owner: still allow by name lookup attempt
            }
            if (skull.getOwningPlayer() != null && skull.getOwningPlayer().getName() != null) {
                return Bukkit.getPlayerExact(skull.getOwningPlayer().getName());
            }
        }
        return null;
    }

    /** Nullify [Left Click]: strip Strength, Speed, and Fire Resistance from untrusted opponents in radius. */
    private void useNullify(Player p, int energy) {
        int radius = 12;
        int cleared = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (isTrusted(p, o)) continue;
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.2f);
        usedGroup(p, "Nullify", cleared);
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
            o.sendMessage(ChatColor.RED + "Chad Strength: your 4th hit deals double damage!");
            allies++;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.1f);
        usedGroup(p, "Chad Strength", allies + 1);
    }

    // ================= FIRE (user kit) =================

    /** Fireball [Left Click]: first click starts charge, second click unleashes. No self damage. */
    private void handleFireballClick(Player p, int energy) {
        UUID id = p.getUniqueId();
        Long started = fireballChargeStart.get(id);
        if (started == null) {
            if (cdRemain(p, "fireball") > 0) return; // still cooling down: cannot start a new charge
            fireballChargeStart.put(id, System.currentTimeMillis());
            p.sendMessage(ChatColor.GOLD + "Fireball charging... left-click again to unleash.");
            p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 1, 1);
            showFireballBar(p);
            return;
        }
        long elapsed = System.currentTimeMillis() - started;
        fireballChargeStart.remove(id);
        hideFireballBar(p);
        double level = fireballLevel(p, elapsed);
        if (level < 0.15) {
            p.sendMessage(ChatColor.GRAY + "Fireball fizzled (too quick). Hold the charge a little longer.");
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1, 1);
        used(p, "Fireball");
        cdStart(p, "fireball", cooldownMs("fireball", energy));
    }

    /** Charge level 0..1 after holding for {@code elapsed} ms. Shared by the bossbar and the release. */
    private double fireballLevel(Player p, long elapsed) {
        boolean onFireBlock = isOnFireThemedBlock(p);
        double fullMs = onFireBlock ? 1500.0 : 3000.0;
        double level = Math.min(1.0, elapsed / fullMs);
        if (!onFireBlock && elapsed > 8000) {
            // overcharge decay when not on fire blocks
            level = Math.max(0.3, 1.0 - ((elapsed - 8000) / 5000.0));
        }
        return level;
    }

    /** Bossbar that tracks the Fireball charge until it is released, or the player quits or dies. */
    private void showFireballBar(Player p) {
        if (!plugin.getConfig().getBoolean("fire.fireball.charge-bossbar", true)) return;
        UUID id = p.getUniqueId();
        hideFireballBar(p);
        var bar = net.kyori.adventure.bossbar.BossBar.bossBar(Component.empty(), 0f,
                net.kyori.adventure.bossbar.BossBar.Color.YELLOW, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
        fireballBars.put(id, bar);
        p.showBossBar(bar);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                Long started = fireballChargeStart.get(id);
                if (started == null || !p.isOnline() || fireballBars.get(id) != bar) {
                    p.hideBossBar(bar);
                    fireballBars.remove(id, bar);
                    cancel();
                    return;
                }
                long elapsed = System.currentTimeMillis() - started;
                double level = fireballLevel(p, elapsed);
                int percent = (int) Math.round(level * 100);
                boolean fading = level < 1.0 && elapsed > 8000;
                bar.progress((float) Math.max(0.0, Math.min(1.0, level)));
                if (level < 0.15) {
                    bar.color(net.kyori.adventure.bossbar.BossBar.Color.WHITE);
                    bar.name(Component.text("Fireball charging " + percent + "% (too weak to fire)",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY));
                } else if (level >= 1.0) {
                    bar.color(net.kyori.adventure.bossbar.BossBar.Color.RED);
                    bar.name(Component.text("Fireball FULLY CHARGED - left-click to unleash",
                            net.kyori.adventure.text.format.NamedTextColor.RED));
                } else {
                    bar.color(net.kyori.adventure.bossbar.BossBar.Color.YELLOW);
                    bar.name(Component.text((fading ? "Fireball fading " : "Fireball charging ") + percent + "%",
                            net.kyori.adventure.text.format.NamedTextColor.GOLD));
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
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
        hideFireballBar(e.getPlayer());
    }

    @EventHandler
    public void onFireballChargeDeath(PlayerDeathEvent e) {
        fireballChargeStart.remove(e.getEntity().getUniqueId());
        hideFireballBar(e.getEntity());
    }

    private boolean isOnFireThemedBlock(Material m) {
        return m == Material.OBSIDIAN || m == Material.MAGMA_BLOCK || m == Material.NETHERRACK
                || m == Material.SOUL_SAND || m == Material.SOUL_SOIL || m == Material.CRYING_OBSIDIAN
                || m == Material.BLACKSTONE || m == Material.BASALT || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.LAVA;
    }

    private boolean isOnFireThemedBlock(Player p) {
        Block under = p.getLocation().getBlock().getRelative(0, -1, 0);
        Block at = p.getLocation().getBlock();
        return isOnFireThemedBlock(under.getType()) || isOnFireThemedBlock(at.getType());
    }

    /** Crisp [offhand right-click]: Nether-ify area, hurt untrusted, smelt drops. */
    private void useCrisp(Player p, int tier, int energy) {
        int radius = (tier >= 2) ? 8 : 6;
        Location c = p.getLocation();
        World w = p.getWorld();
        int converted = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                Block top = w.getHighestBlockAt(c.getBlockX() + dx, c.getBlockZ() + dz);
                Block ground = top.getRelative(0, -1, 0);
                Material gm = ground.getType();
                if (gm == Material.AIR || gm == Material.LAVA || gm == Material.BEDROCK) continue;
                if (gm.name().contains("NETHER") || gm == Material.MAGMA_BLOCK || gm == Material.SOUL_SAND) continue;
                double r = Math.random();
                Material to = (r < 0.7) ? Material.NETHERRACK : (r < 0.85 ? Material.SOUL_SAND : Material.MAGMA_BLOCK);
                ground.setType(to, false);
                converted++;
                if (Math.random() < 0.04 && top.getType() == Material.AIR) top.setType(Material.FIRE, false);
            }
        }
        // lava pockets
        for (int i = 0; i < 3; i++) {
            Block b = c.clone().add((Math.random() - 0.5) * radius, 0, (Math.random() - 0.5) * radius).getBlock();
            Block top = w.getHighestBlockAt(b.getX(), b.getZ());
            if (top.getType() == Material.AIR) top.setType(Material.LAVA, false);
        }
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
        w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1, 0.7f);
        w.spawnParticle(Particle.FLAME, c, 80, radius / 2.0, 2, radius / 2.0, 0.1);
        used(p, "Crisp");
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
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_CAMPFIRE_CRACKLE, 1, 1);
        used(p, "Cozy Campfire");
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

    /** Tick Astra Daggers: update hovering positions and handle firing. */
    private void tickAstraDaggers() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack gem = heldGem(p);
            if (gem == null || gems.getType(gem) != GemType.ASTRA || gems.getTier(gem) < 2) {
                clearAstraDaggers(p.getUniqueId());
                continue;
            }
            List<ItemDisplay> daggers = astraDaggers.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            // Spawn daggers if not exist
            if (daggers.isEmpty()) spawnAstraDaggers(p);
            // Update positions in semi-circle
            updateAstraDaggerPositions(p);
            // Handle firing cooldown
            Long fireCd = astraDaggerFireCooldown.get(p.getUniqueId());
            if (fireCd != null && fireCd > now) continue;
            // Check for left-click (handled in onInteract, we just track the action)
        }
    }

    private void spawnAstraDaggers(Player p) {
        List<ItemDisplay> daggers = astraDaggers.get(p.getUniqueId());
        if (daggers == null) return;
        World w = p.getWorld();
        if (w == null) return;
        for (int i = 0; i < 5; i++) {
            ItemDisplay dagger = w.spawn(p.getLocation(), ItemDisplay.class, d -> {
                d.setItemStack(createAstraDaggerItem());
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
            });
            daggers.add(dagger);
        }
        astraDaggerIndex.put(p.getUniqueId(), 0);
    }

    private ItemStack createAstraDaggerItem() {
        ItemStack item = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(ASTRA_DAGGER_CMD);
        item.setItemMeta(meta);
        return item;
    }

    private void updateAstraDaggerPositions(Player p) {
        List<ItemDisplay> daggers = astraDaggers.get(p.getUniqueId());
        if (daggers == null || daggers.isEmpty()) return;
        Location center = p.getLocation().add(0, 1.5, 0);
        Vector forward = p.getLocation().getDirection().setY(0).normalize();
        if (forward.lengthSquared() < 0.01) forward = new Vector(1, 0, 0);
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double radius = 1.5;
        long time = System.currentTimeMillis();
        double orbitSpeed = 0.002;
        for (int i = 0; i < daggers.size(); i++) {
            ItemDisplay dagger = daggers.get(i);
            if (!dagger.isValid()) continue;
            // Semi-circle in front of player (spread across 180 degrees)
            double angle = Math.PI * (i / 4.0) - Math.PI / 2; // -90 to +90 degrees
            double orbitAngle = time * orbitSpeed;
            double x = Math.cos(angle + orbitAngle);
            double z = Math.sin(angle + orbitAngle);
            Vector offset = right.clone().multiply(x * radius).add(forward.clone().multiply(z * radius * 0.5));
            Location targetLoc = center.clone().add(offset);
            targetLoc.setY(center.getY() + Math.sin(time * 0.005 + i) * 0.2); // gentle bob
            dagger.teleport(targetLoc);
            // Rotate dagger to face outward
            Vector faceDir = targetLoc.toVector().subtract(center.toVector()).setY(0);
            if (faceDir.lengthSquared() > 0.01) {
                faceDir.normalize();
                Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, -1),
                        new Vector3f((float) faceDir.getX(), 0, (float) faceDir.getZ()));
                Transformation t = new Transformation(new Vector3f(), rot,
                        new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf());
                dagger.setTransformation(t);
            }
        }
    }

    private void clearAstraDaggers(UUID id) {
        List<ItemDisplay> daggers = astraDaggers.remove(id);
        if (daggers != null) {
            for (ItemDisplay d : daggers) if (d.isValid()) d.remove();
        }
        astraDaggerIndex.remove(id);
        astraDaggerFireCooldown.remove(id);
    }

    /** Fire one astra dagger in the direction the player is looking. */
    public void fireAstraDagger(Player p) {
        List<ItemDisplay> daggers = astraDaggers.get(p.getUniqueId());
        if (daggers == null || daggers.isEmpty()) return;
        int index = astraDaggerIndex.getOrDefault(p.getUniqueId(), 0);
        if (index >= daggers.size()) return;
        ItemDisplay dagger = daggers.get(index);
        if (!dagger.isValid()) return;
        // Remove from hovering list
        daggers.remove(index);
        // Launch as projectile
        World w = p.getWorld();
        Location launchLoc = dagger.getLocation().clone();
        dagger.remove();
        // Spawn spectral arrow with dagger model
        org.bukkit.entity.SpectralArrow arrow = w.spawn(launchLoc, org.bukkit.entity.SpectralArrow.class);
        Vector dir = p.getLocation().getDirection().normalize();
        double speed = plugin.getConfig().getDouble("astra.dagger-speed", 3.0);
        arrow.setVelocity(dir.multiply(speed));
        arrow.setDamage(plugin.getConfig().getDouble("astra.dagger-damage", 5.0));
        arrow.setCritical(true);
        daggerShots.add(arrow.getUniqueId());
        // Sound & particles
        w.playSound(launchLoc, Sound.ENTITY_ARROW_SHOOT, 1, 1.5f);
        w.spawnParticle(Particle.PORTAL, launchLoc, 10, 0.2, 0.2, 0.2, 0.1);
        // Cooldown between shots
        astraDaggerFireCooldown.put(p.getUniqueId(), System.currentTimeMillis() + 300L);
        // Regenerate dagger after a delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            List<ItemDisplay> current = astraDaggers.get(p.getUniqueId());
            if (current == null) return;
            if (current.size() < 5) {
                ItemDisplay newDagger = w.spawn(p.getLocation(), ItemDisplay.class, d -> {
                    d.setItemStack(createAstraDaggerItem());
                    d.setPersistent(false);
                    d.setBrightness(new Display.Brightness(15, 15));
                });
                current.add(newDagger);
            }
        }, 60L); // 3 seconds
    }

    // ================= WEALTH (user kit, abilities offhand-only) =================

    private void openPockets(Player p) {
        org.bukkit.inventory.Inventory inv = pockets.get(p.getUniqueId());
        if (inv == null) {
            inv = Bukkit.createInventory(null, 9, "Pockets");
            pockets.put(p.getUniqueId(), inv);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
        used(p, "Pockets");
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
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1, 1);
        usedGroup(p, "Item Lock", locked);
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 0.7f);
        used(p, "Rich Rush");
    }

    /** Amplification [Right-click entity]: +1 to all enchants of self + trusted for 30s. */
    private void useAmplification(Player caster, Player anchor, int energy) {
        int radius = 15;
        List<Player> targets = new ArrayList<>();
        targets.add(caster);
        for (Entity e : caster.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && isTrusted(caster, o)) targets.add(o);
        }
        for (Player t : targets) {
            boostEnchants(t);
            if (!t.getUniqueId().equals(caster.getUniqueId())) {
                t.sendMessage(ChatColor.GREEN + "Amplification: enchants +1 for 30s!");
            }
            t.getWorld().playSound(t.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1, 1.2f);
        }
        used(caster, "Amplification");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player t : targets) revertEnchantBoost(t);
        }, 30 * 20L);
    }

    private final Map<UUID, List<BoostRecord>> boostRecords = new HashMap<>();

    private record BoostRecord(int slot, boolean armor, org.bukkit.enchantments.Enchantment ench, int original) {}

    private void boostEnchants(Player p) {
        List<BoostRecord> recs = new ArrayList<>();
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() == Material.AIR || gems.isGem(s)) continue;
            for (var en : new java.util.HashMap<>(s.getEnchantments()).entrySet()) {
                int orig = en.getValue();
                int next = Math.min(10, orig + 1);
                if (next == orig) continue;
                s.addUnsafeEnchantment(en.getKey(), next);
                recs.add(new BoostRecord(i, false, en.getKey(), orig));
            }
        }
        ItemStack[] armor = p.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack s = armor[i];
            if (s == null || s.getType() == Material.AIR) continue;
            for (var en : new java.util.HashMap<>(s.getEnchantments()).entrySet()) {
                int orig = en.getValue();
                int next = Math.min(10, orig + 1);
                if (next == orig) continue;
                s.addUnsafeEnchantment(en.getKey(), next);
                recs.add(new BoostRecord(i, true, en.getKey(), orig));
            }
        }
        if (!recs.isEmpty()) boostRecords.put(p.getUniqueId(), recs);
    }

    private void revertEnchantBoost(Player p) {
        List<BoostRecord> recs = boostRecords.remove(p.getUniqueId());
        if (recs == null) return;
        ItemStack[] contents = p.getInventory().getContents();
        ItemStack[] armor = p.getInventory().getArmorContents();
        for (BoostRecord r : recs) {
            ItemStack s = r.armor ? (r.slot < armor.length ? armor[r.slot] : null)
                    : (r.slot < contents.length ? contents[r.slot] : null);
            if (s == null || s.getType() == Material.AIR) continue;
            int cur = s.getEnchantmentLevel(r.ench);
            if (cur == r.original + 1) {
                if (r.original <= 0) s.removeEnchantment(r.ench);
                else s.addUnsafeEnchantment(r.ench, r.original);
            }
        }
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 40, 0.5, 1, 0.5, 0.2);
        used(p, "Thunderstep");
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.2f);
        p.sendMessage(ChatColor.AQUA + "Speedy Storm unleashed. Charges left: "
                + ChargePools.available(plugin, p, "storm") + "/" + ChargePools.maxCharges(plugin, "storm") + ".");
        used(p, "Speedy Storm");
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
            Long last = stormLastStrike.get(ownerId);
            if (last != null && now - last < 2000) continue;
            stormLastStrike.put(ownerId, now);
            for (int i = 0; i < 3; i++) {
                Location at = storm.loc.clone().add((Math.random() - 0.5) * storm.radius * 2, 0,
                        (Math.random() - 0.5) * storm.radius * 2);
                at.setY(w.getHighestBlockYAt(at) + 1);
                w.strikeLightningEffect(at);
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
        for (int i = 0; i < 3; i++) {
            Location at = victim.getLocation().clone().add((Math.random() - 0.5) * 4, 1, (Math.random() - 0.5) * 4);
            org.bukkit.entity.Vex vex;
            try {
                vex = p.getWorld().spawn(at, org.bukkit.entity.Vex.class);
            } catch (Exception ex) { continue; }
            vex.setPersistent(false); // never saved: a restart cannot leave stray clones behind
            blurCloneVictim.put(vex.getUniqueId(), run.victim);
            run.clones.add(vex);
            try { vex.setTarget(victim); } catch (Exception ignored) { }
            vex.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1));
            p.getWorld().spawnParticle(Particle.PORTAL, at, 30, 0.5, 0.5, 0.5, 0.2);
        }
        blurRuns.put(id, run);
        usedOn(p, "Blur", victim.getName());
    }

    /** Ends a Blur: clones vanish and the cooldown starts now, not when it was activated. */
    private void endBlur(UUID ownerId) {
        BlurRun run = blurRuns.remove(ownerId);
        if (run == null) return;
        for (org.bukkit.entity.Vex clone : run.clones) {
            blurCloneVictim.remove(clone.getUniqueId());
            if (clone.isValid()) {
                clone.getWorld().spawnParticle(Particle.PORTAL, clone.getLocation(), 20, 0.3, 0.3, 0.3, 0.2);
                clone.remove();
            }
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null) cdStart(owner, "blur", cooldownMs("blur", run.energy));
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
                continue;
            }
            for (org.bukkit.entity.Vex clone : run.clones) {
                if (clone.isValid() && clone.getTarget() != victim) clone.setTarget(victim);
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
        if (melee && gems.isGem(striking) && gems.getType(striking) == GemType.SPEED) mine.lastHit = System.currentTimeMillis();
    }

    /** Blur clones are real vexes; stop them from hurting or chasing anyone but the Blur target. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlurCloneHit(EntityDamageByEntityEvent e) {
        UUID victim = blurCloneVictim.get(e.getDamager().getUniqueId());
        if (victim != null && !victim.equals(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlurCloneTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        UUID victim = blurCloneVictim.get(e.getEntity().getUniqueId());
        if (victim != null && (e.getTarget() == null || !victim.equals(e.getTarget().getUniqueId()))) e.setCancelled(true);
    }

    // ================= PUFF (user kit, Double Jump offhand-only) =================

    /** Double Jump: mid-air second jump. Works from a click or double-tap space. */
    private void useDoubleJump(Player p) {
        Vector jump = p.getLocation().getDirection().multiply(0.6);
        jump.setY(0.9);
        p.setVelocity(jump);
        p.setFallDistance(0);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.4f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15, 0.4, 0.2, 0.4, 0.05);
    }

    /** Breezy Bash [Hit]: launch the victim straight up, then crash them into the ground. */
    private void useBreezyHit(Player attacker, LivingEntity victim) {
        victim.setVelocity(new Vector(0, 1.8, 0));
        victim.setFallDistance(0);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1, 0.8f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isValid()) return;
            victim.setVelocity(new Vector(0, -2.5, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (victim.isValid()) abilityDamage(victim, 6.0, attacker);
            }, 10L);
        }, 15L);
        usedOn(attacker, "Breezy Bash", victim.getName());
    }

    /** Breezy Bash [Left Click]: launch a single target straight up, then crash them down. */
    private void useBreezySlam(Player p, int energy) {
        // Find closest untrusted player in radius
        Player target = null;
        double bestDist = 8 * 8;
        for (Entity e : p.getNearbyEntities(8, 4, 8)) {
            if (!(e instanceof Player o)) continue;
            if (o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o)) continue;
            double d = p.getLocation().distanceSquared(o.getLocation());
            if (d < bestDist) { bestDist = d; target = o; }
        }
        if (target == null) {
            p.sendMessage(ChatColor.GRAY + "Breezy Bash: no target in range.");
            return;
        }
        // Launch straight up
        target.setVelocity(new Vector(0, 1.8, 0));
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1, 0.8f);
        // Crash down after a short delay (lambdas need a final copy of the target)
        final Player victim = target;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isOnline()) return;
            victim.setVelocity(new Vector(0, -2.5, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (victim.isOnline()) abilityDamage(victim, 6.0, p);
            }, 10L);
        }, 15L);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 40, 2, 1, 2, 0.1);
        usedOn(p, "Breezy Bash", target.getName());
    }

    /**
     * Dash [Right Click]: 40-block dash. Anyone the caster passes is caught once, then hurt once a
     * second: abilities.dash.damage-per-second for abilities.dash.damage-seconds.
     */
    private void useDash(Player p, int energy) {
        Vector dir = p.getLocation().getDirection().normalize().multiply(6.0);
        dir.setY(Math.max(dir.getY(), 0.2));
        p.setVelocity(dir);
        p.setFallDistance(0);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1, 1);
        double perSecond = Math.max(0.0, plugin.getConfig().getDouble("abilities.dash.damage-per-second", 3.0));
        int seconds = Math.max(1, plugin.getConfig().getInt("abilities.dash.damage-seconds", 3));
        Set<UUID> caught = new HashSet<>();
        // The dash covers its 40 blocks in about half a second: sweep around the caster on every one of those ticks.
        for (int i = 1; i <= 10; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                for (Entity e : p.getNearbyEntities(3, 3, 3)) {
                    if (e.getUniqueId().equals(p.getUniqueId())) continue;
                    if (e instanceof Player o && isTrusted(p, o)) continue;
                    if (e instanceof LivingEntity le && caught.add(le.getUniqueId())) dashHurt(p, le, perSecond, seconds);
                }
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 10, 0.3, 0.3, 0.3, 0.05);
            }, i);
        }
        used(p, "Dash");
    }

    /** One hit now and one each following second. A second apart, every hit lands (no hurt-immunity overlap). */
    private void dashHurt(Player p, LivingEntity victim, double perSecond, int seconds) {
        for (int second = 0; second < seconds; second++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && victim.isValid() && !victim.isDead()) abilityDamage(victim, perSecond, p);
            }, second * 20L);
        }
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
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 0.7f, 0.6f);
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
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 0.7f, 0.6f);
        }
    }

    /** Dimensional Drift [offhand right-click]: T1 ghost horse 5s, T2 speed surge 5s. */
    private void useDimensionalDrift(Player p, int tier, int energy) {
        if (tier >= 2) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 4, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 100, 2, true, false, false));
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1.4f);
            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 50, 0.5, 1, 0.5, 0.3);
            used(p, "Dimensional Drift");
            return;
        }
        org.bukkit.entity.Horse horse = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.Horse.class);
        horse.setAdult();
        horse.setTamed(true);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 140, 0, false, false, false));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 140, 3, true, false, false));
        horse.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 140, 3, true, false, false));
        horse.addPassenger(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
        used(p, "Dimensional Drift");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            horse.remove();
            p.sendMessage(ChatColor.GRAY + "The drift fades...");
        }, 5 * 20L);
    }

    /** Daggers [Left Click]: Fire one hovering dagger straight ahead. Deals 2.5 hearts, moves at configurable speed. */
    private void handleAstraDaggers(Player p, int energy) {
        fireAstraDagger(p);
        used(p, "Daggers");
    }

    /** Unbounded [Hit]: enter the victim's body; exiting disables your own gem. */
    private void enterUnbounded(Player rider, LivingEntity host) {
        long now = System.currentTimeMillis();
        Long last = unboundedLast.get(rider.getUniqueId());
        if (last != null && now - last < 30_000) return;
        unboundedLast.put(rider.getUniqueId(), now);
        try {
            host.addPassenger(rider);
        } catch (Exception ex) { return; }
        unboundedHost.put(rider.getUniqueId(), host.getUniqueId());
        rider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0, false, false, false));
        usedOn(rider, "Unbounded", host.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (unboundedHost.containsKey(rider.getUniqueId())) {
                try { host.removePassenger(rider); } catch (Exception ignored) { }
            }
        }, 15 * 20L);
    }

    private void exitUnbounded(Player rider) {
        if (!unboundedHost.containsKey(rider.getUniqueId())) return;
        unboundedHost.remove(rider.getUniqueId());
        rider.removePotionEffect(PotionEffectType.INVISIBILITY);
        disableGem(rider, 30_000L, "Unbounded exit");
        rider.sendMessage(ChatColor.GRAY + "You slip out. Your gem goes dark for 30s.");
    }

    public boolean isProjecting(Player p) {
        return projectionBody.containsKey(p.getUniqueId());
    }

    /** Astral Projection [Right Click]: leave a zombie body behind, roam as a ghost. */
    private void useProjection(Player p, int energy) {
        if (isProjecting(p)) {
            returnToBody(p);
            return;
        }
        org.bukkit.entity.Zombie body = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.Zombie.class);
        body.setAI(false);
        body.setCanPickupItems(false);
        body.setCustomName(p.getName() + "'s Body");
        body.setCustomNameVisible(true);
        projectionBody.put(p.getUniqueId(), body);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 130, 0, false, false, false));
        p.setAllowFlight(true);
        p.setFlying(true);
        used(p, "Astral Projection");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isProjecting(p)) {
                p.sendMessage(ChatColor.GRAY + "The projection snaps back...");
                returnToBody(p);
            }
        }, 60 * 20L);
    }

    private void returnToBody(Player p) {
        org.bukkit.entity.Zombie body = projectionBody.remove(p.getUniqueId());
        Location back = (body != null && body.isValid()) ? body.getLocation() : p.getWorld().getSpawnLocation();
        if (body != null && body.isValid()) body.remove();
        p.teleport(back);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setFlying(false);
        if (p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            p.setAllowFlight(false);
        }
        p.sendMessage(ChatColor.DARK_PURPLE + "You return to your body.");
    }

    /** Astral Void [Right-click on entity]: disable untrusted gems in a radius. */
    private void useAstralVoid(Player p, Location around, int energy) {
        int radius = 10;
        int hit = 0;
        for (Entity e : around.getWorld().getNearbyEntities(around, radius, radius / 2, radius)) {
            if (!(e instanceof Player o)) continue;
            if (o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o)) continue;
            disableGem(o, 20_000L, "Astral Void");
            hit++;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
        p.getWorld().spawnParticle(Particle.DRAGON_BREATH, around, 60, 3, 2, 3, 0.1);
        usedGroup(p, "Astral Void", hit);
    }

    // ================= LIFE (user kit, Vortex offhand-only) =================

    private double maxHealthOf(Player p) {
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return (attr == null) ? 20.0 : attr.getValue();
    }

    private void setMaxHealth(Player p, double v) {
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(v);
        if (p.getHealth() > v) p.setHealth(v);
    }

    /** Heart lock: cap max HP for a while, then restore the original. */
    private void lockHearts(Player v, double newMax, long durMs) {
        long now = System.currentTimeMillis();
        if (!heartOrig.containsKey(v.getUniqueId())) {
            heartOrig.put(v.getUniqueId(), maxHealthOf(v));
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

    /** Vitality Vortex [offhand right-click]: drain nature into buffs, grey the husks. */
    private void useVitalityVortex(Player p, int tier, int energy) {
        int radius = 8;
        int absorbed = 0;
        World w = p.getWorld();
        Location c = p.getLocation();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    Block b = c.clone().add(dx, dy, dz).getBlock();
                    Material m = b.getType();
                    if (org.bukkit.Tag.LOGS.isTagged(m)) {
                        b.setType(Material.GRAY_CONCRETE, false);
                        absorbed++;
                    } else if (org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.FLOWERS.isTagged(m)) {
                        b.setType(Material.LIGHT_GRAY_CONCRETE, false);
                        absorbed++;
                    }
                }
            }
        }
        if (absorbed < 5) {
            p.sendMessage(ChatColor.GRAY + "Vitality Vortex: not enough nature nearby (need logs, leaves, flowers).");
            cdClear(p, "vortex");
            return;
        }
        int dur = Math.min(60, 20 + absorbed / 2);
        int resAmp = Math.min(2, absorbed / 25);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, dur * 20, resAmp));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, dur * 20, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, dur * 20, 1));
        w.spawnParticle(Particle.HEART, c, 40, 2, 2, 2, 0.1);
        w.playSound(c, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 0.6f);
        used(p, "Vitality Vortex");
    }

    /** Heart Drainer [Left Click]: remove 4 hearts from untrusted enemies for 20s. */
    private void useHeartDrainer(Player p, int energy) {
        int radius = 10;
        int hit = 0;
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (!(e instanceof Player o) || o.getUniqueId().equals(p.getUniqueId()) || isTrusted(p, o)) continue;
            double base = heartOrig.getOrDefault(o.getUniqueId(), maxHealthOf(o));
            lockHearts(o, Math.max(2.0, base - 8.0), 20_000L);
            o.sendMessage(ChatColor.RED + "Heart Drainer: -4 hearts for 20s!");
            hit++;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_HURT, 0.8f, 0.7f);
        usedGroup(p, "Heart Drainer", hit);
    }

    /** Circle of Life [Right Click]: 
     * No targets in radius -> mends armor only (no penalty).
     * Targets in radius -> steals 5 hearts from each target, gives 5 to user (30s, max 15 hearts). */
    private void useCircleOfLife(Player p, int energy) {
        int radius = 12;
        List<Player> targets = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(radius, radius / 2, radius)) {
            if (e instanceof Player o && !o.getUniqueId().equals(p.getUniqueId())) targets.add(o);
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
            // No targets: just mended armor, no penalty
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation(), 10, 1, 1, 1, 0.05);
            used(p, "Circle of Life");
            return;
        }

        // Has targets: steal 5 hearts from each, give 5 to user (30s duration)
        int stolenFrom = 0;
        for (Player t : targets) {
            double targetBase = heartOrig.getOrDefault(t.getUniqueId(), maxHealthOf(t));
            double targetNewMax = Math.max(2.0, targetBase - 10.0); // lose 5 hearts = 10 HP
            if (!heartOrig.containsKey(t.getUniqueId())) {
                heartOrig.put(t.getUniqueId(), targetBase);
            }
            lockHearts(t, targetNewMax, 30_000L);
            t.sendMessage(ChatColor.RED + "Circle of Life: " + p.getName() + " stole 5 hearts from you!");
            stolenFrom++;
        }

        // User gains 5 hearts per target (capped at 15 hearts / 30 HP max)
        double userBase = heartOrig.getOrDefault(p.getUniqueId(), maxHealthOf(p));
        double userGain = stolenFrom * 10.0; // 5 hearts = 10 HP each
        double userNewMax = Math.min(30.0, userBase + userGain); // cap at 15 hearts
        if (!heartOrig.containsKey(p.getUniqueId())) {
            heartOrig.put(p.getUniqueId(), userBase);
        }
        lockHearts(p, userNewMax, 30_000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, true, false, false));
        p.sendMessage(ChatColor.GREEN + "Circle of Life: Stole 5 hearts from " + stolenFrom + " target(s)!");
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 0.6f);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation(), 30, 2, 1, 2, 0.1);
        usedGroup(p, "Circle of Life", stolenFrom);
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
            p.sendMessage(ChatColor.GRAY + "Kinetic Burst: not charged yet (take hits to charge: " + charge + "/100).");
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
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.3f);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation(), 60, 2, 1, 2, 0.2);
        used(p, "Kinetic Burst");
    }

    /** Energy Beam [Left Click]: ray of wattage. Consumes 30 watts. */
    private void useEnergyBeam(Player p, int energy) {
        int watts = fluxOf(fluxWatts, p.getUniqueId());
        if (watts < 10) {
            p.sendMessage(ChatColor.GRAY + "Energy Beam: no wattage (feed ingots or convert ore energy: " + watts + "/100).");
            fluxBeamCooldown.remove(p.getUniqueId());
            return;
        }
        double dmg = 2.0 + watts / 8.0;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        World w = p.getWorld();
        for (int i = 1; i <= 20; i++) {
            Location at = eye.clone().add(dir.clone().multiply(i));
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 2, 0.1, 0.1, 0.1, 0);
            if (!at.getBlock().getType().isAir()) break;
            for (Entity e : w.getNearbyEntities(at, 1.5, 1.5, 1.5)) {
                if (e.getUniqueId().equals(p.getUniqueId())) continue;
                if (e instanceof Player o && isTrusted(p, o)) continue;
                if (e instanceof LivingEntity le) abilityDamage(le, dmg, p);
            }
        }
        fluxAdd(fluxWatts, p.getUniqueId(), -30);
        w.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.5f);
        used(p, "Energy Beam");
    }

    /** Ground [Hit]: wattage damage + stun; 100% watts scrambles the victim's inventory. */
    private void useGroundHit(Player attacker, Player victim, int watts) {
        double bonus = watts / 10.0;
        int stunSecs = Math.max(1, watts / 20);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stunSecs * 20, 8, true, false, false));
        victim.sendMessage(ChatColor.GRAY + "Grounded: stunned " + stunSecs + "s!");
        usedOn(attacker, "Ground", victim.getName());
        if (watts >= 100) {
            ItemStack[] contents = victim.getInventory().getContents();
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack s : contents) items.add(s);
            java.util.Collections.shuffle(items);
            for (int i = 0; i < contents.length; i++) contents[i] = items.get(i);
            victim.getInventory().setContents(contents);
            victim.sendMessage(ChatColor.RED + "Your inventory scrambles!");
            attacker.sendMessage(ChatColor.YELLOW + "Ground: 100% watts scrambled " + victim.getName() + "!");
        }
    }

    /** Kinetic Overdrive [Right Click]: 30s buff; every 3 hits taken arms a big next hit. */
    private void useOverdrive(Player p, int energy) {
        overdriveUntil.put(p.getUniqueId(), System.currentTimeMillis() + 30_000L);
        overdriveTaken.put(p.getUniqueId(), 0);
        overdriveArmed.put(p.getUniqueId(), false);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
        used(p, "Kinetic Overdrive");
    }

    /** T2 ingot tap: gem in offhand + ingot/block in main + right-click converts it to watts. */
    private boolean tryIngotCollect(Player p, ItemStack main) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (!gems.isGem(off) || gems.getType(off) != GemType.FLUX || gems.getTier(off) < 2) return false;
        if (!EnergyStage.allowsPowers(gems.getEnergy(off)) || isGemDisabled(p)) return false;
        int gain = switch (main.getType()) {
            case COPPER_INGOT -> 10;
            case IRON_INGOT -> 15;
            case DIAMOND -> 30;
            case NETHERITE_INGOT -> 50;
            case COPPER_BLOCK -> 80;
            case IRON_BLOCK -> 100;
            case DIAMOND_BLOCK -> 100;
            case NETHERITE_BLOCK -> 100;
            case RAW_COPPER, RAW_IRON, RAW_GOLD -> 8;
            default -> 0;
        };
        if (gain <= 0) return false;
        if (main.getAmount() > 1) main.setAmount(main.getAmount() - 1);
        else p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        fluxAdd(fluxWatts, p.getUniqueId(), gain);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.2f);
        p.sendMessage(ChatColor.YELLOW + "Conduction: +" + gain + " watts (" + fluxOf(fluxWatts, p.getUniqueId()) + "/100).");
        return true;
    }

    private void usePower(Player p, GemType type, int tier, int energy) {
        switch (type) {
            case STRENGTH -> useChadStrength(p, energy);
            case FIRE -> handleFireballClick(p, energy);
            case SPEED -> useSpeedyStorm(p, energy);
            case WEALTH -> useRichRush(p, energy);
            case FLUX -> useEnergyBeam(p, energy);
            case ASTRA -> handleAstraDaggers(p, energy);
            case LIFE -> {
                double heal = Math.min(p.getMaxHealth() - p.getHealth(), 4.0);
                p.setHealth(p.getHealth() + heal);
                for (Entity en : p.getNearbyEntities(5, 5, 5)) {
                    if (en instanceof LivingEntity le && !en.getUniqueId().equals(p.getUniqueId())) {
                        le.damage(2.0, p);
                    }
                }
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
                p.sendMessage(ChatColor.RED + "Siphon: +" + ((int) heal / 2) + " hearts drained.");
            }
            case PUFF -> useDash(p, energy);
        }
    }

    private void useTier2(Player p, GemType type, int energy) {
        switch (type) {
            case STRENGTH -> {
                Player t = getBounty(p);
                if (t == null) {
                    p.sendMessage(ChatColor.GRAY + "Bounty Hunter: hold their player head in offhand + right-click to track.");
                } else if (t.isOnline()) {
                    p.setCompassTarget(t.getLocation());
                    int d = (int) p.getLocation().distance(t.getLocation());
                    p.sendMessage(ChatColor.RED + "Bounty Hunter: " + t.getName() + " §7(" + d + "m) — compass updated.");
                } else {
                    p.sendMessage(ChatColor.GRAY + "Bounty target offline.");
                }
            }
            case FIRE -> {
                Block target = p.getTargetBlockExact(6);
                if (target == null) {
                    p.sendMessage(ChatColor.GRAY + "Cozy Campfire: aim at a block within 6m (or right-click a block).");
                } else {
                    long rem = remain(cozyCooldown, p.getUniqueId());
                    if (rem > 0) {
                        p.sendMessage(ChatColor.RED + "Cozy Campfire on cooldown: " + (rem / 1000) + "s");
                    } else {
                        cozyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("cozy", energy));
                        useCozyCampfire(p, target, energy);
                    }
                }
            }
            case SPEED -> {
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.3f);
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
                List<GemType> pool = new ArrayList<>(List.of(GemType.values()));
                pool.remove(cur);
                GemType next = pool.get((int) (Math.random() * pool.size()));
                int tier = gems.getTier(gem);
                int en = gems.getEnergy(gem);
                ItemStack replacement = gems.createGem(next, tier, en);
                if (gemMain) p.getInventory().setItemInMainHand(replacement);
                else p.getInventory().setItemInOffHand(replacement);
                consumeOne(p, inHand);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1, 1);
                p.sendMessage(ChatColor.AQUA + "Time for a change: " + cur.display + " → " + next.display);
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
                    phaseVictim.getWorld().spawnParticle(Particle.PORTAL, phaseVictim.getLocation().add(0, 1, 0),
                            25, 0.4, 0.6, 0.4, 0.2);
                    return;
                }
            }
        }
        // Dagger shots disable gems on hit
        if (e.getDamager() instanceof org.bukkit.entity.Projectile proj && daggerShots.remove(proj.getUniqueId())) {
            if (e.getEntity() instanceof Player hit && proj.getShooter() instanceof Player shooter
                    && !hit.getUniqueId().equals(shooter.getUniqueId())) {
                disableGem(hit, 8_000L, "Daggers");
            }
            return;
        }
        // Stun shots root players briefly
        if (e.getDamager() instanceof org.bukkit.entity.Projectile sproj && stunShots.remove(sproj.getUniqueId())) {
            if (e.getEntity() instanceof Player hit) {
                hit.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 9, true, false, false));
                hit.sendMessage(ChatColor.GRAY + "Stun shot: rooted!");
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
        // Ghost hits disable gems instead of normal powers
        if (isProjecting(p)) {
            if (e.getEntity() instanceof Player hit && !hit.getUniqueId().equals(p.getUniqueId())) {
                disableGem(hit, 10_000L, "Astral touch");
            }
            e.setDamage(e.getDamage() * 0.5);
            return;
        }
        GemType gtype = gems.getType(gem);
        if (gtype == GemType.FIRE) {
            // Meteor Shower [Hit] T2: fireballs rain around the hit player for 30s
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof Player hit) {
                meteorUntil.put(hit.getUniqueId(), System.currentTimeMillis() + 30_000L);
                meteorLastShot.put(hit.getUniqueId(), 0L);
                usedOn(p, "Meteor Shower", hit.getName());
            }
            return;
        }
        if (gtype == GemType.WEALTH) {
            int wTier = gems.getTier(gem);
            int wEnergy = gems.getEnergy(gem);
            // Unfortunate [Hit] T2: 50% of the victim's actions fizzle for 15s
            if (wTier >= 2 && EnergyStage.allowsPowers(wEnergy) && e.getEntity() instanceof Player hit) {
                unfortunateUntil.put(hit.getUniqueId(), System.currentTimeMillis() + 15_000L);
                hit.sendMessage(ChatColor.RED + "Unfortunate: half your actions will fail for 15s!");
                usedOn(p, "Unfortunate", hit.getName());
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
            // Hit passive (both tiers): chance to levitate or slow-fall the victim
            if (EnergyStage.allowsPassives(gems.getEnergy(gem)) && e.getEntity() instanceof LivingEntity le
                    && Math.random() < 0.25) {
                if (Math.random() < 0.5) le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 60, 0));
                else le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0));
            }
            // Breezy Bash [Hit] T2: launch up + slam down
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof LivingEntity le2) {
                useBreezyHit(p, le2);
            }
            return;
        }
        if (gtype == GemType.ASTRA) {
            // Unbounded [Hit] T2: slip inside the victim's body
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof LivingEntity host && !host.getUniqueId().equals(p.getUniqueId())) {
                enterUnbounded(p, host);
            }
            return;
        }
        if (gtype == GemType.LIFE) {
            // Heartlock [Hit] T2: freeze the victim at their current hearts for 15s
            if (gems.getTier(gem) >= 2 && EnergyStage.allowsPowers(gems.getEnergy(gem))
                    && e.getEntity() instanceof Player hit) {
                lockHearts(hit, Math.max(2.0, hit.getHealth()), 15_000L);
                hit.sendMessage(ChatColor.RED + "Heartlock: your hearts freeze for 15s!");
                usedOn(p, "Heartlock", hit.getName());
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
            // Ground [Hit] T2: wattage damage + stun (100% scrambles players)
            if (fTier >= 2 && EnergyStage.allowsPowers(fEnergy) && e.getEntity() instanceof Player hitP) {
                int watts = fluxOf(fluxWatts, p.getUniqueId());
                e.setDamage(e.getDamage() + watts / 10.0);
                useGroundHit(p, hitP, watts);
                fluxAdd(fluxWatts, p.getUniqueId(), -20);
            }
            return;
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
            usedOn(p, "Frailer", le.getName());
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
                p.sendMessage(ChatColor.RED + "CHAD STRENGTH x2! (hit 4)");
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
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
            p.sendMessage(ChatColor.GRAY + "Unfortunate: it slips from your grasp...");
        }
    }

    /** Amplification [Right-click on entity]: T2 Wealth main-hand on a player. */
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof LivingEntity)) return;
        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!gems.isGem(inHand)) return;
        if (gems.getType(inHand) == GemType.WEALTH && gems.getTier(inHand) >= 2
                && EnergyStage.allowsPowers(gems.getEnergy(inHand)) && !isGemDisabled(p)) {
            long rem = remain(amplifyCooldown, p.getUniqueId());
            if (rem > 0) { p.sendMessage(ChatColor.RED + "Amplification on cooldown: " + (rem / 1000) + "s"); return; }
            amplifyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("amp", gems.getEnergy(inHand)));
            useAmplification(p, p, gems.getEnergy(inHand));
            return;
        }
        // Astral Void [Right-click on entity]: T2 Astra main-hand disables nearby untrusted gems
        if (gems.getType(inHand) == GemType.ASTRA && gems.getTier(inHand) >= 2
                && EnergyStage.allowsPowers(gems.getEnergy(inHand)) && !isGemDisabled(p)) {
            long rem = remain(voidCooldown, p.getUniqueId());
            if (rem > 0) { p.sendMessage(ChatColor.RED + "Astral Void on cooldown: " + (rem / 1000) + "s"); return; }
            voidCooldown.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMs("void", gems.getEnergy(inHand)));
            useAstralVoid(p, e.getRightClicked().getLocation(), gems.getEnergy(inHand));
        }
    }

    /** Unbounded exit: slipping out (sneak/dismount) darkens your own gem for 30s. */
    @EventHandler
    public void onUnboundedDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (e.getEntity() instanceof Player rider) exitUnbounded(rider);
    }

    /** Projecting ghosts return on crouch. */
    @EventHandler
    public void onGhostSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (isProjecting(p)) returnToBody(p);
    }

    /** If a projection body is slain, yank the ghost home. */
    @EventHandler
    public void onBodySlain(org.bukkit.event.entity.EntityDeathEvent e) {
        UUID ownerId = null;
        for (var entry : projectionBody.entrySet()) {
            if (entry.getValue().getUniqueId().equals(e.getEntity().getUniqueId())) { ownerId = entry.getKey(); break; }
        }
        if (ownerId == null) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            returnToBody(owner);
            owner.damage(4.0);
            owner.sendMessage(ChatColor.RED + "Your body was slain!");
        } else {
            org.bukkit.entity.Zombie z = projectionBody.remove(ownerId);
            if (z != null && z.isValid()) z.remove();
        }
    }

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
        fluxAdd(fluxCharge, p.getUniqueId(), 8);
        if (fluxOf(fluxCharge, p.getUniqueId()) >= 100) {
            p.sendMessage(ChatColor.YELLOW + "Flux fully charged (100)! Unleash Kinetic Burst!");
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

    /** Flux: arrows may stun players they hit. */
    @EventHandler
    public void onFluxShoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack gem = findGem(p);
        if (gem == null || gems.getType(gem) != GemType.FLUX) return;
        if (!EnergyStage.allowsPassives(gems.getEnergy(gem)) || isGemDisabled(p)) return;
        if (Math.random() < 0.25 && e.getProjectile() != null) {
            stunShots.add(e.getProjectile().getUniqueId());
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
        if (!plugin.getConfig().getBoolean("protection.prevent-drop", true)) return;
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (!gems.isGem(dropped)) return;
        // Flux T2: dropping the gem converts banked ore energy into wattage (gem stays soulbound)
        if (gems.getType(dropped) == GemType.FLUX && gems.getTier(dropped) >= 2) {
            int banked = fluxOf(fluxOre, e.getPlayer().getUniqueId());
            if (banked > 0 && EnergyStage.allowsPowers(gems.getEnergy(dropped)) && !isGemDisabled(e.getPlayer())) {
                fluxOre.put(e.getPlayer().getUniqueId(), 0);
                fluxAdd(fluxWatts, e.getPlayer().getUniqueId(), banked);
                e.getPlayer().sendMessage(ChatColor.YELLOW + "Conduction: converted " + banked + " ore energy into "
                        + fluxOf(fluxWatts, e.getPlayer().getUniqueId()) + "/100 watts!");
                e.setCancelled(true);
                return;
            }
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "Gems are soulbound and cannot be dropped.");
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
        if (gems.isGem(moving) || gems.isGem(e.getCursor())) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage(ChatColor.RED + "Gems cannot be stored in containers.");
        }
    }
}
