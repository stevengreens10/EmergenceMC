package dev.emergence.core.assembly;

import dev.emergence.core.em.MagneticSystem;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SimulationException;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.AngleTracker;
import dev.emergence.core.sim.elements.Capacitor;
import dev.emergence.core.sim.elements.Diode;
import dev.emergence.core.sim.elements.ElectrochemicalCell;
import dev.emergence.core.sim.elements.FluidBranch;
import dev.emergence.core.sim.elements.FluidVolume;
import dev.emergence.core.sim.elements.FlowSource;
import dev.emergence.core.sim.elements.Resistor;
import dev.emergence.core.sim.elements.Switch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The physics model compiled from an {@link Assembly}: the multiphysics network, its solver, and the
 * mappings from parts to network quantities used for instruments, rendering and persistence.
 */
public final class CompiledAssembly {

    /** A rigid body found by the compiler. Body 0 is always the static frame. */
    public static final class Body {
        final int index;
        final boolean isStatic;
        final Vec3 pivot, axis;
        final List<Long> parts = new ArrayList<>();
        Node node;
        AngleTracker tracker;
        double inertia, mass;
        /** A slider translates along its axis (node: velocity, tracker: displacement) instead of turning. */
        boolean slider;
        double strokeLo, strokeHi;
        /** The slide this rotor's bearings ride on (null: they are on the frame, or on a rotor sharing its axis). */
        Body carrier;
        /** Mass of the bodies a slide carries [kg] (they move with it). */
        double carriedMass;
        FlowSource drive; // external torque input (cranks, couplings to the world)

        Body(int index, boolean isStatic, Vec3 pivot, Vec3 axis) {
            this.index = index;
            this.isStatic = isStatic;
            this.pivot = pivot;
            this.axis = axis;
        }

        public int index() { return index; }
        public boolean isStatic() { return isStatic; }
        public Vec3 pivot() { return pivot; }
        public Vec3 axis() { return axis; }
        public List<Long> parts() { return Collections.unmodifiableList(parts); }
        public double inertia() { return inertia; }
        public boolean slider() { return slider; }
        public double mass() { return mass; }
        /** A slide's travel limits from where it was built [m] (±∞ where nothing stops it). */
        public double strokeLo() { return strokeLo; }
        public double strokeHi() { return strokeHi; }
        public Body carrier() { return carrier; }
    }

    final Assembly assembly;
    final Network network = new Network();
    TransientSolver solver;
    final List<Body> bodies = new ArrayList<>();
    final Map<Long, Body> bodyOfPart = new HashMap<>();
    final Map<Long, Node> thermalOfPart = new HashMap<>();
    final Map<Long, Node[]> leadsOfPart = new HashMap<>();   // {neg, pos} or {node}
    final Map<Long, Object> deviceOfPart = new HashMap<>(); // Resistor, Diode, Switch, Capacitor, MagneticSystem.Coil
    /** Chips with registers or memory, by their first segment's part id (their bits are saved with the build). */
    /** The digital side: event-driven chips' insides and the nets of their pins (see DigitalDomain). */
    final dev.emergence.core.digital.DigitalDomain digital = new dev.emergence.core.digital.DigitalDomain();
    /** Event-driven chips by their first segment's part id; each segment's package; display panels' controllers. */
    final Map<Long, dev.emergence.core.digital.DigitalChip> digitalChips = new java.util.LinkedHashMap<>();
    final Map<Long, Long> packageOfPart = new HashMap<>();
    /** The digital net a wire part is on (a net joining chip pins only), for instruments. */
    final Map<Long, dev.emergence.core.digital.DigitalDomain.Net> digitalNetOfPart = new HashMap<>();
    final Map<Long, dev.emergence.core.digital.DisplayPanel> displays = new java.util.LinkedHashMap<>();
    /**
     * Each display part's share of its screen's image: {u at its left edge, v at its top, u at its right, v at its
     * bottom} (the image 0..1 across and down; beyond that range, the bezel).
     */
    final Map<Long, float[]> displayTiles = new HashMap<>();

    /** A display part's share of its screen's image (see displayTiles), or null. */
    public float[] displayTile(long partId) {
        return displayTiles.get(partId);
    }

    /** The digital domain (its nets and events). */
    public dev.emergence.core.digital.DigitalDomain digital() {
        return digital;
    }

    /** The event-driven chip a part (any of its segments) belongs to, or null. */
    public dev.emergence.core.digital.DigitalChip digitalChip(long partId) {
        Long pkg = packageOfPart.get(partId);
        return pkg == null ? null : digitalChips.get(pkg);
    }

    /** Event-driven chips by their first segment's part id. */
    public Map<Long, dev.emergence.core.digital.DigitalChip> digitalChips() {
        return java.util.Collections.unmodifiableMap(digitalChips);
    }

    /** The display a DISPLAY part shows (its controller's), or null. */
    public dev.emergence.core.digital.DisplayPanel display(long partId) {
        return displays.get(partId);
    }

    /** Display parts and what they show. */
    public Map<Long, dev.emergence.core.digital.DisplayPanel> displays() {
        return java.util.Collections.unmodifiableMap(displays);
    }
    /** A phototransistor's light-sensitive collector–base junction (its device is the transistor). */
    final Map<Long, dev.emergence.core.sim.elements.Photodiode> photoJunction = new HashMap<>();

    /** The junction that turns light arriving at this part into current: a photodiode's, a phototransistor's; or null. */
    public dev.emergence.core.sim.elements.Photodiode receiver(long partId) {
        return deviceOfPart.get(partId) instanceof dev.emergence.core.sim.elements.Photodiode pd ? pd : photoJunction.get(partId);
    }
    final Map<Long, Double> failureTemp = new HashMap<>();
    final Map<Long, Double> crankTorque = new HashMap<>();
    final Map<Long, dev.emergence.core.sim.elements.HumanCrank> cranks = new HashMap<>();
    MagneticSystem magnetics;
    final Map<Long, ElectrochemicalCell> cells = new HashMap<>();
    /** Radioactive sources and Geiger–Müller tubes, by part id. */
    final Map<Long, dev.emergence.core.sim.elements.RadioactiveSource> sourceOfPart = new HashMap<>();
    final Map<Long, dev.emergence.core.sim.elements.GeigerTube> tubeOfPart = new HashMap<>();

    public dev.emergence.core.sim.elements.RadioactiveSource source(long partId) {
        return sourceOfPart.get(partId);
    }

    public dev.emergence.core.sim.elements.GeigerTube tube(long partId) {
        return tubeOfPart.get(partId);
    }

    /** A hand that can grip each turning body and turn it (same force–speed–power limits as on a crank). */
    final Map<Body, dev.emergence.core.sim.elements.HumanCrank> turners = new HashMap<>();

    /**
     * A person grips the part and turns the body it is fixed to about its axis (+1, −1) or lets go (0): the hand
     * pushes at the part's distance from the axis (at least 3 cm — fingers round a shaft). Returns false if the part
     * does not turn.
     */
    public boolean setTurnEffort(long partId, int direction, boolean sprint) {
        Body b = bodyOfPart.get(partId);
        var h = b == null ? null : turners.get(b);
        Part p = assembly.get(partId);
        if (h == null || p == null) return false;
        Vec3 rel = p.center().sub(b.pivot);
        double r = rel.sub(b.axis.mul(rel.dot(b.axis))).length();
        h.setRadius(Math.max(r, 0.03));
        if (h.direction() != Integer.signum(direction)) solver.markDiscontinuity();
        h.set(direction, sprint);
        return true;
    }

