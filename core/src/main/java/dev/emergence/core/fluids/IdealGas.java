package dev.emergence.core.fluids;

import dev.emergence.core.units.PhysicalConstants;

/**
 * Thermally perfect ideal gas: p = ρ·R·T with R = R_u/M, c_p(T) = Σ a_i (T/1000 K)^i, c_v = c_p − R and
 * u(T) = ∫₀ᵀ c_v dT (closed form). Transport properties from Sutherland's law. Valid at pressures well
 * below the critical pressure and away from condensation (compressibility factor Z ≈ 1 within ~1 % for
 * air below ~2 MPa near room temperature).
 */
public final class IdealGas implements Fluid {
    private final String id, name;
    private final int code;
    private final double r;
    private final double[] a;
    private final double mu0, muT0, muS, k0, kT0, kS;

    public IdealGas(String id, String name, int code, double molarMass, double[] cpPoly,
                    double mu0, double muT0, double muS, double k0, double kT0, double kS) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.r = PhysicalConstants.GAS_CONSTANT / molarMass;
        this.a = cpPoly.clone();
        this.mu0 = mu0;
        this.muT0 = muT0;
        this.muS = muS;
        this.k0 = k0;
        this.kT0 = kT0;
        this.kS = kS;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public int code() { return code; }
    @Override public boolean isGas() { return true; }

    /** Specific gas constant R [J/(kg·K)]. */
    public double gasConstant() {
        return r;
    }

    @Override
    public double density(double p, double t) {
        return p / (r * t);
    }

    @Override
    public double dDensityDp(double p, double t) {
        return 1 / (r * t);
    }

    @Override
    public double dDensityDt(double p, double t) {
        return -p / (r * t * t);
    }

    @Override
    public double cp(double t) {
        double x = t / 1000.0, s = 0, xp = 1;
        for (double c : a) {
            s += c * xp;
            xp *= x;
        }
        return s;
    }

    @Override
    public double cv(double t) {
        return cp(t) - r;
    }

    @Override
    public double internalEnergy(double p, double t) {
        double x = t / 1000.0, s = 0, xp = x;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * xp / (i + 1);
            xp *= x;
        }
        return 1000.0 * s - r * t;
    }

    @Override
    public double enthalpy(double p, double t) {
        return internalEnergy(p, t) + r * t;
    }

    /** Ratio of specific heats γ = c_p/c_v. */
    public double gamma(double t) {
        return cp(t) / cv(t);
    }

    @Override
    public double viscosity(double t) {
        return sutherland(mu0, muT0, muS, t);
    }

    @Override
    public double conductivity(double t) {
        return sutherland(k0, kT0, kS, t);
    }

    static double sutherland(double v0, double t0, double s, double t) {
        return v0 * Math.pow(t / t0, 1.5) * (t0 + s) / (t + s);
    }
}
