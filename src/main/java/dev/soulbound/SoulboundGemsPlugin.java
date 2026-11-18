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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        energy = new EnergyManager(this);
        gemManager = new GemManager(this);
        mythics = new MythicListener(this);
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

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(mythics, this);
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

        registerRecipes();
        getLogger().info("Soulbound Gems enabled.");
    }

    @Override
    public void onDisable() {
        if (revenant != null) revenant.shutdown(); // pauses a running fight and gives the holder their own movement back
        if (relay != null) relay.shutdown();
        if (finale != null) finale.shutdown();
        if (turmoil != null && turmoil.isActive()) turmoil.stop();
        if (stalker != null) stalker.shutdown();
        if (energy != null) energy.save();
    }

    private void registerRecipes() {
        // Progression-gated recipes (upgrader/trader/repairkit) are owned by the
        // ProgressionManager and registered once the configured progression allows them.
        progression.applyProgressionState();
    }
}
