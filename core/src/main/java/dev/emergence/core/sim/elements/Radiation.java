package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Grey-body thermal radiation from a small surface to large isothermal surroundings:
 * Q̇ = ε·σ·A·(T_s⁴ − T_∞⁴) (Stefan–Boltzmann). Exact for a convex body in a large enclosure.
 */
public final class Radiation implements Element {
    private final Node surface, surroundings;
    private final double epsA;

    public Radiation(Node surface, Node surroundings, double emissivity, double area) {
        this.surface = surface;
        this.surroundings = surroundings;
        this.epsA = emissivity * area;
    }

    @Override
    public void load(LoadContext ctx) {
        // bounded for Newton iterates that stray (T⁴ of an absurd temperature would overflow); converged states lie inside
        double ts = Math.max(0, Math.min(2e4, ctx.x(surface.index())));
        double ta = Math.max(0, Math.min(2e4, ctx.x(surroundings.index())));
        double k = PhysicalConstants.STEFAN_BOLTZMANN * epsA;
        double q = k * (ts * ts * ts * ts - ta * ta * ta * ta);
        ctx.addFlow(surface, surroundings, q);
        double ds = 4 * k * ts * ts * ts, da = -4 * k * ta * ta * ta;
        ctx.addJ(surface.index(), surface.index(), ds);
        ctx.addJ(surface.index(), surroundings.index(), da);
        ctx.addJ(surroundings.index(), surface.index(), -ds);
        ctx.addJ(surroundings.index(), surroundings.index(), -da);
    }

    /** The radiating surface (heat flow positive from it to the surroundings). */
    public Node surface() {
        return surface;
    }

    public Node surroundings() {
        return surroundings;
    }

    public double heatFlow(StateView s) {
        double ts = s.x(surface.index()), ta = s.x(surroundings.index());
        return PhysicalConstants.STEFAN_BOLTZMANN * epsA * (ts * ts * ts * ts - ta * ta * ta * ta);
    }
}
