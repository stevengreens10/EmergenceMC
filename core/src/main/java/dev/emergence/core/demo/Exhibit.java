package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A showcase installation for the demo world, defined in platform-independent grid cells so the same
 * definition is both validated by core physics tests and built into Minecraft by the demo builder.
 * <p>
 * Coordinates are cells relative to the exhibit origin; y = 0 is the first cell above the ground, and
 * parts in that layer rest on (and are anchored to) the world. Exhibits are built at block scale — every
 * part is one cell, built as one block (a 6.25 cm cell of a scale model) — so a part spanning several
 * cells is laid down as a row of single-cell parts ({@link #fill}).
 */
public final class Exhibit {

    /** A part to place. */
    public record PartDef(int index, PartKind kind, String material, int x0, int y0, int z0, int x1, int y1, int z1,
                          Dir axis, Map<String, Double> params) {}

    /** Initial dynamic state of a part: temperature [K] (NaN = ambient), body ω [rad/s], capacitor V, body angle [rad]. */
    public record Initial(double temperature, double omega, double capacitorVoltage, double angle) {
        public Initial(double temperature, double omega, double capacitorVoltage) {
            this(temperature, omega, capacitorVoltage, 0);
        }
    }

    public final String id;
    public final String title;
    /** Up to four short sign lines. */
    public final List<String> sign;
    /** Longer explanation shown to the player. */
    public final String description;
    public final List<PartDef> parts = new ArrayList<>();
    public final Map<Integer, Initial> initial = new LinkedHashMap<>();
    /** Named parts of interest (for instruments and tests). */
    public final Map<String, Integer> named = new LinkedHashMap<>();
    /**
     * Miniature exhibits are built at true scale inside one block (16³ cells of 1/16 block, placed with
     * the precision tweezers in game); their parts may span several cells ({@link #box}).
     */
    public boolean miniature;

    /** Suggested camera position (blocks from the plot origin; the camera looks north) and pitch [°]. */
    public double viewX = 8, viewHeight = 6, viewZ = 18, viewPitch = 25;

    public Exhibit view(double x, double height, double z, double pitch) {
        this.viewX = x;
        this.viewHeight = height;
        this.viewZ = z;
        this.viewPitch = pitch;
        return this;
    }

    public Exhibit(String id, String title, List<String> sign, String description) {
        this.id = id;
        this.title = title;
        this.sign = sign;
        this.description = description;
    }

    /** Adds a single-cell part; returns its index. */
    public int add(PartKind k, String material, int x, int y, int z, Dir axis, Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        int idx = parts.size();
        parts.add(new PartDef(idx, k, material, x, y, z, x, y, z, axis, m));
        return idx;
    }

    /** Adds one part spanning a box of cells (miniature exhibits only; the box must lie inside one block). */
    public int box(PartKind k, String material, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        if (!miniature) throw new IllegalStateException(id + ": multi-cell parts belong in miniature exhibits");
        if (Math.min(x0, Math.min(y0, z0)) < 0 || Math.max(x1, Math.max(y1, z1)) > 15) {
            throw new IllegalArgumentException(id + ": part outside the 16³ block");
        }
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        int idx = parts.size();
        parts.add(new PartDef(idx, k, material, x0, y0, z0, x1, y1, z1, axis, m));
        return idx;
    }

    /** Fills a box of cells with one single-cell part each (frames, shafts, disks); returns the first index. */
    public int fill(PartKind k, String material, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        int first = parts.size();
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) add(k, material, x, y, z, axis, kv);
        return first;
    }

    /** Adds single-cell parts at the listed cells. */
    public void path(PartKind k, String material, int[][] cells, Object... kv) {
        for (int[] c : cells) add(k, material, c[0], c[1], c[2], Dir.UP, kv);
    }

    public Exhibit name(String key, int partIndex) {
        named.put(key, partIndex);
        return this;
    }

    public Exhibit init(int partIndex, Initial st) {
        initial.put(partIndex, st);
        return this;
    }
}
