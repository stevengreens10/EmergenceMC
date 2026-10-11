package dev.emergence.core.acoustics;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.List;

/**
 * Bodies moving through the same air hear one another: each body (a velocity node, translational) is a
 * {@link DipoleRadiator}; the {@link SoundField} the others make, retarded by r/c, pushes on it with
 * F = ρ·M·(∂u/∂t·ŝ). Their velocities are recorded at every accepted step; within a step the force comes from that
 * history alone (the delay outlasts the step), so it is a known external force: the air is a reservoir, and the work it
 * does on each body is counted as external power. The reaction of radiation on a source — its radiated power, of order
 * ρM²Ü²/(12πc³), many orders below its mechanical losses — is not applied to the source (see KNOWN_LIMITATIONS).
 */
public final class AcousticCoupling implements Element {
    private final SoundField field;
    private final List<DipoleRadiator> bodies;
    private final List<Node> nodes;
    private final double[] force;
    private double forceTime = Double.NaN, maxDelay;

    public AcousticCoupling(SoundField field, List<DipoleRadiator> bodies, List<Node> nodes) {
        this.field = field;
        this.bodies = bodies;
        this.nodes = nodes;
        this.force = new double[bodies.size()];
        for (DipoleRadiator a : bodies) {
            field.add(a);
            for (DipoleRadiator b : bodies) maxDelay = Math.max(maxDelay, a.centre().sub(b.centre()).length() / field.soundSpeed());
        }
    }

    public SoundField field() {
        return field;
    }

    private void forcesAt(double t) {
        if (t == forceTime) return;
        for (int i = 0; i < bodies.size(); i++) force[i] = field.force(bodies.get(i), t);
        forceTime = t;
    }

    /** The air's force on body i [N] at time t. */
    public double force(int i, double t) {
        forcesAt(t);
        return force[i];
    }

    @Override
    public void load(LoadContext ctx) {
        forcesAt(ctx.time());
        for (int i = 0; i < nodes.size(); i++) ctx.addF(nodes.get(i).index(), -force[i]);
    }

    @Override
    public void acceptStep(StateView s, double time, double dt) {
        for (int i = 0; i < bodies.size(); i++) {
            DipoleRadiator b = bodies.get(i);
            b.record(time, s.x(nodes.get(i)));
            b.forgetBefore(time - maxDelay - 0.01);
        }
        forceTime = Double.NaN;
    }

    @Override
    public double externalPower(StateView s) {
        forcesAt(s.time());
        double p = 0;
        for (int i = 0; i < nodes.size(); i++) p += force[i] * s.x(nodes.get(i));
        return p;
    }

    @Override
    public String label() {
        return "sound in air";
    }
}
