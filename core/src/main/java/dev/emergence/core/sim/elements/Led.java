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
    private int uIdx;

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
        // unknown: the junction voltage behind the series resistance, with SPICE's logarithmic limiting
        uIdx = ctx.allocUnknown("V(led junction)", 1e-6);
        ctx.limitNewton(uIdx, (old, proposed) -> pnjlim(proposed, old));
    }

    /** SPICE p-n junction limiting: forward steps above the critical voltage grow only logarithmically. */
    private double pnjlim(double vnew, double vold) {
        double nvt = n * PhysicalConstants.K_OVER_Q * 300;
        double vcrit = nvt * Math.log(nvt / (Math.sqrt(2) * is));
        if (vnew > vcrit && Math.abs(vnew - vold) > 2 * nvt) {
            if (vold > 0) {
                double arg = 1 + (vnew - vold) / nvt;
                return arg > 0 ? vold + nvt * Math.log(arg) : vcrit;
            }
            return nvt * Math.log(vnew / nvt);
        }
        return vnew;
    }

    /** Junction current {i, di/du}, continued linearly far above any reachable current. */
    private double[] junction(double u) {
        double nvt = n * PhysicalConstants.K_OVER_Q * 300;
        double x = u / nvt;
        if (x > 80) {
            double m = Math.exp(80);
            return new double[]{is * (m * (1 + x - 80) - 1), is * m / nvt};
        }
        double e = Math.exp(x);
        return new double[]{is * (e - 1), is * e / nvt};
    }

    @Override
    public void load(LoadContext ctx) {
        double va = ctx.x(a.index()), vk = ctx.x(k.index()), u = ctx.x(uIdx);
        double[] j = junction(u);
        double gs = 1 / rs, ir = gs * (va - vk - u);
        // anode: current into the series resistance
        ctx.addF(a.index(), ir);
        ctx.addJ(a.index(), a.index(), gs);
        ctx.addJ(a.index(), k.index(), -gs);
        ctx.addJ(a.index(), uIdx, -gs);
        // internal node: resistor current = junction current
        ctx.addF(uIdx, -ir + j[0]);
        ctx.addJ(uIdx, a.index(), -gs);
        ctx.addJ(uIdx, k.index(), gs);
        ctx.addJ(uIdx, uIdx, gs + j[1]);
        // cathode: the junction current arrives
        ctx.addF(k.index(), -j[0]);
        ctx.addJ(k.index(), uIdx, -j[1]);
        if (thermal != null) ctx.addF(thermal.index(), -((va - vk) * j[0] - optical(j[0])));
    }

    private double optical(double i) {
        return i > 0 ? eta * photonEv * i : 0;
    }

    public double current(StateView s) {
        return junction(s.x(uIdx))[0];
    }

    /** Emitted optical power [W]. */
    public double opticalPower(StateView s) {
        return optical(current(s));
    }

    @Override
    public double externalPower(StateView s) {
        return -opticalPower(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double i = current(s);
        return s.across(a, k) * i - optical(i);
    }
}
