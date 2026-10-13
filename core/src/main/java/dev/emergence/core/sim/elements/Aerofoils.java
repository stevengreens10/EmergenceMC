package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

import java.util.List;

/**
 * The air's forces on the blades of one moving body (a rotor turning about a fixed axis, or a slide), by blade-element
 * theory with momentum-theory inflow.
 * <p>
 * <b>Blade elements.</b> Each element (span s, chord c, area A, at r) meets the air at the relative velocity
 * w = u_air − v_body (v_body = ω n × (r − p) on a rotor, v·a on a slide), less its spanwise part. Its angle of attack
 * α = atan2(w·(s × c), w·c); lift ⟂ w along s × ŵ, drag along ŵ, each ½ρ|w|²A times
 * C_L = sin 2α·(1 + (π − 1)·e^{−(sin α/0.25)²}) (thin-aerofoil slope 2π below ~14°, flat-plate sin 2α after stall) and
 * C_D = C_D0 + 1.8 sin²α — the polar of the ducted rotors, here in free air. All angles of attack (reversed flow,
 * stall) are covered; finite-span effects are not.
 * <p>
 * <b>Inflow</b> (a rotor's blades together): the blades push on the air passing through their disc (area A_d = πR²,
 * R the tip radius) with a thrust T along the axis n, and the air through the disc moves at u_n + v_i, the wind's
 * axial part plus an induced velocity v_i (a solver unknown, uniform over the disc). Momentum theory (Glauert) closes
 * it: T = 2ρA_d·v_i·|u_n + v_i| — the propeller state (v_i along the thrust) and the windmill state (the air slowed,
 * a = −v_i/u_n) — with Buhl's empirical thrust coefficient for heavily loaded windmills, C_T = 8/9 + (4 − 40/9)a +
 * (50/9 − 4)a² beyond a = 0.4 (where momentum theory fails: the turbulent-wake state), C¹-continuous with
 * 4a(1 − a). For ideal (drag-free) blades the shaft power is then −T·(u_n + v_i) = ½ρA_d u_n³·4a(1 − a)²: at most
 * 16/27 of the wind's flux (the Betz limit). Wake rotation, tip losses and radial variation of the inflow are
 * neglected (a uniform actuator disc); the induced velocity is axial only (a rotor edge-on to the wind gets none).
 * <p>
 * <b>A rotor riding a slide</b> ({@link #carriedBy}: a propeller on a cart): each element also moves with the slide,
 * v_body = ω n × (r − p) + v_s·a, so the air's force on it is both a torque about n on the rotor and a force a·F on the
 * slide — the thrust that drives the cart — and the disc meets the air at the wind less the cart's velocity.
 * <p>
 * The air is an outside reservoir: the work it does on the body, Q·q̇ (torque × ω, or force × v, plus the carrier's
 * force × its velocity), is external power.
 */
public final class Aerofoils implements Element {
    /** One blade element at the body's reference pose: centre, unit span and chord, plan area [m²], C_D0. */
    public record Foil(Vec3 centre, Vec3 span, Vec3 chord, double area, double cd0) {}

    private final Node node;
    private final AngleTracker pose;
    private final boolean rotor;
    private final Vec3 axis, pivot;
    private final List<Foil> foils;
    private final double discArea;
    private double rho;
    private Vec3 wind;
    private int vIdx = -1;
    private Node carrier;
    private Vec3 carrierAxis;

    /**
     * @param node      the body's velocity node (ω for a rotor, v for a slide); {@code pose} its angle or displacement
     * @param rotor     true: the body turns about {@code axis} through {@code pivot}; false: it slides along {@code axis}
     * @param discArea  a rotor's swept disc area for momentum-theory inflow [m²] (0: none)
     */
    public Aerofoils(Node node, AngleTracker pose, boolean rotor, Vec3 axis, Vec3 pivot, List<Foil> foils, double discArea,
                     double rho, Vec3 wind) {
        this.node = node;
        this.pose = pose;
        this.rotor = rotor;
        this.axis = axis;
        this.pivot = pivot;
        this.foils = List.copyOf(foils);
        this.discArea = rotor ? discArea : 0;
        this.rho = rho;
        this.wind = wind;
    }

