package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rule 10, maces (rules.mace): only max-on-server can exist. Every mace gets a hidden id (maces.yml), so the count is
 * exact. Mode race: crafting is open until the limit, each craft announced; mode event: no crafting, admins give them
 * with /rules mace give. Density and Breach are capped, and there's no smash damage while gliding or just after. The
 * Revenant's mace (a mythic) is never counted or touched.
 */
final class MaceRules implements Listener {
    private final RuleCore core;
    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey idKey;
    private final File file;
    private final YamlConfiguration reg;
    private final CombatRules combat;

    MaceRules(SoulboundGemsPlugin plugin, RuleCore core, CombatRules combat) {
        this.plugin = plugin;
        this.core = core;
        this.combat = combat;
        this.idKey = new NamespacedKey(plugin, "mace_id");
        this.file = new File(plugin.getDataFolder(), "maces.yml");
        this.reg = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try { reg.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save maces.yml: " + e.getMessage()); }
    }

    int max() { return (int) core.num("mace", "max-on-server", 3); }

    RuleMath.MaceMode mode() { return RuleMath.MaceMode.of(plugin.getConfig().getString("rules.mace.mode", "race")); }

    /** Maces that count: every one with an id (a destroyed one only frees its slot with destroyed-frees-slot). */
    int count() {
        ConfigurationSection s = reg.getConfigurationSection("maces");
        if (s == null) return 0;
        int n = 0;
        for (String id : s.getKeys(false)) if (!s.getBoolean(id + ".destroyed", false)) n++;
        return n;
    }

    /** A normal mace (not the Revenant's). */
    boolean isMace(ItemStack it) {
        if (it == null || it.getType() != Material.MACE) return false;
        return plugin.revenant() == null || !plugin.revenant().isLiveItem(it);
    }

    String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** Gives the mace its hidden id and records it. */
    private void tag(ItemStack it, String how, Player holder) {
        ItemMeta m = it.getItemMeta();
        String id = UUID.randomUUID().toString().substring(0, 8);
        m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        reg.set("maces." + id + ".how", how);
        reg.set("maces." + id + ".at", System.currentTimeMillis());
        if (holder != null) seen(id, holder);
        save();
    }

    private void seen(String id, Player holder) {
        if (!reg.contains("maces." + id)) {
            reg.set("maces." + id + ".how", "found");
            reg.set("maces." + id + ".at", System.currentTimeMillis());
        }
        reg.set("maces." + id + ".holder", holder.getName());
        reg.set("maces." + id + ".seen", System.currentTimeMillis());
    }

    /**
     * Any untagged mace a player has is tagged (it exists, so it counts); the holder is noted for /rules mace list.
     * Creative players are skipped: a mace taken from the creative menu only counts once a survival player has it.
     */
    private void scan(Player p) {
        if (!core.on("mace") || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        boolean changed = false;
        for (ItemStack it : p.getInventory().getContents()) {
            if (!isMace(it)) continue;
            String id = idOf(it);
            if (id == null) tag(it, "found", p); else seen(id, p);
            capEnchants(it);
            changed = true;
        }
        if (changed) save();
    }

    private void announce(int n) {
        Bukkit.broadcast(Component.text(SmallCaps.of("A mace has been forged (" + n + "/" + max() + ")"), NamedTextColor.GOLD));
    }

    // ------------------------------------------------------------------ crafting and giving

    @EventHandler(priority = EventPriority.HIGH)
    public void onRecipe(PrepareItemCraftEvent e) {
        ItemStack r = e.getInventory().getResult();
        if (!isMace(r) || !core.on("mace", e.getView().getPlayer().getWorld())) return;
        if (RuleMath.canCraft(mode(), count(), max())) return;
        e.getInventory().setResult(null);
        if (e.getView().getPlayer() instanceof Player p)
            core.say(p, "mace", mode() == RuleMath.MaceMode.EVENT ? "maces are only given at events" : "all " + max() + " maces have been forged");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        ItemStack r = e.getCurrentItem();
        if (!isMace(r) || !(e.getWhoClicked() instanceof Player p) || !core.on("mace", p.getWorld())) return;
        if (!RuleMath.canCraft(mode(), count(), max())) { e.setCancelled(true); return; }
        if (e.isShiftClick()) { e.setCancelled(true); core.say(p, "mace", "craft a mace one at a time"); return; } // each one counted
        ItemStack made = r.clone();
        tag(made, "crafted by " + p.getName(), p);
        e.setCurrentItem(made);
        announce(count());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        ItemStack r = e.getResult();
        if (!isMace(r) || !core.on("mace", e.getBlock().getWorld())) return;
        if (!RuleMath.canCraft(mode(), count(), max())) { e.setCancelled(true); return; }
        ItemStack made = r.clone();
        tag(made, "crafted by a crafter", null);
        e.setResult(made);
        announce(count());
    }

    /** /rules mace give: still counts toward the limit. False if the limit is reached. */
    boolean give(Player to) {
        if (!RuleMath.canGive(count(), max())) return false;
        ItemStack mace = new ItemStack(Material.MACE);
        tag(mace, "given to " + to.getName(), to);
        for (ItemStack left : to.getInventory().addItem(mace).values()) to.getWorld().dropItemNaturally(to.getLocation(), left);
        return true;
    }

    /** /rules mace list: each mace and who was last seen with it. */
    List<String> list() {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = reg.getConfigurationSection("maces");
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            String holder = s.getString(id + ".holder", "nobody yet");
            out.add(id + ": " + (s.getBoolean(id + ".destroyed") ? "destroyed" : "held by " + holder) + " (" + s.getString(id + ".how", "?") + ")");
        }
        return out;
    }

    // ------------------------------------------------------------------ keeping count

    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isOnline()) scan(e.getPlayer()); }); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) { if (isMace(e.getPlayer().getInventory().getItem(e.getNewSlot()))) scan(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isMace(e.getItem().getItemStack())) Bukkit.getScheduler().runTask(plugin, () -> scan(p));
    }

    private void destroyed(ItemStack it) {
        String id = idOf(it);
        if (id == null || !reg.contains("maces." + id)) return;
        if (plugin.getConfig().getBoolean("rules.mace.destroyed-frees-slot", false)) reg.set("maces." + id, null);
        else reg.set("maces." + id + ".destroyed", true);
        save();
    }

    /** A mace lying on the ground is gone for good: burnt in lava or fire, blown up, fallen into the void, or despawned. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGone(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item item) || !isMace(item.getItemStack())) return;
        EntityRemoveEvent.Cause c = e.getCause();
        if (c == EntityRemoveEvent.Cause.DEATH || c == EntityRemoveEvent.Cause.DESPAWN || c == EntityRemoveEvent.Cause.OUT_OF_WORLD) destroyed(item.getItemStack());
    }

    // ------------------------------------------------------------------ enchant caps

    /** Density at most max-density, Breach at most max-breach. True if anything was lowered. */
    boolean capEnchants(ItemStack it) {
        if (!isMace(it) || !core.on("mace")) return false;
        boolean changed = false;
        int d = it.getEnchantmentLevel(Enchantment.DENSITY), dm = (int) core.num("mace", "max-density", 3);
        if (d > dm) { it.removeEnchantment(Enchantment.DENSITY); if (dm > 0) it.addUnsafeEnchantment(Enchantment.DENSITY, RuleMath.capLevel(d, dm)); changed = true; }
        int b = it.getEnchantmentLevel(Enchantment.BREACH), bm = (int) core.num("mace", "max-breach", 2);
        if (b > bm) { it.removeEnchantment(Enchantment.BREACH); if (bm > 0) it.addUnsafeEnchantment(Enchantment.BREACH, RuleMath.capLevel(b, bm)); changed = true; }
        return changed;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!isMace(e.getItem()) || !core.on("mace", e.getEnchanter().getWorld())) return;
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        add.computeIfPresent(Enchantment.DENSITY, (k, v) -> RuleMath.capLevel(v, (int) core.num("mace", "max-density", 3)));
        add.computeIfPresent(Enchantment.BREACH, (k, v) -> RuleMath.capLevel(v, (int) core.num("mace", "max-breach", 2)));
        add.values().removeIf(v -> v <= 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack r = e.getResult();
        if (!isMace(r)) return;
        ItemStack copy = r.clone();
        if (capEnchants(copy)) e.setResult(copy);
    }

    // ------------------------------------------------------------------ no smash while gliding

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmash(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isMace(hand) || !core.on("mace", p.getWorld())) return;
        capEnchants(hand); // the caps apply when it's used, too
        if (e.getDamageSource().getDamageType() != org.bukkit.damage.DamageType.MACE_SMASH) return; // only a real smash, never a gem ability's hit
        Long ended = combat.glideEndedAt.get(p.getUniqueId());
        long window = (long) (core.num("mace", "no-smash-after-gliding-seconds", 2) * 1000);
        if (RuleMath.smashAllowed(p.isGliding(), ended == null ? 0 : ended, System.currentTimeMillis(), window)) return;
        AttributeInstance atk = p.getAttribute(Attribute.ATTACK_DAMAGE);
        double plain = atk == null ? 6.0 : atk.getValue(); // a normal hit with the mace, without the smash bonus
        if (e.getDamage() > plain) e.setDamage(plain);
        core.say(p, "mace", "no mace smash while gliding");
    }
}
