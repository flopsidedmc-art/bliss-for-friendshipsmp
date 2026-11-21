package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** enforceProgression walks inventories whose empty slots are null. */
class ProgressionInventoryTest {
    @Test void emptySlotsAreSkippedNotFatal() {
        String[] contents = {"sword", null, null, "apple", null};
        String[] armor = {null, "chestplate", null, null};
        String[] offhand = {null};
        List<String> all = assertDoesNotThrow(() -> ProgressionManager.present(contents, armor, offhand));
        assertEquals(List.of("sword", "apple", "chestplate"), all);
        assertEquals(List.of(), ProgressionManager.present(new String[41], null));
    }

    /** List.of(array) throws on a null element: never feed it an inventory's contents. */
    @Test void noListOfOverInventoryArrays() throws Exception {
        Pattern bad = Pattern.compile("List\\.of\\([^;]*get(Storage|Armor|Extra)?Contents\\(\\)");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
                assertFalse(bad.matcher(Files.readString(f)).find(), f + " builds a List.of from inventory contents (null slots throw)");
        }
    }
}
