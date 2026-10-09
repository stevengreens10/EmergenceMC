package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Light-emitting diode: a PN junction of a direct-gap semiconductor whose band gap sets the photon energy
 * E = hc/λ. Electrical law: Shockley with ideality n and a saturation current placing the forward voltage
 * at 20 mA near E/q + 0.1 V (typical of real LEDs), plus series resistance R_s. Each recombining electron
 * emits a photon with probability η_ext (external quantum efficiency), so the optical power is
 * P_opt = η_ext·(E/q)·I; the rest of V·I heats the die. The light leaves the network (external power).
 */
public final class Led implements Element {
    private final Node a, k, thermal;
    private final double photonEv, n, is, rs, eta;
    private int iIdx;

    public Led(Node anode, Node cathode, double wavelengthNm, double quantumEfficiency, Node thermal) {
        this.a = anode;
        this.k = cathode;
        this.thermal = thermal;
        this.photonEv = 1239.84 / wavelengthNm;
        this.n = 2.0;
        this.rs = 10.0;
        this.eta = quantumEfficiency;
        double vt = PhysicalConstants.K_OVER_Q * 300;
        double vf = photonEv + 0.1 - 0.02 * rs;
        this.is = 0.02 / Math.exp(vf / (n * vt));
    }

    public double photonEnergyEv() {
        return photonEv;
    }

    @Override
    public void setup(dev.emergence.core.sim.SetupContext ctx) {
        // the junction current is an unknown so series resistance and the exponential stay well conditioned
        iIdx = ctx.allocUnknown("I(led)", 1e-9);
    }

    @Override
    public void load(LoadContext ctx) {
        double vt = PhysicalConstants.K_OVER_Q * 300;
        double i = ctx.x(iIdx);
        double v = ctx.x(a.index()) - ctx.x(k.index());
        // v = R_s·i + n·V_T·ln(1 + i/I_s) (inverse Shockley); continued linearly below i = −I_s/2, where the
        // slope n·V_T/(I_s/2) is so steep that a reverse-biased junction is an open circuit
        double i0 = -0.5 * is, vj, dvj;
        if (i >= i0) {
            vj = n * vt * Math.log1p(i / is);
            dvj = n * vt / (is + i);
        } else {
            dvj = n * vt / (is + i0);
            vj = n * vt * Math.log1p(i0 / is) + dvj * (i - i0);
        }
        ctx.addF(iIdx, v - rs * i - vj);
        ctx.addJ(iIdx, a.index(), 1);
        ctx.addJ(iIdx, k.index(), -1);
        ctx.addJ(iIdx, iIdx, -rs - dvj);
        ctx.addF(a.index(), i);
        ctx.addF(k.index(), -i);
        ctx.addJ(a.index(), iIdx, 1);
        ctx.addJ(k.index(), iIdx, -1);
        if (thermal != null) ctx.addF(thermal.index(), -(v * i - optical(i)));
    }

    private double optical(double i) {
        return i > 0 ? eta * photonEv * i : 0;
    }

    public double current(StateView s) {
        return s.x(iIdx);
    }

    /** Emitted optical power [W]. */
    public double opticalPower(StateView s) {
        return optical(s.x(iIdx));
    }

    @Override
    public double externalPower(StateView s) {
        return -opticalPower(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double i = s.x(iIdx);
        return s.across(a, k) * i - optical(i);
    }
}
