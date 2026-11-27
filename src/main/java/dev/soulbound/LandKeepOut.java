package dev.soulbound;

import java.util.ArrayList;
import java.util.List;

/**
 * Where Crisp and Vitality Vortex never change a block (LandTest): boxes (land.protected, made with /bliss landprotect)
 * and circles, every height (a radius around each world's spawn, the Gold Gem ritual site, the pedestal and every
 * Pantheon arena point). Built from the config and the other systems by LandWorks, which refreshes it every few seconds.
 */
final class LandKeepOut {
    /** A box, both corners included. */
    record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Box of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        boolean contains(String w, int x, int y, int z) {
            return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** A circle around a point, every height (block centres measured). */
    record Circle(String world, double x, double z, double radius) {
        boolean contains(String w, int bx, int bz) {
            double dx = bx + 0.5 - x, dz = bz + 0.5 - z;
            return world.equals(w) && dx * dx + dz * dz <= radius * radius;
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final List<Circle> circles = new ArrayList<>();

    LandKeepOut box(Box b) { boxes.add(b); return this; }

    LandKeepOut circle(String world, double x, double z, double radius) {
        if (world != null && radius > 0) circles.add(new Circle(world, x, z, radius));
        return this;
    }

    boolean contains(String world, int x, int y, int z) {
        for (Box b : boxes) if (b.contains(world, x, y, z)) return true;
        for (Circle c : circles) if (c.contains(world, x, z)) return true;
        return false;
    }

    int size() { return boxes.size() + circles.size(); }
}
