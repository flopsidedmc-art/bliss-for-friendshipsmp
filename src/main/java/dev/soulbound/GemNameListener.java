package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

import java.util.UUID;

/**
 * Names on the gems players carry. On join and on a hotbar switch a gem picks up the server's current names
 * (GemNames). Renaming your gem on an anvil sets its nickname (Nicknames; the same rules and cooldown as
 * /gem nickname). A mythic's nickname is wiped when it changes hands (nicknames.mythic-nicknames).
 */
final class GemNameListener implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final NamesCommand names;

    GemNameListener(SoulboundGemsPlugin plugin, NamesCommand names) {
        this.plugin = plugin;
        this.names = names;
    }

    /** A mythic someone else nicknamed loses the nickname in your hands. True if it did. */
    private boolean wipeIfNotTheirs(Player p, ItemStack it) {
        GemManager g = plugin.gems();
        if (!g.isGem(it) || !g.isMythic(it) || g.getNickname(it) == null) return false;
        UUID owner = g.nicknameOwner(it);
        if (owner != null && owner.equals(p.getUniqueId())) return false;
        g.setNickname(it, null, null);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            for (ItemStack it : p.getInventory().getContents()) wipeIfNotTheirs(p, it);
            names.refresh(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (!plugin.gems().isGem(it)) return;
        wipeIfNotTheirs(e.getPlayer(), it);
        plugin.gems().styleLore(it); // only rebuilds if the names changed since
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack st = e.getItem().getItemStack();
        if (wipeIfNotTheirs(p, st)) e.getItem().setItemStack(st);
    }

    /** An anvil rename of your gem is its nickname: checked against the rules, or no result (and the reason on the action bar). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        AnvilView v = e.getView();
        ItemStack first = v.getTopInventory().getItem(0), second = v.getTopInventory().getItem(1);
        if (!plugin.gems().isGem(first)) return;
        if (!(v.getPlayer() instanceof Player p)) return;
        if (second != null && second.getType() != Material.AIR) return; // not a rename: left as it was
        if (!names.nameable(first)) { e.setResult(null); return; }
        String text = v.getRenameText();
        String now = plugin.gems().getNickname(first);
        String shown = first.hasItemMeta() ? GemNames.plain(first.getItemMeta().getDisplayName()) : "";
        if (text == null || text.isBlank() || text.equals(shown) || text.equals(now)) { e.setResult(null); return; } // nothing to do (never an unnamed gem)
        long wait = names.cooldownLeft(p);
        if (wait > 0 && !p.hasPermission(NamesCommand.ADMIN)) {
            e.setResult(null);
            p.sendActionBar(Component.text("You can rename your gem again in " + HudStatus.time(wait) + ".", NamedTextColor.RED));
            return;
        }
        Nicknames.Result r = Nicknames.check(text, names.rules(), p.hasPermission(NamesCommand.COLOUR));
        if (!r.ok()) {
            e.setResult(null);
            p.sendActionBar(Component.text(r.error(), NamedTextColor.RED));
            return;
        }
        ItemStack out = first.clone();
        plugin.gems().setNickname(out, r.value(), p.getUniqueId());
        e.setResult(out);
        v.setRepairCost(1);
    }

    /** Taking a nicknamed gem out of the anvil starts the cooldown. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() != InventoryType.ANVIL || e.getRawSlot() != 2) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack out = e.getCurrentItem(), in = e.getView().getTopInventory().getItem(0);
        if (!plugin.gems().isGem(out)) return;
        String a = plugin.gems().getNickname(out), b = plugin.gems().getNickname(in);
        if (a != null && !a.equals(b)) names.startCooldown(p);
    }
}
