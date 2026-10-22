package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Reduced-order CMOS logic integrated circuit (74HC-class): the abstraction level above the transistor
 * models, for circuits too large to simulate device by device. It keeps the physics that digital design
 * depends on:
 * <ul>
 *   <li><b>Supply</b>: the chip has real V_DD and GND pins; its output stage is a pull-up and a pull-down
 *       conductance (the on-resistances of the output transistors, ~50 Ω) weighted by the output state, so
 *       output current comes from the supply and the dissipation heats the chip.</li>
 *   <li><b>Input thresholds</b>: an input's logic value is a steep sigmoid of its voltage around V_DD/2
 *       (transition width 3 % of V_DD) — the switching point of a CMOS input stage, validated against the
 *       transistor-level inverter.</li>
 *   <li><b>Propagation delay</b>: the output state relaxes towards the logic function with time constant
 *       t_pd (default 10 ns), so outputs never jump and the solver integrates transitions smoothly.</li>
 *   <li><b>Memory</b>: the D flip-flop is master–slave: the master follows D while the clock is low, the
 *       slave copies the master while it is high — rising-edge triggered from the same relaxation law.</li>
 *   <li><b>Hysteresis</b>: the Schmitt inverter's threshold moves between 0.4 and 0.6 V_DD with its own
 *       output, the positive feedback that makes RC oscillators possible. The threshold is a discrete state, flipped
 *       as a solver event where the input crosses it (located by secant from the accepted steps): the trigger's
 *       internal regeneration is far faster than its output stage, and a continuous threshold that folded with the
 *       output cost Newton dozens of failures an edge.</li>
 * </ul>
 * Fuzzy (continuous) forms of the Boolean functions keep everything differentiable.
 */
public final class LogicGate implements dev.emergence.core.sim.EventSource {
    // (Fidelity note: real gate thresholds depend on internal device sizing — a transistor-level NAND with
    // equal devices switches near 0.65 V_DD — whereas this model uses the V_DD/2 of ratioed 74HC parts.)
    public enum Function { BUFFER, NOT, NAND, NOR, AND, OR, XOR, SCHMITT_NOT, DFF }

    private final Function fn;
    private final Node a, b, out, outBar, vdd, gnd, thermal;
    private final double tpd, gOn;
    private int sIdx, mIdx, sSlot, mSlot;
    /**
     * Schmitt trigger: which threshold is in force (true: the output is high, so the input must rise to 0.6 V_DD to
     * switch it), once {@link #thresholdKnown}; the input (a fraction of the supply) at the last accepted step, and when;
     * where the input is predicted to reach the threshold.
     */
    private boolean outHigh, thresholdKnown;
    private double lastT = Double.NEGATIVE_INFINITY, lastU = Double.NaN, crossing = Double.POSITIVE_INFINITY;

    /**
     * @param a      first input (D for a flip-flop)
     * @param b      second input (clock for a flip-flop; unused for single-input functions — may be null)
     * @param out    output (Q for a flip-flop)
     * @param outBar complementary output Q̄ (flip-flop only; may be null)
     */
    public LogicGate(Function fn, Node a, Node b, Node out, Node outBar, Node vdd, Node gnd, double propagationDelay,
                     double outputResistance, Node thermal) {
        this.fn = fn;
        this.a = a;
        this.b = b;
        this.out = out;
        this.outBar = outBar;
        this.vdd = vdd;
        this.gnd = gnd;
        this.tpd = propagationDelay;
        this.gOn = 1 / outputResistance;
        this.thermal = thermal;
    }

    @Override
    public void setup(SetupContext ctx) {
        sIdx = ctx.allocUnknown("logic(" + fn + ")", 1e-6);
        ctx.setInitial(sIdx, 0);
        ctx.limitNewtonStep(sIdx, 0.4);
        sSlot = ctx.allocState(0.02);
        if (fn == Function.DFF) {
            mIdx = ctx.allocUnknown("master", 1e-6);
            ctx.limitNewtonStep(mIdx, 0.4);
            mSlot = ctx.allocState(0.02);
        }
    }

