package dev.emergence.core.sim;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validation of the parts of servos and controllers: gravity on levers, potentiometers, op-amps. */
class RoboticsValidationTest {
    static final Vec3 G = new Vec3(0, -9.81, 0);

    /** A point mass on a light rod swings with the pendulum period, including the finite-amplitude correction. */
    @Test
    void pendulumPeriodAndEnergy() {
        double m = 2, d = 0.5, th0 = 0.4;
        Network n = new Network();
        Node w = n.node(Domain.ROTATIONAL, "bob");
        n.add(new Inertia(w, m * d * d));
        AngleTracker ang = n.add(new AngleTracker(w, th0));
        n.add(new GravityTorque(w, ang, new Vec3(0, 0, 1), new Vec3(0, -d, 0), m, G));
        TransientSolver s = new TransientSolver(n).maxStep(1e-3);
        double e0 = s.storedEnergy();
        assertEquals(m * 9.81 * d * (1 - Math.cos(th0)) - m * 9.81 * d, e0, 1e-9, "potential −m g d cos θ₀");
        // period: 2π√(d/g)·(1 + θ₀²/16 + 11θ₀⁴/3072)
        double t0 = 2 * Math.PI * Math.sqrt(d / 9.81) * (1 + th0 * th0 / 16 + 11 * Math.pow(th0, 4) / 3072);
        // the angle returns to θ₀ (a maximum) one period later: time the zero crossings of ω going negative
        double prevW = s.x(w), tPrev = 0;
        java.util.List<Double> crossings = new java.util.ArrayList<>();
        while (s.time() < 3.5 * t0) {
            s.advance(1e-3);
            double now = s.x(w);
            if (prevW > 0 && now <= 0) crossings.add(tPrev + 1e-3 * prevW / (prevW - now));
            prevW = now;
            tPrev = s.time();
        }
        assertTrue(crossings.size() >= 3, "it swings: " + crossings);
        double period = (crossings.get(crossings.size() - 1) - crossings.get(0)) / (crossings.size() - 1);
        assertEquals(t0, period, 1e-3 * t0, "large-amplitude pendulum period");
        assertEquals(e0, s.storedEnergy(), 1e-4 * Math.abs(e0), "no energy gained or lost");
        assertTrue(Math.abs(ang.angle(s)) <= th0 * 1.001, "amplitude preserved");
    }

    /** A lever held level by a torque m g d; gravity's torque has the right sign and size. */
    @Test
    void leverTorqueBalances() {
        double m = 1.5, d = 0.3;
        Network n = new Network();
        Node ref = n.reference(Domain.ROTATIONAL);
        Node w = n.node(Domain.ROTATIONAL, "lever");
        n.add(new Inertia(w, m * d * d));
        AngleTracker ang = n.add(new AngleTracker(w, 0));
        // mass out along +x about the z axis: gravity turns it clockwise (−z); a +z torque m g d holds it
        n.add(new GravityTorque(w, ang, new Vec3(0, 0, 1), new Vec3(d, 0, 0), m, G));
        n.add(new FlowSource(ref, w, m * 9.81 * d));
        TransientSolver s = new TransientSolver(n);
        s.advance(1.0);
        assertEquals(0, ang.angle(s), 1e-6, "held level");
        assertEquals(0, s.x(w), 1e-6);
    }

