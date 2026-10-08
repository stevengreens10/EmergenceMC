package dev.emergence.core.assembly;

import dev.emergence.core.em.EmBody;
import dev.emergence.core.em.MagneticSystem;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.*;

import java.util.*;

import static dev.emergence.core.assembly.Part.CELL;

/**
 * Compiles an {@link Assembly} of primitive parts into a multiphysics network. Nothing here knows about
 * machines: the compiler only infers what physically follows from geometry and contact.
 *
 * <ol>
 *   <li><b>Rigid bodies.</b> Structural parts rigidly join everything they touch, except that a shaft
 *   passing through a bearing along its axis forms a revolute joint, meshing gears touch without
 *   joining, and flexible wires never transmit load. Groups touching the world are the static frame.</li>
 *   <li><b>Kinematics.</b> Groups connected to the frame (or to a coaxial rotor) only through collinear
 *   bearings become rotors about that axis line; anything else is locked.</li>
 *   <li><b>Mechanics.</b> Rotor inertia from the mass distribution of its parts; bearing friction from
 *   rotor weight and bearing type; gear meshes from tooth geometry and centre distance.</li>
 *   <li><b>Electrical.</b> Conductors in contact on the same body form circuits; resistance follows
 *   from material, length and cross-section; sliding contact across a rotating joint needs a brush.</li>
 *   <li><b>Thermal.</b> Every part is a thermal mass with natural convection and radiation to ambient
 *   and conduction to parts it is joined to; all dissipation is deposited as heat.</li>
 *   <li><b>Magnetic.</b> All coils and magnets are coupled through one {@link MagneticSystem}.</li>
 * </ol>
 */
public final class AssemblyCompiler {
    private AssemblyCompiler() {}

    static final double CONTACT_H = 5000;          // W/(m²K), dry metal–metal contact conductance
    static final double BRUSH_RESISTANCE = 0.02;   // Ω, graphite brush contact (≈ 0.2–1 V drop at rated current)
    static final double SWITCH_R_ON = 2e-3, SWITCH_R_OFF = 1e9;

    public static CompiledAssembly compile(Assembly a) {
        return compile(a, AssemblyState.empty());
    }

    public static CompiledAssembly compile(Assembly a, AssemblyState prev) {
        CompiledAssembly c = new CompiledAssembly(a);
        new Builder(a, c, prev).build();
        return c;
    }

    private static final class Builder {
        final Assembly a;
        final CompiledAssembly c;
        final AssemblyState prev;
        final List<Part> parts;
        final Map<Long, Integer> idx = new HashMap<>();
        int[] uf;
        final List<long[]> joints = new ArrayList<>();       // {bearingId, shaftId}
        final Set<Long> joinedPairs = new HashSet<>();        // structurally joined (thermal contact)
        final Node gndE, refR;
        final double ambientT;

        Builder(Assembly a, CompiledAssembly c, AssemblyState prev) {
            this.a = a;
            this.c = c;
            this.prev = prev;
            this.parts = new ArrayList<>(a.parts());
            for (int i = 0; i < parts.size(); i++) idx.put(parts.get(i).id(), i);
            gndE = c.network.reference(Domain.ELECTRICAL);
            refR = c.network.reference(Domain.ROTATIONAL);
            ambientT = a.ambientTemperature();
        }

        // ------------------------------------------------------------ union-find
        int find(int i) {
            while (uf[i] != i) {
                uf[i] = uf[uf[i]];
                i = uf[i];
            }
            return i;
        }

        void union(int i, int j) {
            int ri = find(i), rj = find(j);
            if (ri != rj) uf[ri] = rj;
        }

        static long pairKey(long x, long y) {
            return x < y ? x * 1_000_003L ^ y : y * 1_000_003L ^ x;
        }

        boolean isWire(Part p) {
            return p.kind() == PartKind.WIRE || p.kind() == PartKind.INSULATED_WIRE;
        }

        void build() {
            buildRigidGroups();
            buildBodies();
            buildThermal();
            buildMechanics();
            buildElectrical();
            c.network.compile();
            applyPreviousElectricalState();
            c.solver = new TransientSolver(c.network);
            String m = System.getProperty("emergence.method");
            if (m != null) c.solver.method(dev.emergence.core.sim.IntegrationMethod.valueOf(m));
        }

        // ------------------------------------------------------------ 1. rigid groups
        void buildRigidGroups() {
            uf = new int[parts.size()];
            for (int i = 0; i < uf.length; i++) uf[i] = i;
            for (Part p : parts) {
                if (isWire(p)) continue;
                for (Part q : a.neighbors(p)) {
                    if (q.id() < p.id() || isWire(q)) continue;
                    if (!p.kind().structural && !q.kind().structural) continue;
                    if (isBearingJoint(p, q)) {
                        joints.add(p.kind() == PartKind.BEARING ? new long[]{p.id(), q.id()} : new long[]{q.id(), p.id()});
                        joinedPairs.add(pairKey(p.id(), q.id()));
                        continue;
                    }
                    if (isBearingShaftNoJoint(p, q)) continue;
                    if (!shaftReaches(p, q) || !shaftReaches(q, p)) continue;
                    if (p.kind() == PartKind.GEAR && q.kind() == PartKind.GEAR) continue; // mesh, not weld
                    // A brush is spring-loaded in its holder and merely slides on what it touches: it never
                    // joins rigid bodies (brushes always belong to the stationary frame — see KNOWN_LIMITATIONS).
                    if (p.kind() == PartKind.BRUSH || q.kind() == PartKind.BRUSH) continue;
                    union(idx.get(p.id()), idx.get(q.id()));
                    joinedPairs.add(pairKey(p.id(), q.id()));
                }
            }
        }

