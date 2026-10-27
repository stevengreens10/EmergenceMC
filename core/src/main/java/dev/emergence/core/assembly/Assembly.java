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
    private double ambientPressure = 101_325;
    private dev.emergence.core.math.Vec3 ambientWind = dev.emergence.core.math.Vec3.ZERO;
    private CellPredicate solid = (x, y, z) -> false;
    private CellPredicate water = (x, y, z) -> false;
    private CellVector flow = (x, y, z) -> dev.emergence.core.math.Vec3.ZERO;
    private CellDouble waterTop = (x, y, z) -> WATER_TOP;

    /** Tests a grid cell (global cell coordinates). */
    @FunctionalInterface
    public interface CellPredicate {
        boolean test(int x, int y, int z);
    }

    public static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Joins across scales: {ribbon part, insulated wire part} (see {@link #bridge}). */
    private final List<long[]> bridges = new ArrayList<>();

    /**
     * Joins a ribbon's conductor to an insulated wire that touches it in the world but not in this assembly's grid —
     * a miniature wire against a full-size ribbon's face (the two scales' parts lie in separate grids). The conductor
     * is the wire's colour.
     */
    public Assembly bridge(long ribbon, long wire) {
        bridges.add(new long[]{ribbon, wire});
        return this;
    }

    public List<long[]> bridges() {
        return java.util.Collections.unmodifiableList(bridges);
    }

    public Assembly add(Part p) {
        parts.put(p.id(), p);
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++)
                    cellIndex.computeIfAbsent(cellKey(x, y, z), k -> new ArrayList<>(2)).add(p);
        return this;
    }

    /** Takes a part away (a broken or removed part). */
    public Assembly remove(long id) {
        Part p = parts.remove(id);
        if (p == null) return this;
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    List<Part> l = cellIndex.get(cellKey(x, y, z));
                    if (l != null) l.remove(p);
                }
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

    /** Cells occupied by solid surroundings (in Minecraft: world blocks); open pipe ends there are capped. */
    public Assembly solidWhen(CellPredicate pred) {
        this.solid = pred;
        return this;
    }

    public boolean isSolidCell(int x, int y, int z) {
        return solid.test(x, y, z);
    }

    /** Cells of still water around and in the machine (in Minecraft: water blocks, and its own blocks waterlogged). */
    public Assembly waterWhen(CellPredicate pred) {
        this.water = pred;
        return this;
    }

    public boolean isWaterCell(int x, int y, int z) {
        return water.test(x, y, z);
    }

    /** The water's velocity in a cell [m/s]. */
    public interface CellVector {
        dev.emergence.core.math.Vec3 at(int x, int y, int z);
    }

    /** How the water around the machine flows (a stream's current); still everywhere unless given. */
    public Assembly flowWhen(CellVector flow) {
        this.flow = flow;
        return this;
    }

    public dev.emergence.core.math.Vec3 waterVelocity(int x, int y, int z) {
        return flow.at(x, y, z);
    }

    /** How far up its top cell still water stands (a Minecraft source block's surface, 8/9 of the block). */
    public static final double WATER_TOP = 8.0 / 9.0;

    /**
     * The height [m] of the still water's surface over cell (x, z), the water reaching up from cell y; NaN if cell y
     * holds no water.
     */
    public double waterSurface(int x, int y, int z) {
        if (!isWaterCell(x, y, z)) return Double.NaN;
        int top = y;
        while (top - y < 256 && isWaterCell(x, top + 1, z)) top++;
        return (top + waterTop.at(x, top, z)) * Part.CELL;
    }

    /** A value per cell. */
    public interface CellDouble {
        double at(int x, int y, int z);
    }

    /** How far up the top cell of a column of water its surface stands (a stream's falls along its run). */
    public Assembly waterTopWhen(CellDouble top) {
        this.waterTop = top;
        return this;
    }

    /** Pressure of the surrounding air [Pa]. */
    public double ambientPressure() {
        return ambientPressure;
    }

    public Assembly ambientPressure(double p) {
        this.ambientPressure = p;
        return this;
    }

    public double ambientTemperature() {
        return ambientTemperature;
    }

    public Assembly ambientTemperature(double t) {
        this.ambientTemperature = t;
        return this;
    }

    /** The wind blowing past the assembly (world frame, uniform over it) [m/s]. */
    public dev.emergence.core.math.Vec3 ambientWind() {
        return ambientWind;
    }

    public Assembly ambientWind(dev.emergence.core.math.Vec3 w) {
        this.ambientWind = w;
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
