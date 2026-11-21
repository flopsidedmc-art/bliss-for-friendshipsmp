package dev.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        for (String bad : GemSounds.unknown(this)) getLogger().warning("config.yml: unknown sound " + bad + " (it will be silent)");
        migrateBalance();
        saveConfig();
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
        Bukkit.getPluginManager().registerEvents(new ItemProvenanceListener(this), this);
        Bukkit.getPluginManager().registerEvents(energy, this);
        Bukkit.getPluginManager().registerEvents(edge, this);
        Bukkit.getPluginManager().registerEvents(stalker, this);
        Bukkit.getPluginManager().registerEvents(turmoil, this);
        Bukkit.getPluginManager().registerEvents(finale, this);
        Bukkit.getPluginManager().registerEvents(revenant, this);
        Bukkit.getPluginManager().registerEvents(captures, this);
        Bukkit.getPluginManager().registerEvents(overlay, this);

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
    }

    /**
     * Balance 2: ability hits pierce armour, and the Heretic's weakest numbers go up. A server still on the old
     * defaults gets the new ones once; numbers someone changed are left alone.
     */
    private void migrateBalance() {
        var c = getConfig();
        if (c.getInt("balance-version", 1) >= 2) return;
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (c.getDouble("mythics.heretic.bloodsaws.damage", 5.0) == 5.0) { c.set("mythics.heretic.bloodsaws.damage", 7.0); changed.add("Bloodsaw damage 5 -> 7"); }
        if (c.getDouble("mythics.heretic.bloodlinking.damage", 6.0) == 6.0) { c.set("mythics.heretic.bloodlinking.damage", 9.0); changed.add("Bloodlinking damage 6 -> 9"); }
        c.set("balance-version", 2);
        if (!changed.isEmpty()) getLogger().info("Balance update: " + String.join(", ", changed) + " (abilities now pierce armour; see CHANGELOG.md).");
    }

    private void registerRecipes() {
        // Progression-gated recipes (upgrader/trader/repairkit) are owned by the
        // ProgressionManager and registered once the configured progression allows them.
        progression.applyProgressionState();
    }
}
