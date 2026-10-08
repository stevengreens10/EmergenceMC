package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.function.DoubleUnaryOperator;

/**
 * Ideal through-variable source for any domain: drives flow I(t) out of node {@code to} into the
 * network, drawing it from node {@code from}. Electrically a current source; mechanically a torque or
 * force source; thermally a heat source. Power delivered to the network = I·(x_to − x_from).
 */
public final class FlowSource implements Element {
    private final Node from, to;
    private DoubleUnaryOperator waveform;

    public FlowSource(Node from, Node to, DoubleUnaryOperator waveform) {
        this.from = from;
        this.to = to;
        this.waveform = waveform;
    }

    public FlowSource(Node from, Node to, double value) {
        this(from, to, t -> value);
    }

    public void setWaveform(DoubleUnaryOperator w) {
        this.waveform = w;
    }

    public void setValue(double v) {
        this.waveform = t -> v;
    }

    @Override
    public void load(LoadContext ctx) {
        double i = waveform.applyAsDouble(ctx.time());
        ctx.addF(from.index(), i);
        ctx.addF(to.index(), -i);
    }

    @Override
    public double externalPower(StateView s) {
        return waveform.applyAsDouble(s.time()) * s.across(to, from);
    }
}