    /** The wiper divides the supply in proportion to the shaft angle over its travel. */
    @Test
    void potentiometerDividesByAngle() {
        double travel = Math.toRadians(270);
        for (double f : new double[]{0.5, 0.75, 0.1}) {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL);
            Node top = n.node(Domain.ELECTRICAL, "top"), wiper = n.node(Domain.ELECTRICAL, "w");
            Node w = n.node(Domain.ROTATIONAL, "knob");
            n.add(new Inertia(w, 1e-4));
            AngleTracker ang = n.add(new AngleTracker(w, (f - 0.5) * travel));
            n.add(new VoltageSource(top, gnd, 5));
            Potentiometer pot = n.add(new Potentiometer(gnd, top, wiper, ang, 1, 10e3, travel, null));
            TransientSolver s = new TransientSolver(n);
            s.advance(1e-3);
            assertEquals(f, pot.wiper(s), 1e-3);
            assertEquals(5 * (10e3 * f + 1) / (10e3 + 2), s.x(wiper), 5e-3, "divider at f = " + f);
        }
    }

    static Network amplifier(double vin, Node[] nodes, OpAmp[] amp) {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node vp = n.node(Domain.ELECTRICAL, "V+"), vn = n.node(Domain.ELECTRICAL, "V-");
        Node in = n.node(Domain.ELECTRICAL, "in"), out = n.node(Domain.ELECTRICAL, "out"), fb = n.node(Domain.ELECTRICAL, "fb");
        n.add(new VoltageSource(vp, gnd, 12));
        n.add(new VoltageSource(gnd, vn, 12));
        n.add(new VoltageSource(in, gnd, t -> t > 1e-5 ? vin : 0));
        amp[0] = n.add(new OpAmp(in, fb, out, vp, vn, 1e6, 10e6, null));
        n.add(new Resistor(out, fb, 100e3)); // R2
        n.add(new Resistor(fb, gnd, 10e3)); // R1: gain 11
        nodes[0] = out;
        return n;
    }

    /** Non-inverting amplifier: gain 1 + R₂/R₁, closed-loop bandwidth GBW/gain, clipping at the rails. */
    @Test
    void opAmpNonInvertingAmplifier() {
        Node[] out = new Node[1];
        OpAmp[] amp = new OpAmp[1];
        TransientSolver s = new TransientSolver(amplifier(0.1, out, amp)).maxStep(2e-7);
        // closed-loop time constant 11/(2π·1 MHz) = 1.75 µs
        double tc = 11 / (2 * Math.PI * 1e6);
        s.advance(1e-5 + tc);
        assertEquals(1.1 * (1 - Math.exp(-1)), s.x(out[0]), 0.03, "single-pole closed-loop step response");
        s.advance(50e-6);
        assertEquals(1.1, s.x(out[0]), 1.1 * (11.0 / 1e5) + 1e-3, "gain 1 + R2/R1 (less the loop-gain error)");
        // overdriven: the output stops near the rail and the internal state does not wind up
        TransientSolver sat = new TransientSolver(amplifier(2, out, amp));
        sat.advance(1e-3);
        assertEquals(11.9, sat.x(out[0]), 0.05, "clips 0.1 V below the +12 V rail");
        assertTrue(amp[0].internal(sat) < 11.95, "no wind-up");
        // a voltage follower given a 10 V step slews at SR = 10 V/µs: 5 V after 0.5 µs
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node vp = n.node(Domain.ELECTRICAL, "V+"), vn = n.node(Domain.ELECTRICAL, "V-");
        Node in = n.node(Domain.ELECTRICAL, "in"), o = n.node(Domain.ELECTRICAL, "out");
        n.add(new VoltageSource(vp, gnd, 12));
        n.add(new VoltageSource(gnd, vn, 12));
        n.add(new VoltageSource(in, gnd, t -> t > 1e-5 ? 10 : 0));
        n.add(new OpAmp(in, o, o, vp, vn, 1e6, 10e6, null));
        n.add(new Resistor(o, gnd, 10e3));
        TransientSolver big = new TransientSolver(n).maxStep(2e-8);
        big.advance(1e-5 + 0.5e-6);
        assertEquals(5, big.x(o), 0.2, "slew-rate limited");
        big.advance(2e-6);
        assertEquals(10, big.x(o), 0.01, "settles on the input");
    }

    /** Energy closure: supply power = load power + op-amp dissipation. */
    @Test
    void opAmpPowerBalance() {
        Node[] out = new Node[1];
        OpAmp[] amp = new OpAmp[1];
        Network n = amplifier(0.5, out, amp);
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        assertTrue(s.externalEnergyIn() > 0);
        assertEquals(0, s.energyError(), 1e-4 * s.externalEnergyIn(), "energy closes");
    }
}
