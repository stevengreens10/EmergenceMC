package dev.emergence.core.digital;

import java.util.Arrays;
import java.util.List;

/**
 * The medium-scale chips of the 74HC family and memories, run by events (see DigitalDomain): the same logic as the
 * analog model ({@link dev.emergence.core.sim.elements.MsiChip}), each output following its inputs after the chip's
 * propagation delay, the clocked ones changing on their clock's rising edge.
 * <ul>
 *   <li>COUNTER4 (74HC163): synchronous 4-bit counter. On a rising CLK: CLR̄ low clears, else LOAD̄ low loads P0–P3,
 *       else EN high counts up. TC = Q0·Q1·Q2·Q3·EN.</li>
 *   <li>REGISTER4 (74HC173-like): loads D0–D3 on a rising CLK while EN is high.</li>
 *   <li>ADDER4 (74HC283): S = A + B + C_in, C_out the carry.</li>
 *   <li>ROM16X8: 16 bytes fixed when it is made; D0–D7 show the byte at A0–A3.</li>
 *   <li>RAM16X4: 16 words of 4 bits; on a rising CLK while WE is high, D0–D3 are written to the word at A0–A3; Q0–Q3
 *       always show the word addressed.</li>
 *   <li>ROM256X8 (an EEPROM of the 28C-family's kind, 256 bytes): D0–D7 show the byte at A0–A7 after its access time.
 *       Its contents are the part's text, in hex (see {@link #parseHex}).</li>
 * </ul>
 * An unconnected input reads low (so CLR̄ and LOAD̄ must be tied high to count). Supply current: the quiescent
 * current, and C_pd·V² per cycle of switching (the datasheets' power-dissipation capacitance): half of it per clock
 * edge, a fifth per edge of a data input to a clocked chip, all of it per input edge of a combinational one.
 */
public final class MsiComponent implements DigitalComponent {
    public enum Type {
        COUNTER4(List.of("P0", "P1", "P2", "P3", "CLK", "CLR", "LOAD", "EN"), List.of("Q0", "Q1", "Q2", "Q3", "TC"), 35e-12),
        REGISTER4(List.of("D0", "D1", "D2", "D3", "CLK", "EN"), List.of("Q0", "Q1", "Q2", "Q3"), 25e-12),
        ADDER4(List.of("A0", "A1", "A2", "A3", "B0", "B1", "B2", "B3", "CIN"), List.of("S0", "S1", "S2", "S3", "COUT"), 80e-12),
        ROM16X8(List.of("A0", "A1", "A2", "A3"), List.of("D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7"), 40e-12),
        RAM16X4(List.of("A0", "A1", "A2", "A3", "D0", "D1", "D2", "D3", "WE", "CLK"), List.of("Q0", "Q1", "Q2", "Q3"), 50e-12),
        ROM256X8(List.of("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"), List.of("D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7"), 60e-12);

        public final List<String> inputs, outputs;
        /** Power-dissipation capacitance [F]. */
        final double cpd;

        Type(List<String> inputs, List<String> outputs, double cpd) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.cpd = cpd;
        }

        /** Words of memory (ROM or RAM), 0 for logic. */
        public int words() {
            return switch (this) {
                case ROM16X8, RAM16X4 -> 16;
                case ROM256X8 -> 256;
                default -> 0;
            };
        }

