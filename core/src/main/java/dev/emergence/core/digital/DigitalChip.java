package dev.emergence.core.digital;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.EventSource;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A chip's body in the circuit: its supply pins, and its signal pins that sit on analog nets (those on nets of chip
 * pins only are the digital domain's). What its inside does is the domain's (see DigitalDomain); what it costs is
 * paid here:
 * <ul>
 *   <li>Supply: the chip draws its leakage and the power its switching took over the last millisecond or so (the
 *       energy of each handled edge and of each net it charged, smoothed with a 1 ms time constant), as a conductance
 *       across V_DD–GND; all of it heats the chip.</li>
 *   <li>An output on an analog net: a pull-up and a pull-down transistor of 50 Ω (74HC-class), one on as the output
 *       is high or low, switching at the times the domain sets — the solver lands a step there. Unpowered, both
 *       are off.</li>
 *   <li>An input on an analog net: a CMOS input stage switching at half the supply, with ±5 % hysteresis; a crossing
 *       is posted to the domain at its time, interpolated within the solver's step.</li>
 *   <li>An analog input (an ADC's): sampled by the inside at any time, interpolated between the solver's steps.</li>
 * </ul>
 * The chip powers up when its supply passes 2 V (down below 1.5 V): from its starting state, or the state it was
 * saved in.
 */
public final class DigitalChip implements Element, EventSource {
    private static final double R_ON = 50, G_OFF = 1e-9, V_ON = 2.0, V_OFF = 1.5, TAU = 1e-3;

    private final DigitalDomain dom;
    private final int slot;
    private final Node vdd, gnd, thermal;
    private final Node[] in, out;
    private final boolean[] sampled;
    private final boolean[] inLevel;
    private final double[] vPrev;
    private double tPrev = Double.NaN;
    private boolean on;
    private double pDyn, gSupply = G_OFF;
    private final String label;

    /**
     * @param in  for each input pin, its analog node (null: on a digital net, or unconnected)
     * @param out for each output pin, its analog node (null likewise)
     */
    public DigitalChip(DigitalDomain dom, int slot, Node vdd, Node gnd, Node[] in, Node[] out, Node thermal, String label) {
        this.dom = dom;
        this.slot = slot;
        this.vdd = vdd;
        this.gnd = gnd;
        this.thermal = thermal;
        this.in = in.clone();
        this.out = out.clone();
        this.label = label;
        DigitalComponent c = dom.component(slot);
        sampled = new boolean[in.length];
        for (int k : c.analogInputs()) sampled[k] = true;
        inLevel = new boolean[in.length];
        vPrev = new double[in.length];
        for (int k = 0; k < in.length; k++) if (in[k] != null) dom.bindAnalogInput(slot, k);
        for (int k = 0; k < out.length; k++) if (out[k] != null) dom.bindAnalogOutput(slot, k);
    }

    public DigitalDomain domain() {
        return dom;
    }

    public DigitalComponent component() {
        return dom.component(slot);
    }

    /** Whether the chip is powered. */
    public boolean powered() {
        return dom.slot(slot).powered;
    }

    @Override
    public void load(LoadContext ctx) {
        double v = ctx.x(vdd.index()) - ctx.x(gnd.index());
        // the supply draw: leakage and smoothed switching power, as a conductance held over the step
        ctx.addFlow(vdd, gnd, gSupply * v);
        ctx.stampConductance(vdd, gnd, gSupply);
        heat(ctx, vdd, gnd, gSupply);
        DigitalDomain.Slot s = dom.slot(slot);
        for (int k = 0; k < out.length; k++) {
            Node o = out[k];
            if (o == null) continue;
            boolean level = s.powered && s.outLevel[k];
            double gu = s.powered && level ? 1 / R_ON : G_OFF, gd = s.powered && !level ? 1 / R_ON : G_OFF;
            ctx.addFlow(vdd, o, gu * (ctx.x(vdd.index()) - ctx.x(o.index())));
            ctx.stampConductance(vdd, o, gu);
            ctx.addFlow(o, gnd, gd * (ctx.x(o.index()) - ctx.x(gnd.index())));
            ctx.stampConductance(o, gnd, gd);
            heat(ctx, vdd, o, gu);
            heat(ctx, o, gnd, gd);
        }
    }

    /** A conductance g between a and b dissipating g·(a − b)² into the chip's thermal node. */
    private void heat(LoadContext ctx, Node a, Node b, double g) {
        if (thermal == null) return;
        int t = thermal.index();
        double d = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addF(t, -g * d * d);
        ctx.addJ(t, a.index(), -2 * g * d);
        ctx.addJ(t, b.index(), 2 * g * d);
    }

    @Override
    public void acceptStep(StateView s, double time, double dt) {
        double v = s.x(vdd.index()) - s.x(gnd.index());
        DigitalDomain.Slot sl = dom.slot(slot);
        sl.supply = v;
        if (!on && v > V_ON) {
            on = true;
            dom.postPower(slot, true, time);
        } else if (on && v < V_OFF) {
            on = false;
            dom.postPower(slot, false, time);
        }
        double g = s.x(gnd.index());
        for (int k = 0; k < in.length; k++) {
            if (in[k] == null) continue;
            double vi = s.x(in[k].index()) - g;
            if (sampled[k]) {
                double[] a = sl.analog[k];
                if (Double.isNaN(tPrev)) {
                    a[0] = time;
                    a[1] = vi;
                } else {
                    a[0] = a[2];
                    a[1] = a[3];
                }
                a[2] = time;
                a[3] = vi;
                continue;
            }
            double hi = 0.55 * v, lo = 0.45 * v;
            boolean level = inLevel[k];
            if (!level && vi > hi || level && vi < lo) {
                double th = level ? lo : hi, tc = time;
                if (!Double.isNaN(tPrev) && vi != vPrev[k]) {
                    double f = (th - vPrev[k]) / (vi - vPrev[k]);
                    if (f >= 0 && f <= 1) tc = tPrev + f * (time - tPrev);
                }
                inLevel[k] = !level;
                dom.postInput(slot, k, !level, tc);
            }
            vPrev[k] = vi;
        }
        // the switching power, smoothed; drawn as a conductance with the leakage
        double e = dom.takeEnergy(slot), h = Double.isNaN(tPrev) ? dt : time - tPrev;
        if (h > 0) pDyn += (e / h - pDyn) * Math.min(1, h / TAU);
        double vv = Math.max(v, 0.1);
        gSupply = sl.powered ? component().leakage(v) / vv + pDyn / (vv * vv) + G_OFF : G_OFF;
        tPrev = time;
    }

    @Override
    public double nextEvent(double time) {
        dom.advanceTo(time);
        return dom.analogChangePending() ? time : dom.nextAnalogEvent();
    }

    @Override
    public boolean fireEvent(StateView s, double time) {
        dom.advanceTo(time);
        return dom.takeAnalogChange();
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double v = s.x(vdd.index()) - s.x(gnd.index()), p = gSupply * v * v;
        DigitalDomain.Slot sl = dom.slot(slot);
        for (int k = 0; k < out.length; k++) {
            Node o = out[k];
            if (o == null) continue;
            boolean level = sl.powered && sl.outLevel[k];
            double gu = sl.powered && level ? 1 / R_ON : G_OFF, gd = sl.powered && !level ? 1 / R_ON : G_OFF;
            double du = s.x(vdd.index()) - s.x(o.index()), dd = s.x(o.index()) - s.x(gnd.index());
            p += gu * du * du + gd * dd * dd;
        }
        return p;
    }

    @Override
    public String label() {
        return "DigitalChip(" + label + ")";
    }
}
