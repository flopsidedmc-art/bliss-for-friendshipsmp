package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** /bliss menu and /bliss config layouts: settings centred on slot 22, and no two buttons on one slot. */
class MenuLayoutTest {
    @Test void settingsAreCentredOnTheMiddleSlot() {
        assertArrayEquals(new int[] {20, 22, 24}, slots(3));
        assertArrayEquals(new int[] {19, 21, 23, 25}, slots(4));
        assertArrayEquals(new int[] {22}, slots(1));
        for (int n = 1; n <= 5; n++) {
            int[] s = slots(n);
            assertEquals(44, s[0] + s[n - 1], n + " settings are centred on 22");
            for (int slot : s) assertTrue(slot >= 18 && slot <= 26, "row 2 only: " + slot);
        }
    }

    /** The click handler turns a slot back into a setting the same way. */
    @Test void clickedSlotMapsBackToItsSetting() {
        for (int n = 1; n <= 5; n++) {
            int first = ConfigGUI.settingSlot(0, n);
            for (int i = 0; i < n; i++) {
                int slot = ConfigGUI.settingSlot(i, n);
                assertEquals(0, (slot - first) % 2);
                assertEquals(i, (slot - first) / 2);
            }
        }
    }

    @Test void menuButtonsNeverShareASlot() throws Exception {
        Set<Integer> used = new HashSet<>();
        for (String f : new String[] {"T1_SLOTS", "T2_SLOTS"}) for (int s : ints(MenuGUI.class, f)) assertTrue(used.add(s), "slot " + s);
        for (int s : new int[] {4, 13, 22, 31, 38, 40, 42}) assertTrue(used.add(s), "slot " + s); // title, labels, mythics
        for (String f : new String[] {"SLOT_UPGRADE", "SLOT_PRISTINE", "SLOT_ENERGY", "SLOT_REPAIR", "SLOT_TRADER"}) {
            int s = num(MenuGUI.class, f);
            assertTrue(s >= 45 && s <= 53, f + " is on the bottom row");
            assertTrue(used.add(s), "slot " + s);
        }
        Set<Integer> hub = new HashSet<>();
        for (String f : new String[] {"SLOTS_A", "SLOTS_B", "SLOTS_M"}) for (int s : ints(ConfigGUI.class, f)) assertTrue(hub.add(s), "slot " + s);
        for (int s : new int[] {4, 22, 40}) assertTrue(hub.add(s), "hub slot " + s); // title and labels
    }

    private static int[] slots(int n) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = ConfigGUI.settingSlot(i, n);
        return s;
    }

    private static int[] ints(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return (int[]) f.get(null);
    }

    private static int num(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
