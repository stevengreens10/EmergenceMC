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
 * parts in that layer rest on (and are anchored to) the world. Each part must fit inside one 16³ block.
 */
public final class Exhibit {

    /** A part to place. */
    public record PartDef(int index, PartKind kind, String material, int x0, int y0, int z0, int x1, int y1, int z1,
                          Dir axis, Map<String, Double> params) {}

    /** Initial dynamic state of a part: temperature [K] (NaN = ambient), body ω [rad/s], capacitor V. */
    public record Initial(double temperature, double omega, double capacitorVoltage) {}

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
    /** Width of the plot in blocks along x. */
    public int widthBlocks = 1;
    /** Suggested viewpoint relative to the plot block's north-west corner: eye height, distance south, pitch. */
    public double viewHeight = 0.45, viewDistance = 1.25, viewPitch = 30;

    public Exhibit view(double height, double distance, double pitch) {
        this.viewHeight = height;
        this.viewDistance = distance;
        this.viewPitch = pitch;
        return this;
    }

    public Exhibit(String id, String title, List<String> sign, String description) {
        this.id = id;
        this.title = title;
        this.sign = sign;
        this.description = description;
    }

    public int add(PartKind k, String material, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        int idx = parts.size();
        if (Math.floorDiv(x0, 16) != Math.floorDiv(x1, 16) || Math.floorDiv(y0, 16) != Math.floorDiv(y1, 16)
                || Math.floorDiv(z0, 16) != Math.floorDiv(z1, 16)) {
            throw new IllegalArgumentException(id + ": part " + idx + " crosses a block boundary");
        }
        parts.add(new PartDef(idx, k, material, x0, y0, z0, x1, y1, z1, axis, m));
        return idx;
    }

    public int add(PartKind k, String material, int x, int y, int z, Dir axis, Object... kv) {
        return add(k, material, x, y, z, x, y, z, axis, kv);
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
