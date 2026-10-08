package dev.emergence.core.sim.elements;

import dev.emergence.core.math.PeriodicFunction;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A sliding electrical contact (carbon brush on a rotating conductor) whose conductance depends on how
 * much of the brush face is over the conductor at the present rotor angle:
 * <pre>
 *   i = G(θ)·(v_a − v_b),   G(θ) = G_max·c(θ) + G_off,   c ∈ [0, 1]
 * </pre>
 * c(θ) is the tabulated contact coverage (fraction of the brush width over the conductor); G_max follows
 * from the brush contact resistance. Commutation and slip rings emerge from the shape of c(θ) for each
 * segment. Dissipation (i²/G) heats the brush.
 */
public final class SlidingContact implements Element {
    private final Node a, b, thermal;
    private final AngleTracker rotor;
    private final double sign;
    private final PeriodicFunction coverage;
    private final double gMax, gOff;
    private final double[] f = new double[3];

    /**
     * @param angleSign +1 or −1: rotor angle enters as φ = sign·θ (axis orientation of the table)
     */
    public SlidingContact(Node a, Node b, AngleTracker rotor, double angleSign, PeriodicFunction coverage,
                          double contactResistance, Node thermal) {
        this.a = a;
        this.b = b;
        this.rotor = rotor;
        this.sign = angleSign;
        this.coverage = coverage;
        this.gMax = 1.0 / contactResistance;
        this.gOff = 1e-9;
        this.thermal = thermal;
    }

    private double g(double theta, double[] dg) {
        coverage.eval(sign * theta, f);
        double c = f[0];
        double dc = f[1] * sign;
        if (c <= 0) {
            dg[0] = 0;
            return gOff;
        }
        if (c >= 1) {
            dg[0] = 0;
            return gMax + gOff;
        }
        dg[0] = gMax * dc;
        return gMax * c + gOff;
    }

    @Override
    public void load(LoadContext ctx) {
        double th = ctx.x(rotor.angleIndex());
        double[] dg = new double[1];
        double g = g(th, dg);
        double v = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        ctx.addFlowJ(a, b, rotor.angleIndex(), dg[0] * v);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -g * v * v);
            ctx.addJ(t, a.index(), -2 * g * v);
            ctx.addJ(t, b.index(), 2 * g * v);
            ctx.addJ(t, rotor.angleIndex(), -dg[0] * v * v);
        }
    }

    public double conductance(StateView s) {
        return g(s.x(rotor.angleIndex()), new double[1]);
    }

    public double current(StateView s) {
        return conductance(s) * s.across(a, b);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double v = s.across(a, b);
        return conductance(s) * v * v;
    }
}
