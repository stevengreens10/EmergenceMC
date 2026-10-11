package dev.emergence.core.optics;

import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The surfaces light meets in an assembly, for ray tracing: opaque bodies that absorb (some with a sensitive window,
 * a photodiode's junction), mirrors (flat, tilted or spherical, clipped to their part's box), blocks of glass and
 * spherical lenses. Every object belongs to a frame — the fixed world, or a body that turns about an axis or slides
 * along one — and is stored in its frame's coordinates at rest; {@link #setPose} moves a frame. Rays find their
 * first hit by walking a uniform grid of cells (Amanatides–Woo) in each frame.
 */
public final class OpticalScene {
    public enum Kind {
        /** An opaque body: absorbs a fraction (its absorptance); the rest is scattered diffusely and not followed. */
        ABSORBER,
        /** A specular reflector: the front reflects its reflectance, the rest and the back absorb. */
        MIRROR,
        /** A box of transparent material (refraction and Fresnel reflection at its faces). */
        GLASS,
        /** A lens: two spherical (or flat) faces on a cylinder of glass whose ground rim absorbs. */
        LENS
    }

    /** Surfaces: box faces 0..5 (−x, +x, −y, +y, −z, +z); a mirror's front 0 and back 1; a lens's first and second face 0 and 1, rim 2. */
    public static final int LENS_RIM = 2;

    private static final double EPS = 1e-9;

    /** An object light can meet. Geometry is in its frame's coordinates. */
    public static final class Obj {
        /** Bulk absorption coefficient inside glass or a lens [1/m] (0: clear). */
        public double alpha;
        public final int index;
        public final long part;
        public final Kind kind;
        final int frame;
        final double[] lo = new double[3], hi = new double[3];
        int stamp;
        /** Absorbers: the fraction of arriving light absorbed (the rest is scattered and not followed). */
        double absorptance = 1;
        /** Absorbers: a sensitive window on one face (face index, or −1), its centre and half-width. */
        int apertureFace = -1;
        Vec3 apertureCentre;
        double apertureHalf;
        /** Mirrors: front normal, vertex, signed radius (positive concave towards the front; 0 flat), reflectance. */
        Vec3 axis, vertex;
        double radius, reflectance;
        /** Glass and lenses: n_d and Abbe number; lens centre, axis (above), face radii, rim radius, half thickness. */
        double nd = 1.5, vd;
        Vec3 centre;
        double r1, r2, rim, halfThickness;
        /** Glass cut by extra planes (a prism): each {n_x, n_y, n_z, c}, inside where n·x ≤ c; faces 6, 7, … */
        double[][] planes = new double[0][];

        Obj(int index, long part, Kind kind, int frame, double[] lo, double[] hi) {
            this.index = index;
            this.part = part;
            this.kind = kind;
            this.frame = frame;
            System.arraycopy(lo, 0, this.lo, 0, 3);
            System.arraycopy(hi, 0, this.hi, 0, 3);
        }

        /** Refractive index at a wavelength [nm]. */
        public double index(double nm) {
            return Optics.cauchy(nd, vd, nm);
        }

        public double absorptance() {
            return absorptance;
        }

        public double reflectance() {
            return reflectance;
        }

        /** Lens vertex positions along the axis from the centre. */
        Vec3 lensVertex(int face) {
            return centre.add(axis.mul(face == 0 ? -halfThickness : halfThickness));
        }

        double lensRadius(int face) {
            return face == 0 ? r1 : r2;
        }

        /** Bounding sphere radius [m]. */
        double boundRadius() {
            double s = 0;
            for (int a = 0; a < 3; a++) s += (hi[a] - lo[a]) * (hi[a] - lo[a]);
            return 0.5 * Math.sqrt(s);
        }

        Vec3 boxCentre() {
            return new Vec3(0.5 * (lo[0] + hi[0]), 0.5 * (lo[1] + hi[1]), 0.5 * (lo[2] + hi[2]));
        }

        /** True for mirrors, glass and lenses: objects that redirect light. */
        public boolean redirects() {
            return kind != Kind.ABSORBER;
        }
    }

