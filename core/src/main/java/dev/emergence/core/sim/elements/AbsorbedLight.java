package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.Arrays;
import java.util.List;

/**
 * Light absorbed by bodies (sunlight, directly or by way of mirrors and lenses): a heat flow into each body's thermal
 * node, from outside the network. The powers are set from the optics; the element just delivers them.
 */
public final class AbsorbedLight implements Element {
    private final Node[] nodes;
    private final double[] power;

    public AbsorbedLight(List<Node> thermalNodes) {
        this.nodes = thermalNodes.toArray(new Node[0]);
        this.power = new double[nodes.length];
    }

    /** Sets the absorbed power [W] for node i. */
    public void set(int i, double watts) {
        power[i] = watts;
    }

    public void clear() {
        Arrays.fill(power, 0);
    }

    public double power(int i) {
        return power[i];
    }

    @Override
    public void load(LoadContext ctx) {
        for (int i = 0; i < nodes.length; i++) if (power[i] != 0) ctx.addF(nodes[i].index(), -power[i]);
    }

    @Override
    public double externalPower(StateView s) {
        double sum = 0;
        for (double p : power) sum += p;
        return sum;
    }

    @Override
    public String label() {
        return "absorbed light";
    }
}