        boolean isBearingJoint(Part p, Part q) {
            Part b = p.kind() == PartKind.BEARING ? p : q.kind() == PartKind.BEARING ? q : null;
            Part s = b == p ? q : p;
            if (b == null || s.kind() != PartKind.SHAFT) return false;
            return b.overlaps(s) && b.axis().axis() == s.axis().axis();
        }

        /**
         * A shaft thinner than its cells only physically reaches neighbours beyond its end faces, or parts
         * that overlap it (hubs, disks, gears mounted on it). Returns true if {@code s} is not a shaft or
         * it does reach {@code q}.
         */
        boolean shaftReaches(Part s, Part q) {
            if (s.kind() != PartKind.SHAFT || s.overlaps(q)) return true;
            if (PartGeometry.shaftDiameter(s) >= 0.9 * CELL) return true;
            Dir ax = s.axis();
            for (Dir d : new Dir[]{ax, ax.opposite()}) {
                for (int x = s.minX(); x <= s.maxX(); x++)
                    for (int y = s.minY(); y <= s.maxY(); y++)
                        for (int z = s.minZ(); z <= s.maxZ(); z++) {
                            int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                            if (!s.containsCell(nx, ny, nz) && q.containsCell(nx, ny, nz)) return true;
                        }
            }
            return false;
        }

        boolean isBearingShaftNoJoint(Part p, Part q) {
            return (p.kind() == PartKind.BEARING && q.kind() == PartKind.SHAFT && !p.overlaps(q))
                    || (q.kind() == PartKind.BEARING && p.kind() == PartKind.SHAFT && !q.overlaps(p));
        }

        /** True if {@code other} occupies a cell directly beyond the brush's contact (+axis) face. */
        boolean inFrontOf(Part brush, Part other) {
            Dir d = brush.axis();
            for (int x = brush.minX(); x <= brush.maxX(); x++)
                for (int y = brush.minY(); y <= brush.maxY(); y++)
                    for (int z = brush.minZ(); z <= brush.maxZ(); z++) {
                        int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                        if (!brush.containsCell(nx, ny, nz) && other.containsCell(nx, ny, nz)) return true;
                    }
            return false;
        }

        // ------------------------------------------------------------ 2. bodies
        final Map<Integer, CompiledAssembly.Body> bodyOfGroup = new HashMap<>();

        void buildBodies() {
            CompiledAssembly.Body ground = new CompiledAssembly.Body(0, true, Vec3.ZERO, null);
            c.bodies.add(ground);
            Map<Integer, List<Part>> groups = new LinkedHashMap<>();
            for (Part p : parts) if (!isWire(p)) groups.computeIfAbsent(find(idx.get(p.id())), k -> new ArrayList<>()).add(p);
            Set<Integer> anchored = new HashSet<>();
            for (var e : groups.entrySet()) for (Part p : e.getValue()) if (a.isAnchored(p)) anchored.add(e.getKey());
            for (int g : anchored) bodyOfGroup.put(g, ground);

            // joint graph between groups
            record J(int g1, int g2, Vec3 pivot, Vec3 axis, Part bearing) {}
            List<J> js = new ArrayList<>();
            for (long[] jt : joints) {
                Part b = a.get(jt[0]);
                Vec3 ax = b.axis().vec();
                if (ax.x() + ax.y() + ax.z() < 0) ax = ax.mul(-1);
                js.add(new J(find(idx.get(jt[0])), find(idx.get(jt[1])), b.center(), ax, b));
            }
            // Resolve rotors breadth-first from the static frame. If some groups are not connected to an
            // anchored body (a free-standing machine), the heaviest such group serves as their reference
            // frame and resolution continues from it.
            boolean progress = true;
            while (true) {
            progress = true;
            while (progress) {
                progress = false;
                for (int g : groups.keySet()) {
                    if (bodyOfGroup.containsKey(g)) continue;
                    List<J> toResolved = new ArrayList<>();
                    for (J j : js) {
                        int other = j.g1 == g ? j.g2 : j.g2 == g ? j.g1 : -1;
                        if (other >= 0 && other != g && bodyOfGroup.containsKey(other)) toResolved.add(j);
                    }
                    if (toResolved.isEmpty()) continue;
                    progress = true;
                    J first = toResolved.get(0);
                    boolean ok = true;
                    for (J j : toResolved) {
                        if (!collinear(first.pivot, first.axis, j.pivot, j.axis)) ok = false;
                        CompiledAssembly.Body parent = bodyOfGroup.get(j.g1 == g ? j.g2 : j.g1);
                        if (!parent.isStatic && !collinear(first.pivot, first.axis, parent.pivot, parent.axis)) ok = false;
                    }
                    if (!ok) {
                        c.warnings.add("Locked: a body is held by bearings that are not on one axis line");
                        bodyOfGroup.put(g, ground);
                        continue;
                    }
                    CompiledAssembly.Body rotor = new CompiledAssembly.Body(c.bodies.size(), false, first.pivot, first.axis);
                    c.bodies.add(rotor);
                    bodyOfGroup.put(g, rotor);
                }
            }
            Integer heaviest = null;
            double best = -1;
            for (var e : groups.entrySet()) {
                if (bodyOfGroup.containsKey(e.getKey())) continue;
                boolean jointed = false;
                for (J j : js) if (j.g1 == e.getKey() || j.g2 == e.getKey()) jointed = true;
                if (!jointed) continue;
                double m = 0;
                for (Part p : e.getValue()) m += PartGeometry.mass(p);
                if (m > best) {
                    best = m;
                    heaviest = e.getKey();
                }
            }
            if (heaviest == null) break;
            bodyOfGroup.put(heaviest, ground);
            }
            for (int g : groups.keySet()) {
                if (!bodyOfGroup.containsKey(g)) {
                    bodyOfGroup.put(g, ground); // unsupported or floating: rests on the world
                }
            }
            for (var e : groups.entrySet()) {
                CompiledAssembly.Body b = bodyOfGroup.get(e.getKey());
                for (Part p : e.getValue()) {
                    b.parts.add(p.id());
                    c.bodyOfPart.put(p.id(), b);
                }
            }
            assignWireBodies(ground);
        }