    /**
     * The rotor rides a slide moving along unit {@code axis} with velocity node {@code slide} (a propeller on a cart):
     * the blades move with it, and push it.
     */
    public Aerofoils carriedBy(Node slide, Vec3 axis) {
        this.carrier = slide;
        this.carrierAxis = axis;
        return this;
    }

    /** Sets the wind (world frame) [m/s] and the air's density [kg/m³]. */
    public void setAir(Vec3 wind, double rho) {
        this.wind = wind;
        this.rho = rho;
    }

    public Vec3 wind() {
        return wind;
    }

    public double discArea() {
        return discArea;
    }

    @Override
    public void setup(SetupContext ctx) {
        if (discArea > 0) vIdx = ctx.allocUnknown("v_induced", 1e-5);
    }

    /** C_L and C_D at angle of attack α. */
    public static double[] polar(double alpha, double cd0) {
        double sa = Math.sin(alpha), u = sa / 0.25;
        return new double[]{Math.sin(2 * alpha) * (1 + (Math.PI - 1) * Math.exp(-u * u)), cd0 + 1.8 * sa * sa};
    }

    /**
     * [generalised force on the body (torque about n, or force along a), thrust on the air along n, force on the
     * carrier slide along its axis], the carrier moving at {@code vs}.
     */
    double[] forces(double q, double rate, double vInduced, double vs) {
        double gen = 0, thrust = 0, push = 0;
        Vec3 air = wind.add(axis.mul(rotor ? vInduced : 0));
        Vec3 vc = carrier == null ? Vec3.ZERO : carrierAxis.mul(vs);
        for (Foil f : foils) {
            Vec3 r, s, c, vb;
            if (rotor) {
                r = f.centre.sub(pivot).rotateAbout(axis, q);
                s = f.span.rotateAbout(axis, q);
                c = f.chord.rotateAbout(axis, q);
                vb = axis.cross(r).mul(rate).add(vc);
            } else {
                r = null;
                s = f.span;
                c = f.chord;
                vb = axis.mul(rate);
            }
            Vec3 w = air.sub(vb);
            w = w.sub(s.mul(s.dot(w)));
            double ww = w.lengthSq();
            if (ww < 1e-18) continue;
            double wm = Math.sqrt(ww);
            Vec3 e = w.mul(1 / wm);
            double alpha = Math.atan2(w.dot(s.cross(c)), w.dot(c));
            double[] cl = polar(alpha, f.cd0);
            double dyn = 0.5 * rho * ww * f.area;
            Vec3 force = s.cross(e).mul(dyn * cl[0]).add(e.mul(dyn * cl[1]));
            if (rotor) {
                gen += axis.dot(r.cross(force));
                thrust -= force.dot(axis);
                if (carrier != null) push += force.dot(carrierAxis);
            } else {
                gen += force.dot(axis);
            }
        }
        return new double[]{gen, thrust, push};
    }

    double[] forces(double q, double rate, double vInduced) {
        return forces(q, rate, vInduced, 0);
    }

    /** The wind's part along the axis, as the disc meets it (less a carrier's own velocity). */
    private double axialWind(double vs) {
        return wind.dot(axis) - (carrier == null ? 0 : vs * carrierAxis.dot(axis));
    }

    /**
     * Thrust momentum theory requires on the air for an induced velocity v along n, the wind's axial part being un:
     * Glauert's 2ρA·v·|u_n + v|, with Buhl's correction for windmills slowing the air by more than 40 %.
     */
    double momentumThrust(double v, double un) {
        double s = un >= 0 ? 1 : -1, big = Math.abs(un), u = s * v;
        if (big < 1e-9 || u >= -0.4 * big) return 2 * rho * discArea * v * Math.abs(un + v);
        double a = -u / big, ct = 8.0 / 9 + (4 - 40.0 / 9) * a + (50.0 / 9 - 4) * a * a;
        return -s * ct * 0.5 * rho * discArea * big * big;
    }

