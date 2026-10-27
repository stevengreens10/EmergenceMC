package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.digital.DisplayPanel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The counter-display exhibit's screen shows the count its counter chip's LEDs show, in decimal and in binary. */
class CounterDisplayExhibitTest {
    /** The font of the counter-display design: three columns of five bits per digit, bit 0 the top row. */
    static final int[] FONT = {0x1F, 0x11, 0x1F, 0x12, 0x1F, 0x10, 0x1D, 0x15, 0x17, 0x15, 0x15, 0x1F, 0x07, 0x04, 0x1F,
            0x17, 0x15, 0x1D, 0x1F, 0x15, 0x1D, 0x01, 0x01, 0x1F, 0x1F, 0x15, 0x1F, 0x17, 0x15, 0x1F};

    /** The big digit whose cell starts at column x0 (pages 1–5, eight pixels a dot): 0–9, −1 blank, −2 unreadable. */
    static int bigDigit(DisplayPanel p, int x0) {
        int[] cols = new int[3];
        for (int c = 0; c < 3; c++) for (int r = 0; r < 5; r++) if (p.pixel(x0 + 8 * c + 4, 8 + 8 * r + 4)) cols[c] |= 1 << r;
        if (cols[0] == 0 && cols[1] == 0 && cols[2] == 0) return -1;
        for (int n = 0; n < 10; n++) if (cols[0] == FONT[3 * n] && cols[1] == FONT[3 * n + 1] && cols[2] == FONT[3 * n + 2]) return n;
        return -2;
    }

    /** The binary row (page 7, columns 48–79): the number its four small digits spell, or −1 if unreadable. */
    static int binaryRow(DisplayPanel p) {
        int v = 0;
        for (int k = 0; k < 4; k++) {
            int[] cols = new int[3];
            for (int c = 0; c < 3; c++) for (int r = 0; r < 5; r++) if (p.pixel(48 + 8 * k + 2 * c, 57 + r)) cols[c] |= 1 << r;
            int bit = -1;
            for (int n = 0; n < 2; n++) if (cols[0] == FONT[3 * n] && cols[1] == FONT[3 * n + 1] && cols[2] == FONT[3 * n + 2]) bit = n;
            if (bit < 0) return -1;
            v = 2 * v + bit;
        }
        return v;
    }

    /** The number on the screen in decimal, or −1 if unreadable. */
    static int shown(DisplayPanel p) {
        int t = bigDigit(p, 36), u = bigDigit(p, 68);
        if (u < 0 || t == -2 || t > 1) return -1;
        return (t == 1 ? 10 : 0) + u;
    }

    @Test
    void screenShowsTheCountInDecimalAndBinaryAsTheLedsDo() {
        countsOnScreen(MonitorExhibits.counterDisplay());
    }

    /** The same at true scale, in one block. */
    @Test
    void theMiniatureCountsOnItsScreenToo() {
        countsOnScreen(MonitorExhibits.miniatureCounterDisplay());
    }

    static void countsOnScreen(Exhibit e) {
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        // the screen's image: its own panels', or (the miniature's full-size screen, a separate machine) its controller's
        DisplayPanel screen = e.named.containsKey("screen") ? c.display(b.id(e, "screen"))
                : (DisplayPanel) c.digitalChip(b.id(e, "controller")).component();
        assertNotNull(screen);
        List<Integer> seen = new ArrayList<>();
        int agree = 0, checked = 0;
        // 20 s: the count goes round once (0..15, then 0 again); read the screen half-way through each second
        for (int i = 1; i <= 20 * 20; i++) {
            c.step(0.05);
            if (i % 20 != 10) continue;
            int leds = 0;
            for (int k = 0; k < 4; k++) {
                // its own LEDs, or (the miniature's) the full-size ones its ribbon cable feeds
                long led = e.named.containsKey("led" + k) ? b.id(e, "led" + k) : b.blockId(e, "led" + k);
                if (Math.abs(c.probe(led).current()) > 1e-3) leds |= 1 << k;
            }
            int dec = shown(screen), bin = binaryRow(screen);
            System.out.printf("t %4.1f s: LEDs %2d, screen %2d (binary %2d)%n", i * 0.05, leds, dec, bin);
            checked++;
            if (dec == leds && bin == leds) agree++;
            seen.add(dec);
        }
        assertTrue(screen.on(), "the screen is on");
        assertTrue(agree >= checked - 1, "the screen shows what the LEDs show (" + agree + " of " + checked + ")");
        // it counts: each reading one more than the last, mod 16, apart from the first second
        int steps = 0;
        for (int k = 1; k < seen.size(); k++) if (seen.get(k) == (seen.get(k - 1) + 1) % 16) steps++;
        assertTrue(steps >= seen.size() - 3, "it counts up a step a second: " + seen);
        assertTrue(seen.contains(15) && seen.contains(9) && seen.contains(10), "two-digit numbers too: " + seen);
        assertTrue(seen.lastIndexOf(0) > seen.indexOf(15), "and it wraps from 15 to 0: " + seen);
    }

    /** The digits' shapes are the ROM's: give the "1" the "7"'s columns, and a count of 1 is drawn as a 7. */
    @Test
    void theShapesComeFromTheRom() {
        Exhibit e = MonitorExhibits.counterDisplay();
        String font = dev.emergence.core.digital.ChipLibrary.rom("hex_font_3x5");
        String changed = font.replace("12 1F 10   // 1", "01 01 1F   // 1 drawn as a 7");
        assertNotEquals(font, changed);
        e.texts.replaceAll((k, v) -> v.equals(font) ? changed : v);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        DisplayPanel screen = c.display(b.id(e, "screen"));
        for (int i = 0; i < 10; i++) c.step(0.05); // 0.5 s: the count is 1 (its first tick comes as the clock starts)
        assertEquals(7, bigDigit(screen, 68), "the units digit, drawn from the ROM's columns at address 3");
    }
}
