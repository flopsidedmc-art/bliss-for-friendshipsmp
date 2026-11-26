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
            "Crisp [offhand right-click]: the land catches fire for 30s (Nether patches, fire, lava pits, water steamed away), then cools back; hurts untrusted, smelts drops.",
            "Powers T2: Fireball [Left-click x2 charge+release, no self damage], Meteor Shower [Hit 30s], Cozy Campfire [Right-click block]."),
    SPEED("Speed", "§b", Material.FEATHER, "➶", "☁",
            "Speed 1 + Dolphin's Grace while held. Tools auto-Efficiency, boots Soul Speed.",
            "Thunderstep [offhand right-click]: swap with aimed player (10m) or dash + Speed 4 (5s).",
            "Powers T2: Blur [Hit with the gem] clones attack victim until they hit back or you stop hitting, Speedy Storm [Right-click] lightning zone, 3 charges."),
    WEALTH("Wealth", "§a", Material.EMERALD, "☘", "$",
            "Luck + Hero of the Village while held. Tools auto-Looting/Fortune/Mending (offhand). Bonus ores, XP, double debris.",
            "Pockets [offhand right-click]: open a 9-slot extra inventory.",
            "Powers T2: Unfortunate [Hit] 50% action cancel, Item Lock [Left-click] lock consumables, Rich Rush [Right-click] double ores, Amplification [Sneak + Right-click] +1 enchants 30s for you and trusted players within 8 blocks."),
    FLUX("Flux", "§e", Material.COPPER_INGOT, "⚡", "◉",
            "Flow State speed/haste that builds as you move/mine. Immune Hunger/Slowness/Weakness. Hits taken charge it; arrows may stun.",
            "Kinetic Burst [offhand hold right-click]: unleash stored charge as AoE + knockback.",
            "Powers T2: Energy Beam [Left-click] wattage beam, Ground [Hit] wattage stun (100% scrambles), Overdrive [Right-click] every 3rd hit taken empowers next hit."),
    ASTRA("Astra", "§d", Material.AMETHYST_SHARD, "✦", "❂",
            "Phasing: dodge hits (10% T1 / 15% T2), vanishing for a moment. Soul Healing on kills.",
            "Dimensional Drift [offhand right-click]: a ghost horse ride, 5s (both tiers).",
            "Powers T2: Daggers [Left-click] five behind you, one per click, gem-disabling; Unbounded [Right-click a player] see through their eyes; Astral Void [Right-click anywhere else] mass disable; Astral Projection [Sneak + right-click] leave your body as a spectator and tag players."),
    LIFE("Life", "§c", Material.GHAST_TEAR, "♥", "✚",
            "Regen + Absorption 2 over time. Bonus absorption from gapples. Wither immune.",
            "Vitality Vortex [offhand right-click]: works anywhere; drains the life out of the land around you (it grows back in 30s) into Resistance + Regen + Absorption.",
            "Powers T2: Heart Drainer [Left-click] cap foes at 8 hearts, Heartlock [Hit] freeze victim at current HP 15s, Circle of Life [Right-click an enemy] steal 5 hearts, or [Right-click anywhere else] bless you and your trusted players (+4 hearts 30s, full hunger, Regen II)."),
    PUFF("Puff", "§f", Material.WIND_CHARGE, "☘", "≋",
            "No fall damage while held. Bows auto-Power/Punch. Hits may levitate or slow-fall victims.",
            "Double Jump [offhand right-click]: jump again mid-air.",
            "Powers T2: Breezy Bash [Hit] launch up + slam, Breezy Bash [Left-click] radial launch, Dash [Right-click] 40-block dash, hurts anyone you pass every second for 3s."),
    HERETIC("Heretic", "§d", Material.REPEATING_COMMAND_BLOCK, "☠", "✹",
            "Mythic: ignores the energy system. Hemorrhage doubles damage to bleeding targets; Strength lasts longer; hits auto-crit; incoming hits are capped.",
            "Bloodsaws [Left-click; off hand: right-click]: fire two fast, piercing saws that reflect off terrain.",
            "Bloodlinking [Right-click]: launch upward and chain repeated ground slams that can bloodlink players."),
    AURATUS("Auratus", "§6", Material.REPEATING_COMMAND_BLOCK, "⛓", "✧",
            "Mythic: 90% less fall damage, +1 reach and full-strength hits that pull the target in, chain and slam victims can't pearl or wind-charge away, and debuffs last half as long.",
            "Venerated Perforators [Left-click; off hand: right-click]: a chain hooks what it hits and stays; right-click goes there, swap hands hauls a creature in (or lets go). Sneak + chain in the air: Gilded Slam.",
            "Echoing Aegis [Right-click; off hand: sneak + right-click]: a 1s parry and a shield 3 blocks ahead that hits whoever's there. Pull someone in and catch them: Flagpole, or chain again to launch them."),
    RELAY("Relay", "§x§B§8§9§3§5§8", Material.REPEATING_COMMAND_BLOCK, "⌛", "⟲",
            "Mythic: ignores the energy system. Tuned In: your hits tag players for 30s (+5% damage from you) and fill the Tuned In bar; every Relay power needs it full. Quickened: swings 1.2x faster. Clean Signal: immune to gem disables.",
            "Rewind [Left-click]: snap back to where you were ~5s ago, restoring lost health and clearing debuffs. Two charges. Time Dilate [Right-click]: an 8s bubble where everything else moves at a tenth of the speed and gems are disabled.",
            "Temporal Divinity [Sneak + right-click]: take nearby opponents into the void for 8s. Rift [Sneak + left-click]: travel between your two anchors.");

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

    /** The name players see (names.yml; the default is {@link #display}). */
    public String shown() { return GemNames.current().gem(this); }

    /** The colour players see (names.yml; the default is {@link #color}). */
    public String tint() { return GemNames.current().color(this); }

    public String coloredName() {
        return tint() + shown() + " Gem";
    }

    public boolean isMythic() {
        return this == HERETIC || this == AURATUS || this == RELAY;
    }

    /**
     * What a Trader can turn {@code current} into: every standard gem except the current one. Never a mythic (they are
     * one of a kind), and a mythic can't be traded at all, so it gets an empty pool.
     */
    public static java.util.List<GemType> tradePool(GemType current) {
        java.util.List<GemType> pool = new java.util.ArrayList<>();
        if (current == null || current.isMythic()) return pool;
        for (GemType g : values()) if (!g.isMythic() && g != current) pool.add(g);
        return pool;
    }

    public static GemType match(String s) {
        if (s == null) return null;
        for (GemType g : values()) {
            if (g.name().equalsIgnoreCase(s) || g.display.equalsIgnoreCase(s)) return g;
        }
        return null;
    }
}
