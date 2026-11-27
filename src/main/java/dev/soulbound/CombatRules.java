package dev.soulbound;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fight rules (rules: in config.yml): the combat tag, enchanted golden apples and totems per fight, the pearl,
 * wind charge, chorus and riptide cooldowns, no gliding right after a hit, harmless end crystals and bed / anchor
 * bombs, no TNT minecarts, and invisible players' names scrambled in death messages and chat. Each rule has its own
 * rules.&lt;rule&gt;.enabled; gem and mythic abilities are never affected.
 */
final class CombatRules implements Listener {
    static final String SEE_INVISIBLE = "soulbound.rules.see-invisible";
    private final RuleCore core;
    private final SoulboundGemsPlugin plugin;
    private final java.util.Map<UUID, Long> hitByPlayerAt = new java.util.HashMap<>();
    final java.util.Map<UUID, Long> glideEndedAt = new java.util.HashMap<>(); // read by MaceRules
    private final Set<UUID> invisible = ConcurrentHashMap.newKeySet(); // updated on the main thread, read by async chat
    private final Set<UUID> unlisted = new java.util.HashSet<>();
    private final Random rnd = new Random();
    private BukkitTask task;

    CombatRules(SoulboundGemsPlugin plugin, RuleCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L); }

    void shutdown() {
        if (task != null) task.cancel();
        for (UUID id : new ArrayList<>(unlisted)) relist(id);
    }

    /** Every half second: fights that ran out end (with the action bar once), and who's invisible. */
    private void tick() {
        for (UUID id : core.fights.expired(System.currentTimeMillis())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendActionBar(Component.text(SmallCaps.of("Out of combat"), NamedTextColor.GREEN));
                plugin.holdActionBar(p, 1500L); // readable over the gem HUD
            }
        }
        boolean on = core.on("invisibility"), tab = plugin.getConfig().getBoolean("rules.invisibility.tab", false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inv = on && core.on("invisibility", p.getWorld()) && (p.hasPotionEffect(PotionEffectType.INVISIBILITY) || p.isInvisible());
            if (inv) invisible.add(p.getUniqueId()); else invisible.remove(p.getUniqueId());
            if (inv && tab) { // every tick, so someone who joined since doesn't see them either (unlisting twice sends nothing)
                unlisted.add(p.getUniqueId());
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && !o.hasPermission(SEE_INVISIBLE)) o.unlistPlayer(p);
            } else if (unlisted.contains(p.getUniqueId())) relist(p.getUniqueId());
        }
    }

    private void relist(UUID id) {
        unlisted.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.listPlayer(p);
    }

    /** Invisible (a potion, or a gem ability that hides you) with the invisibility rule on there. Checked live on the main thread. */
    boolean invisible(Player p) {
        if (p == null) return false;
        if (!Bukkit.isPrimaryThread()) return invisible.contains(p.getUniqueId()); // async chat: the last tick's answer
        return core.on("invisibility", p.getWorld()) && (p.hasPotionEffect(PotionEffectType.INVISIBILITY) || p.isInvisible());
    }

    /** The name other messages should use: scrambled (§k) while the player is invisible. */
    String shownName(Player p) {
        return invisible(p) ? "§k" + RuleMath.scramble(rnd) + "§r" : p.getName();
    }

    /** The same as a component: an obfuscated scramble of 4-10 letters while invisible, else the plain name. */
    Component shown(Player p) { return invisible(p) ? scrambled() : Component.text(p.getName()); }

    private Component scrambled() { return Component.text(RuleMath.scramble(rnd)).decorate(TextDecoration.OBFUSCATED); }

    // ------------------------------------------------------------------ the combat tag

    /** The player behind some damage: themselves, the shooter of a projectile, whoever lit the TNT, a pet's owner, a cloud's thrower. */
    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player by = attacker(e.getDamager());
        if (by == null || by.equals(victim)) return;
        long now = System.currentTimeMillis();
        hitByPlayerAt.put(victim.getUniqueId(), now);
        if (!core.on("combat", victim.getWorld())) return;
        long tag = (long) (core.num("combat", "tag-seconds", 15) * 1000);
        for (Player p : new Player[] {victim, by}) {
            if (core.fights.hit(p.getUniqueId(), now, tag)) {
                p.sendActionBar(Component.text(SmallCaps.of("In combat"), NamedTextColor.RED));
                plugin.holdActionBar(p, 1500L); // readable over the gem HUD
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        core.fights.end(id);
        core.forget(id);
        hitByPlayerAt.remove(id);
        glideEndedAt.remove(id);
        invisible.remove(id);
        unlisted.remove(id);
    }

    // ------------------------------------------------------------------ 1. enchanted golden apples, 9. chorus fruit

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Material m = e.getItem().getType();
        if (m == Material.ENCHANTED_GOLDEN_APPLE && core.on("enchanted-golden-apples", p.getWorld()) && core.on("combat", p.getWorld()) && !core.fightExempt(p)) {
            int max = (int) core.num("enchanted-golden-apples", "max-per-fight", 3);
            if (!core.fights.eatEgap(p.getUniqueId(), System.currentTimeMillis(), max)) {
                e.setCancelled(true);
                core.say(p, "enchanted-golden-apples", max + "/" + max + " enchanted gapples this fight");
            }
        } else if (m == Material.CHORUS_FRUIT && core.on("chorus-fruit", p.getWorld()) && core.inFight(p) && !core.fightExempt(p)) {
            cooldown(p, Material.CHORUS_FRUIT, core.num("chorus-fruit", "fight-cooldown-seconds", 7));
        }
    }

    // ------------------------------------------------------------------ 3. totems

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !core.on("totems", p.getWorld()) || !core.on("combat", p.getWorld()) || core.fightExempt(p)) return;
        if (e.getHand() == null) return; // not a totem (another plugin's revive): never this rule's business
        int max = (int) core.num("totems", "max-per-fight", 1);
        if (!core.fights.useTotem(p.getUniqueId(), System.currentTimeMillis(), max)) {
            e.setCancelled(true); // you die normally
            core.say(p, "totems", "only " + max + " totem per fight");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        core.fights.end(e.getEntity().getUniqueId());
        hitByPlayerAt.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ 7, 8, 14. cooldowns

    /** The vanilla cooldown overlay, never shortening a longer one (Auratus' chains set their own). */
    private void cooldown(Player p, Material m, double seconds) {
        int ticks = (int) Math.round(seconds * 20);
        if (ticks <= 0) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) p.setCooldown(m, Math.max(p.getCooldown(m), ticks)); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent e) {
        Player p = e.getPlayer();
        Material m = e.getItemStack().getType();
        if (m == Material.ENDER_PEARL && core.on("ender-pearls", p.getWorld()) && core.inFight(p) && !core.fightExempt(p))
            cooldown(p, Material.ENDER_PEARL, core.num("ender-pearls", "fight-cooldown-seconds", 7));
        else if (m == Material.WIND_CHARGE && core.on("wind-charges", p.getWorld()) && !core.fightExempt(p))
            cooldown(p, Material.WIND_CHARGE, core.num("wind-charges", "cooldown-seconds", 5));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent e) {
        Player p = e.getPlayer();
        if (core.on("riptide", p.getWorld()) && core.inFight(p) && !core.fightExempt(p))
            cooldown(p, Material.TRIDENT, core.num("riptide", "fight-cooldown-seconds", 8));
    }

    // ------------------------------------------------------------------ 15. elytra

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isGliding()) { glideEndedAt.put(p.getUniqueId(), System.currentTimeMillis()); return; }
        if (!core.on("elytra", p.getWorld()) || core.fightExempt(p)) return;
        Long hit = hitByPlayerAt.get(p.getUniqueId());
        double secs = core.num("elytra", "no-glide-after-hit-seconds", 5);
        if (hit != null && System.currentTimeMillis() - hit < secs * 1000) {
            e.setCancelled(true);
            core.say(p, "elytra", "no gliding for " + (int) secs + "s after a hit");
        }
    }

    // ------------------------------------------------------------------ 11. end crystals, 12. beds and anchors

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystal(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player p && e.getDamager() instanceof EnderCrystal && core.on("end-crystals", p.getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalBlast(EntityExplodeEvent e) {
        if (e.getEntity() instanceof EnderCrystal && core.on("end-crystals", e.getLocation().getWorld())
                && !plugin.getConfig().getBoolean("rules.end-crystals.break-blocks", true)) e.blockList().clear();
    }

    private static boolean bomb(BlockState s) {
        return s != null && (Tag.BEDS.isTagged(s.getType()) || s.getType() == Material.RESPAWN_ANCHOR);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBomb(EntityDamageByBlockEvent e) {
        if (e.getEntity() instanceof Player p && bomb(e.getDamagerBlockState()) && core.on("bed-bombing", p.getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBombBlast(BlockExplodeEvent e) {
        if (bomb(e.getExplodedBlockState()) && core.on("bed-bombing", e.getBlock().getWorld())
                && !plugin.getConfig().getBoolean("rules.bed-bombing.break-blocks", true)) e.blockList().clear();
    }

    // ------------------------------------------------------------------ 13. TNT minecarts

    @EventHandler(priority = EventPriority.HIGH)
    public void onCartCraft(PrepareItemCraftEvent e) {
        if (e.getInventory().getResult() == null || e.getInventory().getResult().getType() != Material.TNT_MINECART) return;
        if (!(e.getView().getPlayer() instanceof Player p) || !core.on("tnt-minecarts", p.getWorld()) || core.exempt(p)) return;
        e.getInventory().setResult(null);
        core.say(p, "tnt-minecarts", "TNT minecarts are banned");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCartCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (e.getResult().getType() == Material.TNT_MINECART && core.on("tnt-minecarts", e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCartPlace(EntityPlaceEvent e) {
        if (e.getEntityType() != EntityType.TNT_MINECART || !core.on("tnt-minecarts", e.getEntity().getWorld())) return;
        if (e.getPlayer() != null && core.exempt(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getPlayer() != null) core.say(e.getPlayer(), "tnt-minecarts", "TNT minecarts are banned");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCartUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getItem() == null || e.getItem().getType() != Material.TNT_MINECART) return;
        Player p = e.getPlayer();
        if (!core.on("tnt-minecarts", p.getWorld()) || core.exempt(p)) return;
        e.setCancelled(true);
        core.say(p, "tnt-minecarts", "TNT minecarts are banned");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCartDispense(BlockDispenseEvent e) {
        if (e.getItem().getType() == Material.TNT_MINECART && core.on("tnt-minecarts", e.getBlock().getWorld())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ 16. invisibility hides your name

    /** Death messages: an invisible killer or victim is a scrambled name, and "using [item]" is gone. Admins and the console see the real one. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathMessage(PlayerDeathEvent e) {
        Player victim = e.getEntity(), killer = victim.getKiller();
        boolean hideVictim = invisible(victim), hideKiller = invisible(killer);
        Component real = e.deathMessage();
        if (real == null || (!hideVictim && !hideKiller) || !core.on("invisibility", victim.getWorld())) return;
        Component masked = mask(real, hideVictim ? victim.getName() : null, hideKiller ? killer.getName() : null);
        e.deathMessage(null);
        for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(o.hasPermission(SEE_INVISIBLE) ? real : masked);
        plugin.getLogger().info("[death] " + PlainTextComponentSerializer.plainText().serialize(real)); // the real one, for the logs
    }

    /** The death message with these names scrambled, and its "using [item]" part dropped. */
    Component mask(Component msg, String victim, String killer) {
        if (!(msg instanceof TranslatableComponent t)) return msg;
        String key = t.key();
        List<Component> args = new ArrayList<>(t.args());
        if (key.endsWith(".item") && !args.isEmpty()) {
            key = key.substring(0, key.length() - ".item".length()); // the weapon (a named one would give them away)
            args.remove(args.size() - 1);
        }
        for (int i = 0; i < args.size(); i++) {
            String plain = PlainTextComponentSerializer.plainText().serialize(args.get(i));
            if ((victim != null && plain.equals(victim)) || (killer != null && plain.equals(killer))) args.set(i, scrambled());
        }
        return Component.translatable(key, args).style(t.style());
    }

    /** Chat: only an invisible player's name is scrambled, never the message; admins with see-invisible and the console see it. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!invisible.contains(e.getPlayer().getUniqueId()) || !plugin.getConfig().getBoolean("rules.invisibility.chat-name", true)) return;
        ChatRenderer before = e.renderer();
        Component name = scrambled();
        e.renderer((source, displayName, message, viewer) -> {
            boolean real = !(viewer instanceof Player vp) || vp.hasPermission(SEE_INVISIBLE);
            return before.render(source, real ? displayName : name, message, viewer);
        });
    }
}
