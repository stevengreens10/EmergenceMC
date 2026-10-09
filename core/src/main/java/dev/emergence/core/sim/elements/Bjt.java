package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Bipolar junction transistor, Ebers–Moll transport model with the Early effect (Gummel–Poon without
 * high-injection terms):
 * <pre>
 *   I_T = I_S (e^{V_BE/V_T} − e^{V_BC/V_T}) · (1 − V_BC/V_A)
 *   I_C = I_T − (I_S/β_R)(e^{V_BC/V_T} − 1)
 *   I_B = (I_S/β_F)(e^{V_BE/V_T} − 1) + (I_S/β_R)(e^{V_BC/V_T} − 1)
 * </pre>
 * For PNP all junction voltages and currents change sign. I_S(T) follows the diode law
 * (∝ T³ e^{−E_g/kT}); exponentials are continued linearly beyond 40 V_T for Newton robustness.
 * Power V_CE·I_C + V_BE·I_B is deposited in the thermal node. Junction and diffusion capacitances are
 * added separately by the caller.
 */
public final class Bjt implements Element {
    private final Node c, b, e, thermal;
    private final double sign, is0, betaF, betaR, va;
    private static final double U_MAX = 40;

    public Bjt(Node collector, Node base, Node emitter, boolean pnp, double saturationCurrent, double betaF, double betaR,
               double earlyVoltage, Node thermal) {
        this.c = collector;
        this.b = base;
        this.e = emitter;
        this.sign = pnp ? -1 : 1;
        this.is0 = saturationCurrent;
        this.betaF = betaF;
        this.betaR = betaR;
        this.va = earlyVoltage;
        this.thermal = thermal;
    }

    private double temp(StateView s) {
        return thermal != null ? Math.max(s.x(thermal.index()), 50) : 300;
    }

    private static double isat(double is0, double t) {
        double vt = PhysicalConstants.K_OVER_Q * t;
        return is0 * Math.pow(t / 300.0, 3) * Math.exp(1.12 / vt * (t / 300.0 - 1));
    }

    /** {e^u, d/dv} with linear continuation. */
    private static double[] ex(double v, double vt) {
        double u = v / vt;
        if (u > U_MAX) {
            double m = Math.exp(U_MAX);
            return new double[]{m * (1 + u - U_MAX), m / vt};
        }
        double x = Math.exp(u);
        return new double[]{x, x / vt};
    }

    /** Terminal currents into the device {i_c, i_b} (i_e = −i_c − i_b) and their derivatives wrt v_be, v_bc. */
    private double[] eval(double vbe, double vbc, double t) {
        double vt = PhysicalConstants.K_OVER_Q * t, is = isat(is0, t);
        double[] f = ex(vbe, vt), r = ex(vbc, vt);
        double early = Math.max(1 - vbc / va, 0.05);
        double dEarly = (1 - vbc / va) > 0.05 ? -1 / va : 0;
        double it = is * (f[0] - r[0]) * early;
        double ic = it - is / betaR * (r[0] - 1);
        double ib = is / betaF * (f[0] - 1) + is / betaR * (r[0] - 1);
        double dic_dbe = is * f[1] * early;
        double dic_dbc = -is * r[1] * early + is * (f[0] - r[0]) * dEarly - is / betaR * r[1];
        double dib_dbe = is / betaF * f[1];
        double dib_dbc = is / betaR * r[1];
        return new double[]{ic, ib, dic_dbe, dic_dbc, dib_dbe, dib_dbc};
    }

    @Override
    public void load(LoadContext ctx) {
        double t = temp(ctx);
        double vb = ctx.x(b.index()), vc = ctx.x(c.index()), ve = ctx.x(e.index());
        double vbe = sign * (vb - ve), vbc = sign * (vb - vc);
        double[] r = eval(vbe, vbc, t);
        double ic = sign * r[0], ib = sign * r[1];
        // currents flowing into the collector and base terminals leave those nodes; the emitter supplies them
        ctx.addF(c.index(), ic);
        ctx.addF(b.index(), ib);
        ctx.addF(e.index(), -ic - ib);
        // derivatives: v_be = s(vb − ve), v_bc = s(vb − vc); d(s·x)/dv = s·s·dx = dx
        int ic_ = c.index(), ib_ = b.index(), ie_ = e.index();
        double gcbe = r[2], gcbc = r[3], gbbe = r[4], gbbc = r[5];
        // d ic / d(vb, vc, ve)
        stamp(ctx, ic_, ib_, gcbe + gcbc);
        stamp(ctx, ic_, ic_, -gcbc);
        stamp(ctx, ic_, ie_, -gcbe);
        stamp(ctx, ib_, ib_, gbbe + gbbc);
        stamp(ctx, ib_, ic_, -gbbc);
        stamp(ctx, ib_, ie_, -gbbe);
        stamp(ctx, ie_, ib_, -(gcbe + gcbc + gbbe + gbbc));
        stamp(ctx, ie_, ic_, gcbc + gbbc);
        stamp(ctx, ie_, ie_, gcbe + gbbe);
        if (thermal != null) {
            double p = (vc - ve) * ic + (vb - ve) * ib;
            ctx.addF(thermal.index(), -p);
        }
    }

    private static void stamp(LoadContext ctx, int row, int col, double v) {
        ctx.addJ(row, col, v);
    }

    /** Collector current (positive into the collector for NPN) [A]. */
    public double collectorCurrent(StateView s) {
        double vbe = sign * (s.x(b) - s.x(e)), vbc = sign * (s.x(b) - s.x(c));
        return sign * eval(vbe, vbc, temp(s))[0];
    }

    public double baseCurrent(StateView s) {
        double vbe = sign * (s.x(b) - s.x(e)), vbc = sign * (s.x(b) - s.x(c));
        return sign * eval(vbe, vbc, temp(s))[1];
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        return (s.x(c) - s.x(e)) * collectorCurrent(s) + (s.x(b) - s.x(e)) * baseCurrent(s);
    }
}
