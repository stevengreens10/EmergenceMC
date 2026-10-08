package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Mechanical contact switch: closed = contact resistance R_on (physically from contact area and
 * material, typically 1–50 mΩ), open = insulation leakage R_off (air gap / insulator body, ~1e12 Ω).
 * Toggling is a discontinuity; callers must notify the solver via {@code markDiscontinuity()}.
 */
public final class Switch implements Element {
    private final Node a, b, thermal;
    private final double rOn, rOff;
    private boolean closed;

    public Switch(Node a, Node b, double rOn, double rOff, boolean closed, Node thermal) {
        this.a = a;
        this.b = b;
        this.rOn = rOn;
        this.rOff = rOff;
        this.closed = closed;
        this.thermal = thermal;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean c) {
        this.closed = c;
    }

    private double g() {
        return closed ? 1.0 / rOn : 1.0 / rOff;
    }

    @Override
    public void load(LoadContext ctx) {
        double g = g();
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
        return g() * s.across(a, b);
    }

    @Override
    public double untrackedLoss(StateView s) {
        double v = s.across(a, b);
        return thermal == null ? g() * v * v : 0.0;
    }
}
