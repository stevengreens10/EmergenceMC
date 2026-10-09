package dev.emergence.core.chem;

import dev.emergence.core.units.PhysicalConstants;

import java.util.Map;

/**
 * A pure substance with standard-state thermochemistry: formation enthalpy, standard entropy and a heat capacity
 * Cp(T) = a + bT + cT² fitted through tabulated values at 298.15, 1000 and 2000 K (held constant outside
 * 298.15–3000 K). Enthalpy and entropy are integrated analytically, so h, s and g are mutually consistent and the
 * equilibrium constants derived from them obey the van 't Hoff relation exactly.
 */
public final class Species {
    public static final double T0 = 298.15, T_MAX = 3000, P0 = 1e5;
    private static final double R = PhysicalConstants.GAS_CONSTANT;

    private final String id, name, phase;
    private final Map<String, Integer> formula;
    private final double molarMass, hf, s0, a, b, c;

    public Species(String id, String name, String phase, Map<String, Integer> formula, double molarMass, double hf, double s0,
                   double cp298, double cp1000, double cp2000) {
        this.id = id;
        this.name = name;
        this.phase = phase;
        this.formula = Map.copyOf(formula);
        this.molarMass = molarMass;
        this.hf = hf;
        this.s0 = s0;
        // quadratic through (298.15, cp298), (1000, cp1000), (2000, cp2000)
        double x0 = T0, x1 = 1000, x2 = 2000;
        double d01 = (cp1000 - cp298) / (x1 - x0), d12 = (cp2000 - cp1000) / (x2 - x1);
        this.c = (d12 - d01) / (x2 - x0);
        this.b = d01 - c * (x0 + x1);
        this.a = cp298 - b * x0 - c * x0 * x0;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String phase() {
        return phase;
    }

    public boolean isGas() {
        return phase.equals("gas");
    }

    public Map<String, Integer> formula() {
        return formula;
    }

    /** Molar mass [kg/mol]. */
    public double molarMass() {
        return molarMass;
    }

    private double clamp(double t) {
        return Math.max(T0, Math.min(T_MAX, t));
    }

    /** Isobaric molar heat capacity [J/(mol·K)]. */
    public double cp(double t) {
        double x = clamp(t);
        return a + b * x + c * x * x;
    }

    /** Isochoric molar heat capacity [J/(mol·K)] (ideal gas: c_p − R; condensed: c_p). */
    public double cv(double t) {
        return isGas() ? cp(t) - R : cp(t);
    }

    /** ∫ Cp dT between T0 and x within the fitted range. */
    private double cpInt(double x) {
        return a * (x - T0) + b / 2 * (x * x - T0 * T0) + c / 3 * (x * x * x - T0 * T0 * T0);
    }

    /** ∫ Cp/T dT between T0 and x within the fitted range. */
    private double cpOverTInt(double x) {
        return a * Math.log(x / T0) + b * (x - T0) + c / 2 * (x * x - T0 * T0);
    }

    /** Molar enthalpy including the enthalpy of formation [J/mol]. */
    public double enthalpy(double t) {
        double x = clamp(t);
        // below T0 and above T_MAX the heat capacity is held at its end value
        return hf + cpInt(x) + cp(t) * (t - x);
    }

    /** Molar internal energy [J/mol] (ideal gas h − RT; condensed phases h). */
    public double internalEnergy(double t) {
        return isGas() ? enthalpy(t) - R * t : enthalpy(t);
    }

    /** Standard molar entropy at T (at P0 for a gas) [J/(mol·K)]. */
    public double entropy(double t) {
        double x = clamp(t);
        return s0 + cpOverTInt(x) + cp(t) * Math.log(t / x);
    }

    /** Standard molar Gibbs energy g° = h − T s° [J/mol]. */
    public double gibbs(double t) {
        return enthalpy(t) - t * entropy(t);
    }

    /** Elements counted (for conservation checks). */
    public int atoms(String element) {
        return formula.getOrDefault(element, 0);
    }
}
