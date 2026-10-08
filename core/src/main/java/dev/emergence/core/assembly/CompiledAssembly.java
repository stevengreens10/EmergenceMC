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

    /** Applies a toggle to a switch part; returns its new state. */
    public boolean toggleSwitch(long partId) {
        Object d = deviceOfPart.get(partId);
        if (!(d instanceof Switch s)) return false;
        boolean c = !s.isClosed();
        s.setClosed(c);
        Part p = assembly.get(partId);
        if (p != null) p.setSwitchClosed(c);
        solver.markDiscontinuity();
        return c;
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