    /** Where a ray meets an object: distance, surface, point and unit normal (world), and the point in the object's frame. */
    public static final class Hit {
        public double t;
        public Obj obj;
        public int surface;
        public Vec3 point, normal, local;
    }

    /** A frame: the world (identity), or a body turning about an axis through a pivot, or sliding along an axis. */
    static final class Frame {
        final Vec3 pivot, axis;
        final boolean slider, fixed;
        double angle, offset;
        final Map<Long, List<Obj>> cells = new HashMap<>();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        Frame(Vec3 pivot, Vec3 axis, boolean slider, boolean fixed) {
            this.pivot = pivot;
            this.axis = axis;
            this.slider = slider;
            this.fixed = fixed;
        }

        Vec3 pointToLocal(Vec3 p) {
            if (fixed) return p;
            if (slider) return p.sub(axis.mul(offset));
            return p.sub(pivot).rotateAbout(axis, -angle).add(pivot);
        }

        Vec3 dirToLocal(Vec3 d) {
            return fixed || slider ? d : d.rotateAbout(axis, -angle);
        }

        Vec3 dirToWorld(Vec3 d) {
            return fixed || slider ? d : d.rotateAbout(axis, angle);
        }

        Vec3 pointToWorld(Vec3 p) {
            if (fixed) return p;
            if (slider) return p.add(axis.mul(offset));
            return p.sub(pivot).rotateAbout(axis, angle).add(pivot);
        }
    }

    private final double cell;
    private final List<Obj> objects = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private int stamp;

    /** A scene on a grid of the given cell size [m]; frame 0 is the fixed world. */
    public OpticalScene(double cell) {
        this.cell = cell;
        frames.add(new Frame(Vec3.ZERO, Vec3.Y, false, true));
    }

    /** Adds a frame turning about {@code axis} through {@code pivot} (or sliding along it); returns its index. */
    public int addFrame(Vec3 pivot, Vec3 axis, boolean slider) {
        frames.add(new Frame(pivot, axis.normalize(), slider, false));
        return frames.size() - 1;
    }

    /** Turns a frame to an angle [rad] (or slides it by a displacement [m]). */
    public void setPose(int frame, double angleOrOffset) {
        Frame f = frames.get(frame);
        if (f.slider) f.offset = angleOrOffset;
        else f.angle = angleOrOffset;
    }

    public double pose(int frame) {
        Frame f = frames.get(frame);
        return f.slider ? f.offset : f.angle;
    }

    public int frameCount() {
        return frames.size();
    }

    public List<Obj> objects() {
        return objects;
    }

    /** An opaque box [m] absorbing the given fraction of the light that meets it. */
    public Obj absorber(long part, int frame, double[] lo, double[] hi, double absorptance) {
        Obj o = add(new Obj(objects.size(), part, Kind.ABSORBER, frame, lo, hi));
        o.absorptance = absorptance;
        return o;
    }

    /** Gives an absorber a sensitive square window of the given area [m²] centred on one of its faces (0..5). */
    public void aperture(Obj o, int face, double area) {
        int a = face / 2;
        double[] c = {0.5 * (o.lo[0] + o.hi[0]), 0.5 * (o.lo[1] + o.hi[1]), 0.5 * (o.lo[2] + o.hi[2])};
        c[a] = face % 2 == 0 ? o.lo[a] : o.hi[a];
        o.apertureFace = face;
        o.apertureCentre = new Vec3(c[0], c[1], c[2]);
        double half = 0.5 * Math.sqrt(Math.max(area, 0));
        for (int k = 0; k < 3; k++) if (k != a) half = Math.min(half, 0.5 * (o.hi[k] - o.lo[k]));
        o.apertureHalf = half;
    }

    /**
     * A mirror inside the box: front normal {@code normal}, surface through {@code vertex}, signed radius of curvature
     * (positive: concave towards the front, its centre at vertex + radius·normal; 0: flat), clipped to the box.
     */
    public Obj mirror(long part, int frame, double[] lo, double[] hi, Vec3 normal, Vec3 vertex, double radius,
                      double reflectance, double backAbsorptance) {
        Obj o = add(new Obj(objects.size(), part, Kind.MIRROR, frame, lo, hi));
        o.axis = normal.normalize();
        o.vertex = vertex;
        o.radius = radius;
        o.reflectance = reflectance;
        o.absorptance = backAbsorptance;
        return o;
    }

