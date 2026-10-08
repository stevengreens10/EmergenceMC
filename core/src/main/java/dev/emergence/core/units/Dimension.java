package dev.emergence.core.units;

import java.util.Arrays;

/**
 * Physical dimension expressed as integer exponents of the seven SI base dimensions:
 * mass (M), length (L), time (T), electric current (I), thermodynamic temperature (Θ),
 * amount of substance (N) and luminous intensity (J).
 * <p>
 * Dimensions are immutable value objects used to validate that quantities combined in
 * equations, data files and coupling interfaces are dimensionally consistent.
 */
public final class Dimension {
    public static final int M = 0, L = 1, T = 2, I = 3, THETA = 4, N = 5, J = 6;
    private static final String[] SYMBOLS = {"kg", "m", "s", "A", "K", "mol", "cd"};

    private final int[] exp;

    private Dimension(int... exp) {
        this.exp = exp;
    }

    public static Dimension of(int m, int l, int t, int i, int theta, int n, int j) {
        return new Dimension(m, l, t, i, theta, n, j);
    }

    public static final Dimension DIMENSIONLESS = of(0, 0, 0, 0, 0, 0, 0);
    public static final Dimension MASS = of(1, 0, 0, 0, 0, 0, 0);
    public static final Dimension LENGTH = of(0, 1, 0, 0, 0, 0, 0);
    public static final Dimension TIME = of(0, 0, 1, 0, 0, 0, 0);
    public static final Dimension CURRENT = of(0, 0, 0, 1, 0, 0, 0);
    public static final Dimension TEMPERATURE = of(0, 0, 0, 0, 1, 0, 0);
    public static final Dimension AMOUNT = of(0, 0, 0, 0, 0, 1, 0);
    public static final Dimension LUMINOUS_INTENSITY = of(0, 0, 0, 0, 0, 0, 1);

    public static final Dimension AREA = LENGTH.pow(2);
    public static final Dimension VOLUME = LENGTH.pow(3);
    public static final Dimension VELOCITY = LENGTH.div(TIME);
    public static final Dimension ACCELERATION = VELOCITY.div(TIME);
    public static final Dimension FREQUENCY = TIME.pow(-1);
    public static final Dimension ANGULAR_VELOCITY = FREQUENCY;
    public static final Dimension FORCE = MASS.mul(ACCELERATION);
    public static final Dimension TORQUE = FORCE.mul(LENGTH);
    public static final Dimension ENERGY = FORCE.mul(LENGTH);
    public static final Dimension POWER = ENERGY.div(TIME);
    public static final Dimension PRESSURE = FORCE.div(AREA);
    public static final Dimension DENSITY = MASS.div(VOLUME);
    public static final Dimension CHARGE = CURRENT.mul(TIME);
    public static final Dimension VOLTAGE = POWER.div(CURRENT);
    public static final Dimension RESISTANCE = VOLTAGE.div(CURRENT);
    public static final Dimension CONDUCTANCE = RESISTANCE.pow(-1);
    public static final Dimension RESISTIVITY = RESISTANCE.mul(LENGTH);
    public static final Dimension CAPACITANCE = CHARGE.div(VOLTAGE);
    public static final Dimension MAGNETIC_FLUX = VOLTAGE.mul(TIME);
    public static final Dimension INDUCTANCE = MAGNETIC_FLUX.div(CURRENT);
    public static final Dimension MAGNETIC_FLUX_DENSITY = MAGNETIC_FLUX.div(AREA);
    public static final Dimension MAGNETIC_FIELD_STRENGTH = CURRENT.div(LENGTH);
    public static final Dimension PERMEABILITY = INDUCTANCE.div(LENGTH);
    public static final Dimension PERMITTIVITY = CAPACITANCE.div(LENGTH);
    public static final Dimension SPECIFIC_HEAT = ENERGY.div(MASS.mul(TEMPERATURE));
    public static final Dimension HEAT_CAPACITY = ENERGY.div(TEMPERATURE);
    public static final Dimension THERMAL_CONDUCTIVITY = POWER.div(LENGTH.mul(TEMPERATURE));
    public static final Dimension THERMAL_CONDUCTANCE = POWER.div(TEMPERATURE);
    public static final Dimension HEAT_TRANSFER_COEFFICIENT = POWER.div(AREA.mul(TEMPERATURE));
    public static final Dimension INVERSE_TEMPERATURE = TEMPERATURE.pow(-1);
    public static final Dimension DYNAMIC_VISCOSITY = PRESSURE.mul(TIME);
    public static final Dimension KINEMATIC_VISCOSITY = AREA.div(TIME);
    public static final Dimension MOMENT_OF_INERTIA = MASS.mul(AREA);
    public static final Dimension MOLAR_MASS = MASS.div(AMOUNT);
    public static final Dimension MOLAR_ENERGY = ENERGY.div(AMOUNT);
    public static final Dimension MOLAR_ENTROPY = ENERGY.div(AMOUNT.mul(TEMPERATURE));
    public static final Dimension SPECIFIC_ENERGY = ENERGY.div(MASS);
    public static final Dimension ROTATIONAL_DAMPING = TORQUE.div(ANGULAR_VELOCITY);
    public static final Dimension VOLUMETRIC_FLOW = VOLUME.div(TIME);
    public static final Dimension MASS_FLOW = MASS.div(TIME);
    public static final Dimension SURFACE_TENSION = FORCE.div(LENGTH);

    public int exponent(int base) {
        return exp[base];
    }

    public Dimension mul(Dimension o) {
        int[] r = new int[7];
        for (int k = 0; k < 7; k++) r[k] = exp[k] + o.exp[k];
        return new Dimension(r);
    }

    public Dimension div(Dimension o) {
        int[] r = new int[7];
        for (int k = 0; k < 7; k++) r[k] = exp[k] - o.exp[k];
        return new Dimension(r);
    }

    public Dimension pow(int p) {
        int[] r = new int[7];
        for (int k = 0; k < 7; k++) r[k] = exp[k] * p;
        return new Dimension(r);
    }

    public boolean isDimensionless() {
        for (int e : exp) if (e != 0) return false;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dimension d && Arrays.equals(exp, d.exp);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(exp);
    }

    @Override
    public String toString() {
        if (isDimensionless()) return "1";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 7; k++) {
            if (exp[k] == 0) continue;
            if (!sb.isEmpty()) sb.append('·');
            sb.append(SYMBOLS[k]);
            if (exp[k] != 1) sb.append('^').append(exp[k]);
        }
        return sb.toString();
    }
}
