package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Integrates a rotational node's angular velocity into an absolute angle unknown θ (dθ/dt = ω),
 * or a translational node's velocity into a position. Geometry-dependent couplings (magnetic flux
 * linkage, cams, crank kinematics) read this angle. Carries no energy.
 */
public final class AngleTracker implements Element {
    private final Node node;
    private final double initialAngle;
    private int idx, slot;

    public AngleTracker(Node node, double initialAngle) {
        this.node = node;
        this.initialAngle = initialAngle;
    }

    @Override
    public void setup(SetupContext ctx) {
        idx = ctx.allocUnknown("theta(" + node.name() + ")", 1e-9);
        slot = ctx.allocState(1e-6);
        ctx.setInitial(idx, initialAngle);
    }

    public int angleIndex() {
        return idx;
    }

    public Node node() {
        return node;
    }

    @Override
    public void load(LoadContext ctx) {
        double th = ctx.x(idx);
        double rate = ctx.ddt(slot, th);
        ctx.addF(idx, rate - ctx.x(node.index()));
        ctx.addJ(idx, idx, ctx.alpha());
        ctx.addJ(idx, node.index(), -1);
    }

    public double angle(StateView s) {
        return s.x(idx);
    }
}
