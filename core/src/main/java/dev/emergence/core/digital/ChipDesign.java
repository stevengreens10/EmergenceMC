package dev.emergence.core.digital;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A chip's logic, written in the chip language: a small register-transfer language in the manner of Verilog, which a
 * player writes (or opens from the examples) and has fabricated as a chip. What it describes is the chip's
 * behaviour at the level digital designers think in — registers updated on a clock edge, combinational logic between
 * them — not its transistors; the physics stays at the pins (see DigitalChip), and the design sets what the
 * physics costs: its gate count, its logic depth (hence its propagation delay and the fastest clock it keeps up
 * with) and the energy each clock edge switches (hence its supply current).
 *
 * <pre>
 * chip blinker                      // the chip's name
 * in clk, rst                       // input pins; a bus: in [3:0] d  (pins d0..d3)
 * out led                           // output pins
 * param half = 500                  // a constant a chip part may override ("half" parameter)
 * reg [9:0] n = 0                   // a register, 10 bits, starting at 0
 * reg lit = 0
 * wire tick = n == half - 1         // combinational logic
 * assign led = lit                   // an output driven combinationally
 * on rise clk {                     // on each rising edge of clk (also: on fall clk)
 *     if (rst) { n <= 0  lit <= 0 }
 *     else if (tick) { n <= 0  lit <= !lit }
 *     else n <= n + 1
 * }
 * </pre>
 * Statements in an {@code on} block take effect together at its end (non-blocking: every right-hand side reads the
 * values from before the edge). Also: {@code case (e) { 0, 1: {…} 2: {…} default: {…} }}, memories
 * {@code mem [7:0] font[64] = {0x3e, 0x51, …}} read as {@code font[a]} and written in {@code on} blocks, an output
 * written in an {@code on} block (it is then a register), slices {@code x[7:4]}, bits {@code x[i]}, concatenation
 * {@code {a, b}}, sized literals {@code 8'hff 4'b1010 10'd5}, operators {@code + - * / % & | ^ ~ ! && || == != < <= >
 * >= << >> ?:}. Values are unsigned, at most 64 bits.
 */
public final class ChipDesign {
    /** A compile error, with its line and column. */
    public static final class ChipError extends Exception {
        public final int line, column;

        ChipError(String msg, int line, int column) {
            super("line " + line + ":" + column + ": " + msg);
            this.line = line;
            this.column = column;
        }
    }

    enum Kind { INPUT, OUTPUT, REG, WIRE, PARAM, MEM }

    /** A named value: a port, register, wire, parameter or memory. */
    static final class Signal {
        final String name;
        final Kind kind;
        final int width, index;
        Expr expr; // a wire's (or combinational output's) expression
        long init; // a register's starting value (a parameter's default)
        boolean registered; // an output written in an on block
        int depth = -1; // memory: words
        long[] memInit;

        Signal(String name, Kind kind, int width, int index) {
            this.name = name;
            this.kind = kind;
            this.width = width;
            this.index = index;
        }

        long mask() {
            return width >= 64 ? -1L : (1L << width) - 1;
        }
    }

    // ------------------------------------------------------------------ the design

    private final String name;
    private final List<Signal> signals = new ArrayList<>();
    private final Map<String, Signal> byName = new LinkedHashMap<>();
    private final List<Signal> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();
    private final List<String> inputPins = new ArrayList<>(), outputPins = new ArrayList<>();
    /** Each input pin's port and bit; each output pin's. */
    private final int[] inPort, inBit, outPort, outBit;
    private Estimate estimate;

    private ChipDesign(Parser p) {
        this.name = p.chipName;
        signals.addAll(p.signals);
        for (Signal s : signals) byName.put(s.name, s);
        for (Signal s : signals) {
            if (s.kind == Kind.INPUT) inputs.add(s);
            if (s.kind == Kind.OUTPUT) outputs.add(s);
        }
        processes.addAll(p.processes);
        List<int[]> ip = new ArrayList<>(), op = new ArrayList<>();
        for (Signal s : inputs) for (int b = 0; b < s.width; b++) {
            inputPins.add(s.width == 1 ? s.name : s.name + b);
            ip.add(new int[]{s.index, b});
        }
        for (Signal s : outputs) for (int b = 0; b < s.width; b++) {
            outputPins.add(s.width == 1 ? s.name : s.name + b);
            op.add(new int[]{s.index, b});
        }
        inPort = ip.stream().mapToInt(a -> a[0]).toArray();
        inBit = ip.stream().mapToInt(a -> a[1]).toArray();
        outPort = op.stream().mapToInt(a -> a[0]).toArray();
        outBit = op.stream().mapToInt(a -> a[1]).toArray();
    }

    /** Compiles a design from its source. */
    public static ChipDesign compile(String source) throws ChipError {
        Parser p = new Parser(source);
        p.parse();
        ChipDesign d = new ChipDesign(p);
        d.estimate = Estimate.of(d);
        return d;
    }

    public String name() {
        return name;
    }

    /** The input pins, in order (a bus's bits low first: d0, d1, …). */
    public List<String> inputPins() {
        return java.util.Collections.unmodifiableList(inputPins);
    }

    public List<String> outputPins() {
        return java.util.Collections.unmodifiableList(outputPins);
    }

