package dev.soulbound;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Heretic's Games' curses, one per round (events.heretic-games.curses.&lt;id&gt;, each with enabled: and its own
 * numbers). Every curse undoes itself in end(): modifiers off, effects off, gems back, lava gone. HereticGames calls
 * clear() on everyone as well, so nothing outlives a round, a leave, a stop or a restart.
 */
final class HereticCurses {
    private HereticCurses() {}

    /** In curse order: the ids and how the round title names them. */
    static final Map<String, String> NAMES = new java.util.LinkedHashMap<>();
    static {
        NAMES.put("low-gravity", "Low Gravity");
        NAMES.put("swap", "Swap");
        NAMES.put("gem-roulette", "Gem Roulette");
        NAMES.put("no-sprint", "No Sprint");
        NAMES.put("bloodthirst", "Bloodthirst");
        NAMES.put("rising-lava", "Rising Lava");
        NAMES.put("blind", "Blind");
        NAMES.put("fists-only", "Fists Only");
        NAMES.put("reverse-knockback", "Reverse Knockback");
        NAMES.put("one-shot", "One Shot");
    }

    static final NamespacedKey MOD_GRAVITY = new NamespacedKey("soulboundgems", "hg_gravity");
    static final NamespacedKey MOD_FALL = new NamespacedKey("soulboundgems", "hg_fall");
    static final NamespacedKey MOD_HEALTH = new NamespacedKey("soulboundgems", "hg_onehp");

    /** Every attribute change a curse makes, off again (safe to call on anyone, any time: a join after a crash). */
    static void clearModifiers(Player p) {
        remove(p, Attribute.GRAVITY, MOD_GRAVITY);
        remove(p, Attribute.SAFE_FALL_DISTANCE, MOD_FALL);
        remove(p, Attribute.MAX_HEALTH, MOD_HEALTH);
    }

