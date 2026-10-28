package dev.emergence.core.assembly;

import dev.emergence.core.em.CoilGeometry;
import dev.emergence.core.em.EmBody;
import dev.emergence.core.em.MagneticSystem;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.core.digital.DigitalChip;
import dev.emergence.core.digital.DigitalComponent;
import dev.emergence.core.digital.DigitalDomain;
import dev.emergence.core.digital.DisplayPanel;
import dev.emergence.core.digital.MsiComponent;
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
    /** Solid conductors in contact below this resistance [Ω] share one electrical node. */
    static final double BULK_MERGE_OHMS = 1e-4;

    public static CompiledAssembly compile(Assembly a) {
        return compile(a, AssemblyState.empty());
    }

    public static CompiledAssembly compile(Assembly a, AssemblyState prev) {
        CompiledAssembly c = new CompiledAssembly(a);
        new Builder(a, c, prev).build();
        return c;
    }

    /**
     * Thrown by a compile whose thread is interrupted: one no longer wanted (in the game, the machine changed again while
     * it compiled off the server thread). Checked between the compiler's phases and its costlier steps.
     */
    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("compile cancelled", null, false, false);
        }
    }

    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    /**
     * True if part p makes electrical contact through face d of its cell (x, y, z): a wire, a component's lead or
     * terminal there, or a conductor (renderers use this to draw leads only where they connect).
     */
    public static boolean hasElectricalPort(Part p, int x, int y, int z, Dir d) {
        return Builder.port(p, x, y, z, d) != null;
    }

    /**
     * The lead a two-terminal component brings out through face d of its cell (x, y, z): "+", "−", or null (no lead
     * there) — for telling a player where a wire would join it.
     */
    public static String leadAt(Part p, int x, int y, int z, Dir d) {
        String k = Builder.port(p, x, y, z, d);
        if (k == null) return null;
        return k.endsWith("+") ? "+" : k.endsWith("-") ? "−" : null;
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
        final Node gndE, refR, refT;
        final double ambientT;

        Builder(Assembly a, CompiledAssembly c, AssemblyState prev) {
            this.a = a;
            this.c = c;
            this.prev = prev;
            this.parts = new ArrayList<>(a.parts());
            for (int i = 0; i < parts.size(); i++) idx.put(parts.get(i).id(), i);
            gndE = c.network.reference(Domain.ELECTRICAL);
            c.network.electricalUngrounded(); // nothing a player builds touches it (the solver grounds each circuit once)
            refR = c.network.reference(Domain.ROTATIONAL);
            refT = c.network.reference(Domain.TRANSLATIONAL);
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
            buildWireRuns();
            buildThermal();
            checkpoint();
            buildMechanics();
            buildScaleLinks();
            buildAero();
            buildBrakes();
            checkpoint();
            buildFluids();
            buildJets();
            checkpoint();
            buildElectrical();
            buildDisplays();
            buildAbsorbedLight();
            c.fatigue = ShaftFatigue.build(a, c, prev);
            checkpoint();
            c.network.compile(); // (the magnet field tables)
            checkpoint();
            applyPreviousElectricalState();
            // the thermal network stepped apart from the rest (see TransientSolver#multirate); -Demergence.multirate=false
            // steps everything together
            c.solver = new TransientSolver(c.network).multirate(!"false".equals(System.getProperty("emergence.multirate")));
            // Switching electronics have sub-nanosecond modes (gate input capacitance against output resistance; an
            // LED's 20 pF junction against its series resistor) that a millisecond step cannot resolve: the
            // trapezoidal rule rings on them (its amplification → −1), so such islands integrate with L-stable BDF2,
            // which damps them. Other islands keep the trapezoidal rule, which conserves the energy of mechanical and
            // electromagnetic oscillations.
            for (Part p : parts) {
                if (p.kind() == PartKind.CHIP || p.kind() == PartKind.LOGIC || p.kind() == PartKind.TRANSISTOR || p.kind() == PartKind.OPAMP
                        || p.kind() == PartKind.LED) {
                    c.solver.method(dev.emergence.core.sim.IntegrationMethod.BDF2);
                    break;
                }
            }
            String m = System.getProperty("emergence.method");
            if (m != null) c.solver.method(dev.emergence.core.sim.IntegrationMethod.valueOf(m));
            // the passages' flows as they were (from a saved state): the first step starts from them, not from none
            boolean flows = false;
            for (int i = 0; i < c.solver.unknowns(); i++) {
                Double q = prev.chemistry().get(AssemblyState.flowKey(c.network.unknownName(i)));
                if (q == null) continue;
                c.solver.setState(i, q);
                flows = true;
            }
            if (flows) c.solver.resyncHistory();
        }

        /** Every part with a thermal node can absorb light (sunlight set by {@link CompiledAssembly#setDaylight}). */
        void buildAbsorbedLight() {
            List<Node> nodes = new ArrayList<>();
            for (Part p : parts) {
                Node n = c.thermalOfPart.get(p.id());
                if (n == null) continue;
                c.lightIndex.put(p.id(), nodes.size());
                nodes.add(n);
            }
            if (!nodes.isEmpty()) c.absorbedLight = c.network.add(new dev.emergence.core.sim.elements.AbsorbedLight(nodes));
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
                        joints.add(isJournal(p) ? new long[]{p.id(), q.id()} : new long[]{q.id(), p.id()});
                        joinedPairs.add(pairKey(p.id(), q.id()));
                        continue;
                    }
                    if (isBearingShaftNoJoint(p, q)) continue;
                    if (turnsInBearing(p, q) || turnsInBearing(q, p)) continue;
                    if (!shaftReaches(p, q) || !shaftReaches(q, p)) continue;
                    if (p.kind() == PartKind.GEAR && q.kind() == PartKind.GEAR) continue; // mesh, not weld
                    if ((p.kind() == PartKind.GEAR && q.kind() == PartKind.RACK) || (p.kind() == PartKind.RACK && q.kind() == PartKind.GEAR)) continue;
                    // a nut screws along its shaft: it turns on the thread instead of being fixed to it (nor to the
                    // shaft's neighbouring segments, end-on against it)
                    if ((p.kind() == PartKind.NUT && onAxisLine(q, p)) || (q.kind() == PartKind.NUT && onAxisLine(p, q))) continue;
                    if (isGland(p, q) || isGland(q, p)) continue; // shaft through a pipe's seal
                    // a bladed rotor spins inside its housing without touching it
                    if ((p.kind() == PartKind.ROTOR && (q.kind().holdsFluid() || q.kind() == PartKind.ROTOR))
                            || (q.kind() == PartKind.ROTOR && p.kind().holdsFluid())) continue;
                    // a crank throw is linked to cylinders only through connecting rods
                    if ((p.kind() == PartKind.ECCENTRIC && (q.kind() == PartKind.CYLINDER || q.kind() == PartKind.VALVE))
                            || (q.kind() == PartKind.ECCENTRIC && (p.kind() == PartKind.CYLINDER || p.kind() == PartKind.VALVE))) continue;
                    // A brush is spring-loaded in its holder and merely slides on what it touches: it never
                    // joins rigid bodies (brushes always belong to the stationary frame — see KNOWN_LIMITATIONS).
                    if (p.kind() == PartKind.BRUSH || q.kind() == PartKind.BRUSH) continue;
                    if (p.kind() == PartKind.BRAKE || q.kind() == PartKind.BRAKE) continue; // a pad presses, it does not weld
                    if (p.kind() == PartKind.SPRING || q.kind() == PartKind.SPRING) continue; // a spring yields, it does not weld
                    if (p.kind() == PartKind.LINK || q.kind() == PartKind.LINK) continue; // a link's ends are pins
                    joinedPairs.add(pairKey(p.id(), q.id()));
                    if (p.kind().structural && q.kind().structural) union(idx.get(p.id()), idx.get(q.id()));
                    else mounts.computeIfAbsent(p.kind().structural ? q : p, k -> new ArrayList<>()).add(p.kind().structural ? p : q);
                }
            }
            // a component (coil, magnet, chip …) is mounted on one structure: it joins the one it touches that is fixed to the
            // ground if there is one, else the first — it never bridges two (a coil over a relay's armature does not weld the
            // armature to the coil's bracket)
            Set<Integer> grounded = new HashSet<>();
            for (Part p : parts) if (a.isAnchored(p) && !isWire(p)) grounded.add(find(idx.get(p.id())));
            for (var m : mounts.entrySet()) {
                int pick = -1;
                for (Part q : m.getValue()) {
                    int r = find(idx.get(q.id()));
                    if (pick < 0 || (grounded.contains(r) && !grounded.contains(pick))) pick = r;
                }
                if (pick >= 0) {
                    union(idx.get(m.getKey().id()), pick);
                    if (grounded.contains(pick)) grounded.add(find(pick));
                }
            }
        }

        /** Non-structural parts and the structural parts they touch (their candidate mounts). */
        final Map<Part, List<Part>> mounts = new LinkedHashMap<>();

        /**
         * A shaft passing through a pipe, valve or tank runs in a gland seal, and a shaft end abutting one is
         * merely close to it: neither forms a rigid joint (only mounted rotors ride on shafts).
         */
        boolean isGland(Part s, Part q) {
            return s.kind() == PartKind.SHAFT && q.kind().holdsFluid() && q.kind() != PartKind.ROTOR;
        }

        /** A part a shaft turns in: a bearing, or the housing of a potentiometer. */
        static boolean isJournal(Part p) {
            return p.kind() == PartKind.BEARING || p.kind() == PartKind.POTENTIOMETER || p.kind() == PartKind.LINEAR_BEARING;
        }

        boolean isBearingJoint(Part p, Part q) {
            Part b = isJournal(p) ? p : isJournal(q) ? q : null;
            Part s = b == p ? q : p;
            if (b == null || s.kind() != PartKind.SHAFT) return false;
            return b.overlaps(s) && b.axis().axis() == s.axis().axis();
        }

        /** True if s is a shaft whose axis line runs through nut n's cell, along n's axis. */
        static boolean onAxisLine(Part s, Part n) {
            if (s.kind() != PartKind.SHAFT || s.axis().axis() != n.axis().axis()) return false;
            int a = n.axis().axis();
            for (int k = 0; k < 3; k++) {
                if (k == a) continue;
                int sl = k == 0 ? s.minX() : k == 1 ? s.minY() : s.minZ(), nl = k == 0 ? n.minX() : k == 1 ? n.minY() : n.minZ();
                if (sl != nl) return false;
            }
            return true;
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

        /**
         * True if {@code w} turns with the shaft that bearing {@code b} carries: it sits against the bearing along its
         * axis and is fixed to that shaft (a gear, crank, hub or flywheel beside the bearing), so it is not also fixed to
         * the bearing's housing — which would lock the shaft to the frame.
         */
        boolean turnsInBearing(Part b, Part w) {
            if (!isJournal(b) || isJournal(w) || w.kind() == PartKind.SHAFT || !w.kind().structural) return false;
            if (!besideAlongAxis(b, w)) return false;
            for (Part s : a.neighbors(b)) {
                if (!isBearingJoint(b, s)) continue;
                if ((s.overlaps(w) || a.neighbors(s).contains(w)) && shaftReaches(s, w) && shaftReaches(w, s)) return true;
            }
            return false;
        }

        /** True if {@code w} occupies a cell next to {@code b} along {@code b}'s axis. */
        static boolean besideAlongAxis(Part b, Part w) {
            Dir ax = b.axis();
            for (Dir d : new Dir[]{ax, ax.opposite()})
                for (int x = b.minX(); x <= b.maxX(); x++)
                    for (int y = b.minY(); y <= b.maxY(); y++)
                        for (int z = b.minZ(); z <= b.maxZ(); z++) {
                            int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                            if (!b.containsCell(nx, ny, nz) && w.containsCell(nx, ny, nz)) return true;
                        }
            return false;
        }

        boolean isBearingShaftNoJoint(Part p, Part q) {
            return (isJournal(p) && q.kind() == PartKind.SHAFT && !p.overlaps(q))
                    || (isJournal(q) && p.kind() == PartKind.SHAFT && !q.overlaps(p));
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
            record J(int g1, int g2, Vec3 pivot, Vec3 axis, Part bearing) {
                boolean prismatic() {
                    return bearing.kind() == PartKind.LINEAR_BEARING;
                }
            }
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
                    boolean ok = true, slide = first.prismatic();
                    CompiledAssembly.Body carrier = null;
                    for (J j : toResolved) {
                        CompiledAssembly.Body parent = bodyOfGroup.get(j.g1 == g ? j.g2 : j.g1);
                        if (j.prismatic() != slide) ok = false;
                        if (slide) {
                            // a slide: parallel guides (several bushings on one rod, or on parallel rods), all on the
                            // frame or all on one turning body (a weight on a spinning arm: carried by it)
                            if (Math.abs(Math.abs(first.axis.dot(j.axis)) - 1) > 1e-9 || parent.slider || parent.carrier != null) ok = false;
                            CompiledAssembly.Body on = parent.isStatic ? null : parent;
                            if (j == first) carrier = on;
                            else if (on != carrier) ok = false;
                            continue;
                        }
                        if (!collinear(first.pivot, first.axis, j.pivot, j.axis)) ok = false;
                        // the bearings' side: the frame, a slide (the rotor rides on it), or a coaxial rotor (and its carrier)
                        CompiledAssembly.Body on = parent.isStatic ? null : parent.slider ? parent : parent.carrier;
                        if (j == first) carrier = on;
                        else if (on != carrier) ok = false;
                        if (parent.slider && parent.carrier != null) ok = false; // a slide on a rotor carries nothing that turns
                        if (!parent.isStatic && !parent.slider && !collinear(first.pivot, first.axis, parent.pivot, parent.axis)) ok = false;
                    }
                    if (!ok) {
                        c.warnings.add(slide ? "Locked: a slide must run in parallel linear bearings on the frame or on one turning body"
                                : "Locked: a body is held by bearings that are not on one axis line");
                        bodyOfGroup.put(g, ground);
                        continue;
                    }
                    CompiledAssembly.Body rotor = new CompiledAssembly.Body(c.bodies.size(), false, first.pivot, first.axis);
                    rotor.carrier = carrier;
                    if (slide) {
                        rotor.slider = true;
                        strokeOf(rotor, groups.get(g), toResolved.stream().map(J::bearing).toList());
                    }
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

        /**
         * Travel of a slide: it can move until a linear bearing reaches the end of the shaft running through it — the
         * shaft cells of the slide on each bearing's line bound the displacement along the (positive) axis.
         */
        void strokeOf(CompiledAssembly.Body s, List<Part> group, List<Part> bearings) {
            int ax = s.axis.x() != 0 ? 0 : s.axis.y() != 0 ? 1 : 2;
            double lo = -Double.MAX_VALUE, hi = Double.MAX_VALUE;
            for (Part b : bearings) {
                int bMin = cellRange(b, ax)[0], bMax = cellRange(b, ax)[1];
                int sMin = Integer.MAX_VALUE, sMax = Integer.MIN_VALUE;
                for (Part p : group) {
                    if (p.kind() != PartKind.SHAFT || p.axis().axis() != ax) continue;
                    boolean onLine = true;
                    for (int k = 0; k < 3; k++) if (k != ax && (cellRange(p, k)[0] > cellRange(b, k)[1] || cellRange(p, k)[1] < cellRange(b, k)[0])) onLine = false;
                    if (!onLine) continue;
                    sMin = Math.min(sMin, cellRange(p, ax)[0]);
                    sMax = Math.max(sMax, cellRange(p, ax)[1]);
                }
                if (sMin > sMax) continue;
                hi = Math.min(hi, (bMin - sMin) * CELL);
                lo = Math.max(lo, (bMax - sMax) * CELL);
                // a stop collar on the shaft: "travel" [m] either way from where it was placed
                if (b.params().containsKey("travel")) {
                    double t = Math.max(0, b.param("travel", 0));
                    hi = Math.min(hi, t);
                    lo = Math.max(lo, -t);
                }
                // or one on either side: "travel_fwd" [m] along the bushing's own axis, "travel_back" against it
                int sg = (int) Math.signum(s.axis.dot(b.axis().vec()));
                if (b.params().containsKey("travel_fwd")) {
                    double t = Math.max(0, b.param("travel_fwd", 0));
                    if (sg > 0) hi = Math.min(hi, t);
                    else lo = Math.max(lo, -t);
                }
                if (b.params().containsKey("travel_back")) {
                    double t = Math.max(0, b.param("travel_back", 0));
                    if (sg > 0) lo = Math.max(lo, -t);
                    else hi = Math.min(hi, t);
                }
            }
            s.strokeLo = lo == -Double.MAX_VALUE ? 0 : lo;
            s.strokeHi = hi == Double.MAX_VALUE ? 0 : hi;
        }

        static int[] cellRange(Part p, int axis) {
            return axis == 0 ? new int[]{p.minX(), p.maxX()} : axis == 1 ? new int[]{p.minY(), p.maxY()} : new int[]{p.minZ(), p.maxZ()};
        }

        boolean collinear(Vec3 p1, Vec3 a1, Vec3 p2, Vec3 a2) {
            if (Math.abs(Math.abs(a1.dot(a2)) - 1) > 1e-9) return false;
            Vec3 d = p2.sub(p1);
            return d.sub(a1.mul(d.dot(a1))).length() < 1e-6;
        }

        /**
         * Still water on a slide's parts (see {@link dev.emergence.core.sim.elements.Buoyancy}): each part standing in
         * water, or just over it, displaces its own material — a closed vessel (tank, cylinder) its whole outer box, the
         * gas or liquid it holds not weighed (air: a few grams).
         */
        void buildBuoyancy(CompiledAssembly.Body b) {
            List<dev.emergence.core.sim.elements.Buoyancy.Immersed> wet = new ArrayList<>();
            for (long id : b.parts) {
                Part p = a.get(id);
                if (isWire(p)) continue;
                int cx = (p.minX() + p.maxX()) / 2, cz = (p.minZ() + p.maxZ()) / 2;
                // the water over its bottom cell, or the pond just under it (a float riding high)
                double surface = a.waterSurface(cx, p.minY(), cz);
                if (Double.isNaN(surface)) surface = a.waterSurface(cx, p.minY() - 1, cz);
                if (Double.isNaN(surface)) continue;
                double h = p.boxSize(1);
                boolean sealed = p.kind() == PartKind.TANK || p.kind() == PartKind.CYLINDER;
                double vol = sealed ? p.boxSize(0) * p.boxSize(1) * p.boxSize(2) : PartGeometry.volume(p);
                // its section across the guide: the displaced volume over its length along it
                double along = Math.abs(b.axis.x()) * p.boxSize(0) + Math.abs(b.axis.y()) * p.boxSize(1) + Math.abs(b.axis.z()) * p.boxSize(2);
                wet.add(new dev.emergence.core.sim.elements.Buoyancy.Immersed(vol / h, h, p.center().y() - h / 2, surface,
                        vol / Math.max(along, 1e-6)));
            }
            if (wet.isEmpty()) return;
            c.network.add(new dev.emergence.core.sim.elements.Buoyancy(b.node, b.tracker, b.axis.y(), WATER_DENSITY,
                    PartGeometry.gravity(), b.mass + b.carriedMass, wet));
        }

        /** The water about the machine, as the turning parts in it see it: the surface over a point, its current there. */
        dev.emergence.core.sim.elements.WaterOnRotor.Field waterField() {
            return new dev.emergence.core.sim.elements.WaterOnRotor.Field() {
                int[] cell(Vec3 p) {
                    int x = (int) Math.floor(p.x() / Part.CELL), y = (int) Math.floor(p.y() / Part.CELL), z = (int) Math.floor(p.z() / Part.CELL);
                    if (a.isWaterCell(x, y, z)) return new int[]{x, y, z};
                    if (a.isWaterCell(x, y - 1, z)) return new int[]{x, y - 1, z}; // just over the water
                    return null;
                }

                @Override
                public double surface(Vec3 p) {
                    int[] c0 = cell(p);
                    return c0 == null ? Double.NaN : a.waterSurface(c0[0], c0[1], c0[2]);
                }

                @Override
                public Vec3 velocity(Vec3 p) {
                    int[] c0 = cell(p);
                    return c0 == null ? Vec3.ZERO : a.waterVelocity(c0[0], c0[1], c0[2]);
                }
            };
        }

        /**
         * Water on a turning body's parts (see {@link dev.emergence.core.sim.elements.WaterOnRotor}): those whose path
         * round the axis dips into water. A thin block (filled to half its depth or less) is a plate, its face normal to
         * its axis; any other part a bluff body.
         */
        void buildWaterOnRotor(CompiledAssembly.Body b) {
            var field = waterField();
            List<dev.emergence.core.sim.elements.WaterOnRotor.Wetted> wet = new ArrayList<>();
            for (long id : b.parts) {
                Part p = a.get(id);
                if (isWire(p)) continue;
                Vec3 r0 = p.center().sub(b.pivot);
                boolean dips = false;
                for (int k = 0; k < 24 && !dips; k++) {
                    double th = 2 * Math.PI * k / 24, c0 = Math.cos(th), s0 = Math.sin(th);
                    Vec3 along = b.axis.mul(r0.dot(b.axis)), across = r0.sub(along);
                    Vec3 at = b.pivot.add(along).add(across.mul(c0)).add(b.axis.cross(across).mul(s0));
                    dips = !Double.isNaN(field.surface(at));
                }
                if (!dips) continue;
                boolean sealed = p.kind() == PartKind.TANK || p.kind() == PartKind.CYLINDER;
                double vol = sealed ? p.boxSize(0) * p.boxSize(1) * p.boxSize(2) : PartGeometry.volume(p);
                boolean plate = p.kind() == PartKind.BLOCK && p.param("fill", 1.0) <= 0.5;
                int ax = p.axis().axis();
                double plateArea = plate ? p.boxSize((ax + 1) % 3) * p.boxSize((ax + 2) % 3) : 0;
                wet.add(new dev.emergence.core.sim.elements.WaterOnRotor.Wetted(r0, vol, p.boxSize(1), plateArea,
                        plate ? p.axis().vec() : Vec3.ZERO, Math.pow(vol, 2.0 / 3)));
            }
            if (wet.isEmpty()) return;
            c.network.add(new dev.emergence.core.sim.elements.WaterOnRotor(b.node, b.tracker, b.axis, b.pivot, WATER_DENSITY,
                    PartGeometry.gravity(), wet, field));
        }

        /** Still water's density near 15 °C [kg/m³]. */
        static final double WATER_DENSITY = 999.1;

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
                WireRun run = runOf.get(p.id());
                if (run != null && run.parts.get(0) != p) continue; // a wire run is one thermal mass, built with its first wire
                double t0 = prev.temperatures().getOrDefault(p.id(), ambientT);
                Node n = c.network.node(Domain.THERMAL, "T" + p.id(), t0);
                c.thermalOfPart.put(p.id(), n);
                double cp = p.material().get(MaterialProperty.SPECIFIC_HEAT, 500);
                int count = run == null ? 1 : run.parts.size();
                if (run != null) for (Part q : run.parts) c.thermalOfPart.put(q.id(), n);
                c.network.add(new HeatCapacity(n, Math.max(count * PartGeometry.mass(p) * cp, 1e-4)));
                double area = count * PartGeometry.surfaceArea(p);
                NaturalConvection.Shape shape = switch (p.kind()) {
                    case SHAFT, WIRE, INSULATED_WIRE -> NaturalConvection.Shape.HORIZONTAL_CYLINDER;
                    case BLOCK, GEAR -> NaturalConvection.Shape.VERTICAL_PLATE;
                    default -> NaturalConvection.Shape.SPHERE;
                };
                c.network.add(new NaturalConvection(n, c.ambient, shape, area, PartGeometry.convectionLength(p)));
                c.network.add(new Radiation(n, c.ambient, PartGeometry.emissivity(p), area));
                c.failureTemp.put(p.id(), PartGeometry.failureTemperature(p));
                if (run != null) for (Part q : run.parts) c.failureTemp.put(q.id(), PartGeometry.failureTemperature(q));
            }
        }

        // ------------------------------------------------------------ wire runs
        /**
         * A chain of identical single-cell wires, each touching exactly two others, carries one current through every
         * cell: it is compiled as one electrical node and one thermal mass (summed heat capacity and surface), with
         * half the run's total resistance at each end — the series resistance and the Joule heat are exact, only the
         * temperature profile along the run is lumped. This keeps routed wiring from multiplying the unknowns.
         */
        static final class WireRun {
            final List<Part> parts = new ArrayList<>();
        }

        final Map<Long, WireRun> runOf = new HashMap<>();

        /** A flexible lead: a bare wire marked "flexible" (a moving coil's tinsel lead). */
        /** True if body s is a slide whose travel carries its part to or from one on body o: o is the frame, or s's carrier. */
        static boolean slideAgainst(CompiledAssembly.Body s, CompiledAssembly.Body o) {
            if (s == null || !s.slider) return false;
            return s.carrier == null ? o == null || o.isStatic : o == s.carrier;
        }

        boolean isFlexibleLead(Part p) {
            return p.kind() == PartKind.WIRE && p.param("flexible", 0) > 0.5;
        }

        /** The parts a wire makes electrical contact with (on the same body), or null if it is not a plain wire. */
        List<Part> wireContacts(Part p) {
            if (!isWire(p) || p.minX() != p.maxX() || p.minY() != p.maxY() || p.minZ() != p.maxZ()) return null;
            List<Part> out = new ArrayList<>();
            int x = p.minX(), y = p.minY(), z = p.minZ();
            for (Dir d : Dir.values()) {
                for (Part q : a.at(x + d.dx, y + d.dy, z + d.dz)) {
                    if (q == p || !(conductive(q) || q.kind().leaded)) continue;
                    if (port(q, x + d.dx, y + d.dy, z + d.dz, d.opposite()) == null || !compatible(p, q)) continue;
                    if (c.bodyOfPart.get(p.id()) != c.bodyOfPart.get(q.id())) continue;
                    out.add(q);
                }
            }
            return out;
        }

        void buildWireRuns() {
            Map<Long, List<Part>> links = new HashMap<>();
            for (Part p : parts) {
                List<Part> k = wireContacts(p);
                if (k != null && k.size() == 2) links.put(p.id(), k);
            }
            Map<Long, Long> parent = new HashMap<>();
            for (long id : links.keySet()) parent.put(id, id);
            java.util.function.LongUnaryOperator find = new java.util.function.LongUnaryOperator() {
                public long applyAsLong(long v) {
                    long r = v;
                    while (parent.get(r) != r) r = parent.get(r);
                    parent.put(v, r);
                    return r;
                }
            };
            for (var en : links.entrySet()) {
                Part p = a.get(en.getKey());
                for (Part q : en.getValue()) {
                    if (!links.containsKey(q.id()) || q.kind() != p.kind() || conductorMaterial(q) != conductorMaterial(p)
                            || conductorArea(q) != conductorArea(p)) continue;
                    parent.put(find.applyAsLong(p.id()), find.applyAsLong(q.id()));
                }
            }
            Map<Long, WireRun> byRoot = new HashMap<>();
            for (Part p : parts) {
                if (!links.containsKey(p.id())) continue;
                byRoot.computeIfAbsent(find.applyAsLong(p.id()), k -> new WireRun()).parts.add(p);
            }
            for (WireRun run : byRoot.values()) {
                if (run.parts.size() < 2) continue;
                for (Part p : run.parts) runOf.put(p.id(), run);
            }
        }

        /** Conduction length from a part's node to a contact face, a wire run's being half the run. */
        double nodeToFace(Part p) {
            WireRun run = runOf.get(p.id());
            return run != null ? run.parts.size() * CELL / 2 : halfLength(p);
        }

        /**
         * Heat across a contact between scales (Assembly.Link THERMAL): each side conducts over half a cell of its own
         * material to the contact, of {@code share} of a cell's face; the contact's own conductance as any.
         */
        void thermalAcross(Part p, Part q, double share) {
            Node tp = c.thermalOfPart.get(p.id()), tq = c.thermalOfPart.get(q.id());
            if (tp == null || tq == null) return;
            double area = CELL * CELL * Math.max(1e-3, Math.min(1, share));
            double kp = p.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1), kq = q.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1);
            double r = (CELL / 2) / (kp * area) + (CELL / 2) / (kq * area) + 1 / (CONTACT_H * area);
            c.network.add(new ThermalConductance(tp, tq, 1 / r));
        }

        /** The links across scales: heat through contacts, shafts coupled (fluid passages are made with the fluids). */
        void buildScaleLinks() {
            for (Assembly.Link l : a.links()) {
                Part p = partById(l.a()), q = partById(l.b());
                if (p == null || q == null) continue;
                switch (l.kind()) {
                    case THERMAL -> thermalAcross(p, q, l.share());
                    case SHAFT -> {
                        CompiledAssembly.Body bp = c.bodyOfPart.get(p.id()), bq = c.bodyOfPart.get(q.id());
                        if (bp == null || bq == null || bp == bq || bp.isStatic && bq.isStatic || bp.slider || bq.slider) continue;
                        Vec3 ap = bp.isStatic ? p.axis().vec() : bp.axis, aq = bq.isStatic ? q.axis().vec() : bq.axis;
                        // they turn together: ω_p·a_p = ω_q·a_q, i.e. r·ω_p + s·r·ω_q = 0 with s = −(a_p·a_q)
                        double s = -Math.signum(ap.dot(aq));
                        if (s == 0) continue;
                        double r = Math.max(PartGeometry.shaftDiameter(p), PartGeometry.shaftDiameter(q)) / 2;
                        Node na = bp.isStatic ? refR : bp.node, nb = bq.isStatic ? refR : bq.node;
                        c.network.add(GearMesh.bevel(na, nb, r, r, 1, 1, s, 0, c.thermalOfPart.get(p.id())));
                        c.shaftCouplings++;
                    }
                    default -> { }
                }
            }
        }

        /** Whether part p's cell (x, y, z), facing d, is the end of a fluid link across scales (so it does not vent). */
        boolean fluidLinked(Part p, int x, int y, int z, Dir d) {
            for (Assembly.Link l : a.links()) {
                if (l.kind() != Assembly.LinkKind.FLUID) continue;
                if (l.a() == p.id() && l.faceA() == d && l.cellA()[0] == x && l.cellA()[1] == y && l.cellA()[2] == z) return true;
                if (l.b() == p.id() && l.faceB() == d && l.cellB()[0] == x && l.cellB()[1] == y && l.cellB()[2] == z) return true;
            }
            return false;
        }

        void thermalLink(Part p, Part q) {
            double kp = p.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1);
            double kq = q.material().get(MaterialProperty.THERMAL_CONDUCTIVITY, 1);
            double[] cp = conductionPath(p, q), cq = conductionPath(q, p);
            double r = cp[0] / (kp * cp[1]) + cq[0] / (kq * cq[1]) + 1 / (CONTACT_H * Math.min(cp[1], cq[1]));
            c.network.add(new ThermalConductance(c.thermalOfPart.get(p.id()), c.thermalOfPart.get(q.id()), 1 / r));
        }

        /**
         * {length, area} of the conduction path from part p's bulk to its contact with q. Heat entering a pipe,
         * valve or tank through its side crosses only the wall (thickness × contact face); along a pipe it runs
         * through the tube wall's cross-section; solid parts conduct over half a cell.
         */
        double[] conductionPath(Part p, Part q) {
            // a manufactured component whose body is clamped against a structure (a heat sink, a heater mounted on
            // a plate) conducts through its body face, not through its leads: ~2 mm of body over its contact area
            if (p.kind().leaded && !isWire(p) && p.kind() != PartKind.COIL && q.kind().structural) {
                return new double[]{2e-3, p.param("contact_area", 2e-4)};
            }
            if (p.kind() == PartKind.TANK) {
                return new double[]{PartGeometry.wallThickness(p), CELL * CELL};
            }
            if (p.kind() == PartKind.CYLINDER) {
                Vec3 d = q.center().sub(p.center());
                int a = p.axis().axis();
                double along = Math.abs(a == 0 ? d.x() : a == 1 ? d.y() : d.z());
                double w = Math.max(PartGeometry.wallThickness(p), 0.003), bore = PartGeometry.cylinderBore(p);
                // through the head (a cylinder head is about twice the barrel wall) or through the barrel
                if (along > 0.5 * d.length()) return new double[]{2 * w, Math.PI * bore * bore / 4};
                return new double[]{w, (bore + 2 * w) * PartGeometry.axialLength(p)};
            }
            if (p.kind() == PartKind.PIPE || p.kind() == PartKind.VALVE) {
                boolean axial = false;
                Vec3 d = q.center().sub(p.center());
                int a = p.axis().axis();
                double along = Math.abs(a == 0 ? d.x() : a == 1 ? d.y() : d.z());
                axial = along > 0.5 * d.length();
                if (!axial) {
                    double outer = PartGeometry.pipeBore(p) + 2 * PartGeometry.wallThickness(p);
                    return new double[]{PartGeometry.wallThickness(p), outer * PartGeometry.axialLength(p)};
                }
            }
            return new double[]{CELL / 2, PartGeometry.conductionArea(p)};
        }

        // ------------------------------------------------------------ 4. mechanics
        void buildMechanics() {
            // a slide carries the rotors whose bearings ride on it: their mass moves with it
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.isStatic || b.carrier == null) continue;
                for (long id : b.parts) if (!isWire(a.get(id))) b.carrier.carriedMass += PartGeometry.mass(a.get(id));
            }
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.isStatic) continue;
                double j = 0, m = 0;
                Vec3 moment = new Vec3(0, 0, 0);
                for (long id : b.parts) {
                    Part p = a.get(id);
                    if (isWire(p)) continue;
                    j += PartGeometry.inertiaAbout(p, b.pivot, b.axis);
                    m += PartGeometry.mass(p);
                    moment = moment.add(p.center().sub(b.pivot).mul(PartGeometry.mass(p)));
                }
                b.inertia = Math.max(j, 1e-7);
                b.mass = m;
                double[] motion = null;
                for (long id : b.parts) {
                    motion = prev.bodyMotion().get(id);
                    if (motion != null) break;
                }
                if (b.slider) {
                    // a slide: its mass on a velocity node, its displacement tracked, gravity along the guide, and
                    // stops where the bearings reach the shaft's ends
                    double mt = m + b.carriedMass; // with what rides on it
                    b.node = c.network.node(Domain.TRANSLATIONAL, "v" + b.index, motion == null ? 0 : motion[0]);
                    c.network.add(new Inertia(b.node, Math.max(mt, 1e-6)));
                    b.tracker = c.network.add(new AngleTracker(b.node, motion == null ? 0 : motion[1]));
                    b.drive = c.network.add(new FlowSource(refT, b.node, 0.0));
                    double fg = -mt * PartGeometry.gravity() * b.axis.y();
                    if (fg != 0 && b.carrier == null) c.network.add(new FlowSource(refT, b.node, fg));
                    if (b.carrier == null) buildBuoyancy(b);
                    if (b.carrier != null) {
                        // riding on a turning body: its weight, the centrifugal and Coriolis forces, and the rotor's
                        // inertia changing as it moves out or in
                        CompiledAssembly.Body r = b.carrier;
                        Vec3 com = b.pivot.add(moment.mul(1 / Math.max(m, 1e-12)));
                        double js = 0;
                        for (long id : b.parts) if (!isWire(a.get(id))) js += PartGeometry.inertiaAbout(a.get(id), com, r.axis);
                        c.network.add(new dev.emergence.core.sim.elements.CarriedSlide(b.node, b.tracker, r.node, r.tracker, r.axis, b.axis,
                                com.sub(r.pivot), Math.max(m, 1e-6), js, new Vec3(0, -PartGeometry.gravity(), 0)));
                    }
                    // the stops: a firm, well-damped contact (150 Hz on the slide's mass, damping ratio 0.7): it
                    // settles within a few hundredths of a millimetre of the end, without bouncing
                    double wStop = 2 * Math.PI * 150, kStop = Math.max(mt, 1e-3) * wStop * wStop;
                    c.network.add(new TravelStops(b.node, b.tracker, b.strokeLo, b.strokeHi, kStop,
                            2 * 0.7 * Math.sqrt(kStop * Math.max(mt, 1e-3)), null));
                    c.pushers.put(b, c.network.add(new HumanCrank(b.node, refT, 1.0)));
                    continue;
                }
                b.node = c.network.node(Domain.ROTATIONAL, "w" + b.index, motion == null ? 0 : motion[0]);
                c.network.add(new Inertia(b.node, b.inertia));
                b.tracker = c.network.add(new AngleTracker(b.node, motion == null ? 0 : motion[1]));
                b.drive = c.network.add(new FlowSource(refR, b.node, 0.0));
                // a hand that may grip any part of it and turn it (a flywheel given a turn, a motor nudged off its dead point)
                c.turners.put(b, c.network.add(new HumanCrank(b.node, refR, 0.1)));
                // gravity on an off-centre mass (a lever, a pendulum, an unbalanced wheel)
                Vec3 offset = moment.mul(1 / Math.max(m, 1e-12));
                Vec3 perp = offset.sub(b.axis.mul(offset.dot(b.axis)));
                if (m > 0 && perp.length() > 1e-6 && Math.abs(b.axis.y()) < 0.999) {
                    c.network.add(new GravityTorque(b.node, b.tracker, b.axis, offset, m, new Vec3(0, -PartGeometry.gravity(), 0)));
                }
                if (b.carrier == null) buildWaterOnRotor(b);
                // riding on a slide: an unbalanced rotor shakes it, and the slide's acceleration swings the rotor
                if (b.carrier != null && m > 0 && perp.length() > 1e-6) {
                    c.network.add(new dev.emergence.core.sim.elements.CarriedRotor(b.carrier.node, b.carrier.axis, b.node, b.tracker,
                            b.axis, offset, m));
                }
            }
            // bearings
            Map<CompiledAssembly.Body, Integer> bearingCount = new HashMap<>();
            for (long[] jt : joints) {
                CompiledAssembly.Body bb = c.bodyOfPart.get(jt[0]), sb = c.bodyOfPart.get(jt[1]);
                if (bb != sb) {
                    bearingCount.merge(jointChild(bb, sb), 1, Integer::sum);
                }
            }
            for (long[] jt : joints) {
                Part bearing = a.get(jt[0]), shaft = a.get(jt[1]);
                CompiledAssembly.Body bb = c.bodyOfPart.get(bearing.id()), sb = c.bodyOfPart.get(shaft.id());
                thermalLink(bearing, shaft);
                if (bb == sb) continue; // locked joint
                CompiledAssembly.Body rot = jointChild(bb, sb);
                double load = (rot.mass + rot.carriedMass) * PartGeometry.gravity() / Math.max(1, bearingCount.getOrDefault(rot, 1));
                if (bearing.kind() == PartKind.LINEAR_BEARING) {
                    // ball bushing: rolling friction μ ≈ 0.003 of the load across the guide, plus grease drag
                    Node ra = bb.slider ? bb.node : refT, rb = sb.slider ? sb.node : refT; // (a turning body: the slide's own frame)
                    double across = Math.sqrt(Math.max(0, 1 - rot.axis.y() * rot.axis.y()));
                    c.network.add(new CoulombFriction(rb, ra, 0.003 * Math.max(load * across, 0.01), 1e-3, c.thermalOfPart.get(bearing.id())));
                    c.network.add(new Damper(rb, ra, bearing.param("viscosity", 0.05), c.thermalOfPart.get(bearing.id())));
                    thermalLink(bearing, shaft);
                    continue;
                }
                double d = PartGeometry.shaftDiameter(shaft);
                int btype = (int) Math.round(bearing.param("type", 0));
                boolean plain = btype == 1;
                Node th = c.thermalOfPart.get(bearing.id());
                Node na = turning(bb), nb = turning(sb);
                if (bearing.kind() == PartKind.POTENTIOMETER) {
                    // wiper and bushing drag of a panel potentiometer, scaled with its size (≈ 2 mN·m for a 25 mm body)
                    double size = PartGeometry.transverseSize(bearing) / 0.025;
                    c.network.add(new CoulombFriction(nb, na, bearing.param("drag", 2e-3) * size * size * size, 1e-2, th));
                    potShaft.put(bearing.id(), new Object[]{rot, bb.isStatic ? 1.0 : -1.0});
                    continue;
                }
                double mu, cv;
                if (btype == 2) {
                    // rotary viscous damper: a rotor turning in a silicone-filled housing, Couette shear across a
                    // 0.5 mm gap: c = μ·2π·r³·L / gap (r 40 % of the cell, L 80 % of its length); no dry friction
                    double r = 0.4 * PartGeometry.transverseSize(bearing), len = 0.8 * PartGeometry.axialLength(bearing);
                    c.network.add(new Damper(nb, na, bearing.param("viscosity", 10) * 2 * Math.PI * r * r * r * len / 5e-4, th));
                    continue;
                }
                if (!plain) {
                    // SKF simplified friction model for a deep-groove ball bearing: M = ½·μ·P·d, μ ≈ 0.0015,
                    // plus a small viscous grease-drag term.
                    mu = 0.0015;
                    cv = 2e-7 * Math.pow(d / 0.01, 3);
                } else {
                    // Lubricated plain bushing: boundary friction μ·P·d/2 plus Petroff hydrodynamic drag
                    // c = μ_oil·2π·r³·L / clearance, clearance 0.1 % of d; lubricant viscosity "viscosity" (default
                    // 0.1 Pa·s, SAE-30-like oil; thick damping grease is several Pa·s)
                    mu = bearing.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.15) * 0.4;
                    double r = d / 2, len = PartGeometry.axialLength(bearing);
                    cv = bearing.param("viscosity", 0.1) * 2 * Math.PI * r * r * r * len / (0.001 * d);
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
            for (Part r : parts) {
                if (r.kind() != PartKind.RACK) continue;
                for (Part g : gears) tryRack(g, r);
            }
            for (Part n : parts) if (n.kind() == PartKind.NUT) buildLeadScrew(n);
            // thermal contact between structurally joined parts
            for (Part p : parts) {
                for (Part q : a.neighbors(p)) {
                    if (q.id() <= p.id()) continue;
                    if (joinedPairs.contains(pairKey(p.id(), q.id())) && !isBearingJointPair(p, q)) thermalLink(p, q);
                }
            }
            buildSprings();
            buildLinks();
            // cranks are where a person can put work in
            for (Part p : parts) {
                if (p.kind() != PartKind.CRANK) continue;
                CompiledAssembly.Body b = c.bodyOfPart.get(p.id());
                if (b.isStatic || b.slider) {
                    c.warnings.add("Crank #" + p.id() + " is on a body that cannot rotate");
                    continue;
                }
                c.cranks.put(p.id(), c.network.add(new HumanCrank(b.node, refR, PartGeometry.crankRadius(p))));
            }
        }

        /**
         * Blades: each moving body's blade cells become blade elements of one {@link Aerofoils} element (on a turning
         * body with momentum-theory inflow through the disc its blades sweep). Blades on the frame feel the wind but
         * move nothing; a rotor riding a slide (a propeller on a cart) pushes its slide; blades on a slide riding a
         * rotor are not modelled (a warning).
         */
        void buildAero() {
            double rho = dev.emergence.core.fluids.Air.density(ambientT, a.ambientPressure());
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.isStatic) continue;
                List<Aerofoils.Foil> foils = new ArrayList<>();
                double tip = 0;
                for (long id : b.parts) {
                    Part p = a.get(id);
                    if (p.kind() != PartKind.BLADE) continue;
                    // a rotor riding a slide (a propeller on a cart) is modelled; a slide riding a rotor is not
                    if (b.carrier != null && (b.slider || !b.carrier.slider)) {
                        c.warnings.add("Blade #" + p.id() + " rides on a slide on a turning body: its aerodynamics are not modelled");
                        continue;
                    }
                    Vec3 s = p.axis().vec(), ch = PartGeometry.bladeChordDirection(p);
                    double chord = PartGeometry.bladeChord(p), cd0 = p.param("drag", 0.02);
                    for (int x = p.minX(); x <= p.maxX(); x++)
                        for (int y = p.minY(); y <= p.maxY(); y++)
                            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                                Vec3 ctr = Part.cellCenter(x, y, z);
                                foils.add(new Aerofoils.Foil(ctr, s, ch, chord * CELL, cd0));
                                if (!b.slider) {
                                    Vec3 d = ctr.sub(b.pivot);
                                    tip = Math.max(tip, d.sub(b.axis.mul(d.dot(b.axis))).length() + CELL / 2);
                                }
                            }
                }
                if (foils.isEmpty()) continue;
                Aerofoils af = new Aerofoils(b.node, b.tracker, !b.slider, b.axis, b.pivot, foils,
                        b.slider ? 0 : Math.PI * tip * tip, rho, a.ambientWind());
                if (b.carrier != null) af.carriedBy(b.carrier.node, b.carrier.axis);
                c.network.add(af);
                c.aerofoils.add(af);
                c.aeroOfBody.put(b, af);
            }
        }

        /**
         * The share of its cells' face that a small light-sensitive part's body presents: a cube of its volume, never
         * smaller than its window (a solar cell is all window). The optics catch light over the whole face (its window
         * gathers from it); its body takes only this share of the sunlight as heat.
         */
        static double bodyShare(Part p, double window) {
            double side = Math.max(Math.cbrt(Math.max(PartGeometry.volume(p), 1e-12)), Math.sqrt(window));
            double face = p.boxSize(0) * p.boxSize(1) * p.boxSize(2) / Math.max(p.boxSize(0), Math.max(p.boxSize(1), p.boxSize(2)));
            return Math.min(1, side * side / face);
        }

        /** Of two bodies joined by a bearing, the one that moves relative to the other. */
        static CompiledAssembly.Body jointChild(CompiledAssembly.Body bb, CompiledAssembly.Body sb) {
            if (bb.isStatic) return sb;
            if (sb.isStatic) return bb;
            if (sb.carrier == bb) return sb;
            return bb;
        }

        /** A body's angular-velocity node: the reference for the frame and for slides (which do not turn). */
        Node turning(CompiledAssembly.Body b) {
            return b.isStatic || b.slider ? refR : b.node;
        }

        /**
         * True if the geometry between parts of these two bodies changes only by their turning about fixed axes (or one
         * sliding on the frame): a rotor carried by a slide keeps it with its carrier and that carrier's other
         * passengers, but not with the frame or with anything else, which it moves past.
         */
        static boolean sameFrame(CompiledAssembly.Body x, CompiledAssembly.Body y) {
            CompiledAssembly.Body fx = x.carrier, fy = y.carrier;
            if (fx == null && fy == null) return true;
            return fx == fy || fx == y || fy == x;
        }

        /** Potentiometer id → {rotating body its shaft belongs to, +1 if the housing is the static side else −1}. */
        final Map<Long, Object[]> potShaft = new HashMap<>();

        /**
         * Helical springs: each joins the bodies touching its two end faces (along its axis). A slider end moves the
         * spring's end by its displacement projected on the spring axis; a rotor end is not supported (torsion
         * springs are not modelled) and the spring then acts as fixed there.
         */
        void buildSprings() {
            for (Part sp : parts) {
                if (sp.kind() != PartKind.SPRING) continue;
                Vec3 sa = sp.axis().vec();
                CompiledAssembly.Body[] end = endBodies(sp);
                CompiledAssembly.Body ground = c.bodies.get(0);
                if (!sameFrame(end[0] == null ? ground : end[0], end[1] == null ? ground : end[1])) {
                    c.warnings.add("Spring #" + sp.id() + " joins a body riding on a turning one to something it moves past (not modelled)");
                    continue;
                }
                // the spring rides with the frame its ends move in (a slide's turning carrier, or a rotor it joins):
                // it joins no rigid group, so it would otherwise stand still on the ground while the rotor turns
                CompiledAssembly.Body frame = null;
                for (CompiledAssembly.Body eb : end) if (eb != null && !eb.slider) frame = eb;
                if (frame == null) for (CompiledAssembly.Body eb : end) if (eb != null && eb.carrier != null) frame = eb.carrier;
                CompiledAssembly.Body was = c.bodyOfPart.get(sp.id());
                if (frame != null && was != null && frame != was) {
                    was.parts.remove(Long.valueOf(sp.id()));
                    frame.parts.add(sp.id());
                    c.bodyOfPart.put(sp.id(), frame);
                }
                double k = PartGeometry.springRate(sp);
                // extension = (end b's displacement − end a's) along the spring axis; a slider moving along its
                // (positive) axis moves that end by x·(axis·spring axis)
                Node na = null, nb = null;
                AngleTracker ta = null, tb = null;
                double sa_ = 0, sb_ = 0;
                if (end[0] != null && end[0].slider) {
                    na = end[0].node;
                    ta = end[0].tracker;
                    sa_ = -end[0].axis.dot(sa);
                }
                if (end[1] != null && end[1].slider) {
                    nb = end[1].node;
                    tb = end[1].tracker;
                    sb_ = end[1].axis.dot(sa);
                }
                if (na == null && nb == null) continue; // both ends fixed: it just sits there
                // "preload" [m]: how much shorter than its free length it was fitted (it pushes its ends apart by k·preload
                // where it was placed — a governor's spring setting the speed its weights lift at)
                c.network.add(new PositionSpring(na, ta, sa_, nb, tb, sb_, k, sp.param("preload", 0)));
                c.springs.put(sp.id(), new double[]{k});
                c.springEnds.put(sp.id(), new Object[]{ta, -sa_, tb, sb_});
            }
        }

        /**
         * The bodies touching a part's two end faces ({−axis face, +axis face}): a moving one if any does, else the
         * frame's; null where nothing does.
         */
        CompiledAssembly.Body[] endBodies(Part sp) {
            return new CompiledAssembly.Body[]{faceBody(sp, sp.axis().opposite()), faceBody(sp, sp.axis())};
        }

        /** The body touching face d of part p: a moving one if any does, else the frame's; null if nothing does. */
        CompiledAssembly.Body faceBody(Part sp, Dir d) {
            CompiledAssembly.Body found = null;
            for (int x = sp.minX(); x <= sp.maxX(); x++)
                for (int y = sp.minY(); y <= sp.maxY(); y++)
                    for (int z = sp.minZ(); z <= sp.maxZ(); z++) {
                        int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                        if (sp.containsCell(nx, ny, nz)) continue;
                        for (Part q : a.at(nx, ny, nz)) {
                            if (isWire(q) || q == sp || q.kind() == PartKind.LINK || q.kind() == PartKind.SPRING) continue;
                            CompiledAssembly.Body b = c.bodyOfPart.get(q.id());
                            if (b != null && (found == null || found.isStatic)) found = b;
                        }
                    }
            return found;
        }

        /** True if b is a slide carried by a turning body along its axis line, and point p lies on that line. */
        static boolean onSpinAxis(CompiledAssembly.Body b, Vec3 p) {
            CompiledAssembly.Body r = b.carrier;
            if (!b.slider || r == null || r.slider || Math.abs(Math.abs(b.axis.dot(r.axis)) - 1) > 1e-9) return false;
            Vec3 off = p.sub(r.pivot);
            return off.sub(r.axis.mul(off.dot(r.axis))).length() < 1e-6;
        }

        /** The link parts touching link l, by id. */
        List<Part> linkNeighbours(Part l) {
            List<Part> out = new ArrayList<>();
            for (Part q : a.neighbors(l)) if (q.kind() == PartKind.LINK) out.add(q);
            out.sort(Comparator.comparingLong(Part::id));
            return out;
        }

        /** The face of part p that faces part q (by the larger offset between their centres). */
        static Dir faceToward(Part p, Part q) {
            Vec3 d = q.center().sub(p.center());
            double[] c = {d.x(), d.y(), d.z()};
            int ax = Math.abs(c[0]) >= Math.abs(c[1]) && Math.abs(c[0]) >= Math.abs(c[2]) ? 0 : Math.abs(c[1]) >= Math.abs(c[2]) ? 1 : 2;
            return Dir.fromAxis(ax, c[ax] > 0);
        }

        /** Centre of face d of part p [m]. */
        static Vec3 faceCentre(Part p, Dir d) {
            return p.center().add(d.vec().mul(0.5 * p.boxSize(d.axis())));
        }

        /**
         * Links: a path of link parts, face to face, is one bar running straight between its two ends — a straight row
         * along its length, or a path laid round to an end off that line (the bar then runs across it, at a slant).
         * It is a {@link RigidLink} between the bodies at the path's two outer faces, pinned at their centres (a single
         * link part: its two axis faces). Both must move in one frame — the ground, or a turning body (a governor's
         * weights on its spindle) or slide that carries them (or is one of them); each end's body is then fixed in that
         * frame, turns in it about a fixed axis, or slides along one.
         */
        void buildLinks() {
            CompiledAssembly.Body ground = c.bodies.get(0);
            Set<Long> done = new HashSet<>();
            for (Part lk : parts) {
                if (lk.kind() != PartKind.LINK || done.contains(lk.id())) continue;
                // the connected link parts; a bar is a simple path
                List<Part> comp = new ArrayList<>();
                Deque<Part> todo = new ArrayDeque<>(List.of(lk));
                done.add(lk.id());
                boolean branched = false;
                while (!todo.isEmpty()) {
                    Part p = todo.poll();
                    comp.add(p);
                    List<Part> nb = linkNeighbours(p);
                    if (nb.size() > 2) branched = true;
                    for (Part q : nb) if (done.add(q.id())) todo.add(q);
                }
                Part first = null;
                for (Part p : comp) if (linkNeighbours(p).size() <= 1 && (first == null || p.id() < first.id())) first = p;
                if (branched || first == null) {
                    c.warnings.add("Link #" + lk.id() + ": link parts touching each other form one bar, a single path from end to end (this one branches or closes)");
                    continue;
                }
                List<Part> run = new ArrayList<>(List.of(first));
                for (Part prev = null, cur = first; ; ) {
                    Part next = null;
                    for (Part q : linkNeighbours(cur)) if (q != prev) next = q;
                    if (next == null) break;
                    run.add(next);
                    prev = cur;
                    cur = next;
                }
                Part last = run.get(run.size() - 1);
                Dir outA, outB;
                if (run.size() == 1) {
                    outA = Dir.fromAxis(first.axis().axis(), false);
                    outB = outA.opposite();
                } else {
                    outA = faceToward(first, run.get(1)).opposite();
                    outB = faceToward(last, run.get(run.size() - 2)).opposite();
                }
                Vec3 pinA = faceCentre(first, outA), pinB = faceCentre(last, outB);
                CompiledAssembly.Body[] end = {faceBody(first, outA), faceBody(last, outB)};
                for (int s = 0; s < 2; s++) if (end[s] == null) end[s] = ground;
                if (end[0] == end[1]) continue; // both pins on one body: it holds them anyway
                Vec3[] pin = {pinA, pinB};
                // the frame each end moves in: its carrier's — but a sleeve sliding along the axis of the spindle that
                // carries it, pinned on that axis, moves only along it as the spindle turns, as if it slid on the frame
                // (the pin rides in a groove round it: a thrust collar)
                CompiledAssembly.Body[] in = new CompiledAssembly.Body[2];
                for (int s = 0; s < 2; s++) in[s] = onSpinAxis(end[s], pin[s]) ? null : end[s].carrier;
                boolean together = (in[0] == null && in[1] == null) || in[0] == in[1] || in[0] == end[1] || in[1] == end[0];
                if (!together) {
                    c.warnings.add("Link #" + lk.id() + " joins a body riding on a moving one to something it moves past (not modelled)");
                    continue;
                }
                CompiledAssembly.Body frame = in[0] != null ? in[0] : in[1];
                RigidLink.End[] e = new RigidLink.End[2];
                boolean ok = true;
                for (int s = 0; s < 2; s++) {
                    CompiledAssembly.Body b = end[s];
                    if (b.isStatic || b == frame) e[s] = RigidLink.End.fixed(pin[s]);
                    else if (b.slider) e[s] = RigidLink.End.slide(b.node, b.tracker, b.axis, pin[s]);
                    else if (b.tracker != null) e[s] = RigidLink.End.rotor(b.node, b.tracker, b.axis, b.pivot, pin[s]);
                    else ok = false;
                }
                if (!ok || (e[0].kind() == RigidLink.Kind.FIXED && e[1].kind() == RigidLink.Kind.FIXED)) continue;
                RigidLink link = c.network.add(new RigidLink(e[0], e[1]));
                CompiledAssembly.Body home = frame == null ? ground : frame;
                for (int k = 0; k < run.size(); k++) {
                    // the bar is drawn in the frame its pins move in (it would otherwise stay behind on the ground while
                    // a carrying spindle turns), each piece of the path drawing its share of it
                    Part p = run.get(k);
                    CompiledAssembly.Body was = c.bodyOfPart.get(p.id());
                    if (was != null && was != home) {
                        was.parts.remove(Long.valueOf(p.id()));
                        home.parts.add(p.id());
                        c.bodyOfPart.put(p.id(), home);
                    }
                    c.links.put(p.id(), new CompiledAssembly.LinkPiece(link, (double) k / run.size(), (k + 1.0) / run.size(), p.center()));
                }
            }
        }

        boolean isBearingJointPair(Part p, Part q) {
            return isBearingJoint(p, q);
        }

        /**
         * A spur gear meshing with a rack: the gear's axis crosses the bar, the bar's toothed face looks at the gear,
         * the gear's face width overlaps the bar and its pitch circle touches the toothed face. The rack's slide is
         * stopped before the gear would run off its teeth.
         */
        void tryRack(Part g, Part r) {
            CompiledAssembly.Body bg = c.bodyOfPart.get(g.id()), br = c.bodyOfPart.get(r.id());
            if (bg == null || br == null || bg == br || bg.slider) return;
            Vec3 u = r.axis().vec(), n = Vec3.axis(g.axis().axis()), w = PartGeometry.rackFace(r).vec();
            if (Math.abs(u.dot(n)) > 1e-9 || Math.abs(w.dot(u)) > 1e-9 || Math.abs(w.dot(n)) > 1e-9) return;
            int ax = g.axis().axis();
            int lo1 = ax == 0 ? g.minX() : ax == 1 ? g.minY() : g.minZ(), hi1 = ax == 0 ? g.maxX() : ax == 1 ? g.maxY() : g.maxZ();
            int lo2 = ax == 0 ? r.minX() : ax == 1 ? r.minY() : r.minZ(), hi2 = ax == 0 ? r.maxX() : ax == 1 ? r.maxY() : r.maxZ();
            if (hi1 < lo2 || hi2 < lo1) return; // not in the same plane
            double rp = PartGeometry.gearPitchRadius(g), m = 2 * rp / PartGeometry.gearTeeth(g), mr = PartGeometry.rackModule(r);
            Vec3 d = g.center().sub(r.center());
            int wa = PartGeometry.rackFace(r).axis(), ua = r.axis().axis();
            double off = d.dot(w) - 0.5 * r.boxSize(wa), along = d.dot(u), half = 0.5 * r.boxSize(ua);
            if (Math.abs(off - rp) > 0.3 * m || Math.abs(along) > half) return;
            // the whole bar: the run of rack segments in line with this one, teeth on the same face
            double[] run = rackRun(r);
            along = g.center().dot(u) - 0.5 * (run[0] + run[1]);
            half = 0.5 * (run[1] - run[0]);
            if (Math.abs(m - mr) > 0.1 * Math.max(m, mr)) {
                c.warnings.add("Gear #" + g.id() + " and rack #" + r.id() + " have different tooth modules and cannot mesh");
                return;
            }
            if (!br.slider || br.carrier != null || Math.abs(Math.abs(br.axis.dot(u)) - 1) > 1e-9) {
                c.warnings.add("Rack #" + r.id() + " must ride on a slide running along its length");
                return;
            }
            if (bg.carrier != null) {
                c.warnings.add("Gear #" + g.id() + " rides on a slide and is not meshed with rack #" + r.id());
                return;
            }
            // the gear's tooth at the contact point (−w·r_p from its centre) moves at −ω·r_p·(A × w); the rack at v·S
            Vec3 axisA = bg.isStatic ? n : bg.axis;
            double k = axisA.cross(w).dot(u), q = br.axis.dot(u);
            Node ng = bg.isStatic ? refR : bg.node;
            boolean lub = g.param("lubricated", 1) > 0 || r.param("lubricated", 1) > 0;
            double mu = lub ? 0.05 : Math.max(g.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4),
                    r.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4));
            c.network.add(GearMesh.rackAndPinion(ng, br.node, rp, PartGeometry.gearTeeth(g), Math.signum(k * q), mu,
                    c.thermalOfPart.get(g.id())));
            thermalLink(g, r);
            // keep a tooth's width of rack under the gear: the contact sits at along − x·q along the bar
            double h = Math.max(half - m * Math.PI / 2, 0);
            double x1 = q > 0 ? along - h : -along - h, x2 = q > 0 ? along + h : -along + h;
            c.network.add(new TravelStops(br.node, br.tracker, x1, x2, 2e6, 200, null));
        }

        /** Extent {lo, hi} along the bar's (positive) axis [m] of the run of rack segments in line with r. */
        double[] rackRun(Part r) {
            Dir ax = r.axis();
            Vec3 u = ax.vec();
            double lo = r.center().dot(u) - 0.5 * r.boxSize(ax.axis()), hi = lo + r.boxSize(ax.axis());
            for (int dir : new int[]{-1, 1}) {
                Part cur = r;
                for (int n = 0; n < 4096; n++) {
                    int x = (dir > 0 ? cur.maxX() : cur.minX()) + ax.dx * dir, y = (dir > 0 ? cur.maxY() : cur.minY()) + ax.dy * dir,
                            z = (dir > 0 ? cur.maxZ() : cur.minZ()) + ax.dz * dir;
                    Part next = null;
                    for (Part q : a.at(x, y, z)) {
                        if (q.kind() == PartKind.RACK && q.axis().axis() == ax.axis() && PartGeometry.rackFace(q) == PartGeometry.rackFace(r)) next = q;
                    }
                    if (next == null) break;
                    cur = next;
                    double c0 = cur.center().dot(u), h0 = 0.5 * cur.boxSize(ax.axis());
                    lo = Math.min(lo, c0 - h0);
                    hi = Math.max(hi, c0 + h0);
                }
            }
            return new double[]{lo, hi};
        }

        /**
         * A nut on a shaft: the screw turning in bearings and the nut on a slide along it. Turning the screw by +ω
         * about +u (right-hand thread) screws it forward along +u through the nut, so the nut moves at
         * −(lead/2π)·ω·(A·u) along u relative to it.
         */
        void buildLeadScrew(Part nut) {
            Part screw = null;
            for (Part q : a.neighbors(nut)) {
                if (q.kind() == PartKind.SHAFT && q.overlaps(nut) && q.axis().axis() == nut.axis().axis()) screw = q;
            }
            if (screw == null) {
                for (Part q : parts) {
                    if (q.kind() == PartKind.SHAFT && q.overlaps(nut) && q.axis().axis() == nut.axis().axis()) screw = q;
                }
            }
            if (screw == null) {
                c.warnings.add("Nut #" + nut.id() + " is not on a shaft running along its axis");
                return;
            }
            CompiledAssembly.Body bs = c.bodyOfPart.get(screw.id()), bn = c.bodyOfPart.get(nut.id());
            if (bs == null || bn == null || bs == bn) return;
            Vec3 u = nut.axis().vec();
            boolean screwTurns = !bs.isStatic && !bs.slider && Math.abs(Math.abs(bs.axis.dot(u)) - 1) < 1e-9;
            boolean nutSlides = bn.slider && bn.carrier == null && Math.abs(Math.abs(bn.axis.dot(u)) - 1) < 1e-9;
            if (!nutSlides || !(screwTurns || bs.isStatic) || bs.carrier != null) {
                c.warnings.add("Nut #" + nut.id() + ": a lead screw needs the screw turning in bearings and the nut on a slide along it");
                return;
            }
            double d = PartGeometry.shaftDiameter(screw), lead = PartGeometry.nutLead(nut, d);
            double dm = d - 0.5 * Math.min(lead, 0.25 * d); // trapezoidal thread, depth half the pitch (multi-start: capped)
            double hand = nut.param("left", 0) > 0 ? -1 : 1;
            double aDotU = bs.isStatic ? 1 : bs.axis.dot(u), q = bn.axis.dot(u);
            // (lead/2π)·ω·hand·(A·u) + v·(S·u) = 0
            double sense = Math.signum(hand * aDotU * q);
            boolean lub = nut.param("lubricated", 1) > 0;
            // threaded pairs, steel screw (Shigley Table 8-5): bronze nut 0.10–0.16 oiled; dry, the nut's own friction
            double mu = lub ? 0.13 : nut.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.19);
            c.network.add(GearMesh.leadScrew(bs.isStatic ? refR : bs.node, bn.node, lead, dm, Math.toRadians(15),
                    sense, mu, c.thermalOfPart.get(nut.id())));
            thermalLink(screw, nut);
        }

        void tryMesh(Part g1, Part g2) {
            CompiledAssembly.Body b1 = c.bodyOfPart.get(g1.id()), b2 = c.bodyOfPart.get(g2.id());
            if (b1 == b2 || b1.slider || b2.slider) return;
            if (g1.axis().axis() != g2.axis().axis()) {
                tryBevel(g1, g2, b1, b2);
                return;
            }
            if (!sameFrame(b1, b2)) {
                c.warnings.add("Gears #" + g1.id() + " and #" + g2.id() + ": one rides on a slide past the other and they are not meshed");
                return;
            }
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

        /**
         * Bevel gears: two gears on perpendicular axes that cross (their pitch cones share an apex) mesh where each one's
         * pitch circle passes through the other's: gear 2's centre lies r₁ from gear 1's along gear 2's axis and r₂ from it
         * along gear 1's (and level with it the third way). Their pitch points then roll together at the contact, so
         * r₁·ω₁ and r₂·ω₂ agree there — the sign from which way each turns past it.
         */
        void tryBevel(Part g1, Part g2, CompiledAssembly.Body b1, CompiledAssembly.Body b2) {
            Vec3 a1 = g1.axis().vec(), a2 = g2.axis().vec();
            Vec3 u = g2.center().sub(g1.center());
            double r1 = PartGeometry.gearPitchRadius(g1), r2 = PartGeometry.gearPitchRadius(g2);
            int z1 = PartGeometry.gearTeeth(g1), z2 = PartGeometry.gearTeeth(g2);
            double m1 = 2 * r1 / z1, m2 = 2 * r2 / z2, tol = 0.3 * Math.max(m1, m2);
            double h2 = u.dot(a2), h1 = u.dot(a1), across = u.sub(a1.mul(h1)).sub(a2.mul(h2)).length();
            if (across > tol || Math.abs(Math.abs(h2) - r1) > tol || Math.abs(Math.abs(h1) - r2) > tol) return;
            if (Math.abs(m1 - m2) > 0.1 * Math.max(m1, m2)) {
                c.warnings.add("Bevel gears #" + g1.id() + " and #" + g2.id() + " have different tooth modules and cannot mesh");
                return;
            }
            if (!sameFrame(b1, b2)) {
                c.warnings.add("Bevel gears #" + g1.id() + " and #" + g2.id() + ": one is carried past the other and they are not meshed");
                return;
            }
            // the contact: on gear 1's pitch circle towards gear 2 (along gear 2's axis), and the common tangent there
            Vec3 p = g1.center().add(a2.mul(Math.signum(h2) * r1));
            Vec3 t = a1.cross(a2);
            // each body's ω is about its own axis vector: the pitch point's speed along t per unit ω
            CompiledAssembly.Body ra = b1, rb = b2;
            Vec3 w1 = ra.isStatic ? a1 : ra.axis, w2 = rb.isStatic ? a2 : rb.axis;
            double k1 = w1.cross(p.sub(g1.center())).dot(t), k2 = w2.cross(p.sub(g2.center())).dot(t);
            // rolling: k1·ω1 = k2·ω2, i.e. r1·ω1 + s·r2·ω2 = 0 with s = −sign(k1)·sign(k2)
            double s = -Math.signum(k1) * Math.signum(k2);
            Node na = ra.isStatic ? refR : ra.node, nb = rb.isStatic ? refR : rb.node;
            boolean lub = g1.param("lubricated", 1) > 0 || g2.param("lubricated", 1) > 0;
            double mu = lub ? 0.05 : Math.max(g1.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4),
                    g2.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4));
            c.network.add(GearMesh.bevel(na, nb, r1, r2, z1, z2, s, mu, c.thermalOfPart.get(g1.id())));
            c.bevels++;
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

        /**
         * A ribbon meeting something: another ribbon joins it conductor to conductor; an insulated wire joins the
         * conductor of its colour. Nothing else connects to a ribbon (its conductors are insulated). A conductor's
         * few milliohms a block are left out: one node.
         */
        void ribbonJoin(Part p, Part q) {
            if (p.kind() == PartKind.RIBBON && q.kind() == PartKind.RIBBON) {
                long a = Math.min(p.id(), q.id()), b = Math.max(p.id(), q.id());
                for (long[] l : ribbonLinks) if (l[0] == a && l[1] == b) return;
                ribbonLinks.add(new long[]{a, b});
                return;
            }
            Part r = p.kind() == PartKind.RIBBON ? p : q, w = r == p ? q : p;
            if (w.kind() != PartKind.INSULATED_WIRE) return;
            long k = Math.round(w.param("color", 0));
            emerge("p" + r.id() + "c" + k, "p" + w.id());
            ribbonUsed.add(new long[]{r.id(), k});
        }

        /** Ribbon blocks side by side, and the (ribbon, conductor) pairs a wire joins (see buildRibbonConductors). */
        final List<long[]> ribbonLinks = new ArrayList<>(), ribbonUsed = new ArrayList<>();

        /** Copper's cross-section in a 16-way ribbon's conductor [m²] (28 AWG, 7 × 0.127 mm). */
        static final double RIBBON_CONDUCTOR_AREA = 0.081e-6;

        /**
         * A ribbon's conductors along it: between each block and the next, for every conductor some wire joins somewhere
         * along that run of ribbon, its copper's resistance from centre to centre (heating the ribbon). Conductors
         * nothing joins make no nodes.
         */
        void buildRibbonConductors() {
            if (ribbonLinks.isEmpty()) return;
            Map<Long, Long> parent = new HashMap<>();
            java.util.function.LongUnaryOperator find = new java.util.function.LongUnaryOperator() {
                public long applyAsLong(long x) {
                    long r = x;
                    while (parent.getOrDefault(r, r) != r) r = parent.get(r);
                    parent.put(x, r);
                    return r;
                }
            };
            for (long[] l : ribbonLinks) {
                long ra = find.applyAsLong(l[0]), rb = find.applyAsLong(l[1]);
                if (ra != rb) parent.put(ra, rb);
            }
            Map<Long, Set<Long>> used = new HashMap<>();
            for (long[] u : ribbonUsed) used.computeIfAbsent(find.applyAsLong(u[0]), k -> new HashSet<>()).add(u[1]);
            Material cu = MaterialDatabase.standard().get("copper");
            for (long[] l : ribbonLinks) {
                Part a = partById(l[0]);
                if (a == null) continue;
                double r = cu.resistivity(ambientT) * CELL / RIBBON_CONDUCTOR_AREA;
                for (long k : used.getOrDefault(find.applyAsLong(l[0]), Set.of())) {
                    c.network.add(new Resistor(enode("p" + l[0] + "c" + k), enode("p" + l[1] + "c" + k), r, c.thermalOfPart.get(a.id())));
                }
            }
        }

        Part partById(long id) {
            for (Part p : parts) if (p.id() == id) return p;
            return null;
        }

        void emerge(String x, String y) {
            String rx = eroot(x), ry = eroot(y);
            if (!rx.equals(ry)) eunion.put(rx, ry);
        }

        Node enode(String key) {
            return enodes.computeIfAbsent(eroot(key), k -> c.network.node(Domain.ELECTRICAL, k));
        }

        static boolean conductive(Part p) {
            return conductorMaterial(p).isConductor();
        }

        boolean isBulk(Part p) {
            return p.kind().structural || p.kind() == PartKind.MAGNET;
        }

        /** Port key of part p on face d of cell (x,y,z), or null if p has no electrical port there. */
        /** Whether a part is a single-cell two-terminal component, whose leads come out on its sides as well as its ends. */
        static boolean sideLeaded(Part p) {
            return switch (p.kind()) {
                case RESISTOR, CAPACITOR, BATTERY, DIODE, LED, PHOTODIODE, GM_TUBE, SWITCH, COIL -> p.minX() == p.maxX() && p.minY() == p.maxY() && p.minZ() == p.maxZ();
                default -> false;
            };
        }

        static String port(Part p, int x, int y, int z, Dir d) {
            switch (p.kind()) {
                case WIRE, INSULATED_WIRE -> {
                    return "p" + p.id();
                }
                case RIBBON -> {
                    return "p" + p.id() + "r"; // a placeholder: which conductor depends on what it meets (ribbonJoin)
                }
                case OPAMP -> {
                    // like a single-gate logic package: in+ (a) and out (o) on the axial faces, V−/V+ on the lead-axis
                    // sides, in− (b) on the −side of the remaining axis
                    Dir ax = p.axis();
                    int la = PartGeometry.leadAxis(p);
                    if (d.axis() == ax.axis()) return "p" + p.id() + (d.sign() == ax.sign() ? "o" : "a");
                    if (d.axis() == la) return "p" + p.id() + (d.sign() > 0 ? "v" : "n");
                    return d.sign() < 0 ? "p" + p.id() + "b" : null;
                }
                case POTENTIOMETER -> {
                    // the shaft passes through the axial faces; track ends on the lead-axis sides, wiper on the −side
                    // of the remaining axis
                    if (d.axis() == p.axis().axis()) return null;
                    if (d.axis() == PartGeometry.leadAxis(p)) return "p" + p.id() + (d.sign() > 0 ? "+" : "-");
                    return d.sign() < 0 ? "p" + p.id() + "w" : null;
                }
                case LOGIC -> {
                    Dir ax = p.axis();
                    int la = PartGeometry.leadAxis(p);
                    if (d.axis() == ax.axis()) return "p" + p.id() + (d.sign() == ax.sign() ? "o" : "a");
                    if (d.axis() == la) return "p" + p.id() + (d.sign() > 0 ? "v" : "n");
                    int fn = (int) Math.round(p.param("function", 2));
                    if (d.sign() < 0) return fn >= 2 && fn != 7 ? "p" + p.id() + "b" : null;
                    return fn == 8 ? "p" + p.id() + "q" : null;
                }
                case CHIP -> {
                    // input pin on the −lead-axis side, output pin on the +lead-axis side
                    if (d.axis() != PartGeometry.leadAxis(p)) return null;
                    return "p" + p.id() + (d.sign() > 0 ? "o" : "i");
                }
                case TRANSISTOR -> {
                    // emitter/source (−) and collector/drain (+) on the axial faces, base/gate on the lead-axis sides
                    Dir ax = p.axis();
                    if (d.axis() == ax.axis()) return "p" + p.id() + (d.sign() == ax.sign() ? "+" : "-");
                    if (d.axis() == PartGeometry.leadAxis(p)) return "p" + p.id() + "g";
                    return null;
                }
                case SOURCE -> {
                    return null; // a sealed capsule: no electrical contact
                }
                case DISPLAY -> {
                    return null; // the screen: its controller chip has the pins
                }
                case PRESSURE_SENSOR -> {
                    // output on the +axis face, GND/V_DD on the lead-axis sides; the −axis face is its fluid port
                    Dir ax = p.axis();
                    if (d.axis() == ax.axis()) return d.sign() == ax.sign() ? "p" + p.id() + "o" : null;
                    if (d.axis() == PartGeometry.leadAxis(p)) return "p" + p.id() + (d.sign() > 0 ? "v" : "n");
                    return null;
                }
                case RESISTOR, CAPACITOR, BATTERY, DIODE, LED, PHOTODIODE, GM_TUBE, SWITCH, COIL, BRUSH -> {
                    Dir ax = p.axis();
                    int a0 = ax.axis();
                    int[] cell = {x, y, z};
                    int lo = a0 == 0 ? p.minX() : a0 == 1 ? p.minY() : p.minZ();
                    int hi = a0 == 0 ? p.maxX() : a0 == 1 ? p.maxY() : p.maxZ();
                    boolean posAxis = ax.sign() > 0;
                    if (p.kind() == PartKind.BRUSH && d == ax) return null; // the brush face slides: see buildBrushContacts
                    // a coil wound on a core: the core runs on through its end faces; its leads leave at the sides
                    if (p.kind() == PartKind.COIL && p.param("core", 0) > 0 && d.axis() == a0) return null;
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

        // ---- event-driven chips (functions 5–8)

        /** An event-driven chip package: its segments (first to last) and what runs it. */
        record DigitalPackage(List<Part> seg, DigitalComponent comp, String label) { }

        final Map<Long, DigitalPackage> digitalPackages = new LinkedHashMap<>();
        /** Signal pin keys of event-driven chips ("p{segment}i" / "p{segment}o"). */
        final Set<String> digitalPinKeys = new HashSet<>();
        final Map<String, DigitalDomain.Net> digitalNetOfRoot = new HashMap<>(), digitalNetsByGroup = new HashMap<>();
        final Set<String> connectedRoots = new HashSet<>();

        /** The packages of event-driven chips, each with its component, and their signal pins' keys. */
        void prepareDigitalPackages() {
            if (!digitalPackages.isEmpty()) return;
            for (Part p : parts) {
                if (p.kind() != PartKind.CHIP || chipNeighbour(p, -1) != null) continue;
                int fn = (int) Math.round(p.param("function", 0));
                List<Part> seg = new ArrayList<>();
                for (Part q = p; q != null && seg.size() < 128; q = chipNeighbour(q, 1)) seg.add(q);
                // a design or a ROM image is the first segment's text (or any segment's, if that one has none)
                String text = p.text();
                if (text == null || text.isBlank()) for (Part q : seg) if (q.text() != null && !q.text().isBlank()) text = q.text();
                ChipTypes.Pins pins = ChipTypes.pins(fn, text);
                if (pins.error() != null) {
                    c.warnings.add("Chip #" + p.id() + " (" + pins.label() + "): " + pins.error());
                    continue;
                }
                String label = pins.label();
                if (fn == ChipTypes.ROM256 && ChipTypes.romError(text) != null) {
                    c.warnings.add("Chip #" + p.id() + " (" + label + "): its contents are not hex, " + ChipTypes.romError(text));
                }
                int[] rom16 = new int[16];
                for (Part q : seg) for (int w = 0; w < 16; w++) if (q.params().containsKey("w" + w)) rom16[w] = (int) Math.round(q.param("w" + w, 0));
                DigitalComponent comp = ChipTypes.create(fn, p.params(), text, rom16);
                int ni = comp.inputs().size(), no = comp.outputs().size(), need = pins.segments();
                if (seg.size() != need) {
                    c.warnings.add("Chip #" + p.id() + " (" + label + ") needs " + need + " segments in a row, has " + seg.size());
                    continue;
                }
                digitalPackages.put(p.id(), new DigitalPackage(seg, comp, label));
                for (int k = 0; k < ni; k++) digitalPinKeys.add("p" + seg.get(k).id() + "i");
                for (int k = 0; k < no; k++) digitalPinKeys.add("p" + seg.get(k).id() + "o");
            }
        }

        /**
         * An event-driven chip: its pins on digital nets join them; those on analog nets get nodes (and a CMOS input's
         * 5 pF); unconnected ones neither (an input then reads low).
         */
        void buildDigitalChip(Part p, Node th) {
            DigitalPackage pk = digitalPackages.get(p.id());
            List<Part> seg = pk.seg();
            DigitalComponent comp = pk.comp();
            int slot = c.digital.add(comp);
            Part last = seg.get(seg.size() - 1);
            Node gnd = enode("p" + last.id() + "i"), vdd = enode("p" + last.id() + "o");
            int ni = comp.inputs().size(), no = comp.outputs().size();
            Node[] in = new Node[ni], out = new Node[no];
            for (int k = 0; k < ni; k++) {
                String key = "p" + seg.get(k).id() + "i", root = eroot(key);
                DigitalDomain.Net n = digitalNetOfRoot.get(root);
                if (n != null) c.digital.connectInput(slot, k, n);
                else if (connectedRoots.contains(root)) in[k] = enode(key);
            }
            for (int k = 0; k < no; k++) {
                String key = "p" + seg.get(k).id() + "o", root = eroot(key);
                DigitalDomain.Net n = digitalNetOfRoot.get(root);
                if (n != null) c.digital.connectOutput(slot, k, n);
                else if (connectedRoots.contains(root)) out[k] = enode(key);
            }
            // a chip's state as it was (registers, memories, a display's image)
            Double len = prev.chemistry().get("R" + p.id() + ":n");
            if (len != null) {
                long[] st = new long[(int) Math.round(len)];
                for (int i = 0; i < st.length; i++) {
                    long lo = Math.round(prev.chemistry().getOrDefault("R" + p.id() + ":w" + (2 * i), 0.0));
                    long hi = Math.round(prev.chemistry().getOrDefault("R" + p.id() + ":w" + (2 * i + 1), 0.0));
                    st[i] = (lo & 0xffffffffL) | (hi << 32);
                }
                comp.restore(st);
            } else if (comp instanceof MsiComponent m && prev.chemistry().containsKey(AssemblyState.registerKey(p.id(), 0))) {
                // saved by the analog model of the MSI chips (bit i of its registers, or of its RAM)
                double[] bits = new double[64];
                for (int i = 0; i < 64; i++) bits[i] = prev.chemistry().getOrDefault(AssemblyState.registerKey(p.id(), i), 0.0);
                m.restoreBits(bits);
            }
            DigitalChip chip = c.network.add(new DigitalChip(c.digital, slot, vdd, gnd, in, out, th, pk.label()));
            if (p.params().containsKey("output_resistance")) chip.outputResistance(p.param("output_resistance", 50));
            c.digitalChips.put(p.id(), chip);
            for (int k = 0; k < seg.size(); k++) {
                Part q = seg.get(k);
                c.deviceOfPart.put(q.id(), chip);
                c.packageOfPart.put(q.id(), p.id());
                Node a = k < ni && in[k] != null ? in[k] : q == last ? gnd : null, b = k < no && out[k] != null ? out[k] : q == last ? vdd : null;
                c.leadsOfPart.put(q.id(), new Node[]{a != null ? a : gnd, b != null ? b : gnd});
            }
            for (Node n : in) if (n != null) c.network.add(Capacitor.parasitic(n, gnd, 5e-12));
            c.network.add(new Capacitor(vdd, gnd, 100e-9));
            if (comp instanceof DisplayPanel dp) displayControllers.put(p.id(), dp);
        }

        final Map<Long, DisplayPanel> displayControllers = new LinkedHashMap<>();

        /** Each display panel is driven by the display controller chip touching it. */
        void buildDisplays() {
            // panels side by side in one plane, facing the same way, are one screen (tiles of it)
            List<Part> panels = new ArrayList<>();
            for (Part d : parts) if (d.kind() == PartKind.DISPLAY) panels.add(d);
            for (List<Part> screen : DisplayTiles.screens(panels)) {
                DisplayPanel found = null;
                search:
                for (var en : displayControllers.entrySet()) {
                    for (Part q : digitalPackages.get(en.getKey()).seg()) {
                        for (Part d : screen) {
                            if (q.touches(d) && !q.overlaps(d)) {
                                found = en.getValue();
                                break search;
                            }
                        }
                    }
                }
                // (a screen no controller here touches is dark, as a monitor with nothing plugged in, or driven from a
                // machine at the other scale: see the game's display links)
                if (found == null) continue;
                for (var t : DisplayTiles.tiles(screen).entrySet()) {
                    c.displays.put(t.getKey(), found);
                    c.displayTiles.put(t.getKey(), t.getValue());
                }
            }
        }

        /** A pressure transducer on the vessel or pipe at its −axis face. */
        void buildPressureSensor(Part p) {
            Dir ax = p.axis();
            Node pressure = null;
            for (Part q : parts) {
                if (!q.kind().holdsFluid() || !q.touches(p) || q.overlaps(p)) continue;
                // on the port side: the part's centre lies behind the sensor's −axis face
                double along = (q.center().x() - p.center().x()) * ax.dx + (q.center().y() - p.center().y()) * ax.dy
                        + (q.center().z() - p.center().z()) * ax.dz;
                if (along >= 0) continue;
                // a water/steam (or other single-fluid) volume's pressure, or a gas mixture's (a fuel bottle's)
                FluidVolume v = c.fluidOfPart.get(q.id());
                var g = c.gasOfPart.get(q.id());
                pressure = v != null ? v.pressureNode() : g != null ? g.pressureNode() : null;
                if (pressure != null) break;
            }
            Node out = enode("p" + p.id() + "o"), gnd = enode("p" + p.id() + "n"), vdd = enode("p" + p.id() + "v");
            c.leadsOfPart.put(p.id(), new Node[]{gnd, out});
            if (pressure == null) {
                c.warnings.add("Pressure sensor #" + p.id() + " has no vessel or pipe on its port (its −axis face)");
                return;
            }
            c.deviceOfPart.put(p.id(), c.network.add(new dev.emergence.core.sim.elements.PressureTransducer(pressure, vdd, gnd, out,
                    a.ambientPressure(), p.param("pmin", 0), p.param("pmax", 10e5))));
        }

        /** The chip segment adjacent to p one cell along {@code dir} that belongs to the same package, or null. */
        Part chipNeighbour(Part p, int dir) {
            Dir ax = p.axis();
            int x = p.minX() + ax.dx * dir, y = p.minY() + ax.dy * dir, z = p.minZ() + ax.dz * dir;
            for (Part q : a.at(x, y, z)) {
                if (q.kind() == PartKind.CHIP && q.axis() == ax && PartGeometry.leadAxis(q) == PartGeometry.leadAxis(p)
                        && Math.round(q.param("function", 0)) == Math.round(p.param("function", 0))) return q;
            }
            return null;
        }

        /** Leads are at the transverse centre cell of each end face. */
        static boolean isLeadCell(Part p, int[] cell) {
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
            // slide: contacts across a slide's direction of travel, closing at its end towards one another (end, sense)
            record Contact(Part p, String pk, Part q, String qk, boolean sliding, CompiledAssembly.Body slide, double end, double sense) {
                Contact(Part p, String pk, Part q, String qk, boolean sliding) {
                    this(p, pk, q, qk, sliding, null, 0, 0);
                }
            }
            List<Contact> contacts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // a single-cell component's leads come out on its sides too (see port): what reaches each lead by its end
            // and by its side, to warn of joins that are likely accidents (a wire of another net beside a component, two
            // components side by side)
            Map<String, Set<Long>> endColours = new HashMap<>(), sideColours = new HashMap<>();
            Map<String, Part> leadOwner = new HashMap<>();
            Set<String> sideBySide = new java.util.LinkedHashSet<>();
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
                                    CompiledAssembly.Body slide = null;
                                    double end = 0, sense = 0;
                                    if (qk == null) continue;
                                    if (p.kind() == PartKind.RIBBON || q.kind() == PartKind.RIBBON) {
                                        ribbonJoin(p, q);
                                        continue;
                                    }
                                    if (!compatible(p, q)) continue;
                                    // a thin shaft physically touches neighbours only at its ends (or hubs on it)
                                    if (!shaftReaches(p, q) || !shaftReaches(q, p)) continue;
                                    CompiledAssembly.Body bp = c.bodyOfPart.get(p.id()), bq = c.bodyOfPart.get(q.id());
                                    // a flexible lead conducts across a moving joint, whatever moves
                                    if (bp != bq && !isFlexibleLead(p) && !isFlexibleLead(q)) {
                                        // only a brush may conduct across a moving joint, through its contact face —
                                        // or contacts a slide brings together across its direction of travel
                                        Part brush = p.kind() == PartKind.BRUSH ? p : q.kind() == PartKind.BRUSH ? q : null;
                                        // (a slide on a turning body against that body: a governor's or a motor's
                                        // centrifugal switch — the contacts turn together, the slide's travel is relative)
                                        CompiledAssembly.Body sp = slideAgainst(bp, bq) ? bp : slideAgainst(bq, bp) ? bq : null;
                                        if (brush == null && sp != null && Math.abs(Math.abs(sp.axis.dot(d.vec())) - 1) < 1e-9
                                                && !isJournal(p) && !isJournal(q)) {
                                            // the slide's part moves towards the other along +sense·axis
                                            Vec3 toward = sp == bp ? d.vec() : d.vec().mul(-1);
                                            sense = Math.signum(toward.dot(sp.axis));
                                            end = sense > 0 ? sp.strokeHi : sp.strokeLo;
                                            slide = sp;
                                        } else {
                                            if (brush == null) continue;
                                            Part other = brush == p ? q : p;
                                            if (!isBulk(other) || !inFrontOf(brush, other)) continue;
                                            sliding = true;
                                        }
                                    }
                                    String key = pk.compareTo(qk) < 0 ? pk + "|" + qk : qk + "|" + pk;
                                    if (!seen.add(key)) continue;
                                    contacts.add(new Contact(p, pk, q, qk, sliding, slide, end, sense));
                                    if (sideLeaded(p)) {
                                        boolean side = d.axis() != p.axis().axis();
                                        if (q.kind() == PartKind.INSULATED_WIRE) {
                                            (side ? sideColours : endColours).computeIfAbsent(pk, k -> new HashSet<>()).add(Math.round(q.param("color", 0)));
                                            leadOwner.put(pk, p);
                                        } else if (side && sideLeaded(q) && d.axis() != q.axis().axis() && p.id() < q.id()) {
                                            sideBySide.add(p.kind().name().toLowerCase(Locale.ROOT) + " #" + p.id() + " and " + q.kind().name().toLowerCase(Locale.ROOT)
                                                    + " #" + q.id());
                                        }
                                    }
                                }
                            }
            }
            for (var en : sideColours.entrySet()) {
                Set<Long> ends = endColours.get(en.getKey());
                if (ends == null) continue;
                for (long col : en.getValue()) {
                    if (ends.contains(col)) continue;
                    Part p = leadOwner.get(en.getKey());
                    c.warnings.add(String.format("%s #%d: a lead is reached at its end by wire of colour %s and on its side by colour %d — a single-cell "
                            + "part's leads come out on its sides too, so the two wires join there (move one aside if they are not one net)",
                            p.kind().name().toLowerCase(Locale.ROOT), p.id(), ends, col));
                    break;
                }
            }
            for (String pair : sideBySide)
                c.warnings.add("The " + pair + " touch side by side: single-cell parts' leads come out on their sides too, so their leads join "
                        + "there (a row apart, they do not)");
            // ---- joins across scales: a full-size ribbon's conductors to the miniature wires against its face
            for (long[] br : a.bridges()) {
                Part r = partById(br[0]), w = partById(br[1]);
                if (r != null && w != null) ribbonJoin(r, w);
            }
            // ---- zero-resistance joins (lead to lead), and solid metal pieces in contact whose resistance is a few
            // micro-ohms: one node, not a 10⁷ S conductance — which would leave a circuit's potential determined only
            // to its roundoff where a nearly open element (an open contact) is all that holds it. Their Joule heat is
            // negligible; their junction's thermoelectric EMF is kept only where the Seebeck coefficients differ
            // (a thermoelement against its copper strap), not between ordinary metals.
            for (Contact k : contacts) {
                if (k.sliding || k.slide != null) continue;
                if (isLeadKey(k.pk) && isLeadKey(k.qk)) {
                    emerge(k.pk, k.qk);
                    continue;
                }
                if (!isBulk(k.p) || !isBulk(k.q) || isWire(k.p) || isWire(k.q)) continue;
                Material mp = conductorMaterial(k.p), mq = conductorMaterial(k.q);
                double r = mp.resistivity(ambientT) * nodeToFace(k.p) / conductorArea(k.p)
                        + mq.resistivity(ambientT) * nodeToFace(k.q) / conductorArea(k.q);
                double ds = Math.abs(mp.get(MaterialProperty.SEEBECK_COEFFICIENT, 0) - mq.get(MaterialProperty.SEEBECK_COEFFICIENT, 0));
                if (r < BULK_MERGE_OHMS && ds < 2e-5) emerge(k.pk, k.qk);
            }
            // a wire run is one node
            for (Part p : parts) {
                WireRun run = runOf.get(p.id());
                if (run != null && run.parts.get(0) != p) emerge("p" + p.id(), "p" + run.parts.get(0).id());
            }
            // ---- nets of event-driven chips' signal pins and of wires, and nothing else: the digital domain's (see
            // DigitalDomain) — no conductors and no nodes in the circuit, so the solver never sees their edges. A net
            // with anything else on it (a resistor, an LED, a supply pin, a 74HC chip) stays analog, and the chips'
            // pins on it are physical there.
            prepareDigitalPackages();
            if (!digitalPinKeys.isEmpty()) {
                Map<String, String> uf = new HashMap<>();
                java.util.function.UnaryOperator<String> find = new java.util.function.UnaryOperator<>() {
                    public String apply(String k) {
                        String r = k;
                        while (uf.containsKey(r) && !uf.get(r).equals(r)) r = uf.get(r);
                        uf.put(k, r);
                        return r;
                    }
                };
                Map<String, Part> keyPart = new HashMap<>();
                for (Contact k : contacts) {
                    if (k.sliding || k.slide != null) continue;
                    keyPart.put(k.pk, k.p);
                    keyPart.put(k.qk, k.q);
                    String ra = find.apply(eroot(k.pk)), rb = find.apply(eroot(k.qk));
                    if (!ra.equals(rb)) uf.put(ra, rb);
                }
                for (Part p : parts) if (runOf.containsKey(p.id())) keyPart.put("p" + p.id(), p);
                // a net through a ribbon's conductor has its copper's resistance in it: analog
                for (long[] u : ribbonUsed) keyPart.put("p" + u[0] + "c" + u[1], partById(u[0]));
                Map<String, List<String>> members = new HashMap<>();
                for (String key : keyPart.keySet()) members.computeIfAbsent(find.apply(eroot(key)), r -> new ArrayList<>()).add(key);
                for (var en : members.entrySet()) {
                    boolean pins = false, other = false;
                    for (String key : en.getValue()) {
                        if (digitalPinKeys.contains(key)) pins = true;
                        else if (!isWire(keyPart.get(key))) other = true;
                    }
                    if (!pins || other) continue;
                    DigitalDomain.Net net = c.digital.net(en.getKey());
                    for (String key : en.getValue()) digitalNetOfRoot.put(eroot(key), net);
                    digitalNetsByGroup.put(en.getKey(), net);
                    for (String key : en.getValue()) {
                        Part kp = keyPart.get(key);
                        if (kp != null && isWire(kp)) c.digitalNetOfPart.put(kp.id(), net);
                    }
                }
                for (Contact k : contacts) {
                    connectedRoots.add(eroot(k.pk));
                    connectedRoots.add(eroot(k.qk));
                }
            }
            if (Boolean.getBoolean("diag.contacts")) {
                for (Contact k : contacts) {
                    CompiledAssembly.Body b1 = c.bodyOfPart.get(k.p.id()), b2 = c.bodyOfPart.get(k.q.id());
                    if ((b1 != null && b1.slider) || (b2 != null && b2.slider))
                        System.out.println("contact " + k.p.kind() + "@" + k.p.minX() + "," + k.p.minY() + "," + k.p.minZ() + " [" + k.pk + "] — "
                                + k.q.kind() + "@" + k.q.minX() + "," + k.q.minY() + "," + k.q.minZ() + " [" + k.qk + "] sliding " + k.sliding + " slide " + (k.slide != null));
                }
            }
            buildRibbonConductors();
            // ---- conductors between contacts
            for (Contact k : contacts) {
                Part p = k.p, q = k.q;
                if (digitalNetOfRoot.containsKey(eroot(k.pk)) && digitalNetOfRoot.get(eroot(k.pk)) == digitalNetOfRoot.get(eroot(k.qk))) continue;
                Node np = enode(k.pk), nq = enode(k.qk);
                if (np == nq) continue;
                if (k.slide != null) {
                    Part moving = c.bodyOfPart.get(p.id()) == k.slide ? p : q;
                    if (Boolean.getBoolean("diag.contacts")) System.out.println("travel contact " + p.kind() + "@" + p.minX() + "," + p.minY() + "," + p.minZ()
                            + " " + q.kind() + "@" + q.minX() + "," + q.minY() + "," + q.minZ() + " end " + k.end + " sense " + k.sense);
                    c.network.add(new dev.emergence.core.sim.elements.TravelContact(np, nq, k.slide.tracker, k.end, k.sense, 2e-4,
                            moving.param("contact_resistance", 0.02), c.thermalOfPart.get(moving.id())));
                    continue;
                }
                if (k.sliding) {
                    Part brush = p.kind() == PartKind.BRUSH ? p : q;
                    c.network.add(new Resistor(np, nq, brush.param("contact_resistance", BRUSH_RESISTANCE), c.thermalOfPart.get(brush.id())));
                    continue;
                }
                double rHalfP = nodeToFace(p), rHalfQ = nodeToFace(q);
                if (rHalfP >= CELL / 2 && rHalfQ >= CELL / 2) {
                    // two bulk/wire conductors: the halves to the contact face in series, each half's Joule heat
                    // into its own part (exact heat allocation, e.g. along a filament)
                    Node tp = c.thermalOfPart.get(p.id()), tq = c.thermalOfPart.get(q.id());
                    // thermoelectricity: each half runs from its part's temperature to the contact's, which lies between
                    // the two parts' in proportion to the halves' thermal conductances
                    double sp = conductorMaterial(p).get(MaterialProperty.SEEBECK_COEFFICIENT, 0);
                    double sq = conductorMaterial(q).get(MaterialProperty.SEEBECK_COEFFICIENT, 0);
                    double lp = 0.5;
                    if ((sp != 0 || sq != 0) && tp != tq) {
                        double gp = conductorMaterial(p).get(MaterialProperty.THERMAL_CONDUCTIVITY, 1) * conductorArea(p) / rHalfP;
                        double gq = conductorMaterial(q).get(MaterialProperty.THERMAL_CONDUCTIVITY, 1) * conductorArea(q) / rHalfQ;
                        lp = gp / (gp + gq);
                    } else {
                        sp = sq = 0;
                    }
                    c.network.add(new dev.emergence.core.sim.elements.ContactConductor(np, nq, conductorMaterial(p), rHalfP / conductorArea(p), tp, sp,
                            conductorMaterial(q), rHalfQ / conductorArea(q), tq, sq, lp, ambientT));
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
                        double beta = p.param("ntc_beta", 0);
                        c.deviceOfPart.put(p.id(), beta > 0
                                ? c.network.add(new Thermistor(l[0], l[1], p.param("resistance", 10_000), beta, th))
                                : c.network.add(new Resistor(l[0], l[1], p.param("resistance", 10), th)));
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
                    case LED -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Led(l[0], l[1], p.param("wavelength", 630),
                                p.param("efficiency", 0.2), th)));
                        c.network.add(Capacitor.parasitic(l[0], l[1], 20e-12));
                    }
                    case CHIP -> {
                        // every chip runs by events; a package that could not be made was warned about
                        if (digitalPackages.containsKey(p.id())) buildDigitalChip(p, th);
                    }
                    case PRESSURE_SENSOR -> buildPressureSensor(p);
                    case POTENTIOMETER -> {
                        Node[] l = leads(p);
                        Node w = enode("p" + p.id() + "w");
                        Object[] ps = potShaft.get(p.id());
                        AngleTracker tr = null;
                        double sense = 1;
                        if (ps != null) {
                            CompiledAssembly.Body rot = (CompiledAssembly.Body) ps[0];
                            tr = rot.tracker;
                            // wiper angle = shaft angle relative to the housing, about the pot's own +axis
                            sense = (double) ps[1] * Math.signum(rot.axis.dot(p.axis().vec()));
                        }
                        // end-stop stiffness scales with the size of the part (≈ 20 N·m/rad for a 25 mm body)
                        double size = PartGeometry.transverseSize(p) / 0.025;
                        c.deviceOfPart.put(p.id(), c.network.add(new Potentiometer(l[0], l[1], w, tr, sense,
                                p.param("resistance", 10_000), Math.toRadians(p.param("travel", 270)), p.param("wiper", 0.5),
                                p.param("stop_stiffness", 20) * size * size * size, th)));
                        c.network.add(Capacitor.parasitic(w, l[0], 5e-12));
                    }
                    case OPAMP -> {
                        Node inP = enode("p" + p.id() + "a"), inN = enode("p" + p.id() + "b"), o = enode("p" + p.id() + "o"),
                                v = enode("p" + p.id() + "v"), n = enode("p" + p.id() + "n");
                        c.deviceOfPart.put(p.id(), c.network.add(new OpAmp(inP, inN, o, v, n, p.param("gbw", 3e6),
                                p.param("slew", 13e6), th)));
                        c.leadsOfPart.put(p.id(), new Node[]{n, o});
                        // input capacitances and the supply bypass capacitor
                        c.network.add(Capacitor.parasitic(inP, n, 3e-12));
                        c.network.add(Capacitor.parasitic(inN, n, 3e-12));
                        c.network.add(Capacitor.parasitic(o, n, 10e-12));
                        c.network.add(new Capacitor(v, n, 100e-9));
                    }
                    case PHOTODIODE -> {
                        Node[] l = leads(p);
                        var pd = c.network.add(new Photodiode(l[0], l[1], p.param("area", 7.5e-6), th));
                        c.deviceOfPart.put(p.id(), pd);
                        c.sunHeatShare.put(p.id(), bodyShare(p, p.param("area", 7.5e-6)));
                        // junction capacitance ≈ 70 pF per 7.5 mm² at zero bias
                        c.network.add(Capacitor.parasitic(l[0], l[1], Math.max(5e-12, 70e-12 * p.param("area", 7.5e-6) / 7.5e-6)));
                    }
                    case LOGIC -> {
                        int fn = (int) Math.round(p.param("function", 2));
                        var f = dev.emergence.core.sim.elements.LogicGate.Function.values()[Math.max(0, Math.min(8, fn))];
                        Node in = enode("p" + p.id() + "a"), o = enode("p" + p.id() + "o"), v = enode("p" + p.id() + "v"),
                                gnd = enode("p" + p.id() + "n");
                        boolean twoIn = fn >= 2 && fn != 7;
                        Node inB = twoIn ? enode("p" + p.id() + "b") : null;
                        Node qb = fn == 8 ? enode("p" + p.id() + "q") : null;
                        c.deviceOfPart.put(p.id(), c.network.add(new dev.emergence.core.sim.elements.LogicGate(f, in, inB, o, qb, v, gnd,
                                p.param("delay", 10e-9), p.param("output_resistance", 50), th)));
                        c.leadsOfPart.put(p.id(), new Node[]{gnd, o});
                        // CMOS input capacitance, and the supply bypass capacitor every logic chip sits next to
                        c.network.add(Capacitor.parasitic(in, gnd, 5e-12));
                        if (inB != null) c.network.add(Capacitor.parasitic(inB, gnd, 5e-12));
                        c.network.add(new Capacitor(v, gnd, 100e-9));
                    }
                    case TRANSISTOR -> {
                        Node[] l = leads(p);
                        Node gate = enode("p" + p.id() + "g");
                        int type = (int) Math.round(p.param("type", 0));
                        Object dev;
                        if (type <= 1) {
                            // small-signal BJT (2N3904/2N3906 class); depletion capacitances C_je, C_jc
                            dev = c.network.add(new Bjt(l[1], gate, l[0], type == 1, p.param("saturation_current", 1e-14),
                                    p.param("beta", 150), p.param("beta_r", 2), p.param("early_voltage", 75), th));
                            c.network.add(Capacitor.parasitic(gate, l[0], p.param("c_be", 18e-12)));
                            c.network.add(Capacitor.parasitic(gate, l[1], p.param("c_bc", 4e-12)));
                            if (PartGeometry.isPhototransistor(p)) {
                                // a phototransistor: its collector–base junction, enlarged under a window, is a photodiode
                                // whose photocurrent (collector → base) is the base current the transistor amplifies;
                                // the big junction's capacitance (Miller-multiplied) is what makes it slow
                                double area = p.param("photo", 0);
                                Node anode = type == 1 ? l[1] : gate, cathode = type == 1 ? gate : l[1];
                                c.photoJunction.put(p.id(), c.network.add(new Photodiode(anode, cathode, area, th)));
                                c.sunHeatShare.put(p.id(), bodyShare(p, area));
                                c.network.add(Capacitor.parasitic(gate, l[1], Math.max(5e-12, 70e-12 * area / 7.5e-6)));
                            }
                        } else {
                            // small-signal MOSFET (2N7000/BS250 class); gate capacitances C_gs, C_gd
                            boolean pch = type == 3;
                            dev = c.network.add(new Mosfet(l[1], gate, l[0], pch, p.param("threshold", 1.6),
                                    p.param("transconductance", pch ? 0.05 : 0.1), p.param("slope", 1.5), p.param("lambda", 0.01), th));
                            c.network.add(Capacitor.parasitic(gate, l[0], p.param("c_gs", 40e-12)));
                            c.network.add(Capacitor.parasitic(gate, l[1], p.param("c_gd", 8e-12)));
                        }
                        c.deviceOfPart.put(p.id(), dev);
                    }
                    case CAPACITOR -> {
                        Node[] l = leads(p);
                        c.deviceOfPart.put(p.id(), c.network.add(new Capacitor(l[1], l[0], p.param("capacitance", 1e-4))));
                    }
                    case BATTERY -> {
                        Node[] l = leads(p);
                        c.network.preferDatum(l[0]); // its circuit's datum: the supply's negative terminal, where it is stiff
                        // its charge carries over from the running state (kept with the capacitors' voltages)
                        Double held = prev.capacitorVoltages().get(p.id());
                        c.deviceOfPart.put(p.id(), c.network.add(new Battery(Battery.Chemistry.of(p.param("chemistry", 0)), l[1], l[0],
                                (int) Math.round(p.param("cells", 6)),
                                p.param("capacity", 7), held != null ? held : p.param("charge", 1), c.thermalOfPart.get(p.id()))));
                    }
                    case WIRE, INSULATED_WIRE -> c.leadsOfPart.put(p.id(), new Node[]{enode("p" + p.id())});
                    default -> {
                        if (conductive(p) && enodes.containsKey(eroot("p" + p.id()))) c.leadsOfPart.put(p.id(), new Node[]{enode("p" + p.id())});
                    }
                }
            }
            buildBrushContacts();
            checkpoint();
            buildOptics();
            checkpoint();
            buildRadiation();
            buildElectrochemistry();
            checkpoint();
            buildMagnetics();
            checkpoint();
            buildEddyCurrents();
            buildAcoustics();
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
                    if (!sameFrame(qb, bb)) continue; // a commutator riding a slide past the brush: not modelled
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
            char e = k.charAt(k.length() - 1);
            return e == '+' || e == '-' || Character.isLetter(e);
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
                case RESISTOR, CAPACITOR, BATTERY, DIODE, SWITCH, BRUSH -> 1e-6;
                default -> CELL * CELL;
            };
        }

        /**
         * Material that actually carries current at a part's contacts: manufactured components
         * (resistor, capacitor, diode, switch) have tinned-copper leads whatever their body is made of;
         * a brush conducts through its graphite.
         */
        static Material conductorMaterial(Part p) {
            return switch (p.kind()) {
                // manufactured components carry (tinned) copper leads, whatever their body is made of
                case RESISTOR, CAPACITOR, BATTERY, DIODE, SWITCH, TRANSISTOR, LED, LOGIC, CHIP, PHOTODIODE, POTENTIOMETER, OPAMP, GM_TUBE -> MaterialDatabase.standard().get("copper");
                default -> p.material();
            };
        }

        boolean compatible(Part p, Part q) {
            // insulation: an insulated wire only connects to wires and component leads
            if (p.kind() == PartKind.INSULATED_WIRE && !(q.kind().leaded)) return false;
            if (q.kind() == PartKind.INSULATED_WIRE && !(p.kind().leaded)) return false;
            // insulated wires lying side by side do not touch conductor to conductor; runs of the same insulation
            // colour are taken as spliced together, so colour-coding separates neighbouring nets
            if (p.kind() == PartKind.INSULATED_WIRE && q.kind() == PartKind.INSULATED_WIRE
                    && Math.round(p.param("color", 0)) != Math.round(q.param("color", 0))) return false;
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
            // a coil or magnet on a rotor riding a slide moves past the frame's: not in the field model
            for (List<Part> list : List.of(coils, magnets)) {
                list.removeIf(p -> {
                    boolean carried = c.bodyOfPart.get(p.id()).carrier != null;
                    if (carried) c.warnings.add(p.kind() + " #" + p.id() + " rides on a slide's rotor: its magnetic coupling is not modelled");
                    return carried;
                });
            }
            // (a magnet alone matters where something can move towards iron: a slide)
            boolean slides = c.bodies.stream().anyMatch(b -> !b.isStatic && b.slider);
            if (coils.isEmpty() && magnets.size() < 2 && (magnets.isEmpty() || !slides)) return;
            MagneticSystem ms = new MagneticSystem().tableResolution(256);
            Map<CompiledAssembly.Body, EmBody> em = new HashMap<>();
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.carrier != null) continue; // rotors riding on slides: not in the field model (see below)
                em.put(b, b.isStatic ? EmBody.fixed("frame")
                        : b.slider ? EmBody.slider("slide" + b.index, b.axis, b.node, b.tracker, b.strokeLo, b.strokeHi)
                        : EmBody.rotor("rotor" + b.index, b.pivot, b.axis, b.node, b.tracker));
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
            // ferromagnetic parts (μr ≥ 2: soft iron, electrical steel, structural steel, nickel) and coils wound on a
            // core ("core" = 1: electrical steel filling the bore) shape the coils' and magnets' magnetic circuits
            if (!coils.isEmpty() || !magnets.isEmpty()) {
                Material es = MaterialDatabase.standard().get("electrical_steel");
                double steel = es.get(MaterialProperty.RELATIVE_PERMEABILITY, 5000);
                for (Part p : parts) {
                    double muR, fill, bSat, hc;
                    if (p.kind() == PartKind.COIL) {
                        if (p.param("core", 0) <= 0) continue;
                        CoilGeometry g = PartGeometry.coil(p);
                        double rIn = g.meanRadius() - 0.5 * g.thickness();
                        fill = Math.min(1, Math.PI * rIn * rIn / (CELL * CELL));
                        muR = 1 + fill * (steel - 1);
                        bSat = es.get(MaterialProperty.SATURATION_FLUX_DENSITY, 2.0);
                        hc = es.get(MaterialProperty.COERCIVITY, 0);
                    } else if (p.kind() == PartKind.MAGNET || isWire(p)) {
                        continue;
                    } else {
                        muR = p.material().get(MaterialProperty.RELATIVE_PERMEABILITY, 1);
                        bSat = p.material().get(MaterialProperty.SATURATION_FLUX_DENSITY, Double.POSITIVE_INFINITY);
                        hc = p.material().get(MaterialProperty.COERCIVITY, 0);
                        fill = 1;
                        if (p.kind() == PartKind.SHAFT) {
                            double d = PartGeometry.shaftDiameter(p);
                            fill = Math.PI * d * d / 4 / (CELL * CELL);
                        } else if (p.kind() == PartKind.BLOCK) {
                            fill = Math.max(0.01, Math.min(1, p.param("fill", 1.0)));
                        } else if (!p.kind().structural) {
                            continue;
                        }
                        muR = 1 + fill * (muR - 1);
                    }
                    if (muR < 2) continue;
                    EmBody body = em.get(c.bodyOfPart.get(p.id()));
                    if (body == null) continue;
                    // a part-filled cell carries the cell's mean flux in its iron alone: it saturates at fill·B_sat
                    for (int x = p.minX(); x <= p.maxX(); x++)
                        for (int y = p.minY(); y <= p.maxY(); y++)
                            for (int z = p.minZ(); z <= p.maxZ(); z++)
                                ms.addIronCell(body, x, y, z, muR, CELL, fill * bSat, hc, c.thermalOfPart.get(p.id()));
                }
            }
            c.magnetics = c.network.add(ms);
            c.warnings.addAll(ms.warnings());
        }

        // ------------------------------------------------------------ brakes
        /**
         * Friction pads: each brake presses its +axis face on the parts in the cell in front of it. For a part on a
         * turning body, the contact point's distance r from that body's axis gives the friction torque μ·F·r
         * (dry Coulomb friction, regularised at very low slip), reacting on the stationary frame; the dissipated
         * work heats the pad.
         */
        void buildBrakes() {
            for (Part p : parts) {
                if (p.kind() != PartKind.BRAKE) continue;
                Dir ax = p.axis();
                int a0 = ax.axis();
                int[] cell = {(p.minX() + p.maxX()) / 2, (p.minY() + p.maxY()) / 2, (p.minZ() + p.maxZ()) / 2};
                cell[a0] = ax.sign() > 0 ? new int[]{p.maxX(), p.maxY(), p.maxZ()}[a0] + 1 : new int[]{p.minX(), p.minY(), p.minZ()}[a0] - 1;
                Vec3 contact = p.center().add(ax.vec().mul(p.boxSize(a0) / 2));
                double mu = p.material().get(MaterialProperty.FRICTION_COEFFICIENT, 0.4), force = p.param("force", 20);
                Set<CompiledAssembly.Body> done = new HashSet<>();
                for (Part q : a.at(cell[0], cell[1], cell[2])) {
                    CompiledAssembly.Body qb = c.bodyOfPart.get(q.id());
                    if (qb == null || qb.isStatic || qb.slider || !sameFrame(qb, c.bodyOfPart.get(p.id())) || !done.add(qb)) continue;
                    Vec3 rel = contact.sub(qb.pivot);
                    double r = rel.sub(qb.axis.mul(rel.dot(qb.axis))).length();
                    if (r < 1e-6) continue; // pressing on the axis itself exerts no torque
                    double torque = mu * force * r;
                    CoulombFriction f = c.network.add(new CoulombFriction(qb.node, refR, p.switchClosed() ? torque : 0, 1e-2,
                            c.thermalOfPart.get(p.id())));
                    c.brakes.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(new double[]{torque});
                    c.brakeElements.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(f);
                }
            }
        }

        // ------------------------------------------------------------ optics
        /**
         * True if the straight path is stopped at cell point q of part o. Thin wires and shafts, liquids and transparent
         * vessels pass light; a plate stops it only within its thickness. Mirrors and glass blocks and lenses stop the
         * straight path: what they pass on is traced as ray tubes ({@link #buildRayOptics}).
         */
        boolean blocks(Part o, Vec3 q) {
            if (AssemblyOptics.passes(o)) return false;
            if (o.kind() == PartKind.BLOCK) {
                int ax = o.axis().axis();
                double half = 0.5 * o.boxSize(ax) * Math.max(0.01, Math.min(1, o.param("fill", 1.0)));
                return Math.abs(StructuralAnalysis.component(q.sub(o.center()), ax)) <= half;
            }
            return true;
        }

        /**
         * Radioactive sources and Geiger–Müller tubes. Each source's gamma lines reach each tube with the probability
         * A·ε(E)·e^{−Σμℓ}/(4πd²) per photon: the tube's sensitive area over the sphere at its distance, its intrinsic
         * efficiency, and the attenuation along the straight ray through every part in between (sampled every 0.01
         * cell; a plate absorbs only within its thickness, other parts with their mass spread over their cells).
         */
        void buildRadiation() {
            var nd = dev.emergence.core.nuclear.NuclearData.standard();
            List<Part> tubes = new ArrayList<>();
            for (Part p : parts) {
                if (p.kind() == PartKind.SOURCE) {
                    var parent = nd.byCode((int) Math.round(p.param("nuclide", 0)));
                    var chain = nd.chain(parent.id());
                    double[] atoms = new double[chain.size()];
                    double a0 = p.param("activity", 3.7e5);
                    boolean fresh = p.param("fresh", 0) > 0.5;
                    for (int i = 0; i < chain.size(); i++) {
                        Double saved = prev.chemistry().get("N" + p.id() + ":" + chain.get(i).id());
                        if (saved != null) atoms[i] = saved;
                        else if (i == 0) atoms[i] = a0 / chain.get(0).lambda();
                        else if (!fresh) {
                            // secular equilibrium with the parent: the daughter's activity is the parent's × branching
                            double b = 0;
                            for (var br : chain.get(0).decays()) if (chain.get(i).id().equals(br.daughter())) b += br.fraction();
                            atoms[i] = b * a0 / chain.get(i).lambda();
                        }
                    }
                    c.sourceOfPart.put(p.id(), c.network.add(new RadioactiveSource(chain, atoms, c.thermalOfPart.get(p.id()))));
                }
                if (p.kind() == PartKind.GM_TUBE) tubes.add(p);
            }
            for (Part t : tubes) {
                Node[] l = leads(t);
                var tube = new GeigerTube(l[1], l[0], t.param("start", 350), t.param("stop", 300), 1e5, nd.gmBackground(),
                        t.id() * 0x9E3779B97F4A7C15L, c.thermalOfPart.get(t.id()));
                c.network.add(Capacitor.parasitic(l[1], l[0], 2e-12)); // the tube's own anode–cathode capacitance
                double area = t.param("area", 3e-4);
                for (Part sp : parts) {
                    if (sp.kind() != PartKind.SOURCE) continue;
                    var src = c.sourceOfPart.get(sp.id());
                    Vec3 from = sp.center(), to = t.center();
                    double d = to.sub(from).length();
                    if (d < 1e-6) continue;
                    for (int i = 0; i < src.chain().size(); i++) {
                        double w = 0;
                        for (var g : src.chain().get(i).gammas()) {
                            double path = attenuation(from, to, g.keV(), sp, t);
                            w += g.yield() * area * nd.gmEfficiency(g.keV()) * Math.exp(-path) / (4 * Math.PI * d * d);
                        }
                        tube.couple(src, i, w);
                    }
                }
                c.tubeOfPart.put(t.id(), c.network.add(tube));
                c.deviceOfPart.put(t.id(), tube);
            }
        }

        /** Σμℓ (optical depth) for photons of energy keV along the segment, skipping the two end parts. */
        double attenuation(Vec3 from, Vec3 to, double keV, Part skipA, Part skipB) {
            var nd = dev.emergence.core.nuclear.NuclearData.standard();
            Vec3 d = to.sub(from);
            double len = d.length(), step = 0.01 * CELL, tau = 0;
            int n = (int) Math.ceil(len / step);
            for (int k = 0; k < n; k++) {
                Vec3 q = from.add(d.mul((k + 0.5) / n));
                for (Part o : a.at(cellOf(q.x()), cellOf(q.y()), cellOf(q.z()))) {
                    if (o == skipA || o == skipB) continue;
                    double rho;
                    if (o.kind() == PartKind.BLOCK) {
                        int ax = o.axis().axis();
                        double half = 0.5 * o.boxSize(ax) * Math.max(0.01, Math.min(1, o.param("fill", 1.0)));
                        if (Math.abs(StructuralAnalysis.component(q.sub(o.center()), ax)) > half) continue;
                        rho = o.material().density();
                    } else {
                        rho = PartGeometry.mass(o) / (o.boxSize(0) * o.boxSize(1) * o.boxSize(2));
                    }
                    tau += nd.massAttenuation(o.material(), keV) * rho * (len / n);
                }
            }
            return tau;
        }

        int cellOf(double v) {
            return (int) Math.floor(v / CELL);
        }

        /**
         * Optical links: each photodiode receives a fraction of every LED's light — radiant intensity of the LED
         * (generalised Lambertian, (m+1)/2π·cos^m θ, m = "beam", default 6: ±27° half power) times the receiver's
         * projected solid angle A·cos θ_r/d², times the visibility of the path. Visibility is sampled on five rays
         * through the assembly's cells; parts on a turning body are tested in that body's frame, giving a visibility
         * table over its angle (a slotted disk chopping the beam). Light turned by mirrors, lenses and glass is added
         * by {@link #buildRayOptics}.
         */
        void buildOptics() {
            List<Part> leds = new ArrayList<>(), pds = new ArrayList<>();
            for (Part p : parts) {
                if (p.kind() == PartKind.LED) leds.add(p);
                if (p.kind() == PartKind.PHOTODIODE || PartGeometry.isPhototransistor(p)) pds.add(p);
            }
            if (!pds.isEmpty()) buildIncandescence(pds);
            if (leds.isEmpty()) return;
            buildRayOptics(leds, pds);
            if (pds.isEmpty()) return;
            for (Part rx : pds) {
                Photodiode pd = c.receiver(rx.id());
                if (pd == null) continue;
                // the junction's window lies on the sensitive face (as the ray tubes see it)
                Vec3 fr = PartGeometry.facing(rx);
                int fa = Math.abs(fr.x()) > 0.5 ? 0 : Math.abs(fr.y()) > 0.5 ? 1 : 2;
                Vec3 r0 = rx.center().add(fr.mul(0.5 * rx.boxSize(fa)));
                for (Part tx : leds) {
                    if (!(c.deviceOfPart.get(tx.id()) instanceof Led led)) continue;
                    Vec3 ft = PartGeometry.facing(tx), s0 = tx.center().add(ft.mul(0.3 * CELL));
                    Vec3 dv = r0.sub(s0);
                    double d = dv.length();
                    if (d < 1e-6 || d > 16 * CELL) continue;
                    Vec3 u = dv.mul(1 / d);
                    double cs = ft.dot(u), cr = -fr.dot(u);
                    if (cs <= 0 || cr <= 0) continue;
                    double m = tx.param("beam", 6);
                    double geo = (m + 1) / (2 * Math.PI) * Math.pow(cs, m) * pd.area() * cr / (d * d);
                    // five rays: centre and four offsets across the beam
                    Vec3 e1 = Math.abs(u.y()) < 0.9 ? u.cross(new Vec3(0, 1, 0)) : u.cross(new Vec3(1, 0, 0));
                    e1 = e1.mul(1 / e1.length());
                    Vec3 e2 = u.cross(e1);
                    double off = 0.08 * CELL;
                    Vec3[] offs = {new Vec3(0, 0, 0), e1.mul(off), e1.mul(-off), e2.mul(off), e2.mul(-off)};
                    // static visibility, and the body (if any) whose parts can cut the rays
                    CompiledAssembly.Body mover = null;
                    int n = 0;
                    boolean[] staticBlocked = new boolean[offs.length];
                    List<List<Vec3>> samples = new ArrayList<>();
                    for (int k = 0; k < offs.length; k++) {
                        List<Vec3> pts = new ArrayList<>();
                        for (double t = 0.02; t < 0.98; t += 0.01 * CELL / d) { // fine enough to catch thin plates
                            Vec3 q = s0.add(dv.mul(t)).add(offs[k]);
                            pts.add(q);
                            for (Part o : a.at(cellOf(q.x()), cellOf(q.y()), cellOf(q.z()))) {
                                if (o == tx || o == rx || !blocks(o, q)) continue;
                                CompiledAssembly.Body b = c.bodyOfPart.get(o.id());
                                if (b != null && !b.isStatic && !b.slider && b.carrier == null) mover = b; // (riding a slide: seen as fixed)
                                else staticBlocked[k] = true;
                            }
                        }
                        samples.add(pts);
                    }
                    // a turning body may also sweep into the path at other angles: test every body with off-axis parts
                    for (CompiledAssembly.Body b : c.bodies)
                        if (!b.isStatic && !b.slider && b.carrier == null && mover == null && sweeps(b, samples)) mover = b;
                    if (Boolean.getBoolean("diag.optics"))
                        System.out.println("OPTICS LED #" + tx.id() + " → photodiode #" + rx.id() + ": geo " + geo + ", mover " + (mover == null ? "none" : mover.parts.size() + " parts")
                                + ", bodies " + c.bodies.size() + ", static-blocked " + java.util.Arrays.toString(staticBlocked) + " [" + Thread.currentThread().getName() + "]");
                    if (mover == null) {
                        int open = 0;
                        for (boolean bl : staticBlocked) if (!bl) open++;
                        double vis = open / (double) offs.length;
                        if (vis > 0) pd.addSource(led, th -> geo * vis, null);
                        continue;
                    }
                    double[] table = new double[360];
                    for (int deg = 0; deg < 360; deg++) {
                        double ang = Math.toRadians(deg);
                        int open = 0;
                        for (int k = 0; k < offs.length; k++) {
                            if (staticBlocked[k]) continue;
                            boolean blocked = false;
                            for (Vec3 q : samples.get(k)) {
                                Vec3 local = rotate(q.sub(mover.pivot), mover.axis, -ang).add(mover.pivot);
                                for (Part o : a.at(cellOf(local.x()), cellOf(local.y()), cellOf(local.z()))) {
                                    if (o != tx && o != rx && c.bodyOfPart.get(o.id()) == mover && blocks(o, local)) blocked = true;
                                }
                                if (blocked) break;
                            }
                            if (!blocked) open++;
                        }
                        table[deg] = geo * open / offs.length;
                    }
                    pd.addSource(led, th -> {
                        double x = Math.toDegrees(th) % 360;
                        if (x < 0) x += 360;
                        int i0 = (int) Math.floor(x) % 360, i1 = (i0 + 1) % 360;
                        double f = x - Math.floor(x);
                        return table[i0] * (1 - f) + table[i1] * f;
                    }, mover.tracker);
                }
            }
        }

        /**
         * Light from each LED that reaches photodiodes by way of mirrors, lenses or glass: ray tubes traced through the
         * assembly ({@link dev.emergence.core.optics.TubeTracer}), at the LED's wavelength, with moving bodies in their
         * poses as compiled. Paths straight from LED to photodiode are left to the direct calculation.
         */
        void buildRayOptics(List<Part> leds, List<Part> pds) {
            if (parts.stream().noneMatch(AssemblyOptics::redirects)) return;
            Map<Double, dev.emergence.core.optics.OpticalScene> scenes = new HashMap<>();
            for (Part tx : leds) {
                if (!(c.deviceOfPart.get(tx.id()) instanceof Led led)) continue;
                double nm = tx.param("wavelength", 630);
                var scene = scenes.computeIfAbsent(nm, k -> AssemblyOptics.scene(a, c, k, b -> {
                    double[] m = b.parts.isEmpty() ? null : prev.bodyMotion().get(b.parts.get(0));
                    return m == null ? 0 : m[1];
                }));
                Vec3 ft = PartGeometry.facing(tx), s0 = tx.center().add(ft.mul(0.3 * CELL));
                var res = pds.isEmpty() ? null : dev.emergence.core.optics.TubeTracer.point(scene, s0, ft, tx.param("beam", 6), nm, tx.id(), 16, 7);
                // rays to show the beam by, if mirrors or lenses turn it (and it is visible)
                if (nm >= 380 && nm <= 750) {
                    var rays = dev.emergence.core.optics.TubeTracer.beams(scene, s0, ft, tx.param("beam", 6), nm, tx.id(), 0.25);
                    if (!rays.isEmpty()) c.beams.put(tx.id(), rays);
                }
                if (res == null) continue;
                for (Part rx : pds) {
                    double f = res.light(rx.id());
                    if (f > 1e-12 && c.receiver(rx.id()) != null) c.receiver(rx.id()).addSource(led, th -> f, null);
                }
            }
        }

        /**
         * Hot bodies seen by photodiodes: every part with a thermal node within 16 cells in front of a photodiode's
         * window radiates on it as a grey Lambertian body — g = ε·(A_s/4)·A·cos θ_r/(π d²), its mean projected area
         * being a quarter of its surface (Cauchy), times the fraction of five rays from the window to the body that
         * nothing stops. A pair is kept if it could drive 0.1 nA at the body's failure temperature.
         */
        void buildIncandescence(List<Part> pds) {
            dev.emergence.core.optics.OpticalScene scene = null;
            for (Part rx : pds) {
                Photodiode pd = c.receiver(rx.id());
                if (pd == null) continue;
                Vec3 fr = PartGeometry.facing(rx);
                int fa = Math.abs(fr.x()) > 0.5 ? 0 : Math.abs(fr.y()) > 0.5 ? 1 : 2;
                Vec3 r0 = rx.center().add(fr.mul(0.5 * rx.boxSize(fa)));
                for (Part src : parts) {
                    if (src == rx || src.kind() == PartKind.ELECTROLYTE) continue;
                    Node tn = c.thermalOfPart.get(src.id());
                    if (tn == null) continue;
                    Vec3 dv = src.center().sub(r0);
                    double d = dv.length();
                    if (d < 1e-6 || d > 16 * CELL) continue;
                    double cr = fr.dot(dv) / d;
                    if (cr <= 0) continue;
                    double g0 = PartGeometry.emissivity(src) * PartGeometry.surfaceArea(src) / 4 * pd.area() * cr / (Math.PI * d * d);
                    double tMax = Math.min(PartGeometry.failureTemperature(src), 5999);
                    if (g0 * Photodiode.blackbodyCurrent(tMax) < 1e-10) continue;
                    if (scene == null) scene = AssemblyOptics.scene(a, c, 700, b -> {
                        double[] m = b.parts.isEmpty() ? null : prev.bodyMotion().get(b.parts.get(0));
                        return m == null ? 0 : m[1];
                    });
                    Vec3 u = dv.mul(1 / d);
                    Vec3 e1 = Math.abs(u.y()) < 0.9 ? u.cross(new Vec3(0, 1, 0)) : u.cross(new Vec3(1, 0, 0));
                    e1 = e1.mul(1 / e1.length());
                    Vec3 e2 = u.cross(e1);
                    double off = 0.08 * CELL;
                    int open = 0;
                    for (Vec3 o : new Vec3[]{Vec3.ZERO, e1.mul(off), e1.mul(-off), e2.mul(off), e2.mul(-off)}) {
                        Vec3 target = src.center().add(o), from = r0.add(fr.mul(1e-6));
                        Vec3 ray = target.sub(from);
                        double len = ray.length();
                        var h = scene.trace(from, ray.mul(1 / len), null, rx.id());
                        if (h == null || h.obj.part == src.id() || h.t >= len - 0.02 * CELL) open++;
                    }
                    if (open > 0) pd.addHotSource(tn, g0 * open / 5.0, ambientT);
                }
            }
            buildSpectralIncandescence(pds);
        }

        /**
         * Light from hot circuit parts (filaments, heating elements — conductors in a circuit that survive 1200 K) that
         * reaches photodiodes by way of mirrors, lenses or glass, band by band: each of {@link Photodiode#BAND_EDGES}'s
         * 40 nm bands is traced as ray tubes at its centre wavelength (so a prism sends each band its own way), from the
         * part's centre into two opposite hemispheres of uniform intensity — an isotropic emitter of ε·A_s·F_b(T). Paths
         * straight to a photodiode are left to the direct, grey calculation above.
         */
        void buildSpectralIncandescence(List<Part> pds) {
            List<Part> optics = new ArrayList<>();
            for (Part p : parts) if (AssemblyOptics.redirects(p)) optics.add(p);
            if (optics.isEmpty()) return;
            int nb = Photodiode.BAND_EDGES.length - 1;
            dev.emergence.core.optics.OpticalScene[] scenes = new dev.emergence.core.optics.OpticalScene[nb];
            for (Part src : parts) {
                checkpoint();
                Node tn = c.thermalOfPart.get(src.id());
                if (tn == null || !c.leadsOfPart.containsKey(src.id()) || !conductive(src)) continue;
                double tMax = Math.min(PartGeometry.failureTemperature(src), 5999);
                if (tMax < 1200) continue;
                Part near = null;
                double best = 16 * CELL;
                for (Part o : optics) {
                    double d = o.center().sub(src.center()).length();
                    if (d < best) {
                        best = d;
                        near = o;
                    }
                }
                if (near == null) continue;
                Vec3 ax = near.center().sub(src.center());
                ax = ax.mul(1 / ax.length());
                double emit = PartGeometry.emissivity(src) * PartGeometry.surfaceArea(src);
                Map<Long, double[]> g = new HashMap<>();
                for (int b = 0; b < nb; b++) {
                    if (scenes[b] == null) scenes[b] = AssemblyOptics.scene(a, c, Photodiode.bandCentre(b), bd -> {
                        double[] m = bd.parts.isEmpty() ? null : prev.bodyMotion().get(bd.parts.get(0));
                        return m == null ? 0 : m[1];
                    });
                }
                // the bands traced side by side (each its own scene, which a trace marks as it goes: one thread a scene)
                // — a colour-filter bench's three lamps were 108 traces, six seconds on one core
                final Vec3 axis = ax;
                double[][] got = new double[nb][pds.size()];
                java.util.stream.IntStream.range(0, nb).parallel().forEach(b -> {
                    double nm = Photodiode.bandCentre(b);
                    for (Vec3 dir : new Vec3[]{axis, axis.mul(-1)}) {
                        var res = dev.emergence.core.optics.TubeTracer.point(scenes[b], src.center(), dir, 0, nm, src.id(), 16, 7);
                        for (int k = 0; k < pds.size(); k++) got[b][k] += res.light(pds.get(k).id());
                    }
                });
                for (int b = 0; b < nb; b++)
                    for (int k = 0; k < pds.size(); k++)
                        if (got[b][k] > 0) g.computeIfAbsent(pds.get(k).id(), id -> new double[nb])[b] += 0.5 * emit * got[b][k];
                for (var en : g.entrySet()) {
                    Photodiode pd = c.receiver(en.getKey());
                    if (pd == null) continue;
                    double[] gb = en.getValue();
                    double peak = 0;
                    for (int b = 0; b < nb; b++) peak = Math.max(peak, gb[b] * Photodiode.bandCurrent(b, tMax));
                    if (peak > 1e-11) pd.addSpectralHotSource(tn, gb, ambientT);
                }
                // to draw by: the ray from the part through the nearest optic's centre, in seven colours
                List<List<Vec3>> rays = new ArrayList<>();
                List<Double> nms = new ArrayList<>();
                for (double nm : new double[]{420, 460, 500, 540, 580, 620, 680}) {
                    var sc = AssemblyOptics.scene(a, c, nm, bd -> {
                        double[] m = bd.parts.isEmpty() ? null : prev.bodyMotion().get(bd.parts.get(0));
                        return m == null ? 0 : m[1];
                    });
                    var rs = dev.emergence.core.optics.TubeTracer.beams(sc, src.center(), ax, 400, nm, src.id(), 0.25);
                    if (rs.isEmpty()) continue;
                    rays.add(rs.get(0));
                    nms.add(nm);
                }
                if (!rays.isEmpty()) {
                    c.beams.put(src.id(), rays);
                    c.beamNm.put(src.id(), nms.stream().mapToDouble(Double::doubleValue).toArray());
                }
            }
        }

        /**
         * Sound: every body on a slide pushes air as a compact dipole ({@link dev.emergence.core.acoustics.DipoleRadiator})
         * of strength M = (8/3)(S/π)^{3/2} + V — S its outline seen along the slide (each cell column of its parts counted
         * once, at the largest cross-section any part offers there: a plate facing the travel its whole cell, a thin
         * plate edge-on its thickness, a rod along the travel its section), V its solid volume — sitting at the centroid
         * of that outline. Each feels the air the others set moving ({@link dev.emergence.core.acoustics.AcousticCoupling});
         * each one's recent motion is what it sounds like in the game. Turning bodies do not radiate yet.
         */
        void buildAcoustics() {
            List<dev.emergence.core.acoustics.DipoleRadiator> rads = new ArrayList<>();
            List<Node> nodes = new ArrayList<>();
            for (CompiledAssembly.Body b : c.bodies) {
                if (b.isStatic || !b.slider || b.carrier != null || b.node == null) continue;
                int ax = Math.abs(b.axis.x()) > 0.5 ? 0 : Math.abs(b.axis.y()) > 0.5 ? 1 : 2;
                Map<Long, Double> columns = new HashMap<>();
                Map<Long, Vec3> where = new HashMap<>();
                double volume = 0;
                for (long id : b.parts) {
                    Part p = a.get(id);
                    if (p == null || isWire(p)) continue;
                    double rho = p.material().get(MaterialProperty.DENSITY, 1000);
                    volume += PartGeometry.mass(p) / rho;
                    double f;
                    if (p.kind() == PartKind.BLOCK) {
                        f = p.axis().axis() == ax ? 1 : Math.max(0.01, Math.min(1, p.param("fill", 1.0)));
                    } else if (p.kind() == PartKind.SHAFT) {
                        double d = PartGeometry.shaftDiameter(p) / CELL;
                        f = p.axis().axis() == ax ? Math.PI * d * d / 4 : Math.min(1, d);
                    } else {
                        f = 1;
                    }
                    for (int x = p.minX(); x <= p.maxX(); x++)
                        for (int y = p.minY(); y <= p.maxY(); y++)
                            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                                int[] cell = {x, y, z};
                                cell[ax] = 0;
                                long key = CellKey.key(cell[0], cell[1], cell[2]);
                                columns.merge(key, f, Math::max);
                                int[] full = {x, y, z};
                                where.merge(key, new Vec3(full[0] + 0.5, full[1] + 0.5, full[2] + 0.5).mul(CELL), (u, v) -> u);
                            }
                }
                double area = 0;
                Vec3 centre = Vec3.ZERO;
                for (var en : columns.entrySet()) {
                    area += en.getValue();
                    centre = centre.add(where.get(en.getKey()).mul(en.getValue()));
                }
                if (area <= 0) continue;
                centre = centre.mul(1 / area);
                area *= CELL * CELL;
                rads.add(new dev.emergence.core.acoustics.DipoleRadiator(centre, b.axis, dev.emergence.core.acoustics.DipoleRadiator.plateMoment(area, volume)));
                nodes.add(b.node);
            }
            if (rads.isEmpty()) return; // (one body alone still sounds: its history makes its tone, see CompiledAssembly#sounds)
            var air = dev.emergence.core.acoustics.SoundField.air(ambientT, 101_325);
            c.acoustics = c.network.add(new dev.emergence.core.acoustics.AcousticCoupling(air, rads, nodes));
        }

        /** Packs a cell coordinate into a map key. */
        static final class CellKey {
            static long key(int x, int y, int z) {
                return ((long) (x + 1024) << 22) | ((long) (y + 1024) << 11) | (z + 1024);
            }
        }

        /**
         * Eddy-current drag: every conducting plate or pipe wall near a magnet, the two on different bodies at least one
         * of which moves, gets an {@link dev.emergence.core.sim.elements.EddyCurrentDrag} between those bodies. The plate
         * (4×4 points across its face, its normal along its axis, its thickness its fill) or the pipe wall (12 × 8 points,
         * normals radial) is sampled ({@link dev.emergence.core.em.EddyCurrents}): at each point the field's normal
         * component from the magnet, the point's speed across the field per unit speed of the moving body, and
         * η·σ·t·A·B_n²·|v_⊥|² — tabulated over the moving body's angle (180 steps) or travel (64). Two bodies turning about one axis (a drag
         * cup) couple through the coefficient averaged over their relative angle.
         */
        void buildEddyCurrents() {
            List<Part> magnets = new ArrayList<>(), conductors = new ArrayList<>();
            for (Part p : parts) {
                if (p.kind() == PartKind.MAGNET && p.material().has(MaterialProperty.REMANENCE)) magnets.add(p);
                boolean sheet = p.kind() == PartKind.BLOCK || p.kind() == PartKind.PIPE;
                if (sheet && p.material().get(MaterialProperty.ELECTRICAL_RESISTIVITY, 1) < 1e-5) conductors.add(p);
            }
            if (magnets.isEmpty() || conductors.isEmpty()) return;
            for (Part mp : magnets) {
                CompiledAssembly.Body bm = c.bodyOfPart.get(mp.id());
                if (bm == null) continue;
                for (Part cp : conductors) {
                    CompiledAssembly.Body bc = c.bodyOfPart.get(cp.id());
                    if (bc == null || bc == bm || (bm.isStatic && bc.isStatic)) continue;
                    eddyPair(mp, bm, cp, bc);
                }
            }
        }

        void eddyPair(Part mp, CompiledAssembly.Body bm, Part cp, CompiledAssembly.Body bc) {
            dev.emergence.core.em.CuboidMagnet mag = PartGeometry.magnet(mp);
            List<dev.emergence.core.em.EddyCurrents.Sample> samples;
            if (cp.kind() == PartKind.BLOCK) {
                int ax = cp.axis().axis();
                double t = cp.boxSize(ax) * Math.max(0.01, Math.min(1, cp.param("fill", 1.0)));
                samples = dev.emergence.core.em.EddyCurrents.plate(cp.center(), ax, cp.boxSize((ax + 1) % 3), cp.boxSize((ax + 2) % 3), t, 4);
            } else {
                double w = PartGeometry.wallThickness(cp);
                samples = dev.emergence.core.em.EddyCurrents.tube(cp.center(), cp.axis().vec(), PartGeometry.pipeBore(cp) / 2 + w / 2, w,
                        PartGeometry.axialLength(cp), 12, 8);
            }
            double rho = cp.material().resistivity(ambientT), sigma = 1 / rho;
            boolean coaxial = !bm.isStatic && !bc.isStatic && !bm.slider && !bc.slider
                    && bm.axis.dot(bc.axis) > 1 - 1e-9 && bm.pivot.sub(bc.pivot).cross(bc.axis).length() < 1e-9;
            if (!bm.isStatic && !bc.isStatic && !coaxial) return; // two independently moving bodies: not modelled
            if (bm.carrier != null || bc.carrier != null) return; // a rotor riding a slide: not modelled
            CompiledAssembly.Body mover = bc.isStatic ? bm : bc;
            boolean slider = mover.slider;
            int n = slider ? 64 : 180;
            double lo = slider ? mover.strokeLo : 0, hi = slider ? mover.strokeHi : 2 * Math.PI;
            if (slider && !(hi > lo)) return;
            // the samples' (or the field pattern's) velocity per unit speed of the moving body
            java.util.function.Function<Vec3, Vec3> velocity = slider ? q -> mover.axis : q -> mover.axis.cross(q.sub(mover.pivot));
            boolean conductorMoves = mover == bc;
            double[] table = new double[n];
            double peak = 0;
            for (int k = 0; k < n; k++) {
                double x = slider ? lo + (hi - lo) * k / (n - 1) : (hi - lo) * k / n;
                List<dev.emergence.core.em.EddyCurrents.Sample> at = samples;
                dev.emergence.core.em.CuboidMagnet m = mag;
                if (conductorMoves) {
                    at = new ArrayList<>();
                    for (var smp : samples) at.add(slider ? smp.moved(mover.pivot, mover.axis, 0, mover.axis.mul(x))
                            : smp.moved(mover.pivot, mover.axis, x, Vec3.ZERO));
                } else {
                    m = slider ? mag.translated(mover.axis.mul(x)) : mag.rotated(mover.pivot, mover.axis, x);
                }
                table[k] = sigma * dev.emergence.core.em.EddyCurrents.coefficient(at, m, velocity);
                peak = Math.max(peak, table[k]);
            }
            if (peak < 1e-9) return;
            if (coaxial) {
                double avg = 0;
                for (double v : table) avg += v / n;
                table = new double[]{avg};
            }
            Node ref = mover.slider ? refT : refR;
            Node na = bc.isStatic ? ref : bc.node, nb = bm.isStatic ? ref : bm.node;
            AngleTracker pose = coaxial ? null : mover.tracker;
            c.network.add(new dev.emergence.core.sim.elements.EddyCurrentDrag(na, nb, c.thermalOfPart.get(cp.id()), pose, table, lo, hi,
                    !slider, cp.material(), rho));
        }

        /** True if some part of body b, turned to some angle, lands on one of the sample points. */
        boolean sweeps(CompiledAssembly.Body b, List<List<Vec3>> samples) {
            for (List<Vec3> pts : samples)
                for (Vec3 q : pts) {
                    Vec3 rel = q.sub(b.pivot);
                    double along = rel.dot(b.axis), radius = rel.sub(b.axis.mul(along)).length();
                    for (long id : b.parts) {
                        Part o = a.get(id);
                        Vec3 orel = o.center().sub(b.pivot);
                        double oa = orel.dot(b.axis), orad = orel.sub(b.axis.mul(oa)).length();
                        if (Math.abs(oa - along) < 0.6 * CELL && Math.abs(orad - radius) < 0.8 * CELL) return true;
                    }
                }
            return false;
        }

        /** Rotates v about the unit axis n by angle t (Rodrigues). */
        static Vec3 rotate(Vec3 v, Vec3 n, double t) {
            double ct = Math.cos(t), st = Math.sin(t);
            return v.mul(ct).add(n.cross(v).mul(st)).add(n.mul(n.dot(v) * (1 - ct)));
        }

        // ------------------------------------------------------------ fluids
        /**
         * Every fluid-holding part is a control volume; volumes sharing a face are joined by a flow branch
         * (pipes and valves along their axis or sideways as a tee; tanks on every face; rotors along their
         * axis). An open axial end of a pipe, valve or rotor facing free air vents to the atmosphere. A rotor
         * adds its blade row to the branch on its +axis face, acting through its rigid body's rotation.
         */
        /** A fluid part charged with a gas mixture ("x_" + species id = mole fraction). */
        static boolean isGasVessel(Part p) {
            if (!p.kind().holdsFluid()) return false;
            for (String k : p.params().keySet()) if (k.startsWith("x_") || k.startsWith("liquid_")) return true;
            return false;
        }

        /** Parts of each gas-mixture network: the fluid parts connected (through ports) to a gas-charged part. */
        List<List<Part>> mixtureComponents() {
            List<Part> fl = new ArrayList<>();
            for (Part p : parts) if (p.kind().holdsFluid()) fl.add(p);
            Map<Long, Integer> comp = new HashMap<>();
            List<List<Part>> out = new ArrayList<>();
            for (Part start : fl) {
                if (comp.containsKey(start.id())) continue;
                List<Part> group = new ArrayList<>();
                Deque<Part> todo = new ArrayDeque<>(List.of(start));
                comp.put(start.id(), out.size());
                while (!todo.isEmpty()) {
                    Part p = todo.pop();
                    group.add(p);
                    for (Part q : fluidNeighbours(p)) {
                        if (comp.containsKey(q.id())) continue;
                        comp.put(q.id(), out.size());
                        todo.push(q);
                    }
                }
                out.add(group);
            }
            out.removeIf(g -> g.stream().noneMatch(Builder::isGasVessel));
            return out;
        }

        /** Fluid parts connected to p through matching ports. */
        List<Part> fluidNeighbours(Part p) {
            List<Part> out = new ArrayList<>();
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int y = p.minY(); y <= p.maxY(); y++)
                    for (int z = p.minZ(); z <= p.maxZ(); z++)
                        for (Dir d : Dir.values()) {
                            int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                            if (p.containsCell(nx, ny, nz)) continue;
                            for (Part o : a.at(nx, ny, nz)) {
                                if (o == p || !o.kind().holdsFluid() || out.contains(o)) continue;
                                if (ports(p, d, o) && ports(o, d.opposite(), p)) out.add(o);
                            }
                        }
            return out;
        }

        /**
         * Gas-mixture networks: every fluid part connected to a gas-charged one holds a {@link GasReactor} over one
         * shared species list — everything charged anywhere in the network, the air species (uncharged parts and the
         * open air hold air), and every product the database's reactions can make from them. Adjacent parts are joined
         * by {@link MixtureBranch} passages (valves scale their opening; the rise between centres gives the gas
         * column's weight), open ends breathe the atmosphere at the hydrostatic pressure of their height, and a
         * cylinder's gas drives its piston. Each gas exchanges heat with its own wall by convection (h ≈ 10 W/m²K) and
         * gas radiation (ε ≈ 0.05).
         */
        void buildMixtureNetworks() {
            var chem = dev.emergence.core.chem.Chemistry.standard();
            for (List<Part> comp : mixtureComponents()) buildMixtureNetwork(comp, chem);
        }

        static final String[] AIR_SPECIES = {"N2", "O2", "Ar"};
        static final double[] AIR_FRACTIONS = {0.7809, 0.2095, 0.0096};

        void buildMixtureNetwork(List<Part> comp, dev.emergence.core.chem.Chemistry chem) {
            double pAtm = a.ambientPressure(), g = PartGeometry.gravity();
            Set<dev.emergence.core.chem.Species> present = new LinkedHashSet<>();
            for (String s : AIR_SPECIES) present.add(chem.species(s));
            for (Part p : comp)
                for (String k : p.params().keySet()) {
                    // "x_<species>": the gas's mole fractions; "liquid_<species>": moles of a condensable species' liquid
                    String id = k.startsWith("x_") ? k.substring(2) : k.startsWith("liquid_") ? k.substring(7) : null;
                    if (id == null) continue;
                    if (chem.hasSpecies(id)) present.add(chem.species(id));
                    else c.warnings.add("Unknown species \"" + id + "\" in part #" + p.id());
                }
            boolean grew = true;
            while (grew) {
                grew = false;
                for (var rx : chem.reactions()) {
                    if (present.containsAll(rx.reactants().keySet()) && present.containsAll(rx.orders().keySet())
                            && !present.containsAll(rx.products().keySet())) {
                        present.addAll(rx.products().keySet());
                        grew = true;
                    }
                }
            }
            List<dev.emergence.core.chem.Species> list = new ArrayList<>(present);
            var reactions = chem.reactionsAmong(present);
            double[] xAir = new double[list.size()];
            for (int i = 0; i < AIR_SPECIES.length; i++) xAir[list.indexOf(chem.species(AIR_SPECIES[i]))] = AIR_FRACTIONS[i];
            double rhoAir = pAtm * 0.028965 / (dev.emergence.core.units.PhysicalConstants.GAS_CONSTANT * ambientT);
            double yRef = Double.MAX_VALUE;
            for (Part p : parts) yRef = Math.min(yRef, p.center().y());
            Map<Long, Object[]> pistons = new HashMap<>();
            for (Part p : comp) if (p.kind() == PartKind.CYLINDER) linkPiston(p, pistons);
            boolean condensables = list.stream().anyMatch(s -> dev.emergence.core.chem.VaporLiquid.of(s.id()) != null);
            Map<Long, GasReactor> gas = new HashMap<>();
            for (Part p : comp) {
                double[] st = prev.fluids().get(p.id());
                double t0 = st != null ? st[1] : prev.temperatures().getOrDefault(p.id(), ambientT);
                Object[] link = pistons.get(p.id());
                PistonKinematics k = link == null ? null : (PistonKinematics) link[0];
                CompiledAssembly.Body body = link == null ? null : (CompiledAssembly.Body) link[1];
                double v = k != null ? k.volume(body.tracker == null ? 0 : prev.bodyMotion().getOrDefault(p.id(), new double[]{0, 0})[1])
                        : Math.max(PartGeometry.fluidVolume(p), 1e-9);
                double pHydro = pAtm - rhoAir * g * (p.center().y() - yRef);
                double p0 = st != null ? st[0] : p.param("pressure", pHydro);
                double nt = p0 * v / (dev.emergence.core.units.PhysicalConstants.GAS_CONSTANT * t0);
                double sum = 0;
                for (var e : p.params().entrySet()) if (e.getKey().startsWith("x_")) sum += Math.max(0, e.getValue());
                double[] n0 = new double[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    Double saved = prev.chemistry().get(gasKey(p.id(), list.get(i).id()));
                    double xi = sum > 0 ? Math.max(0, p.param("x_" + list.get(i).id(), 0)) / sum : xAir[i];
                    n0[i] = saved != null ? saved : nt * xi;
                }
                Node tn = c.network.node(Domain.THERMAL, "Tg" + p.id(), t0);
                Node pn = c.network.node(Domain.FLUID, "pg" + p.id(), p0);
                GasReactor r = new GasReactor(list, reactions, v, tn, n0).withPressureNode(pn);
                // condensable species (water, ethanol…) may also be liquid in the vessel: every vessel of the network gets
                // their pools (a cold one condenses what reaches it), over its horizontal section ≈ V^(2/3)
                if (condensables) {
                    double[] liq = new double[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        Double saved = prev.chemistry().get("L" + p.id() + ":" + list.get(i).id());
                        liq[i] = saved != null ? saved : Math.max(0, p.param("liquid_" + list.get(i).id(), 0));
                    }
                    r.withLiquid(liq, Math.pow(v, 2.0 / 3));
                }
                if (k != null) {
                    r.withPiston(body.tracker, k);
                    PistonCoupling pc = c.network.add(new PistonCoupling(pn, tn, body.node, body.tracker, k, pAtm,
                            p.param("ring_friction", 2.0), c.thermalOfPart.get(p.id())));
                    c.pistons.put(p.id(), pc);
                    c.pistonLateral.put(p.id(), (Vec3) link[2]);
                }
                c.network.add(r);
                gas.put(p.id(), r);
                c.gasOfPart.put(p.id(), r);
                Node wall = c.thermalOfPart.get(p.id());
                double area = PartGeometry.wettedArea(p);
                // gas against a wall: ~10 W/(m²·K); a vessel holding liquid (it boils on the wall) or made a condenser
                // ("condenser" = 1: vapour condenses on its cold wall) exchanges far more — film condensation and
                // nucleate boiling run to thousands; 200 is a modest lumped figure
                boolean wet = p.param("condenser", 0) > 0;
                for (String key : p.params().keySet()) if (key.startsWith("liquid_") && p.param(key, 0) > 0) wet = true;
                c.network.add(new ThermalConductance(tn, wall, (condensables && wet ? 200 : 10) * area));
                c.network.add(new Radiation(tn, wall, 0.05, area));
                // the thermal boundary layer at the wall (δ = k/h ≈ 2 mm of gas at the wall's temperature)
                if (!reactions.isEmpty()) r.withWallFilm(wall, area, 0.002);
            }
            // a heating element set into a vessel ("exposed" = 1, a glow plug): its hot surface meets the gas directly —
            // a film of gas at the element's temperature (hot-surface ignition) and convection to the bulk
            for (Part h : parts) {
                if (h.kind() != PartKind.RESISTOR || h.param("exposed", 0) <= 0) continue;
                Node hn = c.thermalOfPart.get(h.id());
                if (hn == null) continue;
                double area = h.param("surface_area", 1e-4);
                for (Dir d : Dir.values()) {
                    for (Part q : a.at(h.minX() + d.dx, h.minY() + d.dy, h.minZ() + d.dz)) {
                        GasReactor r = gas.get(q.id());
                        if (r == null) continue;
                        if (!reactions.isEmpty()) r.withWallFilm(hn, area, 0.002);
                        c.network.add(new ThermalConductance(hn, r.temperatureNode(), 50 * area));
                    }
                }
            }
            Set<Long> seen = new HashSet<>();
            for (Part p : comp) {
                for (int x = p.minX(); x <= p.maxX(); x++)
                    for (int y = p.minY(); y <= p.maxY(); y++)
                        for (int z = p.minZ(); z <= p.maxZ(); z++)
                            for (Dir d : Dir.values()) {
                                int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                                if (p.containsCell(nx, ny, nz)) continue;
                                boolean axial = d.axis() == p.axis().axis();
                                Part q = null;
                                for (Part o : a.at(nx, ny, nz)) if (o.kind().holdsFluid() && o != p) q = o;
                                if (q == null) {
                                    if (!axial || p.kind() == PartKind.TANK || !a.at(nx, ny, nz).isEmpty() || a.isSolidCell(nx, ny, nz)) continue;
                                    if (p.kind() == PartKind.CYLINDER && d == p.axis()) continue;
                                    // an open end: the atmosphere at the vent's height
                                    double half = halfExtent(p, d), yv = p.center().y() + d.dy * half;
                                    double pv = pAtm - rhoAir * g * (yv - yRef);
                                    double av = mixturePassage(p, d);
                                    var b = new MixtureBranch(list, MixtureBranch.End.of(gas.get(p.id())),
                                            MixtureBranch.End.reservoir(pv, ambientT, xAir), av, equivalentDiameter(av), half, 1.0)
                                            .withRise(d.dy * half).withTag(p.kind() + "#" + p.id() + "->air");
                                    mixtureValve(p, b);
                                    c.network.add(b);
                                    continue;
                                }
                                if (!gas.containsKey(q.id())) {
                                    c.warnings.add("Parts #" + p.id() + " and #" + q.id() + " hold different fluids and are not connected");
                                    continue;
                                }
                                if (!ports(p, d, q) || !ports(q, d.opposite(), p)) continue;
                                if (!seen.add(pairKey(p.id(), q.id()))) continue;
                                // a valve has one seat, on its +axis face; through its other faces gas enters its body
                                double ap = mixturePassage(p, d), aq = mixturePassage(q, d.opposite());
                                double amin = Math.min(ap, aq), sigma = amin / Math.max(ap, aq);
                                double kk = 0.5 * (1 - sigma) + (1 - sigma) * (1 - sigma) + 0.5;
                                if (!axial && p.kind() != PartKind.TANK) kk += 0.9;
                                if (d.axis() != q.axis().axis() && q.kind() != PartKind.TANK) kk += 0.9;
                                double len = halfExtent(p, d) + halfExtent(q, d.opposite());
                                var b = new MixtureBranch(list, MixtureBranch.End.of(gas.get(p.id())), MixtureBranch.End.of(gas.get(q.id())),
                                        amin, equivalentDiameter(amin), len, kk).withRise(q.center().y() - p.center().y())
                                        .withTag(p.kind() + "#" + p.id() + "->" + q.kind() + "#" + q.id());
                                mixtureValve(p, b);
                                mixtureValve(q, b);
                                c.network.add(b);
                                if (condensables) {
                                    // the liquid pools pass the same way, by their levels and weights
                                    double[] sp = liquidSpace(p, gas.get(p.id())), sq = liquidSpace(q, gas.get(q.id()));
                                    double portP, portQ;
                                    // (a tank's "weir" [m]: its outlet to the vessel below stands that high over its
                                    // floor, so it holds a pool that deep — a column's tray, which the vapour from below
                                    // passes through)
                                    if (d == Dir.UP) {
                                        portP = sp[0] + sp[2];
                                        portQ = sq[0] + weir(q, sq);
                                    } else if (d == Dir.DOWN) {
                                        portP = sp[0] + weir(p, sp);
                                        portQ = sq[0] + sq[2];
                                    } else {
                                        portP = portQ = Math.max(y * CELL, Math.max(sp[0], sq[0]));
                                    }
                                    c.network.add(new LiquidDrain(gas.get(p.id()), sp[0], sp[1], portP, gas.get(q.id()), sq[0], sq[1], portQ, amin)
                                            .gatedBy(b));
                                }
                            }
            }
        }

        /**
         * The space a liquid fills in a gas-holding part: {floor height [m], horizontal section [m²], height [m]} — a tank
         * between its walls, an upright tube its length, a lying tube its bore (its volume spread over that height).
         */
        double[] liquidSpace(Part p, GasReactor g) {
            double floor, height;
            if (p.kind() == PartKind.TANK) {
                double w = PartGeometry.wallThickness(p);
                floor = p.minY() * CELL + w;
                height = Math.max(p.boxSize(1) - 2 * w, 1e-3);
            } else if (p.axis().axis() == 1) {
                floor = p.minY() * CELL;
                height = p.boxSize(1);
            } else {
                double bore = 2 * Math.sqrt(PartGeometry.passageArea(p) / Math.PI);
                floor = p.center().y() - bore / 2;
                height = bore;
            }
            return new double[]{floor, g.volume() / height, height};
        }

        /** Height of a tank's liquid outlet to the vessel below over its floor [m] (at most 90 % of its liquid space). */
        static double weir(Part p, double[] space) {
            return p.kind() == PartKind.TANK ? Math.max(0, Math.min(p.param("weir", 0), 0.9 * space[2])) : 0;
        }

        /** Flow area through face d of part p in a mixture network (a valve's seat only on its +axis face). */
        double mixturePassage(Part p, Dir d) {
            if (p.kind() == PartKind.VALVE && d != p.axis()) {
                double ch = PartGeometry.valveChamber(p);
                return Math.PI * ch * ch / 4;
            }
            return PartGeometry.passageArea(p);
        }

        /** A valve's seat in a mixture passage: an orifice (C_d ≈ 0.6) of the valve's bore, opened by hand. */
        void mixtureValve(Part v, MixtureBranch b) {
            if (v.kind() != PartKind.VALVE) return;
            Object[] cam = cams.computeIfAbsent(v.id(), k -> findCam(v));
            if (cam.length > 0) {
                // a valve whose stem rides on a cam: opened by the turning shaft (no hand wheel)
                CompiledAssembly.Body body = (CompiledAssembly.Body) cam[0];
                b.withCam(body.tracker, (Double) cam[1], v.param("lap", 0.0));
                b.setOpening(1);
                return;
            }
            double[] plug = plugs.computeIfAbsent(v.id(), k -> findPlug(v));
            if (plug.length > 0) {
                // a valve whose plug is on a slide: opened by the slide's travel (no hand wheel)
                b.withPlug(plugBody.get(v.id()).tracker, plug[0], plug[1], plug[2]);
                b.setOpening(1);
                return;
            }
            b.setOpening(v.switchClosed() ? 1 : 0);
            c.mixtureValves.computeIfAbsent(v.id(), kk -> new ArrayList<>()).add(b);
        }

        static String gasKey(long part, String species) {
            return "G" + part + ":" + species;
        }

        void buildFluids() {
            buildMixtureNetworks();
            var db = dev.emergence.core.fluids.FluidDatabase.standard();
            List<Part> fluids = new ArrayList<>();
            for (Part p : parts) if (p.kind().holdsFluid() && !c.gasOfPart.containsKey(p.id())) fluids.add(p);
            if (fluids.isEmpty()) return;
            double pAtm = a.ambientPressure();
            ventRef = Double.MAX_VALUE;
            for (Part p : fluids) ventRef = Math.min(ventRef, p.center().y());
            Map<Long, Object[]> pistons = new HashMap<>();
            for (Part p : fluids) if (p.kind() == PartKind.CYLINDER) linkPiston(p, pistons);
            Map<Long, Run> runOf = findRuns(fluids, pistons);
            for (Part p : fluids) {
                Run run = runOf.get(p.id());
                if (run != null && run.rep != p) continue; // a run is one volume: made with its first part
                var f = db.byCode(PartGeometry.fluidCode(p));
                double[] st = prev.fluids().get(p.id());
                double p0 = st != null ? st[0] : p.param("pressure", pAtm);
                double t0 = st != null ? st[1] : prev.temperatures().getOrDefault(p.id(), ambientT);
                boolean wet = f instanceof dev.emergence.core.fluids.WaterSteam;
                double s0 = 1;
                if (wet) {
                    // water/steam: the state is (T, phase); a fresh part is saturated with its "fill" of liquid
                    s0 = st != null && st.length > 2 ? st[2]
                            : dev.emergence.core.fluids.WaterSteam.phaseForLiquidFraction(t0, p.param("fill", 0));
                    double[] sv = new double[9];
                    dev.emergence.core.fluids.WaterSteam.state(t0, s0, sv);
                    p0 = sv[dev.emergence.core.fluids.WaterSteam.P];
                }
                Node pn = c.network.node(Domain.FLUID, "p" + p.id(), p0);
                Node tn = c.network.node(Domain.THERMAL, "Tf" + p.id(), t0);
                Object[] link = pistons.get(p.id());
                FluidVolume v;
                if (link != null) {
                    PistonKinematics k = (PistonKinematics) link[0];
                    CompiledAssembly.Body body = (CompiledAssembly.Body) link[1];
                    v = c.network.add(new FluidVolume(pn, tn, f, k.volume(0)).withPiston(body.tracker, k));
                    PistonCoupling pc = c.network.add(new PistonCoupling(pn, tn, body.node, body.tracker, k, pAtm,
                            p.param("ring_friction", 2.0), c.thermalOfPart.get(p.id())));
                    c.pistons.put(p.id(), pc);
                    c.pistonLateral.put(p.id(), (Vec3) link[2]);
                } else {
                    double vol = run != null ? run.volume : PartGeometry.fluidVolume(p);
                    v = c.network.add(new FluidVolume(pn, tn, f, Math.max(vol, 1e-9)));
                }
                if (wet) v.withPhase(s0);
                c.fluidOfPart.put(p.id(), v);
                if (run != null) for (Part m : run.members) c.fluidOfPart.put(m.id(), v);
            }
            Map<Long, List<FluidBranch>> through = new HashMap<>();
            Set<Long> seen = new HashSet<>();
            Node[] atm = new Node[2];
            for (Part p : fluids) {
                for (int x = p.minX(); x <= p.maxX(); x++)
                    for (int y = p.minY(); y <= p.maxY(); y++)
                        for (int z = p.minZ(); z <= p.maxZ(); z++)
                            for (Dir d : Dir.values()) {
                                int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                                if (p.containsCell(nx, ny, nz)) continue;
                                boolean axial = d.axis() == p.axis().axis();
                                Part q = null;
                                for (Part o : a.at(nx, ny, nz)) if (o.kind().holdsFluid() && o != p) q = o;
                                if (q == null) {
                                    // an open end: along the axis of a tube-like part, into a cell that is neither
                                    // occupied by a part nor solid surroundings — nor joined across scales to another
                                    if (!axial || p.kind() == PartKind.TANK || !a.at(nx, ny, nz).isEmpty() || a.isSolidCell(nx, ny, nz)) continue;
                                    if (fluidLinked(p, x, y, z, d)) continue;
                                    if (p.kind() == PartKind.CYLINDER && d == p.axis()) continue; // the rod side is open crankcase
                                    addVent(p, new int[]{x, y, z}, d, atm, pAtm, through);
                                    continue;
                                }
                                if (!ports(p, d, q) || !ports(q, d.opposite(), p)) continue;
                                if (!seen.add(pairKey(p.id(), q.id()))) continue;
                                Run rp = runOf.get(p.id()), rq = runOf.get(q.id());
                                if (rp != null && rp == rq) continue; // inside a run: one volume
                                FluidVolume va = c.fluidOfPart.get(p.id()), vb = c.fluidOfPart.get(q.id());
                                if (va.fluid() != vb.fluid()) {
                                    c.warnings.add("Parts #" + p.id() + " and #" + q.id() + " hold different fluids and are not connected");
                                    continue;
                                }
                                double ap = PartGeometry.passageArea(p), aq = PartGeometry.passageArea(q);
                                double amin = Math.min(ap, aq), sigma = amin / Math.max(ap, aq);
                                // sudden contraction + expansion (Borda-Carnot) at a change of section, plus a
                                // tee/elbow loss where the flow turns into or out of a tube's side
                                double k = 0.5 * (1 - sigma) + (1 - sigma) * (1 - sigma);
                                if (!axial && p.kind() != PartKind.TANK) k += 0.9;
                                if (d.axis() != q.axis().axis() && q.kind() != PartKind.TANK) k += 0.9;
                                double len = halfExtent(p, d) + halfExtent(q, d.opposite());
                                if (rp != null) {
                                    len += rp.internalLength / 2;
                                    k += rp.internalK / 2;
                                }
                                if (rq != null) {
                                    len += rq.internalLength / 2;
                                    k += rq.internalK / 2;
                                }
                                double dz = (rq != null ? rq.yRef : q.center().y()) - (rp != null ? rp.yRef : p.center().y());
                                // two vessels side by side holding water and steam: the face between them passes both at
                                // once, steam over the top and water under it the other way (stratified, counter-current);
                                // one passage drawing at mid-height passes only one phase at a time — a boiler's cell
                                // making the steam could not take water back from the one its riser drew from. Its upper
                                // and lower halves, each drawing at its own height:
                                // The same holds for any side face, a lying tube's above all: its condensate runs along
                                // the bottom of the bore under the steam, so a level steam line drains (into a valve or
                                // an upright riser), where one passage at mid-bore kept each of its
                                // cells half full of water. Not a cylinder's: its water lies on the moving piston or
                                // the head, which a port at a fixed height does not describe.
                                boolean tanks = p.kind() == PartKind.TANK && q.kind() == PartKind.TANK;
                                int halves = va.twoPhase() && d != Dir.UP && d != Dir.DOWN
                                        && p.kind() != PartKind.CYLINDER && q.kind() != PartKind.CYLINDER ? 2 : 1;
                                // each half's height in the relative coordinate of each side: a tank's rows between
                                // tanks, else a quarter of the opening (the smaller bore) over the side's liquid height
                                double open = 2 * Math.sqrt(amin / Math.PI);
                                double qp = tanks ? 0.25 / (p.maxY() - p.minY() + 1) : 0.25 * Math.min(1, open / liquidHeight(p, rp));
                                double qq = tanks ? 0.25 / (q.maxY() - q.minY() + 1) : 0.25 * Math.min(1, open / liquidHeight(q, rq));
                                for (int half = 0; half < halves; half++) {
                                    double shift = halves == 1 ? 0 : half == 0 ? -1 : 1;
                                    FluidBranch b = new FluidBranch(va.pressureNode(), va.temperatureNode(), vb.pressureNode(),
                                            vb.temperatureNode(), va.fluid(), len, equivalentDiameter(amin / halves),
                                            Math.max(roughness(p), roughness(q)), k, dz)
                                            .withTag(p.kind() + "#" + p.id() + "->" + q.kind() + "#" + q.id() + (halves == 1 ? "" : half == 0 ? " (lower)" : " (upper)"));
                                    if (va.twoPhase()) b.withPorts(va.port((rp != null ? rp.portHeight(y, d) : portHeight(p, y, d)) + shift * qp,
                                                    liquidHeight(p, rp)),
                                            vb.port((rq != null ? rq.portHeight(ny, d.opposite()) : portHeight(q, ny, d.opposite())) + shift * qq,
                                                    liquidHeight(q, rq)));
                                    double dhMin = Math.min(PartGeometry.hydraulicDiameter(p), PartGeometry.hydraulicDiameter(q));
                                    if (p.kind() != PartKind.TANK && q.kind() != PartKind.TANK) b.withHydraulicDiameter(dhMin);
                                    Part rotor = rotorFor(p, d, q);
                                    if (rotor != null) attachRotor(b, rotor, rotor == p ? d : d.opposite(), rotor == p);
                                    for (Part valve : new Part[]{p, q}) {
                                        if (valve.kind() != PartKind.VALVE) continue;
                                        // the valve seat is an orifice of the valve's bore (globe valve C_d ≈ 0.6)
                                        b.withOrifice(PartGeometry.passageArea(valve), 0.6);
                                        b.setOpening(valve.switchClosed() ? 1 : 0);
                                        if (valveAction(valve, b, valve == p ? d : d.opposite(), valve == p))
                                            c.valveBranches.computeIfAbsent(valve.id(), kk -> new ArrayList<>()).add(b);
                                    }
                                    c.network.add(b);
                                    through.computeIfAbsent((rp != null ? rp.rep : p).id(), kk -> new ArrayList<>()).add(b);
                                    through.computeIfAbsent((rq != null ? rq.rep : q).id(), kk -> new ArrayList<>()).add(b);
                                }
                            }
            }
            // passages across scales: a miniature pipe's end against a full-size pipe's port (Assembly.Link FLUID)
            for (Assembly.Link l : a.links()) {
                if (l.kind() != Assembly.LinkKind.FLUID) continue;
                Part p = partById(l.a()), q = partById(l.b());
                FluidVolume va = p == null ? null : c.fluidOfPart.get(p.id()), vb = q == null ? null : c.fluidOfPart.get(q.id());
                if (va == null || vb == null || va == vb) continue;
                if (va.fluid() != vb.fluid()) {
                    c.warnings.add("Parts #" + p.id() + " and #" + q.id() + " hold different fluids and are not connected");
                    continue;
                }
                double ap = PartGeometry.passageArea(p), aq = PartGeometry.passageArea(q);
                double amin = Math.min(ap, aq), sigma = amin / Math.max(ap, aq);
                double k = 0.5 * (1 - sigma) + (1 - sigma) * (1 - sigma);
                double len = halfExtent(p, l.faceA()) + halfExtent(q, l.faceB());
                FluidBranch b = new FluidBranch(va.pressureNode(), va.temperatureNode(), vb.pressureNode(), vb.temperatureNode(), va.fluid(),
                        len, equivalentDiameter(amin), Math.max(roughness(p), roughness(q)), k, 0).withTag(p.kind() + "#" + p.id() + "->" + q.kind() + "#" + q.id() + " (across scales)");
                c.network.add(b);
                Run rp = runOf.get(p.id()), rq = runOf.get(q.id());
                through.computeIfAbsent((rp != null ? rp.rep : p).id(), kk -> new ArrayList<>()).add(b);
                through.computeIfAbsent((rq != null ? rq.rep : q).id(), kk -> new ArrayList<>()).add(b);
            }
            // heat exchange between each fluid volume and its own wall (boiling and condensation for water)
            for (Part p : fluids) {
                FluidVolume v = c.fluidOfPart.get(p.id());
                Run run = runOf.get(p.id());
                c.network.add(new FluidWallConvection(v.temperatureNode(), c.thermalOfPart.get(p.id()), v.fluid(),
                        PartGeometry.wettedArea(p), PartGeometry.hydraulicDiameter(p), PartGeometry.passageArea(p),
                        through.getOrDefault((run != null ? run.rep : p).id(), List.of()))
                        .withTwoPhase(v, run != null ? run.height() : p.boxSize(1)));
            }
        }

        /**
         * A run of plain pipe: single-cell pipes of one bore and one fluid, each joined to exactly two others along the
         * run (or to whatever the run ends at), with no open end, rotor or piston. It is one volume — the cells' fluid
         * moves together, its pressure and temperature one — where each cell was one, with its own pressure and
         * temperature and a flow between each pair: a lumped pressure–flow model of ordinary piping. What it keeps: the
         * cells' total volume (what the run holds), their walls (each exchanges heat with the run's fluid), the friction
         * of the run's whole length and the loss at each turn within it (half each to its two ends' branches), and its
         * rise (its ends' branches measure height to the run's mean height, so the head from end to end is exact). What
         * it loses: a pressure or temperature gradient along the run, and two phases stratified along a tall run (its
         * liquid level is taken over the run's height). Long pipelines were most of a steam plant's unknowns, and their
         * smallest cells held its steps short. {@code -Ddiag.noLump=true} builds every cell its own volume.
         */
        static final class Run {
            final List<Part> members = new ArrayList<>();
            Part rep;
            double volume, internalLength, internalK, yRef;
            int yMinCell = Integer.MAX_VALUE, yMaxCell = Integer.MIN_VALUE;

            /** Relative height (0 bottom ... 1 top) of a port through face d of cell row y, over the run's height. */
            double portHeight(int y, Dir d) {
                double top = yMaxCell - yMinCell + 1;
                double at = d == Dir.UP ? y - yMinCell + 1 : d == Dir.DOWN ? y - yMinCell : y - yMinCell + 0.5;
                return Math.max(0, Math.min(1, at / top));
            }

            double height() {
                return (yMaxCell - yMinCell + 1) * CELL;
            }
        }

        /** The runs of plain pipe among the fluid parts (see {@link Run}), by member part id. */
        Map<Long, Run> findRuns(List<Part> fluids, Map<Long, Object[]> pistons) {
            Map<Long, Run> out = new HashMap<>();
            if (Boolean.getBoolean("diag.noLump")) return out;
            // each part's fluid connections, by the rules the branches are made by
            Map<Long, List<Part>> nbr = new HashMap<>();
            Map<Long, List<Dir>> nbrDir = new HashMap<>();
            Set<Long> special = new HashSet<>();
            for (Part p : fluids) {
                for (int x = p.minX(); x <= p.maxX(); x++)
                    for (int y = p.minY(); y <= p.maxY(); y++)
                        for (int z = p.minZ(); z <= p.maxZ(); z++)
                            for (Dir d : Dir.values()) {
                                int nx = x + d.dx, ny = y + d.dy, nz = z + d.dz;
                                if (p.containsCell(nx, ny, nz)) continue;
                                boolean axial = d.axis() == p.axis().axis();
                                Part q = null;
                                for (Part o : a.at(nx, ny, nz)) if (o.kind().holdsFluid() && o != p) q = o;
                                if (q == null) {
                                    if (axial && p.kind() != PartKind.TANK && a.at(nx, ny, nz).isEmpty() && !a.isSolidCell(nx, ny, nz))
                                        special.add(p.id()); // an open end
                                    continue;
                                }
                                if (!ports(p, d, q) || !ports(q, d.opposite(), p)) continue;
                                if (PartGeometry.fluidCode(p) != PartGeometry.fluidCode(q)) continue;
                                if (rotorFor(p, d, q) != null) {
                                    special.add(p.id());
                                    special.add(q.id());
                                }
                                nbr.computeIfAbsent(p.id(), kk -> new ArrayList<>()).add(q);
                                nbrDir.computeIfAbsent(p.id(), kk -> new ArrayList<>()).add(d);
                            }
            }
            java.util.function.Predicate<Part> plain = p -> p.kind() == PartKind.PIPE && !pistons.containsKey(p.id())
                    && !special.contains(p.id()) && nbr.getOrDefault(p.id(), List.of()).size() == 2
                    && p.minX() == p.maxX() && p.minY() == p.maxY() && p.minZ() == p.maxZ();
            Set<Long> done = new HashSet<>();
            for (Part start : fluids) {
                if (done.contains(start.id()) || !plain.test(start)) continue;
                double area = PartGeometry.passageArea(start);
                int code = PartGeometry.fluidCode(start);
                Run run = new Run();
                java.util.ArrayDeque<Part> todo = new java.util.ArrayDeque<>(List.of(start));
                done.add(start.id());
                while (!todo.isEmpty()) {
                    Part m = todo.poll();
                    run.members.add(m);
                    for (Part q : nbr.get(m.id())) {
                        if (done.contains(q.id()) || !plain.test(q) || PartGeometry.fluidCode(q) != code
                                || Math.abs(PartGeometry.passageArea(q) - area) > 1e-9 * area) continue;
                        done.add(q.id());
                        todo.add(q);
                    }
                }
                if (run.members.size() < 2) continue;
                run.members.sort(java.util.Comparator.comparingLong(Part::id));
                run.rep = run.members.get(0);
                Set<Long> in = new HashSet<>();
                for (Part m : run.members) in.add(m.id());
                double ySum = 0;
                for (Part m : run.members) {
                    run.volume += PartGeometry.fluidVolume(m);
                    ySum += m.center().y();
                    run.yMinCell = Math.min(run.yMinCell, m.minY());
                    run.yMaxCell = Math.max(run.yMaxCell, m.maxY());
                    List<Part> ns = nbr.get(m.id());
                    List<Dir> ds = nbrDir.get(m.id());
                    for (int i = 0; i < ns.size(); i++) {
                        Part q = ns.get(i);
                        if (!in.contains(q.id()) || q.id() < m.id()) continue; // each joint inside the run once
                        Dir d = ds.get(i);
                        run.internalLength += halfExtent(m, d) + halfExtent(q, d.opposite());
                        if (d.axis() != m.axis().axis()) run.internalK += 0.9; // a turn into a tube's side
                        if (d.axis() != q.axis().axis()) run.internalK += 0.9;
                    }
                }
                run.yRef = ySum / run.members.size();
                for (Part m : run.members) out.put(m.id(), run);
            }
            return out;
        }

        /**
         * How deep liquid can stand in a fluid part (or the run it is lumped into) [m]: a vessel between its walls, an
         * upright tube or run its length, a lying tube its bore.
         */
        static double liquidHeight(Part p, Run r) {
            if (r != null && r.yMaxCell > r.yMinCell) return r.height();
            if (p.kind() == PartKind.TANK) return Math.max(p.boxSize(1) - 2 * PartGeometry.wallThickness(p), 1e-3);
            if (p.axis().axis() == 1) return p.boxSize(1);
            return 2 * Math.sqrt(PartGeometry.passageArea(p) / Math.PI);
        }

        /** Relative height (0 bottom ... 1 top) of the port through face d of cell row y of a fluid part. */
        double portHeight(Part p, int y, Dir d) {
            if (d == Dir.UP) return 1;
            if (d == Dir.DOWN) return 0;
            return (y - p.minY() + 0.5) / (p.maxY() - p.minY() + 1);
        }

        /**
         * Valve behaviour beyond a plain seat: a spring-loaded check/relief valve (parameter {@code relief}, the
         * cracking pressure in Pa) passes flow only along the valve's +axis, each of its two seats taking half the
         * cracking pressure; a valve whose stem lines up with an eccentric (cam) on a turning shaft opens and
         * closes with it (and has no hand wheel). {@code face} is the valve face the branch crosses; {@code valveIsA}
         * whether the valve is the branch's a end. Returns true if the valve is worked by hand.
         */
        boolean valveAction(Part valve, FluidBranch b, Dir face, boolean valveIsA) {
            if (valve.params().containsKey("relief")) {
                double crack = valve.param("relief", 0);
                // forward = along +axis: out through the outlet face, or in through the inlet face
                boolean outletFace = face == valve.axis();
                double sense = (outletFace == valveIsA) ? 1 : -1;
                b.withCheck(crack / 2, Math.max(crack / 4, 2e3), sense);
                b.setOpening(1); // spring-loaded: always armed, no hand wheel
                return false;
            }
            Object[] cam = cams.computeIfAbsent(valve.id(), k -> findCam(valve));
            if (cam.length > 0) {
                CompiledAssembly.Body body = (CompiledAssembly.Body) cam[0];
                b.withCam(body.tracker, (Double) cam[1], valve.param("lap", 0.0));
                b.setOpening(1);
                return false;
            }
            double[] plug = plugs.computeIfAbsent(valve.id(), k -> findPlug(valve));
            if (plug.length > 0) {
                b.withPlug(plugBody.get(valve.id()).tracker, plug[0], plug[1], plug[2]);
                b.setOpening(1);
                return false;
            }
            return true;
        }

        final Map<Long, double[]> plugs = new HashMap<>();
        final Map<Long, CompiledAssembly.Body> plugBody = new HashMap<>();

        /**
         * The slide that works a valve's plug: the first part found looking out from the valve across its axis (through
         * empty cells, up to 12: a rod) that rides a sliding body moving along that line. Pushed fully in towards the
         * valve the plug is seated; drawn back it opens over the slide's stroke ({@code plug_travel} [m] on the valve
         * for a shorter one). Returns {seat displacement, sense, travel} or an empty array.
         */
        double[] findPlug(Part valve) {
            CompiledAssembly.Body vb = c.bodyOfPart.get(valve.id());
            if (vb != null && !vb.isStatic) return new double[0];
            int a0 = valve.axis().axis();
            for (Dir d : Dir.values()) {
                if (d.axis() == a0) continue;
                for (int k = 1; k <= 12; k++) {
                    List<Part> here = a.at(valve.minX() + d.dx * k, valve.minY() + d.dy * k, valve.minZ() + d.dz * k);
                    if (here.isEmpty()) continue;
                    CompiledAssembly.Body slide = null;
                    for (Part q : here) {
                        CompiledAssembly.Body bq = c.bodyOfPart.get(q.id());
                        if (bq != null && bq.slider && Math.abs(Math.abs(bq.axis.dot(d.vec())) - 1) < 1e-9) slide = bq;
                    }
                    if (slide == null) break;
                    double stroke = slide.strokeHi - slide.strokeLo;
                    if (!(stroke > 0)) break;
                    // moving towards the valve is along −d: the end it seats at, and the way it opens
                    boolean inIsUp = slide.axis.dot(d.vec()) < 0;
                    double pt = valve.param("plug_travel", 0), travel = pt > 0 ? Math.min(pt, stroke) : stroke;
                    plugBody.put(valve.id(), slide);
                    return new double[]{inIsUp ? slide.strokeHi : slide.strokeLo, inIsUp ? -1 : 1, travel};
                }
            }
            return new double[0];
        }

        final Map<Long, Object[]> cams = new HashMap<>();

        /**
         * The eccentric that works a valve's stem: the first eccentric found looking out from the valve across
         * its axis (through empty cells, up to 12 cells: a push rod), on a turning body. Returns {body, angle at
         * which the throw points at the valve} or an empty array.
         */
        Object[] findCam(Part valve) {
            int a0 = valve.axis().axis();
            for (Dir d : Dir.values()) {
                if (d.axis() == a0) continue;
                for (int k = 1; k <= 12; k++) {
                    int x = valve.minX() + d.dx * k, y = valve.minY() + d.dy * k, z = valve.minZ() + d.dz * k;
                    List<Part> here = a.at(x, y, z);
                    if (here.isEmpty()) continue;
                    Part ecc = null;
                    for (Part q : here) if (q.kind() == PartKind.ECCENTRIC) ecc = q;
                    if (ecc == null) break;
                    CompiledAssembly.Body body = c.bodyOfPart.get(ecc.id());
                    if (body == null || body.isStatic || body.slider || !sameFrame(body, c.bodyOfPart.get(valve.id()))) break;
                    Vec3 n = body.axis;
                    if (Math.abs(n.dot(d.vec())) > 1e-6) break;
                    Vec3 rel = valve.center().sub(body.pivot);
                    Vec3 radial = rel.sub(n.mul(rel.dot(n)));
                    Vec3 u = radial.mul(1 / radial.length());
                    int na = Math.abs(n.x()) > 0.5 ? 0 : Math.abs(n.y()) > 0.5 ? 1 : 2;
                    Vec3 ref = Vec3.axis((na + 1) % 3);
                    double phiU = Math.atan2(ref.cross(u).dot(n), ref.dot(u));
                    return new Object[]{body, phiU - Math.toRadians(ecc.param("phase", 0))};
                }
            }
            return new Object[0];
        }

        /**
         * Air blowing out of an open duct end cools what it meets: along the vent's axis, the first solid part in its
         * path (up to 16 cells downstream; what stands behind it is sheltered) gets a {@link
         * dev.emergence.core.sim.elements.JetConvection} — the free jet's velocity at that distance, cross-flow forced
         * convection over the part's surface — in addition to its natural convection.
         */
        void buildJets() {
            for (CompiledAssembly.Vent v : c.vents) {
                Part duct = a.get(v.partId());
                if (duct == null || v.branch() == null) continue;
                var fluid = c.fluidOfPart.get(duct.id());
                if (fluid == null || fluid.twoPhase() || !fluid.fluid().id().equals("air")) continue;
                double area = PartGeometry.passageArea(duct), d = equivalentDiameter(area);
                Dir dir = v.face();
                for (int k = 1; k <= 16; k++) {
                    int x = v.cell()[0] + dir.dx * k, y = v.cell()[1] + dir.dy * k, z = v.cell()[2] + dir.dz * k;
                    List<Part> here = a.at(x, y, z);
                    boolean stop = false;
                    for (Part q : here) {
                        if (q.kind().holdsFluid() || isWire(q)) continue;
                        Node tn = c.thermalOfPart.get(q.id());
                        if (tn == null) continue;
                        int ax = dir.axis();
                        double across = Math.max(q.boxSize((ax + 1) % 3), q.boxSize((ax + 2) % 3));
                        double dist = (k - 0.5) * CELL;
                        c.network.add(new dev.emergence.core.sim.elements.JetConvection(tn, c.ambient, v.branch(), area, d,
                                dist, PartGeometry.surfaceArea(q), across));
                        stop = true;
                    }
                    if (stop) break;
                }
            }
        }

        final Node[] atmSteam = new Node[2];
        /** The open air's reservoirs, for the vents into them (by their pressure node). */
        final Map<Node, FluidReservoir> reservoirOf = new HashMap<>();
        /** Height the single-fluid network's atmosphere node stands for [m]; vents above it see less pressure. */
        double ventRef;

        void addVent(Part p, int[] cell, Dir d, Node[] atm, double pAtm, Map<Long, List<FluidBranch>> through) {
            if (atm[0] == null) {
                atm[0] = c.network.node(Domain.FLUID, "atm.p", pAtm);
                atm[1] = c.network.node(Domain.THERMAL, "atm.T", ambientT);
                reservoirOf.put(atm[0], c.network.add(new FluidReservoir(atm[0], atm[1], pAtm, ambientT)));
            }
            FluidVolume v = c.fluidOfPart.get(p.id());
            Node[] sink = atm;
            if (v.twoPhase()) {
                // steam vents into the open air: modelled as a reservoir of steam at ambient pressure, just
                // superheated (any back-flow is steam, not air: no non-condensable gases are modelled)
                if (atmSteam[0] == null) {
                    double ts = dev.emergence.core.fluids.WaterSteam.tSat(pAtm) + 1;
                    atmSteam[0] = c.network.node(Domain.FLUID, "atm.steam.p", pAtm);
                    atmSteam[1] = c.network.node(Domain.THERMAL, "atm.steam.T", ts);
                    reservoirOf.put(atmSteam[0], c.network.add(new FluidReservoir(atmSteam[0], atmSteam[1], pAtm, ts)));
                }
                sink = atmSteam;
            }
            double half = halfExtent(p, d);
            // exit (or entry) loss K = 1 of a free jet
            FluidBranch b = new FluidBranch(v.pressureNode(), v.temperatureNode(), sink[0], sink[1], v.fluid(), half,
                    equivalentDiameter(PartGeometry.passageArea(p)), roughness(p), 1.0, d.dy * half)
                    .withTag(p.kind() + "#" + p.id() + "->air").withSinkReservoir(reservoirOf.get(sink[0]));
            // the outside air's own column: without it, two open ends at different heights of a cold duct would
            // draw a draft through it forever (the column inside weighs on its lower end, the outside would not)
            double rhoAir = pAtm * 0.028965 / (dev.emergence.core.units.PhysicalConstants.GAS_CONSTANT * ambientT);
            b.withEndPressureOffset(-rhoAir * PartGeometry.gravity() * (p.center().y() + d.dy * half - ventRef));
            if (v.twoPhase()) b.withPorts(v.port(portHeight(p, cell[1], d), liquidHeight(p, null)), null);
            if (p.kind() == PartKind.VALVE) {
                b.withOrifice(PartGeometry.passageArea(p), 0.6);
                b.setOpening(p.switchClosed() ? 1 : 0);
                if (valveAction(p, b, d, true)) c.valveBranches.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(b);
            }
            if (p.kind() == PartKind.ROTOR && d == Dir.fromAxis(p.axis().axis(), true)) attachRotor(b, p, d, true);
            c.network.add(b);
            through.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(b);
            c.vents.add(new CompiledAssembly.Vent(p.id(), cell, d, b));
        }

        /**
         * Puts rotor {@code r}'s blade row on branch b, which leaves the rotor through its face {@code face}
         * when {@code rotorIsA} (a = rotor), or enters it through that face otherwise.
         */
        void attachRotor(FluidBranch b, Part r, Dir face, boolean rotorIsA) {
            CompiledAssembly.Body body = c.bodyOfPart.get(r.id());
            if (body.carrier != null) {
                c.warnings.add("Rotor #" + r.id() + " rides on a slide: its blades are not coupled to the flow");
                return;
            }
            // flow a -> b runs along +axis when it leaves through the +axis face from the rotor, or enters the
            // rotor through its -axis face
            double flowAlongAxis = (rotorIsA ? 1 : -1) * face.sign() * r.axis().sign();
            double bodySense = body.isStatic ? 1 : Math.signum(body.axis.dot(r.axis().vec()));
            double rt = PartGeometry.rotorTipRadius(r), rh = PartGeometry.rotorHubRadius(r);
            b.withRotor(body.isStatic ? refR : body.node, Math.sqrt((rt * rt + rh * rh) / 2), PartGeometry.passageArea(r),
                    PartGeometry.rotorBladeAngle(r), r.param("pitch", 1), bodySense, flowAlongAxis,
                    r.param("blade_drag", 0.02), r.param("solidity", 0.8));
            c.rotorBranches.put(r.id(), b);
        }

        /**
         * Finds the crank a cylinder drives: following the cylinder's axis out of its rod (+axis) face, the
         * first part met must be an eccentric on a rotor whose axis is perpendicular to the cylinder and
         * passes through the cylinder's axis line. Rod length follows from where the head is: the piston
         * reaches the clearance gap below the head at top dead centre. The eccentric's "phase" sets where its
         * pin points at body angle 0, so several cylinders around one crank are phased by their positions.
         */
        void linkPiston(Part cyl, Map<Long, Object[]> out) {
            Dir ax = cyl.axis();
            int a0 = ax.axis();
            int[] lo = {cyl.minX(), cyl.minY(), cyl.minZ()}, hi = {cyl.maxX(), cyl.maxY(), cyl.maxZ()};
            int[] cell = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
            cell[a0] = ax.sign() > 0 ? hi[a0] : lo[a0];
            // a piston rod that is a slide: the piston moves with the slide (a hydraulic or pneumatic ram)
            {
                int x = cell[0] + ax.dx, y = cell[1] + ax.dy, z = cell[2] + ax.dz;
                for (Part q : a.at(x, y, z)) {
                    CompiledAssembly.Body sb = c.bodyOfPart.get(q.id());
                    if (sb == null || !sb.slider) continue;
                    if (sb.carrier != null) {
                        c.warnings.add("Cylinder #" + cyl.id() + ": its piston rod rides on a turning body (not modelled)");
                        return;
                    }
                    if (Math.abs(Math.abs(sb.axis.dot(ax.vec())) - 1) > 1e-6) {
                        c.warnings.add("Cylinder #" + cyl.id() + ": its piston rod slides across the bore");
                        return;
                    }
                    double length = cyl.boxSize(a0), clearance = Math.max(cyl.param("clearance", 0.05 * length), 1e-4);
                    double s0 = cyl.param("piston", 0.5) * (length - clearance), sign = Math.signum(sb.axis.dot(ax.vec()));
                    out.put(cyl.id(), new Object[]{new LinearPiston(PartGeometry.passageArea(cyl), clearance, s0, sign), sb, Vec3.ZERO});
                    // the piston meets the head at s = 0 and the rod end at s = length − clearance: the slide stops there
                    double x1 = -s0 / sign, x2 = (length - clearance - s0) / sign;
                    c.network.add(new TravelStops(sb.node, sb.tracker, Math.min(x1, x2), Math.max(x1, x2), 2e6, 200, null));
                    return;
                }
            }
            Part ecc = null;
            for (int k = 1; k <= 12 && ecc == null; k++) {
                int x = cell[0] + ax.dx * k, y = cell[1] + ax.dy * k, z = cell[2] + ax.dz * k;
                List<Part> here = a.at(x, y, z);
                for (Part q : here) if (q.kind() == PartKind.ECCENTRIC) ecc = q;
                if (ecc == null) {
                    for (Part q : here) {
                        if (q.kind() != PartKind.SHAFT) {
                            c.warnings.add("Cylinder #" + cyl.id() + ": its connecting rod is blocked by " + q.kind());
                            return;
                        }
                    }
                }
            }
            if (ecc == null) return;
            CompiledAssembly.Body body = c.bodyOfPart.get(ecc.id());
            if (body == null || body.isStatic || body.slider) {
                c.warnings.add("Cylinder #" + cyl.id() + ": its crank cannot turn");
                return;
            }
            if (!sameFrame(body, c.bodyOfPart.get(cyl.id()))) {
                c.warnings.add("Cylinder #" + cyl.id() + ": its crank rides on a slide past it (not modelled)");
                return;
            }
            Vec3 n = body.axis;
            if (Math.abs(n.dot(ax.vec())) > 1e-6) {
                c.warnings.add("Cylinder #" + cyl.id() + " is not perpendicular to its crankshaft");
                return;
            }
            Vec3 head = cyl.center().sub(ax.vec().mul(cyl.boxSize(a0) / 2));
            Vec3 rel = head.sub(body.pivot);
            Vec3 radial = rel.sub(n.mul(rel.dot(n)));
            double dist = radial.length();
            Vec3 u = radial.mul(1 / dist);
            if (Math.abs(u.dot(ax.vec()) + 1) > 1e-3) {
                c.warnings.add("Cylinder #" + cyl.id() + " does not point at its crankshaft");
                return;
            }
            double r = PartGeometry.eccentricThrow(ecc);
            double area = PartGeometry.passageArea(cyl);
            double stroke = 2 * r, length = cyl.boxSize(a0);
            double clearance = Math.max(cyl.param("clearance", 0.08 * stroke), 1e-4);
            if (stroke + clearance > length) {
                c.warnings.add("Cylinder #" + cyl.id() + ": stroke " + stroke + " m does not fit its " + length + " m bore");
                return;
            }
            double rod = dist - clearance - r;
            if (rod <= r) {
                c.warnings.add("Cylinder #" + cyl.id() + ": too close to its crank for the connecting rod");
                return;
            }
            // reference direction perpendicular to the crank axis, then the pin's angle at body angle 0
            int na = Math.abs(n.x()) > 0.5 ? 0 : Math.abs(n.y()) > 0.5 ? 1 : 2;
            Vec3 ref = Vec3.axis((na + 1) % 3);
            double phiU = Math.atan2(ref.cross(u).dot(n), ref.dot(u));
            double tdc = phiU - Math.toRadians(ecc.param("phase", 0));
            out.put(cyl.id(), new Object[]{new SliderCrank(area, r, rod, clearance, tdc), body, n.cross(u)});
        }

        /** Whether part p has a fluid port on its face d towards fluid part q. */
        boolean ports(Part p, Dir d, Part q) {
            boolean axial = d.axis() == p.axis().axis();
            return switch (p.kind()) {
                case TANK -> true;
                case ROTOR -> axial;
                // head and sides take pipes; the rod side faces the crank
                case CYLINDER -> d != p.axis();
                // tubes connect along their axis, and sideways into a tank or into another tube's end
                case PIPE, VALVE -> axial || q.kind() == PartKind.TANK || q.kind() == PartKind.CYLINDER || d.axis() == q.axis().axis();
                default -> false;
            };
        }

        /** The rotor whose +axis face this branch crosses, if any. */
        Part rotorFor(Part p, Dir d, Part q) {
            if (p.kind() == PartKind.ROTOR && d == Dir.fromAxis(p.axis().axis(), true)) return p;
            if (q.kind() == PartKind.ROTOR && d.opposite() == Dir.fromAxis(q.axis().axis(), true)) return q;
            return null;
        }

        /** Distance from the part's centre to its face d [m]. */
        double halfExtent(Part p, Dir d) {
            return p.boxSize(d.axis()) / 2;
        }

        double equivalentDiameter(double area) {
            return Math.sqrt(4 * Math.max(area, 1e-12) / Math.PI);
        }

        /** Absolute wall roughness [m] (Moody chart values: drawn tube 1.5 µm, stainless 15 µm, steel 45 µm). */
        double roughness(Part p) {
            String m = p.material().id();
            if (m.contains("copper") || m.contains("brass") || m.contains("pvc") || m.contains("glass")) return 1.5e-6;
            if (m.contains("stainless")) return 1.5e-5;
            return 4.5e-5;
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
