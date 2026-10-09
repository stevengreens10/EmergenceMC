package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * NTC thermistor: a semiconducting ceramic whose resistance falls with temperature, by the β-model
 * R(T) = R₂₅·exp(β(1/T − 1/298.15 K)) (±1 % over 0–100 °C for typical β ≈ 3950 K parts). The temperature is
 * that of its own thermal node — the part and whatever it is clamped to — so it measures heat flows rather
 * than a scripted value; its own Joule heating (self-heating error) is deposited there too.
 */
public final class Thermistor implements Element {
    private final Node a, b, thermal;
    private final double r25, beta;

    public Thermistor(Node a, Node b, double r25, double beta, Node thermal) {
        if (thermal == null) throw new IllegalArgumentException("a thermistor needs a thermal node");
        this.a = a;
        this.b = b;
        this.r25 = r25;
        this.beta = beta;
        this.thermal = thermal;
    }

    public double resistance(double t) {
        return r25 * Math.exp(beta * (1 / Math.max(t, 50) - 1 / 298.15));
    }

    @Override
    public void load(LoadContext ctx) {
        double t = Diode.deviceTemperature(ctx.x(thermal.index())), v = ctx.x(a.index()) - ctx.x(b.index());
        double g = 1 / resistance(t);
        double dg = g * beta / (t * t); // dG/dT
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        ctx.addFlowJ(a, b, thermal.index(), dg * v);
        int th = thermal.index();
        ctx.addF(th, -g * v * v);
        ctx.addJ(th, a.index(), -2 * g * v);
        ctx.addJ(th, b.index(), 2 * g * v);
        ctx.addJ(th, th, -dg * v * v);
    }

    public double current(StateView s) {
        return s.across(a, b) / resistance(s.x(thermal));
    }
}
