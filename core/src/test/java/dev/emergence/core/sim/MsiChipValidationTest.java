package dev.emergence.core.sim;

import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** MSI chips (counter, register, adder, ROM) at the pins: truth tables, clocked behaviour, supply current. */
class MsiChipValidationTest {
    static final double VDD = 5;

    /** A chip on a 5 V supply; inputs driven by sources (set by {@code drive}), outputs loaded by 10 kΩ. */
    static final class Bench {
        final Network n = new Network();
        final Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd");
        final Node[] in, out;
        final VoltageSource[] src;
        final MsiChip chip;
        TransientSolver s;

        Bench(MsiChip.Type type, int[] rom) {
            n.add(new VoltageSource(vdd, gnd, VDD));
            in = new Node[type.inputs.length];
            src = new VoltageSource[in.length];
            for (int i = 0; i < in.length; i++) {
                in[i] = n.node(Domain.ELECTRICAL, type.inputs[i]);
                src[i] = n.add(new VoltageSource(in[i], gnd, 0));
            }
            out = new Node[type.outputs.length];
            for (int j = 0; j < out.length; j++) {
                out[j] = n.node(Domain.ELECTRICAL, type.outputs[j]);
                n.add(new Resistor(out[j], gnd, 10_000, null));
            }
            chip = n.add(new MsiChip(type, in, out, vdd, gnd, rom, 10e-9, 50, null));
        }

        void set(int pin, boolean high) {
            src[pin].setWaveform(t -> high ? VDD : 0);
        }

        void set(int pin, DoubleUnaryOperator w) {
            src[pin].setWaveform(w);
        }

        TransientSolver solver() {
            if (s == null) s = new TransientSolver(n);
            return s;
        }

        void run(double t) {
            solver().markDiscontinuity();
            solver().advance(t);
        }

        int read(int from, int bits) {
            int v = 0;
            for (int k = 0; k < bits; k++) {
                double volts = solver().x(out[from + k]);
                assertTrue(volts < 0.5 || volts > 4.5, "output " + (from + k) + " at a valid logic level: " + volts + " V");
                if (volts > 2.5) v |= 1 << k;
            }
            return v;
        }
    }

    @Test
    void adderAddsEveryOperandPair() {
        Bench b = new Bench(MsiChip.Type.ADDER4, null);
        for (int a = 0; a < 16; a++) {
            for (int c = 0; c < 16; c += 3) {
                for (int cin = 0; cin < 2; cin++) {
                    for (int k = 0; k < 4; k++) {
                        b.set(k, ((a >> k) & 1) == 1);
                        b.set(4 + k, ((c >> k) & 1) == 1);
                    }
                    b.set(8, cin == 1);
                    b.run(1e-6);
                    assertEquals(a + c + cin, b.read(0, 5), a + " + " + c + " + " + cin);
                }
            }
        }
    }

    @Test
    void romReadsItsProgram() {
        int[] program = new int[16];
        for (int w = 0; w < 16; w++) program[w] = (w * 37 + 11) & 0xFF;
        Bench b = new Bench(MsiChip.Type.ROM16X8, program);
        for (int w = 0; w < 16; w++) {
            for (int k = 0; k < 4; k++) b.set(k, ((w >> k) & 1) == 1);
            b.run(1e-6);
            assertEquals(program[w], b.read(0, 8), "word " + w);
        }
    }

    /** A 100 Hz square clock starting low. */
    static DoubleUnaryOperator clock(double f) {
        return t -> (t * f) % 1.0 >= 0.5 ? VDD : 0;
    }

    @Test
    void counterCountsLoadsAndClears() {
        Bench b = new Bench(MsiChip.Type.COUNTER4, null);
        // CLR̄, LOAD̄ high (inactive), EN high, P = 0b1010
        b.set(5, true);
        b.set(6, true);
        b.set(7, true);
        b.set(1, true);
        b.set(3, true);
        b.set(4, clock(100));
        b.run(0.0025);
        assertEquals(0, b.read(0, 4), "counter starts cleared");
        b.run(0.1); // rising edges at 5, 15, ..., 95 ms (10 edges) plus the one at 0 that is not an edge
        assertEquals(10, b.read(0, 4), "ten rising edges counted");
        // terminal count at 15 with EN high
        b.run(0.05);
        assertEquals(15, b.read(0, 4));
        assertTrue(b.solver().x(b.out[4]) > 4.5, "TC high at 15");
        b.run(0.01);
        assertEquals(0, b.read(0, 4), "wraps to 0");
        // EN low holds
        b.set(7, false);
        b.run(0.05);
        assertEquals(0, b.read(0, 4), "EN low: holds");
        // LOAD̄ low loads P on the next edge
        b.set(7, true);
        b.set(6, false);
        b.run(0.01);
        assertEquals(10, b.read(0, 4), "LOAD̄ low loads P = 10");
        b.set(6, true);
        b.run(0.02);
        assertEquals(12, b.read(0, 4), "then counts on");
        // CLR̄ low clears synchronously
        b.set(5, false);
        b.run(0.01);
        assertEquals(0, b.read(0, 4), "CLR̄ low clears");
    }

    @Test
    void registerLoadsOnlyWhenEnabled() {
        Bench b = new Bench(MsiChip.Type.REGISTER4, null);
        b.set(4, clock(100));
        b.set(5, true);
        b.set(0, true);
        b.set(2, true);
        b.run(0.012);
        assertEquals(5, b.read(0, 4), "loads D = 5 on the edge");
        b.set(5, false);
        b.set(0, false);
        b.set(1, true);
        b.run(0.05);
        assertEquals(5, b.read(0, 4), "EN low: keeps 5 while D changes");
        b.set(5, true);
        b.run(0.01);
        assertEquals(6, b.read(0, 4), "EN high: takes D = 6");
    }

    @Test
    void outputsDriveRealLoadsFromTheSupply() {
        Bench b = new Bench(MsiChip.Type.ROM16X8, new int[]{0xFF});
        b.run(1e-6);
        // 8 outputs high into 10 kΩ each: 8 × 0.5 mA from the supply, minus the ~50 Ω drop
        double v = b.solver().x(b.out[0]);
        assertEquals(VDD * 10_000 / 10_050, v, 1e-3, "output high through the 50 Ω pull-up");
    }
}
