package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Linear viscous damper between two mechanical nodes: τ = c·(ω_a − ω_b) (or F = c·Δv).
 * Models viscous drag such as lubricated journal bearings (Petroff), fluid couplings and windage at
 * low speed. Dissipated power c·Δω² is delivered to the thermal node if provided.
 */
public final class Damper implements Element {
    private final Node a, b, thermal;
    private double c;

    public Damper(Node a, Node b, double coefficient, Node thermal) {
        this.a = a;
        this.b = b;
        this.c = coefficient;
        this.thermal = thermal;
    }

    public void setCoefficient(double c) {
        this.c = c;
    }

    @Override
    public void load(LoadContext ctx) {
        double dw = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, c * dw);
        ctx.stampConductance(a, b, c);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -c * dw * dw);
            ctx.addJ(t, a.index(), -2 * c * dw);
            ctx.addJ(t, b.index(), 2 * c * dw);
        }
    }

    @Override
    public double untrackedLoss(StateView s) {
        double dw = s.across(a, b);
        return thermal == null ? c * dw * dw : 0.0;
    }
}
