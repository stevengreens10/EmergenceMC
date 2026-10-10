package dev.emergence.core.optics;

import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Light transport through an {@link OpticalScene} by ray tubes. A source's light is divided into triangular tubes of
 * equal power (a point source's emission pattern mapped onto the unit square so equal areas carry equal power; a
 * parallel beam's cross-section). Each corner ray is traced through every reflection and refraction, splitting at
 * each glass surface into a reflected and a transmitted branch with Fresnel's weights (total internal reflection
 * past the critical angle; mirrors reflect their reflectance and absorb the rest); every branch path is labelled by
 * the sequence of surfaces and choices it met. Where the three corners of a tube follow the same path the tube is a
 * beam of light carried whole: where it lands on a sensitive window (a photodiode's junction), the power is the
 * tube's power times the window's share of the tube's footprint — the irradiance is uniform across a small tube, and
 * a tube squeezed by focusing delivers its power into a small spot. Tubes whose corners part company (straddling an
 * edge) are split in four, as are tubes too coarse to resolve a mirror or lens they look at; at the finest level the
 * corners share the tube's power. Every watt is accounted for: absorbed as heat, delivered to windows, scattered by
 * matt surfaces, escaped, or cut off below 10⁻⁴ of a tube's power.
 */
public final class TubeTracer {
    /** Where the light went, per watt emitted (point source) or per W/m² (parallel beam: per unit irradiance). */
    public static final class Result {
        /** Absorbed in each part (heat) [W]. */
        public final Map<Long, Double> heat = new HashMap<>();
        /** Delivered to each part's sensitive window by paths that met at least one mirror or glass surface [W]. */
        public final Map<Long, Double> light = new HashMap<>();
        /** Delivered to sensitive windows straight from the source (left to a direct calculation unless included) [W]. */
        public double direct;
        /** Scattered by matt surfaces (not followed), escaped from the scene, cut off as negligible; the total [W]. */
        public double scattered, escaped, cut, total;
        /** Tubes and corner rays traced. */
        public int tubes, rays;

        public double heat(long part) {
            return heat.getOrDefault(part, 0.0);
        }

        public double light(long part) {
            return light.getOrDefault(part, 0.0);
        }

        /** Sum of every destination (equals the total but for rounding). */
        public double accounted() {
            double s = direct + scattered + escaped + cut;
            for (double v : heat.values()) s += v;
            for (double v : light.values()) s += v;
            return s;
        }
    }

    private static final int ESCAPED = 0, ABSORBED = 1, CUT = 2;
    private static final double CUTOFF = 1e-4;
    private static final int MAX_EVENTS = 40;

    private record Leaf(long sig, int events, double w, int type, OpticalScene.Hit end, int[] depObj, double[] depW) {
    }

    private static final class Vertex {
        Vec3 dir;
        List<Leaf> leaves;
        long[] sigs;
    }

    private record Key(double u, double v) {
    }

    private interface Emitter {
        Vec3 origin(double u, double v);

        Vec3 direction(double u, double v);

        /** Power carried by the tube with these corners (parameters and directions). */
        double power(double ua, double va, double ub, double vb, double uc, double vc, Vec3 da, Vec3 db, Vec3 dc);

        /** True if the source's intensity varies too much across the tube for its power to be integrated well. */
        default boolean uneven(Vec3 da, Vec3 db, Vec3 dc) {
            return false;
        }
    }

    private final OpticalScene scene;
    private final double nm;
    private final long skip;
    private final boolean includeDirect;
    private final int maxLevel;
    private final Emitter src;
    private final Map<Key, Vertex> cache = new HashMap<>();
    private final Result r = new Result();
    /** Objects that redirect light and must be resolved: direction from the source, angular radius. */
    private final List<double[]> targets = new ArrayList<>();
    /** Refine where corners part company only if one of them reaches a sensitive window (light is all that is wanted). */
    private boolean windowsOnly;

    private TubeTracer(OpticalScene scene, double nm, long skip, boolean includeDirect, int maxLevel, Emitter src) {
        this.scene = scene;
        this.nm = nm;
        this.skip = skip;
        this.includeDirect = includeDirect;
        this.maxLevel = maxLevel;
        this.src = src;
    }

    /**
     * A small source at {@code pos} emitting about {@code axis} with radiant intensity ∝ cos^m θ (m = 0: Lambertian),
     * at a wavelength [nm]; part {@code skip} (the emitter itself) is transparent to its own light. The pattern is
     * divided into 2·base × 2·base squares of two tubes each (each carrying the intensity at its middle times its solid
     * angle; the results are per watt to within that quadrature, see {@link Result#total}), refined up to
     * {@code maxLevel} times where the corners
     * part company on their way to a sensitive window, or the tubes are too coarse for a mirror or lens they meet.
     */
    public static Result point(OpticalScene scene, Vec3 pos, Vec3 axis, double m, double nm, long skip, int base, int maxLevel) {
        Vec3 n = axis.normalize(), e1 = n.anyPerpendicular().normalize(), e2 = n.cross(e1);
        double inv = 1 / (m + 1);
        Emitter em = new Emitter() {
            public Vec3 origin(double u, double v) {
                return pos;
            }

            public Vec3 direction(double u, double v) {
                // the unit square onto the unit disc keeping areas (Shirley & Chiu's concentric map: no seam, no
                // collapsed pole), then the disc's area fraction r² as the fraction of power within θ, 1 − cos^{m+1}θ
                double a = 2 * u - 1, b = 2 * v - 1, r, ph;
                if (a == 0 && b == 0) return n;
                if (Math.abs(a) > Math.abs(b)) {
                    r = a;
                    ph = Math.PI / 4 * (b / a);
                } else {
                    r = b;
                    ph = Math.PI / 2 - Math.PI / 4 * (a / b);
                }
                double c = Math.pow(Math.max(0, 1 - r * r), inv), s = Math.sqrt(Math.max(0, 1 - c * c));
                double cx = Math.cos(ph) * Math.signum(r), sy = Math.sin(ph) * Math.signum(r);
                return n.mul(c).add(e1.mul(s * cx)).add(e2.mul(s * sy));
            }

            public double power(double ua, double va, double ub, double vb, double uc, double vc, Vec3 da, Vec3 db, Vec3 dc) {
                // radiant intensity across the tube times its solid angle (Van Oosterom & Strackee): a direct
                // path's footprint is the same triangle projected, so it carries exactly I·Ω whatever the sampling
                double det = Math.abs(da.dot(db.cross(dc)));
                double omega = 2 * Math.atan2(det, 1 + da.dot(db) + db.dot(dc) + dc.dot(da));
                // the intensity averaged at the edges' midpoints (exact for a quadratic across a flat triangle)
                double i = 0;
                for (Vec3 mid : new Vec3[]{da.add(db), db.add(dc), dc.add(da)}) i += Math.pow(Math.max(0, mid.dot(n) / mid.length()), m);
                return (m + 1) / (2 * Math.PI) * i / 3 * omega;
            }

            public boolean uneven(Vec3 da, Vec3 db, Vec3 dc) {
                double lo = Double.MAX_VALUE, hi = 0;
                for (Vec3 d : new Vec3[]{da, db, dc}) {
                    double i = Math.pow(Math.max(0, d.dot(n)), m);
                    lo = Math.min(lo, i);
                    hi = Math.max(hi, i);
                }
                // a tube whose corners' intensities differ threefold, unless it carries next to nothing
                return hi > 3 * lo && hi * (m + 1) / (2 * Math.PI) * 2 * Math.atan2(Math.abs(da.dot(db.cross(dc))),
                        1 + da.dot(db) + db.dot(dc) + dc.dot(da)) > 1e-4;
            }
        };
        TubeTracer t = new TubeTracer(scene, nm, skip, false, maxLevel, em);
        t.windowsOnly = true;
        for (OpticalScene.Obj o : scene.objects()) {
            if (!o.redirects() || o.part == skip) continue;
            double[] sph = scene.worldSphere(o);
            Vec3 c = new Vec3(sph[0], sph[1], sph[2]).sub(pos);
            double d = c.length();
            if (d <= sph[3]) continue; // the source sits within its bounds: the base tubes are small enough there
            Vec3 u = c.mul(1 / d);
            t.targets.add(new double[]{u.x(), u.y(), u.z(), Math.asin(sph[3] / d)});
        }
        int k = 2 * base;
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) {
                double u0 = (double) i / k, u1 = (double) (i + 1) / k, v0 = (double) j / k, v1 = (double) (j + 1) / k;
                // split each square along the diagonal pointing away from the centre: the axis is a corner
                if ((i < k / 2) == (j < k / 2)) {
                    t.tube(u0, v0, u1, v0, u1, v1, 0);
                    t.tube(u0, v0, u1, v1, u0, v1, 0);
                } else {
                    t.tube(u0, v0, u1, v0, u0, v1, 0);
                    t.tube(u1, v0, u1, v1, u0, v1, 0);
                }
            }
        return t.r;
    }

    /**
     * A parallel beam travelling along {@code dir} across the whole scene (sunlight), per unit irradiance [W/m²]: the
     * results are in watts per W/m². {@code spacing} [m] is the base tube size; {@code includeDirect}: count light
     * reaching sensitive windows straight from the beam among {@link Result#light}.
     */
    public static Result parallel(OpticalScene scene, Vec3 dir, double nm, double spacing, int maxLevel, boolean includeDirect) {
        Vec3 n = dir.normalize(), e1 = n.anyPerpendicular().normalize(), e2 = n.cross(e1);
        double[] b = scene.bounds();
        Vec3 c = new Vec3(b[0], b[1], b[2]);
        double rad = b[3] + spacing;
        Vec3 base = c.sub(n.mul(rad + spacing));
        Emitter em = new Emitter() {
            public Vec3 origin(double u, double v) {
                return base.add(e1.mul(u)).add(e2.mul(v));
            }

            public Vec3 direction(double u, double v) {
                return n;
            }

            public double power(double ua, double va, double ub, double vb, double uc, double vc, Vec3 da, Vec3 db, Vec3 dc) {
                return 0.5 * Math.abs((ub - ua) * (vc - va) - (uc - ua) * (vb - va)); // its cross-section [m²]
            }
        };
        TubeTracer t = new TubeTracer(scene, nm, Long.MIN_VALUE, includeDirect, maxLevel, em);
        int k = (int) Math.ceil(2 * rad / spacing);
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) {
                double u0 = -rad + i * spacing, u1 = u0 + spacing, v0 = -rad + j * spacing, v1 = v0 + spacing;
                t.tube(u0, v0, u1, v0, u1, v1, 0);
                t.tube(u0, v0, u1, v1, u0, v1, 0);
            }
        return t.r;
    }

    // ------------------------------------------------------------------ tubes

    private void tube(double ua, double va, double ub, double vb, double uc, double vc, int level) {
        Vertex a = vertex(ua, va), b = vertex(ub, vb), c = vertex(uc, vc);
        boolean parted = !Arrays.equals(a.sigs, b.sigs) || !Arrays.equals(a.sigs, c.sigs);
        if (parted && windowsOnly) parted = reachesWindow(a) || reachesWindow(b) || reachesWindow(c);
        if (level < maxLevel && (parted || coarse(a, b, c) || src.uneven(a.dir, b.dir, c.dir))) {
            double uab = 0.5 * (ua + ub), vab = 0.5 * (va + vb), ubc = 0.5 * (ub + uc), vbc = 0.5 * (vb + vc);
            double uca = 0.5 * (uc + ua), vca = 0.5 * (vc + va);
            tube(ua, va, uab, vab, uca, vca, level + 1);
            tube(uab, vab, ub, vb, ubc, vbc, level + 1);
            tube(uca, vca, ubc, vbc, uc, vc, level + 1);
            tube(uab, vab, ubc, vbc, uca, vca, level + 1);
            return;
        }
        r.tubes++;
        double power = src.power(ua, va, ub, vb, uc, vc, a.dir, b.dir, c.dir);
        r.total += power;
        Vertex[] vs = {a, b, c};
        double share = power / 3;
        for (int i = 0; i < 3; i++) {
            for (Leaf l : vs[i].leaves) {
                Leaf l1 = find(vs[(i + 1) % 3], l.sig), l2 = find(vs[(i + 2) % 3], l.sig);
                double f;
                if (l1 != null && l2 != null) f = window(l, l1, l2);
                else f = window(l, null, null);
                deposit(l, share, f);
            }
        }
    }

    /** True if some path from the corner ends on a sensitive window (counting direct paths only when they are wanted). */
    private boolean reachesWindow(Vertex v) {
        for (Leaf l : v.leaves) {
            if (l.type == ABSORBED && l.end.obj.apertureFace == l.end.surface && (l.events > 0 || includeDirect)) return true;
        }
        return false;
    }

    /** True if the tube looks at a mirror or lens too small for it to resolve (its cone wider than a third of the object's). */
    private boolean coarse(Vertex a, Vertex b, Vertex c) {
        if (targets.isEmpty()) return false;
        Vec3 m = a.dir.add(b.dir).add(c.dir);
        double ml = m.length();
        if (ml < 1e-12) return false;
        m = m.mul(1 / ml);
        double g = Math.max(angle(m, a.dir), Math.max(angle(m, b.dir), angle(m, c.dir)));
        for (double[] t : targets) {
            if (g <= t[3] / 3) continue;
            double ang = angle(m, new Vec3(t[0], t[1], t[2]));
            if (ang < g + t[3]) return true;
        }
        return false;
    }

    private static double angle(Vec3 a, Vec3 b) {
        return Math.acos(Math.max(-1, Math.min(1, a.dot(b))));
    }

    private static Leaf find(Vertex v, long sig) {
        for (Leaf l : v.leaves) if (l.sig == sig) return l;
        return null;
    }

    private Vertex vertex(double u, double v) {
        return cache.computeIfAbsent(new Key(u, v), k -> {
            Vertex x = new Vertex();
            x.dir = src.direction(u, v);
            x.leaves = new ArrayList<>(2);
            r.rays++;
            path(src.origin(u, v), x.dir, 1.0, null, 1469598103934665603L, 0, new int[0], new double[0], x.leaves);
            x.sigs = x.leaves.stream().filter(l -> l.w >= 10 * CUTOFF).mapToLong(Leaf::sig).sorted().toArray();
            return x;
        });
    }

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h * 0x100000001B3L;
    }

    // ------------------------------------------------------------------ one ray and its branches

    private void path(Vec3 o, Vec3 d, double w, OpticalScene.Obj inside, long sig, int events, int[] depObj, double[] depW, List<Leaf> out) {
        if (w < CUTOFF || events > MAX_EVENTS) {
            out.add(new Leaf(mix(sig, -3), events, w, CUT, null, depObj, depW));
            return;
        }
        OpticalScene.Hit h = scene.trace(o, d, inside, skip);
        if (h == null) {
            out.add(new Leaf(mix(sig, -1), events, w, ESCAPED, null, depObj, depW));
            return;
        }
        OpticalScene.Obj ob = h.obj;
        long s = mix(sig, ob.index * 8L + h.surface);
        switch (ob.kind) {
            case ABSORBER -> out.add(new Leaf(s, events, w, ABSORBED, h, depObj, depW));
            case MIRROR -> {
                double rf = h.surface == 0 ? ob.reflectance : 0;
                if (rf <= 0) {
                    out.add(new Leaf(s, events, w, ABSORBED, h, depObj, depW));
                    return;
                }
                // a polished metal absorbs what it does not reflect
                int[] dob = Arrays.copyOf(depObj, depObj.length + 1);
                double[] dw = Arrays.copyOf(depW, depW.length + 1);
                dob[depObj.length] = ob.index;
                dw[depW.length] = w * (1 - rf);
                path(h.point, Optics.reflect(d, h.normal), w * rf, null, mix(s, 1), events + 1, dob, dw, out);
            }
            case GLASS, LENS -> {
                if (ob.kind == OpticalScene.Kind.LENS && h.surface == OpticalScene.LENS_RIM) {
                    out.add(new Leaf(s, events, w, ABSORBED, h, depObj, depW)); // the ground, blackened edge
                    return;
                }
                double n = ob.index(nm), n1 = inside != null ? n : 1, n2 = inside != null ? 1 : n;
                double cos = Math.abs(d.dot(h.normal));
                double rf = Optics.fresnel(cos, n1, n2);
                Vec3 tr = rf < 1 ? Optics.refract(d, h.normal, n1, n2) : null;
                if (tr == null) rf = 1;
                if (rf < 1) path(h.point, tr, w * (1 - rf), inside != null ? null : ob, mix(s, 2), events + 1, depObj, depW, out);
                if (rf > 0) path(h.point, Optics.reflect(d, h.normal), w * rf, inside, mix(s, 3), events + 1, depObj, depW, out);
            }
        }
    }

    // ------------------------------------------------------------------ where the power goes

    /** Share of a leaf's light landing in its object's sensitive window: by the tube's footprint, or by its own point. */
    private static double window(Leaf l, Leaf l1, Leaf l2) {
        if (l.type != ABSORBED) return 0;
        OpticalScene.Obj o = l.end.obj;
        if (o.apertureFace < 0 || l.end.surface != o.apertureFace) return 0;
        int ax = o.apertureFace / 2, iu = (ax + 1) % 3, iv = (ax + 2) % 3;
        double cu = o.apertureCentre.get(iu), cv = o.apertureCentre.get(iv), h = o.apertureHalf;
        if (l1 == null) {
            Vec3 p = l.end.local;
            return Math.abs(p.get(iu) - cu) <= h && Math.abs(p.get(iv) - cv) <= h ? 1 : 0;
        }
        double[] xs = {l.end.local.get(iu) - cu, l1.end.local.get(iu) - cu, l2.end.local.get(iu) - cu};
        double[] ys = {l.end.local.get(iv) - cv, l1.end.local.get(iv) - cv, l2.end.local.get(iv) - cv};
        double a0 = Math.abs(area(xs, ys, 3));
        if (a0 < 1e-24) {
            double x = (xs[0] + xs[1] + xs[2]) / 3, y = (ys[0] + ys[1] + ys[2]) / 3;
            return Math.abs(x) <= h && Math.abs(y) <= h ? 1 : 0;
        }
        double[][] poly = clip(xs, ys, h);
        return poly[0].length < 3 ? 0 : Math.min(1, Math.abs(area(poly[0], poly[1], poly[0].length)) / a0);
    }

    private static double area(double[] x, double[] y, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            s += x[i] * y[j] - x[j] * y[i];
        }
        return 0.5 * s;
    }

    /** Sutherland–Hodgman: the triangle clipped to the square |x|, |y| ≤ h. */
    private static double[][] clip(double[] x, double[] y, double h) {
        double[] px = x.clone(), py = y.clone();
        int n = 3;
        for (int e = 0; e < 4 && n > 0; e++) {
            double[] qx = new double[n + 4], qy = new double[n + 4];
            int m = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double si = side(e, px[i], py[i], h), sj = side(e, px[j], py[j], h);
                if (si >= 0) {
                    qx[m] = px[i];
                    qy[m++] = py[i];
                }
                if ((si >= 0) != (sj >= 0)) {
                    double t = si / (si - sj);
                    qx[m] = px[i] + t * (px[j] - px[i]);
                    qy[m++] = py[i] + t * (py[j] - py[i]);
                }
            }
            px = Arrays.copyOf(qx, m);
            py = Arrays.copyOf(qy, m);
            n = m;
        }
        return new double[][]{px, py};
    }

    /** Signed distance inside edge e of the square (0: x ≤ h, 1: x ≥ −h, 2: y ≤ h, 3: y ≥ −h). */
    private static double side(int e, double x, double y, double h) {
        return switch (e) {
            case 0 -> h - x;
            case 1 -> x + h;
            case 2 -> h - y;
            default -> y + h;
        };
    }

    private void deposit(Leaf l, double p, double window) {
        for (int i = 0; i < l.depObj.length; i++) add(r.heat, scene.objects().get(l.depObj[i]).part, p * l.depW[i]);
        double q = p * l.w;
        switch (l.type) {
            case ESCAPED -> r.escaped += q;
            case CUT -> r.cut += q;
            default -> {
                OpticalScene.Obj o = l.end.obj;
                double toWindow = q * window;
                if (toWindow > 0) {
                    if (l.events > 0 || includeDirect) add(r.light, o.part, toWindow);
                    else r.direct += toWindow;
                }
                double rest = q - toWindow;
                add(r.heat, o.part, rest * o.absorptance);
                r.scattered += rest * (1 - o.absorptance);
            }
        }
    }

    private static void add(Map<Long, Double> m, long k, double v) {
        if (v != 0) m.merge(k, v, Double::sum);
    }
}
