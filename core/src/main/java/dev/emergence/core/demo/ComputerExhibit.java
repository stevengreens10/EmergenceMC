package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.sim.elements.MsiChip;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/**
 * "EM-4": a 4-bit programmable computer built from MSI chips, driving a stepper motor — the Phase 4
 * milestone (a player-built computer controlling a physical machine).
 * <p>
 * Architecture (single cycle, one clock): a program counter (counter chip) addresses a 16×8 ROM; each
 * instruction word is {@code J JS A - n3 n2 n1 n0}:
 * <ul>
 *   <li>A — ACC ← ACC + n (a 4-bit adder feeds the accumulator register, which loads while A is set);</li>
 *   <li>J — jump: PC ← n; JS — jump to n if the SENSE switch is on (PC LOAD̄ = NOR(J, JS·SENSE)).</li>
 * </ul>
 * The accumulator's four bits drive four power MOSFETs, each switching one coil of an axial-flux stepper motor:
 * the rotor's magnet sweeps directly under the four coils, whose inner leads meet at a star point on the supply,
 * and a silicone-damped bearing settles it at each step. Flyback diodes catch each coil's inductive kick. The
 * program computes the coil pattern 1, 2, 4, 8 by addition and loops; with SENSE on it branches into the reverse
 * sequence.
 */
public final class ComputerExhibit {
    private ComputerExhibit() {
    }

    /** The demo program: forward stepping, reverse while SENSE is on. */
    public static final int[] PROGRAM = {
            0x21, 0x21, 0x22, 0x24, // 0–3: ACC 0→1→2→4→8 (coils E, N, W, S in turn)
            0x47,                   // 4: JS 7 — reverse while SENSE is on
            0x29,                   // 5: ACC 8 + 9 = 1 (mod 16)
            0x81,                   // 6: J 1
            0x2C, 0x2E, 0x2F, 0x27, // 7–10: ACC 8→4→2→1→8 (reverse)
            0x47,                   // 11: JS 7 — keep reversing
            0x85,                   // 12: J 5 — back to forward
            0x80, 0x80, 0x80};      // 13–15: J 0

    /** Chip of the given type laid along +x from (x, y, z), pins on the north (inputs) and south (outputs) sides. */
    static int chip(Exhibit e, MsiChip.Type type, int x, int y, int z, Object... extra) {
        int first = -1;
        for (int k = 0; k < type.length(); k++) {
            Object[] kv = new Object[4 + extra.length];
            kv[0] = "function";
            kv[1] = type.ordinal();
            kv[2] = "lead_axis";
            kv[3] = 2;
            System.arraycopy(extra, 0, kv, 4, extra.length);
            int idx = e.add(CHIP, "silicon", x + k, y, z, Dir.EAST, kv);
            if (first < 0) first = idx;
        }
        return first;
    }