    private static double sig(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    /**
     * Logic value of a node voltage and its derivative wrt that voltage (supply span given). An unpowered
     * chip (span below ~1 V) reads its inputs as low.
     */
    private static double[] level(double v, double span, double frac) {
        return level(v, span, frac, 0.03);
    }

    /** As {@link #level(double, double, double)} with the transition width given as a fraction of the supply. */
    private static double[] level(double v, double span, double frac, double width) {
        double sp = Math.max(span, 1.0);
        double w = width * sp;
        double x = (v - frac * sp) / w;
        double s = sig(Math.max(-40, Math.min(40, x)));
        return new double[]{s, s * (1 - s) / w};
    }

    @Override
    public void load(LoadContext ctx) {
        double vg = ctx.x(gnd.index()), span = ctx.x(vdd.index()) - vg;
        double s = ctx.x(sIdx);
        double va = ctx.x(a.index()) - vg, vb = b != null ? ctx.x(b.index()) - vg : 0;
        // Schmitt: the threshold is 0.6 V_DD while the output is high and 0.4 V_DD while it is low, switching
        // sharply with the output state (a soft dependence would fold the hysteresis loop shut)
        // (until the first event has read the state, the threshold follows the output state continuously)
        double hs = thresholdKnown ? (outHigh ? 1 : 0) : sig(Math.max(-40, Math.min(40, (s - 0.5) / 0.05)));
        double dhs = thresholdKnown ? 0 : hs * (1 - hs) / 0.05;
        // a Schmitt trigger's input stage has high gain (a 0.5 %-of-supply transition) so that, with the hysteresis
        // feedback, its output edge is fast however slowly the input moves — the purpose of the part
        double[] la = fn == Function.SCHMITT_NOT ? level(va, span, 0.4 + 0.2 * hs, 0.005) : level(va, span, 0.5);
        double[] lb = b != null ? level(vb, span, 0.5) : new double[]{0, 0};
        double x = la[0], y = lb[0];
        // target and its partial derivatives wrt x and y (fuzzy Boolean algebra)
        double t, tx, ty;
        switch (fn) {
            case BUFFER -> { t = x; tx = 1; ty = 0; }
            case NOT, SCHMITT_NOT -> { t = 1 - x; tx = -1; ty = 0; }
            case AND -> { t = x * y; tx = y; ty = x; }
            case NAND -> { t = 1 - x * y; tx = -y; ty = -x; }
            case OR -> { t = x + y - x * y; tx = 1 - y; ty = 1 - x; }
            case NOR -> { t = (1 - x) * (1 - y); tx = -(1 - y); ty = -(1 - x); }
            case XOR -> { t = x + y - 2 * x * y; tx = 1 - 2 * y; ty = 1 - 2 * x; }
            default -> { t = 0; tx = 0; ty = 0; }
        }
        if (fn == Function.SCHMITT_NOT && thresholdKnown) {
            // its regeneration has decided: the output heads for the state the last crossing set, whatever the input
            // does short of the next threshold (an input stage still free to follow the input stalled the timing
            // capacitor just short of the threshold, the gate an amplifier creeping there)
            t = outHigh ? 1 : 0;
            tx = 0;
        }
        if (fn == Function.DFF) {
            // master follows D while the clock is low; slave (the output) copies the master while it is high.
            // The two are transparent in non-overlapping windows of the clock level (master below 35 %, slave
            // above 65 %), so data cannot race through while the clock edge passes; a latch that is holding
            // regenerates — its cross-coupled inverters amplify any departure from the midpoint towards the
            // nearer rail — so no state can sit metastable at 1/2.
            double m = ctx.x(mIdx), clk = y;
            double wm = sig(Math.max(-40, Math.min(40, (0.35 - clk) / 0.04))), dwm = -wm * (1 - wm) / 0.04;
            double ws = sig(Math.max(-40, Math.min(40, (clk - 0.65) / 0.04))), dws = ws * (1 - ws) / 0.04;
            double[] rm = regen(m), rs = regen(s);
            double dm = ctx.ddt(mSlot, m);
            ctx.addF(mIdx, tpd * dm - (x - m) * wm - (rm[0] - m) * (1 - wm));
            ctx.addJ(mIdx, mIdx, tpd * ctx.alpha() + wm - (rm[1] - 1) * (1 - wm));
            ctx.addJ(mIdx, a.index(), -la[1] * wm);
            ctx.addJ(mIdx, gnd.index(), la[1] * wm);
            double dmClk = (-(x - m) + (rm[0] - m)) * dwm;
            ctx.addJ(mIdx, b.index(), dmClk * lb[1]);
            ctx.addJ(mIdx, gnd.index(), -dmClk * lb[1]);
            double ds = ctx.ddt(sSlot, s);
            ctx.addF(sIdx, tpd * ds - (m - s) * ws - (rs[0] - s) * (1 - ws));
            ctx.addJ(sIdx, sIdx, tpd * ctx.alpha() + ws - (rs[1] - 1) * (1 - ws));
            ctx.addJ(sIdx, mIdx, -ws);
            double dsClk = (-(m - s) + (rs[0] - s)) * dws;
            ctx.addJ(sIdx, b.index(), dsClk * lb[1]);
            ctx.addJ(sIdx, gnd.index(), -dsClk * lb[1]);
        } else {
            double ds = ctx.ddt(sSlot, s);
            ctx.addF(sIdx, tpd * ds - (t - s));
            ctx.addJ(sIdx, sIdx, tpd * ctx.alpha() + 1);
            ctx.addJ(sIdx, a.index(), -tx * la[1]);
            ctx.addJ(sIdx, gnd.index(), tx * la[1]);
            if (fn == Function.SCHMITT_NOT) {
                // the threshold moves with the output: ∂t/∂s through the threshold
                ctx.addJ(sIdx, sIdx, -tx * la[1] * (-0.2 * dhs * span));
            }
            if (b != null && ty != 0) {
                ctx.addJ(sIdx, b.index(), -ty * lb[1]);
                ctx.addJ(sIdx, gnd.index(), ty * lb[1]);
            }
        }
        // the state may stray marginally outside [0, 1]; the output stage saturates there, and its derivative with
        // it (a derivative that ignores the clamp makes Newton cycle at a fully driven output)
        double inside = s > 0 && s < 1 ? 1 : 0;
        stage(ctx, out, Math.max(0, Math.min(1, s)), sIdx, inside);
        if (outBar != null) stage(ctx, outBar, Math.max(0, Math.min(1, 1 - s)), sIdx, -inside);
    }

    // ---- the Schmitt trigger's threshold as an event

    @Override
    public double nextEvent(double time) {
        if (fn != Function.SCHMITT_NOT) return Double.POSITIVE_INFINITY;
        // due at every accepted step not yet read (the solver asks just before stepping on); otherwise the predicted
        // crossing, which the solver steps onto
        if (lastT < time) return time;
        return crossing > time ? crossing : Double.POSITIVE_INFINITY;
    }

    @Override
    public boolean fireEvent(StateView st, double time) {
        if (fn != Function.SCHMITT_NOT) return false;
        double vg = st.x(gnd), span = Math.max(st.x(vdd) - vg, 1.0), u = (st.x(a) - vg) / span;
        boolean flipped = false;
        if (!thresholdKnown) {
            outHigh = st.x(sIdx) > 0.5;
            thresholdKnown = true;
            flipped = true; // the threshold law changes from continuous to discrete: restart the history
        } else if (outHigh ? u >= 0.6 - 1e-4 : u <= 0.4 + 1e-4) {
            outHigh = !outHigh;
            flipped = true;
        }
        // where the input will reach the threshold now in force, by secant through the last two readings
        double target = outHigh ? 0.6 : 0.4;
        crossing = Double.POSITIVE_INFINITY;
        if (!flipped && Double.isFinite(lastU) && time > lastT) {
            double rate = (u - lastU) / (time - lastT);
            if (outHigh ? rate > 0 : rate < 0) crossing = time + (target - u) / rate;
        }
        lastT = time;
        lastU = u;
        return flipped;
    }

    /** Regeneration target of a holding latch: a steep sigmoid of its own state, and its slope. */
    private static double[] regen(double q) {
        double r = sig(Math.max(-40, Math.min(40, (q - 0.5) / 0.1)));
        return new double[]{r, r * (1 - r) / 0.1};
    }

    /** Output stage: pull-up conductance s·G_on to V_DD and pull-down (1 − s)·G_on to GND. */
    private void stage(LoadContext ctx, Node o, double s, int stateIdx, double sense) {
        double leak = 1e-9;
        double gu = s * gOn + leak, gd = (1 - s) * gOn + leak;
        double vo = ctx.x(o.index()), vu = ctx.x(vdd.index()), vl = ctx.x(gnd.index());
        double iu = gu * (vo - vu), il = gd * (vo - vl); // currents leaving the output node
        ctx.addFlow(o, vdd, iu);
        ctx.addFlow(o, gnd, il);
        ctx.stampConductance(o, vdd, gu);
        ctx.stampConductance(o, gnd, gd);
        double diu = sense * gOn * (vo - vu), dil = -sense * gOn * (vo - vl);
        ctx.addFlowJ(o, vdd, stateIdx, diu);
        ctx.addFlowJ(o, gnd, stateIdx, dil);
        if (thermal != null) {
            ctx.addF(thermal.index(), -(gu * (vo - vu) * (vo - vu) + gd * (vo - vl) * (vo - vl)));
        }
    }

    /** Output logic state (0..1). */
    public double state(StateView s) {
        return s.x(sIdx);
    }

    @Override
    public double untrackedLoss(StateView st) {
        if (thermal != null) return 0;
        double loss = 0;
        for (Node o : new Node[]{out, outBar}) {
            if (o == null) continue;
            double s = Math.max(0, Math.min(1, o == out ? st.x(sIdx) : 1 - st.x(sIdx)));
            double vo = st.x(o), vu = st.x(vdd), vl = st.x(gnd);
            loss += (s * gOn + 1e-9) * (vo - vu) * (vo - vu) + ((1 - s) * gOn + 1e-9) * (vo - vl) * (vo - vl);
        }
        return loss;
    }

    @Override
    public String label() {
        return "LogicGate(" + fn + ")";
    }
}
