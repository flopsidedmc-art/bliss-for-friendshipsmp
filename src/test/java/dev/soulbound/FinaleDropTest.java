package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The finale pedestal dupe: a merge gem dropped at its pedestal must end up in exactly one place, 1 on the pedestal
 * and 0 in the inventory, whatever the other drop listeners did and in whatever order they ran.
 *
 * A cut-down copy of Paper's drop runs the real PedestalDrop logic. The item leaves its slot, then the listeners run.
 * A cancelled drop is given back: a Q drop to the hand if it's empty, anything else to the first free storage slot,
 * and if there's none it's deleted. A drop nobody cancelled lands on the ground.
 */
class FinaleDropTest {
    /** An item stack. gem is null for anything that isn't a mythic; token is the pedestal drop's stamp. */
    static final class Stack {
        final GemType gem;
        String token;
        Stack(GemType gem) { this.gem = gem; }
    }

    static final class Event {
        final Stack item;
        boolean cancelled;
        Event(Stack item) { this.item = item; }
    }

    /** One player, the ground, the pedestals and the plugin's three drop listeners. */
    static final class Sim {
        final Stack[] slots = new Stack[41]; // 0-8 hotbar, 9-35 storage, 36-39 armour, 40 off hand
        Stack cursor;
        final List<Stack> ground = new ArrayList<>();
        final Map<GemType, Integer> pedestal = new EnumMap<>(GemType.class);
        final PedestalDrop drops = new PedestalDrop();
        final List<Runnable> nextTick = new ArrayList<>();
        int held = 0;
        boolean dropPhase = true, near = true, chainOut = true;
        int releases;

        PedestalDrop.Facts facts(Stack s) {
            boolean merge = s.gem == GemType.AURATUS || s.gem == GemType.HERETIC;
            return new PedestalDrop.Facts(dropPhase, merge, merge && placed(s.gem), near);
        }

        boolean placed(GemType t) { return pedestal.getOrDefault(t, 0) > 0; }

        // the plugin's listeners, as they decide
        final Consumer<Event> anotherPlugin = e -> e.cancelled = true; // cancels every drop, ours included
        final Consumer<Event> soulbound = e -> { // GemListener.onDrop, NORMAL: Auratus and Heretic aren't soulbound any more
            if (e.item.gem == null || e.item.gem == GemType.AURATUS || e.item.gem == GemType.HERETIC) return;
            if (dropPhase && PedestalDrop.owns(facts(e.item))) return;
            e.cancelled = true;
        };
        final Consumer<Event> auratusDropped = e -> { // AuratusGem.onDropped, MONITOR, ignoreCancelled: the chain goes with the gem
            if (e.cancelled || e.item.gem != GemType.AURATUS || !chainOut) return;
            chainOut = false;
            releases++;
        };
        final Consumer<Event> finale = e -> { // Finale.onDrop, HIGHEST
            boolean couldNotReturn = firstFreeStorage() < 0;
            GemType type = facts(e.item).mergeGem() ? e.item.gem : null;
            PedestalDrop.Verdict v = drops.onDrop(type, facts(e.item), () -> e.cancelled = true,
                    () -> e.item.token = UUID.randomUUID().toString(), couldNotReturn);
            if (v == PedestalDrop.Verdict.CLAIMED) nextTick.add(() -> {
                if (drops.settle(type, dropPhase, placed(type), this::take, this::strip)) pedestal.merge(type, 1, Integer::sum);
            });
        };
        final Consumer<Event> uncancels = e -> e.cancelled = false; // a badly behaved plugin after everyone

        List<Consumer<Event>> listeners = new ArrayList<>(List.of(anotherPlugin, soulbound, finale, auratusDropped));

        int firstFreeStorage() {
            for (int i = 0; i < 36; i++) if (slots[i] == null) return i;
            return -1;
        }

        private void fire(Stack item, boolean fromHand) {
            Event e = new Event(item);
            for (Consumer<Event> l : listeners) l.accept(e);
            if (!e.cancelled) { ground.add(item); return; }
            if (fromHand && slots[held] == null) { slots[held] = item; return; }
            int free = firstFreeStorage();
            if (free >= 0) slots[free] = item; // otherwise Paper's fallback has nowhere to put it: deleted
        }

