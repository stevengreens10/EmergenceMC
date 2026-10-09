package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Rotary potentiometer: a resistive track of total resistance R between ends A and B, and a wiper W turned by a
 * shaft. With the wiper at fraction f of the track, R_AW = R·f + r₀ and R_WB = R·(1 − f) + r₀ (r₀ the end/contact
 * resistance); f follows the shaft angle φ over the electrical travel (default 270°), centred on the reference
 * orientation, and stays at the ends beyond it (smoothly). Mechanical end stops 15° past the ends of the track
 * (300° of rotation) push back with a stiff spring and contact damping, as the stop pin of a real potentiometer
 * does. It is the angle sensor of servos and the set-point knob of controllers. Joule and stop heat go to the part.
 */
public final class Potentiometer implements Element {
    private final Node a, b, w, thermal;
    private final AngleTracker shaft;
    private final double r, r0 = 1.0, travel, sense;
    private final double stop, kStop, cStop, knee = Math.toRadians(1);

    /**
     * @param shaft       the angle the wiper follows (null: fixed at mid-track)
     * @param sense       +1 if the wiper moves towards B as that angle increases, −1 if towards A
     * @param stopTorque  stiffness of the end stops [N·m/rad] (0 for none)
     */
    public Potentiometer(Node a, Node b, Node wiper, AngleTracker shaft, double sense, double resistance, double travelRad,
                         double stopTorque, Node thermal) {
        this.sense = sense;
        this.a = a;
        this.b = b;
        this.w = wiper;
        this.shaft = shaft;
        this.r = resistance;
        this.travel = travelRad;
        this.thermal = thermal;
        this.stop = travelRad / 2 + Math.toRadians(15);
        this.kStop = stopTorque;
        this.cStop = stopTorque * 2e-3;
    }

    public Potentiometer(Node a, Node b, Node wiper, AngleTracker shaft, double sense, double resistance, double travelRad, Node thermal) {
        this(a, b, wiper, shaft, sense, resistance, travelRad, 0, thermal);
    }

    /** Wiper fraction and its derivative with respect to the angle (soft limits at the track ends). */
    double[] fraction(double th) {
        double x = 0.5 + sense * th / travel;
        // smooth clamp to [0, 1]: f = softplus-difference with a 1 % rounding at each end
        double e = 0.01;
        double lo = 0.5 * (x + Math.sqrt(x * x + e * e)), dlo = 0.5 * (1 + x / Math.sqrt(x * x + e * e));
        double y = lo - 1, hi = 0.5 * (y + Math.sqrt(y * y + e * e)), dhi = 0.5 * (1 + y / Math.sqrt(y * y + e * e));
        return new double[]{lo - hi, sense * (dlo - dhi * dlo) / travel};
    }

    /** Stop spring: torque magnitude for penetration e (C¹: quadratic over the first degree, then linear), its slope and energy. */
    private double[] spring(double e) {
        if (e <= 0) return new double[]{0, 0, 0};
        if (e < knee) return new double[]{kStop * e * e / (2 * knee), kStop * e / knee, kStop * e * e * e / (6 * knee)};
        return new double[]{kStop * (e - knee / 2), kStop, kStop * (e * e / 2 - knee * e / 2 + knee * knee / 6)};
    }

    /** Contact weight for the damping (0 free, 1 in contact over the first degree). */
    private double[] contact(double e) {
        if (e <= 0) return new double[]{0, 0};
        if (e < knee) return new double[]{e / knee, 1 / knee};
        return new double[]{1, 0};
    }

    @Override
    public void load(LoadContext ctx) {
        int ia = shaft == null ? -1 : shaft.angleIndex();
        double th = ia < 0 ? 0 : ctx.x(ia);
        double[] f = fraction(th);
        double raw = r * f[0] + r0, rwb = r * (1 - f[0]) + r0;
        leg(ctx, a, w, raw, r * f[1], ia);
        leg(ctx, w, b, rwb, -r * f[1], ia);
        if (ia < 0 || kStop <= 0) return;
        // end stops, in the pot's angle φ = sense·θ: τ_φ = −T(φ − φs) + T(−φ − φs), plus damping in contact
        int iw = shaft.node().index();
        double phi = sense * th, om = ctx.x(iw);
        double[] up = spring(phi - stop), dn = spring(-phi - stop), cu = contact(phi - stop), cd = contact(-phi - stop);
        double tq = sense * (-up[0] + dn[0]), dtq = -up[1] - dn[1];
        double cw = cu[0] + cd[0], dcw = sense * (cu[1] - cd[1]);
        // torque on the shaft body: spring + damping −c·w·ω
        ctx.addF(iw, -tq + cStop * cw * om);
        ctx.addJ(iw, ia, -dtq + cStop * dcw * om);
        ctx.addJ(iw, iw, cStop * cw);
        if (thermal != null) {
            ctx.addF(thermal.index(), -cStop * cw * om * om);
            ctx.addJ(thermal.index(), iw, -2 * cStop * cw * om);
            ctx.addJ(thermal.index(), ia, -cStop * dcw * om * om);
        }
    }

    /** A resistor x→y whose resistance depends on the angle (dR/dθ given). */
    private void leg(LoadContext ctx, Node x, Node y, double res, double dres, int ia) {
        double v = ctx.x(x.index()) - ctx.x(y.index()), g = 1 / res, i = g * v;
        ctx.addFlow(x, y, i);
        ctx.stampConductance(x, y, g);
        if (ia >= 0) ctx.addFlowJ(x, y, ia, -v * dres / (res * res));
        if (thermal != null) {
            ctx.addF(thermal.index(), -v * i);
            ctx.addJ(thermal.index(), x.index(), -2 * v * g);
            ctx.addJ(thermal.index(), y.index(), 2 * v * g);
            if (ia >= 0) ctx.addJ(thermal.index(), ia, v * v * dres / (res * res));
        }
    }

    /** Wiper position as a fraction of the track (0 at A, 1 at B). */
    public double wiper(StateView s) {
        return fraction(shaft == null ? 0 : s.x(shaft.angleIndex()))[0];
    }

    @Override
    public double storedEnergy(StateView s) {
        if (shaft == null || kStop <= 0) return 0;
        double phi = sense * s.x(shaft.angleIndex());
        return spring(phi - stop)[2] + spring(-phi - stop)[2];
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double f = wiper(s), raw = r * f + r0, rwb = r * (1 - f) + r0;
        double v1 = s.across(a, w), v2 = s.across(w, b);
        double loss = v1 * v1 / raw + v2 * v2 / rwb;
        if (shaft != null && kStop > 0) {
            double phi = sense * s.x(shaft.angleIndex()), om = s.x(shaft.node());
            loss += cStop * (contact(phi - stop)[0] + contact(-phi - stop)[0]) * om * om;
        }
        return loss;
    }
}
