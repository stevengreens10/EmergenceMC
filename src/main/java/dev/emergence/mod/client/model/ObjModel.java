package dev.emergence.mod.client.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Wavefront OBJ reader for the part models authored in Blender (tools/blender/build_parts.py).
 * Geometry is converted back to the authoring frame (Blender: part axis +Z, lead axis +X) from the
 * exporter's Y-up frame. Polygons are fanned into triangles and stored as quads with a repeated last
 * vertex, which is what Minecraft's quad-based vertex formats expect.
 */
public final class ObjModel {
    /** One named object of the model with a single material. Vertex layout: x y z nx ny nz u v (×4 per quad). */
    public record Piece(String name, String material, float[] quads) {
        public int quadCount() {
            return quads.length / 32;
        }
    }

    public final List<Piece> pieces;

    private ObjModel(List<Piece> pieces) {
        this.pieces = List.copyOf(pieces);
    }

    public static ObjModel read(Reader in) throws IOException {
        List<float[]> v = new ArrayList<>(), vt = new ArrayList<>(), vn = new ArrayList<>();
        List<Piece> pieces = new ArrayList<>();
        String name = "default", material = "metal_smooth";
        FloatList quads = new FloatList();
        BufferedReader r = new BufferedReader(in);
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                // OBJ (exported forward −Z, up Y) → authoring frame: (x, −z, y)
                case "v" -> v.add(new float[]{f(t[1]), -f(t[3]), f(t[2])});
                case "vn" -> vn.add(new float[]{f(t[1]), -f(t[3]), f(t[2])});
                case "vt" -> vt.add(new float[]{f(t[1]), f(t[2])});
                case "o", "g" -> {
                    flush(pieces, name, material, quads);
                    quads = new FloatList();
                    name = t.length > 1 ? t[1] : "default";
                }
                case "usemtl" -> {
                    if (quads.size() > 0) {
                        flush(pieces, name, material, quads);
                        quads = new FloatList();
                    }
                    material = t[1];
                }
                case "f" -> {
                    int n = t.length - 1;
                    float[][] corner = new float[n][];
                    for (int k = 0; k < n; k++) corner[k] = vertex(t[k + 1], v, vt, vn);
                    // planar-projected UVs may sit anywhere on the 1-cell texture lattice; atlas sprites
                    // cannot wrap, so shift each face into the [0, 1] tile it mostly covers
                    float mu = Float.MAX_VALUE, mv = Float.MAX_VALUE;
                    for (float[] c : corner) {
                        mu = Math.min(mu, c[6]);
                        mv = Math.min(mv, c[7]);
                    }
                    float su = (float) Math.floor(mu + 1e-4), sv = (float) Math.floor(mv + 1e-4);
                    for (float[] c : corner) {
                        c[6] = Math.max(0, Math.min(1, c[6] - su));
                        c[7] = Math.max(0, Math.min(1, c[7] - sv));
                    }
                    for (int k = 1; k + 1 < n; k++) {
                        quads.add(corner[0]);
                        quads.add(corner[k]);
                        quads.add(corner[k + 1]);
                        quads.add(corner[k + 1]);
                    }
                }
                default -> {
                }
            }
        }
        flush(pieces, name, material, quads);
        return new ObjModel(pieces);
    }

    private static void flush(List<Piece> pieces, String name, String material, FloatList quads) {
        if (quads.size() > 0) pieces.add(new Piece(name, material, quads.toArray()));
    }

    private static float[] vertex(String ref, List<float[]> v, List<float[]> vt, List<float[]> vn) {
        String[] i = ref.split("/");
        float[] p = v.get(index(i[0], v.size()));
        float[] uv = i.length > 1 && !i[1].isEmpty() ? vt.get(index(i[1], vt.size())) : new float[]{0, 0};
        float[] n = i.length > 2 && !i[2].isEmpty() ? vn.get(index(i[2], vn.size())) : new float[]{0, 0, 1};
        return new float[]{p[0], p[1], p[2], n[0], n[1], n[2], uv[0], uv[1]};
    }

    private static int index(String s, int size) {
        int k = Integer.parseInt(s);
        return k < 0 ? size + k : k - 1;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    private static final class FloatList {
        private float[] a = new float[256];
        private int n;

        void add(float[] values) {
            if (n + values.length > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + values.length));
            System.arraycopy(values, 0, a, n, values.length);
            n += values.length;
        }

        int size() {
            return n;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
