package dev.emergence.core.fluids;

import dev.emergence.core.units.PhysicalConstants;

/**
 * Thermophysical properties of dry air as an ideal gas, valid ~200–1500 K at near-atmospheric pressure.
 * <ul>
 *   <li>Dynamic viscosity: Sutherland's law, μ0 = 1.716e-5 Pa·s at 273.15 K, S = 110.4 K
 *       (error &lt; 2 % for 200–1000 K).</li>
 *   <li>Thermal conductivity: Sutherland-type fit k0 = 0.02414 W/(m·K) at 273.15 K, S = 194 K
 *       (error &lt; 3 % for 200–1000 K vs. Incropera Table A.4).</li>
 *   <li>Density: ideal gas, R_specific = 287.05 J/(kg·K).</li>
 *   <li>c_p: polynomial fit to Incropera Table A.4 (error &lt; 1 % 250–1500 K).</li>
 * </ul>
 */
public final class Air {
    private Air() {}

    public static final double R_SPECIFIC = 287.05;
    public static final double MOLAR_MASS = 0.0289647;

    public static double density(double temperature, double pressure) {
        return pressure / (R_SPECIFIC * temperature);
    }

    public static double viscosity(double t) {
        return 1.716e-5 * Math.pow(t / 273.15, 1.5) * (273.15 + 110.4) / (t + 110.4);
    }

    public static double conductivity(double t) {
        return 0.02414 * Math.pow(t / 273.15, 1.5) * (273.15 + 194.0) / (t + 194.0);
    }

    public static double specificHeat(double t) {
        double x = t / 1000.0;
        // cp [J/(kg K)] fit: 1047.6 − 372.6x + 945.3x² − 602.4x³ + 128.1x⁴ (≈ NASA/JANAF dry air)
        return 1047.6 + x * (-372.6 + x * (945.3 + x * (-602.4 + x * 128.1)));
    }

    public static double prandtl(double t) {
        return viscosity(t) * specificHeat(t) / conductivity(t);
    }

    /** Kinematic viscosity at standard pressure unless given. */
    public static double kinematicViscosity(double t, double p) {
        return viscosity(t) / density(t, p);
    }

    public static double thermalDiffusivity(double t, double p) {
        return conductivity(t) / (density(t, p) * specificHeat(t));
    }

    public static double kinematicViscosity(double t) {
        return kinematicViscosity(t, PhysicalConstants.STANDARD_ATMOSPHERE);
    }

    public static double thermalDiffusivity(double t) {
        return thermalDiffusivity(t, PhysicalConstants.STANDARD_ATMOSPHERE);
    }
}
