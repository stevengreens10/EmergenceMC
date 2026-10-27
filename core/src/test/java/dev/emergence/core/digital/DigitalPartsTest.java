package dev.emergence.core.digital;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.PressureTransducer;
import dev.emergence.core.sim.elements.Resistor;
import dev.emergence.core.sim.elements.VoltageSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The library's digital parts, driven by designs a player could write: an ADC read over SPI, a display written. */
class DigitalPartsTest {
    /** Reads a 10-bit SPI ADC over and over: select (it samples), ten bits on rising edges, deselect. */
    static final String READER = """
            chip reader
            in clk, miso
            out cs = 1, sclk
            out [9:0] value
            reg [3:0] n = 0
            reg [9:0] sh = 0
            reg [1:0] st = 0
            on rise clk {
                case (st) {
                    0: { cs <= 0  st <= 1  n <= 0  sh <= 0 }
                    1: { sclk <= 1  sh <= {sh[8:0], miso}  st <= 2 }
                    2: { sclk <= 0  n <= n + 1  if (n == 9) st <= 3 else st <= 1 }
                    3: { cs <= 1  value <= sh  st <= 0 }
                }
            }
            """;

    /** Sends a display a fixed script: display on, page 2, column 5, one data byte of all ones. Bit 8 is d/c. */
    static final String WRITER = """
            chip writer
            in clk
            out cs = 1, dc, sclk, din
            mem [8:0] script[5] = {0x0af, 0x0b2, 0x005, 0x010, 0x1ff}
            reg [2:0] idx = 0
            reg [3:0] b = 0
            reg st = 0
            reg done = 0
            wire [8:0] cur = script[idx]
            on rise clk {
                if (!done) {
                    if (st == 0) {
                        cs <= 0  sclk <= 0  dc <= cur[8]  din <= cur[7 - b]  st <= 1
                    } else {
                        sclk <= 1  st <= 0
                        if (b == 7) { b <= 0  if (idx == 4) done <= 1 else idx <= idx + 1 } else b <= b + 1
                    }
                } else { cs <= 1  sclk <= 0 }
            }
            """;

    record Bench(Network n, Node gnd, Node vdd, DigitalDomain dom) {
        static Bench of() {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd");
            n.add(new VoltageSource(vdd, gnd, 5));
            return new Bench(n, gnd, vdd, new DigitalDomain());
        }

        int chip(DigitalComponent c, Node[] in, Node[] out) {
            int s = dom.add(c);
            n.add(new DigitalChip(dom, s, vdd, gnd, in, out, null, c.getClass().getSimpleName()));
            return s;
        }

        DigitalDomain.Net wire(int from, int out, int to, int in) {
            DigitalDomain.Net w = dom.net("w" + dom.nets().size());
            dom.connectOutput(from, out, w);
            dom.connectInput(to, in, w);
            return w;
        }
    }

    @Test
    void adcReadOverSpiGivesTheInputAsAFractionOfTheSupply() throws Exception {
        Bench b = Bench.of();
        Node vin = b.n.node(Domain.ELECTRICAL, "vin");
        b.n.add(new VoltageSource(vin, b.gnd, 1.25));
        int osc = b.chip(new ClockOscillator(10_000, 0), new Node[0], new Node[1]);
        SpiAdc adc = new SpiAdc(10);
        int a = b.chip(adc, new Node[]{null, null, vin}, new Node[1]);
        DesignComponent reader = new DesignComponent(ChipDesign.compile(READER), null);
        int r = b.chip(reader, new Node[2], new Node[12]);
        b.wire(osc, 0, r, 0);
        b.wire(r, 0, a, 0);  // cs
        b.wire(r, 1, a, 1);  // sclk
        b.wire(a, 0, r, 1);  // dout -> miso
        TransientSolver s = new TransientSolver(b.n);
        s.advance(0.05);
        assertTrue(adc.samples() > 10, "conversions: " + adc.samples());
        // 1.25 V of 5 V: 0.25 * 1023 = 255.75, within the half-LSB of noise
        long v = reader.instance().value("value");
        assertTrue(v == 255 || v == 256, "read " + v + " (last converted " + adc.lastCode() + ")");
    }

    @Test
    void displayTakesCommandsAndDataOverSpi() throws Exception {
        Bench b = Bench.of();
        int osc = b.chip(new ClockOscillator(100_000, 0), new Node[0], new Node[1]);
        DisplayPanel panel = new DisplayPanel();
        int d = b.chip(panel, new Node[5], new Node[0]);
        DesignComponent writer = new DesignComponent(ChipDesign.compile(WRITER), null);
        int w = b.chip(writer, new Node[1], new Node[4]);
        b.wire(osc, 0, w, 0);
        b.wire(w, 0, d, 0); // cs
        b.wire(w, 1, d, 1); // d/c
        b.wire(w, 2, d, 2); // sclk
        b.wire(w, 3, d, 3); // din
        // rst (input 4) unconnected reads low: the controller would be held in reset — tie it high
        DigitalDomain.Net high = b.dom.net("high");
        int tie = b.chip(new DesignComponent(ChipDesign.compile("chip tie\nout h\nassign h = 1"), null), new Node[0], new Node[1]);
        b.dom.connectOutput(tie, 0, high);
        b.dom.connectInput(d, 4, high);
        TransientSolver s = new TransientSolver(b.n);
        s.advance(0.01);
        assertEquals(5, panel.bytes());
        assertTrue(panel.on());
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 128; x++) {
                boolean want = x == 5 && y >= 16 && y < 24;
                assertEquals(want, panel.pixel(x, y), "pixel " + x + ", " + y);
            }
        }
        assertEquals(8, panel.shown());
    }

    @Test
    void pressureTransducerGivesTenToNinetyPercentOfItsSupplyOverItsRange() {
        for (double[] c : new double[][]{{0, 0.1}, {2e5, 0.5}, {4e5, 0.9}, {6e5, 0.9}}) {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd"), out = n.node(Domain.ELECTRICAL, "out"),
                    p = n.node(Domain.ELECTRICAL, "p");
            n.add(new VoltageSource(vdd, gnd, 5));
            n.add(new VoltageSource(p, gnd, 101_325 + c[0])); // (a fixed "pressure" carried on a node, for the test)
            n.add(new PressureTransducer(p, vdd, gnd, out, 101_325, 0, 4e5));
            n.add(new Resistor(out, gnd, 1e6)); // a meter's input
            TransientSolver s = new TransientSolver(n);
            s.advance(1e-3);
            assertEquals(5 * c[1], s.x(out), 0.02, "at " + c[0] + " Pa gauge");
        }
    }
}
