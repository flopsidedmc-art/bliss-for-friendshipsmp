package dev.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every gem, ability and tier name players see (names.yml), so a server can rename them all (GemNamesTest). Internal
 * ids (FIRE, AURATUS, crisp ...) never change, so configs and saves keep working. The defaults are exactly the names
 * the plugin always used, and text only changes where a name was changed: with an untouched names.yml, every item,
 * message and menu reads exactly as before.
 *
 * Gem names and colours are looked up where they're used (GemType.shown, tint). Ability names inside messages and
 * lore go through {@link #t}, which swaps a renamed name (as written, and in CAPITALS for the lore headings) for the
 * new one.
 */
final class GemNames {
    private static volatile GemNames current; // made on first use: the default tables below must be filled in first

    /** The names in use (the defaults until the plugin has read names.yml). */
    static GemNames current() {
        GemNames c = current;
        if (c == null) current = c = new GemNames();
        return c;
    }

    static void use(GemNames n) { current = n; }

    /** A message or label as players see it: any renamed ability or mythic in it swapped for its new name ({@link #t}). */
    static String tx(String text) { return current().t(text); }

    /** Each gem's abilities and named passives: id, then the name it always had. Generic stats (Speed, Luck ...) aren't names. */
    static final Map<GemType, LinkedHashMap<String, String>> DEFAULTS = new EnumMap<>(GemType.class);
    /** Other ways the plugin wrote a name: they're swapped for the new name too. */
    private static final Map<String, String> ALIASES = Map.of("Unfortunate", "WEALTH.unfortune", "Aegis", "AURATUS.aegis",
            "Time Dilation", "RELAY.dilate");

    private static void def(GemType t, String... idName) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < idName.length; i += 2) m.put(idName[i], idName[i + 1]);
        DEFAULTS.put(t, m);
    }

    static {
        def(GemType.STRENGTH, "nullify", "Nullify", "chad", "Chad Strength", "bounty", "Bounty Hunter", "frailer", "Frailer", "bloodthorns", "Bloodthorns");
        def(GemType.FIRE, "fireball", "Fireball", "crisp", "Crisp", "cozy", "Cozy Campfire", "meteor", "Meteor Shower",
                "autosmelt", "Autosmelt", "flamestrike", "Flamestrike", "fireshot", "Fireshot");
        def(GemType.SPEED, "blur", "Blur", "storm", "Speedy Storm", "thunderstep", "Thunderstep");
        def(GemType.WEALTH, "itemlock", "Item Lock", "richrush", "Rich Rush", "pockets", "Pockets", "amp", "Amplification",
                "unfortune", "Unfortune", "doubledebris", "Double Debris");
        def(GemType.FLUX, "beam", "Energy Beam", "ground", "Ground", "overdrive", "Kinetic Overdrive", "burst", "Kinetic Burst",
                "flowstate", "Flow State", "conduction", "Conduction");
        def(GemType.ASTRA, "daggers", "Daggers", "unbounded", "Unbounded", "projection", "Astral Projection", "void", "Astral Void",
                "drift", "Dimensional Drift", "phasing", "Phasing", "soulhealing", "Soul Healing", "soulcapture", "Soul Capture");
        def(GemType.LIFE, "heartdrainer", "Heart Drainer", "heartlock", "Heartlock", "circle", "Circle of Life", "vortex", "Vitality Vortex",
                "greenthumb", "Green Thumb", "radiantfist", "Radiant Fist");
        def(GemType.PUFF, "bash", "Breezy Bash", "dash", "Dash", "doublejump", "Double Jump", "sculksilence", "Sculk Silence");
        def(GemType.HERETIC, "bloodsaws", "Bloodsaws", "bloodlinking", "Bloodlinking", "spore", "Spore", "hemorrhage", "Hemorrhage",
                "enduring", "Enduring Strength", "autocrit", "Auto-Crit", "hardening", "Blood Hardening");
        def(GemType.AURATUS, "chain", "Venerated Perforators", "aegis", "Echoing Aegis", "slam", "Gilded Slam", "flagpole", "Flagpole",
                "featheredfall", "Feathered Fall", "haulingstrike", "Hauling Strike", "angelsgrasp", "Angels Grasp", "divinepurity", "Divine Purity");
        def(GemType.RELAY, "rewind", "Rewind", "dilate", "Time Dilate", "divinity", "Temporal Divinity", "rift", "Rift",
                "tunedin", "Tuned In", "quickened", "Quickened", "cleansignal", "Clean Signal");
    }

    static final Map<Integer, String> DEFAULT_TIERS = Map.of(1, "Tier I", 2, "Tier II");

    private final Map<GemType, String> gems = new EnumMap<>(GemType.class), colors = new EnumMap<>(GemType.class);
    private final Map<GemType, Map<String, String>> abilities = new EnumMap<>(GemType.class);
    private final Map<Integer, String> tiers = new LinkedHashMap<>(DEFAULT_TIERS);
    private int maxLength = 32;
    private List<Swap> swaps = List.of();

    private record Swap(Pattern find, String with, boolean upper) {}

    GemNames() {
        for (GemType t : GemType.values()) {
            gems.put(t, t.display);
            colors.put(t, t.color);
            abilities.put(t, new LinkedHashMap<>(DEFAULTS.getOrDefault(t, new LinkedHashMap<>())));
        }
        rebuild();
    }

    // ------------------------------------------------------------------ reading

    String gem(GemType t) { return gems.getOrDefault(t, t.display); }

    /** The gem's colour as a legacy code (§6, or §x§R§R§G§G§B§B for a hex colour). */
    String color(GemType t) { return colors.getOrDefault(t, t.color); }

    /** The name for one ability id ("crisp"), or the id itself if the gem has no such ability. */
    String ability(GemType t, String id) {
        Map<String, String> m = abilities.get(t);
        String n = m == null ? null : m.get(id);
        return n != null ? n : id;
    }

    Map<String, String> abilities(GemType t) { return abilities.getOrDefault(t, Map.of()); }

    String tier(int tier) { return tiers.getOrDefault(tier, DEFAULT_TIERS.getOrDefault(tier, "Tier " + tier)); }

    boolean tiersChanged() { return !tiers.equals(DEFAULT_TIERS); }

    /** Whether this gem's own name or colour differs from the default (its lore heading is then rewritten). */
    boolean gemChanged(GemType t) { return !gem(t).equals(t.display) || !color(t).equals(t.color); }

    int maxLength() { return maxLength; }

    /** Changes whenever any name does: gems carry it, so their name and lore are rebuilt once after a rename. */
    String version() {
        int h = gems.hashCode() * 31 + colors.hashCode();
        h = h * 31 + abilities.hashCode();
        h = h * 31 + tiers.hashCode();
        return Integer.toHexString(h);
    }

    /**
     * The same text with every renamed name swapped for its new one: ability names as written and in CAPITALS (the
     * lore headings), the old spellings the plugin used too, and the mythics' names. A colour in the new name is
     * followed by the colour that was in force before it, so the rest of the line keeps its own. Unchanged names
     * aren't touched, so with an untouched names.yml this returns the text exactly as given.
     */
    String t(String text) {
        if (text == null || swaps.isEmpty()) return text;
        String out = text;
        for (Swap s : swaps) {
            Matcher m = s.find.matcher(out);
            if (!m.find()) continue;
            StringBuilder b = new StringBuilder();
            int last = 0;
            do {
                b.append(out, last, m.start());
                String name = s.upper ? upperKeepingCodes(s.with) : s.with;
                b.append(name);
                if (name.indexOf('§') >= 0) b.append(styleBefore(out, m.start()));
                last = m.end();
            } while (m.find());
            b.append(out.substring(last));
            out = b.toString();
        }
        return out;
    }

    /** Every line of a list through {@link #t}. */
    List<String> t(List<String> lines) {
        if (swaps.isEmpty()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(t(l));
        return out;
    }

    /** A name starts where no letter or digit comes right before it, or right after a colour code (§6Crisp). */
    private static final String START = "(?:(?<![\\p{L}\\p{N}])|(?<=§[0-9a-zA-Z]))";

    private void rebuild() {
        List<String[]> pairs = new ArrayList<>(); // {old, new}
        for (GemType t : GemType.values()) {
            Map<String, String> d = DEFAULTS.getOrDefault(t, new LinkedHashMap<>());
            for (Map.Entry<String, String> e : d.entrySet()) {
                String now = ability(t, e.getKey());
                if (!now.equals(e.getValue())) pairs.add(new String[] {e.getValue(), now});
            }
            if (t.isMythic() && !gem(t).equals(t.display)) pairs.add(new String[] {t.display, gem(t)}); // Heretic, Auratus, Relay are unique words
            else if (!gem(t).equals(t.display)) {
                pairs.add(new String[] {t.display + " Gem", gem(t) + " Gem"});
                pairs.add(new String[] {t.display + " gem", gem(t) + " gem"});
            }
        }
        for (Map.Entry<String, String> a : ALIASES.entrySet()) {
            String[] ref = a.getValue().split("\\.");
            GemType t = GemType.valueOf(ref[0]);
            String now = ability(t, ref[1]);
            if (!now.equals(DEFAULTS.get(t).get(ref[1]))) pairs.add(new String[] {a.getKey(), now});
        }
        pairs.sort(Comparator.comparingInt((String[] p) -> -p[0].length())); // "Kinetic Overdrive" before "Overdrive"-like parts
        List<Swap> out = new ArrayList<>();
        for (String[] p : pairs) {
            out.add(new Swap(Pattern.compile(START + Pattern.quote(p[0]) + "(?![\\p{L}\\p{N}])"), p[1], false));
            String up = p[0].toUpperCase(Locale.ROOT);
            if (!up.equals(p[0])) out.add(new Swap(Pattern.compile(START + Pattern.quote(up) + "(?![\\p{L}\\p{N}])"), p[1], true));
        }
        swaps = out;
    }

    /** CAPITALS of a name, leaving its colour codes alone (§x§a§b... stays as it is). */
    private static String upperKeepingCodes(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { b.append(c).append(s.charAt(++i)); continue; }
            b.append(Character.toUpperCase(c));
        }
        return b.toString();
    }

    /** The colour (and formats) in force at {@code at}: re-applied after a coloured name. Nothing before it: a reset. */
    static String styleBefore(String s, int at) {
        String style = "";
        for (int i = 0; i + 1 < Math.min(at, s.length()); i++) {
            if (s.charAt(i) != '§') continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if (c == 'x' && i + 14 <= at) { style = s.substring(i, i + 14); i += 13; }
            else if ("0123456789abcdef".indexOf(c) >= 0) { style = "§" + c; i++; }
            else if (c == 'r') { style = "§r"; i++; }
            else if ("klmno".indexOf(c) >= 0) { style += "§" + c; i++; }
        }
        return style.isEmpty() ? "§r" : style;
    }

    // ------------------------------------------------------------------ cleaning names

    /** Colours and bold, italic and so on only: no clicks, hovers, inserts, fonts or newlines. */
    private static final MiniMessage SAFE = MiniMessage.builder().tags(TagResolver.resolver(StandardTags.color(),
            StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset())).build();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('§').hexColors()
            .useUnusualXRepeatedCharacterHexFormat().build();

    /**
     * A name as typed (MiniMessage, & or § colour codes, or plain), made safe: newlines go, only colours and text
     * styles are kept, and the result is a legacy § string. Null if it's empty or longer than {@code max} letters
     * (colour codes don't count).
     */
    static String clean(String raw, int max) {
        if (raw == null) return null;
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.isEmpty()) return null;
        Component c = s.indexOf('<') >= 0 ? SAFE.deserialize(onlyStyleTags(s))
                : LegacyComponentSerializer.legacyAmpersand().deserialize(s.replace('§', '&'));
        String plain = PlainTextComponentSerializer.plainText().serialize(c).trim();
        if (plain.isEmpty() || plain.length() > max) return null;
        return LEGACY.serialize(c);
    }

    /** The colour and style names MiniMessage may use in a name; any other tag (click, hover, insert, newline ...) is taken out. */
    private static final java.util.Set<String> STYLE_TAGS = java.util.Set.of("color", "colour", "c", "bold", "b", "italic", "i", "em",
            "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "gradient", "rainbow", "reset", "black", "dark_blue",
            "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green",
            "aqua", "red", "light_purple", "yellow", "white");
    private static final Pattern TAG = Pattern.compile("</?([!#]?[a-zA-Z0-9_#-]+)[^>]*>");

    static String onlyStyleTags(String s) {
        Matcher m = TAG.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            boolean keep = STYLE_TAGS.contains(name) || name.matches("#[0-9a-f]{6}");
            m.appendReplacement(b, keep ? Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(b);
        return b.toString();
    }

    static String plain(String legacy) {
        return legacy == null ? "" : PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(legacy));
    }

    /** A colour as typed: #RRGGBB, a chat colour name (gold), or a legacy code (§6 / &6). Null if it isn't one. */
    static String colour(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("[§&][0-9a-fA-F]")) return "§" + Character.toLowerCase(s.charAt(1));
        if (s.matches("(§x(§[0-9a-fA-F]){6})")) return s; // kept exactly as written (the Relay's default is in capitals)
        Integer rgb = CaptureMath.color(s);
        if (rgb == null) return null;
        String hex = String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
        StringBuilder b = new StringBuilder("§x");
        for (char ch : hex.toCharArray()) b.append('§').append(ch);
        return b.toString();
    }

    // ------------------------------------------------------------------ changing them

    /** False if the name isn't allowed (empty, too long); otherwise saved in memory (save() writes the file). */
    boolean renameGem(GemType t, String raw) {
        String n = clean(raw, maxLength);
        if (n == null) return false;
        gems.put(t, n);
        rebuild();
        return true;
    }

    boolean renameAbility(GemType t, String id, String raw) {
        if (!abilities.containsKey(t) || !abilities.get(t).containsKey(id)) return false;
        String n = clean(raw, maxLength);
        if (n == null) return false;
        abilities.get(t).put(id, n);
        rebuild();
        return true;
    }

    boolean recolor(GemType t, String raw) {
        String c = colour(raw);
        if (c == null) return false;
        colors.put(t, c);
        rebuild();
        return true;
    }

    boolean renameTier(int tier, String raw) {
        String n = clean(raw, maxLength);
        if (n == null) return false;
        tiers.put(tier, n);
        rebuild();
        return true;
    }

    /** One gem (its name, colour and abilities) back to the defaults, or everything ({@code t} null, tiers too). */
    void reset(GemType t) {
        for (GemType g : GemType.values()) {
            if (t != null && g != t) continue;
            gems.put(g, g.display);
            colors.put(g, g.color);
            abilities.put(g, new LinkedHashMap<>(DEFAULTS.getOrDefault(g, new LinkedHashMap<>())));
        }
        if (t == null) { tiers.clear(); tiers.putAll(DEFAULT_TIERS); }
        rebuild();
    }

    // ------------------------------------------------------------------ names.yml

    /** Reads names.yml, filling in anything missing with the defaults (and writing them back, so it's all there to edit). */
    static GemNames load(File file) {
        GemNames n = new GemNames();
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        n.maxLength = Math.max(4, y.getInt("max-length", 32));
        ConfigurationSection tiers = y.getConfigurationSection("tiers");
        if (tiers != null) for (String k : tiers.getKeys(false)) {
            try {
                String c = clean(tiers.getString(k), n.maxLength);
                if (c != null) n.tiers.put(Integer.parseInt(k), c);
            } catch (NumberFormatException ignored) { }
        }
        for (GemType t : GemType.values()) {
            String base = "gems." + t.name() + ".";
            String name = clean(y.getString(base + "name"), n.maxLength);
            if (name != null) n.gems.put(t, name);
            String col = colour(y.getString(base + "color"));
            if (col != null) n.colors.put(t, col);
            for (String id : new ArrayList<>(n.abilities.get(t).keySet())) {
                String a = clean(y.getString(base + "abilities." + id), n.maxLength);
                if (a != null) n.abilities.get(t).put(id, a);
            }
        }
        n.rebuild();
        n.save(file);
        return n;
    }

    void save(File file) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Every gem, ability and tier name players see. Edit it, then /gems names refresh (or use /gems rename,",
                "/gems color and /gems names reset). Names may use MiniMessage (<gold>) or & colour codes; clicks, hovers and",
                "newlines are stripped, and a name can be at most max-length letters long. The ids (FIRE, crisp ...) never change."));
        y.set("max-length", maxLength);
        for (Map.Entry<Integer, String> e : tiers.entrySet()) y.set("tiers." + e.getKey(), e.getValue());
        for (GemType t : GemType.values()) {
            String base = "gems." + t.name() + ".";
            y.set(base + "name", gem(t));
            y.set(base + "color", color(t));
            for (Map.Entry<String, String> e : abilities.get(t).entrySet()) y.set(base + "abilities." + e.getKey(), e.getValue());
        }
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            org.bukkit.Bukkit.getLogger().warning("[SoulboundGems] Couldn't save names.yml: " + ex.getMessage());
        }
    }
}
