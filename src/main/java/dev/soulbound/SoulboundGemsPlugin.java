package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Main class: original Bliss-inspired implementation. */
public class SoulboundGemsPlugin extends JavaPlugin {
    private GemManager gemManager;
    private GemListener listener;
    private MythicListener mythics;
    private RelayGem relay;
    private ProgressionManager progression;
    private MenuGUI menu;
    private Pedestal pedestal;
    private ConfigGUI configGui;
    private BuildManager builds;
    private ShadowStalkerListener stalker;
    private PrismaticEdge edge;
    private Turmoil turmoil;
    private Finale finale;
    private RevenantMace revenant;
    private CaptureManager captures;
    private CameraOverlay overlay;
    private AuratusGem auratus;

    public GemManager gems() { return gemManager; }
    public GemListener listener() { return listener; }
    public MythicListener mythics() { return mythics; }
    public RelayGem relay() { return relay; }
    public ProgressionManager progression() { return progression; }
    public MenuGUI menu() { return menu; }
    public Pedestal pedestal() { return pedestal; }
    public ConfigGUI configGui() { return configGui; }
    public BuildManager builds() { return builds; }
    public ShadowStalkerListener stalker() { return stalker; }
    public PrismaticEdge prismaticEdge() { return edge; }
    public Turmoil turmoil() { return turmoil; }
    public Finale finale() { return finale; }
    public RevenantMace revenant() { return revenant; }
    public AuratusGem auratus() { return auratus; }
    public CaptureManager captures() { return captures; }
    CameraOverlay overlay() { return overlay; }
    private LooseMythics loose;
    private Prophet prophet;
    private AstralSpectate astral;
    AstralSpectate astral() { return astral; }
    private LandWorks land;
    private StarterGem starter;
    StarterGem starter() { return starter; }
    LandWorks land() { return land; }
    private CrispLand crisp;
    CrispLand crisp() { return crisp; }
    private VortexDrain vortex;
    VortexDrain vortex() { return vortex; }
    private LifeBlessing blessing;
    LifeBlessing blessing() { return blessing; }
    private CombatRules combatRules;
    private ItemProvenanceListener provenance;
    ItemProvenanceListener provenance() { return provenance; }
    private BountyHunt bounty;
    BountyHunt bounty() { return bounty; }
    private Amplification amplification;
    Amplification amplification() { return amplification; }
    private FluxPower flux;
    FluxPower flux() { return flux; }
    private GoldGem gold;
    GoldGem gold() { return gold; }
    private MaceRules maceRules;
    private PantheonLink pantheon;
    PantheonLink pantheon() { return pantheon; }
    private EventBlock eventBlock;
    EventBlock eventBlock() { return eventBlock; }
    private HaloHunt halo;
    HaloHunt halo() { return halo; }
    private HereticGames games;
    HereticGames games() { return games; }

    /** In a Pantheon event where this power is blocked: says so (at most every 2 s) and returns true. */
    boolean eventRefuses(Player p, String power) { return eventBlock != null && eventBlock.refuse(p, power); }

    /** The same without a message (passives, and players a power would move). */
    boolean eventBlocks(Player p, String power) { return eventBlock != null && eventBlock.blockedFor(p, power); }

    /**
     * In any running Pantheon event, or playing in the Heretic's Games: the same rules either way (no Soulbound death
     * effects, never pulled in or out by a power).
     */
    boolean inPantheonEvent(Player p) { return pantheon != null && pantheon.inEvent(p) || games != null && games.playing(p); }
    private final ActionBarHold actionBarHold = new ActionBarHold();

    // ------------------------------------------------------------------ public API (Pantheon reads it by reflection)

    /** The gem, mythic, Revenant and Gold Gem HUDs leave this player's action bar alone for {@code ms} (a one-off message). */
    public void holdActionBar(Player p, long ms) {
        if (p != null) actionBarHold.hold(p.getUniqueId(), ms, System.currentTimeMillis());
    }

    /**
     * In a Soulbound event: the Finale battle (inside its area), the Revenant boss fight, or the Heretic's Games.
     * Capture points don't count.
     */
    public boolean isInSoulboundEvent(Player p) {
        if (p == null) return false;
        return finale != null && finale.inBattle(p) || revenant != null && revenant.inBossFight(p) || games != null && games.playing(p);
    }

    /** Our HUDs skip a draw: a one-off message holds the bar, or the player's Pantheon event draws its own HUD. */
    boolean hudHeld(Player p) {
        if (actionBarHold.held(p.getUniqueId(), System.currentTimeMillis())) return true;
        return pantheon != null && pantheon.eventHud(p);
    }

