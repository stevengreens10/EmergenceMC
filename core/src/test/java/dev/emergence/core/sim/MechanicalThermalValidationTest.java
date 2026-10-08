package dev.emergence.core.sim;

import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical validation of rotational mechanics, thermal networks and cross-domain energy closure. */
class MechanicalThermalValidationTest {

    @Test
    void constantTorqueAcceleratesInertiaLinearly() {
        double J = 0.05, tau = 2.0;
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "shaft");
        n.add(new Inertia(w, J));
        n.add(new FlowSource(ref, w, tau));
        AngleTracker ang = n.add(new AngleTracker(w, 0));
        TransientSolver s = new TransientSolver(n);
        s.advance(3.0);
        assertEquals(tau / J * 3.0, s.x(w), 1e-6);
        assertEquals(0.5 * tau / J * 9.0, ang.angle(s), 1e-3);
        // work-energy theorem
        assertEquals(s.externalEnergyIn(), s.storedEnergy(), 1e-6 * s.storedEnergy());
    }

    @Test
    void flywheelViscousSpinDownAndHeatClosure() {
        double J = 0.2, c = 0.01, w0 = 100;
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "flywheel", w0);
        Node th = n.node(Domain.THERMAL, "bearing", 300);
        n.add(new Inertia(w, J));
        n.add(new Damper(w, ref, c, th));
        n.add(new HeatCapacity(th, 50.0));
        TransientSolver s = new TransientSolver(n);
        double e0 = s.storedEnergy();
        s.advance(10.0);
        assertEquals(w0 * Math.exp(-c / J * 10.0), s.x(w), 1e-3 * w0);
        // isolated system: total (kinetic + thermal) energy conserved
        assertEquals(e0, s.storedEnergy(), 1e-6 * e0);
        // bearing temperature rise equals dissipated kinetic energy / C
        double dKE = 0.5 * J * (w0 * w0 - s.x(w) * s.x(w));
        assertEquals(300 + dKE / 50.0, s.x(th), 1e-3);
    }

    @Test
    void coulombFrictionStopsInFiniteTime() {
        double J = 0.01, tc = 0.05, w0 = 20;
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "rotor", w0);
        n.add(new Inertia(w, J));
        n.add(new CoulombFriction(w, ref, tc, 1e-3, null));
        TransientSolver s = new TransientSolver(n);
        double tStop = J * w0 / tc; // 4 s
        s.advance(tStop * 0.5);
        assertEquals(w0 / 2, s.x(w), 0.01 * w0);
        s.advance(tStop * 0.6);
        assertTrue(Math.abs(s.x(w)) < 0.01, "should have stopped, w=" + s.x(w));
    }

    @Test
    void torsionalOscillatorFrequency() {
        double J = 0.01, k = 40; // ωn = 63.25 rad/s
        double wn = Math.sqrt(k / J);
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "disc", 1.0);
        n.add(new Inertia(w, J));
        n.add(new Spring(w, ref, k));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.TRAPEZOIDAL).maxStep(2 * Math.PI / wn / 200);
        double t = 10.25 * 2 * Math.PI / wn;
        s.advance(t);
        assertEquals(Math.cos(wn * t), s.x(w), 0.02);
        assertEquals(0.5 * J, s.storedEnergy(), 1e-3 * 0.5 * J);
    }

    @Test
    void gearPairTransmitsRatioAndPower() {
        double ra = 0.01, rb = 0.03; // 1:3 reduction
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node a = n.node(Domain.ROTATIONAL, "pinion");
        Node b = n.node(Domain.ROTATIONAL, "wheel");
        n.add(new Inertia(a, 1e-4));
        n.add(new Inertia(b, 1e-2));
        n.add(new FlowSource(ref, a, 0.5));
        n.add(new Damper(b, ref, 0.05, null));
        n.add(new GearMesh(a, b, ra, rb, 10, 30, false, 0.0, null));
        TransientSolver s = new TransientSolver(n);
        s.advance(5.0);
        assertEquals(-s.x(a) * ra / rb, s.x(b), 1e-6 * Math.abs(s.x(a)));
        // steady state: τ_in = 0.5 N·m → wheel torque 1.5 N·m = c·|ω_b|
        assertEquals(1.5 / 0.05, Math.abs(s.x(b)), 0.01 * 30);
        assertEquals(0.0, s.energyError(), 1e-4 * s.externalEnergyIn());
    }

    @Test
    void gearMeshFrictionReducesEfficiencyRealistically() {
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node a = n.node(Domain.ROTATIONAL, "pinion");
        Node b = n.node(Domain.ROTATIONAL, "wheel");
        n.add(new Inertia(a, 1e-4));
        n.add(new Inertia(b, 1e-3));
        FlowSource src = n.add(new FlowSource(ref, a, 1.0));
        n.add(new Damper(b, ref, 0.01, null));
        n.add(new GearMesh(a, b, 0.01, 0.02, 20, 40, false, 0.05, null));
        TransientSolver s = new TransientSolver(n);
        s.advance(3.0);
        double pin = 1.0 * s.x(a);
        double pout = 0.01 * s.x(b) * s.x(b);
        double eta = pout / pin;
        // μ = 0.05, z = 20/40 → f = μπ(1/20+1/40) ≈ 1.2 % loss
        assertEquals(1 - 0.05 * Math.PI * (1.0 / 20 + 1.0 / 40), eta, 0.003);
    }

    @Test
    void lumpedBodyCoolsExponentially() {
        double C = 1000, G = 2.0, T0 = 400, Ta = 300;
        Network n = new Network();
        Node body = n.node(Domain.THERMAL, "body", T0);
        Node amb = n.node(Domain.THERMAL, "ambient", Ta);
        n.add(new HeatCapacity(body, C));
        n.add(new ThermalConductance(body, amb, G));
        FixedTemperature res = n.add(new FixedTemperature(amb, Ta));
        TransientSolver s = new TransientSolver(n);
        double t = 600;
        s.advance(t);
        assertEquals(Ta + (T0 - Ta) * Math.exp(-G * t / C), s.x(body), 0.05);
        // heat absorbed by the reservoir equals the body's lost internal energy
        assertEquals(0.0, s.energyError(), 1e-3 * C * (T0 - Ta));
        assertTrue(res.heatAbsorbed(s) > 0);
    }

    @Test
    void jouleHeatingRaisesTemperatureAdiabatically() {
        double V = 10, R = 5, C = 200;
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        Node th = n.node(Domain.THERMAL, "resistor", 300);
        n.add(new VoltageSource(a, gnd, V));
        n.add(new Resistor(a, gnd, R, th));
        n.add(new HeatCapacity(th, C));
        TransientSolver s = new TransientSolver(n);
        s.advance(10);
        assertEquals(300 + V * V / R * 10 / C, s.x(th), 1e-6);
        assertEquals(0.0, s.energyError(), 1e-6 * V * V / R * 10);
    }

    @Test
    void naturalConvectionCoefficientIsInHandbookRange() {
        // 25 mm horizontal cylinder at 350 K in 300 K air: Incropera Ex. 9.3-type case gives h ≈ 6–8 W/m²K
        NaturalConvection nc = new NaturalConvection(null, null, NaturalConvection.Shape.HORIZONTAL_CYLINDER, 1, 0.025);
        double h = nc.heatTransferCoefficient(350, 300);
        assertTrue(h > 5.5 && h < 8.5, "h=" + h);
    }
}
