package dev.emergence.core.digital;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The chip language: parsing, the behaviour it describes, its errors and its synthesis estimates. */
class ChipDesignTest {
    static final String BLINKER = """
            chip blinker
            in clk, rst
            out led
            param half = 500
            reg [9:0] n = 0
            reg lit = 0
            wire tick = n == half - 1
            assign led = lit
            on rise clk {
                if (rst) { n <= 0  lit <= 0 }
                else if (tick) { n <= 0  lit <= !lit }
                else n <= n + 1
            }
            """;

    /** Clocks input pin k through one full period at time t (rising at t, falling at t + half). */
    static void clock(ChipDesign.Instance in, int k, double t, double period) {
        in.setInput(k, true, t);
        in.setInput(k, false, t + period / 2);
    }

    @Test
    void blinkerTogglesEveryHalfPeriodOfEdges() throws Exception {
        ChipDesign d = ChipDesign.compile(BLINKER);
        assertEquals("blinker", d.name());
        assertEquals(java.util.List.of("clk", "rst"), d.inputPins());
        assertEquals(java.util.List.of("led"), d.outputPins());
        assertEquals(500L, d.parameters().get("half"));
        ChipDesign.Instance in = d.instantiate(Map.of("half", 3.0));
        boolean[] seen = new boolean[12];
        for (int i = 0; i < 12; i++) {
            clock(in, 0, i * 1e-3, 1e-3);
            seen[i] = in.output(0);
        }
        // the light changes on every third edge: off off on on on off off off on …
        boolean[] want = {false, false, true, true, true, false, false, false, true, true, true, false};
        assertArrayEquals(want, seen);
        assertEquals(12, in.edges());
        assertEquals(0, in.violations());
    }

    @Test
    void registeredBusOutputsAndCombinationalOnes() throws Exception {
        ChipDesign d = ChipDesign.compile("""
                chip counter
                in clk, a, b
                out [3:0] q
                out y
                assign y = a ^ b
                on rise clk q <= q + 1
                """);
        assertEquals(java.util.List.of("q0", "q1", "q2", "q3", "y"), d.outputPins());
        ChipDesign.Instance in = d.instantiate(null);
        for (int i = 0; i < 5; i++) clock(in, 0, i * 1e-3, 1e-3);
        assertEquals(5, in.value("q"));
        assertTrue(in.output(0) && !in.output(1) && in.output(2) && !in.output(3));
        // a combinational output changes with its inputs, at once (its delay is the chip's to apply)
        var ch = in.setInput(1, true, 0.01);
        assertNotNull(ch);
        assertTrue(ch.get(4));
        assertTrue(in.output(4));
        assertNull(in.setInput(2, false, 0.011)); // b was already low: no change
        in.setInput(2, true, 0.012);
        assertFalse(in.output(4));
    }

    @Test
    void memoriesCaseSlicesAndConcatenation() throws Exception {
        ChipDesign d = ChipDesign.compile("""
                chip m
                in clk, we
                in [1:0] sel
                out [7:0] o
                mem [7:0] rom[4] = {0x12, 0x34, 0x56, 0x78}
                mem [3:0] ram[4]
                reg [1:0] a = 0
                reg [7:0] r = 0
                on rise clk {
                    if (we) ram[a] <= a + 9
                    a <= a + 1
                    case (sel) {
                        0: r <= rom[a]
                        1: r <= {rom[a][3:0], rom[a][7:4]}
                        2, 3: r <= {4'd0, ram[a]}
                    }
                }
                assign o = r
                """);
        ChipDesign.Instance in = d.instantiate(null);
        // sel 0: read the ROM at a = 0
        clock(in, 0, 0, 1e-3);
        assertEquals(0x12, in.value("o"));
        // sel 1: nibbles swapped (a is now 1)
        in.setInput(2, true, 0.0015);
        clock(in, 0, 0.002, 1e-3);
        assertEquals(0x43, in.value("o"));
        // write the RAM at a = 2 and 3, then read it back at 0..3 with sel 2
        in.setInput(1, true, 0.0025);
        clock(in, 0, 0.003, 1e-3);
        clock(in, 0, 0.004, 1e-3);
        in.setInput(1, false, 0.0045);
        assertEquals(11, in.memory("ram", 2));
        assertEquals(12, in.memory("ram", 3));
        in.setInput(2, false, 0.0046);
        in.setInput(3, true, 0.0047); // sel = 2
        clock(in, 0, 0.005, 1e-3);   // a = 0
        clock(in, 0, 0.006, 1e-3);   // a = 1
        clock(in, 0, 0.007, 1e-3);   // a = 2: reads 11
        assertEquals(11, in.value("o"));
    }

    @Test
    void errorsSayWhereAndWhat() {
        assertError("chip x\nin a\nout y\nassign y = b", 4, "no such signal 'b'");
        assertError("chip x\nin clk\nout q\non rise clk { q = 1 }", 4, "'<='");
        assertError("chip x\nin a\nout y", -1, "never driven");
        assertError("chip x\nin a\nout y\nwire p = a\nassign y = p\nreg r\nreg r", 7, "declared twice");
        assertError("chip x\nin a\nout y\nassign y = { a, 3 }", 4, "need widths");
    }

    static void assertError(String src, int line, String part) {
        var e = assertThrows(ChipDesign.ChipError.class, () -> ChipDesign.compile(src));
        assertTrue(e.getMessage().contains(part), e.getMessage());
        if (line > 0) assertEquals(line, e.line, e.getMessage());
    }

    @Test
    void estimatesFollowTheLogicAndAFastClockIsMissed() throws Exception {
        ChipDesign small = ChipDesign.compile("chip s\nin clk\nout [7:0] q\non rise clk q <= q + 1");
        ChipDesign wide = ChipDesign.compile("chip w\nin clk\nout [31:0] q\non rise clk q <= q * q + 7");
        ChipDesign.Estimate es = small.estimate(), ew = wide.estimate();
        assertEquals(8, es.flipFlops());
        assertTrue(ew.gates() > 20 * es.gates(), es + " vs " + ew);
        assertTrue(es.maxClock() > 5e6 && es.maxClock() < 100e6, "an 8-bit ripple counter: " + es.maxClock());
        assertTrue(ew.maxClock() < es.maxClock() / 5, "a 32-bit multiplier is far slower");
        assertTrue(ew.energyPerEdge(5) > 10 * es.energyPerEdge(5));
        // clocked faster than its logic settles, the counter misses edges (and counts them)
        ChipDesign.Instance in = small.instantiate(null);
        double period = 0.5 / es.maxClock();
        for (int i = 0; i < 10; i++) clock(in, 0, i * period, period);
        assertTrue(in.violations() > 0);
        assertTrue(in.value("q") < 10);
    }

    @Test
    void stateSavesAndRestores() throws Exception {
        ChipDesign d = ChipDesign.compile(BLINKER);
        ChipDesign.Instance a = d.instantiate(Map.of("half", 3.0));
        for (int i = 0; i < 4; i++) clock(a, 0, i * 1e-3, 1e-3);
        ChipDesign.Instance b = d.instantiate(Map.of("half", 3.0));
        b.restore(a.save());
        assertEquals(a.value("n"), b.value("n"));
        assertEquals(a.output(0), b.output(0));
    }
}
