package dev.emergence.core.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How display panels make screens: panels side by side in one plane, facing the same way, are one screen, and one
 * controller's 128 × 64 image is spread across it — letterboxed at 2:1 in 92 % of it, each panel showing its tile.
 * Used by the compiler for the panels a controller in the same machine touches, and by the game for a screen driven
 * across scales (a miniature machine's controller against a full-size screen).
 */
public final class DisplayTiles {
    private DisplayTiles() {
    }

    /** The panels grouped into screens. */
    public static List<List<Part>> screens(List<Part> panels) {
        int n = panels.size();
        int[] group = new int[n];
        for (int i = 0; i < n; i++) group[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Part a = panels.get(i), b = panels.get(j);
                if (a.axis() != b.axis() || !a.touches(b) || a.overlaps(b)) continue;
                int ax = a.axis().axis();
                int ca = ax == 0 ? a.minX() : ax == 1 ? a.minY() : a.minZ(), cb = ax == 0 ? b.minX() : ax == 1 ? b.minY() : b.minZ();
                if (ca != cb) continue;
                int gi = root(group, i), gj = root(group, j);
                if (gi != gj) group[Math.max(gi, gj)] = Math.min(gi, gj);
            }
        }
        Map<Integer, List<Part>> screens = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) screens.computeIfAbsent(root(group, i), k -> new ArrayList<>()).add(panels.get(i));
        return new ArrayList<>(screens.values());
    }

    /**
     * Each panel's share of its screen's image, by part id: {u at its left edge, v at its top, u at its right, v at
     * its bottom} (u across, v down, 0..1 over the image). The screen's cells are seen as its viewer sees them: up is
     * the world's up, or north for a screen lying flat.
     */
    public static Map<Long, float[]> tiles(List<Part> screen) {
        Map<Long, float[]> out = new HashMap<>();
        if (screen.isEmpty()) return out;
        Dir ax = screen.get(0).axis();
        int[] up = ax.axis() == 1 ? new int[]{0, 0, -1} : new int[]{0, 1, 0};
        int[] z = {ax.dx, ax.dy, ax.dz};
        int[] right = {up[1] * z[2] - up[2] * z[1], up[2] * z[0] - up[0] * z[2], up[0] * z[1] - up[1] * z[0]};
        int c0 = Integer.MAX_VALUE, c1 = Integer.MIN_VALUE, r0 = Integer.MAX_VALUE, r1 = Integer.MIN_VALUE;
        for (Part d : screen) {
            for (int[] cell : cellsOf(d)) {
                int col = dot(cell, right), row = dot(cell, up);
                c0 = Math.min(c0, col);
                c1 = Math.max(c1, col);
                r0 = Math.min(r0, row);
                r1 = Math.max(r1, row);
            }
        }
        double w = c1 - c0 + 1, h = r1 - r0 + 1;
        double iw = Math.min(0.92 * w, 2 * 0.92 * h), ih = iw / 2;
        double ox = c0 + (w - iw) / 2, oyTop = r1 + 1 - (h - ih) / 2; // the image's left edge and top edge
        for (Part d : screen) {
            // this panel's face as a rectangle of the screen: [col, col + cols) × [row, row + rows)
            int pc0 = Integer.MAX_VALUE, pc1 = Integer.MIN_VALUE, pr0 = Integer.MAX_VALUE, pr1 = Integer.MIN_VALUE;
            for (int[] cell : cellsOf(d)) {
                int col = dot(cell, right), row = dot(cell, up);
                pc0 = Math.min(pc0, col);
                pc1 = Math.max(pc1, col);
                pr0 = Math.min(pr0, row);
                pr1 = Math.max(pr1, row);
            }
            float uL = (float) ((pc0 - ox) / iw), uR = (float) ((pc1 + 1 - ox) / iw);
            float vT = (float) ((oyTop - (pr1 + 1)) / ih), vB = (float) ((oyTop - pr0) / ih);
            out.put(d.id(), new float[]{uL, vT, uR, vB});
        }
        return out;
    }

    private static int root(int[] g, int i) {
        while (g[i] != i) i = g[i] = g[g[i]];
        return i;
    }

    private static int dot(int[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static List<int[]> cellsOf(Part p) {
        List<int[]> out = new ArrayList<>();
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) out.add(new int[]{x, y, z});
        return out;
    }
}
