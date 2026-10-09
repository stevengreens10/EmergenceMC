package dev.emergence.core.fluids;

/**
 * Slightly compressible liquid with a linearised equation of state ρ = ρ0·[1 + (p − p0)/K − β·(T − T0)]
 * (bulk modulus K, volumetric expansion β), constant c_p ≈ c_v and u = c_v·T. Viscosity from Vogel's
 * equation μ = A·10^(B/(T − C)) or Andrade's μ = A·exp(B/T). Valid for |p − p0| ≪ K and away from
 * freezing and boiling (bulk phase change is not modelled by this class; {@link WaterSteam} does that).
 * <p>
 * <b>Cavitation.</b> A liquid cannot be pulled much below its vapour pressure p_v(T) (Antoine equation
 * log₁₀ p_v = A − B/(T + C)): a vapour cavity opens instead and the pressure stays near p_v. The lumped model lets
 * a void fraction α grow below p_v, ρ = ρ_l·(1 − α), 1 − α = exp(−φ), φ = (w/B_c)·ln(1 + e^{(p_v − p)/w}), so that
 * well below p_v the pressure falls only by B_c per e-fold of liquid fraction lost (B_c = 0.2·max(p_v, 500 Pa), the
 * cavity "modulus"; w = B_c/4 smooths the onset). Above p_v (by a few w) the liquid law is unchanged. The cavity's
 * vapour mass and its latent heat are neglected (ρ_vapour/ρ_liquid ~ 10⁻⁵).
 */
public final class Liquid implements Fluid {
    public enum ViscosityLaw { VOGEL, ANDRADE }

    private final String id, name;
    private final int code;
    private final double rho0, p0, t0, bulk, beta, c, k;
    private final ViscosityLaw law;
    private final double[] mu;
    /** Antoine coefficients for p_v [Pa] with T [K]; NaN if the vapour pressure is negligible (taken as 0). */
    private double antA = Double.NaN, antB, antC;

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

    /** Vapour pressure by Antoine's equation log₁₀(p_v/Pa) = A − B/(T/K + C). */
    public Liquid withVapourPressure(double a, double b, double c) {
        this.antA = a;
        this.antB = b;
        this.antC = c;
        return this;
    }

    /** Vapour pressure p_v(T) [Pa] (0 if not given). */
    public double vapourPressure(double t) {
        if (Double.isNaN(antA)) return 0;
        double tt = Math.max(t, -antC + 20); // keep clear of the Antoine pole
        return Math.pow(10, antA - antB / (tt + antC));
    }

    private double dVapourPressureDt(double t) {
        if (Double.isNaN(antA) || t < -antC + 20) return 0;
        return vapourPressure(t) * Math.log(10) * antB / ((t + antC) * (t + antC));
    }

    private double cavityModulus(double t) {
        return 0.2 * Math.max(vapourPressure(t), 500);
    }

    /** Liquid volume fraction 1 − α (1 with no cavity). */
    public double liquidFraction(double p, double t) {
        return Math.exp(-voidExponent(p, t)[0]);
    }

    /** {φ, ∂φ/∂p, ∂φ/∂T}: φ = ¼·ln(1 + e^x), x = 4·(p_v − p)/B_c. */
    private double[] voidExponent(double p, double t) {
        double pv = vapourPressure(t), bc = cavityModulus(t), x = 4 * (pv - p) / bc;
        if (x < -40) return new double[]{0, 0, 0};
        double sp = x > 40 ? x : Math.log1p(Math.exp(x)), sig = 1 / (1 + Math.exp(-x));
        double dpv = dVapourPressureDt(t), dbc = pv > 500 ? 0.2 * dpv : 0;
        return new double[]{0.25 * sp, -sig / bc, sig * (dpv / bc - (pv - p) * dbc / (bc * bc))};
    }

    private double liquidDensity(double p, double t) {
        return rho0 * (1 + (p - p0) / bulk - beta * (t - t0));
    }

    @Override
    public double density(double p, double t) {
        return liquidDensity(p, t) * Math.exp(-voidExponent(p, t)[0]);
    }

    @Override
    public double dDensityDp(double p, double t) {
        double[] f = voidExponent(p, t);
        double e = Math.exp(-f[0]);
        return e * (rho0 / bulk - liquidDensity(p, t) * f[1]);
    }

    @Override
    public double dDensityDt(double p, double t) {
        double[] f = voidExponent(p, t);
        double e = Math.exp(-f[0]);
        return e * (-rho0 * beta - liquidDensity(p, t) * f[2]);
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
