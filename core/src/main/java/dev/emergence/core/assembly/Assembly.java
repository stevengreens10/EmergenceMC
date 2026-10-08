package dev.emergence.core.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A set of placed parts forming (part of) a construction, with a spatial index over cells.
 * The {@code anchored} predicate tells which parts are fixed to the world (in Minecraft: parts touching
 * a solid world block); it defines the static reference body.
 */
public final class Assembly {
    private final Map<Long, Part> parts = new LinkedHashMap<>();
    private final Map<Long, List<Part>> cellIndex = new HashMap<>();
    private Predicate<Part> anchored = p -> false;
    private double ambientTemperature = 293.15;

    public static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    public Assembly add(Part p) {
        parts.put(p.id(), p);
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++)
                    cellIndex.computeIfAbsent(cellKey(x, y, z), k -> new ArrayList<>(2)).add(p);
        return this;
    }

    public Collection<Part> parts() {
        return parts.values();
    }

    public Part get(long id) {
        return parts.get(id);
    }

    public List<Part> at(int x, int y, int z) {
        return cellIndex.getOrDefault(cellKey(x, y, z), List.of());
    }

    public Assembly anchoredWhen(Predicate<Part> pred) {
        this.anchored = pred;
        return this;
    }

    public boolean isAnchored(Part p) {
        return anchored.test(p);
    }

    public double ambientTemperature() {
        return ambientTemperature;
    }

    public Assembly ambientTemperature(double t) {
        this.ambientTemperature = t;
        return this;
    }

    /** All parts touching or overlapping {@code p} (excluding itself). */
    public Set<Part> neighbors(Part p) {
        Set<Part> out = new HashSet<>();
        for (int x = p.minX() - 1; x <= p.maxX() + 1; x++)
            for (int y = p.minY() - 1; y <= p.maxY() + 1; y++)
                for (int z = p.minZ() - 1; z <= p.maxZ() + 1; z++) {
                    for (Part q : at(x, y, z)) if (q != p && p.touches(q)) out.add(q);
                }
        return out;
    }

    /** Parts whose cells lie immediately beyond the face {@code d} of cell (x,y,z). */
    public List<Part> across(int x, int y, int z, Dir d) {
        return at(x + d.dx, y + d.dy, z + d.dz);
    }
}
