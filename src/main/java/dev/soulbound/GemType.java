package dev.soulbound;

import org.bukkit.Material;

/**
 * Original Bliss-inspired gem definitions.
 * Gem definitions. All display text is original wording.
 */
public enum GemType {
    STRENGTH("Strength", "§c", Material.REDSTONE, "⚔", "❖",
            "Strength 1 while held. Swords/axes auto-Sharpness. Lower HP = more damage (Bloodthorns).",
            "Bounty Hunter: track a player using an item they owned (player head in offhand + right-click).",
            "Powers T2: Frailer [Hit] armor-cut, Nullify [Left-click] clear+block potions 30s, Chad Strength [Right-click] team crit-doubler."),
    FIRE("Fire", "§6", Material.BLAZE_POWDER, "✹", "♨",
            "Fire Resistance while held. Tools auto-Flame/Fire Aspect. Attackers may ignite (Flamestrike). Smelt flavor (Autosmel/Fireshot).",
            "Crisp [offhand right-click]: Nether-ify area, hurt untrusted, smelt drops.",
            "Powers T2: Fireball [Left-click x2 charge+release, no self damage], Meteor Shower [Hit 30s], Cozy Campfire [Right-click block]."),
    SPEED("Speed", "§b", Material.FEATHER, "➶", "☁",
            "Speed 1 + Dolphin's Grace while held. Tools auto-Efficiency, boots Soul Speed.",
            "Thunderstep [offhand right-click]: swap with aimed player (10m) or dash + Speed 4 (5s).",
            "Powers T2: Blur [Hit] clones attack victim, Speedy Storm [Right-click] lightning zone."),
    WEALTH("Wealth", "§a", Material.EMERALD, "☘", "$",
            "Luck + Hero of the Village while held. Tools auto-Looting/Fortune/Mending (offhand). Bonus ores, XP, double debris.",
            "Pockets [offhand right-click]: open a 9-slot extra inventory.",
            "Powers T2: Unfortunate [Hit] 50% action cancel, Item Lock [Left-click] lock consumables, Rich Rush [Right-click] double ores, Amplification [Right-click entity] +1 enchants 30s."),
    FLUX("Flux", "§e", Material.COPPER_INGOT, "⚡", "◉",
            "Flow State speed/haste that builds as you move/mine. Immune Hunger/Slowness/Weakness. Hits taken charge it; arrows may stun.",
            "Kinetic Burst [offhand hold right-click]: unleash stored charge as AoE + knockback.",
            "Powers T2: Energy Beam [Left-click] wattage beam, Ground [Hit] wattage stun (100% scrambles), Overdrive [Right-click] every 3rd hit taken empowers next hit."),
    ASTRA("Astra", "§d", Material.AMETHYST_SHARD, "✦", "❂",
            "Phasing: dodge hits (10% T1 / 15% T2). Soul Healing on kills.",
            "Dimensional Drift [offhand right-click]: ghost horse ride (T1) or speed surge (T2), 5s.",
            "Powers T2: Daggers [Left-click] gem-disabling shots, Unbounded [Hit] ride victims, Projection [Right-click] ghost form, Void [Right-click entity] mass disable."),
    LIFE("Life", "§c", Material.GHAST_TEAR, "♥", "✚",
            "Regen + Absorption 2 over time. Bonus absorption from gapples. Wither immune.",
            "Vitality Vortex [offhand right-click]: drain logs/leaves/flowers into Resistance + Regen + Absorption.",
            "Powers T2: Heart Drainer [Left-click] cap foes at 8 hearts, Heartlock [Hit] freeze victim at current HP 15s, Circle of Life [Right-click] +4 hearts to trusted 30s."),
    PUFF("Puff", "§f", Material.WIND_CHARGE, "☘", "≋",
            "No fall damage while held. Bows auto-Power/Punch. Hits may levitate or slow-fall victims.",
            "Double Jump [offhand right-click]: jump again mid-air.",
            "Powers T2: Breezy Bash [Hit] launch up + slam, Breezy Bash [Left-click] radial launch, Dash [Right-click] 40-block dash, 2.5 hearts/tick x3 ticks."),
    HERETIC("Heretic", "§d", Material.REPEATING_COMMAND_BLOCK, "☠", "✹",
            "Mythic: ignores the energy system. Hemorrhage doubles damage to bleeding targets; Strength lasts longer; hits auto-crit; incoming hits are capped.",
            "Bloodsaws [Right-click]: fire two fast, piercing saws that reflect off terrain.",
            "Bloodlinking [Left-click]: launch upward and chain repeated ground slams that can bloodlink players."),
    AURATUS("Auratus", "§6", Material.REPEATING_COMMAND_BLOCK, "⛓", "✧",
            "Mythic: Haste II while crouching, greatly reduced fall damage, extended reach and forward knockback. Angels Grasp can pull attackers toward you or cut their armor.",
            "Venerated Perforators [Right-click]: grapple terrain, entities, or a temporary Aegis anchor.",
            "Echoing Aegis [Left-click]: brief parry that reflects attacks, heals, and creates a short-lived sky anchor."),
    RELAY("Relay", "§x§B§8§9§3§5§8", Material.REPEATING_COMMAND_BLOCK, "⌛", "⟲",
            "Mythic: ignores the energy system. Quickened makes your swings 1.2x faster. Tuned In tags players you hit for 30s (bonus damage from you); your hits also fill a compass, and when full you hold right-click for 0.5 seconds with Relay in your offhand and the compass needle points at them. Clean Signal makes you immune to gem disables.",
            "Rewind [Right-click]: snap back to where you were ~5s ago, restoring lost health and clearing debuffs. Two charges.",
            "Time Dilation [Left-click]: a time bubble that slows everything inside, disables gems, and makes projectiles crawl. A direct hit with the gem freezes that player (Time Lock).");

    public final String display;
    public final String color;
    public final Material material;
    public final String powerIcon;
    public final String abilityIcon;
    public final String passiveBlurb;
    public final String powerBlurb;
    public final String tier2Blurb;

    GemType(String display, String color, Material material,
            String powerIcon, String abilityIcon,
            String passiveBlurb, String powerBlurb, String tier2Blurb) {
        this.display = display;
        this.color = color;
        this.material = material;
        this.powerIcon = powerIcon;
        this.abilityIcon = abilityIcon;
        this.passiveBlurb = passiveBlurb;
        this.powerBlurb = powerBlurb;
        this.tier2Blurb = tier2Blurb;
    }

    public String coloredName() {
        return color + display + " Gem";
    }

    public boolean isMythic() {
        return this == HERETIC || this == AURATUS || this == RELAY;
    }

    public static GemType match(String s) {
        if (s == null) return null;
        for (GemType g : values()) {
            if (g.name().equalsIgnoreCase(s) || g.display.equalsIgnoreCase(s)) return g;
        }
        return null;
    }
}