        void pressQ() {
            Stack s = slots[held];
            if (s == null) return;
            slots[held] = null;
            fire(s, true);
        }

        /** Q over a slot in the inventory screen (the off hand, say). */
        void throwFrom(int slot) {
            Stack s = slots[slot];
            slots[slot] = null;
            fire(s, false);
        }

        /** Clicking outside the inventory screen with an item on the cursor. */
        void dropCursor() {
            Stack s = cursor;
            cursor = null;
            fire(s, false);
        }

        void tick() {
            List<Runnable> run = new ArrayList<>(nextTick);
            nextTick.clear();
            run.forEach(Runnable::run);
        }

        /** Finale.take: the one stack carrying the token, from any slot, the cursor or the ground. */
        boolean take(String token) {
            for (int i = 0; i < slots.length; i++) if (slots[i] != null && token.equals(slots[i].token)) { slots[i] = null; return true; }
            if (cursor != null && token.equals(cursor.token)) { cursor = null; return true; }
            return ground.removeIf(s -> token.equals(s.token));
        }

        /** Finale.find without taking: the stack stays where it is and only the id comes off. */
        void strip(String token) {
            for (Stack s : slots) if (s != null && token.equals(s.token)) s.token = null;
            if (cursor != null && token.equals(cursor.token)) cursor.token = null;
            for (Stack s : ground) if (token.equals(s.token)) s.token = null;
        }

        /** Every gem of this type that still carries a pedestal id, wherever it is. */
        int stamped(GemType t) {
            int n = 0;
            for (Stack s : slots) if (s != null && s.gem == t && s.token != null) n++;
            if (cursor != null && cursor.gem == t && cursor.token != null) n++;
            for (Stack s : ground) if (s.gem == t && s.token != null) n++;
            return n;
        }

        int inInventory(GemType t) {
            int n = 0;
            for (Stack s : slots) if (s != null && s.gem == t) n++;
            if (cursor != null && cursor.gem == t) n++;
            return n;
        }

        int onGround(GemType t) { return (int) ground.stream().filter(s -> s.gem == t).count(); }

        int onPedestal(GemType t) { return pedestal.getOrDefault(t, 0); }

        void fillStorage() { for (int i = 0; i < 36; i++) if (slots[i] == null) slots[i] = new Stack(null); }
    }

    private static void exactlyOnPedestal(Sim s, GemType t, String why) {
        assertEquals(1, s.onPedestal(t), why + ": on the pedestal");
        assertEquals(0, s.inInventory(t), why + ": not in the inventory");
        assertEquals(0, s.onGround(t), why + ": not on the ground");
    }

    @Test void theOldHandlerDuplicated() {
        Sim s = new Sim();
        s.slots[0] = new Stack(GemType.AURATUS);
        // the old rules: every gem soulbound, and the old Finale.onDrop (no priority, didn't look at cancelled, removed the
        // drop entity and placed)
        Consumer<Event> oldSoulbound = e -> { if (e.item.gem != null) e.cancelled = true; };
        s.listeners = new ArrayList<>(List.of(oldSoulbound, e -> { if (s.facts(e.item).mergeGem()) s.pedestal.merge(e.item.gem, 1, Integer::sum); }));
        s.pressQ();
        assertEquals(1, s.onPedestal(GemType.AURATUS));
        assertEquals(1, s.inInventory(GemType.AURATUS), "the bug: given back as well");
    }

    @Test void aPedestalDropEndsOnThePedestalOnly() {
        for (GemType t : new GemType[] {GemType.AURATUS, GemType.HERETIC}) {
            Sim s = new Sim();
            s.slots[0] = new Stack(t);
            s.pressQ();
            assertEquals(0, s.onPedestal(t), "not before the next tick");
            s.tick();
            exactlyOnPedestal(s, t, t + ", another plugin cancelled it first");
            assertEquals(0, s.releases, "Q at the pedestal doesn't release a chain (Finale puts it away once the gem is placed)");
        }
    }

