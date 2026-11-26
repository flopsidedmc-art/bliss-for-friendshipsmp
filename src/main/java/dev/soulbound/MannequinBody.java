package dev.soulbound;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Astral Projection's body as a Mannequin (Paper 1.21.9+): your skin, your armour, standing still where you were.
 * Also Speed's Blur clones (spawnClone). Kept in its own class so a server without Mannequin never loads it; AstralSpectate
 * falls back to a zombie there, and Blur to vexes.
 */
final class MannequinBody {
    private MannequinBody() {}

    static boolean available() {
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A Blur clone (BlurClones): your skin, armour and held item, no name or description. */
    static LivingEntity spawnClone(Player p, Location at) {
        return at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, m -> {
            m.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
            m.setImmovable(true);
            m.setDescription(Component.empty());
            m.setPersistent(false);
            BlurClones.dress(m, p);
        });
    }

    static LivingEntity spawn(Player p, Location at) {
        return at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, m -> {
            m.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
            m.setImmovable(true);
            m.setDescription(Component.empty()); // no "NPC" line under the name
            m.setPersistent(false); // never saved: a crash can't leave a body behind
            m.setRemoveWhenFarAway(false);
            AstralSpectate.dress(m, p, false);
        });
    }
}
