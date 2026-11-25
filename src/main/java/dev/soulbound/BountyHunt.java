package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Strength's Bounty Hunter (bounty: in config.yml). The Strength gem in your off hand + right-click opens the Bounty
 * Board: every online player's head. Clicking one opens the offering: items stolen from or gifted by that player
 * (ItemProvenanceListener) are used up when you close it, a minute of hunt each (at most bounty.max-seconds left at
 * once); everything else comes back. While the hunt lasts, only you see a black and dark red thread toward them, and the
 * action bar shows the time left. It ends when the time runs out, or early if they log out or die.
 */
final class BountyHunt implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final Map<UUID, Hunt> hunts = new HashMap<>(); // hunter -> their hunt
    private BukkitTask task;
    private long ticks;

    private static final class Hunt {
        final UUID target;
        final String name;
        long until;

        Hunt(UUID target, String name, long until) { this.target = target; this.name = name; this.until = until; }
    }

    /** The board: one page of heads, and whose head is in which slot. */
    private static final class BoardHolder implements InventoryHolder {
        final int page;
        final Map<Integer, UUID> heads = new HashMap<>();
        Inventory inv;

        BoardHolder(int page) { this.page = page; }

        @Override public Inventory getInventory() { return inv; }
    }

    /** The offering for one target. {@code done}: its items have been dealt with (never twice). */
    private static final class OfferHolder implements InventoryHolder {
        final UUID target;
        final String name;
        Inventory inv;
        boolean done;

        OfferHolder(UUID target, String name) { this.target = target; this.name = name; }

        @Override public Inventory getInventory() { return inv; }
    }

    BountyHunt(SoulboundGemsPlugin plugin) { this.plugin = plugin; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L); }

    /** Plugin disable: open offerings go back to their players, every board and offering closes, every hunt ends. */
    void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof OfferHolder o && !o.done) {
                o.done = true;
                giveBack(p, takeAll(o.inv));
            }
            if (h instanceof OfferHolder || h instanceof BoardHolder) p.closeInventory();
        }
        hunts.clear();
    }

    private int perItem() { return Math.max(1, plugin.getConfig().getInt("bounty.seconds-per-item", 60)); }

    private int maxSeconds() { return Math.max(1, plugin.getConfig().getInt("bounty.max-seconds", 300)); }

    private Particle.DustOptions dust(int i, String def) {
        List<String> cols = plugin.getConfig().getStringList("bounty.trail-colors");
        TextColor c = TextColor.fromHexString(cols.size() > i ? cols.get(i) : def);
        if (c == null) c = TextColor.fromHexString(def);
        return new Particle.DustOptions(Color.fromRGB(c.value()), (float) plugin.getConfig().getDouble("bounty.trail-size", 0.6));
    }

    /** The player a hunter is hunting, if they're online. */
    Player target(Player hunter) {
        Hunt h = hunts.get(hunter.getUniqueId());
        return h == null ? null : Bukkit.getPlayer(h.target);
    }

    private Component nameFor(Player viewer, Player who) {
        return plugin.nameHidden(who) && !viewer.hasPermission(CombatRules.SEE_INVISIBLE) ? plugin.shownNameComponent(who) : Component.text(who.getName());
    }

    // ------------------------------------------------------------------ the board

    void openBoard(Player p) { openBoard(p, 0); }

    private void openBoard(Player p, int page) {
        List<Player> others = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.getUniqueId().equals(p.getUniqueId())) others.add(o);
        if (others.isEmpty()) {
            p.sendMessage(Component.text("No one to hunt right now.", NamedTextColor.GRAY));
            return;
        }
        others.sort(Comparator.comparing(o -> o.getName().toLowerCase(Locale.ROOT)));
        page = BountyMath.clampPage(page, others.size());
        BoardHolder h = new BoardHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Bounty Board"));
        h.inv = inv;
        int from = page * BountyMath.PAGE;
        for (int i = 0; i < BountyMath.PAGE && from + i < others.size(); i++) {
            Player o = others.get(from + i);
            inv.setItem(i, head(p, o));
            h.heads.put(i, o.getUniqueId());
        }
        int pages = BountyMath.pages(others.size());
        if (page > 0) inv.setItem(45, button(Material.ARROW, "Previous page"));
        if (pages > 1) inv.setItem(49, button(Material.PAPER, "Page " + (page + 1) + "/" + pages));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "Next page"));
        p.openInventory(inv);
    }

    /** Their head, their name on hover, and a note if they're in another dimension. An invisible player is a faceless head with a scrambled name. */
    private ItemStack head(Player viewer, Player o) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) head.getItemMeta();
        boolean hidden = plugin.nameHidden(o) && !viewer.hasPermission(CombatRules.SEE_INVISIBLE);
        if (!hidden) m.setOwningPlayer(o);
        m.displayName(nameFor(viewer, o).colorIfAbsent(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        if (!o.getWorld().equals(viewer.getWorld()))
            m.lore(List.of(Component.text(SmallCaps.of("In another realm"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        head.setItemMeta(m);
        return head;
    }

    private static ItemStack button(Material mat, String text) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBoardClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof BoardHolder h)) return;
        e.setCancelled(true); // nothing moves in or out of the board
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot == 45 || slot == 53) {
            int to = h.page + (slot == 45 ? -1 : 1);
            if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW)
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) openBoard(p, to); });
            return;
        }
        UUID id = h.heads.get(slot);
        if (id == null) return;
        Player target = Bukkit.getPlayer(id);
        if (target == null || !target.isOnline()) {
            p.sendMessage(Component.text("They slipped away.", NamedTextColor.GRAY));
            Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && target.isOnline()) openOffer(p, target); });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBoardDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof BoardHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ the offering

    /** The 5-slot offering for {@code target}. */
    void openOffer(Player p, Player target) {
        OfferHolder h = new OfferHolder(target.getUniqueId(), target.getName());
        Inventory inv = Bukkit.createInventory(h, InventoryType.HOPPER, Component.text("Offer to the hunt: ").append(nameFor(p, target)));
        h.inv = inv;
        p.openInventory(inv);
        p.sendMessage(Component.text("Offer what was stolen from or given by ", NamedTextColor.GRAY).append(nameFor(p, target))
                .append(Component.text(", then close.", NamedTextColor.GRAY)));
    }

    private static List<ItemStack> takeAll(Inventory inv) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : inv.getContents()) if (s != null && s.getType() != Material.AIR) out.add(s.clone());
        inv.clear();
        return out;
    }

    /** Back into their inventory, or at their feet if it's full. */
    private static void giveBack(Player p, List<ItemStack> items) {
        for (ItemStack s : items)
            for (ItemStack left : p.getInventory().addItem(s).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** Closing it yourself makes the offering; closing for any other reason (logout, death, teleport, another menu) gives everything back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !(e.getInventory().getHolder() instanceof OfferHolder h) || h.done) return;
        h.done = true;
        List<ItemStack> items = takeAll(e.getInventory());
        if (e.getReason() != InventoryCloseEvent.Reason.PLAYER) { giveBack(p, items); return; }
        offer(p, h, items);
    }

    private void offer(Player p, OfferHolder h, List<ItemStack> items) {
        Player target = Bukkit.getPlayer(h.target);
        if (target == null || !target.isOnline()) {
            giveBack(p, items);
            if (!items.isEmpty()) p.sendMessage(Component.text("They slipped away.", NamedTextColor.GRAY));
            return;
        }
        if (items.isEmpty()) return;
        List<ItemStack> counting = new ArrayList<>(), rest = new ArrayList<>();
        ItemProvenanceListener prov = plugin.provenance();
        for (ItemStack s : items) {
            boolean counts = prov != null && prov.tracked(s) && ProvenanceMath.counts(prov.read(s), h.target);
            (counts ? counting : rest).add(s);
        }
        long now = System.currentTimeMillis();
        Hunt running = hunts.get(p.getUniqueId());
        boolean same = running != null && running.target.equals(h.target) && running.until > now;
        int left = same ? (int) ((running.until - now) / 1000) : 0;
        BountyMath.Offer o = BountyMath.offer(counting.size(), perItem(), maxSeconds(), left);
        rest.addAll(counting.subList(o.used(), counting.size())); // only what's needed is used up
        giveBack(p, rest);
        if (o.used() == 0) {
            p.sendMessage(counting.isEmpty()
                    ? Component.text("Nothing here was stolen from or given by ", NamedTextColor.GRAY).append(nameFor(p, target)).append(Component.text(".", NamedTextColor.GRAY))
                    : Component.text("The hunt can't run longer than " + minutes(maxSeconds()) + ".", NamedTextColor.GRAY));
            return; // nothing happens, no cooldown
        }
        if (same) {
            running.until += o.seconds() * 1000L;
            p.sendMessage(Component.text("The hunt grows: " + minutes((int) ((running.until - now + 999) / 1000)) + " left.", NamedTextColor.RED));
            return;
        }
        hunts.put(p.getUniqueId(), new Hunt(h.target, h.name, now + o.seconds() * 1000L));
        if (plugin.listener() != null) plugin.listener().bountyBegan(p); // the 35 s cooldown, the sound
        p.sendMessage(Component.text("The hunt begins: " + minutes(o.seconds()) + ".", NamedTextColor.RED));
        if (plugin.getConfig().getBoolean("bounty.notify-target", false))
            target.sendMessage(Component.text(SmallCaps.of("You feel watched"), NamedTextColor.GRAY));
    }

    /** "3 minutes", "1 minute", or "2m 30s". */
    static String minutes(int seconds) {
        if (seconds > 0 && seconds % 60 == 0) return (seconds / 60) + (seconds == 60 ? " minute" : " minutes");
        return HudStatus.time(seconds * 1000L);
    }

    // ------------------------------------------------------------------ the hunt

    /** Every 5 ticks: each hunter's thread, their action bar (when no gem bar shows it), and hunts running out. */
    private void tick() {
        ticks += 5;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Hunt>> it = hunts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hunt> en = it.next();
            Hunt h = en.getValue();
            Player hunter = Bukkit.getPlayer(en.getKey()), target = Bukkit.getPlayer(h.target);
            if (hunter == null || target == null) { it.remove(); continue; } // logouts are handled in onQuit too
            if (h.until <= now) {
                it.remove();
                hunter.sendActionBar(Component.text(SmallCaps.of("The hunt is over"), NamedTextColor.GRAY));
                continue;
            }
            boolean here = hunter.getWorld().equals(target.getWorld());
            if (here) drawTrail(hunter, target);
            if (ticks % 10 == 0 && !hasOwnHud(hunter)) hunter.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(status(h, here, now)));
        }
    }

    /** The time left, or "they walk another realm" with it. */
    private static String status(Hunt h, boolean here, long now) {
        String time = "§f" + HudStatus.time(h.until - now);
        return here ? "§4☠ " + time : "§4" + SmallCaps.of("They walk another realm") + " " + time;
    }

    /** Added to the end of the gem, mythic and Revenant action bars while hunting. */
    String hudSuffix(Player p) {
        Hunt h = hunts.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (h == null || h.until <= now) return "";
        Player t = Bukkit.getPlayer(h.target);
        return t == null ? "" : "  §8| " + status(h, t.getWorld().equals(p.getWorld()), now);
    }

    boolean hunting(Player p) {
        Hunt h = hunts.get(p.getUniqueId());
        return h != null && h.until > System.currentTimeMillis();
    }

    /** Holding a gem or the Revenant: that action bar is showing, with hudSuffix on it. */
    private boolean hasOwnHud(Player p) {
        GemManager gems = plugin.gems();
        if (gems != null && (gems.isGem(p.getInventory().getItemInMainHand()) || gems.isGem(p.getInventory().getItemInOffHand()))) return true;
        return plugin.revenant() != null && plugin.revenant().hudActive(p);
    }

    /** The thread, for the hunter only, from chest height to the target's. */
    private void drawTrail(Player hunter, Player target) {
        Location a = hunter.getLocation().add(0, 1.0, 0), b = target.getLocation().add(0, 1.0, 0);
        double length = plugin.getConfig().getDouble("bounty.trail-length", 14);
        BountyMath.Trail t = BountyMath.trail(a.toVector(), b.toVector(), 1.5, length, 0.25, ticks);
        Particle.DustOptions black = dust(0, "#111111"), red = dust(1, "#8B0000");
        for (Vector v : t.black()) hunter.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, black);
        for (Vector v : t.red()) hunter.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, red);
    }

    /** The target is gone (logged out or died): every hunt on them ends, and the time is lost. */
    private void endHuntsOn(Player target, String what) {
        Iterator<Map.Entry<UUID, Hunt>> it = hunts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hunt> en = it.next();
            if (!en.getValue().target.equals(target.getUniqueId())) continue;
            it.remove();
            Player hunter = Bukkit.getPlayer(en.getKey());
            if (hunter != null) hunter.sendMessage(Component.text("The hunt is over: ", NamedTextColor.GRAY)
                    .append(nameFor(hunter, target)).append(Component.text(" " + what, NamedTextColor.GRAY)));
        }
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
        if (h instanceof OfferHolder o && !o.done) { // before their inventory is saved
            o.done = true;
            giveBack(p, takeAll(o.inv));
        }
        hunts.remove(p.getUniqueId());
        endHuntsOn(p, "slipped away.");
    }

    /** Dying with the offering open: its items count as yours (they drop with the rest, or stay with keep-inventory). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathOffer(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof OfferHolder o) || o.done) return;
        o.done = true;
        List<ItemStack> items = takeAll(o.inv);
        if (e.getKeepInventory()) giveBack(p, items); else e.getDrops().addAll(items);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        endHuntsOn(e.getEntity(), "fell.");
    }
}
