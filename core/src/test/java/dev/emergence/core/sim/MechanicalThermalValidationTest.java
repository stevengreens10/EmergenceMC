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

    /**
     * Stiction: a rotor in a bearing whose friction can hold 0.05 N·m, pushed by half that, stays where it is — the
     * bristles carry the load — and pushed by more than the limit, it breaks away and accelerates at (τ − τ_c)/J. (The
     * older law, friction a function of the speed alone, let it creep at ω_s·artanh(½), 0.55 mrad/s, without end.)
     */
    @Test
    void frictionHoldsALoadBelowItsLimitAndBreaksAwayAboveIt() {
        double J = 0.01, tc = 0.05;
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "rotor", 0);
        n.add(new Inertia(w, J));
        n.add(new CoulombFriction(w, ref, tc, 1e-3, null));
        double[] push = {0.5 * tc};
        n.add(new FlowSource(ref, w, t -> push[0]));
        TransientSolver s = new TransientSolver(n);
        s.advance(100);
        assertTrue(Math.abs(s.x(w)) < 1e-6, "held: it does not creep, w = " + s.x(w));
        push[0] = 1.5 * tc;
        s.advance(1.0);
        assertEquals((1.5 * tc - tc) / J, s.x(w), 0.02 * (0.5 * tc / J), "it breaks away and accelerates at (τ − τc)/J");
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

    /**
     * Lead screw raising a load: the nut moves lead/2π per radian, and thread friction takes the efficiency
     * η = sin λ cos λ/(sin λ cos λ + μ') (μ' = μ/cos 15°) — Tr 20×4 oiled (μ = 0.13): ≈ 33 %.
     */
    @Test
    void leadScrewEfficiencyFollowsThreadFriction() {
        // 1 kN needs 1.9 N·m (0.64 to lift, 1.2 against the thread friction): driven with 4 N·m
        double lead = 0.004, dm = 0.018, mu = 0.13, f = 1000, c = 0.05, tau = 4.0;
        Network n = new Network();
        Node refR = n.reference(Domain.ROTATIONAL), refT = n.reference(Domain.TRANSLATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "screw"), v = n.node(Domain.TRANSLATIONAL, "nut");
        n.add(new Inertia(w, 1e-4));
        n.add(new Inertia(v, 1.0));
        n.add(new FlowSource(refR, w, tau));
        n.add(new Damper(w, refR, c, null));
        n.add(new FlowSource(refT, v, -f)); // the load pushes the nut back along −x
        n.add(GearMesh.leadScrew(w, v, lead, dm, Math.toRadians(15), -1, mu, null));
        TransientSolver s = new TransientSolver(n);
        s.advance(3.0);
        double om = s.x(w), vel = s.x(v);
        assertEquals(lead / (2 * Math.PI) * om, vel, 1e-6 * Math.abs(vel), "nut advances lead/2π per radian");
        double lam = Math.atan(lead / (Math.PI * dm)), mup = mu / Math.cos(Math.toRadians(15));
        double eta = f * vel / ((tau - c * om) * om), expect = Math.sin(lam) * Math.cos(lam) / (Math.sin(lam) * Math.cos(lam) + mup);
        System.out.printf("lead screw: η = %.4f (model %.4f, exact thread formula %.4f)%n", eta, expect,
                Math.tan(lam) * (1 - mup * Math.tan(lam)) / (Math.tan(lam) + mup));
        assertTrue(vel > 0, "it lifts the load");
        assertEquals(expect, eta, 0.003);
    }

    /** Self-locking: a fine-lead screw holds its load with nothing turning it; a steep (multi-start) one runs back. */
    @Test
    void fineLeadScrewHoldsItsLoadSteepOneRunsBack() {
        double[] leads = {0.004, 0.04};
        double[] fell = new double[2];
        for (int k = 0; k < 2; k++) {
            Network n = new Network();
            Node refR = n.reference(Domain.ROTATIONAL), refT = n.reference(Domain.TRANSLATIONAL);
            Node w = n.node(Domain.ROTATIONAL, "screw"), v = n.node(Domain.TRANSLATIONAL, "nut");
            n.add(new Inertia(w, 1e-4));
            n.add(new Inertia(v, 10.0));
            n.add(new FlowSource(refT, v, -98.1));
            AngleTracker x = n.add(new AngleTracker(v, 0));
            n.add(GearMesh.leadScrew(w, v, leads[k], 0.0175, Math.toRadians(15), -1, 0.13, null));
            TransientSolver s = new TransientSolver(n);
            s.advance(1.0);
            fell[k] = -x.angle(s);
        }
        System.out.printf("self-locking: fine lead fell %.3g m in 1 s, steep lead %.3g m%n", fell[0], fell[1]);
        assertTrue(fell[0] < 1e-3, "fine thread: friction holds it (tan λ = 0.073 < μ' = 0.135)");
        assertTrue(fell[1] > 0.1, "steep thread: it runs back (tan λ = 0.73 > μ')");
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
