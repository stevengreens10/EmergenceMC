package dev.emergence.core.sim;

import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical validation of the electrical network solver (VALIDATION.md §Electrical). */
class CircuitValidationTest {

    @Test
    void resistorDividerSatisfiesOhmAndKirchhoff() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        Node b = n.node(Domain.ELECTRICAL, "b");
        n.add(new VoltageSource(a, gnd, 12.0));
        Resistor r1 = n.add(new Resistor(a, b, 1000));
        Resistor r2 = n.add(new Resistor(b, gnd, 2000));
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        assertEquals(8.0, s.x(b), 1e-6);
        // equal up to the 1e-12 S GMIN shunt at node b
        assertEquals(r1.current(s), r2.current(s), 1e-10);
        assertEquals(4e-3, r1.current(s), 1e-9);
    }

    @Test
    void rcChargingMatchesExponential() {
        double R = 1000, C = 1e-6, V = 10, tau = R * C;
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        Node b = n.node(Domain.ELECTRICAL, "b");
        n.add(new VoltageSource(a, gnd, V));
        n.add(new Resistor(a, b, R));
        n.add(new Capacitor(b, gnd, C));
        TransientSolver s = new TransientSolver(n).maxStep(tau / 20);
        for (double t : new double[]{0.5 * tau, tau, 3 * tau, 5 * tau}) {
            s.advance(t - s.time());
            double exact = V * (1 - Math.exp(-t / tau));
            assertEquals(exact, s.x(b), 2e-3 * V, "t=" + t);
        }
        // energy: source work = capacitor energy + resistor heat (untracked here); ledger accuracy ~1e-3
        assertEquals(0.0, s.energyError(), 1e-3 * C * V * V);
    }

    @Test
    void rlCurrentRiseMatchesExponential() {
        double R = 2, L = 10e-3, V = 5, tau = L / R;
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        n.add(new VoltageSource(a, gnd, V));
        Inductor ind = n.add(new Inductor(a, gnd, L, R, null));
        TransientSolver s = new TransientSolver(n).maxStep(tau / 20);
        s.advance(2 * tau);
        double exact = V / R * (1 - Math.exp(-2));
        assertEquals(exact, ind.current(s), 2e-3 * V / R);
    }

    @Test
    void lcTankConservesEnergyWithTrapezoidalRule() {
        double L = 1e-3, C = 1e-6;
        double w0 = 1 / Math.sqrt(L * C);
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        n.add(new Capacitor(a, gnd, C));
        Inductor ind = n.add(new Inductor(a, gnd, L).initialCurrent(1.0));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.TRAPEZOIDAL).maxStep(2 * Math.PI / w0 / 40);
        double e0 = s.storedEnergy();
        assertEquals(0.5 * L, e0, 1e-12);
        double periods = 50;
        s.advance(periods * 2 * Math.PI / w0);
        assertEquals(e0, s.storedEnergy(), 1e-3 * e0, "trapezoidal LC energy drift");
        // Phase: the trapezoidal rule's frequency warping (≈(ωh)²/12 relative) accumulates; at h = T/40
        // it is ~0.6 rad after 50 periods, so phase is validated separately with a finer step.
    }

    @Test
    void lcTankPhaseAccuracy() {
        double L = 1e-3, C = 1e-6;
        double w0 = 1 / Math.sqrt(L * C);
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        n.add(new Capacitor(a, gnd, C));
        Inductor ind = n.add(new Inductor(a, gnd, L).initialCurrent(1.0));
        TransientSolver s = new TransientSolver(n).maxStep(2 * Math.PI / w0 / 400);
        s.advance(10 * 2 * Math.PI / w0);
        assertEquals(1.0, ind.current(s), 0.01);
    }

    @Test
    void rlcUnderdampedFrequencyAndDecay() {
        double R = 5, L = 1e-3, C = 1e-6;
        double w0 = 1 / Math.sqrt(L * C);
        double alpha = R / (2 * L);
        double wd = Math.sqrt(w0 * w0 - alpha * alpha);
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        n.add(new Capacitor(a, gnd, C));
        Inductor ind = n.add(new Inductor(a, gnd, L, R, null).initialCurrent(1.0));
        TransientSolver s = new TransientSolver(n).maxStep(2 * Math.PI / wd / 100);
        double t = 3.25 * 2 * Math.PI / wd;
        s.advance(t);
        // analytic: i(t) = e^{-αt} (cos ωd t − (α/ωd) sin ωd t) for i(0)=1, v(0)=0
        double exact = Math.exp(-alpha * t) * (Math.cos(wd * t) - alpha / wd * Math.sin(wd * t));
        assertEquals(exact, ind.current(s), 0.01);
        // ledger closes: stored energy lost == resistor heat
        assertEquals(0.0, s.energyError(), 2e-3 * 0.5 * L);
    }

    @Test
    void diodeForwardDropIsPhysical() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        Node d = n.node(Domain.ELECTRICAL, "d");
        n.add(new VoltageSource(a, gnd, 5.0));
        n.add(new Resistor(a, d, 4300));
        Diode diode = n.add(Diode.silicon(d, gnd, null));
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-4);
        double vd = s.x(d);
        // ~1 mA through a 1e-14 A silicon junction at 300 K: V = nVt ln(I/Is) ≈ 0.656 V
        assertEquals(0.0258520 * Math.log(diode.current(s) / 1e-14 + 1), vd, 1e-4);
        assertTrue(vd > 0.6 && vd < 0.72, "vd=" + vd);
        // reverse bias blocks
        Network n2 = new Network();
        Node g2 = n2.reference(Domain.ELECTRICAL);
        Node a2 = n2.node(Domain.ELECTRICAL, "a");
        Node d2 = n2.node(Domain.ELECTRICAL, "d");
        n2.add(new VoltageSource(a2, g2, -5.0));
        n2.add(new Resistor(a2, d2, 1000));
        Diode rd = n2.add(Diode.silicon(d2, g2, null));
        TransientSolver s2 = new TransientSolver(n2);
        s2.advance(1e-4);
        assertTrue(Math.abs(rd.current(s2)) < 1e-12);
    }

    @Test
    void largeResistorLadderUsesSparseSolverCorrectly() {
        // Ladder of N series resistors (R) each with shunt to ground of value R: input resistance
        // converges to R(1+√5)/2 (golden ratio) as N→∞.
        int N = 300;
        double R = 100;
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node in = n.node(Domain.ELECTRICAL, "in");
        VoltageSource src = n.add(new VoltageSource(in, gnd, 1.0));
        Node prev = in;
        for (int i = 0; i < N; i++) {
            Node next = n.node(Domain.ELECTRICAL, "n" + i);
            n.add(new Resistor(prev, next, R));
            n.add(new Resistor(next, gnd, R));
            prev = next;
        }
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-6);
        double rin = 1.0 / src.current(s);
        assertEquals(R * (1 + Math.sqrt(5)) / 2, rin, 1e-6 * R);
    }
}
