package dev.emergence.core.em;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.*;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Emergence tests: generator and motor behaviour arising purely from magnets, coils, a shaft and
 * circuit elements — no generator or motor model exists anywhere in the code.
 */
class ElectromechanicalEmergenceTest {
    static final Material COPPER = MaterialDatabase.standard().get("copper");
    static final double BR = MaterialDatabase.standard().get("ndfeb_n42")
            .get(dev.emergence.core.materials.MaterialProperty.REMANENCE);
    static final double R0 = 0.03;

    /** Axial-flux rig: two NdFeB blocks (N up / S up) on a disk rotor, coils above on a fixed frame. */
    static final class Rig {
        final Network net = new Network();
        final Node gnd = net.reference(Domain.ELECTRICAL);
        final Node ref = net.reference(Domain.ROTATIONAL);
        final Node w;
        final AngleTracker angle;
        final EmBody rotor, stator = EmBody.fixed("stator");
        final MagneticSystem mag = new MagneticSystem().tableResolution(128);

        Rig(double inertia, double w0) {
            w = net.node(Domain.ROTATIONAL, "shaft", w0);
            net.add(new Inertia(w, inertia));
            angle = net.add(new AngleTracker(w, 0));
            rotor = EmBody.rotor("rotor", Vec3.ZERO, Vec3.Z, w, angle);
            mag.addMagnet(CuboidMagnet.axisAligned(new Vec3(R0, 0, 0), 0.012, 0.012, 0.005, Vec3.Z, BR), rotor);
            mag.addMagnet(CuboidMagnet.axisAligned(new Vec3(-R0, 0, 0), 0.012, 0.012, 0.005, Vec3.Z.mul(-1), BR), rotor);
        }

        MagneticSystem.Coil coilAt(double angleRad, Node a, Node b) {
            Vec3 c = new Vec3(R0 * Math.cos(angleRad), R0 * Math.sin(angleRad), 0.0025 + 0.001 + 0.002);
            return mag.addCoil("coil", new CoilGeometry(c, Vec3.Z, 0.006, 0.004, 0.003, 200), stator, a, b, COPPER, 0.3e-3, null);
        }
    }

    @Test
    void spinningMagnetsInduceEmfProportionalToSpeed() {
        double[] peak = new double[2];
        double[] speeds = {50, 100};
        for (int r = 0; r < 2; r++) {
            Rig rig = new Rig(1e3, speeds[r]); // huge inertia → constant speed
            Node a = rig.net.node(Domain.ELECTRICAL, "a");
            rig.coilAt(0, a, rig.gnd);
            rig.net.add(new Resistor(a, rig.gnd, 1e7)); // voltmeter-like load
            rig.net.add(rig.mag);
            TransientSolver s = new TransientSolver(rig.net).maxStep(2e-4);
            double tRev = 2 * Math.PI / speeds[r];
            double max = 0;
            while (s.time() < 1.5 * tRev) {
                s.advance(tRev / 400);
                max = Math.max(max, Math.abs(s.x(a)));
            }
            peak[r] = max;
        }
        assertTrue(peak[0] > 0.05, "EMF should be substantial, got " + peak[0]);
        // Faraday: EMF ∝ dΦ/dt ∝ ω
        assertEquals(2.0, peak[1] / peak[0], 0.02);
    }

    @Test
    void loadedGeneratorBrakesRotorAndConservesEnergy() {
        double w0 = 150, J = 2e-4;
        // open circuit: no electromagnetic drag (air-core, no iron → no cogging)
        Rig open = new Rig(J, w0);
        Node ao = open.net.node(Domain.ELECTRICAL, "a");
        open.coilAt(0, ao, open.gnd);
        open.net.add(new Resistor(ao, open.gnd, 1e9));
        open.net.add(open.mag);
        TransientSolver so = new TransientSolver(open.net).maxStep(2e-4);
        so.advance(0.5);
        assertEquals(w0, so.x(open.w), 0.01 * w0, "open circuit should barely slow");

        // loaded: 4 coils in series around the rotor into a 10 Ω load with thermal masses
        Rig gen = new Rig(J, w0);
        Node prev = gen.gnd;
        for (int k = 0; k < 4; k++) {
            Node next = gen.net.node(Domain.ELECTRICAL, "c" + k);
            // reverse the coils at 180° and 270°: they face the opposite magnet pole at the same
            // time as their 0°/90° partners, so reversed connection makes their EMFs add
            if (k < 2) gen.coilAt(k * Math.PI / 2, next, prev);
            else gen.coilAt(k * Math.PI / 2, prev, next);
            prev = next;
        }
        Node th = gen.net.node(Domain.THERMAL, "load", 293.15);
        gen.net.add(new HeatCapacity(th, 10.0));
        Resistor load = gen.net.add(new Resistor(prev, gen.gnd, 10, th));
        gen.net.add(gen.mag);
        TransientSolver sg = new TransientSolver(gen.net).maxStep(2e-4);
        double e0 = sg.storedEnergy();
        sg.advance(0.5);
        double w1 = sg.x(gen.w);
        double openLoss = w0 - so.x(open.w), loadedLoss = w0 - w1;
        assertTrue(loadedLoss > 0.02 * w0, "loaded generator should brake the rotor (Lenz), w=" + w1);
        assertTrue(loadedLoss > 100 * Math.max(openLoss, 1e-9), "braking must come from the electrical load");
        assertTrue(sg.x(th) > 293.15, "load resistor heated");
        // Conservation: kinetic energy lost = heat in load + coil copper loss (untracked) ± field energy
        double err = sg.energyError();
        double transferred = 0.5 * J * (w0 * w0 - w1 * w1);
        assertTrue(Math.abs(err) < 0.01 * transferred, "energy error " + err + " J of " + transferred + " J");
    }

    @Test
    void rotatingFieldDrivesMagnetRotorSynchronously() {
        Rig rig = new Rig(2e-5, 0);
        Node a = rig.net.node(Domain.ELECTRICAL, "a");
        Node b = rig.net.node(Domain.ELECTRICAL, "b");
        rig.coilAt(0, a, rig.gnd);
        rig.coilAt(Math.PI / 2, b, rig.gnd);
        rig.net.add(new Damper(rig.w, rig.ref, 2e-5, null));
        double fFinal = 3.0, tRamp = 3.0, amp = 1.5;
        java.util.function.DoubleUnaryOperator phase = t -> t < tRamp
                ? Math.PI * fFinal * t * t / tRamp
                : Math.PI * fFinal * tRamp + 2 * Math.PI * fFinal * (t - tRamp);
        rig.net.add(new FlowSource(rig.gnd, a, t -> amp * Math.cos(phase.applyAsDouble(t))));
        rig.net.add(new FlowSource(rig.gnd, b, t -> amp * Math.sin(phase.applyAsDouble(t))));
        rig.net.add(rig.mag);
        TransientSolver s = new TransientSolver(rig.net).maxStep(1e-3);
        s.advance(tRamp + 1.0);
        double th0 = rig.angle.angle(s);
        s.advance(1.0);
        double avgSpeed = (rig.angle.angle(s) - th0) / 1.0;
        assertEquals(2 * Math.PI * fFinal, avgSpeed, 0.02 * 2 * Math.PI * fFinal,
                "rotor should lock to the rotating field");
    }
}