    /**
     * A block of glass cut by planes through it (a prism): the box intersected with the half-spaces n·x ≤ n·point for
     * each {outward normal, point on the plane}.
     */
    public Obj prism(long part, int frame, double[] lo, double[] hi, double nd, double vd, Vec3[][] cuts) {
        Obj o = glass(part, frame, lo, hi, nd, vd);
        o.planes = new double[cuts.length][];
        for (int k = 0; k < cuts.length; k++) {
            Vec3 n = cuts[k][0].normalize();
            o.planes[k] = new double[]{n.x(), n.y(), n.z(), n.dot(cuts[k][1])};
        }
        return o;
    }

    /** Sets a glass or lens's bulk absorption coefficient at the scene's wavelength [1/m] (Beer–Lambert). */
    public Obj absorbing(Obj o, double alpha) {
        o.alpha = Math.max(0, alpha);
        return o;
    }

    /** A box of glass of index n_d and Abbe number V_d. */
    public Obj glass(long part, int frame, double[] lo, double[] hi, double nd, double vd) {
        Obj o = add(new Obj(objects.size(), part, Kind.GLASS, frame, lo, hi));
        o.nd = nd;
        o.vd = vd;
        return o;
    }

    /**
     * A lens centred at {@code centre} with its axis along {@code axis}: first face (met first by light travelling
     * along +axis) of signed radius r1, second r2 (lensmaker's convention; 0 flat), thickness t on the axis, rim
     * radius {@code rim}; glass n_d, V_d. The thickness is raised if the faces would cross inside the rim.
     */
    public Obj lens(long part, int frame, double[] lo, double[] hi, Vec3 centre, Vec3 axis, double r1, double r2,
                    double t, double rim, double nd, double vd) {
        Obj o = add(new Obj(objects.size(), part, Kind.LENS, frame, lo, hi));
        o.centre = centre;
        o.axis = axis.normalize();
        o.r1 = r1;
        o.r2 = r2;
        // a sphere smaller than the rim cuts the rim down to it
        double rr = rim;
        if (r1 != 0) rr = Math.min(rr, 0.999 * Math.abs(r1));
        if (r2 != 0) rr = Math.min(rr, 0.999 * Math.abs(r2));
        o.rim = rr;
        double edge = t - Optics.sag(r1, rr) + Optics.sag(r2, rr);
        o.halfThickness = 0.5 * (edge < 0.05 * t ? t - edge + 0.05 * t : t);
        o.nd = nd;
        o.vd = vd;
        return o;
    }

    /** Thickness of a lens on its axis [m]. */
    public static double thickness(Obj lens) {
        return 2 * lens.halfThickness;
    }

