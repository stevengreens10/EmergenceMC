package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Lumped thermal mass: internal energy U = C·T with C = m·c_p (constant specific heat, referenced to
 * 0 K). Energy balance Σ Q̇_in = dU/dt. Valid when the body's Biot number h·L/k ≪ 0.1 so internal
 * gradients are negligible; larger bodies are split into several thermal nodes by the assembly
 * compiler.
 */
public final class HeatCapacity implements Element {
    private final Node node;
    private final double c;
    private int slot;

    public HeatCapacity(Node node, double heatCapacity) {
        if (node.domain() != Domain.THERMAL) throw new IllegalArgumentException("HeatCapacity requires a thermal node");
        if (!(heatCapacity > 0)) throw new IllegalArgumentException("C must be > 0");
        this.node = node;
        this.c = heatCapacity;
    }

    public double capacity() {
        return c;
    }

    @Override
    public void setup(SetupContext ctx) {
        slot = ctx.allocState(c * 1e-4);
    }

    @Override
    public void load(LoadContext ctx) {
        double dU = ctx.ddt(slot, c * ctx.x(node.index()));
        ctx.addF(node.index(), dU);
        ctx.addJ(node.index(), node.index(), ctx.alpha() * c);
    }

    @Override
    public double storedEnergy(StateView s) {
        return c * s.x(node.index());
    }
}
