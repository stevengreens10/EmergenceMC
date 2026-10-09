package dev.emergence.core.sim.elements;

/**
 * How a piston's position follows the coordinate q of the body that drives it (a crank angle, or a slide's
 * displacement), and so how the cylinder volume V(q) = A·(c + s(q)) changes. The work p·dV/dt is passed to that
 * body through {@link PistonCoupling}.
 */
public interface PistonKinematics {
    /** Piston area A [m²]. */
    double area();

    /** Clearance length c [m] (volume left at the head = A·c). */
    double clearance();

    /** Piston distance from the head end of its travel [m]. */
    double displacement(double q);

    /** ds/dq. */
    double dDisplacement(double q);

    /** Crank angle from top dead centre (0 for a straight rod). */
    default double psi(double q) {
        return 0;
    }

    default double volume(double q) {
        return area() * (clearance() + displacement(q));
    }

    default double dVolume(double q) {
        return area() * dDisplacement(q);
    }

    /** d²V/dq² by central difference. */
    default double d2Volume(double q) {
        double h = 1e-6;
        return (dVolume(q + h) - dVolume(q - h)) / (2 * h);
    }
}
