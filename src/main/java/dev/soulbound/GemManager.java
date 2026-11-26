package dev.soulbound;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Creates/reads gem items and special items via PersistentDataContainer.
 * All lore strings are original (mechanic-accurate, not copied).
 */
public class GemManager {
    public final NamespacedKey keyIsGem;
    public final NamespacedKey keyGemType;
    public final NamespacedKey keyTier;
    public final NamespacedKey keyEnergy;
    public final NamespacedKey keyKind;

    private final Plugin plugin;
    /** Which lore lettering and lore version a gem was last written in, so styleLore only rebuilds on a change. */
    private final NamespacedKey keyLoreStyle = new NamespacedKey("soulbound-gems", "lore_style");
    /** A player's nickname for this gem (Nicknames), and who gave it (a mythic changing hands loses it). */
    private final NamespacedKey keyNickname = new NamespacedKey("soulbound-gems", "nickname");
    private final NamespacedKey keyNickOwner = new NamespacedKey("soulbound-gems", "nickname_owner");
    /** Bump when gem lore text changes, so existing gems rebuild their lore once (9: Life's two Circles of Life). */
    private static final int LORE_VERSION = 12; // 10: Amplification is sneak + right-click; 11: Auratus damage lines; 12: Flux

    public GemManager(Plugin plugin) {
        this.plugin = plugin;
        this.keyIsGem = new NamespacedKey("soulbound-gems", "blisslike_is_gem");
        this.keyGemType = new NamespacedKey("soulbound-gems", "blisslike_gem_type");
        this.keyTier = new NamespacedKey("soulbound-gems", "blisslike_tier");
        this.keyEnergy = new NamespacedKey("soulbound-gems", "blisslike_energy");
        this.keyKind = new NamespacedKey("soulbound-gems", "blisslike_kind");
    }