    @Test void inAnyListenerOrder() {
        // Bukkit runs Finale (HIGHEST) after the others, but the result mustn't depend on that: every order of the
        // listeners that decide, with and without another plugin cancelling, and with and without a plugin un-cancelling
        // afterwards; Auratus' MONITOR listener always runs last
        for (List<Integer> order : permutations(List.of(0, 1, 2))) for (boolean rogue : new boolean[] {false, true}) for (boolean other : new boolean[] {false, true}) {
            Sim s = new Sim();
            List<Consumer<Event>> all = List.of(s.anotherPlugin, s.soulbound, s.finale);
            s.listeners = new ArrayList<>();
            for (int k : order) if (other || k != 0) s.listeners.add(all.get(k));
            if (rogue) s.listeners.add(s.uncancels);
            s.listeners.add(s.auratusDropped);
            s.slots[0] = new Stack(GemType.AURATUS);
            s.pressQ();
            s.tick();
            exactlyOnPedestal(s, GemType.AURATUS, "order " + order + (rogue ? ", un-cancelled at the end" : "") + (other ? ", cancelled by another plugin" : ""));
        }
    }

    @Test void offHandThrownFromTheInventoryScreen() {
        Sim s = new Sim();
        s.slots[40] = new Stack(GemType.HERETIC);
        s.throwFrom(40);
        s.tick();
        exactlyOnPedestal(s, GemType.HERETIC, "off hand");
    }

    @Test void anySlotAndTheRestOfTheInventoryIsLeftAlone() {
        Sim s = new Sim();
        s.slots[17] = new Stack(GemType.AURATUS);
        Stack sword = new Stack(null);
        s.slots[0] = sword;
        s.throwFrom(17);
        s.tick();
        exactlyOnPedestal(s, GemType.AURATUS, "a storage slot");
        assertSame(sword, s.slots[0], "only the stamped gem is taken");
    }

    @Test void aFullInventory() {
        Sim q = new Sim();
        q.slots[0] = new Stack(GemType.AURATUS);
        q.fillStorage();
        q.pressQ(); // the hand slot it left is free again, so it goes back there
        q.tick();
        exactlyOnPedestal(q, GemType.AURATUS, "full inventory, Q");

        Sim c = new Sim();
        c.fillStorage();
        c.cursor = new Stack(GemType.HERETIC);
        c.dropCursor(); // the server has nowhere to give it back: without the pedestal it would be gone
        c.tick();
        exactlyOnPedestal(c, GemType.HERETIC, "full inventory, dropped from the cursor");
    }

    @Test void spammingQ() {
        Sim s = new Sim();
        s.slots[0] = new Stack(GemType.AURATUS);
        for (int i = 0; i < 6; i++) s.pressQ(); // all before the next tick: each one is given back and dropped again
        s.tick();
        for (int i = 0; i < 6; i++) { s.pressQ(); s.tick(); }
        exactlyOnPedestal(s, GemType.AURATUS, "spammed Q");
        assertEquals(0, s.releases);
    }

    @Test void tooFarKeepsItAndTheReleaseStillWorks() {
        Sim s = new Sim();
        s.near = false;
        s.slots[0] = new Stack(GemType.AURATUS);
        s.pressQ();
        s.tick();
        assertEquals(0, s.onPedestal(GemType.AURATUS));
        assertEquals(1, s.inInventory(GemType.AURATUS), "during the finale, the merge gems only go on their pedestals");
        assertEquals(0, s.releases, "a refused drop: the gem is still in hand, and so is its chain");
    }

    @Test void outsideTheFinaleQDropsIt() {
        Sim s = new Sim();
        s.listeners.remove(s.anotherPlugin); // just this plugin's own rules
        s.dropPhase = false;
        s.slots[0] = new Stack(GemType.AURATUS);
        s.pressQ();
        s.tick();
        assertEquals(0, s.inInventory(GemType.AURATUS));
        assertEquals(1, s.onGround(GemType.AURATUS), "Auratus isn't soulbound any more: one copy, on the ground");
        assertEquals(1, s.releases, "and the chain that was out goes with it");
        assertEquals(0, s.stamped(GemType.AURATUS));
    }

    @Test void theFinaleEndingBeforeTheNextTickLeavesItWithThePlayer() {
        Sim s = new Sim();
        s.slots[0] = new Stack(GemType.HERETIC);
        s.pressQ();
        s.dropPhase = false;
        s.tick();
        assertEquals(0, s.onPedestal(GemType.HERETIC));
        assertEquals(1, s.inInventory(GemType.HERETIC));
    }