    /** A hand pushing each slide (same force–speed–power limits as on a crank, at 1 m "radius"). */
    final Map<Body, dev.emergence.core.sim.elements.HumanCrank> pushers = new HashMap<>();

    /** A person pushes the slide carrying this part along its axis (+1, −1) or lets go (0). Returns false if it does not slide. */
    public boolean setPushEffort(long partId, int direction) {
        Body b = bodyOfPart.get(partId);
        var h = b == null ? null : pushers.get(b);
        if (h == null) return false;
        if (h.direction() != Integer.signum(direction)) solver.markDiscontinuity();
        h.set(direction, false);
        return true;
    }

    /** Springs by part id: {stiffness [N/m]}. */
    final Map<Long, double[]> springs = new HashMap<>();
    /** Springs by part id: {tracker of end a (or null), its displacement sense along the spring axis, same for b}. */
    final Map<Long, Object[]> springEnds = new HashMap<>();

    /**
     * How far a spring's two end faces have moved along its axis from where it was placed [m]: {end a (−axis face),
     * end b (+axis face)} — for drawing it stretched or compressed between what it joins. Null if not a spring.
     */
    public double[] springEnds(long partId) {
        Object[] e = springEnds.get(partId);
        if (e == null) return null;
        double da = e[0] == null ? 0 : ((AngleTracker) e[0]).angle(solver) * (Double) e[1];
        double db = e[2] == null ? 0 : ((AngleTracker) e[2]).angle(solver) * (Double) e[3];
        return new double[]{da, db};
    }

    /**
     * A piece of a link bar: the bar, the share of it the piece draws (from, to: 0 at the bar's first pin, 1 at its
     * second) and the piece's centre [m].
     */
    record LinkPiece(dev.emergence.core.sim.elements.RigidLink link, double from, double to, Vec3 centre) {}

    /** Link pieces by part id. */
    final Map<Long, LinkPiece> links = new HashMap<>();

    /**
     * Where the share of its bar a link piece draws now runs, from its centre [m], in the frame the bar moves in (the
     * ground, or the body carrying it): {x, y, z of its start, of its end}. Null if not part of a working link.
     */
    public double[] linkPins(long partId) {
        LinkPiece l = links.get(partId);
        if (l == null) return null;
        double[] d = l.link().pins(solver), out = new double[6];
        double[] o = {l.centre().x(), l.centre().y(), l.centre().z()};
        for (int k = 0; k < 3; k++) {
            out[k] = d[k] + (d[k + 3] - d[k]) * l.from() - o[k];
            out[k + 3] = d[k] + (d[k + 3] - d[k]) * l.to() - o[k];
        }
        return out;
    }

    /** A link's tension [N] (negative: it is pushing). NaN if not part of a working link. */
    /** How far a link bar is from its built length now [m] (the drift its stabilisation is pulling back; NaN if none). */
    public double linkDrift(long partId) {
        LinkPiece l = links.get(partId);
        return l == null ? Double.NaN : l.link().length(solver) - l.link().builtLength();
    }

    public double linkTension(long partId) {
        LinkPiece l = links.get(partId);
        return l == null ? Double.NaN : l.link().tension(solver);
    }

    /** Sealed vessels of reacting gas, by part id. */
    final Map<Long, dev.emergence.core.sim.elements.GasReactor> gasOfPart = new HashMap<>();
    final Map<Long, FluidVolume> fluidOfPart = new HashMap<>();
    final Map<Long, List<FluidBranch>> valveBranches = new HashMap<>();
    /** Valves in gas-mixture networks, by part. */
    final Map<Long, List<dev.emergence.core.sim.elements.MixtureBranch>> mixtureValves = new HashMap<>();
    /** Brake pads: their full friction torques and friction elements, by part. */
    final Map<Long, List<double[]>> brakes = new HashMap<>();
    final Map<Long, List<dev.emergence.core.sim.elements.CoulombFriction>> brakeElements = new HashMap<>();
    final Map<Long, FluidBranch> rotorBranches = new HashMap<>();
    final List<Vent> vents = new ArrayList<>();
    final List<dev.emergence.core.sim.elements.Aerofoils> aerofoils = new ArrayList<>();
    final Map<Body, dev.emergence.core.sim.elements.Aerofoils> aeroOfBody = new HashMap<>();
    final Map<Long, dev.emergence.core.sim.elements.PistonCoupling> pistons = new HashMap<>();
    final Map<Long, Vec3> pistonLateral = new HashMap<>();

    /**
     * Piston state of a cylinder part: {displacement from TDC [m], crank angle from TDC [rad], throw [m],
     * rod length [m], clearance [m], and the unit direction the crank pin moves through TDC (x, y, z)}, or
     * null if the cylinder drives no crank.
     */
    public double[] piston(long partId) {
        var pc = pistons.get(partId);
        if (pc == null) return null;
        var k = pc.crank();
        Vec3 v = pistonLateral.get(partId);
        double r = k instanceof dev.emergence.core.sim.elements.SliderCrank sc ? sc.throw_() : 0;
        double rod = k instanceof dev.emergence.core.sim.elements.SliderCrank sc ? sc.rod() : 0;
        return new double[]{pc.displacement(solver), pc.crankAngle(solver), r, rod, k.clearance(), v.x(), v.y(), v.z()};
    }

    /** An open end of a fluid part into the surrounding air: cell, outward face and its branch. */
    public record Vent(long partId, int[] cell, Dir face, FluidBranch branch) {}
    /** electrode part id -> {cell, electrode, electrolyte part id} entries */
    final Map<Long, List<Object[]>> electrodes = new HashMap<>();
    final List<String> warnings = new ArrayList<>();
    /** Bevel gear pairs meshed (gears on crossing axes). */
    int bevels;
    /** Shafts coupled across scales (Assembly.Link SHAFT). */
    int shaftCouplings;

    /** Gear meshes across scales (Assembly.Link GEAR) made. */
    public int gearLinks() {
        return gearLinks;
    }

    int gearLinks;

    public int shaftCouplings() {
        return shaftCouplings;
    }

    public int bevelMeshes() {
        return bevels;
    }
    Node ambient;
    private double lag;
    private int maxStepsPerAdvance = 4000;
    private long wallBudgetNanos;

    CompiledAssembly(Assembly assembly) {
        this.assembly = assembly;
    }

    public Assembly assembly() { return assembly; }
    public Network network() { return network; }
    public TransientSolver solver() { return solver; }
    public List<Body> bodies() { return Collections.unmodifiableList(bodies); }
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
    public MagneticSystem magnetics() { return magnetics; }

    public Body bodyOf(long partId) {
        return bodyOfPart.get(partId);
    }

    public double angularVelocity(Body b) {
        return b.isStatic ? 0.0 : solver.x(b.node);
    }

    public double angle(Body b) {
        return b.isStatic ? 0.0 : b.tracker.angle(solver);
    }

