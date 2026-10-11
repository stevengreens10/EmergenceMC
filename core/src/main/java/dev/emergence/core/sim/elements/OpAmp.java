package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Operational amplifier (manufactured, LM358/TL07x class), single-pole model:
 * <ul>
 *   <li>inputs draw no current (their 2 pF to V− are added by the compiler);</li>
 *   <li>the internal voltage s (relative to mid-supply) follows τ·ds/dt = τ·SR·tanh(A₀·v_d/(τ·SR)) − s − τ·K·c(s):
 *       for small signals the single pole τ·ds/dt = A₀·v_d − s with open-loop gain A₀ = 10⁵ and τ = A₀/(2π·GBW)
 *       (an integrator at high frequency, finite gain at DC); the input stage's limited current caps the rate at
 *       the slew rate SR; c(s) is a soft clamp (10 mV knee, K = 10⁹/s) that holds s within the supply span less
 *       0.1 V, as the output transistors saturate, so the output stops near the rails and recovers without wind-up;</li>
 *   <li>the output is that voltage behind 75 Ω; the current it sources comes from V₊, the current it sinks goes
 *       to V₋, plus 0.5 mA quiescent current between the rails; all dissipation heats the part.</li>
 * </ul>
 * Closed-loop behaviour (gain = 1 + R₂/R₁, integrators, comparators, PID controllers) follows from the circuit.
 */
public final class OpAmp implements Element {
    private final Node inP, inN, out, vp, vn, thermal;
    private final double a0 = 1e5, tau, slew, rout = 75, iq = 5e-4;
    private int sIdx, sSlot;

    /**
     * @param gainBandwidth unity-gain bandwidth [Hz]
     * @param slewRate      output slew-rate limit [V/s]
     */
    public OpAmp(Node inP, Node inN, Node out, Node vp, Node vn, double gainBandwidth, double slewRate, Node thermal) {
        this.inP = inP;
        this.inN = inN;
        this.out = out;
        this.vp = vp;
        this.vn = vn;
        this.thermal = thermal;
        this.tau = a0 / (2 * Math.PI * gainBandwidth);
        this.slew = slewRate;
    }

    @Override
    public void setup(SetupContext ctx) {
        sIdx = ctx.allocUnknown("opamp", 1e-6);
        sSlot = ctx.allocState(1e-2);
    }

    private static double softplus(double x) {
        return x > 30 ? x : Math.log1p(Math.exp(x));
    }

    private static double logistic(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    private static final double KNEE = 0.01, CLAMP = 1e9;

    @Override
    public void load(LoadContext ctx) {
        double p = ctx.x(vp.index()), n = ctx.x(vn.index()), mid = 0.5 * (p + n), lim = Math.max(0.5 * (p - n) - 0.1, 0.05);
        double vd = ctx.x(inP.index()) - ctx.x(inN.index()), s = ctx.x(sIdx);
        double span = tau * slew, th = Math.tanh(a0 * vd / span), d = 1 - th * th;
        double a = (s - lim) / KNEE, b = (-s - lim) / KNEE, la = logistic(a), lb = logistic(b);
        double c = KNEE * (softplus(a) - softplus(b)), tk = tau * CLAMP;
        ctx.addF(sIdx, tau * ctx.ddt(sSlot, s) - span * th + s + tk * c);
        ctx.addJ(sIdx, sIdx, tau * ctx.alpha() + 1 + tk * (la + lb));
        ctx.addJ(sIdx, inP.index(), -d * a0);
        ctx.addJ(sIdx, inN.index(), d * a0);
        if (0.5 * (p - n) - 0.1 > 0.05) { // the clamp follows the rails
            double dl = tk * (lb - la);
            ctx.addJ(sIdx, vp.index(), 0.5 * dl);
            ctx.addJ(sIdx, vn.index(), -0.5 * dl);
        }
        // output stage
        double vo = ctx.x(out.index()), vt = mid + s, iout = (vt - vo) / rout; // into the output node
        double e = 1e-6, root = Math.sqrt(iout * iout + e * e), ip = 0.5 * (iout + root), in = ip - iout;
        double dip = 0.5 * (1 + iout / root), din = dip - 1;
        // d(iout)/d(s) = 1/rout, d/d(vo) = −1/rout, d/d(vp) = d/d(vn) = 0.5/rout
        int[] col = {sIdx, out.index(), vp.index(), vn.index()};
        double[] di = {1 / rout, -1 / rout, 0.5 / rout, 0.5 / rout};
        ctx.addF(out.index(), -iout);
        ctx.addF(vp.index(), ip + iq);
        ctx.addF(vn.index(), -in - iq);
        for (int k = 0; k < 4; k++) {
            ctx.addJ(out.index(), col[k], -di[k]);
            ctx.addJ(vp.index(), col[k], dip * di[k]);
            ctx.addJ(vn.index(), col[k], -din * di[k]);
        }
        if (thermal != null) ctx.addF(thermal.index(), -(ip * (p - vo) + in * (vo - n) + iq * (p - n)));
    }

    /** Output-stage internal voltage relative to mid-supply. */
    public double internal(StateView s) {
        return s.x(sIdx);
    }

    @Override
    public double untrackedLoss(StateView st) {
        if (thermal != null) return 0;
        double p = st.x(vp), n = st.x(vn), vo = st.x(out), vt = 0.5 * (p + n) + st.x(sIdx), iout = (vt - vo) / rout;
        double root = Math.sqrt(iout * iout + 1e-12), ip = 0.5 * (iout + root), in = ip - iout;
        return ip * (p - vo) + in * (vo - n) + iq * (p - n);
    }
}
