package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Enhancement MOSFET with its body tied to the source, by the EKV charge-sheet interpolation — one smooth
 * expression valid from weak to strong inversion and symmetric in source and drain:
 * <pre>
 *   I_D = I_spec · [F((V_P − V_S)/V_T) − F((V_P − V_D)/V_T)] · (1 + λ|V_DS|)
 *   F(x) = ln²(1 + e^{x/2}),   V_P = (V_G − V_th)/n,   I_spec = 2 n K V_T²
 * </pre>
 * (all voltages relative to the source/body). In strong inversion and saturation this tends to the
 * square law I_D = (K/2n)(V_GS − V_th)²; below threshold to the exponential with slope n·V_T·ln 10 per
 * decade. K = μ·C_ox·W/L. PMOS: all voltages and currents change sign. Temperature: V_th falls ~2 mV/K
 * and mobility ∝ T^−1.5. Dissipated power goes to the thermal node. Gate capacitances are added by the
 * caller.
 */
public final class Mosfet implements Element {
    private final Node d, g, s, thermal;
    private final double sign, vth0, k0, n, lambda;

    public Mosfet(Node drain, Node gate, Node source, boolean pChannel, double thresholdVoltage, double transconductance,
                  double slopeFactor, double lambda, Node thermal) {
        this.d = drain;
        this.g = gate;
        this.s = source;
        this.sign = pChannel ? -1 : 1;
        this.vth0 = thresholdVoltage;
        this.k0 = transconductance;
        this.n = slopeFactor;
        this.lambda = lambda;
        this.thermal = thermal;
    }

    private double temp(StateView st) {
        return thermal != null ? Math.max(st.x(thermal.index()), 50) : 300;
    }

    /** F(x) = ln²(1 + e^{x/2}) and its derivative. */
    private static double[] f(double x) {
        double h = x / 2, sp, sig;
        if (h > 30) {
            sp = h;
            sig = 1;
        } else {
            double eh = Math.exp(h);
            sp = Math.log1p(eh);
            sig = eh / (1 + eh);
        }
        return new double[]{sp * sp, sp * sig};
    }

    /** Drain current (into the drain, n-channel sense) for terminal voltages relative to the source. */
    private double current(double vgs, double vds, double t) {
        double vt = PhysicalConstants.K_OVER_Q * t;
        double vth = vth0 - 2e-3 * (t - 300);
        double k = k0 * Math.pow(t / 300, -1.5);
        double ispec = 2 * n * k * vt * vt;
        double vp = (vgs - vth) / n;
        double fs = f(vp / vt)[0], fd = f((vp - vds) / vt)[0];
        return ispec * (fs - fd) * (1 + lambda * Math.abs(vds));
    }

    @Override
    public void load(LoadContext ctx) {
        double t = temp(ctx);
        double vg = ctx.x(g.index()), vd = ctx.x(d.index()), vs = ctx.x(s.index());
        double vgs = sign * (vg - vs), vds = sign * (vd - vs);
        double id = current(vgs, vds, t);
        // numerical conductances (the expression is smooth everywhere)
        double h = 1e-6;
        double gm = (current(vgs + h, vds, t) - current(vgs - h, vds, t)) / (2 * h);
        double gds = (current(vgs, vds + h, t) - current(vgs, vds - h, t)) / (2 * h);
        double i = sign * id;
        ctx.addF(d.index(), i);
        ctx.addF(s.index(), -i);
        // di/dvg = gm, di/dvd = gds, di/dvs = −gm − gds (the sign factors cancel)
        ctx.addJ(d.index(), g.index(), gm);
        ctx.addJ(d.index(), d.index(), gds);
        ctx.addJ(d.index(), s.index(), -gm - gds);
        ctx.addJ(s.index(), g.index(), -gm);
        ctx.addJ(s.index(), d.index(), -gds);
        ctx.addJ(s.index(), s.index(), gm + gds);
        if (thermal != null) ctx.addF(thermal.index(), -(vd - vs) * i);
    }

    /** Drain current [A] (into the drain for NMOS). */
    public double drainCurrent(StateView st) {
        double vgs = sign * (st.x(g) - st.x(s)), vds = sign * (st.x(d) - st.x(s));
        return sign * current(vgs, vds, temp(st));
    }

    @Override
    public double untrackedLoss(StateView st) {
        if (thermal != null) return 0;
        return (st.x(d) - st.x(s)) * drainCurrent(st);
    }
}
