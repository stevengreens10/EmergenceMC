package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Rigid-body inertia referenced to the inertial frame: angular momentum L = J·ω (rotational) or
 * linear momentum p = m·v (translational). Newton's second law appears as Σ τ = dL/dt at the node.
 */
public final class Inertia implements Element {
    private final Node node;
    private double inertia;
    private int slot;

    /** @param inertia moment of inertia [kg·m²] (rotational node) or mass [kg] (translational node) */
    public Inertia(Node node, double inertia) {
        if (node.domain() != Domain.ROTATIONAL && node.domain() != Domain.TRANSLATIONAL) {
            throw new IllegalArgumentException("Inertia requires a mechanical node");
        }
        if (!(inertia > 0)) throw new IllegalArgumentException("inertia must be > 0");
        this.node = node;
        this.inertia = inertia;
    }

    public double inertia() {
        return inertia;
    }

    @Override
    public void setup(SetupContext ctx) {
        slot = ctx.allocState(inertia * 1e-5);
    }

    @Override
    public void load(LoadContext ctx) {
        double w = ctx.x(node.index());
        double dLdt = ctx.ddt(slot, inertia * w);
        ctx.addF(node.index(), dLdt);
        ctx.addJ(node.index(), node.index(), ctx.alpha() * inertia);
    }

    @Override
    public double storedEnergy(StateView s) {
        double w = s.x(node.index());
        return 0.5 * inertia * w * w;
    }
}
