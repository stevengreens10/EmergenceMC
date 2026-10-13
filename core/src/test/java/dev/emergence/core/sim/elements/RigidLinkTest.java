package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Rigid links: a four-bar linkage's rocker follows the crank as geometry says; a slider-crank's slide likewise. */
class RigidLinkTest {
    static final Vec3 Z = new Vec3(0, 0, 1), X = new Vec3(1, 0, 0);

    /** The rocker angle of a four-bar (ground pivots at 0 and (d, 0)) for crank angle t2, on the upper branch. */
    static double rocker(double t2, double a, double b, double c, double d) {
        double ax = a * Math.cos(t2), ay = a * Math.sin(t2);
        // B on the circle radius c about (d, 0) and radius b about A: intersect
        double dx = d - ax, dy = -ay, dist = Math.hypot(dx, dy);
        double along = (b * b - c * c + dist * dist) / (2 * dist), h = Math.sqrt(Math.max(0, b * b - along * along));
        double mx = ax + along * dx / dist, my = ay + along * dy / dist;
        double bx = mx - h * dy / dist, by = my + h * dx / dist; // the upper intersection
        if (by < 0) {
            bx = mx + h * dy / dist;
            by = my - h * dx / dist;
        }
        return Math.atan2(by, bx - d);
    }

    @Test
    void fourBarRockerFollowsTheCrank() {
        double a = 0.1, b = 0.3, c = 0.25, d = 0.3, w = 5;
        Network net = new Network();
        Node crank = net.node(Domain.ROTATIONAL, "crank", w), rock = net.node(Domain.ROTATIONAL, "rocker", 0);
        net.add(new Inertia(crank, 1e6)); // driven at its speed
        net.add(new Inertia(rock, 0.01));
        AngleTracker t2 = net.add(new AngleTracker(crank, 0));
        double t40 = rocker(0, a, b, c, d);
        AngleTracker t4 = net.add(new AngleTracker(rock, 0)); // its tracked angle is measured from the build pose
        Vec3 pinA = new Vec3(a, 0, 0), o4 = new Vec3(d, 0, 0), pinB = o4.add(new Vec3(c * Math.cos(t40), c * Math.sin(t40), 0));
        RigidLink link = net.add(new RigidLink(RigidLink.End.rotor(crank, t2, Z, Vec3.ZERO, pinA), RigidLink.End.rotor(rock, t4, Z, o4, pinB)));
        TransientSolver s = new TransientSolver(net);
        double worst = 0, worstLen = 0;
        for (int i = 0; i < 400; i++) {
            s.advance(0.005);
            double expect = rocker(s.x(t2.angleIndex()), a, b, c, d), got = t40 + s.x(t4.angleIndex());
            worst = Math.max(worst, Math.abs(Math.atan2(Math.sin(got - expect), Math.cos(got - expect))));
            worstLen = Math.max(worstLen, Math.abs(link.length(s) - b));
        }
        System.out.printf("four-bar: rocker within %.2e rad of the geometry over 2 s (%.1f crank turns); link length within %.2e m; energy error %.3g J%n",
                worst, 10 / (2 * Math.PI), worstLen, s.energyError());
        assertTrue(worst < 1e-3, "the rocker follows the four-bar's geometry: " + worst);
        assertTrue(worstLen < 1e-5, "the link keeps its length: " + worstLen);
    }

    /** A slider-crank built from a rotor, a slide and a link: the slide's travel is r + l − (r cos θ + √(l² − r² sin² θ)). */
    @Test
    void sliderCrankFollowsTheCrank() {
        double r = 0.05, l = 0.2, w = 8;
        Network net = new Network();
        Node crank = net.node(Domain.ROTATIONAL, "crank", w), v = net.node(Domain.TRANSLATIONAL, "v", 0);
        net.add(new Inertia(crank, 1e6));
        net.add(new Inertia(v, 0.5));
        AngleTracker th = net.add(new AngleTracker(crank, 0));
        AngleTracker x = net.add(new AngleTracker(v, 0));
        // crank pin at (r, 0) (top dead centre along +x); the slide's pin at (r + l, 0), sliding along x
        RigidLink link = net.add(new RigidLink(RigidLink.End.rotor(crank, th, Z, Vec3.ZERO, new Vec3(r, 0, 0)),
                RigidLink.End.slide(v, x, X, new Vec3(r + l, 0, 0))));
        TransientSolver s = new TransientSolver(net);
        double worst = 0, peakT = 0;
        for (int i = 0; i < 400; i++) {
            s.advance(0.005);
            double q = s.x(th.angleIndex()), sn = Math.sin(q);
            double expect = r * Math.cos(q) + Math.sqrt(l * l - r * r * sn * sn) - (r + l);
            worst = Math.max(worst, Math.abs(s.x(x.angleIndex()) - expect));
            peakT = Math.max(peakT, Math.abs(link.tension(s)));
        }
        System.out.printf("slider-crank: slide within %.2e m of the geometry; peak rod force %.2f N (≈ m·r·ω²·(1 + r/l) = %.2f N)%n",
                worst, peakT, 0.5 * r * w * w * (1 + r / l));
        assertTrue(worst < 1e-5, "the slide follows the crank: " + worst);
        assertEquals(0.5 * r * w * w * (1 + r / l), peakT, 0.1 * 0.5 * r * w * w * (1 + r / l), "the rod carries the slide's inertia force");
    }
}