    void forgetHold(java.util.UUID id) { actionBarHold.forget(id); }

    /** How a message names this player: a scramble while they're invisible (rules.invisibility), else their name. */
    String shownName(Player p) { return combatRules == null ? p.getName() : combatRules.shownName(p); }

    /** The same, as a component (obfuscated while invisible). */
    net.kyori.adventure.text.Component shownNameComponent(Player p) {
        return combatRules == null ? net.kyori.adventure.text.Component.text(p.getName()) : combatRules.shown(p);
    }

    /** Invisible with the invisibility rule on: messages scramble their name. */
    boolean nameHidden(Player p) { return combatRules != null && combatRules.invisible(p); }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        for (String bad : GemSounds.unknown(this)) getLogger().warning("config.yml: unknown sound " + bad + " (it will be silent)");
        migrateBalance();
        saveConfig();
        GemNames.use(GemNames.load(new java.io.File(getDataFolder(), "names.yml"))); // before any gem item is made
        pantheon = new PantheonLink(this); // soft link: events, arenas and the season leader (nothing if Pantheon isn't there)
        eventBlock = new EventBlock(this); // powers blocked in Pantheon events (event-blocked-powers)
        gemManager = new GemManager(this);
        overlay = new CameraOverlay(this);
        mythics = new MythicListener(this);
        auratus = new AuratusGem(this);
        listener = new GemListener(this);
        relay = new RelayGem(this);
        progression = new ProgressionManager(this);
        menu = new MenuGUI(this);
        pedestal = new Pedestal(this);
        configGui = new ConfigGUI(this);
        builds = new BuildManager(this);
        edge = new PrismaticEdge(this);
        stalker = new ShadowStalkerListener(this);
        turmoil = new Turmoil(this);
        finale = new Finale(this);
        finale.recoverAfterCrash();
        revenant = new RevenantMace(this);
        captures = new CaptureManager(this);

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(mythics, this);
        Bukkit.getPluginManager().registerEvents(auratus, this);
        Bukkit.getPluginManager().registerEvents(relay, this);
        Bukkit.getPluginManager().registerEvents(progression, this);
        Bukkit.getPluginManager().registerEvents(menu, this);
        Bukkit.getPluginManager().registerEvents(pedestal, this);
        pedestal.start(); // an old-style Pedestal is rebuilt once; otherwise its saved state (open with its beam, or closed)
        Bukkit.getPluginManager().registerEvents(configGui, this);
        provenance = new ItemProvenanceListener(this);
        Bukkit.getPluginManager().registerEvents(provenance, this);
        bounty = new BountyHunt(this); // Strength's Bounty Hunter: the board, offerings and hunts
        Bukkit.getPluginManager().registerEvents(bounty, this);
        bounty.start();
        amplification = new Amplification(this); // Wealth's Amplification: the buff and getting every item back to normal
        Bukkit.getPluginManager().registerEvents(amplification, this);
        amplification.start();
        starter = new StarterGem(this); // a starter gem on a player's first join (starter-gem: in config.yml)
        Bukkit.getPluginManager().registerEvents(starter, this);
        starter.start();
        gold = new GoldGem(this); // the Gold Gem: sockets, marks, repurposing, the ritual, Snap and Golden Drill
        Bukkit.getPluginManager().registerEvents(gold, this);
        GoldCommand goldCommand = new GoldCommand(this);
        if (getCommand("goldgem") != null) {
            getCommand("goldgem").setExecutor(goldCommand);
            getCommand("goldgem").setTabCompleter(goldCommand);
        }
        if (getConfig().getBoolean("events.halo.enabled", true)) { // the Lost Halo: how the Auratus enters a season
            halo = new HaloHunt(this);
            Bukkit.getPluginManager().registerEvents(halo, this);
            halo.start(); // a restart: the hunt goes on, the reliquaries come back
        }
        HaloCommand haloCommand = new HaloCommand(this);
        if (getCommand("halo") != null) {
            getCommand("halo").setExecutor(haloCommand);
            getCommand("halo").setTabCompleter(haloCommand);
        }
        flux = new FluxPower(this); // Flux: the charged beam, Ground, Overcharge, Overdrive's meter, Shocking Chance
        Bukkit.getPluginManager().registerEvents(flux, this);
        flux.start();
        Bukkit.getPluginManager().registerEvents(edge, this);
        Bukkit.getPluginManager().registerEvents(stalker, this);
        Bukkit.getPluginManager().registerEvents(turmoil, this);
        Bukkit.getPluginManager().registerEvents(finale, this);
        Bukkit.getPluginManager().registerEvents(revenant, this);
        Bukkit.getPluginManager().registerEvents(captures, this);
        Bukkit.getPluginManager().registerEvents(overlay, this);
        loose = new LooseMythics(this);
        Bukkit.getPluginManager().registerEvents(loose, this);
        loose.start();
        prophet = new Prophet(this);
        Bukkit.getPluginManager().registerEvents(prophet, this);
        astral = new AstralSpectate(this);
        Bukkit.getPluginManager().registerEvents(astral, this);
        astral.start();
        land = new LandWorks(this); // shared by Crisp and Vitality Vortex
        Bukkit.getPluginManager().registerEvents(land, this);
        land.start(); // land a restart left changed goes back now
        if (getConfig().getBoolean("events.heretic-games.enabled", true)) { // the Heretic's Games: how the Heretic enters a season
            games = new HereticGames(this); // after the land: a crash's rising lava is already back
            Bukkit.getPluginManager().registerEvents(games, this);
            Bukkit.getPluginManager().registerEvents(games.kits, this);
            games.start(); // a tournament a restart cut off is cancelled cleanly
        }
        GamesCommand gamesCommand = new GamesCommand(this);
        if (getCommand("hgames") != null) {
            getCommand("hgames").setExecutor(gamesCommand);
            getCommand("hgames").setTabCompleter(gamesCommand);
        }
        crisp = new CrispLand(this, land);
        vortex = new VortexDrain(this, land);
        blessing = new LifeBlessing(this);
        Bukkit.getPluginManager().registerEvents(blessing, this);
        blessing.start(); // a reload: nobody keeps blessed hearts from before
        RuleCore rules = new RuleCore(this); // the combat rules module (rules: in config.yml)
        combatRules = new CombatRules(this, rules);
        Bukkit.getPluginManager().registerEvents(combatRules, this);
        combatRules.start();
        Bukkit.getPluginManager().registerEvents(new ItemRules(this, rules), this);
        maceRules = new MaceRules(this, rules, combatRules);
        Bukkit.getPluginManager().registerEvents(maceRules, this);
        RulesCommand rulesCommand = new RulesCommand(this, rules, maceRules);
        if (getCommand("rules") != null) {
            getCommand("rules").setExecutor(rulesCommand);
            getCommand("rules").setTabCompleter(rulesCommand);
        }

