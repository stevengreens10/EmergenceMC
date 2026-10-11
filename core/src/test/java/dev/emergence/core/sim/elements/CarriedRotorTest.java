package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An unbalanced rotor on a slide: conservation, and forced vibration against the textbook solution. */
class CarriedRotorTest {
    static final Vec3 X = new Vec3(1, 0, 0), Z = new Vec3(0, 0, 1);
    static final double M = 2, m = 0.5, e = 0.05;

    record Rig(Network net, Node v, Node w, AngleTracker x, AngleTracker th, CarriedRotor cr) {}

    /** A slide of mass M along x carrying a rotor (axis z) of mass m whose centre of mass is e off its axis. */
    static Rig rig(double jOwn, double w0) {
        Network net = new Network();
        Node v = net.node(Domain.TRANSLATIONAL, "v", 0), w = net.node(Domain.ROTATIONAL, "w", w0);
        net.add(new Inertia(v, M + m));
        net.add(new Inertia(w, jOwn + m * e * e));
        AngleTracker x = net.add(new AngleTracker(v, 0)), th = net.add(new AngleTracker(w, 0));
        CarriedRotor cr = net.add(new CarriedRotor(v, X, w, th, Z, new Vec3(e, 0, 0), m));
        return new Rig(net, v, w, x, th, cr);
    }

    /** Free: no force along the slide, so its momentum (M+m)v + m·s·ω is constant, the centre of mass stays put. */
    @Test
    void freeSlideConservesMomentumAndEnergy() {
        Rig r = rig(1e-4, 60);
        TransientSolver s = new TransientSolver(r.net);
        double e0 = s.storedEnergy(), lo = 0, hi = 0, worstP = 0;
        // ¼ ms (ω·h = 0.015): the trapezoidal rule loses 1.4·10⁻⁶ of the energy over 120 turns here (6·10⁻⁴ at 1 ms,
        // where the turning inertia's nonlinearity is less well resolved)
        double dt = 2.5e-4;
        for (int k = 0; k < Math.round(2 / dt); k++) {
            s.advance(dt);
            double p = (M + m) * s.x(r.v.index()) + m * r.cr.lever(s.x(r.th.angleIndex())) * s.x(r.w.index());
            worstP = Math.max(worstP, Math.abs(p));
            double xs = s.x(r.x.angleIndex());
            lo = Math.min(lo, xs);
            hi = Math.max(hi, xs);
            // the centre of mass: (M+m)·x + m·e·(cos θ − 1) stays at 0
            double com = (M + m) * xs + m * e * (Math.cos(s.x(r.th.angleIndex())) - 1);
            assertEquals(0, com, 5e-5, "the centre of mass stays put (to the integrator's accuracy)");
        }
        System.out.printf("free: stroke %.5f m (expect %.5f), |p| ≤ %.2g, energy %.6f → %.6f J, error %.2g%n", hi - lo,
                2 * m * e / (M + m), worstP, e0, s.storedEnergy(), s.energyError());
        assertEquals(2 * m * e / (M + m), hi - lo, 1e-4, "the slide swings 2me/(M+m) peak to peak");
        assertTrue(worstP < 1e-6, "momentum along the slide conserved");
        assertTrue(Math.abs(s.energyError()) < 1e-5 * e0, "energy conserved");
    }

    /** On a spring and damper, the rotor (a heavy flywheel, turning steadily) shakes it at m·e·ω²/|k − Mω² + icω|. */
    @Test
    void sprungSlideFollowsForcedVibrationAmplitude() {
        double w0 = 2 * Math.PI * 8, mt = M + m, k = mt * Math.pow(2 * Math.PI * 5, 2), c = 2 * 0.05 * Math.sqrt(k * mt);
        Rig r = rig(100, w0);
        r.net.add(new PositionSpring(r.v, r.x, 1, null, null, 0, k));
        r.net.add(new Damper(r.v, r.net.reference(Domain.TRANSLATIONAL), c, null));
        TransientSolver s = new TransientSolver(r.net);
        s.advance(6);
        double lo = 0, hi = 0;
        for (int i = 0; i < 1000; i++) {
            s.advance(1e-3);
            lo = Math.min(lo, s.x(r.x.angleIndex()));
            hi = Math.max(hi, s.x(r.x.angleIndex()));
        }
        double w = s.x(r.w.index()), expect = m * e * w * w / Math.hypot(k - mt * w * w, c * w);
        System.out.printf("forced: ω %.2f rad/s, amplitude %.5f m (expect %.5f)%n", w, (hi - lo) / 2, expect);
        assertEquals(expect, (hi - lo) / 2, 0.01 * expect, "steady forced amplitude");
    }
}