    /** Whether a change of input pin k to {@code level} is an edge some on block runs on. */
    public boolean clocks(int k, boolean level) {
        Signal s = signals.get(inPort[k]);
        if (s.width != 1) return false;
        for (Process p : processes) if (p.clock == s && p.rising == level) return true;
        return false;
    }

    /** Whether output pin k comes from a register (it changes a clock-to-output after an edge) rather than logic. */
    public boolean outputRegistered(int k) {
        Signal s = signals.get(outPort[k]);
        return s.registered;
    }

    /** The parameters a chip part may set, with their defaults. */
    public Map<String, Long> parameters() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Signal s : signals) if (s.kind == Kind.PARAM) m.put(s.name, s.init);
        return m;
    }

    public Estimate estimate() {
        return estimate;
    }

    /** Its registers (and outputs written in on blocks), in declaration order: what a probe of a running chip shows. */
    public List<String> registers() {
        List<String> out = new ArrayList<>();
        for (Signal s : signals) if (s.kind == Kind.REG || s.kind == Kind.OUTPUT && s.registered) out.add(s.name);
        return out;
    }

    /** A signal's width in bits (0 if there is none by that name). */
    public int width(String name) {
        Signal s = byName.get(name);
        return s == null ? 0 : s.width;
    }

    /** A running copy of the design: its registers, memories and pins. */
    public Instance instantiate(Map<String, Double> params) {
        return new Instance(params);
    }

    // ------------------------------------------------------------------ synthesis estimates

    /**
     * What the design costs as a circuit (rough synthesis, CMOS 74HC-class process): gates (two-input equivalents),
     * flip-flops, memory bits, and the critical path in gate delays — the longest combinational chain between two
     * registers, or from an input to an output.
     */
    public record Estimate(int gates, int flipFlops, int memoryBits, int registerDepth, int combinationalDepth) {
        /** One gate's delay [s] in the process the chips are made in (74HC-class internal gate). */
        public static final double GATE_DELAY = 3e-9;
        /** Setup time and clock-to-output of a flip-flop [s]. */
        public static final double SETUP = 5e-9, CLOCK_TO_Q = 8e-9;
        /** Switched capacitance per gate [F] (an internal node of a 74HC-class chip), and leakage per gate [A at 5 V]. */
        public static final double GATE_CAPACITANCE = 30e-15, GATE_LEAKAGE = 2e-9;

        /** The fastest clock the registers keep up with [Hz]. */
        public double maxClock() {
            return 1 / (registerDepth * GATE_DELAY + SETUP + CLOCK_TO_Q);
        }

        /** From an input pin's change to a combinational output's [s]. */
        public double propagationDelay() {
            return Math.max(1, combinationalDepth) * GATE_DELAY;
        }

        /** Energy one clock edge switches inside, at supply voltage v [J] (about a sixth of the logic toggles). */
        public double energyPerEdge(double v) {
            return (gates + 8.0 * flipFlops) / 6.0 * GATE_CAPACITANCE * v * v;
        }

        /** Leakage current at supply v [A]. */
        public double leakage(double v) {
            return (gates + 8.0 * flipFlops + memoryBits) * GATE_LEAKAGE * Math.max(0, v) / 5;
        }

        static Estimate of(ChipDesign d) {
            int gates = 0, ff = 0, mem = 0, regDepth = 0, combDepth = 0;
            for (Signal s : d.signals) {
                if (s.kind == Kind.REG || s.kind == Kind.OUTPUT && s.registered) ff += s.width;
                if (s.kind == Kind.MEM) {
                    mem += s.width * s.depth;
                    gates += 2 * s.depth + s.width; // address decoding, sense
                }
                if (s.expr != null) {
                    gates += s.expr.gates();
                    if (s.kind == Kind.OUTPUT) combDepth = Math.max(combDepth, s.expr.depth());
                }
            }
            for (Process p : d.processes) {
                int[] gd = p.cost();
                gates += gd[0];
                regDepth = Math.max(regDepth, gd[1]);
            }
            return new Estimate(gates, ff, mem, Math.max(1, regDepth), combDepth);
        }
    }

    // ------------------------------------------------------------------ running

    /** A chip's state: its pins, registers and memories. Not thread-safe (each chip is stepped by one thread). */
    public final class Instance {
        private final long[] values = new long[signals.size()];
        private final long[][] mems = new long[signals.size()][];
        private final long[] pending = new long[signals.size()], pendingMask = new long[signals.size()];
        private final boolean[] dirty = new boolean[signals.size()];
        private final List<long[]> memWrites = new ArrayList<>();
        private final long[] lastOut;
        private final double[] lastEdge = new double[processes.size()];
        /** Cache of wires' values for the current evaluation (an epoch per evaluation). */
        private final long[] wireVal = new long[signals.size()];
        private final int[] wireEpoch = new int[signals.size()];
        private int epoch = 1;
        private long edges, violations;
        /** How much slower its logic settles than the design's estimate (its process: {@link ChipProcess}). */
        private double slowdown = 1;

        /** Sets how much slower than the estimate its logic settles (a clock faster than that is a violation). */
        public void slowdown(double s) {
            slowdown = s;
        }

        Instance(Map<String, Double> params) {
            for (Signal s : signals) {
                switch (s.kind) {
                    case REG -> values[s.index] = s.init & s.mask();
                    case OUTPUT -> values[s.index] = s.registered ? s.init & s.mask() : 0;
                    case PARAM -> values[s.index] = params != null && params.containsKey(s.name) ? Math.round(params.get(s.name)) : s.init;
                    case MEM -> {
                        mems[s.index] = new long[s.depth];
                        if (s.memInit != null) for (int i = 0; i < Math.min(s.depth, s.memInit.length); i++) mems[s.index][i] = s.memInit[i] & s.mask();
                    }
                    default -> { }
                }
            }
            java.util.Arrays.fill(lastEdge, Double.NEGATIVE_INFINITY);
            lastOut = new long[outputs.size()];
            epoch++;
            for (int k = 0; k < outputs.size(); k++) lastOut[k] = outputValue(outputs.get(k));
        }

        public ChipDesign design() {
            return ChipDesign.this;
        }

        /** Input pin k's level. */
        public boolean input(int k) {
            return ((values[inPort[k]] >>> inBit[k]) & 1) != 0;
        }

        /** Output pin k's level now. */
        public boolean output(int k) {
            int port = outPort[k];
            Signal s = signals.get(port);
            return ((outputValueCached(s) >>> outBit[k]) & 1) != 0;
        }

        private long outputValueCached(Signal s) {
            return lastOut[outputs.indexOf(s)];
        }

        /**
         * Sets input pin k at time t [s]. A rising (or falling) edge on a clock runs the processes on it. Returns the
         * output pins whose level changed, as a bit set over the output pins (null if none).
         */
        public java.util.BitSet setInput(int k, boolean level, double t) {
            int port = inPort[k], bit = inBit[k];
            long old = values[port], now = level ? old | (1L << bit) : old & ~(1L << bit);
            if (old == now) return null;
            values[port] = now;
            Signal sig = signals.get(port);
            boolean ran = false;
            if (sig.width == 1) {
                for (int i = 0; i < processes.size(); i++) {
                    Process p = processes.get(i);
                    if (p.clock != sig || p.rising != level) continue;
                    run(i, p, t);
                    ran = true;
                }
            }
            if (ran) commit();
            return changedOutputs();
        }

        private void run(int i, Process p, double t) {
            edges++;
            double period = t - lastEdge[i];
            lastEdge[i] = t;
            epoch++;
            // a clock faster than the logic settles: the registers miss their setup and hold (counted)
            if (period < slowdown / estimate.maxClock()) {
                violations++;
                return;
            }
            for (Stmt s : p.body) s.exec(this);
        }

        private void commit() {
            for (int i = 0; i < values.length; i++) {
                if (!dirty[i]) continue;
                dirty[i] = false;
                Signal s = signals.get(i);
                values[i] = ((values[i] & ~pendingMask[i]) | (pending[i] & pendingMask[i])) & s.mask();
                pendingMask[i] = 0;
            }
            for (long[] w : memWrites) {
                Signal s = signals.get((int) w[0]);
                long[] m = mems[s.index];
                int a = (int) w[1];
                if (a >= 0 && a < m.length) m[a] = w[2] & s.mask();
            }
            memWrites.clear();
        }

        private java.util.BitSet changedOutputs() {
            epoch++;
            java.util.BitSet ch = null;
            int pin = 0;
            for (int k = 0; k < outputs.size(); k++) {
                Signal s = outputs.get(k);
                long v = outputValue(s);
                long diff = v ^ lastOut[k];
                if (diff != 0) {
                    if (ch == null) ch = new java.util.BitSet();
                    for (int b = 0; b < s.width; b++) if (((diff >>> b) & 1) != 0) ch.set(pin + b);
                    lastOut[k] = v;
                }
                pin += s.width;
            }
            return ch;
        }

        private long outputValue(Signal s) {
            return s.registered || s.expr == null ? values[s.index] : s.expr.eval(this) & s.mask();
        }

        long read(Signal s) {
            if (s.kind == Kind.WIRE || s.kind == Kind.OUTPUT && !s.registered && s.expr != null) {
                if (wireEpoch[s.index] == epoch) return wireVal[s.index];
                long v = s.expr.eval(this) & s.mask();
                wireEpoch[s.index] = epoch;
                wireVal[s.index] = v;
                return v;
            }
            return values[s.index];
        }

        long readMem(Signal s, long addr) {
            long[] m = mems[s.index];
            return addr >= 0 && addr < m.length ? m[(int) addr] : 0;
        }

        void write(Signal s, long value, long mask) {
            int i = s.index;
            pending[i] = (pending[i] & ~mask) | (value & mask);
            pendingMask[i] |= mask;
            dirty[i] = true;
        }

        void writeMem(Signal s, long addr, long value) {
            memWrites.add(new long[]{s.index, addr, value});
        }

        /** A register's, wire's or port's value by name (for probes and tests). */
        public long value(String name) {
            Signal s = byName.get(name);
            if (s == null) throw new IllegalArgumentException("no signal " + name);
            epoch++;
            return read(s);
        }

        /** A memory word (for probes and tests). */
        public long memory(String name, int addr) {
            Signal s = byName.get(name);
            return s == null || s.kind != Kind.MEM ? 0 : readMem(s, addr);
        }

        /** Clock edges seen, and those that came faster than the logic settles. */
        public long edges() {
            return edges;
        }

        public long violations() {
            return violations;
        }

        /** The state as numbers (for saving): registers, registered outputs and memories, in declaration order. */
        public long[] save() {
            List<Long> out = new ArrayList<>();
            for (Signal s : signals) {
                if (s.kind == Kind.REG || s.kind == Kind.OUTPUT && s.registered) out.add(values[s.index]);
                if (s.kind == Kind.MEM) for (long w : mems[s.index]) out.add(w);
            }
            return out.stream().mapToLong(Long::longValue).toArray();
        }

        /** Restores what {@link #save} gave (ignored if it does not fit the design). */
        public void restore(long[] state) {
            int n = 0;
            for (Signal s : signals) {
                if (s.kind == Kind.REG || s.kind == Kind.OUTPUT && s.registered) n++;
                if (s.kind == Kind.MEM) n += s.depth;
            }
            if (state == null || state.length != n) return;
            int i = 0;
            for (Signal s : signals) {
                if (s.kind == Kind.REG || s.kind == Kind.OUTPUT && s.registered) values[s.index] = state[i++] & s.mask();
                if (s.kind == Kind.MEM) for (int a = 0; a < s.depth; a++) mems[s.index][a] = state[i++] & s.mask();
            }
            epoch++;
            for (int k = 0; k < outputs.size(); k++) lastOut[k] = outputValue(outputs.get(k));
        }
    }

    // ------------------------------------------------------------------ expressions

    interface Expr {
        long eval(Instance in);

        /** Static width [bits] (64 for unsized). */
        int width();

        int gates();

        int depth();
    }

    record Const(long v, int width) implements Expr {
        public long eval(Instance in) { return v; }
        public int gates() { return 0; }
        public int depth() { return 0; }
    }

    record Ref(Signal s) implements Expr {
        public long eval(Instance in) { return in.read(s); }
        public int width() { return s.width; }
        public int gates() { return 0; }
        public int depth() { return s.expr != null ? s.expr.depth() : 0; }
    }

    record Slice(Expr e, int hi, int lo) implements Expr {
        public long eval(Instance in) {
            int w = hi - lo + 1;
            return (e.eval(in) >>> lo) & (w >= 64 ? -1L : (1L << w) - 1);
        }
        public int width() { return hi - lo + 1; }
        public int gates() { return e.gates(); }
        public int depth() { return e.depth(); }
    }

    record BitSelect(Expr e, Expr index) implements Expr {
        public long eval(Instance in) {
            long i = index.eval(in);
            return i < 0 || i > 63 ? 0 : (e.eval(in) >>> i) & 1;
        }
        public int width() { return 1; }
        public int gates() { return e.gates() + index.gates() + 3 * e.width(); }
        public int depth() { return Math.max(e.depth(), index.depth()) + 2 * log2(e.width()); }
    }

    record MemRead(Signal m, Expr addr) implements Expr {
        public long eval(Instance in) { return in.readMem(m, addr.eval(in)); }
        public int width() { return m.width; }
        public int gates() { return addr.gates(); }
        public int depth() { return addr.depth() + log2(m.depth) + 2; }
    }

    record Unary(char op, Expr e) implements Expr {
        public long eval(Instance in) {
            long v = e.eval(in);
            return switch (op) {
                case '~' -> ~v & mask(e.width());
                case '!' -> v == 0 ? 1 : 0;
                case '-' -> (-v) & mask(e.width());
                case '&' -> v == mask(e.width()) ? 1 : 0; // reduction AND
                case '|' -> v != 0 ? 1 : 0;
                case '^' -> Long.bitCount(v) & 1;
                default -> throw new IllegalStateException();
            };
        }
        public int width() { return op == '~' || op == '-' ? e.width() : 1; }
        public int gates() {
            int w = Math.min(64, e.width());
            return e.gates() + (op == '-' ? 5 * w : w);
        }
        public int depth() {
            int w = Math.min(64, e.width());
            return e.depth() + (op == '~' ? 1 : op == '-' ? 2 * w : log2(w) + 1);
        }
    }

    record Binary(String op, Expr a, Expr b) implements Expr {
        public long eval(Instance in) {
            long x = a.eval(in), y = b.eval(in);
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? -1L : Long.divideUnsigned(x, y);
                case "%" -> y == 0 ? x : Long.remainderUnsigned(x, y);
                case "&" -> x & y;
                case "|" -> x | y;
                case "^" -> x ^ y;
                case "<<" -> y >= 64 ? 0 : x << y;
                case ">>" -> y >= 64 ? 0 : x >>> y;
                case "==" -> x == y ? 1 : 0;
                case "!=" -> x != y ? 1 : 0;
                case "<" -> Long.compareUnsigned(x, y) < 0 ? 1 : 0;
                case "<=" -> Long.compareUnsigned(x, y) <= 0 ? 1 : 0;
                case ">" -> Long.compareUnsigned(x, y) > 0 ? 1 : 0;
                case ">=" -> Long.compareUnsigned(x, y) >= 0 ? 1 : 0;
                case "&&" -> x != 0 && y != 0 ? 1 : 0;
                case "||" -> x != 0 || y != 0 ? 1 : 0;
                default -> throw new IllegalStateException(op);
            };
        }

        public int width() {
            return switch (op) {
                case "==", "!=", "<", "<=", ">", ">=", "&&", "||" -> 1;
                default -> Math.max(a.width(), b.width());
            };
        }

        private int w() {
            int w = Math.max(sized(a), sized(b));
            return Math.min(64, w);
        }

        public int gates() {
            int w = w(), g = a.gates() + b.gates();
            return g + switch (op) {
                case "+", "-" -> 5 * w;
                case "*" -> 6 * w * w;
                case "/", "%" -> 8 * w * w;
                case "&", "|", "^" -> w;
                case "<<", ">>" -> b instanceof Const ? 0 : 3 * w * log2(w);
                case "==", "!=" -> 2 * w;
                case "<", "<=", ">", ">=" -> 5 * w;
                default -> 1;
            };
        }

        public int depth() {
            int w = w(), d = Math.max(a.depth(), b.depth());
            return d + switch (op) {
                case "+", "-", "<", "<=", ">", ">=" -> 2 * w;
                case "*" -> 4 * w;
                case "/", "%" -> 2 * w * w;
                case "&", "|", "^", "&&", "||" -> 1;
                case "<<", ">>" -> b instanceof Const ? 0 : 2 * log2(w);
                case "==", "!=" -> 1 + log2(w);
                default -> 1;
            };
        }
    }

    record Ternary(Expr c, Expr a, Expr b) implements Expr {
        public long eval(Instance in) { return c.eval(in) != 0 ? a.eval(in) : b.eval(in); }
        public int width() { return Math.max(a.width(), b.width()); }
        public int gates() { return c.gates() + a.gates() + b.gates() + 3 * Math.min(64, Math.max(sized(a), sized(b))); }
        public int depth() { return Math.max(c.depth(), Math.max(a.depth(), b.depth())) + 2; }
    }

    record Concat(List<Expr> parts) implements Expr {
        public long eval(Instance in) {
            long v = 0;
            for (Expr e : parts) v = (v << e.width()) | (e.eval(in) & mask(e.width()));
            return v;
        }
        public int width() { return Math.min(64, parts.stream().mapToInt(Expr::width).sum()); }
        public int gates() { return parts.stream().mapToInt(Expr::gates).sum(); }
        public int depth() { return parts.stream().mapToInt(Expr::depth).max().orElse(0); }
    }

    /** A width for estimating: an unsized constant counts as narrow as its value. */
    static int sized(Expr e) {
        if (e instanceof Const c && c.width() >= 64) return Math.max(1, 64 - Long.numberOfLeadingZeros(c.v()));
        return Math.min(64, e.width());
    }

    static long mask(int w) {
        return w >= 64 ? -1L : (1L << w) - 1;
    }

    static int log2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    // ------------------------------------------------------------------ statements

    interface Stmt {
        void exec(Instance in);

        /** {gates, depth} of the logic choosing what is written (muxes along conditions). */
        int[] cost();
    }

    record Assign(Signal target, int hi, int lo, Expr bit, Expr memAddr, Expr value) implements Stmt {
        public void exec(Instance in) {
            long v = value.eval(in);
            if (target.kind == Kind.MEM) {
                in.writeMem(target, memAddr.eval(in), v);
            } else if (bit != null) {
                long i = bit.eval(in);
                if (i >= 0 && i < target.width) in.write(target, (v & 1) << i, 1L << i);
            } else if (hi >= 0) {
                long m = mask(hi - lo + 1) << lo;
                in.write(target, v << lo, m);
            } else {
                in.write(target, v, target.mask());
            }
        }

        public int[] cost() {
            int g = value.gates() + (memAddr != null ? memAddr.gates() : 0) + (bit != null ? bit.gates() + 3 * target.width : 0);
            int d = value.depth();
            if (memAddr != null) d = Math.max(d, memAddr.depth() + log2(target.depth) + 1);
            return new int[]{g, d};
        }
    }

    record If(Expr cond, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
        public void exec(Instance in) {
            for (Stmt s : cond.eval(in) != 0 ? then : otherwise) s.exec(in);
        }

        public int[] cost() {
            int[] a = Process.costOf(then), b = Process.costOf(otherwise);
            int width = 8; // a mux per written bit: an average register's
            return new int[]{cond.gates() + a[0] + b[0] + 3 * width, Math.max(cond.depth(), Math.max(a[1], b[1])) + 2};
        }
    }

    record Case(Expr subject, List<long[]> labels, List<List<Stmt>> bodies, List<Stmt> otherwise) implements Stmt {
        public void exec(Instance in) {
            long v = subject.eval(in);
            for (int i = 0; i < labels.size(); i++) {
                for (long l : labels.get(i)) {
                    if (l == v) {
                        for (Stmt s : bodies.get(i)) s.exec(in);
                        return;
                    }
                }
            }
            for (Stmt s : otherwise) s.exec(in);
        }

        public int[] cost() {
            int g = subject.gates(), d = 0;
            for (List<Stmt> b : bodies) {
                int[] c = Process.costOf(b);
                g += c[0] + 2 * sized(subject) + 3 * 8;
                d = Math.max(d, c[1]);
            }
            int[] o = Process.costOf(otherwise);
            g += o[0];
            d = Math.max(d, o[1]);
            return new int[]{g, Math.max(d, subject.depth() + 1 + log2(Math.max(1, sized(subject)))) + 2 * log2(bodies.size() + 1)};
        }
    }

    record Process(Signal clock, boolean rising, List<Stmt> body) {
        int[] cost() {
            return costOf(body);
        }

        static int[] costOf(List<Stmt> body) {
            int g = 0, d = 0;
            for (Stmt s : body) {
                int[] c = s.cost();
                g += c[0];
                d = Math.max(d, c[1]);
            }
            return new int[]{g, d};
        }
    }

    // ------------------------------------------------------------------ lexer and parser

    private record Tok(String t, int line, int col, char type) { } // type: i ident, n number, s symbol, e end

    static final class Parser {
        private final List<Tok> toks = new ArrayList<>();
        private int pos;
        String chipName = "chip";
        final List<Signal> signals = new ArrayList<>();
        final Map<String, Signal> byName = new HashMap<>();
        final List<Process> processes = new ArrayList<>();

        Parser(String src) throws ChipError {
            lex(src);
        }

        private void lex(String s) throws ChipError {
            int i = 0, line = 1, col = 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    col = 1;
                    i++;
                    continue;
                }
                if (Character.isWhitespace(c) || c == ';') {
                    i++;
                    col++;
                    continue;
                }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') i++;
                    continue;
                }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int end = s.indexOf("*/", i + 2);
                    if (end < 0) throw new ChipError("unclosed comment", line, col);
                    for (int k = i; k < end; k++) if (s.charAt(k) == '\n') { line++; col = 1; }
                    i = end + 2;
                    continue;
                }
                int start = i, startCol = col;
                if (Character.isLetter(c) || c == '_') {
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                    toks.add(new Tok(s.substring(start, i), line, startCol, 'i'));
                } else if (Character.isDigit(c)) {
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '\'')) i++;
                    toks.add(new Tok(s.substring(start, i).replace("_", ""), line, startCol, 'n'));
                } else {
                    String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                    if (List.of("<=", ">=", "==", "!=", "&&", "||", "<<", ">>").contains(two)) {
                        toks.add(new Tok(two, line, startCol, 's'));
                        i += 2;
                    } else if ("{}()[],:=<>+-*/%&|^~!?@".indexOf(c) >= 0) {
                        toks.add(new Tok(String.valueOf(c), line, startCol, 's'));
                        i++;
                    } else {
                        throw new ChipError("unexpected character '" + c + "'", line, col);
                    }
                }
                col += i - start;
            }
            toks.add(new Tok("", line, col, 'e'));
        }

        private Tok peek() {
            return toks.get(pos);
        }

        private Tok next() {
            return toks.get(pos++);
        }

        private boolean is(String t) {
            return peek().t().equals(t) && peek().type() != 'e';
        }

        private boolean accept(String t) {
            if (is(t)) {
                pos++;
                return true;
            }
            return false;
        }

        private ChipError err(String msg) {
            Tok t = peek();
            return new ChipError(msg + (t.type() == 'e' ? " (at the end)" : " (at '" + t.t() + "')"), t.line(), t.col());
        }

        private Tok expect(String t) throws ChipError {
            if (!is(t)) throw err("expected '" + t + "'");
            return next();
        }

        private String ident() throws ChipError {
            if (peek().type() != 'i') throw err("expected a name");
            return next().t();
        }

        void parse() throws ChipError {
            if (accept("chip")) chipName = ident();
            while (peek().type() != 'e') decl();
            // outputs neither assigned nor written: an error a designer wants to know of
            for (Signal s : signals) {
                if (s.kind == Kind.OUTPUT && s.expr == null && !s.registered)
                    throw new ChipError("output '" + s.name + "' is never driven (assign it, or write it in an on block)", 1, 1);
            }
            checkLoops();
        }

        private Signal declare(String name, Kind kind, int width) throws ChipError {
            if (byName.containsKey(name)) throw err("'" + name + "' is declared twice");
            if (List.of("chip", "in", "out", "input", "output", "reg", "wire", "mem", "param", "assign", "on", "rise", "fall",
                    "if", "else", "case", "default").contains(name)) throw err("'" + name + "' is a keyword");
            if (width < 1 || width > 64) throw err("widths are 1 to 64 bits");
            Signal s = new Signal(name, kind, width, signals.size());
            signals.add(s);
            byName.put(name, s);
            return s;
        }

        private int range() throws ChipError {
            if (!accept("[")) return 1;
            int hi = (int) constant(), lo;
            expect(":");
            lo = (int) constant();
            expect("]");
            if (lo != 0 || hi < 0) throw err("a range is written [msb:0]");
            return hi + 1;
        }

        private long constant() throws ChipError {
            Expr e = expr();
            if (!(e instanceof Const c)) throw err("expected a constant");
            return c.v();
        }

        private void decl() throws ChipError {
            Tok t = peek();
            switch (t.t()) {
                case "in", "input" -> {
                    next();
                    do {
                        int w = range();
                        declare(ident(), Kind.INPUT, w);
                    } while (accept(","));
                }
                case "out", "output" -> {
                    next();
                    do {
                        int w = range();
                        Signal s = declare(ident(), Kind.OUTPUT, w);
                        if (accept("=")) s.init = constant();
                    } while (accept(","));
                }
                case "reg" -> {
                    next();
                    int w = range();
                    do {
                        Signal s = declare(ident(), Kind.REG, w);
                        if (accept("=")) s.init = constant();
                    } while (accept(","));
                }
                case "wire" -> {
                    next();
                    int w = range();
                    Signal s = declare(ident(), Kind.WIRE, w);
                    expect("=");
                    s.expr = expr();
                }
                case "param" -> {
                    next();
                    do {
                        Signal s = declare(ident(), Kind.PARAM, 64);
                        expect("=");
                        s.init = constant();
                    } while (accept(","));
                }
                case "mem" -> {
                    next();
                    int w = range();
                    Signal s = declare(ident(), Kind.MEM, w);
                    expect("[");
                    s.depth = (int) constant();
                    expect("]");
                    if (s.depth < 1 || s.depth > 65536) throw err("a memory holds 1 to 65536 words");
                    if (accept("=")) {
                        expect("{");
                        List<Long> init = new ArrayList<>();
                        if (!is("}")) do init.add(constant()); while (accept(","));
                        expect("}");
                        if (init.size() > s.depth) throw err("more initial words than the memory holds");
                        s.memInit = init.stream().mapToLong(Long::longValue).toArray();
                    }
                }
                case "assign" -> {
                    next();
                    String n = ident();
                    Signal s = byName.get(n);
                    if (s == null) throw err("no such output '" + n + "'");
                    if (s.kind != Kind.OUTPUT) throw err("assign drives an output; '" + n + "' is not one (use wire)");
                    if (s.expr != null || s.registered) throw err("output '" + n + "' is driven twice");
                    expect("=");
                    s.expr = expr();
                }
                case "on" -> {
                    next();
                    boolean rising = true;
                    if (accept("fall")) rising = false;
                    else accept("rise");
                    String n = ident();
                    Signal clk = byName.get(n);
                    if (clk == null || clk.kind != Kind.INPUT || clk.width != 1) throw err("a clock is a one-bit input");
                    processes.add(new Process(clk, rising, block()));
                }
                default -> throw err("expected a declaration (in, out, reg, wire, mem, param, assign, on)");
            }
        }

        private List<Stmt> block() throws ChipError {
            List<Stmt> out = new ArrayList<>();
            if (accept("{")) {
                while (!accept("}")) {
                    if (peek().type() == 'e') throw err("unclosed '{'");
                    out.add(stmt());
                }
            } else {
                out.add(stmt());
            }
            return out;
        }

        private Stmt stmt() throws ChipError {
            if (accept("if")) {
                expect("(");
                Expr c = expr();
                expect(")");
                List<Stmt> a = block(), b = List.of();
                if (accept("else")) b = block();
                return new If(c, a, b);
            }
            if (accept("case")) {
                expect("(");
                Expr subj = expr();
                expect(")");
                expect("{");
                List<long[]> labels = new ArrayList<>();
                List<List<Stmt>> bodies = new ArrayList<>();
                List<Stmt> def = List.of();
                while (!accept("}")) {
                    if (accept("default")) {
                        expect(":");
                        def = block();
                        continue;
                    }
                    List<Long> ls = new ArrayList<>();
                    do ls.add(constant()); while (accept(","));
                    expect(":");
                    labels.add(ls.stream().mapToLong(Long::longValue).toArray());
                    bodies.add(block());
                }
                return new Case(subj, labels, bodies, def);
            }
            String n = ident();
            Signal s = byName.get(n);
            if (s == null) throw err("no such register '" + n + "'");
            int hi = -1, lo = -1;
            Expr bit = null, addr = null;
            if (s.kind == Kind.MEM) {
                expect("[");
                addr = expr();
                expect("]");
            } else {
                if (s.kind == Kind.OUTPUT) {
                    if (s.expr != null) throw err("output '" + n + "' is assigned combinationally; it cannot also be written here");
                    s.registered = true;
                } else if (s.kind != Kind.REG) {
                    throw err("'" + n + "' cannot be written (only registers, outputs and memories)");
                }
                if (accept("[")) {
                    Expr a = expr();
                    if (accept(":")) {
                        if (!(a instanceof Const ca)) throw err("a slice's bounds are constants");
                        hi = (int) ca.v();
                        lo = (int) constant();
                        if (hi < lo || hi >= s.width) throw err("slice out of range");
                    } else {
                        bit = a;
                    }
                    expect("]");
                }
            }
            if (is("=")) throw err("in an on block registers are written with '<=' (they all change together at the edge)");
            expect("<=");
            return new Assign(s, hi, lo, bit, addr, expr());
        }

        // precedence climbing: ?: || && | ^ & ==,!= <,<=,>,>= <<,>> +,- *,/,% unary
        private Expr expr() throws ChipError {
            Expr c = binary(0);
            if (accept("?")) {
                Expr a = expr();
                expect(":");
                Expr b = expr();
                return fold(new Ternary(c, a, b));
            }
            return c;
        }

        private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", "<=", ">", ">="},
                {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}};

        private Expr binary(int level) throws ChipError {
            if (level == LEVELS.length) return unary();
            Expr a = binary(level + 1);
            while (true) {
                String op = null;
                for (String o : LEVELS[level]) if (is(o)) op = o;
                if (op == null) return a;
                next();
                a = fold(new Binary(op, a, binary(level + 1)));
            }
        }

        private Expr unary() throws ChipError {
            for (String o : new String[]{"~", "!", "-", "&", "|", "^"}) {
                if (accept(o)) return fold(new Unary(o.charAt(0), unary()));
            }
            return postfix(primary());
        }

        private Expr primary() throws ChipError {
            Tok t = peek();
            if (accept("(")) {
                Expr e = expr();
                expect(")");
                return e;
            }
            if (accept("{")) {
                List<Expr> parts = new ArrayList<>();
                do {
                    Expr e = expr();
                    if (e.width() >= 64) throw err("a concatenation's parts need widths (write constants sized: 4'd3)");
                    parts.add(e);
                } while (accept(","));
                expect("}");
                return fold(new Concat(parts));
            }
            if (t.type() == 'n') {
                next();
                return number(t);
            }
            if (t.type() == 'i') {
                next();
                Signal s = byName.get(t.t());
                if (s == null) throw new ChipError("no such signal '" + t.t() + "' (declare it before using it)", t.line(), t.col());
                if (s.kind == Kind.MEM) {
                    expect("[");
                    Expr a = expr();
                    expect("]");
                    return new MemRead(s, a);
                }
                if (s.kind == Kind.PARAM) return new ParamRef(s);
                return new Ref(s);
            }
            throw err("expected a value");
        }

        private Expr postfix(Expr e) throws ChipError {
            while (accept("[")) {
                Expr a = expr();
                if (accept(":")) {
                    if (!(a instanceof Const ca)) throw err("a slice's bounds are constants");
                    int hi = (int) ca.v(), lo = (int) constant();
                    expect("]");
                    if (hi < lo || hi > 63) throw err("slice out of range");
                    e = fold(new Slice(e, hi, lo));
                } else {
                    expect("]");
                    e = fold(new BitSelect(e, a));
                }
            }
            return e;
        }

        private Expr number(Tok t) throws ChipError {
            String s = t.t().toLowerCase();
            try {
                int q = s.indexOf('\'');
                if (q > 0) {
                    int w = Integer.parseInt(s.substring(0, q));
                    if (w < 1 || w > 64) throw new ChipError("a sized literal is 1 to 64 bits", t.line(), t.col());
                    char base = s.charAt(q + 1);
                    String digits = s.substring(q + 2);
                    long v = switch (base) {
                        case 'b' -> Long.parseUnsignedLong(digits, 2);
                        case 'h' -> Long.parseUnsignedLong(digits, 16);
                        case 'o' -> Long.parseUnsignedLong(digits, 8);
                        case 'd' -> Long.parseUnsignedLong(digits);
                        default -> throw new ChipError("a sized literal's base is b, o, d or h", t.line(), t.col());
                    };
                    return new Const(v & mask(w), w);
                }
                if (s.startsWith("0x")) return new Const(Long.parseUnsignedLong(s.substring(2), 16), 64);
                if (s.startsWith("0b")) return new Const(Long.parseUnsignedLong(s.substring(2), 2), 64);
                return new Const(Long.parseUnsignedLong(s), 64);
            } catch (NumberFormatException e) {
                throw new ChipError("not a number: " + t.t(), t.line(), t.col());
            }
        }

        /** Folds an expression of constants to a constant (parameters are not constants: a chip may set them). */
        private Expr fold(Expr e) {
            if (e instanceof Const) return e;
            boolean allConst = switch (e) {
                case Unary u -> u.e() instanceof Const;
                case Binary b -> b.a() instanceof Const && b.b() instanceof Const;
                case Ternary t -> t.c() instanceof Const && t.a() instanceof Const && t.b() instanceof Const;
                case Slice sl -> sl.e() instanceof Const;
                case BitSelect bs -> bs.e() instanceof Const && bs.index() instanceof Const;
                case Concat c -> c.parts().stream().allMatch(p -> p instanceof Const);
                default -> false;
            };
            if (!allConst) return e;
            long v = e.eval(null);
            return new Const(v, e.width());
        }

        /** Combinational loops among wires and assigned outputs are errors (a latch nobody meant). */
        private void checkLoops() throws ChipError {
            Map<Signal, Integer> state = new HashMap<>();
            for (Signal s : signals) if (s.expr != null) visit(s, state, new ArrayList<>());
        }

        private void visit(Signal s, Map<Signal, Integer> state, List<String> path) throws ChipError {
            Integer st = state.get(s);
            if (st != null && st == 2) return;
            if (st != null && st == 1) {
                path.add(s.name);
                throw new ChipError("combinational loop: " + String.join(" -> ", path), 1, 1);
            }
            state.put(s, 1);
            path.add(s.name);
            for (Signal d : refs(s.expr)) if (d.expr != null && (d.kind == Kind.WIRE || d.kind == Kind.OUTPUT && !d.registered)) visit(d, state, path);
            path.remove(path.size() - 1);
            state.put(s, 2);
        }

        private static List<Signal> refs(Expr e) {
            List<Signal> out = new ArrayList<>();
            collect(e, out);
            return out;
        }

        private static void collect(Expr e, List<Signal> out) {
            switch (e) {
                case Ref r -> out.add(r.s());
                case Slice sl -> collect(sl.e(), out);
                case BitSelect b -> {
                    collect(b.e(), out);
                    collect(b.index(), out);
                }
                case MemRead m -> collect(m.addr(), out);
                case Unary u -> collect(u.e(), out);
                case Binary b -> {
                    collect(b.a(), out);
                    collect(b.b(), out);
                }
                case Ternary t -> {
                    collect(t.c(), out);
                    collect(t.a(), out);
                    collect(t.b(), out);
                }
                case Concat c -> c.parts().forEach(p -> collect(p, out));
                default -> { }
            }
        }
    }

    record ParamRef(Signal s) implements Expr {
        public long eval(Instance in) { return in == null ? s.init : in.values[s.index]; }
        /** (its default's: a part setting it wider may overflow a ~ or a concatenation, as a narrower wire would) */
        public int width() { return Math.max(1, 64 - Long.numberOfLeadingZeros(s.init)); }
        public int gates() { return 0; }
        public int depth() { return 0; }
    }
}
