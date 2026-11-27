package dev.soulbound;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static dev.soulbound.AstraMath.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** Astra's revamp: the controls, the dagger fan and its linger timer, the projection leash, and every way out of spectator. */
class AstraTest {
    @Test void controlRouting() {
        assertEquals(DAGGERS, AstraMath.route(true, false, false, false), "left-click: daggers");
        assertEquals(DAGGERS, AstraMath.route(true, false, true, true), "left-click is daggers even sneaking at a player");
        assertEquals(UNBOUNDED, AstraMath.route(false, true, false, true), "right-click a player: Unbounded");
        assertEquals(VOID, AstraMath.route(false, true, false, false), "right-click anywhere else: Astral Void");
        assertEquals(PROJECTION, AstraMath.route(false, true, true, false), "sneak + right-click: Projection");
        assertEquals(PROJECTION, AstraMath.route(false, true, true, true), "sneak + right-click on a player: still Projection");
        assertEquals(NONE, AstraMath.route(false, false, true, true));
    }

    @Test void theDaggerLingerTimer() {
        long eight = 8_000;
        assertFalse(AstraMath.lingered(10_000, 17_999, eight), "7.999 s after the last throw: still there");
        assertTrue(AstraMath.lingered(10_000, 18_000, eight), "8 s: they fade and the cooldown starts");
        assertFalse(AstraMath.lingered(18_000, 18_000, eight), "each throw restarts it");
    }

    @Test void theFanIsBehindYourShoulders() {
        for (int i = 0; i < AstraMath.DAGGERS; i++) {
            double[] s = AstraMath.fanSlot(i);
            assertTrue(s[2] < 0, "dagger " + i + " is behind you");
            assertTrue(s[1] > 1.3 && s[1] < 1.7, "at shoulder height");
            double[] mirror = AstraMath.fanSlot(AstraMath.DAGGERS - 1 - i);
            assertEquals(-s[0], mirror[0], 1e-9, "symmetric");
        }
        assertTrue(AstraMath.fanSlot(0)[0] < AstraMath.fanSlot(4)[0], "left to right");
        // facing +Z (yaw 0): behind is -Z; facing -X (yaw 90): behind is +X, and the fan turns with you
        Vector south = AstraMath.turned(AstraMath.fanSlot(2), 0f), west = AstraMath.turned(AstraMath.fanSlot(2), 90f);
        assertTrue(south.getZ() < 0 && Math.abs(south.getX()) < 1e-9);
        assertTrue(west.getX() > 0 && Math.abs(west.getZ()) < 1e-9);
        assertEquals(south.length(), west.length(), 1e-9);
    }

    @Test void theProjectionLeash() {
        Vector body = new Vector(0, 64, 0);
        assertNull(AstraMath.clamp(body, new Vector(30, 70, 30), 48), "within 48: left alone");
        Vector back = AstraMath.clamp(body, new Vector(60, 64, 0), 48);
        assertNotNull(back, "past 48: pulled back");
        assertEquals(47.5, back.distance(body), 1e-9, "onto the edge, just inside");
        assertEquals(0, back.clone().subtract(body).normalize().subtract(new Vector(1, 0, 0)).length(), 1e-9, "along the way you went");
    }

    @Test void everyEndRestoresTheGameModeOnce() {
        for (SpectatorSessions.Kind kind : SpectatorSessions.Kind.values()) for (SpectatorSessions.End why : SpectatorSessions.End.values()) {
            SpectatorSessions s = new SpectatorSessions();
            UUID id = UUID.randomUUID();
            Location home = new Location(null, 10, 64, 10, 90f, 10f);
            assertTrue(s.start(id, kind, GameMode.SURVIVAL, home, 0));
            assertFalse(s.start(id, kind, GameMode.ADVENTURE, home, 0), "no stacking: the first game mode is the one kept");
            SpectatorSessions.Session out = s.end(id, why);
            assertNotNull(out, kind + " " + why);
            assertEquals(GameMode.SURVIVAL, out.mode(), kind + " " + why + ": the old game mode comes back");
            assertNull(s.end(id, why), "only once");
            assertFalse(s.any(id), "nothing left");
            Location to = SpectatorSessions.where(out, why, new Location(null, 1, 64, 1), new Location(null, 2, 64, 2));
            if (why == SpectatorSessions.End.DEATH) assertNull(to, "death: they respawn");
            else if (why == SpectatorSessions.End.EVENT) assertNull(to, "pulled into a Pantheon event: they stay where the event put them");
            else if (kind == SpectatorSessions.Kind.PROJECTION) {
                assertEquals(home, to, "projection: always back to the body's exact spot");
                assertEquals(90f, to.getYaw(), "and facing");
            } else assertNotNull(to);
        }
        SpectatorSessions s = new SpectatorSessions();
        assertFalse(s.start(UUID.randomUUID(), SpectatorSessions.Kind.PROJECTION, GameMode.SPECTATOR, new Location(null, 0, 0, 0), 0),
                "never from spectator: that's the mode that would come back");
    }