    public double temperature(long partId) {
        Node n = thermalOfPart.get(partId);
        return n == null ? assembly.ambientTemperature() : solver.x(n);
    }

    public double time() {
        return solver.time();
    }

    /**
     * The charge a battery has delivered since this island started [C] (NaN for any other part): the integral of its
     * current, so that its change over a window gives the window's mean current whatever ripples within it — as an
     * integrating ammeter reads.
     */
    public double batteryChargeDelivered(long partId) {
        return deviceOfPart.get(partId) instanceof dev.emergence.core.sim.elements.Battery bt ? bt.chargeDelivered(solver) : Double.NaN;
    }

    /** Simulated time the solver fell behind wall-clock requests (step budget exhausted) [s]. */
    public double lag() {
        return lag;
    }

    /**
     * Advances the simulation by dt seconds.
     *
     * @return ids of parts destroyed during the interval (over-temperature); the caller should remove
     * them and recompile.
     */
    private StructuralAnalysis.Result structure;
    private double sinceStructure = Double.MAX_VALUE;

    /**
     * Static strength analysis of the stationary structure (recomputed at most once per simulated second, since
     * its loads — weights, held fluid — change slowly).
     */
    public StructuralAnalysis.Result structure() {
        if (structure == null) structure = StructuralAnalysis.run(this);
        return structure;
    }

    /**
     * Pressure vessels: part id → {wall stress per pascal of internal over-pressure [Pa/Pa], strength [Pa]}. Tubes
     * (pipes, valves, cylinders) by Lamé's thick-wall hoop stress σ = Δp·(r_o² + r_i²)/(r_o² − r_i²) (Barlow's Δp·r/t
     * when thin); box tanks by the bending of their flat walls, each face a plate with fixed edges under uniform
     * pressure, σ = β(a/b)·Δp·(b/t)² at the middle of its long edge (Roark, Table 11.4 case 8). Strength: the
     * material's ultimate tensile strength (its yield strength if none is given). A vessel whose wall stress reaches it
     * bursts: the part fails and what it held escapes.
     */
    private Map<Long, double[]> vessels;

    private Map<Long, double[]> vessels() {
        if (vessels != null) return vessels;
        vessels = new HashMap<>();
        for (Part p : assembly.parts()) {
            if (!fluidOfPart.containsKey(p.id()) && !gasOfPart.containsKey(p.id())) continue;
            double k = wallStressPerPascal(p);
            var m = p.material();
            double s = m.get(dev.emergence.core.materials.MaterialProperty.ULTIMATE_TENSILE_STRENGTH,
                    m.get(dev.emergence.core.materials.MaterialProperty.YIELD_STRENGTH, Double.NaN));
            if (k > 0 && Double.isFinite(s)) vessels.put(p.id(), new double[]{k, s});
        }
        return vessels;
    }

    /** Peak wall stress per pascal of over-pressure of a fluid-holding part (0 if not a vessel wall). */
    public static double wallStressPerPascal(Part p) {
        switch (p.kind()) {
            case PIPE, VALVE, CYLINDER -> {
                double ri = 0.5 * (p.kind() == PartKind.CYLINDER ? PartGeometry.cylinderBore(p) : PartGeometry.pipeBore(p));
                double t = PartGeometry.wallThickness(p) * (p.kind() == PartKind.VALVE ? 2.5 : 1);
                if (p.kind() == PartKind.CYLINDER) t = Math.max(t, 0.003);
                double ro = ri + t;
                return ri > 0 ? (ro * ro + ri * ri) / (ro * ro - ri * ri) : 0;
            }
            case TANK -> {
                double t = PartGeometry.wallThickness(p), worst = 0;
                double[] in = {p.boxSize(0) - 2 * t, p.boxSize(1) - 2 * t, p.boxSize(2) - 2 * t};
                for (int n = 0; n < 3; n++) {
                    double a = Math.max(in[(n + 1) % 3], in[(n + 2) % 3]), b = Math.min(in[(n + 1) % 3], in[(n + 2) % 3]);
                    if (b <= 0) continue;
                    worst = Math.max(worst, plateBeta(a / b) * (b / t) * (b / t));
                }
                return worst;
            }
            default -> {
                return 0;
            }
        }
    }

    /** Roark Table 11.4 case 8 (rectangular plate, all edges fixed, uniform pressure): β at the long edge's middle. */
    static double plateBeta(double aOverB) {
        double[] r = {1.0, 1.2, 1.4, 1.6, 1.8, 2.0}, beta = {0.3078, 0.3834, 0.4356, 0.4680, 0.4872, 0.4974};
        if (aOverB >= 2) return 0.4974 + (0.5 - 0.4974) * (1 - 2 / aOverB);
        int i = 0;
        while (i < r.length - 2 && aOverB > r[i + 1]) i++;
        double u = Math.max(0, (aOverB - r[i]) / (r[i + 1] - r[i]));
        return beta[i] + u * (beta[i + 1] - beta[i]);
    }

    /** Internal pressure of a fluid- or gas-holding part [Pa], or NaN. */
    public double pressure(long partId) {
        FluidVolume v = fluidOfPart.get(partId);
        if (v != null) return solver.x(v.pressureNode());
        var g = gasOfPart.get(partId);
        return g == null ? Double.NaN : g.pressure(solver);
    }

    /** A vessel's wall stress over its strength (≥ 1: it bursts), or NaN if the part is not a pressure vessel. */
    public double burstUtilisation(long partId) {
        double[] v = vessels().get(partId);
        if (v == null) return Double.NaN;
        return Math.max(0, pressure(partId) - assembly.ambientPressure()) * v[0] / v[1];
    }

    private boolean started, coilCurrentsReset, motionReset;

    /** True if the first step found the restored coil currents inconsistent with the circuit and started them at 0. */
    public boolean coilCurrentsReset() {
        return coilCurrentsReset;
    }

    /** True if the first step found the restored motion inconsistent with the build and started the bodies from rest. */
    public boolean motionReset() {
        return motionReset;
    }

    public List<Long> step(double dt) {
        if (!started) {
            started = true;
            try {
                return stepOnce(dt);
            } catch (dev.emergence.core.sim.SimulationException e) {
                if (magnetics != null && !magnetics.coils().isEmpty()) {
                    // The currents restored into the coils cannot flow in the circuit as built (its loop was broken: a
                    // chunk loaded without the rest of the circuit, a wire removed): an inductor's current then
                    // collapses. Start them from rest and keep everything else.
                    for (MagneticSystem.Coil coil : magnetics.coils()) network.setInitial(coil.currentIndex(), 0);
                    solver.reinitialize();
                    coilCurrentsReset = true;
                    try {
                        return stepOnce(dt);
                    } catch (dev.emergence.core.sim.SimulationException e2) {
                        e = e2;
                    }
                }
                // The restored motion breaks a constraint between bodies as built now (a nut saved running free of a
                // screw it now grips, gears that were out of mesh): only an impulse at t = 0 could reconcile them.
                // Start the bodies from rest where they stand.
                if (bodies.stream().allMatch(b -> b.isStatic)) throw e;
                for (Body b : bodies) if (!b.isStatic) network.setInitial(b.node.index(), 0);
                solver.reinitialize();
                motionReset = true;
                return stepOnce(dt);
            }
        }
        return stepOnce(dt);
    }

