package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.core.digital.DesignComponent;
import dev.emergence.core.digital.DisplayPanel;
import dev.emergence.core.digital.SpiAdc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** One gauge block, fed over a ribbon: it reads its signal and draws its card. */
class GaugeTest {
    /** A host: a 4-cell battery for the supply and a 1-cell one as a steady signal, their wires onto a ribbon at (6, 0, 2). */
    static Exhibit host(DashboardExhibits.Gauge g) {
        Exhibit e = new Exhibit("host", "Host", List.of(), "");
        e.name("battery", e.add(BATTERY, "pvc", 1, 0, 0, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 2));
        e.name("cell", e.add(BATTERY, "pvc", 9, 0, 0, Dir.EAST, "chemistry", 1, "cells", 1, "capacity", 2));
        e.name("ribbon", e.add(RIBBON, "copper", 6, 0, 2, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 12, 2, 1);
        r.pin("vdd", 2, 0, 0).pin("vdd", 6, 0, 1).colour("vdd", DashboardExhibits.VDD);
        r.pin("gnd", 0, 0, 0).pin("gnd", 8, 0, 0).pin("gnd", 5, 0, 1).colour("gnd", DashboardExhibits.GND);
        r.pin("sig", 10, 0, 0).pin("sig", 7, 0, 1).colour("sig", g.signal());
        r.route();
        // (the wires reach the ribbon from the north: (5, 0, 1), (6, 0, 1), (7, 0, 1) are beside (6, 0, 2) only diagonally
        // but for (6, 0, 1); the others run on to touch it)
        e.add(INSULATED_WIRE, "copper", 5, 0, 2, Dir.UP, "color", DashboardExhibits.GND);
        e.add(INSULATED_WIRE, "copper", 7, 0, 2, Dir.UP, "color", g.signal());
        e.embed(6, 0, 3, DashboardExhibits.gaugeBlock(g));
        return e;
    }

    @Test
    void aGaugeReadsItsSignalAndDrawsItsCard() {
        System.out.println("gauge pins: in " + ChipTypes.pins(ChipTypes.DESIGN, ChipLibrary.source("gauge")).inputs()
                + " out " + ChipTypes.pins(ChipTypes.DESIGN, ChipLibrary.source("gauge")).outputs());
        var g = new DashboardExhibits.Gauge("test", "BOILER PRESSURE", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.BOILER, 0,
                0, 102, 188, 0, 0, 2, 0, 51);
        Exhibit e = host(g);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        var chip = (DesignComponent) c.digitalChip(b.embeddedId(e, 0, "gauge")).component();
        var adc = (SpiAdc) c.digitalChip(b.embeddedId(e, 0, "adc")).component();
        var screen = (DisplayPanel) c.digitalChip(b.embeddedId(e, 0, "controller")).component();
        System.out.printf("gauge: %d gates, fastest clock %.0f kHz%n", chip.instance().design().estimate().gates(),
                chip.instance().design().estimate().maxClock() / 1e3);
        long t0 = System.nanoTime();
        for (int i = 0; i < 120; i++) c.step(0.05);
        double wall = (System.nanoTime() - t0) / 1e9;
        long v = chip.instance().value("v"), code = adc.lastCode();
        System.out.printf("gauge: ADC %d, value %d (%s), %d edges, violations %d; 6 s in %.2f s of wall time%n", code, v,
                String.format("%d.%02d", v / 100, v % 100), chip.instance().edges(), chip.instance().violations(), wall);
        System.out.println(render(screen));
        assertEquals(0, chip.instance().violations());
        long want = (code - 102) * 188 >> 8;
        assertTrue(Math.abs(v - want) <= 2, "the value from the ADC's reading: " + v + " against " + want);
        // the label's first glyph (B) on page 0 from column 2, a row down
        byte[] m = screen.memory();
        int[] bGlyph = DashboardExhibits.FONT[11];
        for (int i = 0; i < 5; i++) assertEquals((bGlyph[i] << 1) & 0xff, m[2 + i] & 0xff, "label column " + i);
        // the big digits: the units digit's first column, three wide, its top rows on page 2
        int d0 = (int) (v % 10), col = 4 + 4 * 18;
        int f = DashboardExhibits.FONT[d0][0];
        int top = (bit(f, 2) << 7) | (bit(f, 2) << 6) | (bit(f, 1) << 5) | (bit(f, 1) << 4) | (bit(f, 1) << 3) | (bit(f, 0) << 2) | (bit(f, 0) << 1) | bit(f, 0);
        for (int dx = 0; dx < 3; dx++) assertEquals(top, m[2 * 128 + col + dx] & 0xff, "units digit's top, column " + dx);
        // the bar: filled to its length, then its frame
        int px = (int) Math.min(120, v * 51 >> 8);
        assertEquals(0x7e, m[5 * 128 + 3] & 0xff, "the bar starts filled");
        assertEquals(0x42, m[5 * 128 + 3 + px + 2] & 0xff, "past its length, the frame");
    }

    static int bit(int f, int i) {
        return (f >> i) & 1;
    }

    /** The screen as text (for the log). */
    static String render(DisplayPanel s) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 128; x++) sb.append(s.pixel(x, y) ? '#' : '.');
            sb.append('\n');
        }
        return sb.toString();
    }
}
