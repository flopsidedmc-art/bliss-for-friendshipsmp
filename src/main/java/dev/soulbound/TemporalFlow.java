package dev.soulbound;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** One time scale per entity. Canonical custom-projectile velocity is never overwritten. */
final class TemporalFlow implements org.bukkit.event.Listener {
    private final SoulboundGemsPlugin plugin;
    private final NamespacedKey motionKey=new NamespacedKey("soulbound-gems","temporal_projectile_motion");
    private final NamespacedKey slowKey = new NamespacedKey("soulbound-gems", "temporal_slow");
    private final List<Field> fields = new ArrayList<>();
    private record Region(Location center, double radius, double scale) {}
    private final Map<UUID, Region> regions = new HashMap<>();
    private final Map<UUID, Double> forced = new HashMap<>();
    private final Set<UUID> slowLiving = new HashSet<>();
    private final Map<UUID, Motion> projectiles = new HashMap<>();
    private long tick;
    private final Map<UUID,Long> meleeTick=new HashMap<>();
    private static class Field {
        UUID owner; Location center; double radius; long age, duration; double phase;
        List<ItemDisplay> displays = new ArrayList<>();
    }
    private static class Motion {
        Vector canonical, lastApplied; boolean gravity;
        Motion(Projectile p) { canonical=p.getVelocity().clone(); gravity=p.hasGravity(); }
    }
    TemporalFlow(SoulboundGemsPlugin plugin) {
        this.plugin=plugin; Bukkit.getPluginManager().registerEvents(this,plugin);
        // One startup recovery pass, never a repeating full-world scan.
        for(World w:Bukkit.getWorlds())for(Projectile p:w.getEntitiesByClass(Projectile.class))recoverMotion(p);
    }
    void domain(UUID owner, Location center, double radius, double scale) { regions.put(owner,new Region(center,radius,clamp(scale))); }
    void endDomain(UUID owner) { regions.remove(owner); }
    private double cfg(String k,double d) { return plugin.getConfig().getDouble("mythics.relay.temporal."+k,d); }
    static double clamp(double s) { return Math.max(0.01,Math.min(1,s)); }
    boolean active(UUID id) { return fields.stream().anyMatch(f->f.owner.equals(id)); }
    void force(UUID id, double scale) { forced.put(id,clamp(scale)); }
    void unforce(UUID id) { forced.remove(id); }
    boolean start(Player p) {
        if(fields.size()>=Math.max(1,(int)cfg("dilate.max-fields",8))) return false;
        removeOwner(p.getUniqueId());
        Field f=new Field(); f.owner=p.getUniqueId(); f.center=p.getLocation().clone();
        f.radius=Math.max(1,Math.min(48,cfg("dilate.radius",10)));
        f.duration=Math.max(1,Math.min(2400,(long)(cfg("dilate.duration-seconds",8)*20)));
        fields.add(f);
        if(plugin.getConfig().getBoolean("mythics.relay.temporal.dilate.display-models",true)) {
            try {
                for(int i=0;i<2;i++) {
                    ItemStack item=new ItemStack(Material.PAPER);
                    var meta=item.getItemMeta();
                    meta.setItemModel(new NamespacedKey("blissgems",i==0?"temporal_clock":"temporal_hand")); item.setItemMeta(meta);
                    ItemDisplay d=f.center.getWorld().spawn(f.center.clone().add(0,0.07+i*0.015,0),ItemDisplay.class);
                    d.setItemStack(item); d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setPersistent(false); d.setInvulnerable(true); d.setBrightness(new Display.Brightness(15,15));
                    d.setViewRange(1.5f); d.setInterpolationDuration(2); d.setDisplayWidth((float)(f.radius*2)); d.setDisplayHeight(1);
                    f.displays.add(d);
                }
            } catch(RuntimeException ex) { removeDisplays(f); plugin.getLogger().warning("Relay display fallback: "+ex.getMessage()); }
        }
        p.getWorld().playSound(p.getLocation(),Sound.BLOCK_BEACON_ACTIVATE,0.8f,0.6f);
        return true;
    }
    private boolean exempt(Field f, UUID owner) {
        if(owner==null)return false;
        if(f.owner.equals(owner))return true;
        Player a=Bukkit.getPlayer(f.owner), b=Bukkit.getPlayer(owner);
        return a!=null && b!=null && plugin.listener().isTrusted(a,b);
    }
    double scaleAt(Location at, UUID owner) {
        if(owner!=null && forced.containsKey(owner)) return forced.get(owner); // domain has precedence
        for(var entry:regions.entrySet()) {
            Region r=entry.getValue();
            if(at.getWorld().equals(r.center.getWorld()) && at.distanceSquared(r.center)<=r.radius*r.radius)
                return entry.getKey().equals(owner)?1:r.scale;
        }
        double scale=1;
        for(Field f:fields) if(at.getWorld().equals(f.center.getWorld()) && !exempt(f,owner)
            && at.distanceSquared(f.center)<=f.radius*f.radius) scale=Math.min(scale,clamp(cfg("dilate.scale",0.10)));
        return scale;
    }
    void tick() {
        tick++;
        if(tick%200==0)meleeTick.entrySet().removeIf(e->tick-e.getValue()>2400);
        Map<UUID, Double> living=new HashMap<>(forced), shots=new HashMap<>();
        Iterator<Field> fi=fields.iterator();
        while(fi.hasNext()) {
            Field f=fi.next();
            if(++f.age>f.duration || Bukkit.getPlayer(f.owner)==null || !f.center.getWorld().isChunkLoaded(f.center.getBlockX()>>4,f.center.getBlockZ()>>4)) {
                removeDisplays(f); fi.remove(); continue;
            }
            draw(f);
            int scanned=0;
            for(Entity e:f.center.getWorld().getNearbyEntities(f.center,f.radius,f.radius,f.radius)) {
                if(++scanned>Math.max(16,(int)cfg("dilate.max-entities-per-field",256)))break;
                if(e.getLocation().distanceSquared(f.center)>f.radius*f.radius)continue;
                UUID owner=e instanceof Projectile p && p.getShooter() instanceof Entity shooter?shooter.getUniqueId():e.getUniqueId();
                if(exempt(f,owner))continue;
                double scale=scaleAt(e.getLocation(),owner);
                if(e instanceof Projectile) shots.merge(e.getUniqueId(),scale,Math::min);
                else if(e instanceof LivingEntity && !(e instanceof ArmorStand)
                    && !(e instanceof Player p && p.getGameMode()==GameMode.SPECTATOR)) living.merge(e.getUniqueId(),scale,Math::min);
            }
        }
        for(var entry:regions.entrySet()) {
            Region r=entry.getValue();
            for(Entity e:r.center.getWorld().getNearbyEntities(r.center,r.radius,r.radius,r.radius)) if(e instanceof Projectile pr) {
                UUID shooter=pr.getShooter() instanceof Entity who?who.getUniqueId():null;
                double scale=scaleAt(e.getLocation(),shooter);
                if(scale<1)shots.merge(e.getUniqueId(),scale,Math::min);
            }
        }
        for(UUID id:new HashSet<>(slowLiving)) if(!living.containsKey(id)) {
            Entity e=Bukkit.getEntity(id); if(e instanceof LivingEntity le) restoreLiving(le); slowLiving.remove(id);
        }
        living.forEach((id,scale)-> {
            Entity e=Bukkit.getEntity(id); if(!(e instanceof LivingEntity le) || !le.isValid())return;
            slowLiving.add(id);
            modifier(le,Attribute.MOVEMENT_SPEED,scale); modifier(le,Attribute.ATTACK_SPEED,scale);
            modifier(le,Attribute.GRAVITY,scale*scale); modifier(le,Attribute.JUMP_STRENGTH,scale);
            Vector movement=le.getVelocity(); double cap=Math.max(0.1,cfg("dilate.horizontal-speed-cap",0.6))*scale;
            double horizontal=Math.hypot(movement.getX(),movement.getZ());
            if(horizontal>cap) {movement.setX(movement.getX()*cap/horizontal);movement.setZ(movement.getZ()*cap/horizontal);le.setVelocity(movement);}
            if(!le.isOnGround() && le.getVelocity().getY() < -0.45*scale) {
                Vector v=le.getVelocity(); v.setY(-0.45*scale); le.setVelocity(v);
            }
        });
        shots.forEach((id,scale)-> {
            Entity e=Bukkit.getEntity(id);
            if(!(e instanceof Projectile p) || !p.isValid() || p instanceof AbstractArrow a && a.isInBlock())return;
            Motion m=projectiles.computeIfAbsent(id,k->new Motion(p));
            Vector actual=p.getVelocity();
            // A collision/plugin redirect changes direction: preserve it, never restore stale direction.
            if(m.lastApplied!=null && actual.lengthSquared()>1e-8 && m.lastApplied.lengthSquared()>1e-8
                && actual.clone().normalize().dot(m.lastApplied.clone().normalize())<0.8)
                m.canonical=actual.clone().normalize().multiply(m.canonical.length());
            if(m.gravity) {
                double gravity=p instanceof AbstractArrow?0.05:0.03;
                m.canonical.multiply(Math.pow(0.99,scale)).add(new Vector(0,-gravity*scale,0));
            }
            p.getPersistentDataContainer().set(motionKey,org.bukkit.persistence.PersistentDataType.LONG_ARRAY,
                new long[]{Double.doubleToRawLongBits(m.canonical.getX()),Double.doubleToRawLongBits(m.canonical.getY()),Double.doubleToRawLongBits(m.canonical.getZ()),m.gravity?1:0});
            p.setGravity(false); m.lastApplied=m.canonical.clone().multiply(scale); p.setVelocity(m.lastApplied);
        });
        Iterator<Map.Entry<UUID,Motion>> it=projectiles.entrySet().iterator();
        while(it.hasNext()) {
            var en=it.next(); Entity e=Bukkit.getEntity(en.getKey());
            boolean grounded=e instanceof AbstractArrow a && a.isInBlock();
            if(shots.containsKey(en.getKey()) && e!=null && e.isValid() && !grounded)continue;
            if(e instanceof Projectile p && p.isValid()) {
                p.getPersistentDataContainer().remove(motionKey);
                p.setGravity(en.getValue().gravity);
                if(!grounded)p.setVelocity(en.getValue().canonical);
            }
            it.remove();
        }
    }
    private void modifier(LivingEntity e,Attribute type,double scale) {
        AttributeInstance a=e.getAttribute(type); if(a==null)return;
        AttributeModifier old=a.getModifier(slowKey);
        double amount=scale-1;
        if(old!=null && Math.abs(old.getAmount()-amount)<1e-6)return;
        if(old!=null)a.removeModifier(old);
        a.addTransientModifier(new AttributeModifier(slowKey,amount,AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
    void restoreLiving(LivingEntity e) {
        for(Attribute t:new Attribute[]{Attribute.MOVEMENT_SPEED,Attribute.ATTACK_SPEED,Attribute.GRAVITY,Attribute.JUMP_STRENGTH}) {
            AttributeInstance a=e.getAttribute(t); if(a!=null && a.getModifier(slowKey)!=null)a.removeModifier(slowKey);
        }
    }
    private void draw(Field f) {
        // Rapid entry -> slow middle -> accelerating exit, in server ticks.
        double edge=Math.min(f.age,f.duration-f.age)/20.0;
        f.phase+=0.025+0.48*Math.pow(Math.max(0,1-edge),2);
        if(tick%2!=0)return;
        for(int i=0;i<f.displays.size();i++) {
            ItemDisplay d=f.displays.get(i); if(!d.isValid())continue;
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(),new Quaternionf().rotateY((float)(f.phase*(i==0?1:-2))),
                new Vector3f((float)f.radius,1,(float)f.radius),new Quaternionf()));
        }
        if(f.displays.isEmpty() && tick%Math.max(2,(int)cfg("dilate.particle-interval-ticks",4))==0) {
            Particle.DustOptions brown=new Particle.DustOptions(Color.fromRGB(0x5A,0x3A,0x1C),1.1f);
            Particle.DustOptions bronze=new Particle.DustOptions(Color.fromRGB(0x92,0x61,0x3B),1.1f);
            Particle.DustOptions gold=new Particle.DustOptions(Color.fromRGB(0xC9,0x98,0x55),1.1f);
            for(int i=0;i<36;i++) { if(i%9==0)continue; double a=f.phase+i*Math.PI/18;
                Particle.DustOptions dust=i%9==1?gold:(i%3==0?bronze:brown);
                f.center.getWorld().spawnParticle(Particle.DUST,f.center.clone().add(Math.cos(a)*f.radius,0.1,Math.sin(a)*f.radius),1,0,0,0,0,dust);
            }
        }
    }
    void removeOwner(UUID id) { fields.removeIf(f->{ if(!f.owner.equals(id))return false; removeDisplays(f); return true; }); }
    private void removeDisplays(Field f) { f.displays.forEach(Entity::remove); f.displays.clear(); }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST,ignoreCancelled=true)
    public void melee(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if(!(e.getDamager() instanceof LivingEntity attacker))return;
        UUID id=attacker.getUniqueId(); double scale=scaleAt(attacker.getLocation(),id);
        if(scale>=1 || forced.containsKey(id))return; // domain owns its damage cadence
        long interval=Math.max(1,(long)Math.ceil(cfg("dilate.melee-base-interval-ticks",10)/scale));
        if(tick-meleeTick.getOrDefault(id,-100000L)<interval)e.setCancelled(true);
    }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
    public void acceptedMelee(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if(e.getDamager() instanceof LivingEntity a && e.getFinalDamage()>0 && scaleAt(a.getLocation(),a.getUniqueId())<1)
            meleeTick.put(a.getUniqueId(),tick);
    }
    @org.bukkit.event.EventHandler(ignoreCancelled=true)
    public void unload(org.bukkit.event.world.ChunkUnloadEvent event) {
        for(Entity e:event.getChunk().getEntities()) {
            Motion m=projectiles.remove(e.getUniqueId());
            if(m!=null && e instanceof Projectile p) {p.getPersistentDataContainer().remove(motionKey);p.setGravity(m.gravity);if(!(p instanceof AbstractArrow a&&a.isInBlock()))p.setVelocity(m.canonical);}
            if(e instanceof LivingEntity le && slowLiving.remove(e.getUniqueId()))restoreLiving(le);
        }
        fields.removeIf(f->{if(f.center.getWorld().equals(event.getWorld()) && (f.center.getBlockX()>>4)==event.getChunk().getX() && (f.center.getBlockZ()>>4)==event.getChunk().getZ()){removeDisplays(f);return true;}return false;});
    }
    private void recoverMotion(Projectile p) {
        long[] m=p.getPersistentDataContainer().get(motionKey,org.bukkit.persistence.PersistentDataType.LONG_ARRAY);
        if(m==null || m.length!=4)return;
        p.setGravity(m[3]!=0);if(!(p instanceof AbstractArrow a&&a.isInBlock()))p.setVelocity(new Vector(Double.longBitsToDouble(m[0]),Double.longBitsToDouble(m[1]),Double.longBitsToDouble(m[2])));
        p.getPersistentDataContainer().remove(motionKey);
    }
    @org.bukkit.event.EventHandler
    public void loaded(org.bukkit.event.world.EntitiesLoadEvent e) {for(Entity entity:e.getEntities())if(entity instanceof Projectile p)recoverMotion(p);}
    void shutdown() {
        fields.forEach(this::removeDisplays);fields.clear();forced.clear();regions.clear();meleeTick.clear();
        for(UUID id:slowLiving) { Entity e=Bukkit.getEntity(id); if(e instanceof LivingEntity le)restoreLiving(le); } slowLiving.clear();
        projectiles.forEach((id,m)->{Entity e=Bukkit.getEntity(id);if(e instanceof Projectile p){p.getPersistentDataContainer().remove(motionKey);p.setGravity(m.gravity);if(!(p instanceof AbstractArrow a&&a.isInBlock()))p.setVelocity(m.canonical);}}); projectiles.clear();
    }
}
