package dev.emergence.core.sim;

import dev.emergence.core.sim.elements.*;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Device-equation validation of transistors and LEDs, and logic emerging from transistor circuits. */
class SemiconductorValidationTest {
    static final double VT = PhysicalConstants.K_OVER_Q * 300;

    /** Solves a DC operating point by letting the circuit settle. */
    static TransientSolver settle(Network n) {
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        return s;
    }

    @Test
    void bjtForwardActiveGainAndEarlyEffect() {
        double is = 1e-14, beta = 100, va = 50;
        double[] ic = new double[2];
        double[] vce = {2, 10};
        for (int k = 0; k < 2; k++) {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL);
            Node c = n.node(Domain.ELECTRICAL, "c"), b = n.node(Domain.ELECTRICAL, "b");
            n.add(new VoltageSource(c, gnd, vce[k]));
            n.add(new VoltageSource(b, gnd, 0.65));
            Bjt q = n.add(new Bjt(c, b, gnd, false, is, beta, 1, va, null));
            TransientSolver s = settle(n);
            ic[k] = q.collectorCurrent(s);
            double expected = is * Math.exp(0.65 / VT) * (1 + vce[k] - 0.65 > 0 ? (1 - (0.65 - vce[k]) / va) : 1);
            assertEquals(expected, ic[k], 1e-3 * expected, "Ebers–Moll collector current");
            // the Early effect raises I_C but not I_B: gain β_F·(1 − V_BC/V_A)
            assertEquals(beta * (1 + (vce[k] - 0.65) / va), ic[k] / q.baseCurrent(s), 0.05, "forward current gain");
        }
        // Early effect: output conductance I_C/V_A
        double slope = (ic[1] - ic[0]) / (vce[1] - vce[0]);
        assertEquals(ic[0] / (va + 2 - 0.65), slope, 0.02 * slope);
    }

    @Test
    void pnpMirrorsNpn() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node c = n.node(Domain.ELECTRICAL, "c"), b = n.node(Domain.ELECTRICAL, "b");
        n.add(new VoltageSource(c, gnd, -5));
        n.add(new VoltageSource(b, gnd, -0.65));
        Bjt q = n.add(new Bjt(c, b, gnd, true, 1e-14, 100, 1, 50, null));
        TransientSolver s = settle(n);
        assertTrue(q.collectorCurrent(s) < -1e-4, "PNP conducts out of its collector: " + q.collectorCurrent(s));
    }

    @Test
    void mosfetSquareLawSubthresholdAndSymmetry() {
        double vth = 0.7, k = 2e-3, nslope = 1.3;
        // strong inversion, saturation: I = K/(2n)(Vgs − Vth)²
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node d = n.node(Domain.ELECTRICAL, "d"), g = n.node(Domain.ELECTRICAL, "g");
        n.add(new VoltageSource(d, gnd, 5));
        VoltageSource vg = n.add(new VoltageSource(g, gnd, 3));
        Mosfet m = n.add(new Mosfet(d, g, gnd, false, vth, k, nslope, 0, null));
        TransientSolver s = settle(n);
        double sq = k / (2 * nslope) * Math.pow(3 - vth, 2);
        assertEquals(sq, m.drainCurrent(s), 0.03 * sq, "square law in saturation");
        // weak inversion: one decade per n·V_T·ln10
        double[] i = new double[2];
        double[] v = {0.2, 0.3};
        for (int q = 0; q < 2; q++) {
            double vq = v[q];
            vg.setWaveform(t -> vq);
            s.markDiscontinuity();
            s.advance(1e-3);
            i[q] = m.drainCurrent(s);
        }
        double mvPerDecade = 0.1 / Math.log10(i[1] / i[0]);
        assertEquals(nslope * VT * Math.log(10), mvPerDecade, 0.03 * mvPerDecade, "subthreshold slope");
        // triode region (small V_DS): I ≈ K (V_GS − V_th − n V_DS/2) V_DS
        Network n2 = new Network();
        Node g2 = n2.reference(Domain.ELECTRICAL);
        Node dd = n2.node(Domain.ELECTRICAL, "d"), gg = n2.node(Domain.ELECTRICAL, "g");
        n2.add(new VoltageSource(dd, g2, 0.05));
        n2.add(new VoltageSource(gg, g2, 3));
        Mosfet tri = n2.add(new Mosfet(dd, gg, g2, false, vth, k, nslope, 0, null));
        double expected = k * (3 - vth - nslope * 0.05 / 2) * 0.05;
        assertEquals(expected, tri.drainCurrent(settle(n2)), 0.02 * expected, "linear (triode) region");
    }

    @Test
    void ledForwardVoltageFollowsItsColourAndEmitsLight() {
        double[] vf = new double[2];
        double[] lambda = {630, 470};
        for (int q = 0; q < 2; q++) {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL);
            Node a = n.node(Domain.ELECTRICAL, "a");
            n.add(new FlowSource(gnd, a, 0.02));
            Led led = n.add(new Led(a, gnd, lambda[q], 0.3, null));
            TransientSolver s = settle(n);
            vf[q] = s.x(a);
            assertEquals(0.02, led.current(s), 1e-6);
            assertEquals(0.3 * 1239.84 / lambda[q] * 0.02, led.opticalPower(s), 1e-6, "photons: η·E·I/q");
        }
        assertEquals(1239.84 / 630 + 0.1, vf[0], 0.05, "red LED ~2.07 V at 20 mA");
        assertEquals(1239.84 / 470 + 0.1, vf[1], 0.05, "blue LED ~2.74 V at 20 mA");
        // reverse biased: essentially no current
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node a = n.node(Domain.ELECTRICAL, "a");
        n.add(new VoltageSource(a, gnd, -5));
        Led led = n.add(new Led(a, gnd, 630, 0.3, null));
        assertTrue(Math.abs(led.current(settle(n))) < 1e-9);
    }

    /** CMOS inverter: matched NMOS and PMOS between 5 V and ground. */
    static double inverter(double vin) {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node vdd = n.node(Domain.ELECTRICAL, "vdd"), in = n.node(Domain.ELECTRICAL, "in"), out = n.node(Domain.ELECTRICAL, "out");
        n.add(new VoltageSource(vdd, gnd, 5));
        n.add(new VoltageSource(in, gnd, vin));
        n.add(new Mosfet(out, in, gnd, false, 0.7, 2e-3, 1.3, 0.02, null));
        n.add(new Mosfet(out, in, vdd, true, 0.7, 2e-3, 1.3, 0.02, null));
        n.add(new Resistor(out, gnd, 1e9, null)); // a high-impedance load
        return settle(n).x(out);
    }

    @Test
    void cmosInverterTransferCharacteristic() {
        assertEquals(5, inverter(0), 0.01, "logic 0 in → 1 out");
        assertEquals(0, inverter(5), 0.01, "logic 1 in → 0 out");
        assertEquals(2.5, inverter(2.5), 0.3, "matched pair switches at mid-rail");
        assertTrue(inverter(2.0) > 4 && inverter(3.0) < 1, "sharp transition (gain ≫ 1)");
    }
}
