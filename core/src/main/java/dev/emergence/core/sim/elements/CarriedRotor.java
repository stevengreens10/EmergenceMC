package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * A body turning in bearings on a slide (a revolute joint carried by a prismatic one): the coupling between the
 * slide's velocity v (along unit axis a) and the rotor's angular velocity ω (about unit axis n). The rotor's centre
 * of mass, a distance e from its axis, turns with it, r(θ) = e₀ cos θ + (n × e₀) sin θ, so the system's kinetic
 * energy is
 * <pre>T = ½(M + m)v² + ½Jω² + m·s(θ)·v·ω,   s(θ) = a·(n × r(θ))</pre>
 * (M, m: slide and rotor masses, J the rotor's moment of inertia about its axis, which already contains m e²). The
 * first two terms are the slide's and rotor's own {@link Inertia} elements (the slide's carrying both masses); this
 * element is the cross term. Lagrange's equations give, on the slide, d(m s ω)/dt — the unbalance's centrifugal and
 * tangential forces shaking it — and on the rotor d(m s v)/dt − m s′ v ω — the slide's acceleration acting on the
 * offset mass. Its power is d(m s v ω)/dt exactly (stored energy m s v ω), so energy is conserved. A balanced rotor
 * (e = 0) is carried without coupling.
 */
public final class CarriedRotor implements Element {
    private final Node v, w;
    private final AngleTracker angle;
    private final double mass, cosK, sinK;
    private int slotSlide, slotRotor;

    /**
     * @param slideAxis unit axis along which the slide's velocity node is measured
     * @param axis      unit rotation axis of the rotor
     * @param offset    centre of mass minus a point on the rotor's axis, at angle 0 [m]
     * @param mass      the rotor's mass [kg]
     */
    public CarriedRotor(Node slideVelocity, Vec3 slideAxis, Node omega, AngleTracker angle, Vec3 axis, Vec3 offset, double mass) {
        this.v = slideVelocity;
        this.w = omega;
        this.angle = angle;
        this.mass = mass;
        Vec3 e0 = offset.sub(axis.mul(offset.dot(axis)));
        // s(θ) = a·(n × e₀) cos θ − a·e₀ sin θ  (n × (n × e₀) = −e₀)
        this.cosK = slideAxis.dot(axis.cross(e0));
        this.sinK = slideAxis.dot(e0);
    }

    /** s(θ) [m]: the slide-axis velocity of the centre of mass per unit ω. */
    public double lever(double th) {
        return cosK * Math.cos(th) - sinK * Math.sin(th);
    }

    private double dLever(double th) {
        return -cosK * Math.sin(th) - sinK * Math.cos(th);
    }

    @Override
    public void setup(SetupContext ctx) {
        double e = Math.max(Math.hypot(cosK, sinK), 1e-4);
        slotSlide = ctx.allocState(mass * e * 1e-5);
        slotRotor = ctx.allocState(mass * e * 1e-6);
    }

    @Override
    public void load(LoadContext ctx) {
        int iv = v.index(), iw = w.index(), it = angle.angleIndex();
        double th = ctx.x(it), vv = ctx.x(iv), ww = ctx.x(iw);
        double s = lever(th), ds = dLever(th), al = ctx.alpha();
        // slide: d(m s ω)/dt
        double dSlide = ctx.ddt(slotSlide, mass * s * ww);
        ctx.addF(iv, dSlide);
        ctx.addJ(iv, iw, al * mass * s);
        ctx.addJ(iv, it, al * mass * ds * ww);
        // rotor: d(m s v)/dt − m s′ v ω   (s″ = −s)
        double dRotor = ctx.ddt(slotRotor, mass * s * vv);
        ctx.addF(iw, dRotor - mass * ds * vv * ww);
        ctx.addJ(iw, iv, al * mass * s - mass * ds * ww);
        ctx.addJ(iw, it, al * mass * ds * vv + mass * s * vv * ww);
        ctx.addJ(iw, iw, -mass * ds * vv);
    }

    @Override
    public double storedEnergy(StateView s) {
        return mass * lever(s.x(angle.angleIndex())) * s.x(v.index()) * s.x(w.index());
    }

    @Override
    public String label() {
        return "carried rotor";
    }
}
