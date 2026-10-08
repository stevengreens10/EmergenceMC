package dev.emergence.core.fluids;

/**
 * Thermodynamic and transport properties of a single-phase working fluid. All values SI; specific
 * quantities per kilogram. Internal energy is referenced so that only differences are meaningful.
 */
public interface Fluid {
    String id();

    String name();

    /** Stable integer code used to tag parts with their fluid. */
    int code();

    boolean isGas();

    /** ρ(p, T) [kg/m³]. */
    double density(double p, double t);

    /** ∂ρ/∂p at constant T [kg/(m³·Pa)]. */
    double dDensityDp(double p, double t);

    /** ∂ρ/∂T at constant p [kg/(m³·K)]. */
    double dDensityDt(double p, double t);

    /** Specific internal energy u(p, T) [J/kg]. */
    double internalEnergy(double p, double t);

    /** ∂u/∂T = c_v [J/(kg·K)]. */
    double cv(double t);

    /** Isobaric specific heat c_p [J/(kg·K)]. */
    double cp(double t);

    /** Specific enthalpy h = u + p/ρ [J/kg]. */
    default double enthalpy(double p, double t) {
        return internalEnergy(p, t) + p / density(p, t);
    }

    /** Dynamic viscosity μ(T) [Pa·s]. */
    double viscosity(double t);

    /** Thermal conductivity k(T) [W/(m·K)]. */
    double conductivity(double t);

    default double prandtl(double t) {
        return viscosity(t) * cp(t) / conductivity(t);
    }
}
