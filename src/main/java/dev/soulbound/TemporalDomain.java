package dev.soulbound;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.Vector;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Journaled, all-or-rollback domain entry. Duration advances only on server ticks.
 * Movement: only the session owner gets the AirSwim controller (free 3D air-swimming); every
 * captured opponent is suspended with gravity off and is re-pinned to a slow downward drift each tick.
 */
final class TemporalDomain implements Listener {
    private final SoulboundGemsPlugin plugin;
    private final TemporalFlow flow;
    private final NamespacedKey attackKey=new NamespacedKey("soulbound-gems","temporal_divinity_attack");
    private final Map<UUID,Session> members=new HashMap<>();
    private final Map<UUID,Saved> pending=new HashMap<>();
    private final Set<UUID> internalTeleport=new HashSet<>();
    private final Map<UUID,Long> fallGrace=new HashMap<>();
    private final Map<UUID,Long> strikes=new HashMap<>();
    private final File journal;
    private World sky;
    private long tick;
    private boolean journalHealthy=true;
    private static final class Session {
        UUID owner; Set<UUID> ids=new HashSet<>(); Location center; int remaining; int slot;
        Set<org.bukkit.Chunk> chunks=new HashSet<>();
        final AirSwim swim=new AirSwim(); // caster only; opponents never get a controller
    }
    private static final class Saved {
        String world; double x,y,z; float yaw,pitch,fall,flySpeed; Vector velocity;
        boolean allowFlight,flying,gravity,collidable; int maxNoDamage,noDamage; double lastDamage;
        Saved(Player p) {
            Location l=p.getLocation();world=l.getWorld().getName();x=l.getX();y=l.getY();z=l.getZ();yaw=l.getYaw();pitch=l.getPitch();
            fall=p.getFallDistance();velocity=p.getVelocity().clone();allowFlight=p.getAllowFlight();flying=p.isFlying();flySpeed=p.getFlySpeed();
            gravity=p.hasGravity();collidable=p.isCollidable();maxNoDamage=p.getMaximumNoDamageTicks();noDamage=p.getNoDamageTicks();lastDamage=p.getLastDamage();
        }
        Saved(ConfigurationSection c) {
            world=c.getString("world");x=c.getDouble("x");y=c.getDouble("y");z=c.getDouble("z");yaw=(float)c.getDouble("yaw");pitch=(float)c.getDouble("pitch");
            fall=(float)c.getDouble("fall");velocity=new Vector(c.getDouble("vx"),c.getDouble("vy"),c.getDouble("vz"));
            allowFlight=c.getBoolean("allow-flight");flying=c.getBoolean("flying");flySpeed=(float)c.getDouble("fly-speed",0.1);
            gravity=c.getBoolean("gravity",true);collidable=c.getBoolean("collidable",true);maxNoDamage=c.getInt("max-no-damage",20);noDamage=c.getInt("no-damage");lastDamage=c.getDouble("last-damage");
        }
        void write(ConfigurationSection c) {
            c.set("world",world);c.set("x",x);c.set("y",y);c.set("z",z);c.set("yaw",yaw);c.set("pitch",pitch);c.set("fall",fall);
            c.set("vx",velocity.getX());c.set("vy",velocity.getY());c.set("vz",velocity.getZ());c.set("allow-flight",allowFlight);c.set("flying",flying);c.set("fly-speed",flySpeed);
            c.set("gravity",gravity);c.set("collidable",collidable);c.set("max-no-damage",maxNoDamage);c.set("no-damage",noDamage);c.set("last-damage",lastDamage);
        }
    }
    TemporalDomain(SoulboundGemsPlugin plugin,TemporalFlow flow) {
        this.plugin=plugin;this.flow=flow;journal=new File(plugin.getDataFolder(),"relay-domain-recovery.yml");
        if(journal.exists()) {
            try {
                YamlConfiguration c=new YamlConfiguration(); c.load(journal);
                for(String id:c.getKeys(false)) pending.put(UUID.fromString(id),new Saved(c.getConfigurationSection(id)));
            } catch(Exception ex) { journalHealthy=false; plugin.getLogger().severe("Relay domain disabled: recovery journal needs repair: "+ex.getMessage()); }
        }
    }
    private double cfg(String k,double d) { return plugin.getConfig().getDouble("mythics.relay.temporal.divinity."+k,d); }
    private double boundary() { return Math.max(8,Math.min(64,cfg("boundary-radius",48))); }
    private AirSwim.Tuning swimTuning() {
        return new AirSwim.Tuning(cfg("swim-speed",0.38),cfg("swim-sprint-multiplier",1.5),cfg("swim-acceleration",0.14),cfg("swim-drag",0.86),cfg("swim-stroke",0.10));
    }
    /** Vanilla kicks a non-flying player who hovers for 80 ticks unless the server allows flight, so the drift must stay a real descent. */
    private double drift() { return Math.max(Bukkit.getAllowFlight()?0.005:0.035,Math.min(0.5,cfg("downward-drift",0.06))); }
    boolean contains(UUID id) { return members.containsKey(id)||pending.containsKey(id); }
    private String worldName() { return plugin.getConfig().getString("mythics.relay.temporal.divinity.world","soulbound_temporal_void"); }
    private World getSky() throws IOException {
        if(sky!=null)return sky;
        String name=worldName();
        if(!name.matches("[A-Za-z0-9_-]{1,64}"))throw new IOException("Invalid void-world name");
        File marker=new File(plugin.getDataFolder(),"temporal-world-"+name+".marker");
        if(!marker.exists() && (Bukkit.getWorld(name)!=null || new File(Bukkit.getWorldContainer(),name).exists()))
            throw new IOException("Refusing to reuse unowned world "+name);
        plugin.getDataFolder().mkdirs();
        Files.writeString(marker.toPath(),"Owned by Soulbound Gems Temporal Divinity\n");
        sky=Bukkit.createWorld(new WorldCreator(name).environment(World.Environment.NORMAL).generateStructures(false).generator(new ChunkGenerator() {
            @Override public boolean shouldGenerateNoise(){return false;}
            @Override public boolean shouldGenerateSurface(){return false;}
            @Override public boolean shouldGenerateBedrock(){return false;}
            @Override public boolean shouldGenerateCaves(){return false;}
            @Override public boolean shouldGenerateDecorations(){return false;}
            @Override public boolean shouldGenerateMobs(){return false;}
            @Override public boolean shouldGenerateStructures(){return false;}
        }));
        if(sky==null)throw new IOException("World creation returned null");
        sky.setTime(6000);sky.setStorm(false);sky.setThundering(false);
        sky.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);sky.setGameRule(GameRule.DO_WEATHER_CYCLE,false);
        sky.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        return sky;
    }
    boolean start(Player owner) {
        if(!journalHealthy || contains(owner.getUniqueId()) || owner.isInsideVehicle() || owner.isDead()
            || owner.getGameMode()==GameMode.SPECTATOR || owner.getWorld().getName().equals(worldName()))return false;
        int maximum=Math.max(1,Math.min(8,(int)cfg("max-sessions",4)));
        Set<Integer> used=new HashSet<>();new HashSet<>(members.values()).forEach(s->used.add(s.slot));
        int slot=0;while(used.contains(slot))slot++;if(slot>=maximum)return false;
        double radius=Math.max(1,Math.min(32,cfg("capture-radius",10)));
        List<Player> players=new ArrayList<>();players.add(owner);
        owner.getNearbyEntities(radius,radius,radius).stream().filter(e->e instanceof Player).map(e->(Player)e)
            .filter(p->p.getLocation().distanceSquared(owner.getLocation())<=radius*radius && !p.isDead() && !contains(p.getUniqueId())
                && !p.isInsideVehicle() && !plugin.listener().isTrusted(owner,p) && !plugin.listener().isTrusted(p,owner)
                && (p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE))
            .sorted(Comparator.comparingDouble(p->p.getLocation().distanceSquared(owner.getLocation())))
            .limit(Math.max(1,Math.min(16,(int)cfg("max-opponents",8)))).forEach(players::add);
        // allow-solo is a testing aid (default off): lets one player open an empty domain to try the swimming.
        if(players.size()<2 && !plugin.getConfig().getBoolean("mythics.relay.temporal.divinity.allow-solo",false)) { owner.sendMessage("§x§B§8§9§3§5§8Temporal Divinity needs a nearby opponent.");return false; }
        Session session=new Session();session.owner=owner.getUniqueId();session.slot=slot;
        try {
            World w=getSky();
            double y=Math.max(w.getMinHeight()+64,Math.min(w.getMaxHeight()-48,cfg("altitude",160)));
            session.center=new Location(w,slot*1024.0,y,0);
            session.remaining=Math.max(1,Math.min(400,(int)(cfg("duration-seconds",8)*20)));
            for(int cx=-2;cx<=2;cx++)for(int cz=-2;cz<=2;cz++) {
                var c=w.getChunkAt((session.center.getBlockX()>>4)+cx,cz);
                if(!c.load())throw new IOException("Domain chunk failed to load");
                c.addPluginChunkTicket(plugin);session.chunks.add(c);
            }
            // Anyone air-swimming in a Dilate field gets their own flags back first, so the snapshot is their real state.
            for(Player p:players) {flow.endSwim(p.getUniqueId());session.ids.add(p.getUniqueId());pending.put(p.getUniqueId(),new Saved(p));}
            if(!saveJournal())throw new IOException("Recovery journal could not be saved");
            for(Player p:players)members.put(p.getUniqueId(),session);
            Location origin=owner.getLocation();
            for(Player p:players) {
                Vector offset=p.getLocation().toVector().subtract(origin.toVector());
                Location destination=session.center.clone().add(offset);destination.setYaw(p.getLocation().getYaw());destination.setPitch(p.getLocation().getPitch());
                if(!teleport(p,destination))throw new IOException("Entry teleport cancelled for "+p.getName());
                p.setFallDistance(0);p.setVelocity(new Vector());p.setCollidable(false);
                if(p.isGliding())p.setGliding(false);
                p.playSound(p.getLocation(),Sound.BLOCK_PORTAL_TRAVEL,0.35f,1.7f);
                p.getWorld().spawnParticle(Particle.REVERSE_PORTAL,p.getLocation().add(0,1,0),18,0.3,0.5,0.3,0.02);
                if(p.equals(owner)) {
                    // Air-swimming, not native flight: gravity off, AirSwim drives velocity every tick (see tick()).
                    // allowFlight only stops vanilla's anti-hover kick; actually toggling flight is cancelled in flight().
                    p.setFlying(false);p.setAllowFlight(true);p.setGravity(false);
                    AttributeInstance a=p.getAttribute(Attribute.ATTACK_SPEED);
                    if(a!=null) { if(a.getModifier(attackKey)!=null)a.removeModifier(attackKey);
                        a.addTransientModifier(new AttributeModifier(attackKey,Math.max(0,cfg("attack-speed-multiplier",5)-1),AttributeModifier.Operation.MULTIPLY_SCALAR_1)); }
                } else {
                    p.setFlying(false);p.setAllowFlight(false);p.setGravity(false);
                    flow.force(p.getUniqueId(),TemporalFlow.clamp(cfg("enemy-scale",0.15)));
                    p.setMaximumNoDamageTicks(Math.max(0,Math.min(20,(int)cfg("hurt-window-ticks",2))));p.setNoDamageTicks(0);
                }
            }
            flow.domain(session.owner,session.center,boundary(),cfg("enemy-scale",0.15));
            return true;
        } catch(Exception ex) {
            plugin.getLogger().warning("Temporal Divinity entry rolled back: "+ex.getMessage());
            finish(session);owner.sendMessage("§cTemporal Divinity could not open; charge was kept.");return false;
        }
    }
    private boolean teleport(Player p,Location to) {
        internalTeleport.add(p.getUniqueId());
        try {return p.teleport(to,PlayerTeleportEvent.TeleportCause.PLUGIN) && p.getWorld().equals(to.getWorld()) && p.getLocation().distanceSquared(to)<4;}
        finally {internalTeleport.remove(p.getUniqueId());}
    }
    void tick() {
        tick++;
        for(Session s:new HashSet<>(members.values())) {
            boolean invalid=--s.remaining<=0;
            for(UUID id:s.ids) {
                Player p=Bukkit.getPlayer(id);
                if(p==null || p.isDead() || !p.getWorld().equals(s.center.getWorld()) || p.getGameMode()==GameMode.SPECTATOR
                    || p.getLocation().distanceSquared(s.center)>boundary()*boundary()) {invalid=true;break;}
                p.setFallDistance(0);
                // Re-assert the movement state every tick: other abilities (Puff double jump, Astral Projection) and
                // other plugins can flip these flags mid-session, and an opponent must never end up able to fly.
                if(p.isFlying())p.setFlying(false);
                if(p.getAllowFlight()!=id.equals(s.owner))p.setAllowFlight(id.equals(s.owner));
                if(p.hasGravity())p.setGravity(false);
                if(p.isGliding())p.setGliding(false);
                if(id.equals(s.owner)) {
                    Input in=p.getCurrentInput();Location at=p.getLocation();
                    p.setVelocity(s.swim.step(swimTuning(),at.getYaw(),at.getPitch(),in.isForward(),in.isBackward(),in.isLeft(),in.isRight(),
                        in.isJump(),in.isSneak(),in.isSprint()||p.isSprinting(),at.toVector().subtract(s.center.toVector()),boundary()));
                } else {
                    // No controller and no input is read: sideways momentum (knockback) is bled off and the fall is
                    // re-pinned every tick, so jumping, sprinting or strafing cannot build speed or gain height.
                    Vector v=p.getVelocity();double keep=1-AirSwim.bound(cfg("enemy-horizontal-damping",0.5),0,1);
                    v.setX(v.getX()*keep);v.setZ(v.getZ()*keep);v.setY(-drift());p.setVelocity(v);
                }
            }
            if(invalid)finish(s);
        }
        if(tick%10==0) {
            for(UUID id:new HashSet<>(pending.keySet())) if(!members.containsKey(id)) {
                Player p=Bukkit.getPlayer(id);if(p!=null && !p.isDead())restore(p);
            }
            World w=Bukkit.getWorld(worldName());
            if(w!=null)for(Player p:w.getPlayers())if(!contains(p.getUniqueId()))teleport(p,fallback(p));
            fallGrace.entrySet().removeIf(en->en.getValue()<tick);
        }
    }
    private void finish(Session s) {
        flow.endDomain(s.owner);
        for(UUID id:s.ids)members.remove(id);
        for(UUID id:s.ids) {flow.unforce(id);Player p=Bukkit.getPlayer(id);if(p!=null && !p.isDead())restore(p);}
        s.chunks.forEach(c->c.removePluginChunkTicket(plugin));s.chunks.clear();
        saveJournal();
    }
    private Location fallback(Player p) {
        String configured=plugin.getConfig().getString("mythics.relay.temporal.divinity.fallback-world","");
        World w=Bukkit.getWorld(configured);
        if(w==null || w.getName().equals(worldName()))w=Bukkit.getWorlds().stream().filter(v->!v.getName().equals(worldName())).findFirst().orElseThrow();
        return w.getSpawnLocation();
    }
    private Location destination(Player p,Saved s) {
        World w=Bukkit.getWorld(s.world);
        if(w==null || w.getName().equals(worldName()))return fallback(p);
        Location l=new Location(w,s.x,s.y,s.z,s.yaw,s.pitch);
        if(l.getY()<w.getMinHeight()+1 || l.getY()>=w.getMaxHeight()-2 || !w.getWorldBorder().isInside(l) || !w.getChunkAt(l).load())return fallback(p);
        if(!l.getBlock().isPassable() || !l.clone().add(0,1,0).getBlock().isPassable() || l.getBlock().getType()==Material.LAVA)return fallback(p);
        return l;
    }
    private void restoreFlags(Player p,Saved s) {
        AttributeInstance a=p.getAttribute(Attribute.ATTACK_SPEED);if(a!=null && a.getModifier(attackKey)!=null)a.removeModifier(attackKey);
        flow.unforce(p.getUniqueId());flow.restoreLiving(p);
        p.setFlying(false);p.setAllowFlight(s.allowFlight);if(s.allowFlight)p.setFlying(s.flying);p.setFlySpeed(s.flySpeed);
        p.setGravity(s.gravity);p.setCollidable(s.collidable);p.setMaximumNoDamageTicks(s.maxNoDamage);p.setNoDamageTicks(s.noDamage);p.setLastDamage(s.lastDamage);
        p.setFallDistance(0);fallGrace.put(p.getUniqueId(),tick+Math.max(20,(long)cfg("return-fall-grace-ticks",100)));
    }
    private boolean restore(Player p) {
        Saved s=pending.get(p.getUniqueId());if(s==null)return true;
        try {
            Location to=destination(p,s);
            restoreFlags(p,s);
            if(!teleport(p,to)) {
                to=fallback(p);
                if(!teleport(p,to)) { p.setVelocity(new Vector());return false; }
            }
            // Original momentum only at the original return point; fallback spawns start at rest.
            p.setVelocity(to.getWorld().getName().equals(s.world) && to.distanceSquared(new Location(to.getWorld(),s.x,s.y,s.z))<1?s.velocity:new Vector());
            pending.remove(p.getUniqueId());strikes.remove(p.getUniqueId());saveJournal();return true;
        }catch(RuntimeException ex){plugin.getLogger().warning("Relay return pending for "+p.getUniqueId()+": "+ex.getMessage());return false;}
    }
    private boolean saveJournal() {
        if(!journalHealthy)return false;
        try {
            plugin.getDataFolder().mkdirs();YamlConfiguration y=new YamlConfiguration();pending.forEach((id,s)->s.write(y.createSection(id.toString())));
            Path temp=journal.toPath().resolveSibling(journal.getName()+".tmp");y.save(temp.toFile());
            try{Files.move(temp,journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temp,journal.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            return true;
        }catch(IOException ex){plugin.getLogger().severe("Relay recovery write failed: "+ex.getMessage());return false;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void travel(PlayerTeleportEvent e) {
        if(internalTeleport.contains(e.getPlayer().getUniqueId()))return;
        if(contains(e.getPlayer().getUniqueId()) || e.getTo()!=null && e.getTo().getWorld().getName().equals(worldName()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Player p))return;
        UUID id=p.getUniqueId();
        if(pending.containsKey(id) && !members.containsKey(id)) {e.setCancelled(true);return;}
        if(e.getCause()==EntityDamageEvent.DamageCause.FALL && (contains(id)||fallGrace.getOrDefault(id,0L)>tick))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void strike(EntityDamageByEntityEvent e) {
        Entity source=e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity shooter?shooter:e.getDamager();
        Session a=members.get(source.getUniqueId()),b=members.get(e.getEntity().getUniqueId());
        if(a!=b && (a!=null||b!=null)) {e.setCancelled(true);return;}
        if(a==null)return;
        UUID hitter=source.getUniqueId();
        int interval=hitter.equals(a.owner)?Math.max(1,(int)cfg("minimum-hit-interval-ticks",4)):
            Math.max(1,(int)Math.ceil(10/TemporalFlow.clamp(cfg("enemy-scale",0.15))));
        if(tick-strikes.getOrDefault(hitter,-1000L)<interval) {e.setCancelled(true);return;}
        if(hitter.equals(a.owner)) e.setDamage(Math.min(Math.max(0,cfg("maximum-hit-damage",6)), e.getDamage()*Math.max(0,cfg("damage-multiplier",0.5))));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void acceptedStrike(EntityDamageByEntityEvent e) {
        Entity source=e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity shooter?shooter:e.getDamager();
        if(e.getFinalDamage()>0 && members.containsKey(source.getUniqueId()))strikes.put(source.getUniqueId(),tick);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {Session s=members.get(e.getPlayer().getUniqueId());if(s!=null)finish(s);saveJournal();}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e) {
        Player p=e.getEntity(); Session session=members.get(p.getUniqueId()); Saved saved=pending.get(p.getUniqueId());
        if(session==null || saved==null)return;
        // Existing soulbound/finale listeners filter drops at NORMAL priority first.
        Location drop=destination(p,saved); java.util.List<Item> spawned=new ArrayList<>();
        try {
            for(var item:e.getDrops())spawned.add(drop.getWorld().dropItemNaturally(drop,item.clone()));
            e.getDrops().clear();
        } catch(RuntimeException ex) {spawned.forEach(Entity::remove);plugin.getLogger().warning("Domain death-drop transfer failed: "+ex.getMessage());}
        if(e.getDroppedExp()>0) {
            int xp=e.getDroppedExp();drop.getWorld().spawn(drop,ExperienceOrb.class,orb->orb.setExperience(xp));e.setDroppedExp(0);
        }
        finish(session);
    }
    @EventHandler(ignoreCancelled=true) public void drop(PlayerDropItemEvent e) {if(contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e) {
        Saved s=pending.get(e.getPlayer().getUniqueId());
        if(s!=null)e.setRespawnLocation(destination(e.getPlayer(),s));
        Bukkit.getScheduler().runTask(plugin,()->restore(e.getPlayer()));
    }
    @EventHandler public void join(PlayerJoinEvent e) {Bukkit.getScheduler().runTask(plugin,()->restore(e.getPlayer()));}
    @EventHandler(ignoreCancelled=true) public void unload(WorldUnloadEvent e) {
        if(e.getWorld().getName().equals(worldName()) && (!members.isEmpty()||!pending.isEmpty()))e.setCancelled(true);
        if(pending.values().stream().anyMatch(s->s.world.equals(e.getWorld().getName())))e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e) {if(e.getBlock().getWorld().getName().equals(worldName()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e) {if(e.getBlock().getWorld().getName().equals(worldName()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void bucket(PlayerBucketEmptyEvent e) {if(e.getPlayer().getWorld().getName().equals(worldName()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void spawn(CreatureSpawnEvent e) {if(e.getLocation().getWorld().getName().equals(worldName()))e.setCancelled(true);}
    /** Nobody in a domain uses native flight: the caster air-swims instead, opponents stay suspended. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flight(PlayerToggleFlightEvent e) {
        if(e.isFlying() && members.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);
    }
    /** An elytra would otherwise let a captured player glide out of the boundary and end the session. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void glide(EntityToggleGlideEvent e) {
        if(e.isGliding() && members.containsKey(e.getEntity().getUniqueId()))e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void mode(PlayerGameModeChangeEvent e) {Session s=members.get(e.getPlayer().getUniqueId());if(s!=null)finish(s);}
    void shutdown() {for(Session s:new HashSet<>(members.values()))finish(s);for(UUID id:new HashSet<>(pending.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null&&!p.isDead())restore(p);}saveJournal();}
}
