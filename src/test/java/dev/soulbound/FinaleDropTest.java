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
        final Consumer<Event> auratusRelease = e -> { // AuratusGem.onRelease, LOWEST
            if (e.item.gem != GemType.AURATUS || !chainOut) return;
            if (dropPhase && PedestalDrop.owns(facts(e.item))) return;
            e.cancelled = true;
            chainOut = false;
            releases++;
        };
        final Consumer<Event> soulbound = e -> { // GemListener.onDrop, NORMAL
            if (e.item.gem == null) return;
            if (dropPhase && PedestalDrop.owns(facts(e.item))) return;
            e.cancelled = true;
        };
        final Consumer<Event> finale = e -> { // Finale.onDrop, HIGHEST
            boolean couldNotReturn = firstFreeStorage() < 0;
            GemType type = facts(e.item).mergeGem() ? e.item.gem : null;
            PedestalDrop.Verdict v = drops.onDrop(type, facts(e.item), () -> e.cancelled = true,
                    () -> e.item.token = UUID.randomUUID().toString(), couldNotReturn);
            if (v == PedestalDrop.Verdict.CLAIMED) nextTick.add(() -> {
                if (drops.settle(type, dropPhase, placed(type), this::take)) pedestal.merge(type, 1, Integer::sum);
            });
        };
        final Consumer<Event> uncancels = e -> e.cancelled = false; // a badly behaved plugin after everyone

        List<Consumer<Event>> listeners = new ArrayList<>(List.of(anotherPlugin, auratusRelease, soulbound, finale));

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
        // the old Finale.onDrop: no priority, didn't look at cancelled, removed the drop entity and placed
        s.listeners = new ArrayList<>(List.of(s.soulbound, e -> { if (s.facts(e.item).mergeGem()) s.pedestal.merge(e.item.gem, 1, Integer::sum); }));
        s.dropPhase = false; // so the soulbound rule acts the old way, as if Finale didn't claim anything
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
            assertEquals(0, s.releases, "the Q release stayed out of it");
        }
    }

    @Test void inAnyListenerOrder() {
        // Bukkit runs Finale (HIGHEST) last, but the result mustn't depend on that: all 24 orders of the four listeners,
        // with and without another plugin cancelling, and with and without a plugin un-cancelling afterwards
        for (List<Integer> order : permutations(List.of(0, 1, 2, 3))) for (boolean rogue : new boolean[] {false, true}) for (boolean other : new boolean[] {false, true}) {
            Sim s = new Sim();
            List<Consumer<Event>> all = List.of(s.anotherPlugin, s.auratusRelease, s.soulbound, s.finale);
            s.listeners = new ArrayList<>();
            for (int k : order) if (other || k != 0) s.listeners.add(all.get(k));
            if (rogue) s.listeners.add(s.uncancels);
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
        assertEquals(1, s.inInventory(GemType.AURATUS));
        assertEquals(1, s.releases, "away from the pedestal, Q still lets go of the chain");
    }

    @Test void outsideTheFinale() {
        Sim s = new Sim();
        s.dropPhase = false;
        s.slots[0] = new Stack(GemType.AURATUS);
        s.pressQ();
        s.tick();
        assertEquals(1, s.inInventory(GemType.AURATUS), "gems can't be dropped");
        assertEquals(0, s.onGround(GemType.AURATUS));
        assertEquals(1, s.releases, "Q releases the chain");
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
        assertTrue(slice(auratus, "public void onRelease(").contains("claimsDrop("));
        assertTrue(slice(auratus, "public void onRelease(").contains("isAuratus(dropped)"), "Q has already emptied the hand");
        assertTrue(slice(listener, "public void onDrop(PlayerDropItemEvent e)").contains("claimsDrop("));
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
