package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A weight sliding on a turning arm: angular momentum, energy, and the spring-held radius at speed. */
class CarriedSlideTest {
    static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);

    record Rig(Network net, Node v, Node w, AngleTracker x, AngleTracker th, CarriedSlide cs, double j0) {}

    static Rig rig(double j0, double m, Vec3 axis, Vec3 guide, Vec3 offset, Vec3 g, double w0, double k, double c) {
        Network net = new Network();
        Node v = net.node(Domain.TRANSLATIONAL, "v", 0), w = net.node(Domain.ROTATIONAL, "w", w0);
        net.add(new Inertia(v, m));
        net.add(new Inertia(w, j0));
        AngleTracker x = net.add(new AngleTracker(v, 0)), th = net.add(new AngleTracker(w, 0));
        CarriedSlide cs = net.add(new CarriedSlide(v, x, w, th, axis, guide, offset, m, 1e-5, g));
        if (k > 0) net.add(new PositionSpring(v, x, 1, null, null, 0, k));
        if (c > 0) net.add(new Damper(v, net.reference(Domain.TRANSLATIONAL), c, null));
        return new Rig(net, v, w, x, th, cs, j0);
    }

    static double angularMomentum(Rig r, TransientSolver s) {
        return r.j0 * s.x(r.w.index()) + r.cs.angularMomentum(s);
    }

    /**
     * A turntable spinning freely with a weight on a radial guide, held in by a spring and damped: the weight moves out
     * and settles where the spring supplies the centripetal force, k·x = m·ω²·r; the damper takes energy but the
     * angular momentum is kept, (J₀ + J_s + m·r²)·ω = L₀ — so the turntable slows as the weight goes out.
     */
    @Test
    void weightMovesOutUntilTheSpringHoldsItAndAngularMomentumIsKept() {
        double j0 = 0.01, m = 0.5, r0 = 0.1, k = 200, w0 = 15;
        Rig r = rig(j0, m, Z, X, new Vec3(r0, 0, 0), new Vec3(0, 0, -9.81), w0, k, 5);
        TransientSolver s = new TransientSolver(r.net);
        double l0 = angularMomentum(r, s), e0 = s.storedEnergy(), worst = 0;
        for (int i = 0; i < 4000; i++) { // 1 ms steps: ω·h ≤ 0.015
            s.advance(1e-3);
            worst = Math.max(worst, Math.abs(angularMomentum(r, s) - l0));
        }
        double xs = s.x(r.x.angleIndex()), w = s.x(r.w.index()), rad = r0 + xs;
        System.out.printf("spinning weight: ω %.3f → %.3f rad/s, x %.4f m; k·x %.3f N vs m·ω²·r %.3f N; |ΔL| ≤ %.2g of %.3f; energy %.3f → %.3f J (error %.2g)%n",
                w0, w, xs, k * xs, m * w * w * rad, worst, l0, e0, s.storedEnergy(), s.energyError());
        assertEquals(k * xs, m * w * w * rad, 1e-3 * k * xs, "the spring holds the weight's centripetal force");
        assertEquals(l0, (j0 + 1e-5 + m * rad * rad) * w, 1e-6 * l0, "angular momentum at the new radius");
        assertTrue(worst < 1e-6 * l0, "angular momentum kept throughout");
        assertTrue(w < 0.8 * w0, "the turntable slowed as the weight moved out");
        assertTrue(Math.abs(s.energyError()) < 1e-4 * e0, "energy ledger closes (the damper's heat accounted)");
    }

    /**
     * A vertical turntable (axis horizontal) with a weight on a guide set off the radial line (σ ≠ 0), on a spring, in
     * gravity: a tumbling, coupled motion; energy is conserved, and without gravity angular momentum too.
     */
    @Test
    void offRadialGuideConservesEnergyInGravityAndAngularMomentumWithout() {
        for (boolean gravity : new boolean[]{true, false}) {
            Vec3 g = gravity ? new Vec3(0, -9.81, 0) : new Vec3(0, 0, 0);
            Rig r = rig(0.02, 0.4, Z, X, new Vec3(0.08, 0.05, 0), g, 6, 300, 0);
            TransientSolver s = new TransientSolver(r.net);
            double l0 = angularMomentum(r, s), e0 = s.storedEnergy(), worst = 0;
            for (int i = 0; i < 8000; i++) {
                s.advance(2.5e-4);
                worst = Math.max(worst, Math.abs(angularMomentum(r, s) - l0));
            }
            System.out.printf("off-radial (gravity %s): ω %.3f rad/s, x %.4f m; energy error %.2g of %.3f J; |ΔL| ≤ %.2g of %.4f%n", gravity,
                    s.x(r.w.index()), s.x(r.x.angleIndex()), s.energyError(), e0, worst, l0);
            assertTrue(Math.abs(s.energyError()) < 1e-4 * Math.abs(e0) + 1e-6, "energy conserved");
            if (!gravity) assertTrue(worst < 1e-6 * Math.abs(l0), "angular momentum conserved");
        }
    }
}
