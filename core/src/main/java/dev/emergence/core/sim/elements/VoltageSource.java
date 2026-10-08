package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

import java.util.function.DoubleUnaryOperator;

/**
 * Ideal voltage source v_a − v_b = V(t), an external energy reservoir (used for tests, bench supplies
 * and as the generic "effort source" pattern). Its branch current j is the current flowing from the
 * network into terminal a and through the source to b; the power delivered to the network is −V·j.
 */
public final class VoltageSource implements Element {
    private final Node a, b;
    private DoubleUnaryOperator waveform;
    private int jIdx;

    public VoltageSource(Node a, Node b, DoubleUnaryOperator waveform) {
        this.a = a;
        this.b = b;
        this.waveform = waveform;
    }

    public VoltageSource(Node a, Node b, double dc) {
        this(a, b, t -> dc);
    }

    public void setWaveform(DoubleUnaryOperator w) {
        this.waveform = w;
    }

    @Override
    public void setup(SetupContext ctx) {
        jIdx = ctx.allocUnknown("I(Vsrc)", 1e-9);
    }

    @Override
    public void load(LoadContext ctx) {
        double j = ctx.x(jIdx);
        ctx.addF(a.index(), j);
        ctx.addF(b.index(), -j);
        ctx.addJ(a.index(), jIdx, 1);
        ctx.addJ(b.index(), jIdx, -1);
        ctx.addF(jIdx, ctx.x(a.index()) - ctx.x(b.index()) - waveform.applyAsDouble(ctx.time()));
        ctx.addJ(jIdx, a.index(), 1);
        ctx.addJ(jIdx, b.index(), -1);
    }

    /** Current delivered out of terminal a into the external network. */
    public double current(StateView s) {
        return -s.x(jIdx);
    }

    @Override
    public double externalPower(StateView s) {
        return -s.across(a, b) * s.x(jIdx);
    }
}
