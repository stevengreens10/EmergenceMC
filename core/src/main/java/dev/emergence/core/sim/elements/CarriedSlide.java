package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * A slide running in bearings on a turning body (a prismatic joint carried by a revolute one: a flyball's weight on
 * its arm, a bead on a spinning rod). Coordinates: the rotor's angle θ about unit axis n through pivot p, and the
 * slide's displacement x along its guide, unit u₀ in the rotor's frame (θ = 0). The slide's centre of mass sits at
 * q(x) = c₀ + x·u₀ from the pivot in that frame, at R(θ)·q in the world. With ρ² = |q⊥|² its squared distance from
 * the axis and σ = u₀·(n × c₀) (the guide's offset from a radial line; u₀·(n × u₀) = 0 makes it constant), the
 * kinetic energy is
 * <pre>T = ½m ẋ² + ½(J₀ + J_s + m ρ²(x)) θ̇² + m σ ẋ θ̇</pre>
 * (J₀ the rotor's own inertia, J_s the slide's about its centre of mass, parallel to n). The slide's and rotor's own
 * {@link Inertia} elements hold ½mẋ² and ½J₀θ̇²; this element is the rest, with gravity on the slide, V = −m g·R(θ)q.
 * Lagrange: on the slide d(mσθ̇)/dt − ½mθ̇²·dρ²/dx − m g·R u₀ (centrifugal force, the guide's tilt into the turn,
 * weight along the guide); on the rotor d((J_s + mρ²)θ̇ + mσẋ)/dt − m g·(n × R q) (the varying inertia — a weight
 * moving out slows the rotor, angular momentum being kept — Coriolis, and the weight's torque). Stored energy
 * ½(J_s + mρ²)θ̇² + mσẋθ̇ + V; the power balance is exact, so energy and angular momentum are conserved.
 */
public final class CarriedSlide implements Element {
    private final Node v, w;
    private final AngleTracker x, theta;
    private final Vec3 n, u0, c0, g;
    private final double mass, jOwn, sigma;
    private int slotSlide, slotRotor;

    /**
     * @param slideVelocity node of ẋ (relative to the rotor); {@code position} its tracker
     * @param omega         the rotor's angular velocity node; {@code angle} its tracker
     * @param axis          unit rotation axis n
     * @param guide         unit direction u₀ of the slide's travel, at rotor angle 0
     * @param offset        the slide's centre of mass from a point on the rotor's axis, at x = 0, θ = 0 [m]
     * @param jOwn          the slide's moment of inertia about an axis parallel to n through its centre of mass
     * @param g             gravitational acceleration [m/s²]
     */
    public CarriedSlide(Node slideVelocity, AngleTracker position, Node omega, AngleTracker angle, Vec3 axis, Vec3 guide, Vec3 offset,
                        double mass, double jOwn, Vec3 g) {
        this.v = slideVelocity;
        this.x = position;
        this.w = omega;
        this.theta = angle;
        this.n = axis;
        this.u0 = guide;
        this.c0 = offset;
        this.mass = mass;
        this.jOwn = jOwn;
        this.g = g;
        this.sigma = guide.dot(axis.cross(offset));
    }

    private Vec3 q(double xs) {
        return c0.add(u0.mul(xs));
    }

    /** Squared distance of the slide's centre of mass from the rotor's axis at displacement x [m²]. */
    public double radiusSq(double xs) {
        Vec3 q = q(xs);
        double a = n.dot(q);
        return q.lengthSq() - a * a;
    }

    private double dRadiusSq(double xs) {
        Vec3 q = q(xs);
        return 2 * (q.dot(u0) - n.dot(q) * n.dot(u0));
    }

    /** The rotor's inertia this slide adds at displacement x (about the rotor's axis) [kg·m²]. */
    public double inertia(double xs) {
        return jOwn + mass * radiusSq(xs);
    }

    /** Angular momentum about the rotor's axis held by the slide's motion, beyond the rotor's own: (J_s + mρ²)θ̇ + mσẋ. */
    public double angularMomentum(StateView s) {
        return inertia(s.x(x.angleIndex())) * s.x(w.index()) + mass * sigma * s.x(v.index());
    }

    @Override
    public void setup(SetupContext ctx) {
        double r = Math.max(c0.length(), 1e-3);
        slotSlide = ctx.allocState(mass * r * 1e-5);
        slotRotor = ctx.allocState(Math.max(mass * r * r, 1e-9) * 1e-5);
    }

    @Override
    public void load(LoadContext ctx) {
        int iv = v.index(), iw = w.index(), ix = x.angleIndex(), it = theta.angleIndex();
        double xs = ctx.x(ix), th = ctx.x(it), vv = ctx.x(iv), ww = ctx.x(iw), al = ctx.alpha();
        double d = dRadiusSq(xs), d2 = 2 * (u0.lengthSq() - n.dot(u0) * n.dot(u0)), jx = inertia(xs);
        Vec3 ru = u0.rotateAbout(n, th), rq = q(xs).rotateAbout(n, th);
        Vec3 mg = g.mul(mass);
        double fg = mg.dot(ru), dfg = mg.dot(n.cross(ru));             // weight along the guide, and d/dθ
        double tg = mg.dot(n.cross(rq));                                // the weight's torque about the axis
        double dtgTh = mg.dot(n.cross(n.cross(rq))), dtgX = mg.dot(n.cross(ru));
        // slide: d(mσω)/dt − ½mω²·dρ²/dx − weight along the guide
        double dSlide = ctx.ddt(slotSlide, mass * sigma * ww);
        ctx.addF(iv, dSlide - 0.5 * mass * ww * ww * d - fg);
        ctx.addJ(iv, iw, al * mass * sigma - mass * ww * d);
        ctx.addJ(iv, ix, -0.5 * mass * ww * ww * d2);
        ctx.addJ(iv, it, -dfg);
        // rotor: d((J_s + mρ²)ω + mσv)/dt − the weight's torque
        double dRotor = ctx.ddt(slotRotor, jx * ww + mass * sigma * vv);
        ctx.addF(iw, dRotor - tg);
        ctx.addJ(iw, iw, al * jx);
        ctx.addJ(iw, iv, al * mass * sigma);
        ctx.addJ(iw, ix, al * mass * d * ww - dtgX);
        ctx.addJ(iw, it, -dtgTh);
    }

    @Override
    public double storedEnergy(StateView s) {
        double xs = s.x(x.angleIndex()), th = s.x(theta.angleIndex()), ww = s.x(w.index());
        return 0.5 * inertia(xs) * ww * ww + mass * sigma * s.x(v.index()) * ww - g.mul(mass).dot(q(xs).rotateAbout(n, th));
    }

    @Override
    public String label() {
        return "carried slide";
    }
}
