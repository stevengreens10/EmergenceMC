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
        public double mass() { return mass; }
    }

    final Assembly assembly;
    final Network network = new Network();
    TransientSolver solver;
    final List<Body> bodies = new ArrayList<>();
    final Map<Long, Body> bodyOfPart = new HashMap<>();
    final Map<Long, Node> thermalOfPart = new HashMap<>();
    final Map<Long, Node[]> leadsOfPart = new HashMap<>();   // {neg, pos} or {node}
    final Map<Long, Object> deviceOfPart = new HashMap<>(); // Resistor, Diode, Switch, Capacitor, MagneticSystem.Coil
    final Map<Long, Double> failureTemp = new HashMap<>();
    final Map<Long, Double> crankTorque = new HashMap<>();
    final Map<Long, dev.emergence.core.sim.elements.HumanCrank> cranks = new HashMap<>();
    MagneticSystem magnetics;
    final Map<Long, ElectrochemicalCell> cells = new HashMap<>();
    final Map<Long, FluidVolume> fluidOfPart = new HashMap<>();
    final Map<Long, List<FluidBranch>> valveBranches = new HashMap<>();
    final Map<Long, FluidBranch> rotorBranches = new HashMap<>();
    final List<Vent> vents = new ArrayList<>();

    /** An open end of a fluid part into the surrounding air: cell, outward face and its branch. */
    public record Vent(long partId, int[] cell, Dir face, FluidBranch branch) {}
    /** electrode part id -> {cell, electrode, electrolyte part id} entries */
    final Map<Long, List<Object[]>> electrodes = new HashMap<>();
    final List<String> warnings = new ArrayList<>();
    Node ambient;
    private double lag;
    private int maxStepsPerAdvance = 4000;

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
    public List<Long> step(double dt) {
        double done = solver.advance(dt, maxStepsPerAdvance);
        lag += dt - done;
        List<Long> failed = new ArrayList<>();
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
    public void setCrankEffort(long crankPartId, int direction, boolean sprint) {
        var h = cranks.get(crankPartId);
        if (h == null) return;
        if (h.direction() != Integer.signum(direction)) solver.markDiscontinuity();
        h.set(direction, sprint);
    }

    /** Applies a toggle to a switch (closes/opens the contact) or a valve (opens/shuts it); returns its new state. */
    public boolean toggleSwitch(long partId) {
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

    /** Fluid state held by a part: {pressure [Pa], fluid temperature [K], mass [kg], mean |mass flow| [kg/s]}, or null. */
    public double[] fluid(long partId) {
        FluidVolume v = fluidOfPart.get(partId);
        if (v == null) return null;
        double flow = 0;
        int n = 0;
        for (FluidBranch b : branchesOf(partId)) {
            flow += Math.abs(b.massFlow(solver));
            n++;
        }
        return new double[]{solver.x(v.pressureNode()), solver.x(v.temperatureNode()), v.mass(solver), n == 0 ? 0 : flow / n};
    }

    /** Name of the fluid in a part, or null. */
    public String fluidName(long partId) {
        FluidVolume v = fluidOfPart.get(partId);
        return v == null ? null : v.fluid().name();
    }

    private List<FluidBranch> branchesOf(long partId) {
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
        } else if (d instanceof MagneticSystem.Coil coil) {
            i = magnetics.current(coil, solver);
            pw = coil.resistance(temp) * i * i;
        }
        return new Probe(partId, p.kind(), temp, i, v, pw, w, th, b == null ? 0 : b.index);
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
        }
        for (var e : fluidOfPart.entrySet()) {
            FluidVolume v = e.getValue();
            s.fluids().put(e.getKey(), new double[]{solver.x(v.pressureNode()), solver.x(v.temperatureNode())});
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