    public static Exhibit build() {
        Exhibit e = new Exhibit("computer", "EM-4 computer",
                List.of("EM-4 COMPUTER", "PC, ROM, adder, ACC", "drive a stepper.", "SENSE reverses it"),
                "A programmable computer from chips. The counter is the program counter; it addresses a 16-word ROM "
                        + "holding the program. Bit A of an instruction adds its 4-bit operand to the accumulator (a "
                        + "4-bit adder feeding a 4-bit register); J jumps to the operand, JS jumps only while the SENSE "
                        + "switch is on. The accumulator's bits switch four MOSFETs, each energising one coil above a "
                        + "magnet rotor: the program computes the pattern 1, 2, 4, 8 (adding 1, 1, 2, 4, then 9 to wrap) "
                        + "and the magnet is pulled a quarter turn under the next coil at each step, a silicone-filled "
                        + "bearing damping its swing. Flip SENSE and the program branches into a "
                        + "loop that adds 12, 14, 15, 7 — the reverse sequence. Diodes across the coils absorb the "
                        + "inductive kick when a MOSFET turns off. Everything runs from a 5 V supercapacitor bank.");
        e.plots = 3; // the stepper motor stands past the second plot
        String st = "steel_1045", cu = "copper";
        int y = 1;
        // ---- chips: program counter and accumulator (z = 2), ROM and adder (z = 7)
        e.name("pc", chip(e, MsiChip.Type.COUNTER4, 1, y, 2));
        e.name("acc", chip(e, MsiChip.Type.REGISTER4, 12, y, 2));
        Object[] words = new Object[32];
        for (int w = 0; w < 16; w++) {
            words[2 * w] = "w" + w;
            words[2 * w + 1] = PROGRAM[w];
        }
        e.name("rom", chip(e, MsiChip.Type.ROM16X8, 1, y, 7, words));
        e.name("adder", chip(e, MsiChip.Type.ADDER4, 12, y, 7));
        // ---- control row (z = 12): clock (Schmitt inverter + RC), jump logic, SENSE switch, supply
        e.name("clock", e.add(LOGIC, "silicon", 2, y, 12, Dir.EAST, "function", 7));
        e.add(RESISTOR, "alumina", 6, y, 12, Dir.EAST, "resistance", 470_000); // ≈ 3.8 s per instruction
        e.add(CAPACITOR, "alumina", 10, y, 12, Dir.EAST, "capacitance", 10e-6);
        e.name("and", e.add(LOGIC, "silicon", 14, y, 12, Dir.EAST, "function", 4));
        e.name("nor", e.add(LOGIC, "silicon", 18, y, 12, Dir.EAST, "function", 3));
        e.name("sense", e.add(SWITCH, "brass", 26, y, 12, Dir.EAST));
        e.add(RESISTOR, "alumina", 30, y, 12, Dir.EAST, "resistance", 100_000);
        int bat = e.add(CAPACITOR, "alumina", 34, y, 12, Dir.EAST, "capacitance", 2000);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        // ---- drivers (rows z = 1, 4, 7, 10): MOSFET, flyback diode, series resistor for coil k
        for (int k = 0; k < 4; k++) {
            int z = 1 + 3 * k;
            e.name("fet" + k, e.add(TRANSISTOR, "silicon", 24, y, z, Dir.EAST, "type", 2, "transconductance", 2.0));
            e.add(DIODE, "silicon", 28, y, z, Dir.EAST);
            e.add(RESISTOR, "alumina", 32, y, z, Dir.EAST, "resistance", 1.5);
        }
        // ---- stepper motor (axial flux): shaft in a silicone-damped bearing, nylon hub, one magnet (north up) at
        // r = 1 sweeping under four coils hung from a nylon plate
        int sx = 36, sz = 6;
        int shaft = e.fill(SHAFT, st, sx, 1, sz, sx, 3, sz, Dir.UP, "diameter", 0.012);
        e.name("rotor", shaft);
        e.add(BEARING, "bronze", sx, 1, sz, Dir.UP, "type", 2, "viscosity", 1.5);
        e.fill(BLOCK, st, sx - 1, 0, sz, sx - 1, 1, sz, Dir.UP);
        e.fill(BLOCK, st, sx + 1, 0, sz, sx + 1, 1, sz, Dir.UP);
        e.add(BLOCK, "nylon_66", sx, 3, sz, Dir.UP, "fill", 0.5);
        e.name("magnet", e.add(MAGNET, "ndfeb_n42", sx + 1, 3, sz, Dir.UP));
        // coils E, N, W, S (driven by accumulator bits 0–3); W and N mounted axis-down so all present the same pole
        int[][] coil = {{sx + 1, sz}, {sx, sz - 1}, {sx - 1, sz}, {sx, sz + 1}};
        Dir[] cax = {Dir.UP, Dir.DOWN, Dir.DOWN, Dir.UP};
        int[] lead = {0, 2, 0, 2};
        for (int k = 0; k < 4; k++) {
            e.name("coil" + k, e.add(COIL, cu, coil[k][0], 4, coil[k][1], cax[k], "turns", 400, "wire_diameter", 0.5e-3,
                    "lead_axis", lead[k]));
        }
        for (int x = sx - 2; x <= sx + 2; x++)
            for (int z = sz - 2; z <= sz + 2; z++) if (x != sx || z != sz) e.add(BLOCK, "nylon_66", x, 5, z, Dir.UP);
        for (int[] c : new int[][]{{sx - 2, sz - 2}, {sx + 2, sz - 2}, {sx - 2, sz + 2}, {sx + 2, sz + 2}})
            e.fill(BLOCK, "nylon_66", c[0], 0, c[1], c[0], 4, c[1], Dir.UP);
        // ---- wiring (pin cells: chip inputs at z − 1, outputs at z + 1; two-lead parts at x ± 1)
        Router r = new Router(e, 0, 0, 0, 39, 7, 14);
        for (int k = 0; k < 4; k++) {
            r.pin("pc" + k, 1 + k, y, 3).pin("pc" + k, 1 + k, y, 6);                                  // PC → ROM address
            r.pin("n" + k, 1 + k, y, 8).pin("n" + k, 16 + k, y, 6).pin("n" + k, 1 + k, y, 1);          // operand → adder B, PC P
            r.pin("s" + k, 12 + k, y, 8).pin("s" + k, 12 + k, y, 1);                                   // sum → ACC D
            r.pin("acc" + k, 12 + k, y, 3).pin("acc" + k, 12 + k, y, 6).pin("acc" + k, 24, y, 3 * k);  // ACC → adder A, gate
        }
        r.pin("accEn", 6, y, 8).pin("accEn", 17, y, 1);
        r.pin("js", 7, y, 8).pin("js", 13, y, 12);
        r.pin("jmp", 8, y, 8).pin("jmp", 17, y, 12);
        r.pin("jsAndSense", 15, y, 12).pin("jsAndSense", 18, 0, 12);
        r.pin("load", 19, y, 12).pin("load", 7, y, 1);
        r.pin("sense", 14, 0, 12).pin("sense", 27, y, 12).pin("sense", 29, y, 12);
        r.pin("clk", 3, y, 12).pin("clk", 7, y, 12).pin("clk", 5, y, 1).pin("clk", 16, y, 1);
        r.pin("osc", 1, y, 12).pin("osc", 5, y, 12).pin("osc", 9, y, 12);
        // coil drive: V+ → star point (all four coils' inner leads) … each coil's outer lead → 1.5 Ω → MOSFET drain;
        // a flyback diode from each drain back to V+
        int[][] outer = {{sx + 2, sz}, {sx, sz - 2}, {sx - 2, sz}, {sx, sz + 2}};
        r.pinShared("vdd", sx, 4, sz);
        for (int k = 0; k < 4; k++) {
            int z = 1 + 3 * k;
            r.pin("coilOut" + k, outer[k][0], 4, outer[k][1]).pin("coilOut" + k, 33, y, z);
            r.pin("coilLow" + k, 31, y, z).pin("coilLow" + k, 25, y, z).pin("coilLow" + k, 27, y, z);
        }
        // supply rails
        r.pin("vdd", 35, y, 12).pin("vdd", 9, y, 3).pin("vdd", 9, y, 8).pin("vdd", 21, y, 8).pin("vdd", 18, y, 3)
                .pin("vdd", 6, y, 1).pin("vdd", 8, y, 1).pin("vdd", 2, y, 13).pin("vdd", 14, y, 13).pin("vdd", 18, y, 13)
                .pin("vdd", 25, y, 12);
        for (int k = 0; k < 4; k++) r.pin("vdd", 29, y, 1 + 3 * k);
        r.pin("gnd", 33, y, 12).pin("gnd", 9, y, 1).pin("gnd", 9, y, 6).pin("gnd", 21, y, 6).pin("gnd", 18, y, 1)
                .pin("gnd", 20, y, 6).pin("gnd", 2, y, 11).pin("gnd", 14, y, 11).pin("gnd", 18, y, 11).pin("gnd", 11, y, 12)
                .pin("gnd", 31, y, 12);
        for (int k = 0; k < 4; k++) r.pin("gnd", 23, y, 1 + 3 * k);
        r.route();
        return e.view(19, 22, 30, 52);
    }
}
