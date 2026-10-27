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
    }
}
