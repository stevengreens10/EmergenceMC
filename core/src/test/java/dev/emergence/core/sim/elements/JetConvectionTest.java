package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Air;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free jet's velocity and the cross-flow heat-transfer coefficient against hand calculations. */
class JetConvectionTest {
    // a 5 cm duct blowing on a 6.25 cm part
    static final double D = 0.05, A0 = Math.PI * D * D / 4, L = 0.0625;

    static JetConvection jet(double distance) {
        return new JetConvection(null, null, null, A0, D, distance, 0.01, L);
    }

    @Test
    void jetKeepsItsSpeedThroughItsCoreThenSlows() {
        double mdot = 0.0108, u0 = mdot / (Air.density(293.15, 101_325) * A0);
        assertEquals(u0, jet(0.1).velocity(mdot, 293.15), 1e-6 * u0, "within 6.2 D of the mouth: the exit velocity");
        assertEquals(u0 * 6.2 * D / 0.62, jet(0.62).velocity(mdot, 293.15), 1e-6 * u0, "beyond it: u ∝ 1/x");
        assertEquals(0, jet(0.1).velocity(-mdot, 293.15), 1e-6, "a duct drawing air in makes no jet");
    }

    @Test
    void coefficientFollowsChurchillBernstein() {
        double u = 4.6, tf = 293.15;
        double nu = Air.kinematicViscosity(tf), k = Air.conductivity(tf), pr = Air.prandtl(tf), re = u * L / nu;
        // Churchill–Bernstein, less the 0.3 that natural convection already accounts for
        double nus = 0.62 * Math.sqrt(re) * Math.cbrt(pr) / Math.pow(1 + Math.pow(0.4 / pr, 2.0 / 3), 0.25)
                * Math.pow(1 + Math.pow(re / 282_000, 5.0 / 8), 4.0 / 5);
        double h = jet(0.1).coefficient(u, tf);
        System.out.printf("jet: Re %.0f, h %.1f W/(m²·K) (hand %.1f)%n", re, h, nus * k / L);
        assertEquals(nus * k / L, h, 0.01 * h, "Re ≈ 19 000: the correlation (its low-Re rounding negligible)");
        assertTrue(h > 25 && h < 35, "a few m/s over a few cm: tens of W/(m²·K), several times still air's");
        assertEquals(0, jet(0.1).coefficient(0, tf), 1e-12, "no flow, no forced convection");
    }
}
