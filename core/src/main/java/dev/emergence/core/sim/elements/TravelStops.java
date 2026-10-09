package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * The ends of a slide's travel: past lo or hi a stiff spring (C¹: quadratic over the first millimetre, then linear)
 * pushes the slider back, with contact damping that dissipates the impact into a thermal node — a slider hitting the
 * end of its guide stops instead of passing through.
 */
public final class TravelStops implements Element {
    private final Node node, thermal;
    private final AngleTracker tracker;
    private final double lo, hi, k, c, knee = 1e-3;

    public TravelStops(Node node, AngleTracker tracker, double lo, double hi, double stiffness, double damping, Node thermal) {
        this.node = node;
        this.tracker = tracker;
        this.lo = lo;
        this.hi = hi;
        this.k = stiffness;
        this.c = damping;
        this.thermal = thermal;
    }

    private double[] spring(double e) {
        if (e <= 0) return new double[]{0, 0, 0};
        if (e < knee) return new double[]{k * e * e / (2 * knee), k * e / knee, k * e * e * e / (6 * knee)};
        return new double[]{k * (e - knee / 2), k, k * (e * e / 2 - knee * e / 2 + knee * knee / 6)};
    }

    private double[] contact(double e) {
        if (e <= 0) return new double[]{0, 0};
        if (e < knee) return new double[]{e / knee, 1 / knee};
        return new double[]{1, 0};
    }

    @Override
    public void load(LoadContext ctx) {
        int ix = tracker.angleIndex(), iv = node.index();
        double x = ctx.x(ix), v = ctx.x(iv);
        double[] up = spring(x - hi), dn = spring(lo - x), cu = contact(x - hi), cd = contact(lo - x);
        double f = -up[0] + dn[0], df = -up[1] - dn[1];
        double w = cu[0] + cd[0], dw = cu[1] - cd[1];
        ctx.addF(iv, -f + c * w * v);
        ctx.addJ(iv, ix, -df + c * dw * v);
        ctx.addJ(iv, iv, c * w);
        if (thermal != null) {
            ctx.addF(thermal.index(), -c * w * v * v);
            ctx.addJ(thermal.index(), iv, -2 * c * w * v);
            ctx.addJ(thermal.index(), ix, -c * dw * v * v);
        }
    }

    @Override
    public double storedEnergy(StateView s) {
        double x = s.x(tracker.angleIndex());
        return spring(x - hi)[2] + spring(lo - x)[2];
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double x = s.x(tracker.angleIndex()), v = s.x(node);
        return c * (contact(x - hi)[0] + contact(lo - x)[0]) * v * v;
    }
}