    @Test void unboundedComesOutBehindThemOrWhereTheyWere() {
        SpectatorSessions.Session u = new SpectatorSessions.Session(SpectatorSessions.Kind.UNBOUNDED, GameMode.SURVIVAL,
                new Location(null, 0, 64, 0), 0);
        Location behind = new Location(null, 5, 64, 4), last = new Location(null, 5, 64, 5);
        assertEquals(behind, SpectatorSessions.where(u, SpectatorSessions.End.TIME_UP, behind, last));
        assertEquals(behind, SpectatorSessions.where(u, SpectatorSessions.End.SNEAK, behind, last));
        assertEquals(last, SpectatorSessions.where(u, SpectatorSessions.End.TARGET_GONE, null, last), "they died or left: where they were");
        assertEquals(u.home(), SpectatorSessions.where(u, SpectatorSessions.End.HOST_EVENT, behind, last),
                "their host is in a Pantheon event: back where they started, never into the arena");
        assertEquals(u.home(), SpectatorSessions.where(u, SpectatorSessions.End.DISABLE, null, null), "nothing known: where you started");
    }

    @Test void everyWayOutIsWired() throws Exception {
        String a = Files.readString(Path.of("src/main/java/dev/soulbound/AstralSpectate.java"));
        for (String path : new String[] {"End.LOGOUT", "End.DEATH", "End.WORLD_CHANGE", "End.DISABLE", "End.SNEAK", "End.TIME_UP",
                "End.BODY_HIT", "End.TARGET_GONE", "End.MODE_CHANGED"}) assertTrue(a.contains("SpectatorSessions." + path), path);
        assertTrue(a.contains("p.setGameMode(s.mode())"), "end() puts the old game mode back");
        assertTrue(a.contains("if (mode == GameMode.SPECTATOR) mode = GameMode.SURVIVAL;"), "a restore record never leaves anyone in spectator");
        String plugin = Files.readString(Path.of("src/main/java/dev/soulbound/SoulboundGemsPlugin.java"));
        int d = plugin.indexOf("public void onDisable()");
        assertTrue(plugin.substring(d, d + 200).contains("astral.shutdown()"), "plugin disable and server stop restore everyone");
        String gl = Files.readString(Path.of("src/main/java/dev/soulbound/GemListener.java"));
        int i = gl.indexOf("private void useDimensionalDrift(");
        String drift = gl.substring(i, gl.indexOf("\n    }\n", i));
        assertFalse(drift.contains("tier >= 2"), "the ghost horse at both tiers");
    }

    @Test void theConfig() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(8, c.getInt("astra.daggers.linger-seconds"));
        assertEquals(10, c.getInt("astra.projection.seconds"));
        assertEquals(48, c.getInt("astra.projection.max-distance"));
        assertEquals(6, c.getInt("astra.projection.tag-lock-seconds"));
        assertEquals(3, c.getInt("astra.projection.max-tags"));
        assertTrue(c.getBoolean("astra.projection.body-takes-hits"));
        assertEquals(4, c.getInt("astra.unbounded.seconds"));
        assertEquals(8, c.getInt("astra.phasing.vanish-ticks"));
        assertEquals("ENTITY_VEX_CHARGE", c.getString("sounds.astra.tag-spook.event"));
        assertEquals("AMBIENT_CAVE", c.getString("sounds.astra.tag-spook.layer.event"));
        assertEquals("BLOCK_AMETHYST_BLOCK_CHIME", c.getString("sounds.astra.daggers-summon.event"));
        assertEquals("ENTITY_ENDERMAN_TELEPORT", c.getString("sounds.astra.phasing.event"));
        for (String k : new String[] {"projection-end", "unbounded-enter", "unbounded-exit", "unbounded-whisper"})
            assertNotNull(c.getString("sounds.astra." + k + ".event"), k);
    }
}