    @Override
    public void load(LoadContext ctx) {
        int iq = pose.angleIndex(), in = node.index(), ic = carrier == null ? -1 : carrier.index();
        double q = ctx.x(iq), rate = ctx.x(in), vi = vIdx >= 0 ? ctx.x(vIdx) : 0, vs = ic < 0 ? 0 : ctx.x(ic);
        double[] f0 = forces(q, rate, vi, vs);
        double un = axialWind(vs);
        // finite-difference slopes in q, rate, the induced velocity and the carrier's velocity
        double hq = 1e-6 * (rotor ? 1 : Math.max(1, Math.abs(q))), hr = 1e-6 * Math.max(1, Math.abs(rate)), hv = 1e-6 * Math.max(1, Math.abs(vi));
        double hs = 1e-6 * Math.max(1, Math.abs(vs));
        double[] fq = forces(q + hq, rate, vi, vs), fr = forces(q, rate + hr, vi, vs);
        double[] fs = ic < 0 ? null : forces(q, rate, vi, vs + hs);
        ctx.addF(in, -f0[0]);
        ctx.addJ(in, iq, -(fq[0] - f0[0]) / hq);
        ctx.addJ(in, in, -(fr[0] - f0[0]) / hr);
        if (ic >= 0) {
            ctx.addJ(in, ic, -(fs[0] - f0[0]) / hs);
            ctx.addF(ic, -f0[2]);
            ctx.addJ(ic, iq, -(fq[2] - f0[2]) / hq);
            ctx.addJ(ic, in, -(fr[2] - f0[2]) / hr);
            ctx.addJ(ic, ic, -(fs[2] - f0[2]) / hs);
        }
        if (vIdx < 0) return;
        double[] fv = forces(q, rate, vi + hv, vs);
        ctx.addJ(in, vIdx, -(fv[0] - f0[0]) / hv);
        if (ic >= 0) ctx.addJ(ic, vIdx, -(fv[2] - f0[2]) / hv);
        // inflow: the blades' thrust on the air equals what momentum theory needs for this induced velocity
        double scale = 1 / (rho * discArea * Math.max(1, Math.abs(un) + Math.abs(vi)));
        double m0 = momentumThrust(vi, un), mv = momentumThrust(vi + hv, un);
        ctx.addF(vIdx, (f0[1] - m0) * scale);
        ctx.addJ(vIdx, iq, (fq[1] - f0[1]) / hq * scale);
        ctx.addJ(vIdx, in, (fr[1] - f0[1]) / hr * scale);
        ctx.addJ(vIdx, vIdx, ((fv[1] - f0[1]) - (mv - m0)) / hv * scale);
        if (ic >= 0) ctx.addJ(vIdx, ic, ((fs[1] - f0[1]) - (momentumThrust(vi, axialWind(vs + hs)) - m0)) / hs * scale);
    }

    private double[] now(StateView s) {
        return forces(s.x(pose.angleIndex()), s.x(node.index()), vIdx >= 0 ? s.x(vIdx) : 0, carrier == null ? 0 : s.x(carrier.index()));
    }

    /** Force the air exerts on a carrier slide along its axis [N] (0 without one). */
    public double carrierForce(StateView s) {
        return now(s)[2];
    }

    /** Torque (rotor) or force (slide) the air exerts on the body [N·m or N]. */
    public double generalisedForce(StateView s) {
        return now(s)[0];
    }

    /** Thrust the blades put on the air along the rotor's axis [N]. */
    public double thrust(StateView s) {
        return now(s)[1];
    }

    /** Induced velocity through the disc, along the axis [m/s] (0 without inflow modelling). */
    public double inducedVelocity(StateView s) {
        return vIdx >= 0 ? s.x(vIdx) : 0;
    }

    @Override
    public double externalPower(StateView s) {
        double[] f = now(s);
        return f[0] * s.x(node.index()) + (carrier == null ? 0 : f[2] * s.x(carrier.index()));
    }

    @Override
    public String label() {
        return "aerofoils";
    }
}