    private List<Long> stepOnce(double dt) {
        double done = solver.advance(dt, maxStepsPerAdvance, wallBudgetNanos > 0 ? System.nanoTime() + wallBudgetNanos : 0);
        lag += dt - done;
        List<Long> failed = new ArrayList<>();
        for (long id : vessels().keySet()) if (burstUtilisation(id) >= 1) failed.add(id);
        if (fatigue != null) for (long id : fatigue.accumulate(this)) if (!failed.contains(id)) failed.add(id);
        sinceStructure += done;
        if (sinceStructure >= 1.0) {
            structure = StructuralAnalysis.run(this);
            sinceStructure = 0;
            failed.addAll(structure.failed());
        }
        for (var e : failureTemp.entrySet()) {
            if (temperature(e.getKey()) > e.getValue()) failed.add(e.getKey());
        }
        // electrodes whose metal has completely dissolved are gone
        for (var en : electrodes.entrySet()) {
            for (Object[] ce : en.getValue()) {
                ElectrochemicalCell cell = (ElectrochemicalCell) ce[0];
                ElectrochemicalCell.Electrode el = (ElectrochemicalCell.Electrode) ce[1];
                if (cell.initialMetal(el) > 0 && cell.remainingMetal(el, solver) < 1e-7 && !failed.contains(en.getKey())) {
                    failed.add(en.getKey());
                }
            }
        }
        return failed;
    }

    public CompiledAssembly maxStepsPerAdvance(int n) {
        this.maxStepsPerAdvance = n;
        return this;
    }

    /**
     * Wall-clock time a {@link #step} may take [ms] (0: no limit, the default — tests are deterministic): past it the
     * step ends early on an accepted state, and the shortfall shows as {@link #lag()}.
     */
    /** Relaxes the truncation-error tolerances by {@code relax} (≥ 1; see TransientSolver#setAccuracy). */
    public void relaxAccuracy(double relax) {
        solver.setAccuracy(relax);
    }

    public CompiledAssembly wallBudget(double ms) {
        this.wallBudgetNanos = (long) (ms * 1e6);
        return this;
    }

    /** Sets the torque applied to the body carrying a crank (player effort) [N·m]. */
    public void setCrankTorque(long crankPartId, double torque) {
        Body b = bodyOfPart.get(crankPartId);
        if (b == null || b.isStatic || b.drive == null) return;
        crankTorque.put(crankPartId, torque);
        double total = 0;
        for (var e : crankTorque.entrySet()) if (bodyOfPart.get(e.getKey()) == b) total += e.getValue();
        b.drive.setValue(total);
    }

    /**
     * A person turns (or stops turning) a crank: +1 / −1 for the direction, 0 to let go. The torque
     * follows the human force–velocity–power model inside the solver.
     */
    /** Changes the wind blowing past the assembly [m/s] (its blades feel it from the next step). */
    public void setWind(dev.emergence.core.math.Vec3 wind) {
        assembly.ambientWind(wind);
        double rho = dev.emergence.core.fluids.Air.density(assembly.ambientTemperature(), assembly.ambientPressure());
        for (var af : aerofoils) af.setAir(wind, rho);
    }

    /** The air's forces on a body's blades, or null if it has none. */
    public dev.emergence.core.sim.elements.Aerofoils aerofoils(Body b) {
        return aeroOfBody.get(b);
    }

    public void setCrankEffort(long crankPartId, int direction, boolean sprint) {
        var h = cranks.get(crankPartId);
        if (h == null) return;
        if (h.direction() != Integer.signum(direction)) solver.markDiscontinuity();
        h.set(direction, sprint);
    }

    /** Applies a toggle to a switch (closes/opens the contact) or a valve (opens/shuts it); returns its new state. */
    public boolean toggleSwitch(long partId) {
        List<dev.emergence.core.sim.elements.CoulombFriction> bf = brakeElements.get(partId);
        if (bf != null) {
            Part p = assembly.get(partId);
            boolean on = p == null || !p.switchClosed();
            if (p != null) p.setSwitchClosed(on);
            List<double[]> tq = brakes.get(partId);
            for (int i = 0; i < bf.size(); i++) bf.get(i).setFrictionTorque(on ? tq.get(i)[0] : 0);
            solver.markDiscontinuity();
            return on;
        }
        List<dev.emergence.core.sim.elements.MixtureBranch> mv = mixtureValves.get(partId);
        if (mv != null) {
            Part p = assembly.get(partId);
            boolean open = p == null || !p.switchClosed();
            if (p != null) p.setSwitchClosed(open);
            for (var b : mv) b.setOpening(open ? 1 : 0);
            solver.markDiscontinuity();
            return open;
        }
        List<FluidBranch> vb = valveBranches.get(partId);
        if (vb != null) {
            Part p = assembly.get(partId);
            boolean open = p == null || !p.switchClosed();
            if (p != null) p.setSwitchClosed(open);
            for (FluidBranch b : vb) b.setOpening(open ? 1 : 0);
            solver.markDiscontinuity();
            return open;
        }
        Object d = deviceOfPart.get(partId);
        if (!(d instanceof Switch s)) return false;
        boolean c = !s.isClosed();
        s.setClosed(c);
        Part p = assembly.get(partId);
        if (p != null) p.setSwitchClosed(c);
        solver.markDiscontinuity();
        return c;
    }

    /** A photodiode's coupling to hot parts seen through optics, per wavelength band [m²] (see {@code Photodiode#BAND_EDGES}). */
    public double[] spectralCoupling(long partId) {
        var pd = receiver(partId);
        return pd != null ? pd.spectralCoupling() : null;
    }

    /** Register bits (or outputs, for chips without registers) of the chip a CHIP part belongs to, as 0..1 levels. */
    public double[] chipState(long partId) {
        if (!(deviceOfPart.get(partId) instanceof dev.emergence.core.digital.DigitalChip chip)) return null;
        return chip.component() instanceof dev.emergence.core.digital.MsiComponent m ? m.state() : null;
    }

    /** Friction torque [N·m] and power [W] a brake pad is absorbing now, or null if the part is not a brake. */
    public double[] brake(long partId) {
        List<dev.emergence.core.sim.elements.CoulombFriction> bf = brakeElements.get(partId);
        if (bf == null) return null;
        double tq = 0, pw = 0;
        for (var f : bf) {
            double[] tp = f.torqueAndPower(solver);
            tq += tp[0];
            pw += tp[1];
        }
        return new double[]{tq, pw};
    }

    /** LED part id → rays showing its beam through mirrors and lenses (points in metres). */
    final Map<Long, List<List<Vec3>>> beams = new HashMap<>();

    /** Rays showing an LED's beam where mirrors or lenses turn it (polylines, metres), or null. */
    /** Wavelength [nm] of each of a source's display rays, for sources that send several colours (hot parts); null for LEDs. */
    public double[] beamWavelengths(long partId) {
        return beamNm.get(partId);
    }

    final Map<Long, double[]> beamNm = new HashMap<>();
    /** Sound in air between the bodies on slides, if there are two or more (null otherwise). */
    dev.emergence.core.acoustics.AcousticCoupling acoustics;