        NamesCommand namesCommand = new NamesCommand(this);
        Bukkit.getPluginManager().registerEvents(new GemNameListener(this, namesCommand), this);
        for (String c : new String[] {"gems", "gem"}) {
            if (getCommand(c) == null) continue;
            getCommand(c).setExecutor(namesCommand);
            getCommand(c).setTabCompleter(namesCommand);
        }

        BlissCommand cmd = new BlissCommand(this);
        if (getCommand("bliss") != null) {
            getCommand("bliss").setExecutor(cmd);
            getCommand("bliss").setTabCompleter(cmd);
        }
        if (getCommand("progression") != null) {
            getCommand("progression").setExecutor(cmd);
            getCommand("progression").setTabCompleter(cmd);
        }
        if (getCommand("givegem") != null) {
            getCommand("givegem").setExecutor(cmd);
            getCommand("givegem").setTabCompleter(cmd);
        }

        BlissOpCommand opCmd = new BlissOpCommand(this, edge);
        if (getCommand("blissop") != null) {
            getCommand("blissop").setExecutor(opCmd);
            getCommand("blissop").setTabCompleter(opCmd);
        }
        if (getCommand("mythicconfig") != null) { // the live tuner (also reachable from /bliss config)
            getCommand("mythicconfig").setExecutor((s, c, l, a) -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                String which = a.length > 0 ? a[0].toLowerCase(java.util.Locale.ROOT) : "heretic";
                mythics.openMythicConfig(p, which.startsWith("a") ? "mythics.auratus." : "mythics.heretic.");
                return true;
            });
            getCommand("mythicconfig").setTabCompleter((s, c, l, a) -> a.length == 1
                    ? java.util.stream.Stream.of("heretic", "auratus").filter(o -> o.startsWith(a[0].toLowerCase(java.util.Locale.ROOT))).toList()
                    : java.util.List.of());
        }
        TurmoilCommand turmoilCommand = new TurmoilCommand(this);
        if (getCommand("turmoil") != null) {
            getCommand("turmoil").setExecutor(turmoilCommand);
            getCommand("turmoil").setTabCompleter(turmoilCommand);
        }
        if (getCommand("revenant") != null) {
            getCommand("revenant").setExecutor(revenant);
            getCommand("revenant").setTabCompleter(revenant);
        }
        if (getCommand("stalker") != null) {
            getCommand("stalker").setExecutor(stalker);
        }
        CaptureCommand captureCommand = new CaptureCommand(this);
        if (getCommand("capture") != null) {
            getCommand("capture").setExecutor(captureCommand);
            getCommand("capture").setTabCompleter(captureCommand);
        }
        captures.enable(); // loads capture-points.yml; a capture left running by a restart is stopped and reported

        int tickSec = getConfig().getInt("passives.tick-seconds", 2);
        int actionTicks = getConfig().getInt("actionbar.update-ticks", 10);
        int period = Math.min(tickSec * 20, actionTicks);
        if (period <= 0) period = 20;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            listener.tick();
            mythics.tick();
            progression.tick();
            pedestal.tick();
        }, 20L, period);

        Bukkit.getScheduler().runTaskTimer(this, () -> relay.tick(), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> listener.tickAstraDaggers(), 1L, 1L); // the waiting daggers follow you every 2 ticks
        Bukkit.getScheduler().runTaskTimer(this, () -> revenant.tick(), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> auratus.tick(), 1L, 1L);

        registerRecipes();
        getLogger().info("Soulbound Gems enabled.");
    }

    @Override
    public void onDisable() {
        if (astral != null) astral.shutdown(); // first: anyone projected or Unbounded is back in their body and game mode
        if (games != null) games.shutdown(); // every player's things, game mode and gem back, the rising lava gone
        if (halo != null) halo.shutdown(); // a claim in progress is given; the reliquary displays go (rebuilt on enable)
        if (land != null) land.shutdown(); // every Crisp and Vitality Vortex change goes back at once
        if (blessing != null) blessing.shutdown(); // no blessed hearts outlive the plugin
        if (captures != null) captures.shutdown(); // removes every capture display and boss bar
        if (pedestal != null) pedestal.shutdown(); // a wave in progress finishes in its target state
        if (listener != null) listener.shutdown(); // removes the Astra dagger models
        if (revenant != null) revenant.shutdown(); // pauses a running fight and gives the holder their own movement back
        if (relay != null) relay.shutdown();
        if (mythics != null) mythics.shutdown(); // Bloodsaws in flight
        if (auratus != null) auratus.shutdown(); // removes chains, halos and slam rings, and lets go of anyone on a Flagpole
        if (finale != null) finale.shutdown();
        if (turmoil != null && turmoil.isActive()) turmoil.stop();
        if (stalker != null) stalker.shutdown();
        if (overlay != null) overlay.clearAll(); // everyone's real helmet back
        if (loose != null) loose.shutdown();
        if (prophet != null) prophet.save();
        if (combatRules != null) combatRules.shutdown(); // everyone hidden from the tab list is back on it
        if (bounty != null) bounty.shutdown(); // open offerings go back to their players, every hunt ends
        if (amplification != null) amplification.shutdown(); // no item stays enchanted higher
        if (flux != null) flux.shutdown(); // charging stops, every stun comes off
        if (gold != null) gold.shutdown(); // every repurpose finishes, every ritual stops, gold.yml saved
    }

    /**
     * Balance 2: ability hits pierce armour, and the Heretic's weakest numbers go up. A server still on the old
     * defaults gets the new ones once; numbers someone changed are left alone.
     */
    private void migrateBalance() {
        var c = getConfig();
        int version = c.getInt("balance-version", 1);
        if (version >= 10) return;
        java.util.List<String> changed = new java.util.ArrayList<>();
        String link = "mythics.heretic.bloodlinking.";
        if (version < 2) {
            if (c.getDouble("mythics.heretic.bloodsaws.damage", 5.0) == 5.0) { c.set("mythics.heretic.bloodsaws.damage", 7.0); changed.add("Bloodsaw damage 5 -> 7"); }
            if (c.getDouble(link + "damage", 6.0) == 6.0) c.set(link + "damage", 9.0); // and on to 11 below
        }
        if (version < 3) { // Balance 3: a stronger Bloodlinking (instant bounces on contact).
            if (c.getDouble(link + "launch-y", 1.35) == 1.35) { c.set(link + "launch-y", 1.9); changed.add("Bloodlinking launch 1.35 -> 1.9"); }
            if (c.getDouble(link + "damage", 9.0) == 9.0) { c.set(link + "damage", 11.0); changed.add("Bloodlinking damage -> 11"); }
            if (c.getInt(link + "max-victims", 2) == 2) { c.set(link + "max-victims", 4); changed.add("Bloodlinking max victims 2 -> 4"); }
        }
        if (version < 4) { // Balance 4: the Bloodlinking dive is one strong push the way you look (no steering).
            if (c.getDouble(link + "dive-speed", 1.3) == 1.3) { c.set(link + "dive-speed", 2.0); changed.add("Bloodlinking dive push 1.3 -> 2.0"); }
            c.set(link + "dive-follows-look", null); // steering was taken out again
        }
        // Balance 5: the fireball cools down in 120 s (was 180).
        if (version < 5 && c.getInt("cooldowns.fireball", 180) == 180) { c.set("cooldowns.fireball", 120); changed.add("Fireball cooldown 180 -> 120 s"); }
        // Balance 6: Auratus damage is raw HP set against full diamond, and Protection no longer counts. Only a config
        // with the old Auratus section (it had flagpole.finisher-damage) is converted; old defaults get the new numbers,
        // numbers someone changed keep the same raw damage (x the ability multiplier they were scaled by).
        String aur = "mythics.auratus.";
        if (c.contains(aur + "flagpole.finisher-damage", true)) {
            double mult = c.getDouble("ability-damage.multiplier", 3.0);
            String[][] keys = {{"chain.hit-damage", "3.0", "14.495"}, {"aegis.strike-damage", "5.0", "20.0"},
                    {"slam.damage-center", "6.0", "18.0"}, {"slam.damage-edge", "3.0", "9.0"}};
            for (String[] k : keys) {
                if (!c.contains(aur + k[0], true)) continue;
                double old = c.getDouble(aur + k[0]), now = old == Double.parseDouble(k[1]) ? Double.parseDouble(k[2]) : old * mult;
                c.set(aur + k[0], now);
                changed.add("Auratus " + k[0] + " " + old + " -> " + now + " (raw HP)");
            }
            c.set(aur + "flagpole.finisher-damage", null); // the launch is worked out from the three now
        }
        // Balance 7: the Energy Beam is charged now (Q), so its cooldown is short.
        if (version < 7 && c.getInt("cooldowns.beam", 120) == 120) { c.set("cooldowns.beam", 8); changed.add("Energy Beam cooldown 120 -> 8 s (charging is the cost now)"); }
        // Balance 8: Astral Projection cools down in 60 s (its tags lock gems); a /config override still at the old 20 moves too.
        if (version < 8 && c.contains("cooldowns.projection", true) && c.getInt("cooldowns.projection") == 20) { c.set("cooldowns.projection", 60); changed.add("Projection cooldown 20 -> 60 s"); }
        // 9: the Gold Gem repurpose has no chains or lightning any more: their settings go; the absorb is a deep chime
        // and the rise gets its soft chime (only where they were still the old defaults).
        if (version < 9) {
            for (String k : new String[] {"gold.repurpose.lightning", "gold.repurpose.chains", "sounds.gold.chains", "sounds.gold.chains-snap"}) {
                if (c.contains(k, true)) { c.set(k, null); changed.add("removed " + k); }
            }
            if ("ENTITY_ZOMBIE_VILLAGER_CURE".equals(c.getString("sounds.gold.absorb.event"))) copyDefault(c, "sounds.gold.absorb");
            if (!c.contains("sounds.gold.repurpose.layer", true)) copyDefault(c, "sounds.gold.repurpose");
        }
        // 10: the repurpose overlay is animated (gold.repurpose.overlay.style ...): the per-gem texture keys go.
        if (version < 10) {
            for (GemType t : GemType.values()) {
                String k = "gold.repurpose.overlay." + t.name().toLowerCase(java.util.Locale.ROOT);
                if (!t.isMythic() && c.contains(k, true)) { c.set(k, null); changed.add("removed " + k); }
            }
        }
        c.set("balance-version", 10);
        if (!changed.isEmpty()) getLogger().info("Balance update: " + String.join(", ", changed) + " (see CHANGELOG.md).");
    }

    /** Puts the shipped default of one config section back (every value under it). */
    private static void copyDefault(org.bukkit.configuration.file.FileConfiguration c, String path) {
        var d = c.getDefaults() == null ? null : c.getDefaults().getConfigurationSection(path);
        if (d == null) return;
        c.set(path, null);
        for (String k : d.getKeys(true)) if (!d.isConfigurationSection(k)) c.set(path + "." + k, d.get(k));
    }

    private void registerRecipes() {
        // Progression-gated recipes (upgrader/trader/repairkit) are owned by the
        // ProgressionManager and registered once the configured progression allows them.
        progression.applyProgressionState();
    }
}
