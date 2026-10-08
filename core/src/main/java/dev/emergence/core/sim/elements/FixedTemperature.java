package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * An isothermal reservoir (the surrounding atmosphere, a large body of water, the ground) holding a
 * thermal node at temperature T_res. Heat absorbed by the reservoir is recorded as energy leaving the
 * network through {@link #externalPower}, so the conservation ledger stays closed.
 */
public final class FixedTemperature implements Element {
    private final Node node;
    private double temperature;
    private int qIdx;

    public FixedTemperature(Node node, double temperature) {
        this.node = node;
        this.temperature = temperature;
    }

    public void setTemperature(double t) {
        this.temperature = t;
    }

    public double temperature() {
        return temperature;
    }

    @Override
    public void setup(SetupContext ctx) {
        qIdx = ctx.allocUnknown("Q(reservoir)", 1e-9);
        ctx.setInitial(node.index(), temperature);
    }

    @Override
    public void load(LoadContext ctx) {
        // qflow = heat flowing from the network node into the reservoir
        double q = ctx.x(qIdx);
        ctx.addF(node.index(), q);
        ctx.addJ(node.index(), qIdx, 1);
        ctx.addF(qIdx, ctx.x(node.index()) - temperature);
        ctx.addJ(qIdx, node.index(), 1);
    }

    /** Heat flow currently absorbed by the reservoir [W]. */
    public double heatAbsorbed(StateView s) {
        return s.x(qIdx);
    }

    @Override
    public double externalPower(StateView s) {
        return -s.x(qIdx);
    }
}
