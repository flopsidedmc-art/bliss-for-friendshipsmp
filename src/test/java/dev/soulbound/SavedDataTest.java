package dev.soulbound;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Trust lists and gems kept through a death are saved to disk, so a restart loses neither. */
class SavedDataTest {
    private static String gl() throws Exception { return Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java")); }

    @Test void trustIsSaved() throws Exception {
        String g = gl();
        assertTrue(g.contains("dataFile(\"trust.yml\")"));
        int a = g.indexOf("public boolean addTrust("), r = g.indexOf("public boolean removeTrust(");
        assertTrue(g.substring(a, a + 300).contains("saveTrust()"), "saved when added");
        assertTrue(g.substring(r, r + 300).contains("saveTrust()"), "and when removed");
        assertTrue(g.contains("loadSaved();"), "read back on start");
    }

    @Test void theRespawnGemIsSavedUntilItsBack() throws Exception {
        String g = gl();
        assertTrue(g.contains("pendingRespawnGem.put(victim.getUniqueId(), toKeep.get(0));\n                saveRespawnGems();"), "saved at death");
        int r = g.indexOf("public void onRespawn(PlayerRespawnEvent e) {");
        String respawn = g.substring(r, r + 800);
        assertTrue(respawn.indexOf("if (!p.isOnline()) return;") < respawn.indexOf("pendingRespawnGem.remove("), "kept saved if they leave before it's given");
        assertTrue(g.contains("public void onSavedGemJoin("), "given on their next join if they left");
    }
}
