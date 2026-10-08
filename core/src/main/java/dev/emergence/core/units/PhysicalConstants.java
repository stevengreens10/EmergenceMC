package dev.emergence.core.units;

/** CODATA 2018 exact / recommended values of fundamental constants (SI). */
public final class PhysicalConstants {
    private PhysicalConstants() {}

    /** Vacuum magnetic permeability μ0 [H/m] (CODATA 2018). */
    public static final double MU0 = 1.25663706212e-6;
    /** Vacuum electric permittivity ε0 [F/m]. */
    public static final double EPSILON0 = 8.8541878128e-12;
    /** Speed of light in vacuum [m/s] (exact). */
    public static final double C = 299_792_458.0;
    /** Elementary charge [C] (exact). */
    public static final double ELEMENTARY_CHARGE = 1.602176634e-19;
    /** Boltzmann constant [J/K] (exact). */
    public static final double BOLTZMANN = 1.380649e-23;
    /** Avogadro constant [1/mol] (exact). */
    public static final double AVOGADRO = 6.02214076e23;
    /** Molar gas constant [J/(mol·K)]. */
    public static final double GAS_CONSTANT = BOLTZMANN * AVOGADRO;
    /** Faraday constant [C/mol]. */
    public static final double FARADAY = ELEMENTARY_CHARGE * AVOGADRO;
    /** Planck constant [J·s] (exact). */
    public static final double PLANCK = 6.62607015e-34;
    /** Stefan–Boltzmann constant [W/(m²·K⁴)]. */
    public static final double STEFAN_BOLTZMANN = 5.670374419e-8;
    /** Standard gravitational acceleration [m/s²]. */
    public static final double STANDARD_GRAVITY = 9.80665;
    /** Standard atmospheric pressure [Pa]. */
    public static final double STANDARD_ATMOSPHERE = 101_325.0;
    /** Thermal voltage factor k/q [V/K]. */
    public static final double K_OVER_Q = BOLTZMANN / ELEMENTARY_CHARGE;
    /** Reference temperature 20 °C [K], used for tabulated material data. */
    public static final double T_REF = 293.15;
    /** 0 °C in kelvin. */
    public static final double T_ZERO_CELSIUS = 273.15;
}
