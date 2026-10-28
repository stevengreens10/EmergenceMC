package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.digital.DisplayPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A screen keeps being drawn after its machine is saved and loaded again. The counter display's chip, like the
 * station's gauges, pulls its display's select low with its first byte and keeps it low (its only device). Saved with
 * the select low, its controller came up deselected after a reload and never heard another byte: every screen in a
 * reloaded world froze on the image it was saved with.
 */
class DisplayReloadTest {
    static DisplayPanel controller(CompiledAssembly c, Exhibits.Built b) {
        for (long id : b.ids().values()) {
            var chip = c.digitalChip(id);
            if (chip != null && chip.component() instanceof DisplayPanel d) return d;
        }
        return null;
    }

    @Test
    void aReloadedScreenIsStillDrawn() {
        Exhibit e = MonitorExhibits.counterDisplay();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        for (int i = 0; i < 30; i++) c.step(0.05);
        DisplayPanel d = controller(c, b);
        assertTrue(d.bytes() > 100 && d.on(), "drawn before: " + d.bytes() + " bytes");
        assertFalse(d.awaitingData());
        // saved and loaded again: the chips and the screen restored
        CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), c.exportState());
        DisplayPanel d2 = controller(again, b);
        again.step(0.001);
        assertTrue(d2.on() && d2.awaitingData(), "restored, not yet drawn: the game shows NO SIGNAL");
        long v = d2.version();
        for (int i = 0; i < 40; i++) again.step(0.05);
        System.out.printf("after the reload: %d bytes, version %d → %d%n", d2.bytes(), v, d2.version());
        assertTrue(d2.bytes() > 100, "the screen hears its chip again after the reload: " + d2.bytes() + " bytes");
        assertFalse(d2.awaitingData(), "and shows what it is sent");
        assertTrue(d2.version() > v, "its image changes");
    }

    @Test
    void noSignalSaysSo() {
        byte[] img = DisplayPanel.noSignalImage();
        int lit = 0;
        for (byte x : img) lit += Integer.bitCount(x & 0xff);
        assertEquals(1024, img.length);
        assertTrue(lit > 300 && lit < 2000, "words and a frame: " + lit);
    }
}