    public boolean isGem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta m = item.getItemMeta();
        return m.getPersistentDataContainer().has(keyIsGem, PersistentDataType.BYTE);
    }

    public GemType getType(ItemStack item) {
        if (!isGem(item)) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(keyGemType, PersistentDataType.STRING);
        return GemType.match(s);
    }

    public int getTier(ItemStack item) {
        if (!isGem(item)) return 1;
        if (isMythic(item)) return 0;
        Integer t = item.getItemMeta().getPersistentDataContainer().get(keyTier, PersistentDataType.INTEGER);
        return (t == null || t < 1) ? 1 : Math.min(t, 2);
    }

    public boolean isMythic(ItemStack item) {
        GemType type = getType(item);
        return type != null && type.isMythic();
    }

    public int getEnergy(ItemStack item) {
        if (!isGem(item)) return 0;
        GemType type = getType(item);
        if (type != null && type.isMythic()) return 0;
        Integer e = item.getItemMeta().getPersistentDataContainer().get(keyEnergy, PersistentDataType.INTEGER);
        return EnergyStage.clamp(e == null ? 5 : e);
    }

    public void setEnergy(ItemStack item, int energy) {
        if (!isGem(item) || isMythic(item)) return;
        energy = EnergyStage.clamp(energy);
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyEnergy, PersistentDataType.INTEGER, energy);
        item.setItemMeta(m);
        refreshLore(item);
        refreshVisuals(item);
    }

    public void setTier(ItemStack item, int tier) {
        if (!isGem(item) || isMythic(item)) return;
        tier = Math.max(1, Math.min(2, tier));
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyTier, PersistentDataType.INTEGER, tier);
        item.setItemMeta(m);
        item.setType(BlissPackVisuals.materialFor(getType(item), tier));
        refreshLore(item);
        refreshVisuals(item);
    }

    /** Tier-only overload: gem item at full energy (real energy is tracked per player in EnergyManager). */
    public ItemStack createGem(GemType type, int tier) {
        return createGem(type, tier, EnergyStage.clamp(10));
    }

    public ItemStack createGem(GemType type, int tier, int energy) {
        if (type != null && type.isMythic()) return createMythic(type);
        tier = Math.max(1, Math.min(2, tier));
        energy = EnergyStage.clamp(energy);
        ItemStack item = new ItemStack(BlissPackVisuals.materialFor(type, tier), 1);
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyIsGem, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(keyGemType, PersistentDataType.STRING, type.name());
        m.getPersistentDataContainer().set(keyTier, PersistentDataType.INTEGER, tier);
        m.getPersistentDataContainer().set(keyEnergy, PersistentDataType.INTEGER, energy);
        m.setDisplayName(displayName(type, null));
        item.setItemMeta(m);
        refreshLore(item);
        refreshVisuals(item);
        return item;
    }

    /** lore.small-caps: "capitals" (default), "all" or "off". true/false from older configs mean capitals/off. */
    private String loreStyle() {
        String s = String.valueOf(plugin.getConfig().getString("lore.small-caps", "capitals")).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "true", "capitals" -> "capitals";
            case "all" -> "all";
            default -> "off";
        };
    }

    /** Gem lore in the configured lettering (item-history lines are left alone). */
    private List<String> styled(List<String> lore) {
        return SmallCaps.lore(lore, loreStyle());
    }

    /** The same lettering for other mythic items' lore (the Revenant). */
    public List<String> styleLoreLines(List<String> lore) {
        return styled(GemNames.current().t(lore));
    }

    /**
     * Makes an existing gem's lore match the configured lettering. The lore is rebuilt from the gem's own
     * data (so a style change, even from every-letter small caps, comes out right), and any item-history
     * lines are carried over. The gem remembers its style, so this is one PDC read when nothing changed.
     */
    public void styleLore(ItemStack item) {
        if (!isGem(item)) return;
        String style = loreStyle() + ":" + LORE_VERSION + ":" + GemNames.current().version(); // a rename rebuilds it once
        ItemMeta meta = item.getItemMeta();
        if (meta == null || style.equals(meta.getPersistentDataContainer().get(keyLoreStyle, PersistentDataType.STRING))) return;
        List<String> history = new ArrayList<>();
        if (meta.hasLore()) for (String line : meta.getLore()) if (line != null && SmallCaps.isHistoryLine(line)) history.add(line);
        refreshLore(item);
        ItemMeta after = item.getItemMeta();
        if (!history.isEmpty()) {
            List<String> lore = after.hasLore() ? new ArrayList<>(after.getLore()) : new ArrayList<>();
            lore.addAll(history);
            after.setLore(lore);
        }
        after.getPersistentDataContainer().set(keyLoreStyle, PersistentDataType.STRING, style);
        GemType type = getType(item);
        if (type != null) after.setDisplayName(displayName(type, getNickname(item))); // the gem's name (or its nickname) as it is now
        item.setItemMeta(after);
    }

    /** Rebuilds a gem's name and lore now (after a rename, a nickname change, /gems names refresh). */
    public void restyle(ItemStack item) {
        if (!isGem(item)) return;
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        m.getPersistentDataContainer().remove(keyLoreStyle);
        item.setItemMeta(m);
        styleLore(item);
    }

    // ---- names (GemNames) and nicknames (Nicknames)

    /** A gem's item name: the server's name for it in its colour, or the owner's nickname in nicknames.format. */
    String displayName(GemType type, String nickname) {
        GemNames n = GemNames.current();
        if (nickname != null && !nickname.isEmpty())
            return Nicknames.display(plugin.getConfig().getString("nicknames.format", "{color}§l\"{nickname}\" §7({gem})"), nickname, n.gem(type), n.color(type));
        return n.color(type) + "§l" + n.gem(type) + " Gem";
    }

    public String getNickname(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyNickname, PersistentDataType.STRING);
    }

    public java.util.UUID nicknameOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(keyNickOwner, PersistentDataType.STRING);
        try { return s == null ? null : java.util.UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Sets (or with null clears) the nickname, and shows it at once. */
    public void setNickname(ItemStack item, String nickname, java.util.UUID owner) {
        if (!isGem(item)) return;
        ItemMeta m = item.getItemMeta();
        if (nickname == null) {
            m.getPersistentDataContainer().remove(keyNickname);
            m.getPersistentDataContainer().remove(keyNickOwner);
        } else {
            m.getPersistentDataContainer().set(keyNickname, PersistentDataType.STRING, nickname);
            if (owner != null) m.getPersistentDataContainer().set(keyNickOwner, PersistentDataType.STRING, owner.toString());
        }
        GemType type = getType(item);
        if (type != null) m.setDisplayName(displayName(type, nickname));
        item.setItemMeta(m);
    }

    /**
     * The lore with the server's names (GemNames): renamed abilities swapped in, the heading rewritten if the gem's
     * name or colour changed, and the tier names if those changed. With untouched names it's returned as it was.
     */
    private List<String> named(GemType type, List<String> lore) {
        GemNames n = GemNames.current();
        List<String> out = new ArrayList<>(n.t(lore));
        if (type != null && n.gemChanged(type) && !lore.isEmpty()) { // the heading, from the line as it was written
            String head = lore.get(0), old = type.display.toUpperCase(java.util.Locale.ROOT);
            int at = head.indexOf(old);
            if (at >= 0) out.set(0, n.color(type) + GemNames.plain(n.gem(type)).toUpperCase(java.util.Locale.ROOT) + head.substring(at + old.length()));
        }
        if (n.tiersChanged()) for (int i = 0; i < out.size(); i++) {
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("Tier ([0-9])\\b").matcher(out.get(i));
            StringBuilder b = new StringBuilder();
            while (t.find()) t.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(n.tier(Integer.parseInt(t.group(1)))));
            t.appendTail(b);
            out.set(i, b.toString());
        }
        return out;
    }

    /** Lore text. Strength layout matches user-provided screenshots; wording is user-supplied. */
    public void refreshLore(ItemStack item) {
        if (!isGem(item)) return;
        GemType type = getType(item);
        if (type != null && type.isMythic()) {
            refreshMythicLore(item, type);
            return;
        }
        int tier = getTier(item);
        int energy = getEnergy(item);
        String stage = EnergyStage.name(energy);
        ItemMeta m = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        if (type == GemType.STRENGTH) {
            lore.add("§cSTRENGTH §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage + " Gem");
            lore.add("§f§lHAVE THE STRENGTH OF A ARMY");
            lore.add("§c● §ePASSIVES");
            lore.add("§7— Strength");
            lore.add("§7— Enchants Sharpness");
            lore.add("§7— Bloodthorns");
            lore.add("§c● §bABILITY");
            lore.add("§7— §cBounty Hunter");
            lore.add("§c● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §c⚔ FRAILER ⚔");
                lore.add("§7— §c✖ NULLIFY ✖");
                lore.add("");
                lore.add("§7— §c↯ CHAD STRENGTH §a〰");
                lore.add("§7— §c↯ CHAD STRENGTH §a〰");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.FIRE) {
            lore.add("§cFIRE §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage + " Gem");
            lore.add("§f§lMANIPULATE FIRE");
            lore.add("§6● §ePASSIVES");
            lore.add("§7— Fire Resistance");
            lore.add("§7— Autosmelt");
            lore.add("§7— Flamestrike");
            if (tier >= 2) lore.add("§7— Fireshot");
            lore.add("§6● §bABILITY");
            lore.add("§7— §6Crisp");
            lore.add("§6● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §c🔥 FIREBALL 🔥");
                lore.add("§7— §c☄ METEOR SHOWER ☄");
                lore.add("");
                lore.add("§7— §6♨ COZY CAMPFIRE");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.WEALTH) {
            lore.add("§2WEALTH §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage + " Gem");
            lore.add("§f§lFUEL A EMPIRE");
            lore.add("§2● §ePASSIVES");
            lore.add("§7— Hero of the Village");
            lore.add("§7— Luck");
            lore.add("§7— Enchants Mending");
            lore.add("§7— Enchants Fortune");
            lore.add("§7— Enchants Looting");
            lore.add("§7— Bonus Ores");
            lore.add("§7— Extra EXP");
            lore.add("§7— Durability Chip");
            lore.add("§7— Double Debris");
            lore.add("§2● §bABILITY");
            lore.add("§7— §aPockets");
            lore.add("§2● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §c☘ UNFORTUNE ☘");
                lore.add("§7— §c⚿ ITEM LOCK ⚿");
                lore.add("");
                lore.add("§7— §a$ RICH RUSH $");
                lore.add("§7— §a✚ AMPLIFICATION ✚ §8(sneak + right-click)");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.SPEED) {
            lore.add("§eSPEED §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage + " Gem");
            lore.add("§f§lWATCH THE WORLD AROUND YOU TURN INTO A BLUR");
            lore.add("§e● §ePASSIVES");
            lore.add("§7— Speed");
            lore.add("§7— Dolphins grace");
            lore.add("§7— Enchants Efficiency");
            lore.add("§7— Enchants Soul Speed");
            lore.add("§e● §bABILITY");
            lore.add("§7— §eThunderstep");
            lore.add("§e● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §e➶ BLUR");
                lore.add("");
                lore.add("§7— §b➶ SPEEDY STORM §c🔥");
                lore.add("§7— §b➶ SPEEDY STORM §c🔥");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.PUFF) {
            lore.add("§fPUFF §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage);
            lore.add("§f§lBE THE BIGGEST BIRD");
            lore.add("§f● §ePASSIVES");
            lore.add("§7— Fall Damage immunity");
            lore.add("§7— Enchants Power");
            lore.add("§7— Enchants Punch");
            lore.add("§7— Sculk Silence");
            lore.add("§7— Crop Tramp-Less");
            lore.add("§f● §bABILITY");
            lore.add("§7— §fDouble Jump");
            lore.add("§f● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §f♨ BREEZY BASH §c🔥");
                lore.add("§7— §f♨ BREEZY BASH §c🔥");
                lore.add("");
                lore.add("§7— §f➶ DASH");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.ASTRA) {
            lore.add("§dASTRA §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage);
            lore.add("§f§lMANAGE THE TIDES OF THE COSMOS");
            lore.add("§d● §ePASSIVES");
            lore.add("§7— Phasing");
            lore.add("§7— Soul Healing");
            lore.add("§7— Soul Capture");
            lore.add("§d● §bABILITY");
            lore.add("§7— §dDimensional Drift §8[Offhand: right-click]");
            lore.add("§d● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §d🔪 DAGGERS §c🔥 §8[Left-click]");
                lore.add("§7— §d⛓ UNBOUNDED §c🔥 §8[Right-click a player]");
                lore.add("");
                lore.add("§7— §5👁 ASTRAL PROJECTION §a〰 §8[Sneak + right-click]");
                lore.add("§7— §5◉ ASTRAL VOID §a〰 §8[Right-click]");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.LIFE) {
            lore.add("§cLIFE §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage + " Gem");
            lore.add("§f§lCONTROL THE BALANCE OF LIFE");
            lore.add("§c● §ePASSIVES");
            lore.add("§7— Green Thumb");
            lore.add("§7— Radiant Fist");
            lore.add("§7— Bonus Saturation");
            lore.add("§7— Bonus Absorption");
            lore.add("§7— Wither Immune");
            lore.add("§c● §bABILITY");
            lore.add("§7— §dVitality Vortex §8[Offhand: right-click, anywhere]");
            lore.add("§c● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §c♥ HEART DRAINER §c🔥");
                lore.add("§7— §c♥ HEARTLOCK §c🔥");
                lore.add("");
                lore.add("§7— §d☯ CIRCLE OF LIFE §a〰 §8[Right-click an enemy: steal 5 hearts]");
                lore.add("§7— §d☯ CIRCLE OF LIFE §a〰 §8[Right-click: bless your side]");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        if (type == GemType.FLUX) {
            lore.add("§eFLUX §eGEM");
            lore.add("§bEnergy:");
            lore.add("§3" + stage);
            lore.add("§f§lEVERYTHING IS A FLUCTUATION");
            lore.add("§e● §ePASSIVES");
            lore.add("§7— Flow State");
            lore.add("§7— Shocking Chance");
            lore.add("§7— Tireless");
            lore.add("§7— Conduction");
            lore.add("§7— Charged");
            lore.add("§e● §bABILITY");
            lore.add("§7— §bKinetic Burst");
            lore.add("§e● §bPOWERS");
            if (tier >= 2) {
                lore.add("§7— §e⚡ ENERGY BEAM §c🔥 §8[Q charges, left-click fires]");
                lore.add("§7— §e◉ GROUND ◉ §8[Left-click a player at 100 W]");
                lore.add("§7— §fOVERCHARGE §8[Charge past 100 W: up to 200]");
                lore.add("");
                lore.add("§7— §b≫ KINETIC OVERDRIVE §a〰 §8[Right-click when FULL]");
            } else {
                lore.add("§8Unknown");
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Tier " + tier + " • Energy " + energy + "/10");
            if (energy == 0) lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers.");
            else if (energy == 1) lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
            else if (energy == 2) lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
            m.setLore(styled(named(type, lore)));
            item.setItemMeta(m);
            return;
        }
        lore.add(ChatColor.GRAY + "Inspired gem • " + type.shown() + " • Tier " + tier);
        lore.add(ChatColor.YELLOW + "Energy: " + energy + "/10 (" + stage + ")");
        lore.add("");
        lore.add(ChatColor.LIGHT_PURPLE + "Passive: " + ChatColor.GRAY + type.passiveBlurb);
        lore.add(ChatColor.AQUA + "Power [Right-click]: " + ChatColor.GRAY + type.powerBlurb);
        if (tier >= 2) {
            lore.add(ChatColor.GREEN + "T2 [Shift+Right-click]: " + ChatColor.GRAY + type.tier2Blurb);
        } else {
            lore.add(ChatColor.DARK_GRAY + "Upgrade to T2 with an Upgrader for a 2nd ability.");
        }
        lore.add("");
        if (energy == 0) {
            lore.add(ChatColor.DARK_RED + "BROKEN: no passives or powers. Restore it!");
        } else if (energy == 1) {
            lore.add(ChatColor.RED + "RUINED: powers only, no passives.");
        } else if (energy == 2) {
            lore.add(ChatColor.RED + "SHATTERED: passives only, no powers.");
        } else if (energy == 3 || energy == 4) {
            lore.add(ChatColor.GOLD + "Cooldowns 1.5x longer at this stage.");
        } else if (energy >= 5) {
            lore.add(ChatColor.GREEN + "Hold in hand/offhand for live status above hotbar.");
        }
        lore.add(ChatColor.DARK_GRAY + "Keep in offhand for passives, main hand to use.");
        m.setLore(styled(named(type, lore)));
        // T2 glow hint via enchant glint handled by listener (Glowing potion on player instead for simplicity)
        item.setItemMeta(m);
    }

    /** Applies the exact CustomModelData/material binding from the user-provided Bliss pack. */
    public void refreshVisuals(ItemStack item) {
        if (!isGem(item)) return;
        GemType type = getType(item);
        if (type == null || type.isMythic()) return;
        int tier = getTier(item);
        int energy = getEnergy(item);
        item.setType(BlissPackVisuals.materialFor(type, tier));
        ItemMeta m = item.getItemMeta();
        int cmd = BlissPackVisuals.modelDataFor(type, tier, energy);
        m.setCustomModelData(cmd < 0 ? null : cmd);
        item.setItemMeta(m);
    }

    static final String RIFT_LORE = "§7— §x§B§8§9§3§5§8✦ RIFT §8[Sneak + left-click gem]";

    /** Migrates old Relay items without replacing item identity or model data. */
    public static void prepareRelayItem(ItemStack item) {
        // Remove the legacy hold-to-consume input without touching PDC/model data.
        if (item.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE))
            item.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE);
        var meta=item.getItemMeta();
        if(meta!=null && meta.hasLore()) {
            var lore=meta.getLore();boolean changed=false;
            for(int i=0;i<lore.size();i++) {
                String old=lore.get(i), line=old.replace("Tuned In §8[Offhand: hold RMB 0.5s]","Tuned In §8[Passive combat charge]")
                    .replace("⌛ TIME DILATION","⌛ TIME DILATE §8[Right-click gem]");
                if(line.endsWith("⟲ REWIND"))line+=" §8[Left-click gem]";
                if(!line.equals(old)){lore.set(i,line);changed=true;}
            }
            if(changed) lore.add("§7— §x§B§8§9§3§5§8\uE045 TEMPORAL DIVINITY §8[Sneak + right-click gem]");
            // Relay items made before Rift existed get its line, once, right after Temporal Divinity.
            int divinity=-1;boolean hasRift=false;
            // Compared in plain capitals, because the lore may already be in small caps.
            for(int i=0;i<lore.size();i++) {String l=SmallCaps.plain(lore.get(i));if(l.contains("✦ RIFT"))hasRift=true;if(l.contains("TEMPORAL DIVINITY"))divinity=i;}
            if(!hasRift && divinity>=0){lore.add(divinity+1,RIFT_LORE);changed=true;}
            if(changed) {meta.setLore(lore);item.setItemMeta(meta);}
        }
    }

    public ItemStack createMythic(GemType type) {
        if (type == null || !type.isMythic()) throw new IllegalArgumentException("Not a mythic gem: " + type);
        ItemStack item = new ItemStack(type.material, 1);
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyIsGem, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(keyGemType, PersistentDataType.STRING, type.name());
        m.getPersistentDataContainer().set(keyTier, PersistentDataType.INTEGER, 0);
        m.getPersistentDataContainer().set(keyEnergy, PersistentDataType.INTEGER, 0);
        m.setDisplayName(displayName(type, null));
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        int cmd = switch (type) {
            case HERETIC -> 12360;
            case RELAY -> 12365; // 12362 = pack's gold gem, 12363/12364 are range separators
            default -> 12361;
        };
        m.setCustomModelData(cmd);
        item.setItemMeta(m);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        refreshMythicLore(item, type);
        if (type == GemType.RELAY) prepareRelayItem(item);
        return item;
    }

    private void refreshMythicLore(ItemStack item, GemType type) {
        ItemMeta m = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        if (type == GemType.HERETIC) {
            lore.add("§dHERETIC GEM");
            lore.add("§f§lFEAR IS THE ONLY SALVATION");
            lore.add("§5(Mythic)");
            lore.add("");
            lore.add("§c♦ §ePASSIVES");
            lore.add("§7— Hemorrhage");
            lore.add("§7— Enduring Strength");
            lore.add("§7— Auto-Crit");
            lore.add("§7— Blood Hardening");
            lore.add("");
            lore.add("§c♦ §bPOWERS");
            lore.add("§7— §4☠ BLOODSAWS");
            lore.add("§7— §4⚔ BLOODLINKING");
            lore.add("");
            lore.add("§8Mythic item • ignores the normal energy system");
        } else if (type == GemType.AURATUS) {
            lore.add("§6AURATUS GEM");
            lore.add("§f§lHONOR IS THE WEAPON");
            lore.add("§5(Mythic)");
            lore.add("");
            lore.add("§6♦ §ePASSIVES");
            lore.add("§7— Feathered Fall §8[90% less fall damage]");
            lore.add("§7— Hauling Strike §8[+1 reach, full hits pull in]");
            lore.add("§7— Angels Grasp §8[Your chains stop escapes]");
            lore.add("§7— Divine Purity §8[Debuffs last half as long]");
            lore.add("");
            lore.add("§6♦ §bPOWERS");
            lore.add("§7— §6⛓ VENERATED PERFORATORS §8[Left-click]");
            lore.add("§7— §e✧ ECHOING AEGIS §8[Right-click]");
            lore.add("");
            lore.add("§8Through full diamond: chain 2.5❤, shield 4❤, launch 9.9❤");
            lore.add("§8Armour counts, Protection doesn't");
            lore.add("");
            lore.add("§8Mythic item • ignores the normal energy system");
        } else if (type == GemType.RELAY) {
            lore.add("§x§B§8§9§3§5§8RELAY GEM");
            lore.add("§f§lTIME IS JUST A SIGNAL");
            lore.add("§5(Mythic)");
            lore.add("");
            lore.add("§x§B§8§9§3§5§8♦ §ePASSIVES");
            lore.add("§7— Tuned In §8[Passive combat charge]");
            lore.add("§7— Quickened");
            lore.add("§7— Clean Signal");
            lore.add("");
            lore.add("§x§B§8§9§3§5§8♦ §bPOWERS");
            lore.add("§7— §x§B§8§9§3§5§8⟲ REWIND §8[Left-click gem]");
            lore.add("§7— §x§B§8§9§3§5§8⌛ TIME DILATE §8[Right-click gem]");
            lore.add("§7— §x§B§8§9§3§5§8\uE045 TEMPORAL DIVINITY §8[Sneak + right-click gem]");
            lore.add(RIFT_LORE);
            lore.add("");
            lore.add("§8Mythic item • ignores the normal energy system");
        }
        m.setLore(styled(named(type, lore)));
        item.setItemMeta(m);
    }

    // ---- special items (kind stored in PDC) ----

    private ItemStack kinded(Material mat, String kind, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyKind, PersistentDataType.STRING, kind);
        m.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        for (String s : loreLines) lore.add(s);
        m.setLore(lore);
        item.setItemMeta(m);
        return item;
    }

    public String getKind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyKind, PersistentDataType.STRING);
    }

    public ItemStack createUpgrader() {
        return kinded(Material.NETHER_STAR, "upgrader",
                ChatColor.YELLOW + "Gem Upgrader",
                ChatColor.GRAY + "Keep a Tier 1 gem anywhere in",
                ChatColor.GRAY + "your inventory, then right-click.");
    }

    public ItemStack createTrader() {
        return kinded(Material.CLOCK, "trader",
                ChatColor.AQUA + "Gem Trader",
                ChatColor.GRAY + "Right-click while holding a gem",
                ChatColor.GRAY + "to randomize its type (keeps tier/energy).");
    }

    public ItemStack createRepairKit() {
        return kinded(Material.NAUTILUS_SHELL, "repairkit", 200,
                ChatColor.GREEN + "Repair Item",
                ChatColor.GRAY + "Used to restore a damaged gem.");
    }

    public ItemStack createReviveItem() {
        return kinded(Material.NAUTILUS_SHELL, "reviveitem_closed", 210,
                ChatColor.AQUA + "Revive Item",
                ChatColor.GRAY + "Bliss special item.");
    }

    public ItemStack createRevivalItem() {
        return kinded(Material.NAUTILUS_SHELL, "revivalitem_open", 220,
                ChatColor.GREEN + "Revival Item",
                ChatColor.GRAY + "Bliss special item.");
    }

    public ItemStack createSpiritDagger() {
        return kinded(Material.NAUTILUS_SHELL, "spiritdagger", 230,
                ChatColor.LIGHT_PURPLE + "Spirit Dagger",
                ChatColor.GRAY + "Bliss special item.");
    }

    public ItemStack createFragment() {
        return kinded(Material.NAUTILUS_SHELL, "fragment", 240,
                ChatColor.LIGHT_PURPLE + "Gem Fragment",
                ChatColor.GRAY + "Crafting component for gem items.");
    }

    public ItemStack createEnchantedObsidian() {
        return kinded(Material.OBSIDIAN, "eobsidian",
                ChatColor.DARK_PURPLE + "Enchanted Obsidian",
                ChatColor.GRAY + "Seat it in the ancient portal.",
                ChatColor.GRAY + "Eight open the Nether.");
    }

    public ItemStack createTotemToken() {
        return kinded(Material.NAUTILUS_SHELL, "totemtoken", 250,
                ChatColor.GOLD + "Totem Token",
                ChatColor.GRAY + "Bliss special item.");
    }

    public ItemStack createEnergyBottle() {
        return kinded(Material.NAUTILUS_SHELL, "energybottle", 300,
                ChatColor.YELLOW + "Bottle of Energy",
                ChatColor.GRAY + "Right-click to absorb +1 energy.");
    }

    public ItemStack createRemote() {
        return kinded(Material.GLOBE_BANNER_PATTERN, "remote", 310,
                ChatColor.AQUA + "Remote",
                ChatColor.GRAY + "Bliss special item.");
    }

    private ItemStack kinded(Material mat, String kind, int customModelData, String name, String... loreLines) {
        ItemStack item = kinded(mat, kind, name, loreLines);
        ItemMeta m = item.getItemMeta();
        m.setCustomModelData(customModelData);
        item.setItemMeta(m);
        return item;
    }

}