    /** A sounding body: where it is [m], the direction it moves along, and its tone. */
    public record Sound(int body, Vec3 position, Vec3 axis, dev.emergence.core.acoustics.Tone tone) {
    }

    /**
     * What the bodies on slides sound like now: each one's tone over the last 0.4 s of simulated time (see
     * {@link dev.emergence.core.acoustics.Tone}), those louder than 20 dB at a metre.
     */
    public List<Sound> sounds() {
        if (acoustics == null) return List.of();
        List<Sound> out = new ArrayList<>();
        var air = acoustics.field();
        var rads = acoustics.radiators();
        for (int i = 0; i < rads.size(); i++) {
            var r = rads.get(i);
            var tone = dev.emergence.core.acoustics.Tone.of(r, time(), 0.4, air.density(), air.soundSpeed());
            if (tone != null && tone.rmsPressure() > 2e-4) out.add(new Sound(i, r.centre(), r.axis(), tone));
        }
        return out;
    }

    /** Sound pressure [Pa] at a point [m] from the bodies moving on slides, now (0 when nothing can sound). */
    public double soundPressure(Vec3 x) {
        return acoustics == null ? 0 : acoustics.field().pressure(x, time(), null);
    }

    public List<List<Vec3>> beams(long ledId) {
        return beams.get(ledId);
    }

    /** Fatigue of turning shafts in rotating bending. */
    ShaftFatigue fatigue;

    /**
     * Fatigue of a shaft cell in rotating bending: {stress amplitude [Pa], accumulated damage (Miner's sum), cycles to
     * failure at that amplitude}, or null if the part is not a bending shaft cell.
     */
    public double[] fatigue(long partId) {
        ShaftFatigue.Cell cell = fatigue == null ? null : fatigue.byPart.get(partId);
        return cell == null ? null : new double[]{cell.amplitude, cell.damage, ShaftFatigue.cyclesToFailure(cell.part.material(), cell.amplitude)};
    }

    /** Part id → index in {@link #absorbedLight}. */
    final Map<Long, Integer> lightIndex = new HashMap<>();
    /**
     * The share of the sunlight a part's cells catch that its body takes as heat, where less than all: a photodiode's
     * or phototransistor's chip, its cells' whole face in the optical scene (so that its window gathers light as it
     * should) but a small body in fact. Without it a phototransistor in the noon sun took a block face's 2.3 W and
     * cooked in two minutes.
     */
    final Map<Long, Double> sunHeatShare = new HashMap<>();
    dev.emergence.core.sim.elements.AbsorbedLight absorbedLight;
    private dev.emergence.core.optics.OpticalScene sunScene;
    private final Map<Body, Integer> sunFrames = new HashMap<>();
    private Vec3 sunTracedFor;
    private double[] sunPoses;
    private dev.emergence.core.optics.TubeTracer.Result sun;
    private long sunTraces, sunNanos;

    /**
     * Daylight: sunlight of the given irradiance [W/m²] travelling along {@code sunDirection} (unit, pointing from the
     * sun), traced as a parallel beam through the assembly ({@link dev.emergence.core.optics.TubeTracer#parallel}):
     * mirrors and lenses turn it, parts shade one another, and what each part's surface absorbs heats it; photodiodes
     * receive what lands on their junction. Parts {@code exposed} does not report as seeing the sky get none. The
     * beam is traced again when the sun has moved half a degree or a body carrying a mirror, lens or glass has.
     */
    public void setDaylight(Vec3 sunDirection, double irradiance, java.util.function.LongPredicate exposed) {
        if (!(irradiance > 0)) {
            if (absorbedLight != null) absorbedLight.clear();
            for (Object d : deviceOfPart.values()) if (d instanceof dev.emergence.core.sim.elements.Photodiode ph) ph.setAmbientPower(0);
            for (var ph : photoJunction.values()) ph.setAmbientPower(0);
            return;
        }
        var r = sunlight(sunDirection.normalize());
        if (absorbedLight != null) {
            for (var e : lightIndex.entrySet())
                absorbedLight.set(e.getValue(), exposed.test(e.getKey()) ? irradiance * r.heat(e.getKey()) * sunHeatShare.getOrDefault(e.getKey(), 1.0) : 0);
        }
        for (var e : deviceOfPart.entrySet()) {
            if (!(e.getValue() instanceof dev.emergence.core.sim.elements.Photodiode ph)) continue;
            ph.setAmbientPower(exposed.test(e.getKey()) ? irradiance * r.light(e.getKey()) : 0);
        }
        for (var e : photoJunction.entrySet()) e.getValue().setAmbientPower(exposed.test(e.getKey()) ? irradiance * r.light(e.getKey()) : 0);
    }

    /** Sunlight absorbed in a part [W] (as last set by {@link #setDaylight}). */
    public double sunlightAbsorbed(long partId) {
        Integer i = lightIndex.get(partId);
        return i == null || absorbedLight == null ? 0 : absorbedLight.power(i);
    }

    /** Sunlight absorbed in the whole assembly [W] (as last set). */
    public double sunlightAbsorbed() {
        double s = 0;
        if (absorbedLight != null) for (int i : lightIndex.values()) s += absorbedLight.power(i);
        return s;
    }

    /** Sun traces so far and their total time [ms]. */
    public double[] sunTraceCost() {
        return new double[]{sunTraces, sunNanos / 1e6};
    }

    private dev.emergence.core.optics.TubeTracer.Result sunlight(Vec3 dir) {
        if (sunScene == null) {
            sunScene = AssemblyOptics.scene(assembly, this, AssemblyOptics.SUN_NM, this::angle, sunFrames);
        }
        boolean moved = sunTracedFor == null || sunTracedFor.dot(dir) < Math.cos(Math.toRadians(0.5));
        double[] poses = new double[sunScene.frameCount()];
        for (var e : sunFrames.entrySet()) {
            poses[e.getValue()] = angle(e.getKey());
            if (sunPoses != null && !moved) {
                boolean optical = false;
                for (long id : e.getKey().parts) optical |= AssemblyOptics.redirects(assembly.get(id));
                double tol = e.getKey().slider ? 0.002 : Math.toRadians(0.5);
                if (optical && Math.abs(poses[e.getValue()] - sunPoses[e.getValue()]) > tol) moved = true;
            }
        }
        if (moved) {
            long t0 = System.nanoTime();
            for (var e : sunFrames.entrySet()) sunScene.setPose(e.getValue(), poses[e.getValue()]);
            sun = dev.emergence.core.optics.TubeTracer.parallel(sunScene, dir, AssemblyOptics.SUN_NM, 0.5 * Part.CELL, 3, true);
            sunTracedFor = dir;
            sunPoses = poses;
            sunTraces++;
            sunNanos += System.nanoTime() - t0;
        }
        return sun;
    }

    /** Light arriving at a photodiode [W], or 0. */
    public double lightInput(long partId) {
        var ph = receiver(partId);
        return ph != null ? ph.incidentPower(solver) : 0;
    }

    /** Light emitted by a part (an LED) [W], 0 otherwise. */
    public double lightOutput(long partId) {
        return deviceOfPart.get(partId) instanceof dev.emergence.core.sim.elements.Led led ? led.opticalPower(solver) : 0;
    }

