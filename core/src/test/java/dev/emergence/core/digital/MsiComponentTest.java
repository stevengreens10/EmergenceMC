package dev.emergence.core.digital;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The MSI chips by events, the 256-byte ROM and its hex, CMOS delay against supply, and contention's dissipation. */
class MsiComponentTest {
    /** A context recording what is driven. */
    static final class Rec implements DigitalComponent.Ctx {
        final boolean[] out = new boolean[16];
        double lastDelay;

        public void drive(int k, boolean level, double delay) {
            out[k] = level;
            lastDelay = delay;
        }

        public void wakeAt(double t) {
        }

        public double supply() {
            return 5;
        }

        public double analog(int k, double t) {
            return 0;
        }
    }

    static int bits(Rec r, int from, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) if (r.out[from + i]) v |= 1 << i;
        return v;
    }

    @Test
    void counterCountsClearsAndLoadsLikeA74HC163() {
        MsiComponent c = new MsiComponent(MsiComponent.Type.COUNTER4, null, 10e-9);
        Rec r = new Rec();
        c.powerUp(0, r);
        // CLR̄ and LOAD̄ left low: every clock clears
        c.input(4, true, 1, r);
        c.input(4, false, 2, r);
        assertEquals(0, bits(r, 0, 4));
        c.input(5, true, 3, r);  // CLR̄ high
        c.input(6, true, 3, r);  // LOAD̄ high
        c.input(7, true, 3, r);  // EN high
        for (int i = 1; i <= 15; i++) {
            c.input(4, true, 4 + i, r);
            c.input(4, false, 4.5 + i, r);
            assertEquals(i, bits(r, 0, 4));
        }
        assertTrue(r.out[4], "TC at 15 with EN high");
        c.input(4, true, 30, r);
        assertEquals(0, bits(r, 0, 4), "wraps to 0");
        assertFalse(r.out[4]);
        // load P = 9 (P0 and P3 high) with LOAD̄ low
        c.input(0, true, 31, r);
        c.input(3, true, 31, r);
        c.input(6, false, 31, r);
        c.input(4, false, 32, r);
        c.input(4, true, 33, r);
        assertEquals(9, bits(r, 0, 4));
        assertEquals(10e-9, r.lastDelay);
        // saved and restored
        long[] st = c.save();
        MsiComponent d = new MsiComponent(MsiComponent.Type.COUNTER4, null, 10e-9);
        d.restore(st);
        Rec r2 = new Rec();
        d.powerUp(0, r2);
        assertEquals(9, bits(r2, 0, 4));
    }

    @Test
    void romShowsTheByteAddressedAndReadsItsHex() {
        String text = "// a test\n12 34 ab\n@10 FF # sixteen\n0x7e";
        int[] img = MsiComponent.parseHex(text, 256);
        assertEquals(0x12, img[0]);
        assertEquals(0xab, img[2]);
        assertEquals(0xff, img[0x10]);
        assertEquals(0x7e, img[0x11]);
        assertArrayEquals(img, MsiComponent.parseHex(MsiComponent.formatHex(img), 256), "formatted and read back");
        var e = assertThrows(IllegalArgumentException.class, () -> MsiComponent.parseHex("12 zz", 256));
        assertTrue(e.getMessage().contains("line 1"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> MsiComponent.parseHex("@FF 01 02", 256), "past the end");

        MsiComponent rom = new MsiComponent(MsiComponent.Type.ROM256X8, img, 120e-9);
        Rec r = new Rec();
        rom.powerUp(0, r);
        assertEquals(0x12, bits(r, 0, 8));
        rom.input(4, true, 1, r); // address 0x10
        assertEquals(0xff, bits(r, 0, 8));
        rom.input(0, true, 2, r); // 0x11
        assertEquals(0x7e, bits(r, 0, 8));
        assertEquals(120e-9, r.lastDelay);
        // the shipped font: digit 7 at 3·7
        int[] font = MsiComponent.parseHex(ChipLibrary.rom("hex_font_3x5"), 256);
        assertArrayEquals(new int[]{0x01, 0x01, 0x1F}, new int[]{font[21], font[22], font[23]});
        assertArrayEquals(new int[]{0x1F, 0x05, 0x05}, new int[]{font[45], font[46], font[47]}, "F");
    }

    @Test
    void cmosDelayGrowsAsTheSupplyFalls() {
        assertEquals(1.0, DigitalDomain.delayFactor(5), 1e-12);
        double at2 = DigitalDomain.delayFactor(2), at6 = DigitalDomain.delayFactor(6);
        assertTrue(at2 > 3.5 && at2 < 5, "74HC: 4–5× slower at 2 V: " + at2);
        assertTrue(at6 < 1 && at6 > 0.6, "faster at 6 V: " + at6);
    }

    /** Drives its one output at a fixed level. */
    static final class Fixed implements DigitalComponent {
        final boolean level;

        Fixed(boolean level) {
            this.level = level;
        }

        public List<String> inputs() {
            return List.of();
        }

        public List<String> outputs() {
            return List.of("o");
        }

        public void input(int k, boolean level, double t, Ctx ctx) {
        }

        public void powerUp(double t, Ctx ctx) {
            ctx.drive(0, level, 0);
        }

        public boolean output(int k) {
            return level;
        }

        public double energyPerEdge(double v) {
            return 0;
        }

        public double leakage(double v) {
            return 0;
        }
    }

    @Test
    void twoOutputsFightingBurnPowerInBoth() {
        DigitalDomain dom = new DigitalDomain();
        int a = dom.add(new Fixed(true)), b = dom.add(new Fixed(false));
        DigitalDomain.Net n = dom.net("fight");
        dom.connectOutput(a, 0, n);
        dom.connectOutput(b, 0, n);
        dom.slot(a).supply = 5;
        dom.slot(b).supply = 5;
        dom.postPower(a, true, 0);
        dom.postPower(b, true, 0);
        dom.advanceTo(1e-3);
        double ea = dom.takeEnergy(a), eb = dom.takeEnergy(b);
        // 5 V across 50 Ω + 50 Ω: 250 mW for 1 ms, shared
        assertEquals(0.25e-3, ea + eb, 0.25e-3 * 1e-6, "energy " + (ea + eb));
        assertEquals(ea, eb, 1e-12);
        assertEquals(0.25e-3, n.contentionEnergy(), 1e-9);
        assertTrue(n.contentions() > 0);
    }
}
