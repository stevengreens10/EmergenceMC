package dev.emergence.core.fluids;

/**
 * Slightly compressible liquid with a linearised equation of state ρ = ρ0·[1 + (p − p0)/K − β·(T − T0)]
 * (bulk modulus K, volumetric expansion β), constant c_p ≈ c_v and u = c_v·T. Viscosity from Vogel's
 * equation μ = A·10^(B/(T − C)) or Andrade's μ = A·exp(B/T). Valid for |p − p0| ≪ K and away from
 * freezing and boiling (phase change is not modelled by this class).
 */
public final class Liquid implements Fluid {
    public enum ViscosityLaw { VOGEL, ANDRADE }

    private final String id, name;
    private final int code;
    private final double rho0, p0, t0, bulk, beta, c, k;
    private final ViscosityLaw law;
    private final double[] mu;

    public Liquid(String id, String name, int code, double rho0, double p0, double t0, double bulkModulus,
                  double expansion, double specificHeat, double conductivity, ViscosityLaw law, double[] mu) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.rho0 = rho0;
        this.p0 = p0;
        this.t0 = t0;
        this.bulk = bulkModulus;
        this.beta = expansion;
        this.c = specificHeat;
        this.k = conductivity;
        this.law = law;
        this.mu = mu.clone();
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public int code() { return code; }
    @Override public boolean isGas() { return false; }

    public double bulkModulus() {
        return bulk;
    }

    @Override
    public double density(double p, double t) {
        return rho0 * (1 + (p - p0) / bulk - beta * (t - t0));
    }

    @Override
    public double dDensityDp(double p, double t) {
        return rho0 / bulk;
    }

    @Override
    public double dDensityDt(double p, double t) {
        return -rho0 * beta;
    }

    @Override
    public double internalEnergy(double p, double t) {
        return c * t;
    }

    @Override
    public double cv(double t) {
        return c;
    }

    @Override
    public double cp(double t) {
        return c;
    }

    @Override
    public double viscosity(double t) {
        return switch (law) {
            case VOGEL -> mu[0] * Math.pow(10, mu[1] / (t - mu[2]));
            case ANDRADE -> mu[0] * Math.exp(mu[1] / t);
        };
    }

    @Override
    public double conductivity(double t) {
        return k;
    }
}
