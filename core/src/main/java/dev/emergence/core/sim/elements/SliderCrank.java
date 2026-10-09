package dev.emergence.core.sim.elements;

/**
 * Slider-crank kinematics of a piston driven by a crank throw r through a connecting rod l. With ψ the
 * crank angle measured from top dead centre, the piston's distance from TDC is
 * <pre>
 *   s(ψ) = r + l − (r·cos ψ + √(l² − r²·sin² ψ))
 * </pre>
 * and the cylinder volume V(ψ) = A·(c + s(ψ)) with clearance length c. Valid for l &gt; r.
 *
 * @param area      piston area A [m²]
 * @param throw_    crank throw r [m] (stroke 2r)
 * @param rod       connecting-rod length l [m]
 * @param clearance clearance length c [m] (volume left at TDC = A·c)
 * @param phase     body angle at which this piston is at TDC [rad]
 */
public record SliderCrank(double area, double throw_, double rod, double clearance, double phase) {
    public SliderCrank {
        if (!(rod > throw_)) throw new IllegalArgumentException("connecting rod must be longer than the crank throw");
    }

    /** Crank angle from TDC for a body angle θ. */
    public double psi(double theta) {
        return theta - phase;
    }

    /** Piston displacement from TDC [m]. */
    public double displacement(double theta) {
        double p = psi(theta), sp = Math.sin(p);
        return throw_ + rod - (throw_ * Math.cos(p) + Math.sqrt(rod * rod - throw_ * throw_ * sp * sp));
    }

    /** ds/dθ [m/rad]. */
    public double dDisplacement(double theta) {
        double p = psi(theta), sp = Math.sin(p), cp = Math.cos(p);
        return throw_ * sp + throw_ * throw_ * sp * cp / Math.sqrt(rod * rod - throw_ * throw_ * sp * sp);
    }

    public double volume(double theta) {
        return area * (clearance + displacement(theta));
    }

    public double dVolume(double theta) {
        return area * dDisplacement(theta);
    }

    /** d²V/dθ² by central difference. */
    public double d2Volume(double theta) {
        double h = 1e-4;
        return (dVolume(theta + h) - dVolume(theta - h)) / (2 * h);
    }

    /** Swept volume 2rA [m³]. */
    public double sweptVolume() {
        return 2 * throw_ * area;
    }
}
