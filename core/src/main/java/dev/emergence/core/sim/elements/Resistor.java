package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Linear resistor i = (v_a − v_b)/R flowing a → b. Joule heat P = v²/R is deposited into
 * {@code thermal} when given (energy-conserving electro-thermal coupling); otherwise it is reported as
 * an untracked loss. For temperature-dependent conductors made of real materials see {@link Conductor}.
 */
public final class Resistor implements Element {
    private final Node a, b, thermal;
    private double g;

    public Resistor(Node a, Node b, double resistance, Node thermal) {
        if (!(resistance > 0)) throw new IllegalArgumentException("R must be > 0");
        this.a = a;
        this.b = b;
        this.g = 1.0 / resistance;
        this.thermal = thermal;
    }

    public Resistor(Node a, Node b, double resistance) {
        this(a, b, resistance, null);
    }

    public void setResistance(double r) {
        this.g = 1.0 / r;
    }

    public double resistance() {
        return 1.0 / g;
    }

    @Override
    public void load(LoadContext ctx) {
        double v = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -g * v * v);
            ctx.addJ(t, a.index(), -2 * g * v);
            ctx.addJ(t, b.index(), 2 * g * v);
        }
    }

    public double current(StateView s) {
        return g * s.across(a, b);
    }

    public double power(StateView s) {
        double v = s.across(a, b);
        return g * v * v;
    }

    @Override
    public double untrackedLoss(StateView s) {
        return thermal == null ? power(s) : 0.0;
    }
}
