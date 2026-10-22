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
    /** The voltage its truncation error is held to [V]: 1 mV, or 100 mV for a device's own parasitic capacitance. */
    private final double vTol;
    private int slot;

    public Capacitor(Node a, Node b, double capacitance) {
        this(a, b, capacitance, 1e-3);
    }

    private Capacitor(Node a, Node b, double capacitance, double vTol) {
        if (!(capacitance > 0)) throw new IllegalArgumentException("C must be > 0");
        this.a = a;
        this.b = b;
        this.c = capacitance;
        this.vTol = vTol;
    }

    /**
     * A device's own picofarad capacitance (a gate's, a junction's, a pin's): integrated as any capacitor, but its
     * truncation error held only to 100 mV. Its job is to make edges finite and keep nodes defined; resolving its
     * voltage to a millivolt through a nanosecond edge (a MOSFET's drain kicked up by an inductor until the diode
     * clamps it) cost the loudspeaker's driver some forty steps an edge, for a hundred picocoulombs.
     */
    public static Capacitor parasitic(Node a, Node b, double capacitance) {
        return new Capacitor(a, b, capacitance, 0.1);
    }

    @Override
    public String label() {
        return "Capacitor(" + c + " F, " + a.name() + "–" + b.name() + ")";
    }

    @Override
    public void setup(SetupContext ctx) {
        // truncation-error floor: the charge of 1 mV. Newton resolves node voltages only to ~1e-4 relative, so a
        // tighter floor makes the step controller chase Newton noise on nearly floating nodes (e.g. the
        // internal node of a stack of off transistors) and collapse the time step
        slot = ctx.allocState(c * vTol);
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
