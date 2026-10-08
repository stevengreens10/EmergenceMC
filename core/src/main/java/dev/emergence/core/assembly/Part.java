package dev.emergence.core.assembly;

import dev.emergence.core.materials.Material;
import dev.emergence.core.math.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A placed primitive component: kind, material, an inclusive box of grid cells, an orientation and
 * physical parameters (SI units). Immutable apart from {@link #setSwitchClosed} style operational
 * state, which is held in the mutable {@code state} map.
 *
 * <h2>Grid</h2>
 * Cells are 1/16 m cubes in a global integer lattice ({@link #CELL}); a Minecraft block is 16³ cells.
 * The grid fixes topology (what touches what); physical dimensions are parametric and may be smaller
 * than the cells they occupy (a 0.3 mm wire, a 20 mm magnet).
 */
public final class Part {
    public static final int CELLS_PER_BLOCK = 16;
    public static final double CELL = 1.0 / CELLS_PER_BLOCK;

    private final long id;
    private final PartKind kind;
    private final Material material;
    private final int x0, y0, z0, x1, y1, z1;
    private final Dir axis;
    private final Map<String, Double> params;
    private final Map<String, Double> state = new HashMap<>();

    public Part(long id, PartKind kind, Material material, int x0, int y0, int z0, int x1, int y1, int z1,
                Dir axis, Map<String, Double> params) {
        this.id = id;
        this.kind = kind;
        this.material = material;
        this.x0 = Math.min(x0, x1);
        this.y0 = Math.min(y0, y1);
        this.z0 = Math.min(z0, z1);
        this.x1 = Math.max(x0, x1);
        this.y1 = Math.max(y0, y1);
        this.z1 = Math.max(z0, z1);
        this.axis = axis;
        this.params = Collections.unmodifiableMap(new HashMap<>(params));
    }

    public long id() { return id; }
    public PartKind kind() { return kind; }
    public Material material() { return material; }
    public Dir axis() { return axis; }
    public Map<String, Double> params() { return params; }
    public int minX() { return x0; }
    public int minY() { return y0; }
    public int minZ() { return z0; }
    public int maxX() { return x1; }
    public int maxY() { return y1; }
    public int maxZ() { return z1; }

    public double param(String key, double fallback) {
        Double v = params.get(key);
        return v == null ? fallback : v;
    }

    public double stateValue(String key, double fallback) {
        Double v = state.get(key);
        return v == null ? fallback : v;
    }

    public void setState(String key, double v) {
        state.put(key, v);
    }

    public Map<String, Double> state() {
        return state;
    }

    public boolean switchClosed() {
        return stateValue("closed", 0) != 0;
    }

    public void setSwitchClosed(boolean c) {
        state.put("closed", c ? 1.0 : 0.0);
    }

    public boolean containsCell(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    /** Number of cells along grid axis a (0=x,1=y,2=z). */
    public int extent(int a) {
        return a == 0 ? x1 - x0 + 1 : a == 1 ? y1 - y0 + 1 : z1 - z0 + 1;
    }

    /** Physical size of the occupied cell box along axis a [m]. */
    public double boxSize(int a) {
        return extent(a) * CELL;
    }

    /** World-space centre of the occupied cell box [m]. */
    public Vec3 center() {
        return new Vec3((x0 + x1 + 1) * 0.5 * CELL, (y0 + y1 + 1) * 0.5 * CELL, (z0 + z1 + 1) * 0.5 * CELL);
    }

    public static Vec3 cellCenter(int x, int y, int z) {
        return new Vec3((x + 0.5) * CELL, (y + 0.5) * CELL, (z + 0.5) * CELL);
    }

    /** True if the two parts' cell boxes overlap. */
    public boolean overlaps(Part o) {
        return x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1 && z0 <= o.z1 && o.z0 <= z1;
    }

    /** True if the boxes share a face (touch without overlapping) or overlap. */
    public boolean touches(Part o) {
        int gx = Math.max(x0 - o.x1, o.x0 - x1) - 1;
        int gy = Math.max(y0 - o.y1, o.y0 - y1) - 1;
        int gz = Math.max(z0 - o.z1, o.z0 - z1) - 1;
        // gaps: −1 or less = overlapping on that axis; 0 = adjacent
        int adjacentAxes = 0;
        for (int g : new int[]{gx, gy, gz}) {
            if (g > 0) return false;
            if (g == 0) adjacentAxes++;
        }
        return adjacentAxes <= 1;
    }

    @Override
    public String toString() {
        return kind + "#" + id + "[" + material.id() + " " + x0 + "," + y0 + "," + z0 + ".." + x1 + "," + y1 + "," + z1 + " " + axis + "]";
    }
}