        boolean collinear(Vec3 p1, Vec3 a1, Vec3 p2, Vec3 a2) {
            if (Math.abs(Math.abs(a1.dot(a2)) - 1) > 1e-9) return false;
            Vec3 d = p2.sub(p1);
            return d.sub(a1.mul(d.dot(a1))).length() < 1e-6;
        }

        /** Wire networks take the body of the non-wire parts they touch (static if any is static). */
        void assignWireBodies(CompiledAssembly.Body ground) {
            Set<Long> seen = new HashSet<>();
            for (Part w : parts) {
                if (!isWire(w) || seen.contains(w.id())) continue;
                List<Part> net = new ArrayList<>();
                Deque<Part> q = new ArrayDeque<>(List.of(w));
                seen.add(w.id());
                Map<CompiledAssembly.Body, Integer> votes = new HashMap<>();
                while (!q.isEmpty()) {
                    Part p = q.poll();
                    net.add(p);
                    for (Part n : a.neighbors(p)) {
                        if (isWire(n)) {
                            if (seen.add(n.id())) q.add(n);
                        } else {
                            CompiledAssembly.Body b = c.bodyOfPart.get(n.id());
                            if (b != null) votes.merge(b, 1, Integer::sum);
                        }
                    }
                }
                CompiledAssembly.Body body = ground;
                if (!votes.isEmpty() && !votes.containsKey(ground)) {
                    body = Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey();
                }
                for (Part p : net) {
                    c.bodyOfPart.put(p.id(), body);
                    body.parts.add(p.id());
                }
            }
        }

        // ------------------------------------------------------------ 3. thermal
        void buildThermal() {
            c.ambient = c.network.node(Domain.THERMAL, "ambient", ambientT);
            c.network.add(new FixedTemperature(c.ambient, ambientT));
            for (Part p : parts) {
                double t0 = prev.temperatures().getOrDefault(p.id(), ambientT);
                Node n = c.network.node(Domain.THERMAL, "T" + p.id(), t0);
                c.thermalOfPart.put(p.id(), n);
                double cp = p.material().get(MaterialProperty.SPECIFIC_HEAT, 500);
                c.network.add(new HeatCapacity(n, Math.max(PartGeometry.mass(p) * cp, 1e-4)));
                double area = PartGeometry.surfaceArea(p);
                NaturalConvection.Shape shape = switch (p.kind()) {
                    case SHAFT, WIRE, INSULATED_WIRE -> NaturalConvection.Shape.HORIZONTAL_CYLINDER;
                    case BLOCK, GEAR -> NaturalConvection.Shape.VERTICAL_PLATE;
                    default -> NaturalConvection.Shape.SPHERE;
                };
                c.network.add(new NaturalConvection(n, c.ambient, shape, area, PartGeometry.convectionLength(p)));
                c.network.add(new Radiation(n, c.ambient, PartGeometry.emissivity(p), area));
                c.failureTemp.put(p.id(), PartGeometry.failureTemperature(p));
            }
        }

