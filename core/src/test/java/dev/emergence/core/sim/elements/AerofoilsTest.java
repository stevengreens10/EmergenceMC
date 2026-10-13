package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Blades in moving air: a plate's lift, the Betz limit of a windmill, a propeller's figure of merit. */
class AerofoilsTest {
    static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);
    static final double RHO = 1.2;

    /** A flat plate on a vertical slide, pitched 5° in a 10 m/s wind: the lift ½ρV²A·C_L, and only that, lifts it. */
    @Test
    void platePitchedInTheWindIsLifted() {
        Network net = new Network();
        Node v = net.node(Domain.TRANSLATIONAL, "v", 0);
        net.add(new Inertia(v, 1));
        AngleTracker x = net.add(new AngleTracker(v, 0));
        double a = Math.toRadians(5), area = 0.01;
        Vec3 chord = new Vec3(Math.cos(a), Math.sin(a), 0); // trailing edge up: the air is turned up, the plate pushed down
        Aerofoils af = net.add(new Aerofoils(v, x, false, Y, Vec3.ZERO, List.of(new Aerofoils.Foil(Vec3.ZERO, Z, chord, area, 0.02)), 0,
                RHO, new Vec3(10, 0, 0)));
        TransientSolver s = new TransientSolver(net);
        double cl = Aerofoils.polar(a, 0.02)[0], expect = -0.5 * RHO * 100 * area * cl;
        System.out.printf("plate: lift %.5f N (½ρV²A·C_L %.5f N, C_L %.3f vs thin-aerofoil 2πα %.3f)%n", af.generalisedForce(s), expect, cl, 2 * Math.PI * a);
        assertEquals(expect, af.generalisedForce(s), 1e-9 * Math.abs(expect));
        assertEquals(2 * Math.PI * a, cl, 0.1 * cl, "near the thin-aerofoil slope at small incidence");
    }

    record Rotor(TransientSolver s, Aerofoils af, Node w, double area) {}

    /**
     * B blades of n elements from r = 0.1R to R, each at blade angle β(r) from the plane of rotation; held at ω > 0.
     * In still air it blows along +x (a propeller); in a wind along +x arriving at an inflow angle φ > β it is driven
     * (a windmill: incidence β − φ < 0).
     */
    static Rotor rotor(int blades, double radius, double chordFrac, java.util.function.DoubleUnaryOperator beta, double omega, Vec3 wind, double cd0) {
        Network net = new Network();
        Node w = net.node(Domain.ROTATIONAL, "w", omega);
        net.add(new Inertia(w, 1e9)); // held at its speed while the inflow settles
        AngleTracker th = net.add(new AngleTracker(w, 0));
        List<Aerofoils.Foil> foils = new ArrayList<>();
        int n = 12;
        for (int b = 0; b < blades; b++) {
            double phi0 = 2 * Math.PI * b / blades;
            Vec3 radial = Y.rotateAbout(X, phi0), tangential = X.cross(radial); // the blade's direction of travel for ω > 0
            for (int k = 0; k < n; k++) {
                double r = radius * (0.1 + 0.9 * (k + 0.5) / n), dr = 0.9 * radius / n, c = chordFrac * radius;
                double b0 = beta.applyAsDouble(r);
                Vec3 chord = tangential.mul(-Math.cos(b0)).add(X.mul(Math.sin(b0)));
                foils.add(new Aerofoils.Foil(radial.mul(r), radial, chord, c * dr, cd0));
            }
        }
        double area = Math.PI * radius * radius;
        Aerofoils af = net.add(new Aerofoils(w, th, true, X, Vec3.ZERO, foils, area, RHO, wind));
        TransientSolver s = new TransientSolver(net);
        s.advance(1e-6);
        return new Rotor(s, af, w, area);
    }

    /**
     * A windmill in a 10 m/s wind, swept over tip-speed ratio and pitch: at every point its power coefficient stays
     * within the momentum-theory bound for its induction, C_p ≤ 4a(1 − a)² ≤ 16/27 (equal only for blades without drag,
     * which the flat-plate polar never quite has), and the best comes near Betz.
     */
    @Test
    void idealWindmillApproachesButNeverExceedsTheBetzLimit() {
        double v = 10, radius = 1, best = 0, bestA = 0;
        String at = "";
        for (double lambda = 3; lambda <= 12; lambda += 1)
            for (double pitch = -6; pitch <= 6; pitch += 2) {
                // twist for a = 1/3 at the design λ 6: inflow angle tan φ = (2/3)/(λ r/R), the blade 2° below it
                double p0 = pitch;
                Rotor r = rotor(3, radius, 0.12, rr -> Math.atan2(2.0 / 3, 6 * rr / radius) - Math.toRadians(2) + Math.toRadians(p0),
                        lambda * v / radius, new Vec3(v, 0, 0), 0);
                double p = r.af.generalisedForce(r.s) * r.s.x(r.w.index()), cp = p / (0.5 * RHO * r.area * v * v * v);
                double a = -r.af.inducedVelocity(r.s) / v;
                assertTrue(cp < 16.0 / 27 + 1e-9, "never beyond Betz: C_p " + cp + " at λ " + lambda + ", pitch " + pitch);
                if (a < 0.4) assertTrue(cp <= 4 * a * (1 - a) * (1 - a) + 1e-9, "within the bound for its induction: " + cp + " at a " + a);
                if (cp > best) {
                    best = cp;
                    bestA = a;
                    at = "λ " + lambda + ", pitch " + pitch;
                }
            }
        System.out.printf("ideal windmill: best C_p %.4f (Betz %.4f) at %s, a = %.3f%n", best, 16.0 / 27, at, bestA);
        assertTrue(best > 0.45, "near Betz, less the blades' drag");
    }

    /** A propeller turning in still air: thrust and power, the figure of merit T^{3/2}/(P·√(2ρA)) below one. */
    @Test
    void staticPropellerFigureOfMeritBelowOne() {
        // constant geometric pitch 0.6 m per turn: β = atan(p/(2πr))
        Rotor r = rotor(3, 0.5, 0.1, rr -> Math.atan2(0.6, 2 * Math.PI * rr), 60, new Vec3(0, 0, 0), 0.01);
        double tq = r.af.generalisedForce(r.s), w = r.s.x(r.w.index()), p = -tq * w, t = r.af.thrust(r.s);
        assertTrue(t > 0, "blowing along +x");
        double fm = Math.pow(Math.abs(t), 1.5) / Math.sqrt(2 * RHO * r.area) / p;
        System.out.printf("propeller: thrust %.2f N, power %.1f W, induced %.2f m/s (momentum %.2f), FM %.3f%n", t, p, r.af.inducedVelocity(r.s),
                Math.signum(t) * Math.sqrt(Math.abs(t) / (2 * RHO * r.area)), fm);
        assertTrue(p > 0, "it takes power to turn");
        assertTrue(Math.abs(t) > 1, "it pushes air");
        assertEquals(Math.signum(t) * Math.sqrt(Math.abs(t) / (2 * RHO * r.area)), r.af.inducedVelocity(r.s), 1e-6, "momentum theory: T = 2ρA v²");
        assertTrue(fm > 0.3 && fm < 1, "figure of merit: " + fm);
    }

    /** A windmill braked by a damper spins up in the wind; the wind's work all goes into its spin and the damper. */
    @Test
    void windmillOnADamperBalancesItsEnergy() {
        Network net = new Network();
        Node w = net.node(Domain.ROTATIONAL, "w", 0);
        net.add(new Inertia(w, 0.05));
        AngleTracker th = net.add(new AngleTracker(w, 0));
        net.add(new Damper(w, net.reference(Domain.ROTATIONAL), 1e-3, null));
        List<Aerofoils.Foil> foils = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            Vec3 radial = Y.rotateAbout(X, Math.PI / 2 * b), t = X.cross(radial);
            double beta = Math.toRadians(10);
            for (int k = 0; k < 4; k++)
                foils.add(new Aerofoils.Foil(radial.mul(0.0625 * (k + 1.5)), radial, t.mul(-Math.cos(beta)).add(X.mul(Math.sin(beta))), 0.0035, 0.02));
        }
        Aerofoils af = net.add(new Aerofoils(w, th, true, X, Vec3.ZERO, foils, Math.PI * 0.28 * 0.28, RHO, new Vec3(8, 0, 0)));
        TransientSolver s = new TransientSolver(net);
        for (int i = 0; i < 600; i++) s.advance(0.05);
        System.out.printf("windmill on damper: ω %.1f rad/s, wind's work %.2f J, energy error %.3g J%n", s.x(w.index()), s.externalEnergyIn(), s.energyError());
        assertTrue(s.x(w.index()) > 20, "it spins up");
        assertTrue(Math.abs(s.energyError()) < 1e-4 * s.externalEnergyIn(), "the ledger closes");
    }

    /**
     * A propeller on a cart (a rotor riding a slide along its own axis), held at its speed: at rest the cart is pushed
     * with exactly the thrust the blades put into the air, the other way (Newton's third law); it runs the opposite way
     * to the air it blows, its momentum the impulse it was given; and as it gathers speed the thrust falls (its own
     * motion eats into the blades' angle of attack — the advance ratio).
     */
    @Test
    void propellerDrivesItsCartWithTheThrustItGivesTheAir() {
        Network net = new Network();
        double mass = 2, omega = 120, radius = 0.15;
        Node v = net.node(Domain.TRANSLATIONAL, "v", 0);
        net.add(new Inertia(v, mass));
        AngleTracker x = net.add(new AngleTracker(v, 0));
        Node w = net.node(Domain.ROTATIONAL, "w", omega);
        net.add(new Inertia(w, 1e9)); // held at its speed (a motor)
        AngleTracker th = net.add(new AngleTracker(w, 0));
        List<Aerofoils.Foil> foils = new ArrayList<>();
        int n = 12;
        for (int b = 0; b < 2; b++) {
            Vec3 radial = Y.rotateAbout(X, Math.PI * b), tangential = X.cross(radial);
            for (int k = 0; k < n; k++) {
                double r = radius * (0.1 + 0.9 * (k + 0.5) / n), dr = 0.9 * radius / n, b0 = Math.toRadians(20);
                Vec3 chord = tangential.mul(-Math.cos(b0)).add(X.mul(Math.sin(b0)));
                foils.add(new Aerofoils.Foil(radial.mul(r), radial, chord, 0.15 * radius * dr, 0.02));
            }
        }
        Aerofoils af = net.add(new Aerofoils(w, th, true, X, Vec3.ZERO, foils, Math.PI * radius * radius, RHO, Vec3.ZERO)
                .carriedBy(v, X));
        TransientSolver s = new TransientSolver(net);
        s.advance(1e-4);
        double thrust0 = af.thrust(s), push0 = af.carrierForce(s);
        double impulse = 0;
        for (int i = 0; i < 400; i++) {
            double f0 = af.carrierForce(s);
            s.advance(0.01);
            impulse += 0.5 * (f0 + af.carrierForce(s)) * 0.01;
        }
        double speed = s.x(v.index()), thrust = af.thrust(s);
        System.out.printf("propeller cart: thrust on the air %.4f N, push on the cart %.4f N at rest; after 4 s the cart at %.3f m/s "
                + "(momentum %.5f vs impulse %.5f N·s), thrust %.4f N%n", thrust0, push0, speed, mass * speed, impulse, thrust);
        assertTrue(thrust0 > 0, "it blows the air along +x");
        assertEquals(-thrust0, push0, 1e-9 * Math.abs(thrust0), "the cart is pushed back with the same force");
        assertTrue(speed < 0, "the cart runs the other way to the air it blows");
        assertEquals(impulse, mass * speed, 0.01 * Math.abs(impulse), "its momentum is the impulse the air gave it");
        assertTrue(Math.abs(thrust) < thrust0, "moving, the thrust falls (advance ratio)");
    }
}