    @Test void aGemThatGoesBackHasNoIdLeft() {
        Sim ended = new Sim(); // the finale ended (or was stopped) before the next tick
        ended.slots[0] = new Stack(GemType.AURATUS);
        ended.pressQ();
        assertEquals(1, ended.stamped(GemType.AURATUS), "stamped while it waits");
        ended.dropPhase = false;
        ended.tick();
        assertEquals(1, ended.inInventory(GemType.AURATUS));
        assertEquals(0, ended.stamped(GemType.AURATUS), "finale over: back with the player, id gone");

        Sim far = new Sim(); // walked away from the pedestal: never stamped
        far.near = false;
        far.slots[0] = new Stack(GemType.HERETIC);
        far.pressQ();
        far.tick();
        assertEquals(1, far.inInventory(GemType.HERETIC));
        assertEquals(0, far.stamped(GemType.HERETIC));

        Sim lost = new Sim(); // couldn't take it back: it went somewhere take() doesn't look (another player picked it up, say)
        lost.slots[0] = new Stack(GemType.AURATUS);
        lost.pressQ();
        Stack elsewhere = lost.slots[0];
        lost.slots[0] = null;
        lost.tick();
        assertEquals(0, lost.onPedestal(GemType.AURATUS), "not placed: it isn't here to take");
        assertNotNull(elsewhere.token, "that copy was out of reach this tick");
        lost.slots[5] = elsewhere; // and when it is found later, nothing places it twice
        assertEquals(0, lost.onPedestal(GemType.AURATUS));

        Sim spam = new Sim(); // Q spammed, then the finale ended before the tick
        spam.slots[0] = new Stack(GemType.HERETIC);
        for (int i = 0; i < 5; i++) spam.pressQ();
        spam.dropPhase = false;
        spam.tick();
        assertEquals(1, spam.inInventory(GemType.HERETIC));
        assertEquals(0, spam.stamped(GemType.HERETIC), "one id, and it came off");
    }

    @Test void aWaitingDropIsLeftToItself() {
        Sim s = new Sim();
        s.slots[0] = new Stack(GemType.AURATUS);
        s.pressQ();
        assertTrue(s.drops.isPending(GemType.AURATUS), "the timeout pull skips it");
        s.tick();
        assertFalse(s.drops.isPending(GemType.AURATUS));
    }

    /** The plugin really is wired this way. */
    @Test void theListenersUseIt() throws Exception {
        String finale = Files.readString(Path.of("src/main/java/dev/soulbound/Finale.java"));
        String auratus = Files.readString(Path.of("src/main/java/dev/soulbound/AuratusGem.java"));
        String listener = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        assertTrue(finale.contains("@EventHandler(priority = EventPriority.HIGHEST)\n    public void onDrop(PlayerDropItemEvent e)"));
        assertTrue(finale.contains("drops.onDrop(") && finale.contains("drops.settle("));
        assertFalse(finale.contains("getItemDrop().remove()"), "the gem is never placed straight from the event");
        assertFalse(auratus.contains("public void onRelease("), "the chain release is on F now");
        assertTrue(auratus.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    public void onDropped(PlayerDropItemEvent e)"),
                "a drop the finale claimed (cancelled) never reaches Auratus");
        assertTrue(slice(auratus, "public void onSwapHands(").contains("letGo(p)"), "F with nothing hooked lets go");
        String drop = slice(listener, "public void onDrop(PlayerDropItemEvent e)");
        assertTrue(drop.contains("claimsDrop(") && drop.contains("LooseMythics.droppable("));
        assertTrue(slice(finale, "private void place(GemType type, String who)").contains("return;"), "never placed twice");
    }

    private static String slice(String src, String from) {
        int i = src.indexOf(from);
        assertTrue(i >= 0, from);
        return src.substring(i, Math.min(src.length(), i + 900));
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) return List.of(List.of());
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> p : permutations(rest)) { List<T> l = new ArrayList<>(); l.add(head); l.addAll(p); out.add(l); }
        }
        return out;
    }
}
