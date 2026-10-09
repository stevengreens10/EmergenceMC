package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Gravity on a body turning about a fixed axis: its centre of mass, offset r₀ from the axis (perpendicular part, in
 * the body's reference orientation), turns with the body; the torque about the axis is τ = n·(r(θ) × m g), with
 * r(θ) the offset rotated by θ about n. It derives from the potential U(θ) = −m g·r(θ) (stored energy), so a
 * pendulum swings without gaining or losing energy and a lever lifts a load against it.
 */
public final class GravityTorque implements Element {
    private final Node omega;
    private final AngleTracker angle;
    private final Vec3 axis, r0, g;
    private final double mass;

    /**
     * @param axis   unit rotation axis
     * @param offset centre of mass minus a point on the axis, in the reference orientation (θ = 0) [m]
     * @param g      gravitational acceleration vector [m/s²]
     */
    public GravityTorque(Node omega, AngleTracker angle, Vec3 axis, Vec3 offset, double mass, Vec3 g) {
        this.omega = omega;
        this.angle = angle;
        this.axis = axis;
        this.r0 = offset.sub(axis.mul(offset.dot(axis)));
        this.mass = mass;
        this.g = g;
    }

    /** Offset of the centre of mass at angle θ (Rodrigues rotation about the axis). */
    private Vec3 r(double th) {
        double c = Math.cos(th), s = Math.sin(th);
        return r0.mul(c).add(axis.cross(r0).mul(s));
    }

    double torque(double th) {
        return axis.dot(r(th).cross(g.mul(mass)));
    }

    @Override
    public void load(LoadContext ctx) {
        int ia = angle.angleIndex();
        double th = ctx.x(ia), tq = torque(th);
        // dτ/dθ: r'(θ) = n × r(θ)
        Vec3 dr = axis.cross(r(th));
        double dtq = axis.dot(dr.cross(g.mul(mass)));
        // a torque driving the body's angular velocity node (as an external source of flow)
        ctx.addF(omega.index(), -tq);
        ctx.addJ(omega.index(), ia, -dtq);
    }

    /** Height of the centre of mass above the axis point, along −g. */
    @Override
    public double storedEnergy(StateView s) {
        return -mass * g.dot(r(s.x(angle.angleIndex())));
    }
}