    private static void remove(Player p, Attribute a, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) if (key.equals(m.getKey())) inst.removeModifier(m);
    }

    private static void add(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        remove(p, a, key);
        inst.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    static Curse make(String id) {
        if (id == null) return null;
        return switch (id) {
            case "low-gravity" -> new LowGravity();
            case "swap" -> new Swap();
            case "gem-roulette" -> new GemRoulette();
            case "no-sprint" -> new NoSprint();
            case "bloodthirst" -> new Bloodthirst();
            case "rising-lava" -> new RisingLava();
            case "blind" -> new Blind();
            case "fists-only" -> new FistsOnly();
            case "reverse-knockback" -> new ReverseKnockback();
            case "one-shot" -> new OneShot();
            default -> null;
        };
    }

    /** One curse. The games call begin() as the round starts, second() once a second, end() as it ends. */
    abstract static class Curse {
        final String id;

        Curse(String id) { this.id = id; }

        String name() { return NAMES.getOrDefault(id, id); }

        /** The round title's second line. */
        abstract String hint();

        ConfigurationSection cfg(HereticGames g) { return g.curseConfig(id); }

        double num(HereticGames g, String key, double def) { ConfigurationSection c = cfg(g); return c == null ? def : c.getDouble(key, def); }

        void begin(HereticGames g, List<Player> alive) {}

        void second(HereticGames g, List<Player> alive, int elapsed) {}

        void end(HereticGames g, List<Player> everyone) {}

        /** One player is out (fell, left): their own part undone now. The round-wide parts (the lava) wait for end(). */
        void off(HereticGames g, Player p) { end(g, List.of(p)); }

        /** The kit is replaced (One Shot) or changed after it's handed out (Fists Only). */
        boolean replacesKit() { return false; }

        void afterKit(HereticGames g, Player p) {}

        boolean noSprint() { return false; }

        boolean reverseKnockback() { return false; }

        boolean noProjectiles() { return false; }

        boolean noRegen() { return false; }

        void hit(HereticGames g, Player attacker) {}
    }

    /** Low gravity: you fall slowly and jump high (and land without a scratch). */
    static final class LowGravity extends Curse {
        LowGravity() { super("low-gravity"); }
        @Override String hint() { return "Jump. Keep jumping."; }
        @Override void begin(HereticGames g, List<Player> alive) {
            double gravity = Math.max(0.05, Math.min(1.0, num(g, "gravity", 0.35)));
            for (Player p : alive) {
                add(p, Attribute.GRAVITY, MOD_GRAVITY, gravity - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
                add(p, Attribute.SAFE_FALL_DISTANCE, MOD_FALL, num(g, "safe-fall-blocks", 10), AttributeModifier.Operation.ADD_NUMBER);
            }
        }
        @Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) clearModifiers(p); }
    }

    /** Swap: every so often everyone trades places with someone else. */
    static final class Swap extends Curse {
        Swap() { super("swap"); }
        @Override String hint() { return "Nobody stays where they are."; }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            int every = (int) Math.max(5, num(g, "every-seconds", 25));
            if (elapsed <= 0 || elapsed % every != 0 || alive.size() < 2) return;
            List<Location> spots = new ArrayList<>();
            for (Player p : alive) spots.add(p.getLocation().clone());
            int[] to = GamesMath.derangement(alive.size(), g.rnd);
            for (int i = 0; i < alive.size(); i++) {
                Player p = alive.get(i);
                Location dest = spots.get(to[i]).clone();
                dest.setYaw(p.getLocation().getYaw());
                dest.setPitch(p.getLocation().getPitch());
                g.move(p, dest);
                p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.02);
                GemSounds.playTo(g.plugin(), "heretic-games.swap", p);
            }
        }
    }

    /** Gem Roulette: every 30 s your gem becomes another; your own (and its cooldowns) come back when the round ends. */
    static final class GemRoulette extends Curse {
        GemRoulette() { super("gem-roulette"); }
        @Override String hint() { return "Your gem isn't yours for long."; }
        @Override void begin(HereticGames g, List<Player> alive) { for (Player p : alive) g.roulette(p); }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            int every = (int) Math.max(5, num(g, "every-seconds", 30));
            if (elapsed > 0 && elapsed % every == 0) for (Player p : alive) g.roulette(p);
        }
        @Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) g.rouletteRestore(p); }
    }

    /** No sprinting (food stays above 6, so it's the curse and not hunger). */
    static final class NoSprint extends Curse {
        NoSprint() { super("no-sprint"); }
        @Override String hint() { return "Walk."; }
        @Override boolean noSprint() { return true; }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            for (Player p : alive) {
                if (p.getFoodLevel() < 7) p.setFoodLevel(7);
                if (p.isSprinting()) p.setSprinting(false);
            }
        }
    }

    /** Bloodthirst: no natural healing, and without a hit you bleed; every hit you land heals you. */
    static final class Bloodthirst extends Curse {
        private final Map<UUID, Long> lastHit = new HashMap<>();
        Bloodthirst() { super("bloodthirst"); }
        @Override String hint() { return "Hit, or bleed."; }
        @Override boolean noRegen() { return true; }
        @Override void begin(HereticGames g, List<Player> alive) {
            long now = System.currentTimeMillis();
            for (Player p : alive) lastHit.put(p.getUniqueId(), now);
        }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            int every = (int) Math.max(1, num(g, "drain-every-seconds", 3));
            if (elapsed % every != 0) return;
            long now = System.currentTimeMillis(), grace = (long) (num(g, "grace-seconds", 6) * 1000);
            for (Player p : alive) {
                long since = now - lastHit.getOrDefault(p.getUniqueId(), now);
                double h = GamesMath.bloodthirst(p.getHealth(), since, grace, num(g, "drain", 1.0), 1.0);
                if (h < p.getHealth()) {
                    p.setHealth(h);
                    p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 3, 0.2, 0.3, 0.2, 0);
                }
            }
        }
        @Override void hit(HereticGames g, Player attacker) {
            lastHit.put(attacker.getUniqueId(), System.currentTimeMillis());
            double max = attacker.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : attacker.getAttribute(Attribute.MAX_HEALTH).getValue();
            attacker.setHealth(Math.min(max, attacker.getHealth() + num(g, "heal-on-hit", 2.0)));
        }
    }

    /**
     * Rising lava: from the arena's floor up, a layer every so often, up to rise-height. Only air turns to lava, every
     * block in the crash-safe land ledger (LandWorks), and all of it goes back when the round ends.
     */
    static final class RisingLava extends Curse {
        private LandWorks.Job job;
        private int filled = -1;
        RisingLava() { super("rising-lava"); }
        @Override String hint() { return "The floor is rising."; }
        @Override void begin(HereticGames g, List<Player> alive) {
            int[] b = g.box();
            World w = g.world();
            if (b == null || w == null || g.plugin().land() == null) return;
            job = g.plugin().land().open("heretic-lava", w, (b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2, 0, false, true, Long.MAX_VALUE);
        }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            if (job == null) return;
            int rise = (int) Math.max(1, num(g, "rise-height", 6));
            int layer = GamesMath.lavaLayer(elapsed, (int) Math.max(5, num(g, "every-seconds", 20)), rise);
            int[] b = g.box();
            boolean any = false;
            while (filled < layer && b[1] + filled + 1 <= b[4]) {
                filled++;
                int y = b[1] + filled;
                for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
                    Block bl = g.world().getBlockAt(x, y, z);
                    if (!bl.getType().isAir() || g.plugin().land().owns(bl)) continue;
                    g.plugin().land().record(job, LandLedger.Phase.PIT, bl, Material.LAVA);
                    bl.setType(Material.LAVA, false);
                }
                any = true;
            }
            if (!any) return;
            g.plugin().land().save(); // in the ledger before anything else happens: a crash still puts it back
            for (Player p : alive) GemSounds.playTo(g.plugin(), "heretic-games.lava", p);
        }
        @Override void off(HereticGames g, Player p) {} // the lava stays for everyone else

        @Override void end(HereticGames g, List<Player> everyone) {
            if (job == null) return;
            for (LandLedger.Entry e : new ArrayList<>(job.ledger.entries())) g.plugin().land().restoreOne(job, e);
            g.plugin().land().close(job);
            job = null;
        }
    }

    /** Blind: Darkness, and the heartbeat of whoever is nearest, louder the closer they are. */
    static final class Blind extends Curse {
        Blind() { super("blind"); }
        @Override String hint() { return "Listen for their heartbeat."; }
        @Override void begin(HereticGames g, List<Player> alive) { for (Player p : alive) dark(p); }
        private static void dark(Player p) { p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false)); }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) {
            double range = num(g, "heartbeat-range", 24);
            for (Player p : alive) {
                if (!p.hasPotionEffect(PotionEffectType.DARKNESS)) dark(p);
                double best = Double.MAX_VALUE;
                for (Player o : alive) if (o != p && o.getWorld() == p.getWorld()) best = Math.min(best, o.getLocation().distance(p.getLocation()));
                if (!GamesMath.heartbeatNow(elapsed, best, range)) continue;
                GemSounds.playScaled(g.plugin(), "heretic-games.heartbeat", p.getLocation(), p, GamesMath.heartbeatVolume(best, range), 1.0);
            }
        }
        @Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) p.removePotionEffect(PotionEffectType.DARKNESS); }
    }

    /** Fists only: the kit's weapons go (armour and food stay), and everyone gets Strength II. */
    static final class FistsOnly extends Curse {
        FistsOnly() { super("fists-only"); }
        @Override String hint() { return "Bare hands."; }
        @Override boolean noProjectiles() { return true; }
        @Override void afterKit(HereticGames g, Player p) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                if (c[i] == null || !GamesKits.isKitItem(c[i])) continue;
                boolean armour = i >= GamesMath.BOOTS && i <= GamesMath.HELMET;
                if (!armour && !c[i].getType().isEdible()) inv.setItem(i, null);
            }
            int level = (int) Math.max(1, num(g, "strength", 2));
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, level - 1, false, false, true));
        }
        @Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) p.removePotionEffect(PotionEffectType.STRENGTH); }
    }

    /** Reverse knockback: a hit pulls you toward whoever hit you. */
    static final class ReverseKnockback extends Curse {
        ReverseKnockback() { super("reverse-knockback"); }
        @Override String hint() { return "Every hit pulls you in."; }
        @Override boolean reverseKnockback() { return true; }
    }

    /** One shot: one heart each, a bow and 64 arrows, whatever the kit was. */
    static final class OneShot extends Curse {
        OneShot() { super("one-shot"); }
        @Override String hint() { return "One heart. One arrow is enough."; }
        @Override boolean replacesKit() { return true; }
        @Override void afterKit(HereticGames g, Player p) {
            p.getInventory().setItem(0, GamesKits.tagged(new ItemStack(Material.BOW)));
            p.getInventory().setItem(9, GamesKits.tagged(new ItemStack(Material.ARROW, (int) Math.max(1, Math.min(64, num(g, "arrows", 64))))));
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                remove(p, Attribute.MAX_HEALTH, MOD_HEALTH);
                add(p, Attribute.MAX_HEALTH, MOD_HEALTH, 2.0 - max.getValue(), AttributeModifier.Operation.ADD_NUMBER);
            }
            p.setHealth(Math.min(2.0, p.getHealth()));
            p.setAbsorptionAmount(0);
        }
        @Override void second(HereticGames g, List<Player> alive, int elapsed) { for (Player p : alive) if (p.getAbsorptionAmount() > 0) p.setAbsorptionAmount(0); }
        @Override void end(HereticGames g, List<Player> everyone) { for (Player p : everyone) remove(p, Attribute.MAX_HEALTH, MOD_HEALTH); }
    }
}
