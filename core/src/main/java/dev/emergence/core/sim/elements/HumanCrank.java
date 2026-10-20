package dev.emergence.core.sim.elements;

import dev.emergence.core.assembly.HumanEffort;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A person turning a crank: a speed-dependent torque source following {@link HumanEffort}'s
 * force–velocity–power relation, evaluated at every Newton iteration (not frozen per game tick) so a
 * light mechanism cannot be flung past the speed a hand can follow. The person is an external energy
 * reservoir: delivered power τ·ω is recorded as external work.
 */
public final class HumanCrank implements Element {
    private final Node node, ref;
    private double radius;
    private int direction; // +1, −1 or 0 (not cranking)
    private boolean sprint;

    public HumanCrank(Node node, Node ref, double radius) {
        this.node = node;
        this.ref = ref;
        this.radius = radius;
    }

    /** Where the hand pushes [m from the axis]: a crank's radius, or where a person grips a wheel. */
    public void setRadius(double radius) {
        this.radius = radius;
    }

    public void set(int direction, boolean sprint) {
        this.direction = Integer.signum(direction);
        this.sprint = sprint;
    }

    public int direction() {
        return direction;
    }

    private double torque(double w) {
        if (direction == 0) return 0;
        // effort only acts while the hand moves (or pushes) in the cranking direction
        double along = w * direction;
        return direction * HumanEffort.crankTorque(radius, Math.max(along, 0), sprint);
    }

    @Override
    public void load(LoadContext ctx) {
        if (direction == 0) return;
        double w = ctx.x(node.index()) - ctx.x(ref.index());
        double tau = torque(w);
        double eps = 1e-4 * Math.max(1, Math.abs(w));
        double d = (torque(w + eps) - torque(w - eps)) / (2 * eps);
        ctx.addFlow(ref, node, tau);
        ctx.addJ(node.index(), node.index(), -d);
        ctx.addJ(node.index(), ref.index(), d);
        ctx.addJ(ref.index(), node.index(), d);
        ctx.addJ(ref.index(), ref.index(), -d);
    }

    @Override
    public double externalPower(StateView s) {
        double w = s.across(node, ref);
        return torque(w) * w;
    }
}
