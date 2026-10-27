package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * PN-junction diode, Shockley equation with temperature dependence:
 * <pre>
 *   i = I_s(T)·(exp(v / (n·V_T)) − 1),   V_T = kT/q
 *   I_s(T) = I_s0 · (T/T0)^(3/n) · exp((E_g/(n·V_T0))·(T/T0 − 1)·... )   (Sze eq. 2.75 form)
 * </pre>
 * plus series resistance folded in by the caller if needed. To keep Newton robust the exponential is
 * continued linearly above u = v/(nV_T) = 40 (the classic junction-limiting approach); the solution
 * is unchanged wherever the true current is physically reachable.
 * Power dissipated (v·i) is delivered to the thermal node when one is given.
 */
public final class Diode implements Element {
    private final Node a, k, thermal;
    private final double is0, n, eg;
    private final double tFixed;
    private static final double U_MAX = 40.0;

    /**
     * @param saturationCurrent I_s at 300 K [A] (≈1e-14 A for a small silicon diode)
     * @param ideality          emission coefficient n (1–2)
     * @param bandGapEv         band gap [eV] used for I_s(T)
     */
    public Diode(Node anode, Node cathode, double saturationCurrent, double ideality, double bandGapEv, Node thermal) {
        this.a = anode;
        this.k = cathode;
        this.is0 = saturationCurrent;
        this.n = ideality;
        this.eg = bandGapEv;
        this.thermal = thermal;
        this.tFixed = 300.0;
    }

    public static Diode silicon(Node anode, Node cathode, Node thermal) {
        return new Diode(anode, cathode, 1e-14, 1.0, 1.12, thermal);
    }

    private double temp(StateView s) {
        return thermal != null ? deviceTemperature(s.x(thermal.index())) : tFixed;
    }

    private double isat(double T) {
        double vt = PhysicalConstants.K_OVER_Q * T;
        return is0 * Math.pow(T / 300.0, 3.0 / n) * Math.exp(eg / (n * vt) * (T / 300.0 - 1.0));
    }

    /** Returns {i, di/dv}. */
    private double[] iv(double v, double T) {
        double nvt = n * PhysicalConstants.K_OVER_Q * T;
        double is = isat(T);
        double u = v / nvt;
        double e, de;
        if (u > U_MAX) {
            double eMax = Math.exp(U_MAX);
            e = eMax * (1 + (u - U_MAX));
            de = eMax / nvt;
        } else {
            e = Math.exp(u);
            de = e / nvt;
        }
        return new double[]{is * (e - 1), is * de};
    }

    /** The junction voltage Newton's last iterate was evaluated at (SPICE's limiting state); NaN before the first. */
    private double vLast = Double.NaN;
    /** {@code emergence.diodeLimit} (on by default): SPICE p-n junction limiting. */
    private static final boolean LIMIT = !"false".equals(System.getProperty("emergence.diodeLimit"));

    /**
     * SPICE's p-n junction limiting (pnjlim): a forward step above the critical voltage grows only logarithmically.
     * Unlimited, an iterate that overshot into strong forward bias walked back down by one n·V_T an iteration — the
     * exponential's Newton step — and a power station's bridge diodes took 25–30 iterations a solve, a fifth of its
     * circuits' time.
     */
    private double limit(double vnew, double T) {
        if (!LIMIT || Double.isNaN(vLast)) return vnew;
        double nvt = n * PhysicalConstants.K_OVER_Q * T, is = isat(T);
        double vcrit = nvt * Math.log(nvt / (Math.sqrt(2) * is));
        if (vnew > vcrit && Math.abs(vnew - vLast) > 2 * nvt) {
            if (vLast > 0) {
                double arg = 1 + (vnew - vLast) / nvt;
                return arg > 0 ? vLast + nvt * Math.log(arg) : vcrit;
            }
            return nvt * Math.log(vnew / nvt);
        }
        return vnew;
    }

    @Override
    public void load(LoadContext ctx) {
        double T = temp(ctx);
        double v = ctx.x(a.index()) - ctx.x(k.index());
        // evaluated at the limited junction voltage and continued linearly from there to the iterate (exact once
        // Newton's steps are small: the limit is then inactive)
        double vl = limit(v, T);
        if (!ctx.isInitializing()) vLast = vl;
        double[] r = iv(vl, T);
        double g = r[1], i = r[0] + g * (v - vl);
        ctx.addFlow(a, k, i);
        ctx.stampConductance(a, k, g);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -v * i);
            ctx.addJ(t, a.index(), -(i + v * g));
            ctx.addJ(t, k.index(), (i + v * g));
            // ∂i/∂T neglected in the Jacobian (weak coupling; Newton still converges to the exact solution)
        }
    }

    public double current(StateView s) {
        return iv(s.across(a, k), temp(s))[0];
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0.0;
        double v = s.across(a, k);
        return v * iv(v, temp(s))[0];
    }

    /**
     * The temperature a device equation is evaluated at: the part's temperature bounded to 50–1500 K. A Newton
     * iterate far from the solution can put a junction's heat node anywhere (even non-finite); the bound keeps the
     * temperature-dependent saturation current finite so the iteration can recover. Converged states lie well
     * inside it (silicon devices fail long before 1500 K).
     */
    public static double deviceTemperature(double t) {
        return Double.isNaN(t) ? 300 : Math.max(50, Math.min(1500, t));
    }
}
