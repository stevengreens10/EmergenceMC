package dev.emergence.core.digital;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The EM-8 processor (the shipped design) running programs from a ROM, and its assembler. */
class Em8Test {
    /** Drives its one output at a fixed level (an input port's switch). */
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

    /** A clock, the CPU and a ROM holding the program, wired; the input port at {@code in}; runs for t seconds; the output port. */
    static int run(String program, int in, double t) throws Exception {
        DigitalDomain dom = new DigitalDomain();
        int clock = dom.add(new ClockOscillator(250e3, 0));
        int cpu = dom.add(new DesignComponent(ChipLibrary.compile("em8"), null));
        int rom = dom.add(new MsiComponent(MsiComponent.Type.ROM256X8, ChipTypes.romImage(program), 120e-9));
        DigitalDomain.Net clk = dom.net("clk");
        dom.connectOutput(clock, 0, clk);
        dom.connectInput(cpu, 0, clk);
        // CPU pins: inputs clk, rst, d0..d7, i0..i3; outputs a0..a7, o0..o7
        for (int b = 0; b < 8; b++) {
            DigitalDomain.Net a = dom.net("a" + b), d = dom.net("d" + b);
            dom.connectOutput(cpu, b, a);
            dom.connectInput(rom, b, a);
            dom.connectOutput(rom, b, d);
            dom.connectInput(cpu, 2 + b, d);
        }
        int[] slots = new int[4];
        for (int b = 0; b < 4; b++) {
            slots[b] = dom.add(new Fixed(((in >> b) & 1) != 0));
            DigitalDomain.Net n = dom.net("i" + b);
            dom.connectOutput(slots[b], 0, n);
            dom.connectInput(cpu, 10 + b, n);
        }
        DigitalDomain.Net[] out = new DigitalDomain.Net[8];
        for (int b = 0; b < 8; b++) {
            out[b] = dom.net("o" + b);
            dom.connectOutput(cpu, 8 + b, out[b]);
        }
        for (int s : new int[]{clock, cpu, rom, slots[0], slots[1], slots[2], slots[3]}) {
            dom.slot(s).supply = 5;
            dom.postPower(s, true, 0);
        }
        dom.advanceTo(t);
        int o = 0;
        for (int b = 0; b < 8; b++) if (out[b].level()) o |= 1 << b;
        return o;
    }

    @Test
    void itComputesAndHalts() throws Exception {
        String p = """
                .asm em8
                ; three times seven, by adding
                sum = 1
                n = 2
                        LDI 0
                        ST sum
                        LDI 7
                        ST n
                again:  LD sum
                        ADDI 3
                        ST sum
                        DEC n
                        JNZ again
                        LD sum
                        OUT
                        HLT
                """;
        assertEquals(21, run(p, 0, 4e-3));
    }

    @Test
    void itReadsItsInputPortAndBranches() throws Exception {
        String p = """
                .asm em8
                        IN
                        CMPI 8          ; C if below 8
                        JC low
                        OUTI 0xAA
                        HLT
                low:    OUTI 0x55
                        HLT
                """;
        assertEquals(0x55, run(p, 0b0101, 4e-3));
        assertEquals(0xAA, run(p, 0b1010, 4e-3));
    }

    /** A subroutine called twice (nested in a third), saving A on the stack across it. */
    @Test
    void itCallsSubroutinesAndReturns() throws Exception {
        String p = """
                .asm em8
                x = 0
                        LDI 5
                        CALL triple     ; 15
                        CALL twice      ; 30, then 90 inside
                        OUT
                        HLT
                twice:  ST x
                        ADD x
                        PUSH            ; kept across the call
                        CALL triple
                        ST x
                        POP
                        ADD x           ; 30 + 90
                        RET
                triple: ST x
                        ADD x
                        ADD x
                        RET
                """;
        assertEquals(120, run(p, 0, 6e-3));
    }

    /** A pointer walks a table in memory (indirect loads and stores), summing it with a 16-bit carry. */
    @Test
    void pointersAndMultiByteArithmetic() throws Exception {
        String p = """
                .asm em8
                ptr = 0
                lo = 1
                hi = 2
                n = 3
                table = 20
                        LDI table       ; fill mem[20..27] with 200, 201, ... 207
                        ST ptr
                        LDI 8
                        ST n
                        LDI 200
                fill:   STN ptr
                        INC ptr
                        ADDI 1
                        DEC n
                        JNZ fill
                        LDI table
                        ST ptr
                        LDI 8
                        ST n
                        LDI 0
                        ST lo
                        ST hi
                sum:    LDN ptr         ; lo += mem[ptr]; hi += carry
                        ADD lo
                        ST lo
                        LD hi
                        ADCI 0
                        ST hi
                        INC ptr
                        DEC n
                        JNZ sum
                        LD hi           ; 1628 = 6 * 256 + 92
                        OUT
                        HLT
                """;
        assertEquals(6, run(p, 0, 12e-3));
    }

    @Test
    void theAssemblerSaysWhereAndWhat() {
        var e = assertThrows(Em8Assembler.AsmError.class, () -> Em8Assembler.assemble(".asm em8\n  LDI\n"));
        assertEquals(2, e.line);
        e = assertThrows(Em8Assembler.AsmError.class, () -> Em8Assembler.assemble(".asm em8\n  JMP nowhere\n"));
        assertTrue(e.getMessage().contains("nowhere"), e.getMessage());
        e = assertThrows(Em8Assembler.AsmError.class, () -> Em8Assembler.assemble(".asm em8\n  FLY 3\n"));
        assertTrue(e.getMessage().contains("FLY"), e.getMessage());
        assertTrue(Em8Assembler.isProgram("; a comment\n.asm em8\nNOP"));
        assertFalse(Em8Assembler.isProgram("12 34"));
    }

    @Test
    void theShippedThermostatProgramAssembles() throws Exception {
        var prog = Em8Assembler.assemble(ChipLibrary.rom("thermostat"));
        assertTrue(prog.labels().containsKey("loop") && prog.labels().containsKey("bit"));
        assertEquals("LDI 0", Em8Assembler.disassemble(prog.bytes(), 0));
        assertNull(ChipTypes.romError(ChipLibrary.rom("thermostat")));
    }
}
