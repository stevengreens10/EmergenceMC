package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Fourier conduction between two thermal nodes: Q̇ = G·(T_a − T_b), with G = k·A/L for a slab or the
 * series combination of half-body resistances plus contact resistance for touching parts.
 */
public final class ThermalConductance implements Element {
    private final Node a, b;
    private final double g;

    public ThermalConductance(Node a, Node b, double conductance) {
        this.a = a;
        this.b = b;
        this.g = conductance;
    }

    @Override
    public void load(LoadContext ctx) {
        double dt = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, g * dt);
        ctx.stampConductance(a, b, g);
    }

    public double heatFlow(StateView s) {
        return g * s.across(a, b);
    }
}
