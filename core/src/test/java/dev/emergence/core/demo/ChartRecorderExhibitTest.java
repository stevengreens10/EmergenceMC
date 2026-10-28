package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.digital.DesignComponent;
import dev.emergence.core.digital.DisplayPanel;
import dev.emergence.core.digital.SpiAdc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The chart-recorder exhibit graphs its vessel's pressure and temperature on its two screens. */
class ChartRecorderExhibitTest {
    static int top(DisplayPanel p, int x) {
        for (int y = 0; y < 64; y++) if (p.pixel(x, y)) return y;
        return 64;
    }

    @Test
    void screensGraphTheVesselsRisingPressureAndTemperature() {
        Exhibit e = MonitorExhibits.chartRecorder();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        DisplayPanel scrP = c.display(b.id(e, "screenP")), scrT = c.display(b.id(e, "screenT"));
        assertNotNull(scrP);
        assertNotNull(scrT);
        assertNotSame(scrP, scrT);
        var recP = (DesignComponent) c.digitalChip(b.id(e, "recorderP")).component();
        var adcP = (SpiAdc) c.digitalChip(b.id(e, "adcP")).component();
        var adcT = (SpiAdc) c.digitalChip(b.id(e, "adcT")).component();
        int p0 = -1, t0 = -1;
        for (int i = 0; i < 45 * 20; i++) {
            c.step(0.05);
            if (i == 40) {
                p0 = adcP.lastCode();
                t0 = adcT.lastCode();
            }
        }
        double[] tank = c.fluid(b.id(e, "tank"));
        System.out.printf("chart recorder: tank %.0f Pa, %.1f K; ADC codes P %d -> %d, T %d -> %d; columns %d; nets %d digital%n",
                tank[0], tank[1], p0, adcP.lastCode(), t0, adcT.lastCode(), recP.instance().value("x"), c.digital().nets().size());
        assertTrue(scrP.on() && scrT.on(), "the screens are on");
        assertTrue(adcP.lastCode() > p0 + 20, "the pressure rose on its ADC: " + p0 + " -> " + adcP.lastCode());
        assertTrue(adcT.lastCode() > t0 + 10, "the temperature rose on its ADC: " + t0 + " -> " + adcT.lastCode());
        // the traces: drawn, and the newest higher on the screen than the first
        long x = recP.instance().value("x");
        assertTrue(x > 90 && x < 128, "columns drawn in 45 s at 2 kHz (one sweep is 128): " + x);
        for (DisplayPanel s : new DisplayPanel[]{scrP, scrT}) {
            int first = top(s, 2), last = top(s, (int) x - 2);
            assertTrue(first < 64 && last < 64, "a trace at both ends: " + first + ", " + last);
            assertTrue(last < first, "the trace climbed: row " + first + " -> " + last);
        }
        assertTrue(c.digital().nets().size() >= 14, "the buses are digital nets (no solver steps): " + c.digital().nets().size());
        // read off the screen: the newest point where the chip put it (its row y in the column it last drew)
        newestPointShown(scrP, recP);
        newestPointShown(scrT, (DesignComponent) c.digitalChip(b.id(e, "recorderT")).component());
    }

    /**
     * The recorder's last finished point is lit on its screen, in the column it last finished (x − 1), and the column
     * ahead of it is clear. (Between columns that point is its y; while it draws column x, y is already that column's
     * point and the finished one is yp.)
     */
    static void newestPointShown(DisplayPanel s, DesignComponent rec) {
        int x = (int) rec.instance().value("x");
        int y = (int) (rec.instance().value("st") >= 6 ? rec.instance().value("yp") : rec.instance().value("y"));
        assertTrue(s.pixel(Math.floorMod(x - 1, 128), y), "the newest point at column " + (x - 1) + ", row " + y + " is on the screen");
        boolean clear = true;
        for (int r = 0; r < 64; r++) clear &= !s.pixel(Math.floorMod(x + 1, 128), r);
        assertTrue(clear, "the column ahead of the trace is clear");
    }

    /** Saved mid-byte and loaded again (as saved now and as saved before), the recorders go on drawing where they should. */
    @Test
    void reloadedMidByteTheTraceGoesOn() {
        Exhibit e = MonitorExhibits.chartRecorder();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        var rec = (DesignComponent) c.digitalChip(b.id(e, "recorderP")).component();
        for (int i = 0; i < 100; i++) c.step(0.05);
        for (int i = 0; i < 20000 && !(rec.instance().value("b") >= 1 && rec.instance().value("b") <= 6); i++) c.step(1e-6);
        long bit = rec.instance().value("b");
        assertTrue(bit >= 1 && bit <= 6, "saved mid-byte: bit " + bit);
        long scr = b.id(e, "screenP");
        for (boolean oldSave : new boolean[]{false, true}) {
            var s = c.exportState();
            if (oldSave) {
                // as saved before the display kept its place in a byte: the controller's words less the last
                for (var k : new java.util.ArrayList<>(s.chemistry().keySet()))
                    if (k.endsWith(":n") && s.chemistry().get(k) == 131 && c.display(scr) != null) s.chemistry().put(k, 130.0);
            }
            CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), s);
            var rec2 = (DesignComponent) again.digitalChip(b.id(e, "recorderP")).component();
            for (int i = 0; i < 100; i++) again.step(0.05);
            System.out.printf("chart recorder reloaded mid-byte (%s): column %d, row %d%n", oldSave ? "an old save" : "its place kept",
                    rec2.instance().value("x"), rec2.instance().value("y"));
            newestPointShown(again.display(scr), rec2);
        }
    }
}