    /**
     * Fluid state held by a part: {pressure [Pa], fluid temperature [K], mass [kg], mean |mass flow| [kg/s],
     * vapour volume fraction}, or null.
     */
    public double[] fluid(long partId) {
        FluidVolume v = fluidOfPart.get(partId);
        if (v == null) return null;
        double flow = 0;
        int n = 0;
        for (FluidBranch b : branchesOf(partId)) {
            flow += Math.abs(b.massFlow(solver));
            n++;
        }
        return new double[]{solver.x(v.pressureNode()), solver.x(v.temperatureNode()), v.mass(solver), n == 0 ? 0 : flow / n,
                v.vapourFraction(solver)};
    }

    /** Name of the fluid in a part, or null. */
    public String fluidName(long partId) {
        FluidVolume v = fluidOfPart.get(partId);
        return v == null ? null : v.fluid().name();
    }

    public List<FluidBranch> branchesOf(long partId) {
        List<FluidBranch> out = new ArrayList<>();
        for (dev.emergence.core.sim.Element e : network.elements()) {
            if (e instanceof FluidBranch b && b.touches(fluidOfPart.get(partId))) out.add(b);
        }
        return out;
    }

    /** Open ends of fluid parts to the surrounding air, with their current outflow [kg/s] (negative = intake). */
    public List<Vent> vents() {
        return Collections.unmodifiableList(vents);
    }

    public double ventOutflow(Vent v) {
        return v.branch().massFlow(solver);
    }

