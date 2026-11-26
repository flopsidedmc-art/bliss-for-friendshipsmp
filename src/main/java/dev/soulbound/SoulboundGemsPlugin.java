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

    /** Pristine-stage energy per player per gem (showcase parity). Public by design. */
    public EnergyManager energy;

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
    private MaceRules maceRules;

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
        energy = new EnergyManager(this);
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
        Bukkit.getPluginManager().registerEvents(configGui, this);
        provenance = new ItemProvenanceListener(this);
        Bukkit.getPluginManager().registerEvents(provenance, this);
        bounty = new BountyHunt(this); // Strength's Bounty Hunter: the board, offerings and hunts
        Bukkit.getPluginManager().registerEvents(bounty, this);
        bounty.start();
        amplification = new Amplification(this); // Wealth's Amplification: the buff and getting every item back to normal
        Bukkit.getPluginManager().registerEvents(amplification, this);
        amplification.start();
        Bukkit.getPluginManager().registerEvents(energy, this);
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
            energy.passiveTick();
        }, 20L, period);

        Bukkit.getScheduler().runTaskTimer(this, () -> relay.tick(), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> revenant.tick(), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> auratus.tick(), 1L, 1L);

        registerRecipes();
        getLogger().info("Soulbound Gems enabled.");
    }

    @Override
    public void onDisable() {
        if (astral != null) astral.shutdown(); // first: anyone projected or Unbounded is back in their body and game mode
        if (land != null) land.shutdown(); // every Crisp and Vitality Vortex change goes back at once
        if (blessing != null) blessing.shutdown(); // no blessed hearts outlive the plugin
        if (captures != null) captures.shutdown(); // removes every capture display and boss bar
        if (listener != null) listener.shutdown(); // removes the Astra dagger models
        if (revenant != null) revenant.shutdown(); // pauses a running fight and gives the holder their own movement back
        if (relay != null) relay.shutdown();
        if (auratus != null) auratus.shutdown(); // removes chains, halos and slam rings, and lets go of anyone on a Flagpole
        if (finale != null) finale.shutdown();
        if (turmoil != null && turmoil.isActive()) turmoil.stop();
        if (stalker != null) stalker.shutdown();
        if (energy != null) energy.save();
        if (overlay != null) overlay.clearAll(); // everyone's real helmet back
        if (loose != null) loose.shutdown();
        if (prophet != null) prophet.save();
        if (combatRules != null) combatRules.shutdown(); // everyone hidden from the tab list is back on it
        if (bounty != null) bounty.shutdown(); // open offerings go back to their players, every hunt ends
        if (amplification != null) amplification.shutdown(); // no item stays enchanted higher
    }

    /**
     * Balance 2: ability hits pierce armour, and the Heretic's weakest numbers go up. A server still on the old
     * defaults gets the new ones once; numbers someone changed are left alone.
     */
    private void migrateBalance() {
        var c = getConfig();
        int version = c.getInt("balance-version", 1);
        if (version >= 5) return;
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
        if (c.getInt("cooldowns.fireball", 180) == 180) { c.set("cooldowns.fireball", 120); changed.add("Fireball cooldown 180 -> 120 s"); }
        c.set("balance-version", 5);
        if (!changed.isEmpty()) getLogger().info("Balance update: " + String.join(", ", changed) + " (see CHANGELOG.md).");
    }

    private void registerRecipes() {
        // Progression-gated recipes (upgrader/trader/repairkit) are owned by the
        // ProgressionManager and registered once the configured progression allows them.
        progression.applyProgressionState();
    }
}
