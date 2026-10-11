package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Medium-scale-integration CMOS chip (74HC-class counter, register, adder, programmable ROM): the building
 * blocks of a small computer, at the same reduced-order fidelity as {@link LogicGate} and with the same
 * physics at the pins:
 * <ul>
 *   <li>inputs read as logic levels through the CMOS input threshold (sigmoid about V_DD/2, 3 % wide; an
 *       unpowered chip reads low);</li>
 *   <li>each output is a pull-up/pull-down pair of 50 Ω on-resistances weighted by an internal state that
 *       relaxes towards its target with the propagation delay, so output current comes from the supply and
 *       heats the chip;</li>
 *   <li>registers are master–slave flip-flops on one clock with the non-overlapping transparency windows and
 *       latch regeneration of {@link LogicGate}'s D flip-flop (rising-edge triggered, race-free).</li>
 * </ul>
 * The internal logic is the <b>multilinear extension</b> of each Boolean function — exact at every logic
 * corner and smooth between them, so the Newton solver sees continuous derivatives while signals switch. The
 * functions:
 * <ul>
 *   <li>{@link Type#COUNTER4} (74HC163): synchronous 4-bit counter. On a rising clock: CLR̄ low clears, else
 *       LOAD̄ low loads P0–P3, else EN high counts up. TC = Q0·Q1·Q2·Q3·EN (ripple-carry out).</li>
 *   <li>{@link Type#REGISTER4} (74HC173-like): 4-bit register loading D0–D3 on a rising clock while EN is high.</li>
 *   <li>{@link Type#ADDER4} (74HC283): S = A + B + C_in as a ripple of full adders, C_out the carry.</li>
 *   <li>{@link Type#ROM16X8}: 16 words of 8 bits, contents fixed when the chip is made (a mask/fuse PROM —
 *       the program); D0–D7 show the word at address A0–A3.</li>
 *   <li>{@link Type#RAM16X4}: 16 words of 4 bits of static memory, 64 flip-flops (synchronous write): on a rising
 *       clock while WE is high, D0–D3 are stored in the word at A0–A3; Q0–Q3 always show the word addressed. The
 *       address decoding is the multilinear product of the address levels, so selection, data and the stored bits
 *       stay smooth for the solver.</li>
 * </ul>
 */
public final class MsiChip implements Element {
    public enum Type {
        COUNTER4(new String[]{"P0", "P1", "P2", "P3", "CLK", "CLR", "LOAD", "EN"}, new String[]{"Q0", "Q1", "Q2", "Q3", "TC"}, 4),
        REGISTER4(new String[]{"D0", "D1", "D2", "D3", "CLK", "EN"}, new String[]{"Q0", "Q1", "Q2", "Q3"}, 4),
        ADDER4(new String[]{"A0", "A1", "A2", "A3", "B0", "B1", "B2", "B3", "CIN"}, new String[]{"S0", "S1", "S2", "S3", "COUT"}, 0),
        ROM16X8(new String[]{"A0", "A1", "A2", "A3"}, new String[]{"D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7"}, 0),
        RAM16X4(new String[]{"A0", "A1", "A2", "A3", "D0", "D1", "D2", "D3", "WE", "CLK"}, new String[]{"Q0", "Q1", "Q2", "Q3"}, 64);

        /** Input and output pin names, in package order. */
        public final String[] inputs, outputs;
        public final int flipFlops;

        Type(String[] inputs, String[] outputs, int flipFlops) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.flipFlops = flipFlops;
        }

        /** Package length in pin pairs: inputs on one side, outputs on the other, power on the last pair. */
        public int length() {
            return Math.max(inputs.length, outputs.length) + 1;
        }

        int clockPin() {
            for (int i = 0; i < inputs.length; i++) if (inputs[i].equals("CLK")) return i;
            return -1;
        }
    }

    private final Type type;
    private final Node[] in, out;
    private final Node vdd, gnd, thermal;
    private final double tpd, gOn;
    private final int[] rom;
    private final int nIn, nOut, nF, clk;
    private int[] oIdx, oSlot, mIdx, mSlot, sIdx, sSlot;

    /**
     * @param inputs  input pin nodes in {@link Type#inputs} order (a null pin is left open and reads low)
     * @param outputs output pin nodes in {@link Type#outputs} order (null = not connected)
     * @param rom     ROM contents (16 words, low 8 bits used), or null
     */
    public MsiChip(Type type, Node[] inputs, Node[] outputs, Node vdd, Node gnd, int[] rom, double propagationDelay,
                   double outputResistance, Node thermal) {
        if (inputs.length != type.inputs.length || outputs.length != type.outputs.length) {
            throw new IllegalArgumentException(type + " has " + type.inputs.length + " inputs and " + type.outputs.length + " outputs");
        }
        this.type = type;
        this.in = inputs.clone();
        this.out = outputs.clone();
        this.vdd = vdd;
        this.gnd = gnd;
        this.thermal = thermal;
        this.tpd = propagationDelay;
        this.gOn = 1 / outputResistance;
        this.rom = rom == null ? new int[16] : java.util.Arrays.copyOf(rom, 16);
        this.nIn = type.inputs.length;
        this.nOut = type.outputs.length;
        this.nF = type.flipFlops;
        this.clk = type.clockPin();
        int nq = Math.max(nF, 1), no = Math.max(nOut, 1);
        qs = new double[nq];
        dd = new double[nq];
        dp = new double[nq];
        tt = new double[no];
        tp = new double[no];
    }

    public Type type() {
        return type;
    }

    @Override
    public void setup(SetupContext ctx) {
        oIdx = new int[nOut];
        oSlot = new int[nOut];
        for (int j = 0; j < nOut; j++) {
            oIdx[j] = ctx.allocUnknown(type + "." + type.outputs[j], 1e-6);
            ctx.setInitial(oIdx[j], 0);
            ctx.limitNewtonStep(oIdx[j], 0.4);
            oSlot[j] = ctx.allocState(0.02);
        }
        mIdx = new int[nF];
        mSlot = new int[nF];
        sIdx = new int[nF];
        sSlot = new int[nF];
        for (int i = 0; i < nF; i++) {
            mIdx[i] = ctx.allocUnknown(type + ".master" + i, 1e-6);
            sIdx[i] = ctx.allocUnknown(type + ".q" + i, 1e-6);
            ctx.limitNewtonStep(mIdx[i], 0.4);
            ctx.limitNewtonStep(sIdx[i], 0.4);
            mSlot[i] = ctx.allocState(0.02);
            sSlot[i] = ctx.allocState(0.02);
        }
    }

    private static double sig(double x) {
        return 1 / (1 + Math.exp(-Math.max(-40, Math.min(40, x))));
    }

    // ------------------------------------------------------------------ logic (multilinear, on levels in [0, 1])

    private static double xor(double a, double b) {
        return a + b - 2 * a * b;
    }

    /** Flip-flop data targets from input levels x and register states q. */
    private void nextState(double[] x, double[] q, double[] d) {
        switch (type) {
            case COUNTER4 -> {
                double clr = x[5], load = x[6], en = x[7];
                double carry = en;
                for (int i = 0; i < 4; i++) {
                    double inc = xor(q[i], carry);
                    carry *= q[i];
                    double v = load * inc + (1 - load) * x[i]; // LOAD̄ low loads P
                    d[i] = clr * v;                            // CLR̄ low clears
                }
            }
            case REGISTER4 -> {
                double en = x[5];
                for (int i = 0; i < 4; i++) d[i] = en * x[i] + (1 - en) * q[i];
            }
            case RAM16X4 -> {
                double we = x[8];
                for (int w = 0; w < 16; w++) {
                    double write = we * select(x, w);
                    for (int k = 0; k < 4; k++) d[4 * w + k] = write * x[4 + k] + (1 - write) * q[4 * w + k];
                }
            }
            default -> {
            }
        }
    }

    /** Output targets from input levels x and register states q. */
    private void outputs(double[] x, double[] q, double[] t) {
        switch (type) {
            case COUNTER4 -> {
                for (int i = 0; i < 4; i++) t[i] = q[i];
                t[4] = q[0] * q[1] * q[2] * q[3] * x[7];
            }
            case REGISTER4 -> {
                for (int i = 0; i < 4; i++) t[i] = q[i];
            }
            case ADDER4 -> {
                double c = x[8];
                for (int i = 0; i < 4; i++) {
                    double a = x[i], b = x[4 + i], p = xor(a, b);
                    t[i] = xor(p, c);
                    c = a * b + c * p; // carry = ab + c(a⊕b) = ab + ac + bc − 2abc, the exact multilinear majority
                }
                t[4] = c;
            }
            case RAM16X4 -> {
                for (int k = 0; k < 4; k++) t[k] = 0;
                for (int w = 0; w < 16; w++) {
                    double sel = select(x, w);
                    for (int k = 0; k < 4; k++) t[k] += sel * q[4 * w + k];
                }
            }
            case ROM16X8 -> {
                for (int k = 0; k < 8; k++) t[k] = 0;
                for (int w = 0; w < 16; w++) {
                    double sel = 1;
                    for (int j = 0; j < 4; j++) sel *= ((w >> j) & 1) == 1 ? x[j] : 1 - x[j];
                    int word = rom[w];
                    for (int k = 0; k < 8; k++) if (((word >> k) & 1) == 1) t[k] += sel;
                }
            }
        }
    }

    /** Multilinear decode of word w from address levels x[0..3]: 1 exactly at its address, 0 at every other. */
    private static double select(double[] x, int w) {
        double sel = 1;
        for (int j = 0; j < 4; j++) sel *= ((w >> j) & 1) == 1 ? x[j] : 1 - x[j];
        return sel;
    }

    // ------------------------------------------------------------------ load

    private final double[] xs = new double[16], dx = new double[16];
    private final double[] qs, dd, tt, tp, dp;

    @Override
    public void load(LoadContext ctx) {
        double vg = ctx.x(gnd.index()), span = Math.max(ctx.x(vdd.index()) - vg, 1.0), w = 0.03 * span;
        for (int i = 0; i < nIn; i++) {
            if (in[i] == null) {
                xs[i] = 0;
                dx[i] = 0;
                continue;
            }
            double s = sig((ctx.x(in[i].index()) - vg - 0.5 * span) / w);
            xs[i] = s;
            dx[i] = s * (1 - s) / w; // ∂level/∂v
        }
        for (int i = 0; i < nF; i++) qs[i] = Math.max(0, Math.min(1, ctx.x(sIdx[i])));
        double h = 1e-6;
        // ---- flip-flops
        if (nF > 0) {
            double c = xs[clk], dc = dx[clk];
            double wm = sig((0.35 - c) / 0.04), dwm = -wm * (1 - wm) / 0.04;
            double ws = sig((c - 0.65) / 0.04), dws = ws * (1 - ws) / 0.04;
            nextState(xs, qs, dd);
            for (int i = 0; i < nF; i++) {
                double m = ctx.x(mIdx[i]), s = ctx.x(sIdx[i]);
                double[] rm = regen(m), rs = regen(s);
                // master: follows its data while the clock is low, regenerates while it is high
                ctx.addF(mIdx[i], tpd * ctx.ddt(mSlot[i], m) - (dd[i] - m) * wm - (rm[0] - m) * (1 - wm));
                ctx.addJ(mIdx[i], mIdx[i], tpd * ctx.alpha() + wm - (rm[1] - 1) * (1 - wm));
                double dmClk = (-(dd[i] - m) + (rm[0] - m)) * dwm * dc;
                ctx.addJ(mIdx[i], in[clk].index(), dmClk);
                ctx.addJ(mIdx[i], gnd.index(), -dmClk);
                // slave: copies the master while the clock is high
                ctx.addF(sIdx[i], tpd * ctx.ddt(sSlot[i], s) - (m - s) * ws - (rs[0] - s) * (1 - ws));
                ctx.addJ(sIdx[i], sIdx[i], tpd * ctx.alpha() + ws - (rs[1] - 1) * (1 - ws));
                ctx.addJ(sIdx[i], mIdx[i], -ws);
                double dsClk = (-(m - s) + (rs[0] - s)) * dws * dc;
                ctx.addJ(sIdx[i], in[clk].index(), dsClk);
                ctx.addJ(sIdx[i], gnd.index(), -dsClk);
            }
            // ∂(data targets)/∂inputs and /∂register states, by finite differences on the smooth logic
            for (int k = 0; k < nIn; k++) {
                if (in[k] == null || k == clk || dx[k] < 1e-12) continue;
                double x0 = xs[k];
                xs[k] = x0 + h;
                nextState(xs, qs, dp);
                xs[k] = x0;
                for (int i = 0; i < nF; i++) {
                    double g = -wm * (dp[i] - dd[i]) / h * dx[k];
                    if (g == 0) continue;
                    ctx.addJ(mIdx[i], in[k].index(), g);
                    ctx.addJ(mIdx[i], gnd.index(), -g);
                }
            }
            for (int k = 0; k < nF; k++) {
                double q0 = qs[k];
                qs[k] = q0 + h;
                nextState(xs, qs, dp);
                qs[k] = q0;
                for (int i = 0; i < nF; i++) {
                    double g = -wm * (dp[i] - dd[i]) / h;
                    if (g != 0) ctx.addJ(mIdx[i], sIdx[k], g);
                }
            }
        }
        // ---- outputs: τ·ds/dt = target − s
        outputs(xs, qs, tt);
        for (int j = 0; j < nOut; j++) {
            double o = ctx.x(oIdx[j]);
            ctx.addF(oIdx[j], tpd * ctx.ddt(oSlot[j], o) - (tt[j] - o));
            ctx.addJ(oIdx[j], oIdx[j], tpd * ctx.alpha() + 1);
        }
        for (int k = 0; k < nIn; k++) {
            if (in[k] == null || dx[k] < 1e-12) continue;
            double x0 = xs[k];
            xs[k] = x0 + h;
            outputs(xs, qs, tp);
            xs[k] = x0;
            for (int j = 0; j < nOut; j++) {
                double g = -(tp[j] - tt[j]) / h * dx[k];
                if (g == 0) continue;
                ctx.addJ(oIdx[j], in[k].index(), g);
                ctx.addJ(oIdx[j], gnd.index(), -g);
            }
        }
        for (int k = 0; k < nF; k++) {
            double q0 = qs[k];
            qs[k] = q0 + h;
            outputs(xs, qs, tp);
            qs[k] = q0;
            for (int j = 0; j < nOut; j++) {
                double g = -(tp[j] - tt[j]) / h;
                if (g != 0) ctx.addJ(oIdx[j], sIdx[k], g);
            }
        }
        for (int j = 0; j < nOut; j++) {
            if (out[j] == null) continue;
            double s = ctx.x(oIdx[j]);
            stage(ctx, out[j], Math.max(0, Math.min(1, s)), oIdx[j], s > 0 && s < 1 ? 1 : 0);
        }
    }

    private static double[] regen(double q) {
        double r = sig((q - 0.5) / 0.1);
        return new double[]{r, r * (1 - r) / 0.1};
    }

    /** Output stage: pull-up s·G_on to V_DD and pull-down (1 − s)·G_on to GND, dissipating in the chip. */
    private void stage(LoadContext ctx, Node o, double s, int stateIdx, double sense) {
        double leak = 1e-9;
        double gu = s * gOn + leak, gd = (1 - s) * gOn + leak;
        double vo = ctx.x(o.index()), vu = ctx.x(vdd.index()), vl = ctx.x(gnd.index());
        ctx.addFlow(o, vdd, gu * (vo - vu));
        ctx.addFlow(o, gnd, gd * (vo - vl));
        ctx.stampConductance(o, vdd, gu);
        ctx.stampConductance(o, gnd, gd);
        ctx.addFlowJ(o, vdd, stateIdx, sense * gOn * (vo - vu));
        ctx.addFlowJ(o, gnd, stateIdx, -sense * gOn * (vo - vl));
        if (thermal != null) ctx.addF(thermal.index(), -(gu * (vo - vu) * (vo - vu) + gd * (vo - vl) * (vo - vl)));
    }

    /** Logic state of output j (0..1). */
    public double output(StateView s, int j) {
        return s.x(oIdx[j]);
    }

    /** Register bit i (0..1). */
    public double register(StateView s, int i) {
        return s.x(sIdx[i]);
    }

    /** Solver unknowns of flip-flop i's master and slave latches (for restoring stored bits). */
    public int[] latchIndices(int i) {
        return new int[]{mIdx[i], sIdx[i]};
    }

    @Override
    public double untrackedLoss(StateView st) {
        if (thermal != null) return 0;
        double loss = 0;
        for (int j = 0; j < nOut; j++) {
            if (out[j] == null) continue;
            double s = Math.max(0, Math.min(1, st.x(oIdx[j])));
            double vo = st.x(out[j]), vu = st.x(vdd), vl = st.x(gnd);
            loss += (s * gOn + 1e-9) * (vo - vu) * (vo - vu) + ((1 - s) * gOn + 1e-9) * (vo - vl) * (vo - vl);
        }
        return loss;
    }

    @Override
    public String label() {
        return "MsiChip(" + type + ")";
    }
}
