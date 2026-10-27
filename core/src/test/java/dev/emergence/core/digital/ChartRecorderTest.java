package dev.emergence.core.digital;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.VoltageSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped chart-recorder design graphs an ADC's input on the display. */
class ChartRecorderTest {
    /** The topmost lit row in column x (64 if none). */
    static int top(DisplayPanel p, int x) {
        for (int y = 0; y < 64; y++) if (p.pixel(x, y)) return y;
        return 64;
    }

    @Test
    void aRisingVoltageDrawsARisingTrace() throws Exception {
        ChipDesign design = ChipLibrary.compile("chart_recorder");
        var est = design.estimate();
        assertTrue(est.maxClock() > 1e6, "keeps up with a fast clock: " + est.maxClock());

        DigitalPartsTest.Bench b = DigitalPartsTest.Bench.of();
        Node vin = b.n().node(Domain.ELECTRICAL, "vin");
        // 0.5 V rising to 4.5 V over 9 s
        b.n().add(new VoltageSource(vin, b.gnd(), t -> 0.5 + 4.0 * Math.min(1, t / 9)));
        int osc = b.chip(new ClockOscillator(10_000, 0), new Node[0], new Node[1]);
        SpiAdc adc = new SpiAdc(10);
        int a = b.chip(adc, new Node[]{null, null, vin}, new Node[1]);
        DisplayPanel panel = new DisplayPanel();
        int d = b.chip(panel, new Node[5], new Node[0]);
        DesignComponent rec = new DesignComponent(design, null);
        int r = b.chip(rec, new Node[2], new Node[6]);
        b.wire(osc, 0, r, 0);
        b.wire(r, 0, a, 0);  // acs
        b.wire(r, 1, a, 1);  // asclk
        b.wire(a, 0, r, 1);  // dout -> miso
        b.wire(r, 2, d, 0);  // cs
        b.wire(r, 3, d, 1);  // d/c
        b.wire(r, 4, d, 2);  // sclk
        b.wire(r, 5, d, 3);  // din
        DigitalDomain.Net high = b.dom().net("high");
        int tie = b.chip(new DesignComponent(ChipDesign.compile("chip tie\nout h\nassign h = 1"), null), new Node[0], new Node[1]);
        b.dom().connectOutput(tie, 0, high);
        b.dom().connectInput(d, 4, high);

        TransientSolver s = new TransientSolver(b.n());
        s.advance(8.5);
        assertTrue(panel.on());
        long x = rec.instance().value("x");
        assertTrue(x > 100, "columns drawn in 8.5 s: " + x);
        // the trace climbs: each column's top no lower than a few columns before it
        int prev = 64, drawn = 0;
        for (int c = 2; c < x; c++) {
            int t = top(panel, c);
            if (t == 64) continue;
            drawn++;
            assertTrue(t <= prev + 1, "column " + c + " top " + t + " after " + prev);
            prev = t;
        }
        assertTrue(drawn > 90, "columns with a trace: " + drawn);
        // the newest point stands where its reading puts it: row 63 − code·16/256
        int code = adc.lastCode();
        int want = 63 - Math.min(63, code * 16 / 256);
        int lastTop = top(panel, (int) x - 1), lastBottom = lastTop;
        while (lastBottom < 63 && panel.pixel((int) x - 1, lastBottom + 1)) lastBottom++;
        assertTrue(want >= lastTop - 1 && want <= lastBottom + 1, "newest point row " + want + " in the stroke " + lastTop + ".." + lastBottom);
        // the column ahead of the one being drawn is clear (the sweep's gap)
        assertEquals(64, top(panel, (int) x + 1));
        assertTrue(s.acceptedSteps() < 20_000, "the solver did not follow the 10 kHz bus: " + s.acceptedSteps() + " steps");
    }
}