    private Obj add(Obj o) {
        objects.add(o);
        Frame f = frames.get(o.frame);
        int[] c0 = new int[3], c1 = new int[3];
        for (int a = 0; a < 3; a++) {
            c0[a] = (int) Math.floor(o.lo[a] / cell + 1e-9);
            c1[a] = (int) Math.floor(o.hi[a] / cell - 1e-9);
            if (c1[a] < c0[a]) c1[a] = c0[a];
            f.min[a] = Math.min(f.min[a], c0[a]);
            f.max[a] = Math.max(f.max[a], c1[a]);
        }
        for (int x = c0[0]; x <= c1[0]; x++)
            for (int y = c0[1]; y <= c1[1]; y++)
                for (int z = c0[2]; z <= c1[2]; z++) f.cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(o);
        return o;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Centre and radius of a sphere holding every object in every pose. */
    public double[] bounds() {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Obj o : objects) {
            Frame f = frames.get(o.frame);
            if (f.fixed) {
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], o.lo[a]);
                    hi[a] = Math.max(hi[a], o.hi[a]);
                }
            } else {
                Vec3 c = f.pointToWorld(o.boxCentre());
                double r = o.boundRadius();
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], c.get(a) - r);
                    hi[a] = Math.max(hi[a], c.get(a) + r);
                }
            }
        }
        if (objects.isEmpty()) return new double[]{0, 0, 0, 0};
        double r = 0;
        for (int a = 0; a < 3; a++) r += (hi[a] - lo[a]) * (hi[a] - lo[a]);
        return new double[]{0.5 * (lo[0] + hi[0]), 0.5 * (lo[1] + hi[1]), 0.5 * (lo[2] + hi[2]), 0.5 * Math.sqrt(r)};
    }

    /** World centre and bounding radius of an object in its frame's present pose. */
    public double[] worldSphere(Obj o) {
        Vec3 c = frames.get(o.frame).pointToWorld(o.boxCentre());
        return new double[]{c.x(), c.y(), c.z(), o.boundRadius()};
    }

    /**
     * The first surface a ray from o along unit d meets, ignoring the parts {@code skip}; inside a glass object the
     * ray can meet only that object's own surfaces.
     */
    public Hit trace(Vec3 o, Vec3 d, Obj inside, long skip) {
        stamp++;
        if (inside != null) {
            Frame f = frames.get(inside.frame);
            Hit h = intersect(inside, f.pointToLocal(o), f.dirToLocal(d), Double.MAX_VALUE);
            return h == null ? null : toWorld(h, f, o, d);
        }
        Hit best = null;
        Frame bestFrame = null;
        for (Frame f : frames) {
            if (f.cells.isEmpty()) continue;
            Vec3 lo = f.pointToLocal(o), ld = f.dirToLocal(d);
            double[] o3 = {lo.x(), lo.y(), lo.z()}, d3 = {ld.x(), ld.y(), ld.z()};
            double t0 = 0, t1 = best == null ? Double.MAX_VALUE : best.t;
            for (int a = 0; a < 3 && t0 <= t1; a++) {
                double bl = f.min[a] * cell, bh = (f.max[a] + 1) * cell;
                if (Math.abs(d3[a]) < 1e-300) {
                    if (o3[a] < bl || o3[a] > bh) t0 = Double.MAX_VALUE;
                    continue;
                }
                double ta = (bl - o3[a]) / d3[a], tb = (bh - o3[a]) / d3[a];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
            }
            if (t0 > t1) continue;
            int[] c = new int[3], step = new int[3];
            double[] tMax = new double[3], tDelta = new double[3];
            for (int a = 0; a < 3; a++) {
                double p = o3[a] + d3[a] * t0;
                c[a] = Math.max(f.min[a], Math.min(f.max[a], (int) Math.floor(p / cell)));
                if (d3[a] > 0) {
                    step[a] = 1;
                    tMax[a] = ((c[a] + 1) * cell - o3[a]) / d3[a];
                    tDelta[a] = cell / d3[a];
                } else if (d3[a] < 0) {
                    step[a] = -1;
                    tMax[a] = (c[a] * cell - o3[a]) / d3[a];
                    tDelta[a] = -cell / d3[a];
                } else {
                    tMax[a] = Double.MAX_VALUE;
                    tDelta[a] = Double.MAX_VALUE;
                }
            }
            while (true) {
                List<Obj> list = f.cells.get(key(c[0], c[1], c[2]));
                if (list != null) {
                    for (Obj ob : list) {
                        if (ob.stamp == stamp || ob.part == skip) continue;
                        ob.stamp = stamp;
                        Hit h = intersect(ob, lo, ld, best == null ? Double.MAX_VALUE : best.t);
                        if (h != null && (best == null || h.t < best.t)) {
                            best = h;
                            bestFrame = f;
                        }
                    }
                }
                int a = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
                double tNext = tMax[a];
                if (best != null && best.t <= tNext) break;
                if (tNext > t1) break;
                c[a] += step[a];
                if (c[a] < f.min[a] || c[a] > f.max[a]) break;
                tMax[a] += tDelta[a];
            }
        }
        return best == null ? null : toWorld(best, bestFrame, o, d);
    }

    private static Hit toWorld(Hit h, Frame f, Vec3 o, Vec3 d) {
        h.point = o.add(d.mul(h.t));
        h.normal = f.dirToWorld(h.normal);
        return h;
    }

    /** First intersection (local coordinates) with t in (EPS, tMax), or null. */
    static Hit intersect(Obj ob, Vec3 o, Vec3 d, double tMax) {
        return switch (ob.kind) {
            case ABSORBER, GLASS -> box(ob, o, d, tMax);
            case MIRROR -> mirror(ob, o, d, tMax);
            case LENS -> lens(ob, o, d, tMax);
        };
    }

    private static Hit box(Obj ob, Vec3 o, Vec3 d, double tMax) {
        double tNear = -Double.MAX_VALUE, tFar = Double.MAX_VALUE;
        int fNear = -1, fFar = -1;
        for (int a = 0; a < 3; a++) {
            double oa = o.get(a), da = d.get(a);
            if (Math.abs(da) < 1e-300) {
                if (oa < ob.lo[a] || oa > ob.hi[a]) return null;
                continue;
            }
            double t1 = (ob.lo[a] - oa) / da, t2 = (ob.hi[a] - oa) / da;
            int f1 = 2 * a, f2 = 2 * a + 1;
            if (t1 > t2) {
                double s = t1;
                t1 = t2;
                t2 = s;
                f1 = 2 * a + 1;
                f2 = 2 * a;
            }
            if (t1 > tNear) {
                tNear = t1;
                fNear = f1;
            }
            if (t2 < tFar) {
                tFar = t2;
                fFar = f2;
            }
        }
        for (int k = 0; k < ob.planes.length; k++) {
            double[] pl = ob.planes[k];
            double den = pl[0] * d.x() + pl[1] * d.y() + pl[2] * d.z();
            double dist = pl[3] - (pl[0] * o.x() + pl[1] * o.y() + pl[2] * o.z());
            if (Math.abs(den) < 1e-300) {
                if (dist < 0) return null;
                continue;
            }
            double t = dist / den;
            if (den < 0) {
                if (t > tNear) {
                    tNear = t;
                    fNear = 6 + k;
                }
            } else if (t < tFar) {
                tFar = t;
                fFar = 6 + k;
            }
        }
        if (tNear > tFar) return null;
        double t;
        int face;
        if (tNear > EPS) {
            t = tNear;
            face = fNear;
        } else if (tFar > EPS) {
            t = tFar;
            face = fFar;
        } else return null;
        if (t >= tMax || face < 0) return null;
        Hit h = new Hit();
        h.t = t;
        h.obj = ob;
        h.surface = face;
        h.local = o.add(d.mul(t));
        if (face >= 6) {
            double[] pl = ob.planes[face - 6];
            h.normal = new Vec3(pl[0], pl[1], pl[2]);
        } else {
            h.normal = Vec3.axis(face / 2).mul(face % 2 == 0 ? -1 : 1);
        }
        return h;
    }

    private static boolean inBox(Obj ob, Vec3 p) {
        for (int a = 0; a < 3; a++) {
            double v = p.get(a);
            if (v < ob.lo[a] - 1e-9 || v > ob.hi[a] + 1e-9) return false;
        }
        return true;
    }

    private static Hit mirror(Obj ob, Vec3 o, Vec3 d, double tMax) {
        Vec3 a = ob.axis;
        if (ob.radius == 0) {
            double den = d.dot(a);
            if (Math.abs(den) < 1e-300) return null;
            double t = ob.vertex.sub(o).dot(a) / den;
            if (!(t > EPS && t < tMax)) return null;
            Vec3 p = o.add(d.mul(t));
            if (!inBox(ob, p)) return null;
            Hit h = new Hit();
            h.t = t;
            h.obj = ob;
            h.surface = den < 0 ? 0 : 1;
            h.local = p;
            h.normal = a;
            return h;
        }
        Vec3 c = ob.vertex.add(a.mul(ob.radius));
        double[] ts = sphere(o, d, c, ob.radius);
        if (ts == null) return null;
        for (double t : ts) {
            if (!(t > EPS && t < tMax)) continue;
            Vec3 p = o.add(d.mul(t));
            if (ob.radius * p.sub(c).dot(a) >= 0 || !inBox(ob, p)) continue; // the cap round the vertex only
            Vec3 n = c.sub(p).mul(1 / ob.radius);                          // front normal
            Hit h = new Hit();
            h.t = t;
            h.obj = ob;
            h.surface = d.dot(n) < 0 ? 0 : 1;
            h.local = p;
            h.normal = n;
            return h;
        }
        return null;
    }

    /** Roots t₁ ≤ t₂ of |o + t d − c| = |r|, or null. */
    private static double[] sphere(Vec3 o, Vec3 d, Vec3 c, double r) {
        Vec3 oc = o.sub(c);
        double b = oc.dot(d), q = oc.dot(oc) - r * r, disc = b * b - q;
        if (disc < 0) return null;
        double s = Math.sqrt(disc);
        return new double[]{-b - s, -b + s};
    }

    private static Hit lens(Obj ob, Vec3 o, Vec3 d, double tMax) {
        Vec3 a = ob.axis;
        Hit best = null;
        for (int face = 0; face < 2; face++) {
            Vec3 v = ob.lensVertex(face);
            double r = ob.lensRadius(face);
            if (r == 0) {
                double den = d.dot(a);
                if (Math.abs(den) < 1e-300) continue;
                double t = v.sub(o).dot(a) / den;
                if (!(t > EPS && t < tMax) || (best != null && t >= best.t)) continue;
                Vec3 p = o.add(d.mul(t));
                if (radial(ob, p) > ob.rim) continue;
                best = hit(best, ob, face, t, p, a);
                continue;
            }
            Vec3 c = v.add(a.mul(r));
            double[] ts = sphere(o, d, c, r);
            if (ts == null) continue;
            for (double t : ts) {
                if (!(t > EPS && t < tMax) || (best != null && t >= best.t)) continue;
                Vec3 p = o.add(d.mul(t));
                if (r * p.sub(c).dot(a) >= 0 || radial(ob, p) > ob.rim) continue;
                best = hit(best, ob, face, t, p, p.sub(c).mul(1 / Math.abs(r)));
                break;
            }
        }
        // the rim: the cylinder r = rim between the two faces
        Vec3 oc = o.sub(ob.centre);
        Vec3 pp = oc.sub(a.mul(oc.dot(a))), qq = d.sub(a.mul(d.dot(a)));
        double qa = qq.dot(qq), qb = pp.dot(qq), qc = pp.dot(pp) - ob.rim * ob.rim;
        if (qa > 1e-300) {
            double disc = qb * qb - qa * qc;
            if (disc >= 0) {
                double s = Math.sqrt(disc);
                double z1 = -ob.halfThickness + Optics.sag(ob.r1, ob.rim), z2 = ob.halfThickness + Optics.sag(ob.r2, ob.rim);
                for (double t : new double[]{(-qb - s) / qa, (-qb + s) / qa}) {
                    if (!(t > EPS && t < tMax) || (best != null && t >= best.t)) continue;
                    Vec3 p = o.add(d.mul(t));
                    double z = p.sub(ob.centre).dot(a);
                    if (z < z1 || z > z2) continue;
                    Vec3 rad = p.sub(ob.centre).sub(a.mul(z));
                    best = hit(best, ob, LENS_RIM, t, p, rad.mul(1 / rad.length()));
                    break;
                }
            }
        }
        return best;
    }

    private static Hit hit(Hit best, Obj ob, int surface, double t, Vec3 p, Vec3 n) {
        if (best != null && best.t <= t) return best;
        Hit h = new Hit();
        h.t = t;
        h.obj = ob;
        h.surface = surface;
        h.local = p;
        h.normal = n;
        return h;
    }

    private static double radial(Obj ob, Vec3 p) {
        Vec3 r = p.sub(ob.centre);
        return r.sub(ob.axis.mul(r.dot(ob.axis))).length();
    }
}
