package dev.emergence.core.assembly;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.structure.FrameSolver;

import java.util.*;

/**
 * Static strength of the stationary structure of an assembly. Every structural part of the anchored frame is a
 * node of a 3D frame; every face contact between two of them is a beam member whose cross-section is the
 * contact face (so a thin plate is a thin member); parts resting on the ground are clamped. Loads are gravity:
 * each part's own weight, small parts carried on the frame, the weight of turning bodies shared among the
 * bearings that hold them, and the fluid held in vessels.
 * <p>
 * Each member's end forces give the extreme-fibre stresses at its contact faces: σ = N/A ± M₁c₂/I₁ ± M₂c₁/I₂ and
 * shear from V and T. Ductile materials (with a yield strength) are checked by von Mises against yield; brittle
 * ones (ultimate strength only — glass, ceramics, stone, wood across the grain) by the largest tensile stress
 * against the ultimate tensile strength (Rankine), compression being allowed eight times more. A utilisation
 * above 1 is failure: the part breaks. Only blocks and vessels are checked; other structural parts (bearings,
 * gears, pipes) act as stiff connectors.
 * <p>
 * Limits (see KNOWN_LIMITATIONS): linear statics (no dynamic or impact loads, no buckling, no plastic
 * redistribution — first yield is taken as failure), slender-beam members even for cube-shaped cells, contact
 * faces fully bonded.
 */
public final class StructuralAnalysis {
    public record Result(Map<Long, Double> utilisation, Map<Long, Vec3> displacement, List<Long> failed, double maxUtilisation) {
        static final Result EMPTY = new Result(Map.of(), Map.of(), List.of(), 0);
    }

    private StructuralAnalysis() {
    }

    static boolean checked(Part p) {
        return p.kind() == PartKind.BLOCK || p.kind() == PartKind.TANK;
    }

    /** Extent of a part's solid along global axis j [m]: a block plate is thin along its own axis. */
    static double solidExtent(Part p, int j) {
        double box = p.boxSize(j);
        if (p.kind() == PartKind.BLOCK) return j == p.axis().axis() ? box * Math.max(0.01, Math.min(1, p.param("fill", 1.0))) : box;
        if (p.kind() == PartKind.TANK) return box;
        return 0.5 * box; // connectors: a solid core
    }

    static double[] strength(Material m) {
        double ys = m.get(MaterialProperty.YIELD_STRENGTH, Double.NaN), uts = m.get(MaterialProperty.ULTIMATE_TENSILE_STRENGTH, Double.NaN);
        // {tensile allowable, compressive allowable, ductile? 1 : 0}
        if (!Double.isNaN(ys)) return new double[]{ys, ys, 1};
        if (!Double.isNaN(uts)) return new double[]{uts, 8 * uts, 0};
        return new double[]{Double.NaN, Double.NaN, 0};
    }