        void thermalLink(Part p, Part q) {
            double kp = p.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1);
            double kq = q.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1);
            double ap = PartGeometry.conductionArea(p), aq = PartGeometry.conductionArea(q);
            double r = (CELL / 2) / (kp * ap) + (CELL / 2) / (kq * aq) + 1 / (CONTACT_H * Math.min(ap, aq));
            c.network.add(new ThermalConductance(c.thermalOfPart.get(p.id()), c.thermalOfPart.get(q.id()), 1 / r));
        }

        // ------------------------------------------------------------ 4. mechanics
        void buildMechanics() {
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.isStatic) continue;
                double j = 0, m = 0;
                for (long id : b.parts) {
                    Part p = a.get(id);
                    if (isWire(p)) continue;
                    j += PartGeometry.inertiaAbout(p, b.pivot, b.axis);
                    m += PartGeometry.mass(p);
                }
                b.inertia = Math.max(j, 1e-7);
                b.mass = m;
                double[] motion = null;
                for (long id : b.parts) {
                    motion = prev.bodyMotion().get(id);
                    if (motion != null) break;
                }
                b.node = c.network.node(Domain.ROTATIONAL, "w" + b.index, motion == null ? 0 : motion[0]);
                c.network.add(new Inertia(b.node, b.inertia));
                b.tracker = c.network.add(new AngleTracker(b.node, motion == null ? 0 : motion[1]));
                b.drive = c.network.add(new FlowSource(refR, b.node, 0.0));
            }
            // bearings
            Map<CompiledAssembly.Body, Integer> bearingCount = new HashMap<>();
            for (long[] jt : joints) {
                CompiledAssembly.Body bb = c.bodyOfPart.get(jt[0]), sb = c.bodyOfPart.get(jt[1]);
                if (bb != sb) {
                    bearingCount.merge(bb.isStatic ? sb : bb, 1, Integer::sum);
                }
            }
            for (long[] jt : joints) {
                Part bearing = a.get(jt[0]), shaft = a.get(jt[1]);
                CompiledAssembly.Body bb = c.bodyOfPart.get(bearing.id()), sb = c.bodyOfPart.get(shaft.id());
                thermalLink(bearing, shaft);
                if (bb == sb) continue; // locked joint
                CompiledAssembly.Body rot = bb.isStatic ? sb : bb;
                double load = rot.mass * PartGeometry.gravity() / Math.max(1, bearingCount.getOrDefault(rot, 1));
                double d = PartGeometry.shaftDiameter(shaft);
                boolean plain = bearing.param("type", 0) >= 1;
                Node th = c.thermalOfPart.get(bearing.id());
                Node na = bb.isStatic ? refR : bb.node, nb = sb.isStatic ? refR : sb.node;
                double mu, cv;
                if (!plain) {
                    // SKF simplified friction model for a deep-groove ball bearing: M = ½·μ·P·d, μ ≈ 0.0015,
                    // plus a small viscous grease-drag term.
                    mu = 0.0015;
                    cv = 2e-7 * Math.pow(d / 0.01, 3);
                } else {
                    // Lubricated plain bushing: boundary friction μ·P·d/2 plus Petroff hydrodynamic drag
                    // c = μ_oil·2π·r³·L / clearance with SAE-30-like oil (0.1 Pa·s), clearance 0.1 % of d.
                    mu = bearing.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.15) * 0.4;
                    double r = d / 2, len = PartGeometry.axialLength(bearing);
                    cv = 0.1 * 2 * Math.PI * r * r * r * len / (0.001 * d);
                }
                c.network.add(new CoulombFriction(nb, na, 0.5 * mu * load * d, 1e-2, th));
                c.network.add(new Damper(nb, na, cv, th));
            }
            // gear meshes
            List<Part> gears = new ArrayList<>();
            for (Part p : parts) if (p.kind() == PartKind.GEAR) gears.add(p);
            for (int i = 0; i < gears.size(); i++) {
                for (int k = i + 1; k < gears.size(); k++) {
                    tryMesh(gears.get(i), gears.get(k));
                }
            }
            // thermal contact between structurally joined parts
            for (Part p : parts) {
                for (Part q : a.neighbors(p)) {
                    if (q.id() <= p.id()) continue;
                    if (joinedPairs.contains(pairKey(p.id(), q.id())) && !isBearingJointPair(p, q)) thermalLink(p, q);
                }
            }
            // cranks are where a person can put work in
            for (Part p : parts) {
                if (p.kind() != PartKind.CRANK) continue;
                CompiledAssembly.Body b = c.bodyOfPart.get(p.id());
                if (b.isStatic) {
                    c.warnings.add("Crank #" + p.id() + " is on a body that cannot rotate");
                    continue;
                }
                c.cranks.put(p.id(), c.network.add(new HumanCrank(b.node, refR, PartGeometry.crankRadius(p))));
            }
        }

        boolean isBearingJointPair(Part p, Part q) {
            return isBearingJoint(p, q);
        }

        void tryMesh(Part g1, Part g2) {
            CompiledAssembly.Body b1 = c.bodyOfPart.get(g1.id()), b2 = c.bodyOfPart.get(g2.id());
            if (b1 == b2 || g1.axis().axis() != g2.axis().axis()) return;
            int ax = g1.axis().axis();
            int lo1 = ax == 0 ? g1.minX() : ax == 1 ? g1.minY() : g1.minZ();
            int hi1 = ax == 0 ? g1.maxX() : ax == 1 ? g1.maxY() : g1.maxZ();
            int lo2 = ax == 0 ? g2.minX() : ax == 1 ? g2.minY() : g2.minZ();
            int hi2 = ax == 0 ? g2.maxX() : ax == 1 ? g2.maxY() : g2.maxZ();
            if (hi1 < lo2 || hi2 < lo1) return; // not in the same plane
            double r1 = PartGeometry.gearPitchRadius(g1), r2 = PartGeometry.gearPitchRadius(g2);
            int z1 = PartGeometry.gearTeeth(g1), z2 = PartGeometry.gearTeeth(g2);
            double m1 = 2 * r1 / z1, m2 = 2 * r2 / z2;
            if (Math.abs(m1 - m2) > 0.1 * Math.max(m1, m2)) {
                c.warnings.add("Gears #" + g1.id() + " and #" + g2.id() + " have different tooth modules and cannot mesh");
                return;
            }
            Vec3 d = g2.center().sub(g1.center());
            Vec3 axis = Vec3.axis(ax);
            double dist = d.sub(axis.mul(d.dot(axis))).length();
            if (Math.abs(dist - (r1 + r2)) > 0.3 * m1) return;
            // Axis direction consistency: each rotor's ω is measured about its own (positive) axis vector.
            CompiledAssembly.Body ra = b1, rb = b2;
            Node na = ra.isStatic ? refR : ra.node, nb = rb.isStatic ? refR : rb.node;
            double s1 = ra.isStatic ? 1 : Math.signum(ra.axis.dot(axis));
            double s2 = rb.isStatic ? 1 : Math.signum(rb.axis.dot(axis));
            if (s1 * s2 < 0) {
                c.warnings.add("Gear mesh with opposed axis conventions not supported");
                return;
            }
            boolean lub = g1.param("lubricated", 1) > 0 || g2.param("lubricated", 1) > 0;
            double mu = lub ? 0.05 : Math.max(g1.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4),
                    g2.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4));
            c.network.add(new GearMesh(na, nb, r1, r2, z1, z2, false, mu, c.thermalOfPart.get(g1.id())));
            thermalLink(g1, g2);
        }

        // ------------------------------------------------------------ 5. electrical
        final Map<String, Node> enodes = new HashMap<>();
        final Map<String, String> eunion = new HashMap<>();

        String eroot(String k) {
            String r = k;
            while (eunion.containsKey(r)) r = eunion.get(r);
            return r;
        }

        void emerge(String x, String y) {
            String rx = eroot(x), ry = eroot(y);
            if (!rx.equals(ry)) eunion.put(rx, ry);
        }

        Node enode(String key) {
            return enodes.computeIfAbsent(eroot(key), k -> c.network.node(Domain.ELECTRICAL, k));
        }

        boolean conductive(Part p) {
            return conductorMaterial(p).isConductor();
        }

        boolean isBulk(Part p) {
            return p.kind().structural || p.kind() == PartKind.MAGNET;
        }

        /** Port key of part p on face d of cell (x,y,z), or null if p has no electrical port there. */
        String port(Part p, int x, int y, int z, Dir d) {
            switch (p.kind()) {
                case WIRE, INSULATED_WIRE -> {
                    return "p" + p.id();
                }
                case RESISTOR, CAPACITOR, DIODE, SWITCH, COIL, BRUSH -> {
                    Dir ax = p.axis();
                    int a0 = ax.axis();
                    int[] cell = {x, y, z};
                    int lo = a0 == 0 ? p.minX() : a0 == 1 ? p.minY() : p.minZ();
                    int hi = a0 == 0 ? p.maxX() : a0 == 1 ? p.maxY() : p.maxZ();
                    boolean posAxis = ax.sign() > 0;
                    if (p.kind() == PartKind.BRUSH && d == ax) return null; // the brush face slides: see buildBrushContacts
                    if (d.axis() == a0) {
                        // end faces: lead exits at the transverse centre of the end face
                        if (!isLeadCell(p, cell)) return null;
                        if (d.sign() > 0 && cell[a0] == hi) return "p" + p.id() + (posAxis ? "+" : "-");
                        if (d.sign() < 0 && cell[a0] == lo) return "p" + p.id() + (posAxis ? "-" : "+");
                        return null;
                    }
                    if (p.kind() == PartKind.BRUSH) return null;
                    if (hi != lo) {
                        // multi-cell part: side faces of the two end layers reach that end's lead
                        if (cell[a0] == hi) return "p" + p.id() + (posAxis ? "+" : "-");
                        if (cell[a0] == lo) return "p" + p.id() + (posAxis ? "-" : "+");
                        return null;
                    }
                    // single-cell part: its leads are brought out sideways along the lead axis L
                    // (− lead on the −L face, + lead on the +L face), as on a real coil former
                    if (d.axis() == PartGeometry.leadAxis(p)) return "p" + p.id() + (d.sign() > 0 ? "+" : "-");
                    return null;
                }
                default -> {
                    return conductive(p) ? "p" + p.id() : null;
                }
            }
        }

        /** Leads are at the transverse centre cell of each end face. */
        boolean isLeadCell(Part p, int[] cell) {
            int a0 = p.axis().axis();
            for (int k = 0; k < 3; k++) {
                if (k == a0) continue;
                int lo = k == 0 ? p.minX() : k == 1 ? p.minY() : p.minZ();
                int ext = p.extent(k);
                if (cell[k] != lo + ext / 2) return false;
            }
            return true;
        }

        void buildElectrical() {
            // ---- discover contacts
            record Contact(Part p, String pk, Part q, String qk, boolean sliding) {}
            List<Contact> contacts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Part p : parts) {
                if (!(conductive(p) || p.kind().leaded)) continue;
                for (int x = p.minX(); x <= p.maxX(); x++)
                    for (int y = p.minY(); y <= p.maxY(); y++)
                        for (int z = p.minZ(); z <= p.maxZ(); z++)
                            for (Dir d : Dir.values()) {
                                int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                                if (p.containsCell(nx, ny, nz)) continue;
                                String pk = port(p, x, y, z, d);
                                if (pk == null) continue;
                                for (Part q : a.at(nx, ny, nz)) {
                                    if (q == p) continue;
                                    String qk = port(q, nx, ny, nz, d.opposite());
                                    boolean sliding = false;
                                    if (qk == null) continue;
                                    if (!compatible(p, q)) continue;
                                    // a thin shaft physically touches neighbours only at its ends (or hubs on it)
                                    if (!shaftReaches(p, q) || !shaftReaches(q, p)) continue;
                                    CompiledAssembly.Body bp = c.bodyOfPart.get(p.id()), bq = c.bodyOfPart.get(q.id());
                                    if (bp != bq) {
                                        // only a brush may conduct across a moving joint, through its contact face
                                        Part brush = p.kind() == PartKind.BRUSH ? p : q.kind() == PartKind.BRUSH ? q : null;
                                        if (brush == null) continue;
                                        Part other = brush == p ? q : p;
                                        if (!isBulk(other) || !inFrontOf(brush, other)) continue;
                                        sliding = true;
                                    }
                                    String key = pk.compareTo(qk) < 0 ? pk + "|" + qk : qk + "|" + pk;
                                    if (!seen.add(key)) continue;
                                    contacts.add(new Contact(p, pk, q, qk, sliding));
                                }
                            }
            }
            // ---- zero-resistance joins (lead to lead)
            for (Contact k : contacts) {
                if (!k.sliding && isLeadKey(k.pk) && isLeadKey(k.qk)) emerge(k.pk, k.qk);
            }
            // ---- conductors between contacts
            for (Contact k : contacts) {
                Part p = k.p, q = k.q;
                Node np = enode(k.pk), nq = enode(k.qk);
                if (np == nq) continue;
                if (k.sliding) {
                    Part brush = p.kind() == PartKind.BRUSH ? p : q;
                    c.network.add(new Resistor(np, nq, brush.param("contact_resistance", BRUSH_RESISTANCE), c.thermalOfPart.get(brush.id())));
                    continue;
                }
                double rHalfP = halfLength(p), rHalfQ = halfLength(q);
                if (rHalfP >= CELL / 2 && rHalfQ >= CELL / 2) {
                    // two bulk/wire conductors: split at the contact face so each half's Joule heat goes
                    // into its own part (exact heat allocation, e.g. along a filament)
                    Node mid = enode("m" + k.pk + "|" + k.qk);
                    c.network.add(new Conductor(np, mid, conductorMaterial(p), rHalfP, conductorArea(p),
                            c.thermalOfPart.get(p.id()), ambientT));
                    c.network.add(new Conductor(mid, nq, conductorMaterial(q), rHalfQ, conductorArea(q),
                            c.thermalOfPart.get(q.id()), ambientT));
                    if (!joinedPairs.contains(pairKey(p.id(), q.id())) && c.bodyOfPart.get(p.id()) == c.bodyOfPart.get(q.id())) {
                        thermalLink(p, q);
                        joinedPairs.add(pairKey(p.id(), q.id()));
                    }
                    continue;
                }
                // series conductor halves; the dominant (more resistive) side receives the heat
                Part dom = segmentResistance(p, rHalfP) >= segmentResistance(q, rHalfQ) ? p : q;
                double rTotal20 = segmentResistance(p, rHalfP) + segmentResistance(q, rHalfQ);
                Material m = conductorMaterial(dom);
                double areaEff = m.resistivity(293.15) * (rHalfP + rHalfQ) / Math.max(rTotal20, 1e-12);
                c.network.add(new Conductor(np, nq, m, rHalfP + rHalfQ, areaEff, c.thermalOfPart.get(dom.id()), ambientT));
                if (!joinedPairs.contains(pairKey(p.id(), q.id())) && c.bodyOfPart.get(p.id()) == c.bodyOfPart.get(q.id())) {
                    thermalLink(p, q);
                    joinedPairs.add(pairKey(p.id(), q.id()));
                }
            }
            // ---- devices
            for (Part p : parts) {
                Node th = c.thermalOfPart.get(p.id());
                switch (p.kind()) {
                    case RESISTOR -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Resistor(l[0], l[1], p.param("resistance", 10), th)));
                    }
                    case SWITCH -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Switch(l[0], l[1], SWITCH_R_ON, SWITCH_R_OFF, p.switchClosed(), th)));
                    }
                    case DIODE -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Diode(l[0], l[1], p.param("saturation_current", 1e-14),
                                p.param("ideality", 1.0), p.material().get(MaterialProperty.BAND_GAP, 1.12 * 1.602e-19) / 1.602176634e-19, th)));
                    }
                    case CAPACITOR -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Capacitor(l[1], l[0], p.param("capacitance", 1e-4))));
                    }
                    case WIRE, INSULATED_WIRE -> c.leadsOfPart.put(p.id(), new Node[]{enode("p" + p.id())});
                    default -> {
                        if (conductive(p) && enodes.containsKey(eroot("p" + p.id()))) c.leadsOfPart.put(p.id(), new Node[]{enode("p" + p.id())});
                    }
                }
            }
            buildBrushContacts();
            buildElectrochemistry();
            buildMagnetics();
        }

        /**
         * Electrolytes: every conductor touching an electrolyte body becomes an electrode of that body;
         * touching electrolyte bodies are joined by ionic conduction (a porous partition / salt bridge).
         */
        void buildElectrochemistry() {
            var db = dev.emergence.core.chem.Electrochemistry.standard();
            for (Part el : parts) {
                if (el.kind() != PartKind.ELECTROLYTE) continue;
                var sol = db.solution(el.material().id());
                if (sol == null) continue;
                double litres = PartGeometry.volume(el) * 1000;
                Node bulk = enode("e" + el.id());
                ElectrochemicalCell cell = new ElectrochemicalCell(bulk, c.thermalOfPart.get(el.id()), litres, sol.species());
                String sPrefix = "S" + el.id() + ":";
                for (var k : prev.chemistry().entrySet()) {
                    if (k.getKey().startsWith(sPrefix)) cell.setInitialMoles(k.getKey().substring(sPrefix.length()), k.getValue());
                }
                double kappa = sol.conductivity();
                for (Part q : a.neighbors(el)) {
                    if (q.kind() == PartKind.ELECTROLYTE) {
                        if (q.id() < el.id()) continue;
                        var s2 = db.solution(q.material().id());
                        if (s2 == null) continue;
                        double area = sharedFaces(el, q) * CELL * CELL;
                        double r = (halfDepth(el, q) / kappa + halfDepth(q, el) / s2.conductivity()) / Math.max(area, 1e-8);
                        c.network.add(new Resistor(bulk, enode("e" + q.id()), r));
                        thermalLink(el, q);
                        continue;
                    }
                    // insulated wire's jacket keeps its copper out of the liquid
                    if (!conductive(q) || q.kind() == PartKind.INSULATED_WIRE || (q.kind().leaded && !isWire(q))) continue;
                    double faces = sharedFaces(el, q);
                    if (faces == 0) continue;
                    double area = isWire(q) ? faces * Math.PI * PartGeometry.wireDiameter(q) * CELL
                            : faces * CELL * CELL * (q.kind() == PartKind.BLOCK ? Math.min(1, q.param("fill", 1.0) + 0.2) : 1);
                    String mat = q.material().id();
                    double mm = q.material().get(MaterialProperty.MOLAR_MASS, 0);
                    double metalMoles = mm > 0 ? PartGeometry.mass(q) / mm : 0;
                    var electrode = cell.addElectrode(enode("p" + q.id()), c.thermalOfPart.get(q.id()), mat, area,
                            halfDepth(el, q), kappa, metalMoles, q.id());
                    Double prevMetal = prev.chemistry().get("M" + q.id() + ":" + el.id());
                    if (prevMetal != null) cell.setRemainingMetal(electrode, prevMetal);
                    String dPrefix = "D" + q.id() + ":";
                    for (var k : prev.chemistry().entrySet()) {
                        if (k.getKey().startsWith(dPrefix)) cell.setDeposit(q.id() + ":" + k.getKey().substring(dPrefix.length()), k.getValue());
                    }
                    c.electrodes.computeIfAbsent(q.id(), x -> new ArrayList<>()).add(new Object[]{cell, electrode, el.id()});
                    if (!c.leadsOfPart.containsKey(q.id())) c.leadsOfPart.put(q.id(), new Node[]{enode("p" + q.id())});
                    thermalLink(el, q);
                }
                c.network.add(cell);
                c.cells.put(el.id(), cell);
                c.leadsOfPart.put(el.id(), new Node[]{bulk});
            }
        }

        /** Number of cell faces shared between two parts. */
        int sharedFaces(Part p, Part q) {
            int n = 0;
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int y = p.minY(); y <= p.maxY(); y++)
                    for (int z = p.minZ(); z <= p.maxZ(); z++)
                        for (Dir d : Dir.values()) {
                            int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                            if (!p.containsCell(nx, ny, nz) && q.containsCell(nx, ny, nz)) n++;
                        }
            return n;
        }

        /** Ionic path from the faces shared with q to the middle of electrolyte el [m]. */
        double halfDepth(Part el, Part q) {
            Vec3 d = q.center().sub(el.center());
            int ax = Math.abs(d.x()) >= Math.abs(d.y()) && Math.abs(d.x()) >= Math.abs(d.z()) ? 0 : Math.abs(d.y()) >= Math.abs(d.z()) ? 1 : 2;
            return Math.max(el.boxSize(ax) / 2, CELL / 4);
        }

        /**
         * Brush contacts. The brush face (+axis end) is sampled by a 5×5 grid of points just in front of
         * it. For each conductive part on a rotor, the fraction of those points inside the part (seen in
         * the rotor's frame at angle θ) gives the contact coverage c(θ) over a revolution; commutators and
         * slip rings are nothing more than the resulting coverage patterns.
         */
        void buildBrushContacts() {
            for (Part br : parts) {
                if (br.kind() != PartKind.BRUSH) continue;
                CompiledAssembly.Body bb = c.bodyOfPart.get(br.id());
                Node back = enode("p" + br.id() + "-");
                double rc = br.param("contact_resistance", BRUSH_RESISTANCE);
                Node th = c.thermalOfPart.get(br.id());
                // sample points on the brush face, 0.25 cell in front of it
                Dir ax = br.axis();
                int a0 = ax.axis(), t1 = (a0 + 1) % 3, t2 = (a0 + 2) % 3;
                Vec3 ctr = br.center();
                double front = br.boxSize(a0) / 2 + 0.25 * CELL;
                java.util.List<Vec3> pts = new ArrayList<>();
                for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) {
                    double u = (i - 2) / 4.0 * 0.6 * br.boxSize(t1), v = (j - 2) / 4.0 * 0.6 * br.boxSize(t2);
                    pts.add(ctr.add(ax.vec().mul(front)).add(Vec3.axis(t1).mul(u)).add(Vec3.axis(t2).mul(v)));
                }
                for (Part q : parts) {
                    if (q == br || !isBulk(q) || !conductive(q)) continue;
                    CompiledAssembly.Body qb = c.bodyOfPart.get(q.id());
                    if (qb == bb || qb.isStatic) {
                        if (qb == bb && inFrontOf(br, q)) {
                            c.network.add(new Resistor(back, enode("p" + q.id()), rc, th));
                        }
                        continue;
                    }
                    if (!bb.isStatic) continue; // brush on a different moving body: not supported yet
                    int n = 720;
                    double[] cov = new double[n];
                    double max = 0;
                    for (int k = 0; k < n; k++) {
                        double theta = 2 * Math.PI * k / n;
                        int inside = 0;
                        for (Vec3 pt : pts) {
                            Vec3 local = pt.sub(qb.pivot).rotateAbout(qb.axis, -theta).add(qb.pivot);
                            if (insideBox(q, local)) inside++;
                        }
                        cov[k] = inside / (double) pts.size();
                        max = Math.max(max, cov[k]);
                    }
                    if (max == 0) continue;
                    c.network.add(new SlidingContact(back, enode("p" + q.id()), qb.tracker, 1.0,
                            new dev.emergence.core.math.PeriodicSpline(cov), rc, th));
                }
                c.leadsOfPart.put(br.id(), new Node[]{back});
            }
        }

        static boolean insideBox(Part q, Vec3 p) {
            return p.x() >= q.minX() * CELL && p.x() <= (q.maxX() + 1) * CELL
                    && p.y() >= q.minY() * CELL && p.y() <= (q.maxY() + 1) * CELL
                    && p.z() >= q.minZ() * CELL && p.z() <= (q.maxZ() + 1) * CELL;
        }

        boolean isLeadKey(String k) {
            return k.endsWith("+") || k.endsWith("-");
        }

        Node[] leads(Part p) {
            Node[] l = {enode("p" + p.id() + "-"), enode("p" + p.id() + "+")};
            c.leadsOfPart.put(p.id(), l);
            return l;
        }

        /** Conduction length from a part's electrical node to its contact face [m] (0 for leads). */
        double halfLength(Part p) {
            if (p.kind().leaded && p.kind() != PartKind.WIRE && p.kind() != PartKind.INSULATED_WIRE) return 1e-4;
            return CELL / 2;
        }

        double segmentResistance(Part p, double len) {
            return conductorMaterial(p).resistivity(293.15) * len / conductorArea(p);
        }

        double conductorArea(Part p) {
            return switch (p.kind()) {
                case WIRE, INSULATED_WIRE -> PartGeometry.wireArea(p);
                case SHAFT -> Math.PI * Math.pow(PartGeometry.shaftDiameter(p) / 2, 2);
                case COIL -> Math.PI * Math.pow(PartGeometry.coilWireDiameter(p) / 2, 2);
                case RESISTOR, CAPACITOR, DIODE, SWITCH, BRUSH -> 1e-6;
                default -> CELL * CELL;
            };
        }

        /**
         * Material that actually carries current at a part's contacts: manufactured components
         * (resistor, capacitor, diode, switch) have tinned-copper leads whatever their body is made of;
         * a brush conducts through its graphite.
         */
        Material conductorMaterial(Part p) {
            return switch (p.kind()) {
                case RESISTOR, CAPACITOR, DIODE, SWITCH -> MaterialDatabase.standard().get("copper");
                default -> p.material();
            };
        }

        boolean compatible(Part p, Part q) {
            // insulation: an insulated wire only connects to wires and component leads
            if (p.kind() == PartKind.INSULATED_WIRE && !(q.kind().leaded)) return false;
            if (q.kind() == PartKind.INSULATED_WIRE && !(p.kind().leaded)) return false;
            boolean pc = conductive(p) || p.kind().leaded, qc = conductive(q) || q.kind().leaded;
            return pc && qc;
        }

        // ------------------------------------------------------------ 6. magnetics
        void buildMagnetics() {
            List<Part> coils = new ArrayList<>(), magnets = new ArrayList<>();
            for (Part p : parts) {
                if (p.kind() == PartKind.COIL) coils.add(p);
                if (p.kind() == PartKind.MAGNET && p.material().has(MaterialProperty.REMANENCE)) magnets.add(p);
            }
            if (coils.isEmpty() && magnets.size() < 2) return;
            MagneticSystem ms = new MagneticSystem().tableResolution(256);
            Map<CompiledAssembly.Body, EmBody> em = new HashMap<>();
            for (CompiledAssembly.Body b : c.bodies) {
                em.put(b, b.isStatic ? EmBody.fixed("frame") : EmBody.rotor("rotor" + b.index, b.pivot, b.axis, b.node, b.tracker));
            }
            Material copper = MaterialDatabase.standard().get("copper");
            for (Part p : coils) {
                Node[] l = leads(p);
                MagneticSystem.Coil coil = ms.addCoil("coil" + p.id(), PartGeometry.coil(p), em.get(c.bodyOfPart.get(p.id())),
                        l[0], l[1], p.material().isConductor() ? p.material() : copper, PartGeometry.coilWireDiameter(p),
                        c.thermalOfPart.get(p.id()));
                c.deviceOfPart.put(p.id(), coil);
            }
            for (Part p : magnets) ms.addMagnet(PartGeometry.magnet(p), em.get(c.bodyOfPart.get(p.id())));
            c.magnetics = c.network.add(ms);
            c.warnings.addAll(ms.warnings());
        }

        void applyPreviousElectricalState() {
            for (var e : c.deviceOfPart.entrySet()) {
                if (e.getValue() instanceof MagneticSystem.Coil coil) {
                    Double i = prev.coilCurrents().get(e.getKey());
                    if (i != null) c.network.setInitial(coil.currentIndex(), i);
                }
                if (e.getValue() instanceof Capacitor) {
                    Double v = prev.capacitorVoltages().get(e.getKey());
                    Node[] l = c.leadsOfPart.get(e.getKey());
                    if (v != null && l != null && l[1].index() >= 0) c.network.setInitial(l[1].index(), v);
                }
            }
        }
    }
}
