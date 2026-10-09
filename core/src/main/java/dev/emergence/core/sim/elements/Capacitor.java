package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/** Linear capacitor: charge q = C·(v_a − v_b), current i = dq/dt flowing a → b. */
public final class Capacitor implements Element {
    private final Node a, b;
    private final double c;
    private int slot;

    public Capacitor(Node a, Node b, double capacitance) {
        if (!(capacitance > 0)) throw new IllegalArgumentException("C must be > 0");
        this.a = a;
        this.b = b;
        this.c = capacitance;
    }

    @Override
    public void setup(SetupContext ctx) {
        // truncation-error floor: the charge of 1 mV. Newton resolves node voltages only to ~1e-4 relative, so a
        // tighter floor makes the step controller chase Newton noise on nearly floating nodes (e.g. the
        // internal node of a stack of off transistors) and collapse the time step
        slot = ctx.allocState(c * 1e-3);
    }

    @Override
    public void load(LoadContext ctx) {
        double v = ctx.x(a.index()) - ctx.x(b.index());
        double i = ctx.ddt(slot, c * v);
        ctx.addFlow(a, b, i);
        ctx.stampConductance(a, b, ctx.alpha() * c);
    }

    public double voltage(StateView s) {
        return s.across(a, b);
    }

    public double current(StateView s) {
        return s.qdot(slot);
    }

    @Override
    public double storedEnergy(StateView s) {
        double v = s.across(a, b);
        return 0.5 * c * v * v;
    }
}