        int clock() {
            return inputs.indexOf("CLK");
        }
    }

    /** Quiescent supply current at 5 V [A] (74HC: typically a fraction of its 8 µA maximum). */
    static final double I_Q = 2e-6;

    private final Type type;
    private final int[] rom;
    private final double delay;
    private final boolean[] in;
    private final boolean[] out;
    /** The counter's or register's four bits. */
    private int q;
    private final int[] ram = new int[16];
    private long[] saved;

    /**
     * @param rom   the ROM's contents (bytes; missing ones read 0), ignored by other types
     * @param delay propagation delay at 5 V [s] (a ROM's access time)
     */
    public MsiComponent(Type type, int[] rom, double delay) {
        this.type = type;
        this.rom = Arrays.copyOf(rom == null ? new int[0] : rom, type.words());
        this.delay = delay;
        this.in = new boolean[type.inputs.size()];
        this.out = new boolean[type.outputs.size()];
    }

    public Type type() {
        return type;
    }

    @Override
    public List<String> inputs() {
        return type.inputs;
    }

    @Override
    public List<String> outputs() {
        return type.outputs;
    }

    private int bits(int from, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) if (in[from + i]) v |= 1 << i;
        return v;
    }

    /** Output j's level as the logic gives it now. */
    private boolean logic(int j) {
        return switch (type) {
            case COUNTER4 -> j < 4 ? ((q >> j) & 1) != 0 : q == 15 && in[7];
            case REGISTER4 -> ((q >> j) & 1) != 0;
            case ADDER4 -> (((bits(0, 4) + bits(4, 4) + (in[8] ? 1 : 0)) >> j) & 1) != 0;
            case ROM16X8 -> ((rom[bits(0, 4)] >> j) & 1) != 0;
            case RAM16X4 -> ((ram[bits(0, 4)] >> j) & 1) != 0;
            case ROM256X8 -> ((rom[bits(0, 8)] >> j) & 1) != 0;
        };
    }

    private void update(Ctx ctx) {
        for (int j = 0; j < out.length; j++) {
            boolean v = logic(j);
            if (v != out[j]) {
                out[j] = v;
                ctx.drive(j, v, delay);
            }
        }
    }

    @Override
    public void input(int k, boolean level, double t, Ctx ctx) {
        boolean was = in[k];
        in[k] = level;
        if (k == type.clock() && level && !was) {
            switch (type) {
                case COUNTER4 -> {
                    if (!in[5]) q = 0;
                    else if (!in[6]) q = bits(0, 4);
                    else if (in[7]) q = (q + 1) & 15;
                }
                case REGISTER4 -> {
                    if (in[5]) q = bits(0, 4);
                }
                case RAM16X4 -> {
                    if (in[8]) ram[bits(0, 4)] = bits(4, 4);
                }
                default -> { }
            }
        }
        update(ctx);
    }

    @Override
    public void powerUp(double t, Ctx ctx) {
        Arrays.fill(in, false);
        q = 0;
        Arrays.fill(ram, 0);
        if (saved != null) {
            apply(saved);
            saved = null;
        }
        for (int j = 0; j < out.length; j++) {
            out[j] = logic(j);
            ctx.drive(j, out[j], delay);
        }
    }

    @Override
    public boolean output(int k) {
        return out[k];
    }

    @Override
    public double energyPerEdge(double v) {
        return 0.5 * type.cpd * v * v;
    }

    @Override
    public double inputEnergy(int k, boolean level, double v) {
        if (type.clock() < 0) return 2 * energyPerEdge(v) / Math.max(1, in.length); // combinational: its share of a full swing
        return k == type.clock() ? energyPerEdge(v) : 0.2 * energyPerEdge(v);
    }

    @Override
    public double leakage(double v) {
        return I_Q * Math.max(0, v) / 5;
    }

    /** The register bits (counter, register), the RAM's 64 bits (word·4 + bit), or the outputs, as 0/1 levels. */
    public double[] state() {
        int n = type == Type.RAM16X4 ? 64 : type == Type.COUNTER4 || type == Type.REGISTER4 ? 4 : out.length;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = switch (type) {
                case RAM16X4 -> (ram[i / 4] >> (i % 4)) & 1;
                case COUNTER4, REGISTER4 -> (q >> i) & 1;
                default -> out[i] ? 1 : 0;
            };
        }
        return s;
    }

    /** A memory word (RAM or ROM). */
    public int word(int address) {
        if (type == Type.RAM16X4) return ram[address & 15];
        return rom.length == 0 ? 0 : rom[address & (rom.length - 1)];
    }

    @Override
    public long[] save() {
        if (type != Type.COUNTER4 && type != Type.REGISTER4 && type != Type.RAM16X4) return new long[0];
        long r = 0;
        for (int w = 0; w < 16; w++) r |= (long) (ram[w] & 15) << (4 * w);
        return new long[]{q, r};
    }

    @Override
    public void restore(long[] state) {
        saved = state;
    }

    private void apply(long[] s) {
        if (s.length < 2) return;
        q = (int) (s[0] & 15);
        for (int w = 0; w < 16; w++) ram[w] = (int) ((s[1] >>> (4 * w)) & 15);
    }

    /** Restores from the analog model's saved bits (a register's bit i, or the RAM's word·4 + bit): older saves. */
    public void restoreBits(double[] bits) {
        int qq = 0;
        long r = 0;
        for (int i = 0; i < bits.length; i++) {
            if (bits[i] < 0.5) continue;
            if (type == Type.RAM16X4) r |= 1L << i;
            else if (i < 4) qq |= 1 << i;
        }
        saved = new long[]{qq, r};
    }

    /**
     * A ROM image from text: hex bytes ("3F 12 a0", "3f12a0" or one per line), from address 0; "@1C" moves on to
     * address 0x1C; "//" and "#" start comments. Returns the bytes, or throws IllegalArgumentException saying where
     * the text is not hex.
     */
    public static int[] parseHex(String text, int size) {
        int[] b = new int[size];
        if (text == null) return b;
        int addr = 0, lineNo = 0;
        for (String line : text.split("\n", -1)) {
            lineNo++;
            int c = line.indexOf("//");
            if (c >= 0) line = line.substring(0, c);
            c = line.indexOf('#');
            if (c >= 0) line = line.substring(0, c);
            for (String tok : line.trim().split("[\\s,]+")) {
                if (tok.isEmpty()) continue;
                if (tok.startsWith("@")) {
                    try {
                        addr = Integer.parseInt(tok.substring(1), 16);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("line " + lineNo + ": \"" + tok + "\" is not an address");
                    }
                    continue;
                }
                if (tok.startsWith("0x") || tok.startsWith("0X")) tok = tok.substring(2);
                if (tok.length() % 2 != 0) tok = "0" + tok;
                for (int i = 0; i < tok.length(); i += 2) {
                    int v;
                    try {
                        v = Integer.parseInt(tok.substring(i, i + 2), 16);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("line " + lineNo + ": \"" + tok + "\" is not hex");
                    }
                    if (addr >= size) throw new IllegalArgumentException("line " + lineNo + ": past the end (" + size + " bytes)");
                    b[addr++] = v;
                }
            }
        }
        return b;
    }

    /** A ROM image as text: 16 bytes a line, each line starting with its address as a comment. */
    public static String formatHex(int[] bytes) {
        int last = bytes.length;
        while (last > 0 && bytes[last - 1] == 0) last--;
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < last; a += 16) {
            for (int i = a; i < Math.min(a + 16, last); i++) {
                if (i > a) sb.append(' ');
                sb.append(String.format("%02X", bytes[i] & 0xff));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