    /** Chemical readouts: concentrations in an electrolyte; dissolved metal, deposits, current and gas at an electrode. */
    public Map<String, Double> chemistry(long partId) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        var src = sourceOfPart.get(partId);
        if (src != null) {
            out.put("Activity kBq", src.activity(solver) / 1e3);
            for (int i = 0; i < src.chain().size(); i++) out.put(src.chain().get(i).name() + " kBq", src.activity(solver, i) / 1e3);
        }
        var tube = tubeOfPart.get(partId);
        if (tube != null) {
            out.put("Counts", (double) tube.discharges());
            out.put("Expected rate counts/s", tube.hitRate(solver));
            out.put("Lost to dead time", (double) tube.lost());
        }
        var gas = gasOfPart.get(partId);
        if (gas != null) {
            out.put("Gas pressure bar", gas.pressure(solver) / 1e5);
            out.put("Gas temperature °C", gas.temperature(solver) - 273.15);
            var sp = gas.species();
            double liq = 0;
            for (int i = 0; i < sp.size(); i++) {
                double xf = gas.moleFraction(solver, i);
                if (xf > 1e-6) out.put(sp.get(i).id() + " %", 100 * xf);
                liq += Math.max(0, gas.liquidMoles(solver, i));
            }
            if (liq > 1e-6) {
                for (int i = 0; i < sp.size(); i++) {
                    double l = gas.liquidMoles(solver, i);
                    if (l > 1e-9) out.put("Liquid " + sp.get(i).id() + " mol %", 100 * l / liq);
                }
                out.put("Liquid mol", liq);
            }
        }
        ElectrochemicalCell cell = cells.get(partId);
        if (cell != null) {
            for (var sp : cell.allMoles(solver).entrySet()) {
                double c = cell.concentration(sp.getKey(), solver);
                if (c > 1e-6) out.put("[" + sp.getKey() + "] mol/L", c);
            }
        }
        for (Object[] ce : electrodes.getOrDefault(partId, List.of())) {
            ElectrochemicalCell cl = (ElectrochemicalCell) ce[0];
            ElectrochemicalCell.Electrode el = (ElectrochemicalCell.Electrode) ce[1];
            double m = cl.remainingMetal(el, solver);
            if (!Double.isNaN(m)) out.merge("metal dissolved mol", cl.initialMetal(el) - m, Double::sum);
            for (var d : cl.deposits(el, solver).entrySet()) out.merge(d.getKey() + " deposited mol", d.getValue(), Double::sum);
            out.merge("interface current A", cl.electrodeCurrent(el, solver), Double::sum);
            out.merge("gas evolution mol/s", cl.gasEvolution(el, solver), Double::sum);
        }
        return out;
    }

    /**
     * Visible colour of a part as changed by chemistry (0xRRGGBB), or −1 if unchanged: an electrolyte's
     * colour follows its ion concentrations (Beer–Lambert); an electrode takes on the colour of metal
     * plated onto it as the film builds up (fully covered at ~1e-4 mol/m², a sub-micrometre film).
     */
    public int chemicalColour(long partId) {
        var gas = gasOfPart.get(partId);
        if (gas != null) return gasColour(gas, assembly.get(partId));
        ElectrochemicalCell cell = cells.get(partId);
        if (cell != null) {
            Map<String, Double> conc = new java.util.HashMap<>();
            for (String sp : cell.allMoles(solver).keySet()) conc.put(sp, cell.concentration(sp, solver));
            return dev.emergence.core.chem.Electrochemistry.standard().solutionColour(conc);
        }
        List<Object[]> els = electrodes.get(partId);
        if (els == null) return -1;
        Part p = assembly.get(partId);
        if (p == null) return -1;
        double r = 0, g = 0, b = 0, total = 0;
        for (Object[] ce : els) {
            ElectrochemicalCell cl = (ElectrochemicalCell) ce[0];
            for (var d : cl.deposits((ElectrochemicalCell.Electrode) ce[1], solver).entrySet()) {
                var m = dev.emergence.core.materials.MaterialDatabase.standard().getOrNull(d.getKey());
                if (m == null || d.getValue() <= 0) continue;
                r += ((m.color() >> 16) & 255) * d.getValue();
                g += ((m.color() >> 8) & 255) * d.getValue();
                b += (m.color() & 255) * d.getValue();
                total += d.getValue();
            }
        }
        if (total <= 0) return -1;
        double area = Part.CELL * Part.CELL;
        double f = 1 - Math.exp(-total / (area * 1e-4));
        int base = p.material().color();
        int rr = (int) (((base >> 16) & 255) * (1 - f) + r / total * f);
        int gg = (int) (((base >> 8) & 255) * (1 - f) + g / total * f);
        int bb = (int) ((base & 255) * (1 - f) + b / total * f);
        return (rr << 16) | (gg << 8) | bb;
    }

    /** Moles of foreign metal plated onto an electrode part (for rendering its colour). */
    public double depositMoles(long partId) {
        double mol = 0;
        for (Object[] ce : electrodes.getOrDefault(partId, List.of())) {
            ElectrochemicalCell cl = (ElectrochemicalCell) ce[0];
            for (double d : cl.deposits((ElectrochemicalCell.Electrode) ce[1], solver).values()) mol += d;
        }
        return mol;
    }

    /** Gas evolution rate at an electrode part [mol/s]. */
    public double gasEvolution(long partId) {
        double r = 0;
        for (Object[] ce : electrodes.getOrDefault(partId, List.of())) {
            r += ((ElectrochemicalCell) ce[0]).gasEvolution((ElectrochemicalCell.Electrode) ce[1], solver);
        }
        return r;
    }

    public Probe probe(long partId) {
        Part p = assembly.get(partId);
        if (p == null) return null;
        Body b = bodyOfPart.get(partId);
        double w = b == null ? 0 : angularVelocity(b), th = b == null ? 0 : angle(b);
        double temp = temperature(partId);
        double i = Double.NaN, v = Double.NaN, pw = Double.NaN;
        Node[] leads = leadsOfPart.get(partId);
        if (leads != null && leads.length == 2) v = solver.x(leads[1]) - solver.x(leads[0]);
        else if (leads != null && leads.length == 1) v = solver.x(leads[0]);
        Object d = deviceOfPart.get(partId);
        if (d instanceof Resistor r) {
            i = -r.current(solver); // current flows neg→pos lead positive
            pw = r.power(solver);
        } else if (d instanceof Switch s) {
            i = -s.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof Diode dd) {
            i = dd.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof Capacitor c) {
            i = c.current(solver);
            pw = 0;
        } else if (d instanceof dev.emergence.core.sim.elements.Battery bt) {
            i = bt.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof dev.emergence.core.sim.elements.Led led) {
            i = led.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof dev.emergence.core.sim.elements.Bjt q) {
            i = q.collectorCurrent(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof dev.emergence.core.sim.elements.Thermistor nt) {
            i = -nt.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof dev.emergence.core.sim.elements.LogicGate lg) {
            i = lg.state(solver);
            pw = 0;
        } else if (d instanceof dev.emergence.core.sim.elements.Photodiode ph) {
            i = ph.current(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof dev.emergence.core.sim.elements.Potentiometer pot) {
            i = pot.wiper(solver); // the wiper's place on the track (0 at A, 1 at B)
            pw = 0;
        } else if (d instanceof dev.emergence.core.sim.elements.OpAmp) {
            i = 0;
            pw = 0;
        } else if (d instanceof dev.emergence.core.sim.elements.MsiChip) {
            i = 0;
            pw = 0;
        } else if (d instanceof dev.emergence.core.sim.elements.Mosfet m) {
            i = m.drainCurrent(solver);
            pw = Math.abs(i * v);
        } else if (d instanceof MagneticSystem.Coil coil) {
            i = magnetics.current(coil, solver);
            pw = coil.resistance(temp) * i * i;
        }
        return new Probe(partId, p.kind(), temp, i, v, pw, w, th, b == null ? 0 : b.index);
    }

    /** The quantities a data logger records from a part (NaN where the part has no such quantity). */
    public static final java.util.List<dev.emergence.core.instruments.DataLogger.Channel> LOGGER_CHANNELS = java.util.List.of(
            new dev.emergence.core.instruments.DataLogger.Channel("temperature", "K"),
            new dev.emergence.core.instruments.DataLogger.Channel("current", "A"),
            new dev.emergence.core.instruments.DataLogger.Channel("voltage", "V"),
            new dev.emergence.core.instruments.DataLogger.Channel("power", "W"),
            new dev.emergence.core.instruments.DataLogger.Channel("angular velocity", "rad/s"),
            new dev.emergence.core.instruments.DataLogger.Channel("angle", "rad"),
            new dev.emergence.core.instruments.DataLogger.Channel("pressure", "Pa"),
            new dev.emergence.core.instruments.DataLogger.Channel("fluid temperature", "K"),
            new dev.emergence.core.instruments.DataLogger.Channel("mass flow", "kg/s"));

    private final Map<dev.emergence.core.instruments.DataLogger, java.util.function.Consumer<TransientSolver>> loggers = new HashMap<>();

    /** A logger for {@link #LOGGER_CHANNELS} sampling every {@code interval} seconds, holding {@code capacity} samples. */
    public static dev.emergence.core.instruments.DataLogger newLogger(double interval, int capacity) {
        return new dev.emergence.core.instruments.DataLogger(LOGGER_CHANNELS, interval, capacity);
    }

    private final Map<dev.emergence.core.instruments.LogicCapture, Runnable> captures = new HashMap<>();

    /**
     * Connects a logic analyser's probes: channel k to part {@code probes[k]} (−1: none), voltages against part
     * {@code ground}'s (−1: the circuit's reference). A probe on a digital net records its level as it changes, at
     * the time it changes; on an analog node, its voltage after every accepted solver step.
     */
    public void attachCapture(dev.emergence.core.instruments.LogicCapture cap, long ground, long[] probes) {
        detachCapture(cap);
        List<Runnable> undo = new ArrayList<>();
        Node ref = ground >= 0 && leadsOfPart.get(ground) != null ? leadsOfPart.get(ground)[0] : null;
        List<Node> analog = new ArrayList<>();
        List<Integer> analogCh = new ArrayList<>();
        for (int k = 0; k < probes.length && k < dev.emergence.core.instruments.LogicCapture.CHANNELS; k++) {
            if (probes[k] < 0 || assembly.get(probes[k]) == null) {
                cap.unuse(k);
                continue;
            }
            var net = digitalNetOfPart.get(probes[k]);
            if (net != null) {
                cap.configure(k, true);
                int ch = k;
                cap.record(ch, solver.time(), net.level() ? 1 : 0);
                java.util.function.ObjDoubleConsumer<Boolean> w = (level, time) -> cap.record(ch, time, level ? 1 : 0);
                net.watch(w);
                undo.add(() -> net.unwatch(w));
                continue;
            }
            Node[] l = leadsOfPart.get(probes[k]);
            if (l == null || l.length == 0 || l[0].index() < 0) {
                cap.unuse(k);
                continue;
            }
            cap.configure(k, false);
            analog.add(l[0]);
            analogCh.add(k);
        }
        if (!analog.isEmpty()) {
            java.util.function.Consumer<TransientSolver> sl = s -> {
                double r = ref != null && ref.index() >= 0 ? s.x(ref.index()) : 0;
                for (int i = 0; i < analog.size(); i++) cap.record(analogCh.get(i), s.time(), s.x(analog.get(i).index()) - r);
            };
            sl.accept(solver);
            solver.addStepListener(sl);
            undo.add(() -> solver.removeStepListener(sl));
        }
        captures.put(cap, () -> undo.forEach(Runnable::run));
    }

    public void detachCapture(dev.emergence.core.instruments.LogicCapture cap) {
        Runnable u = captures.remove(cap);
        if (u != null) u.run();
    }

    /** Connects a data logger to a part: it is fed the part's quantities after every accepted solver step. */
    public void attachLogger(dev.emergence.core.instruments.DataLogger log, long partId) {
        detachLogger(log);
        if (assembly.get(partId) == null) return;
        java.util.function.Consumer<TransientSolver> l = s -> log.offer(s.time(), loggerSample(partId));
        log.rebase(solver.time());
        log.offer(solver.time(), loggerSample(partId));
        solver.addStepListener(l);
        loggers.put(log, l);
    }

    public void detachLogger(dev.emergence.core.instruments.DataLogger log) {
        var l = loggers.remove(log);
        if (l != null) solver.removeStepListener(l);
    }

    /** One reading of {@link #LOGGER_CHANNELS} from a part. */
    public double[] loggerSample(long partId) {
        Probe p = probe(partId);
        double[] f = fluid(partId);
        var gas = gasOfPart.get(partId);
        if (f == null && gas != null) f = new double[]{gas.pressure(solver), gas.temperature(solver), gas.mass(solver), Double.NaN};
        double nan = Double.NaN;
        if (p == null) return new double[]{nan, nan, nan, nan, nan, nan, nan, nan, nan};
        boolean moving = p.bodyIndex() > 0;
        return new double[]{p.temperature(), p.current(), p.voltage(), p.power(), moving ? p.angularVelocity() : nan,
                moving ? p.angle() : nan, f == null ? nan : f[0], f == null ? nan : f[1], f == null ? nan : f[3]};
    }

    /** The reacting gas held in a sealed vessel, or null. */
    public dev.emergence.core.sim.elements.GasReactor gas(long partId) {
        return gasOfPart.get(partId);
    }

    public TransientSolver solverView() {
        return solver;
    }

    /** A potentiometer's wiper position as a fraction of its track (0 at end A, 1 at end B), or NaN. */
    public double wiper(long partId) {
        return deviceOfPart.get(partId) instanceof dev.emergence.core.sim.elements.Potentiometer pot ? pot.wiper(solver) : Double.NaN;
    }

    /**
     * Colour of a gas seen through its vessel (glass): Beer–Lambert transmission e^{−σ N_A c L} per channel for
     * the coloured species, over the vessel's width. NO₂ absorbs strongly in the blue (σ ≈ 5·10⁻²³ m² at 465 nm,
     * 2.2·10⁻²³ at 545 nm, 0.7·10⁻²³ at 610 nm; Vandaele et al. 1998), so it looks yellow-brown to red-brown;
     * the other species are colourless. Returns −1 for a colourless gas.
     */
    private int gasColour(dev.emergence.core.sim.elements.GasReactor gas, Part p) {
        double[] sigma = {0.7e-23, 2.2e-23, 5.0e-23};
        double na = dev.emergence.core.units.PhysicalConstants.AVOGADRO;
        double len = p == null ? 0.05 : PartGeometry.transverseSize(p);
        double c = 0;
        var sp = gas.species();
        for (int i = 0; i < sp.size(); i++) if (sp.get(i).id().equals("NO2")) c = Math.max(0, gas.moles(solver, i)) / gas.volume();
        if (c * len < 1e-9) return -1;
        int rgb = 0;
        for (int k = 0; k < 3; k++) {
            double t = Math.exp(-sigma[k] * na * c * len);
            rgb = (rgb << 8) | (int) Math.round(235 * t + 10);
        }
        return rgb;
    }

    /** Captures the dynamic state keyed by part id. */
    public AssemblyState exportState() {
        AssemblyState s = AssemblyState.empty();
        for (var e : thermalOfPart.entrySet()) s.temperatures().put(e.getKey(), solver.x(e.getValue()));
        for (Body b : bodies) {
            if (b.isStatic) continue;
            double[] m = {angularVelocity(b), angle(b)};
            for (long id : b.parts) s.bodyMotion().put(id, m);
        }
        for (var e : deviceOfPart.entrySet()) {
            if (e.getValue() instanceof MagneticSystem.Coil c) s.coilCurrents().put(e.getKey(), magnetics.current(c, solver));
            if (e.getValue() instanceof Capacitor c) s.capacitorVoltages().put(e.getKey(), c.voltage(solver));
            // a battery's state of charge travels with the capacitors' voltages
            if (e.getValue() instanceof dev.emergence.core.sim.elements.Battery bt) s.capacitorVoltages().put(e.getKey(), bt.stateOfCharge(solver));
        }
        for (var e : fluidOfPart.entrySet()) {
            FluidVolume v = e.getValue();
            s.fluids().put(e.getKey(), v.twoPhase()
                    ? new double[]{solver.x(v.pressureNode()), solver.x(v.temperatureNode()), v.phase(solver)}
                    : new double[]{solver.x(v.pressureNode()), solver.x(v.temperatureNode())});
        }
        for (var e : sourceOfPart.entrySet()) {
            var src = e.getValue();
            for (int i = 0; i < src.chain().size(); i++) s.chemistry().put("N" + e.getKey() + ":" + src.chain().get(i).id(), src.atoms(solver, i));
        }
        for (var e : gasOfPart.entrySet()) {
            var g = e.getValue();
            s.fluids().put(e.getKey(), new double[]{g.pressure(solver), g.temperature(solver)});
            var sp = g.species();
            for (int i = 0; i < sp.size(); i++) {
                s.chemistry().put("G" + e.getKey() + ":" + sp.get(i).id(), g.moles(solver, i));
                if (g.liquidIndex(i) >= 0) s.chemistry().put("L" + e.getKey() + ":" + sp.get(i).id(), g.liquidMoles(solver, i));
            }
        }
        for (var e : cells.entrySet()) {
            for (var sp : e.getValue().allMoles(solver).entrySet()) {
                s.chemistry().put(AssemblyState.speciesKey(e.getKey(), sp.getKey()), sp.getValue());
            }
        }
        for (var en : electrodes.entrySet()) {
            for (Object[] ce : en.getValue()) {
                ElectrochemicalCell cell = (ElectrochemicalCell) ce[0];
                ElectrochemicalCell.Electrode el = (ElectrochemicalCell.Electrode) ce[1];
                double m = cell.remainingMetal(el, solver);
                if (!Double.isNaN(m)) s.chemistry().put("M" + en.getKey() + ":" + ce[2], m);
                for (var d : cell.deposits(el, solver).entrySet()) {
                    s.chemistry().merge("D" + en.getKey() + ":" + d.getKey(), d.getValue(), Double::sum);
                }
            }
        }
        if (fatigue != null) for (var e : fatigue.byPart.entrySet()) if (e.getValue().damage > 0) s.damage().put(e.getKey(), e.getValue().damage);
        // event-driven chips' state (registers, memories, a display's image), each word as two 32-bit halves (doubles
        // hold them exactly)
        for (var e : digitalChips.entrySet()) {
            long[] st = e.getValue().component().save();
            if (st.length == 0) continue;
            s.chemistry().put("R" + e.getKey() + ":n", (double) st.length);
            for (int i = 0; i < st.length; i++) {
                s.chemistry().put("R" + e.getKey() + ":w" + (2 * i), (double) (st[i] & 0xffffffffL));
                s.chemistry().put("R" + e.getKey() + ":w" + (2 * i + 1), (double) (st[i] >>> 32));
            }
        }
        // the flows in the passages (a restart that has to find every flow at once, from none, can fail to)
        for (int i = 0; i < solver.unknowns(); i++) {
            String key = AssemblyState.flowKey(network.unknownName(i));
            if (key != null && Double.isFinite(solver.x(i))) s.chemistry().put(key, solver.x(i));
        }
        return s;
    }

    /** Total energy currently stored in the model (kinetic, field, thermal relative to 0 K) [J]. */
    public double storedEnergy() {
        return solver.storedEnergy();
    }

    public double energyError() {
        return solver.energyError();
    }

    public Set<Long> partIds() {
        return thermalOfPart.keySet();
    }

    /** Recompiles with the same parts, preserving state (used after structural edits). */
    public static CompiledAssembly recompile(Assembly a, CompiledAssembly old) {
        AssemblyState st = old == null ? AssemblyState.empty() : old.exportState();
        try {
            return AssemblyCompiler.compile(a, st);
        } catch (SimulationException e) {
            return AssemblyCompiler.compile(a, AssemblyState.empty());
        }
    }
}