    public static Result run(CompiledAssembly c) {
        Assembly a = c.assembly();
        List<Part> nodes = new ArrayList<>();
        Map<Long, Integer> idx = new HashMap<>();
        for (Part p : a.parts()) {
            CompiledAssembly.Body b = c.bodyOf(p.id());
            if (p.kind().structural && b != null && b.isStatic) {
                idx.put(p.id(), nodes.size());
                nodes.add(p);
            }
        }
        if (nodes.isEmpty()) return Result.EMPTY;
        FrameSolver f = new FrameSolver(nodes.size());
        record Link(Part p, Part q, int axis, FrameSolver.Member m, double d1, double d2) {}
        List<Link> links = new ArrayList<>();
        Set<Long> pairs = new HashSet<>();
        for (Part p : nodes) {
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int y = p.minY(); y <= p.maxY(); y++)
                    for (int z = p.minZ(); z <= p.maxZ(); z++)
                        for (int k = 0; k < 3; k++) {
                            int nx = x + (k == 0 ? 1 : 0), ny = y + (k == 1 ? 1 : 0), nz = z + (k == 2 ? 1 : 0);
                            if (p.containsCell(nx, ny, nz)) continue;
                            for (Part q : a.at(nx, ny, nz)) {
                                Integer qi = idx.get(q.id());
                                if (qi == null || q == p) continue;
                                long key = Math.min(p.id(), q.id()) * 1_000_003L + Math.max(p.id(), q.id()) * 7 + k;
                                if (!pairs.add(key)) continue;
                                int t1 = (k + 1) % 3, t2 = (k + 2) % 3;
                                double d1 = Math.min(solidExtent(p, t1), solidExtent(q, t1)), d2 = Math.min(solidExtent(p, t2), solidExtent(q, t2));
                                double area = d1 * d2;
                                double i1 = d1 * d2 * d2 * d2 / 12, i2 = d2 * d1 * d1 * d1 / 12; // about t1 and about t2
                                double big = Math.max(d1, d2), small = Math.min(d1, d2);
                                double j = big * small * small * small * (1.0 / 3 - 0.21 * small / big * (1 - Math.pow(small / big, 4) / 12));
                                double ep = p.material().get(MaterialProperty.YOUNGS_MODULUS, 1e9), eq = q.material().get(MaterialProperty.YOUNGS_MODULUS, 1e9);
                                double e = 2 / (1 / ep + 1 / eq); // two half-members in series
                                double nu = 0.5 * (p.material().get(MaterialProperty.POISSON_RATIO, 0.3) + q.material().get(MaterialProperty.POISSON_RATIO, 0.3));
                                double len = Math.abs(component(q.center().sub(p.center()), k));
                                Part lo = component(q.center().sub(p.center()), k) > 0 ? p : q, hi = lo == p ? q : p;
                                FrameSolver.Member m = new FrameSolver.Member(idx.get(lo.id()), idx.get(hi.id()), k, Math.max(len, 1e-4), e,
                                        e / (2 * (1 + nu)), area, i1, i2, j);
                                f.addMember(m);
                                links.add(new Link(lo, hi, k, m, d1, d2));
                            }
                        }
        }
        // supports; loads only on parts with a load path to a support (a floating build has nothing to bear on)
        Map<Long, List<Long>> adj = new HashMap<>();
        for (Link l : links) {
            adj.computeIfAbsent(l.p.id(), k -> new ArrayList<>()).add(l.q.id());
            adj.computeIfAbsent(l.q.id(), k -> new ArrayList<>()).add(l.p.id());
        }
        Set<Long> supported = new HashSet<>();
        ArrayDeque<Long> bfs = new ArrayDeque<>();
        for (Part p : nodes) if (a.isAnchored(p) && supported.add(p.id())) bfs.add(p.id());
        while (!bfs.isEmpty()) for (long o : adj.getOrDefault(bfs.poll(), List.of())) if (supported.add(o)) bfs.add(o);
        double g = PartGeometry.gravity();
        for (Part p : nodes) {
            int i = idx.get(p.id());
            if (a.isAnchored(p)) f.fix(i);
            if (!supported.contains(p.id())) continue;
            f.force(i, 1, -PartGeometry.mass(p) * g);
            double[] fl = c.fluid(p.id());
            if (fl != null) f.force(i, 1, -fl[2] * g);
        }
        for (Part p : a.parts()) {
            if (idx.containsKey(p.id())) continue;
            CompiledAssembly.Body b = c.bodyOf(p.id());
            if (b == null || !b.isStatic) continue;
            Part carrier = touchingNode(a, p, idx);
            if (carrier != null && supported.contains(carrier.id())) f.force(idx.get(carrier.id()), 1, -PartGeometry.mass(p) * g);
        }
        for (CompiledAssembly.Body b : c.bodies()) {
            if (b.isStatic) continue;
            List<Part> bearings = new ArrayList<>();
            for (Part p : nodes) if ((p.kind() == PartKind.BEARING || p.kind() == PartKind.POTENTIOMETER || p.kind() == PartKind.LINEAR_BEARING) && supported.contains(p.id()) && touchesBody(a, c, p, b)) bearings.add(p);
            if (bearings.isEmpty()) continue;
            for (Part br : bearings) f.force(idx.get(br.id()), 1, -b.mass * g / bearings.size());
        }
        double[] u;
        try {
            u = f.solve();
        } catch (IllegalStateException ex) {
            return Result.EMPTY;
        }
        Map<Long, Double> util = new HashMap<>();
        Map<Long, Vec3> disp = new HashMap<>();
        for (Part p : nodes) {
            int i = idx.get(p.id());
            disp.put(p.id(), new Vec3(u[6 * i], u[6 * i + 1], u[6 * i + 2]));
        }
        double max = 0;
        for (Link l : links) {
            FrameSolver.MemberForces mf = FrameSolver.forces(l.m, u);
            for (double[] end : new double[][]{mf.atA(), mf.atB()}) {
                double area = l.m.area();
                double sb = Math.abs(end[4]) * (l.d2 / 2) / l.m.i1() + Math.abs(end[5]) * (l.d1 / 2) / l.m.i2();
                double st = end[0] / area + sb, sc = -end[0] / area + sb;
                double tau = 1.5 * Math.hypot(end[1], end[2]) / area + Math.abs(end[3]) * Math.min(l.d1, l.d2) / Math.max(l.m.j(), 1e-30);
                for (Part p : new Part[]{l.p, l.q}) {
                    if (!checked(p)) continue;
                    double[] s = strength(p.material());
                    if (Double.isNaN(s[0])) continue;
                    double r = s[2] > 0
                            ? Math.sqrt(Math.pow(Math.max(st, sc), 2) + 3 * tau * tau) / s[0]
                            : Math.max(Math.max(st, 0) / s[0], Math.max(sc, 0) / s[1]);
                    util.merge(p.id(), r, Math::max);
                    max = Math.max(max, r);
                }
            }
        }
        List<Long> failed = new ArrayList<>();
        for (var en : util.entrySet()) if (en.getValue() > 1) failed.add(en.getKey());
        if (!failed.isEmpty()) {
            // whatever no longer has a load path to the ground once the failed parts are gone collapses with them
            Set<Long> gone = new HashSet<>(failed);
            Set<Long> held = new HashSet<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            for (Part p : nodes) if (a.isAnchored(p) && !gone.contains(p.id()) && held.add(p.id())) q.add(p.id());
            while (!q.isEmpty()) {
                long id = q.poll();
                for (long o : adj.getOrDefault(id, List.of())) if (!gone.contains(o) && held.add(o)) q.add(o);
            }
            for (Part p : nodes) if (!held.contains(p.id()) && !gone.contains(p.id())) failed.add(p.id());
        }
        Collections.sort(failed);
        return new Result(util, disp, failed, max);
    }

    static double component(Vec3 v, int k) {
        return k == 0 ? v.x() : k == 1 ? v.y() : v.z();
    }

    /** A static structural node touching part p (sharing a face with one of its cells), or null. */
    static Part touchingNode(Assembly a, Part p, Map<Long, Integer> idx) {
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++)
                    for (Dir d : Dir.values()) {
                        for (Part q : a.at(x + d.dx, y + d.dy, z + d.dz)) if (idx.containsKey(q.id())) return q;
                    }
        return null;
    }

    static boolean touchesBody(Assembly a, CompiledAssembly c, Part bearing, CompiledAssembly.Body b) {
        for (int x = bearing.minX(); x <= bearing.maxX(); x++)
            for (int y = bearing.minY(); y <= bearing.maxY(); y++)
                for (int z = bearing.minZ(); z <= bearing.maxZ(); z++) {
                    for (Part q : a.at(x, y, z)) if (c.bodyOf(q.id()) == b) return true; // the shaft through the bearing
                }
        return false;
    }
}
