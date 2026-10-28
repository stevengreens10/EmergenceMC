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
        // (looked at once this frame has drawn the value's pages: between frames the chip takes its next reading, and
        // its digits may be new before they are drawn)
        for (int i = 0; i < 200 && !(chip.instance().value("st") >= 17 && chip.instance().value("pg") >= 5); i++) c.step(0.01);
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
        int d0 = (int) chip.instance().value("d0"), col = 4 + 4 * 18;
        int f = DashboardExhibits.FONT[d0][0];
        int top = (bit(f, 2) << 7) | (bit(f, 2) << 6) | (bit(f, 1) << 5) | (bit(f, 1) << 4) | (bit(f, 1) << 3) | (bit(f, 0) << 2) | (bit(f, 0) << 1) | bit(f, 0);
        for (int dx = 0; dx < 3; dx++) assertEquals(top, m[2 * 128 + col + dx] & 0xff, "units digit's top, column " + dx);
        // the bar: filled to its length, then its frame
        int px = (int) chip.instance().value("px");
        assertEquals(0x56, m[5 * 128 + 3] & 0xff, "the bar starts filled (hatched)");
        assertEquals(0x42, m[5 * 128 + 3 + px + 2] & 0xff, "past its length, the frame");
        // and read off the screen as a person reads it: its label, and its value where the chip holds it
        assertEquals("BOILER PRESSURE", DashboardExhibits.readLabel(screen::pixel));
        assertEquals(v / 100.0, DashboardExhibits.valueOf(DashboardExhibits.readValue(screen::pixel)), 1e-9, "the value as shown");
    }

    /**
     * A gauge saved with its machine in the middle of sending a byte, and loaded again, goes on drawing its card right.
     * Saved so, the chip was restored part way through the byte while its display started counting bits afresh: every
     * byte after came in shifted, and the screen showed garbage (the user's station). The display now keeps its place
     * in a byte with its machine; and a save from before it did (no place kept) heals at the end of the next frame, where
     * the gauge releases the display's select.
     */
    @Test
    void aGaugeSavedMidByteGoesOnDrawingRight() {
        var g = new DashboardExhibits.Gauge("test", "BOILER PRESSURE", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.BOILER, 0,
                0, 102, 188, 0, 0, 2, 0, 51);
        Exhibit e = host(g);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        var chip = (DesignComponent) c.digitalChip(b.embeddedId(e, 0, "gauge")).component();
        for (int i = 0; i < 60; i++) c.step(0.05);
        // on into a byte: its fourth bit sent
        for (int i = 0; i < 20000 && !(chip.instance().value("st") == 29 && chip.instance().value("b") == 3); i++) c.step(1e-4);
        assertEquals(3, chip.instance().value("b"), "saved mid-byte");

        long ctl = b.embeddedId(e, 0, "controller");
        for (boolean oldSave : new boolean[]{false, true}) {
            var s = c.exportState(); // (afresh: c is not stepped between)
            if (oldSave) {
                // as saved before the display kept its place: its words less the last
                String n = "R" + ctl + ":n";
                s.chemistry().put(n, s.chemistry().get(n) - 1);
            }
            CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), s);
            var chip2 = (DesignComponent) again.digitalChip(b.embeddedId(e, 0, "gauge")).component();
            var screen2 = (DisplayPanel) again.digitalChip(ctl).component();
            // two frames on (a reading and its frame take some 2 s), looked at once the value's pages are drawn
            for (int i = 0; i < 100; i++) again.step(0.05);
            for (int i = 0; i < 300 && !(chip2.instance().value("st") >= 17 && chip2.instance().value("pg") >= 5); i++) again.step(0.01);
            long v = chip2.instance().value("v");
            String shown = DashboardExhibits.readValue(screen2::pixel), label = DashboardExhibits.readLabel(screen2::pixel);
            System.out.printf("reloaded mid-byte (%s): label \"%s\", value \"%s\" (the chip's %d)%n%s%n", oldSave ? "an old save" : "its place kept",
                    label, shown, v, render(screen2));
            assertEquals("BOILER PRESSURE", label, "the label, read off the screen");
            assertEquals(v / 100.0, DashboardExhibits.valueOf(shown), 1e-9, "the value, read off the screen");
            assertFalse(screen2.awaitingData());
        }
    }

    /**
     * A reading below the gauge's zero shows with a minus sign; past its alarm limit the card is shown inverted.
     * (The 1-cell signal reads about 1.3 V; a zero at code 400 puts it below, and a negative value is below a low alarm.)
     */
    @Test
    void negativeReadingsAndAlarms() {
        var g = new DashboardExhibits.Gauge("test", "SIGNED", "V", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.BOILER, 0,
                0, 400, 256, 0, 0, 0, 0, 51, 1, 0, 1);
        Exhibit e = host(g);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        var chip = (DesignComponent) c.digitalChip(b.embeddedId(e, 0, "gauge")).component();
        var screen = (DisplayPanel) c.digitalChip(b.embeddedId(e, 0, "controller")).component();
        for (int i = 0; i < 160; i++) c.step(0.05);
        for (int i = 0; i < 300 && !(chip.instance().value("st") >= 17 && chip.instance().value("pg") >= 5); i++) c.step(0.01);
        long v = chip.instance().value("v"), neg = chip.instance().value("neg");
        System.out.printf("signed gauge: %s%d, inverted %s%n", neg == 1 ? "-" : "", v, screen.inverted());
        System.out.println(render(screen));
        assertEquals(1, neg, "below its zero: negative");
        assertTrue(v > 50, "its magnitude: " + v);
        // the first cell holds the minus sign: its first glyph column ('-' is 08 in every column) three wide, page 3
        byte[] m = screen.memory();
        int dash = DashboardExhibits.FONT[39][0];
        int mid = (bit(dash, 5) << 7) | (bit(dash, 4) << 6) | (bit(dash, 4) << 5) | (bit(dash, 4) << 4) | (bit(dash, 3) << 3) | (bit(dash, 3) << 2) | (bit(dash, 3) << 1) | bit(dash, 2);
        assertEquals(mid, m[3 * 128 + 4] & 0xff, "the minus sign's middle rows");
        assertTrue(screen.inverted(), "a negative value is past any alarm: the card inverted");
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
