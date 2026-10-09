package dev.emergence.core.sim;

import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The reduced-order CMOS logic model against the transistor-level circuit and analytic timing. */
class LogicValidationTest {
    static final double VDD = 5;

    record Bench(Network n, Node gnd, Node vdd) {}

    static Bench bench() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node vdd = n.node(Domain.ELECTRICAL, "vdd");
        n.add(new VoltageSource(vdd, gnd, VDD));
        return new Bench(n, gnd, vdd);
    }

    /** Transistor-level NAND (two PMOS in parallel, two NMOS in series) output for inputs a, b. */
    static double transistorNand(double va, double vb) {
        Bench b = bench();
        Node a = b.n.node(Domain.ELECTRICAL, "a"), bb = b.n.node(Domain.ELECTRICAL, "b"), out = b.n.node(Domain.ELECTRICAL, "out"),
                mid = b.n.node(Domain.ELECTRICAL, "mid");
        b.n.add(new VoltageSource(a, b.gnd, va));
        b.n.add(new VoltageSource(bb, b.gnd, vb));
        b.n.add(new Mosfet(out, a, b.vdd, true, 0.7, 2e-3, 1.3, 0.02, null));
        b.n.add(new Mosfet(out, bb, b.vdd, true, 0.7, 2e-3, 1.3, 0.02, null));
        b.n.add(new Mosfet(out, a, mid, false, 0.7, 2e-3, 1.3, 0.02, null));
        b.n.add(new Mosfet(mid, bb, b.gnd, false, 0.7, 2e-3, 1.3, 0.02, null));
        b.n.add(new Resistor(out, b.gnd, 1e9, null));
        TransientSolver s = new TransientSolver(b.n);
        s.advance(1e-3);
        return s.x(out);
    }

    static double gateNand(double va, double vb) {
        Bench b = bench();
        Node a = b.n.node(Domain.ELECTRICAL, "a"), bb = b.n.node(Domain.ELECTRICAL, "b"), out = b.n.node(Domain.ELECTRICAL, "out");
        b.n.add(new VoltageSource(a, b.gnd, va));
        b.n.add(new VoltageSource(bb, b.gnd, vb));
        b.n.add(new LogicGate(LogicGate.Function.NAND, a, bb, out, null, b.vdd, b.gnd, 10e-9, 50, null));
        b.n.add(new Resistor(out, b.gnd, 1e9, null));
        TransientSolver s = new TransientSolver(b.n);
        s.advance(1e-3);
        return s.x(out);
    }

    @Test
    void gateModelMatchesTransistorLevelNand() {
        for (double[] in : new double[][]{{0, 0}, {5, 0}, {0, 5}, {5, 5}}) {
            double t = transistorNand(in[0], in[1]), g = gateNand(in[0], in[1]);
            assertEquals(t, g, 0.05, "logic levels for inputs " + in[0] + ", " + in[1]);
        }
        // switching threshold: the gate model's inverter switches at mid-rail like a matched CMOS inverter
        // (a transistor NAND with equal devices switches higher, ~0.65 V_DD, from its series n-channels)
        assertTrue(gateNand(2.3, 5) > 2.5 && gateNand(2.7, 5) < 2.5, "gate input threshold at V_DD/2");
        assertTrue(transistorNand(3.6, 3.6) < 2.5 && transistorNand(2.8, 2.8) > 2.5, "transistor NAND threshold ~3.2 V");
    }

    @Test
    void outputStageDrawsFromTheSupply() {
        // a 1 kΩ load on a high output: ~5 mA from V_DD through the ~50 Ω pull-up
        Bench b = bench();
        Node a = b.n.node(Domain.ELECTRICAL, "a"), out = b.n.node(Domain.ELECTRICAL, "out");
        b.n.add(new VoltageSource(a, b.gnd, 0));
        b.n.add(new LogicGate(LogicGate.Function.NOT, a, null, out, null, b.vdd, b.gnd, 10e-9, 50, null));
        b.n.add(new Resistor(out, b.gnd, 1000, null));
        TransientSolver s = new TransientSolver(b.n);
        s.advance(1e-3);
        assertEquals(VDD * 1000 / 1050, s.x(out), 0.01, "output sags across the output resistance");
    }

    @Test
    void dFlipFlopCapturesOnTheRisingEdge() {
        Bench b = bench();
        Node d = b.n.node(Domain.ELECTRICAL, "d"), clk = b.n.node(Domain.ELECTRICAL, "clk"), q = b.n.node(Domain.ELECTRICAL, "q"),
                qb = b.n.node(Domain.ELECTRICAL, "qb");
        VoltageSource vd = b.n.add(new VoltageSource(d, b.gnd, 0));
        VoltageSource vc = b.n.add(new VoltageSource(clk, b.gnd, 0));
        LogicGate ff = b.n.add(new LogicGate(LogicGate.Function.DFF, d, clk, q, qb, b.vdd, b.gnd, 10e-9, 50, null));
        b.n.add(new Resistor(q, b.gnd, 1e9, null));
        b.n.add(new Resistor(qb, b.gnd, 1e9, null));
        TransientSolver s = new TransientSolver(b.n).maxStep(1e-4);
        s.advance(1e-3);
        assertTrue(s.x(q) < 0.1 && s.x(qb) > 4.9, "starts reset");
        vd.setWaveform(t -> 5);
        s.markDiscontinuity();
        s.advance(1e-3);
        assertTrue(s.x(q) < 0.1, "D alone does not change Q");
        vc.setWaveform(t -> 5);
        s.markDiscontinuity();
        s.advance(1e-3);
        assertTrue(s.x(q) > 4.9 && s.x(qb) < 0.1, "rising clock edge captures D = 1");
        vd.setWaveform(t -> 0);
        s.markDiscontinuity();
        s.advance(1e-3);
        assertTrue(s.x(q) > 4.9, "D changing while the clock is high is ignored");
        vc.setWaveform(t -> 0);
        s.markDiscontinuity();
        s.advance(1e-3);
        assertTrue(s.x(q) > 4.9, "falling edge does nothing");
        vc.setWaveform(t -> 5);
        s.markDiscontinuity();
        s.advance(1e-3);
        assertTrue(s.x(q) < 0.1, "next rising edge captures D = 0");
    }

    @Test
    void schmittTriggerRcOscillatorPeriod() {
        double r = 100e3, c = 10e-6; // RC = 1 s
        Bench b = bench();
        Node in = b.n.node(Domain.ELECTRICAL, "in"), out = b.n.node(Domain.ELECTRICAL, "out");
        LogicGate inv = b.n.add(new LogicGate(LogicGate.Function.SCHMITT_NOT, in, null, out, null, b.vdd, b.gnd, 10e-9, 50, null));
        b.n.add(new Resistor(out, in, r, null));
        b.n.add(new Capacitor(in, b.gnd, c));
        TransientSolver s = new TransientSolver(b.n).maxStep(0.01);
        // record rising edges of the output
        java.util.List<Double> edges = new java.util.ArrayList<>();
        double prev = s.x(out);
        for (int i = 0; i < 1000; i++) {
            s.advance(0.01);
            double v = s.x(out);
            if (prev < 2.5 && v >= 2.5) edges.add(s.time());
            prev = v;
        }
        assertTrue(edges.size() >= 6, "oscillates: " + edges);
        double period = (edges.get(edges.size() - 1) - edges.get(1)) / (edges.size() - 2);
        double expected = 2 * r * c * Math.log(0.6 / 0.4);
        // the finite input transition width (3 % of V_DD) makes the gate flip slightly before the nominal
        // thresholds, shortening the period by ~6 % — within the spread of real 74HC14 thresholds
        assertEquals(expected, period, 0.08 * expected, "T = 2RC ln(V_T+/V_T−) for symmetric thresholds 0.4/0.6 V_DD");
    }
}
