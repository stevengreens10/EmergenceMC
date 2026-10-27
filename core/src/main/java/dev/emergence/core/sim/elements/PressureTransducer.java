package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Amplified pressure transducer (manufactured: a silicon diaphragm on the port with a strain-gauge bridge on it, and
 * the bridge's amplifier, in one housing). The diaphragm's deflection — and so the bridge's imbalance — is in
 * proportion to the pressure on the port above the reference (gauge type: the ambient), and the amplifier brings
 * it out ratiometric: 10 % of the supply at {@code pMin}, 90 % at {@code pMax}, through a 100 Ω output; beyond the
 * range the output stays at the rails of the amplifier (smoothly, over 0.2 % of the range). It draws 1.5 mA from its
 * supply, and its output current from it too; all of it is lost as heat (to the ambient: the housing has no part of
 * its own to warm). The diaphragm's volume is negligible beside any vessel's: it takes nothing from the fluid.
 */
public final class PressureTransducer implements Element, dev.emergence.core.sim.PassiveReader {
    private static final double R_OUT = 100, I_Q = 1.5e-3, V_NOM = 5;
    private final Node p, vdd, gnd, out;
    private final double pRef, pMin, pMax;

    /**
     * @param pressure the fluid's pressure node; @param pRef the reference (ambient) pressure [Pa]; @param pMin,
     * pMax the gauge pressures at 10 % and 90 % output [Pa]
     */
    public PressureTransducer(Node pressure, Node vdd, Node gnd, Node out, double pRef, double pMin, double pMax) {
        this.p = pressure;
        this.vdd = vdd;
        this.gnd = gnd;
        this.out = out;
        this.pRef = pRef;
        this.pMin = pMin;
        this.pMax = pMax;
    }

    /** The output's fraction of the supply at gauge pressure g, and its slope d/dg. */
    private double[] fraction(double g) {
        double u = (g - pMin) / (pMax - pMin), e = 0.002;
        // clamp to [0, 1] with rounded corners (so Newton sees a slope)
        double lo = 0.5 * (u + Math.sqrt(u * u + e * e)), dlo = 0.5 * (1 + u / Math.sqrt(u * u + e * e));
        double w = 1 - lo, hi = 0.5 * (w + Math.sqrt(w * w + e * e)), dhi = 0.5 * (1 + w / Math.sqrt(w * w + e * e));
        double f = 1 - hi, df = dhi * dlo;
        return new double[]{0.1 + 0.8 * f, 0.8 * df / (pMax - pMin)};
    }

    @Override
    public void load(LoadContext ctx) {
        double vs = ctx.x(vdd.index()) - ctx.x(gnd.index()), g = ctx.x(p.index()) - pRef, vo = ctx.x(out.index());
        double[] fr = fraction(g);
        double vt = ctx.x(gnd.index()) + fr[0] * vs, i = (vt - vo) / R_OUT;
        // the output current from the supply into the output
        ctx.addFlow(vdd, out, i);
        ctx.addFlowJ(vdd, out, out.index(), -1 / R_OUT);
        ctx.addFlowJ(vdd, out, vdd.index(), fr[0] / R_OUT);
        ctx.addFlowJ(vdd, out, gnd.index(), (1 - fr[0]) / R_OUT);
        ctx.addFlowJRead(vdd, out, p.index(), fr[1] * vs / R_OUT); // (the vessel's pressure, read passively)
        // the quiescent draw, as a conductance (it falls with the supply)
        double gq = I_Q / V_NOM;
        ctx.addFlow(vdd, gnd, gq * vs);
        ctx.stampConductance(vdd, gnd, gq);
    }

    /** The output voltage it is driving towards [V] at a state (what a meter on an unloaded output reads). */
    public double target(StateView s) {
        return s.x(gnd.index()) + fraction(s.x(p.index()) - pRef)[0] * (s.x(vdd.index()) - s.x(gnd.index()));
    }

    @Override
    public double untrackedLoss(StateView s) {
        double vs = s.x(vdd.index()) - s.x(gnd.index()), vo = s.x(out.index());
        double i = (target(s) - vo) / R_OUT;
        return I_Q / V_NOM * vs * vs + i * (s.x(vdd.index()) - vo);
    }

    @Override
    public String label() {
        return "PressureTransducer";
    }
}
