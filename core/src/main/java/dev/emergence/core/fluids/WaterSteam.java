package dev.emergence.core.fluids;

/**
 * Water and steam with liquid–vapour phase change, from the IAPWS supplementary equations:
 * <ul>
 *   <li>Saturation pressure (Wagner &amp; Pruß 1993, IAPWS SR 1992): ln(p/p_c) = (T_c/T)·Σ a_i τ^{n_i}, τ = 1 − T/T_c
 *       — within 0.1 % from the triple point to the critical point.</li>
 *   <li>Saturated liquid and vapour densities (same source), ±0.1–0.3 %.</li>
 *   <li>Saturated enthalpies from the auxiliary quantity α(θ): h' = α + (T/ρ')·dp/dT, h'' = α + (T/ρ'')·dp/dT,
 *       so the latent heat h'' − h' = T(1/ρ'' − 1/ρ')·dp/dT satisfies Clapeyron exactly.</li>
 *   <li>Subcooled liquid: saturated-liquid energy at T, density with bulk modulus 2.2 GPa about saturation.
 *       Superheated vapour: ideal-gas extension of the saturated vapour at the same pressure,
 *       c_v ≈ 1500 J/(kg·K) (±5 % below ~1 MPa and 600 K).</li>
 * </ul>
 * Pure water: no dissolved air or other non-condensable gases. Valid 273.16 K – ~620 K (below critical).
 */
public final class WaterSteam implements Fluid {
    public static final double TC = 647.096, PC = 22.064e6, RHOC = 322, R = 461.526;
    private static final double BULK = 2.2e9, CV_VAPOUR = 1500;
    private final String id, name;
    private final int code;
    private final Liquid liquid;

    public WaterSteam(String id, String name, int code, Liquid liquidTransport) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.liquid = liquidTransport;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public int code() { return code; }
    @Override public boolean isGas() { return false; }

    // ------------------------------------------------------------------ saturation

    private static double clampT(double t) {
        return Math.max(273.16, Math.min(t, 640));
    }

    /** Saturation pressure p_s(T) [Pa]. */
    public static double pSat(double t) {
        t = clampT(t);
        double tau = 1 - t / TC;
        double s = -7.85951783 * tau + 1.84408259 * Math.pow(tau, 1.5) - 11.7866497 * Math.pow(tau, 3)
                + 22.6807411 * Math.pow(tau, 3.5) - 15.9618719 * Math.pow(tau, 4) + 1.80122502 * Math.pow(tau, 7.5);
        return PC * Math.exp(TC / t * s);
    }

    /** dp_s/dT [Pa/K] (central difference of the smooth correlation). */
    public static double dpSatDt(double t) {
        double h = 1e-3;
        return (pSat(t + h) - pSat(t - h)) / (2 * h);
    }

    /** Saturation temperature T_s(p) [K] (Newton on p_s). */
    public static double tSat(double p) {
        p = Math.max(Math.min(p, 0.99 * PC), 612);
        double t = 373.15 + 30 * Math.log(p / 101_325); // decent start
        t = clampT(t);
        for (int i = 0; i < 30; i++) {
            double f = Math.log(pSat(t) / p);
            double d = dpSatDt(t) / pSat(t);
            double dt = f / d;
            t = clampT(t - dt);
            if (Math.abs(dt) < 1e-9) break;
        }
        return t;
    }

    /** Saturated liquid density ρ'(T). */
    public static double rhoLiquidSat(double t) {
        double tau = 1 - clampT(t) / TC;
        return RHOC * (1 + 1.99274064 * Math.cbrt(tau) + 1.09965342 * Math.pow(tau, 2.0 / 3) - 0.510839303 * Math.pow(tau, 5.0 / 3)
                - 1.75493479 * Math.pow(tau, 16.0 / 3) - 45.5170352 * Math.pow(tau, 43.0 / 3) - 6.74694450e5 * Math.pow(tau, 110.0 / 3));
    }

    /** Saturated vapour density ρ''(T). */
    public static double rhoVapourSat(double t) {
        double tau = 1 - clampT(t) / TC;
        double s = -2.03150240 * Math.pow(tau, 2.0 / 6) - 2.68302940 * Math.pow(tau, 4.0 / 6) - 5.38626492 * Math.pow(tau, 8.0 / 6)
                - 17.2991605 * Math.pow(tau, 18.0 / 6) - 44.7586581 * Math.pow(tau, 37.0 / 6) - 63.9201063 * Math.pow(tau, 71.0 / 6);
        return RHOC * Math.exp(s);
    }

    /** Auxiliary quantity α(T) [J/kg] of the IAPWS saturation-property equations. */
    private static double alpha(double t) {
        double th = clampT(t) / TC;
        return 1000 * (-1135.905627715 - 5.65134998e-8 * Math.pow(th, -19) + 2690.66631 * th + 127.287297 * Math.pow(th, 4.5)
                - 135.003439 * Math.pow(th, 5) + 0.981825814 * Math.pow(th, 54.5));
    }

    /** Saturated liquid enthalpy h'(T) [J/kg]. */
    public static double hLiquidSat(double t) {
        return alpha(t) + clampT(t) / rhoLiquidSat(t) * dpSatDt(t);
    }

    /** Saturated vapour enthalpy h''(T) [J/kg]. */
    public static double hVapourSat(double t) {
        return alpha(t) + clampT(t) / rhoVapourSat(t) * dpSatDt(t);
    }

    public static double uLiquidSat(double t) {
        return hLiquidSat(t) - pSat(t) / rhoLiquidSat(t);
    }

    public static double uVapourSat(double t) {
        return hVapourSat(t) - pSat(t) / rhoVapourSat(t);
    }

    /** Latent heat of vaporisation h'' − h' [J/kg]. */
    public static double latentHeat(double t) {
        return hVapourSat(t) - hLiquidSat(t);
    }

    // ------------------------------------------------------------------ control-volume states (T, s)

    /** Pressure scale of the compressed-liquid branch of the phase coordinate [Pa per unit s]. */
    public static final double COMPRESSION_SCALE = 1e6;
    /** Logarithmic pressure scale of the superheated branch: p = p_s(T)·e^{−SUPERHEAT_SCALE·(s − 1)}. */
    public static final double SUPERHEAT_SCALE = 3;

    /** Indices into the array filled by {@link #state}. */
    public static final int P = 0, RHO = 1, RHO_U = 2, RHO_L = 3, H_L = 4, RHO_V = 5, H_V = 6, ALPHA_V = 7, U_V = 8;

    /**
     * State of a control volume from its temperature T and a phase coordinate s that runs smoothly through
     * all three regions, so that one set of unknowns covers boiling and condensation:
     * <ul>
     *   <li>s &lt; 0 — compressed liquid at p = p_s(T) + |s|·1 MPa;</li>
     *   <li>0 ≤ s ≤ 1 — liquid and vapour in equilibrium at p = p_s(T), s being the vapour volume fraction;</li>
     *   <li>s &gt; 1 — superheated vapour at p = p_s(T)·e^{−3(s−1)} (below the saturation pressure for T).</li>
     * </ul>
     * Fills {@code out} with [p, ρ (mixture), ρu (mixture, per volume), ρ_liquid, h_liquid, ρ_vapour, h_vapour,
     * vapour volume fraction, u_vapour]. The pressure, density and energy are continuous across the region
     * boundaries.
     */
    public static void state(double t, double s, double[] out) {
        t = clampT(t);
        double ps = pSat(t), rl = rhoLiquidSat(t), ul = uLiquidSat(t);
        if (s < 0) {
            double p = ps - s * COMPRESSION_SCALE, rho = rl * (1 + (p - ps) / BULK);
            set(out, p, rho, rho * ul, rho, ul + p / rho, rhoVapourSat(t), hVapourSat(t), 0, uVapourSat(t));
        } else if (s <= 1) {
            double rv = rhoVapourSat(t), uv = uVapourSat(t);
            double rho = (1 - s) * rl + s * rv;
            set(out, ps, rho, (1 - s) * rl * ul + s * rv * uv, rl, ul + ps / rl, rv, uv + ps / rv, s, uv);
        } else {
            double p = ps * Math.exp(-SUPERHEAT_SCALE * (s - 1));
            double ts = Math.min(tSat(p), t), pst = pSat(ts);
            double rv = rhoVapourSat(ts) * (p / pst) * ts / t, uv = uVapourSat(ts) + CV_VAPOUR * (t - ts);
            set(out, p, rv, rv * uv, rl, ul + p / rl, rv, uv + p / rv, 1, uv);
        }
    }

    private static void set(double[] o, double... v) {
        System.arraycopy(v, 0, o, 0, v.length);
    }

    /** Phase coordinate s of the state with this temperature and pressure (single phase; saturated → s = 0.5). */
    public static double phaseOf(double p, double t) {
        double ps = pSat(t);
        if (Math.abs(p - ps) < 1e-6 * ps) return 0.5;
        if (p > ps) return -(p - ps) / COMPRESSION_SCALE;
        return 1 + Math.log(ps / Math.max(p, 1e-3)) / SUPERHEAT_SCALE;
    }

    /** Surface tension of water against its vapour, IAPWS (1994): σ = 235.8 mN/m·τ^1.256·(1 − 0.625τ). */
    public static double surfaceTension(double t) {
        double tau = Math.max(1 - clampT(t) / TC, 0);
        return 0.2358 * Math.pow(tau, 1.256) * (1 - 0.625 * tau);
    }

    /** Steam thermal conductivity, ±8 % 373–600 K at low pressure [W/(m·K)]. */
    public static double vapourConductivity(double t) {
        return 0.0248 * Math.pow(Math.max(t, 273.16) / 373.15, 1.35);
    }

    public double liquidViscosity(double t) {
        return liquid.viscosity(t);
    }

    /** Saturated-liquid thermal conductivity, cubic fit to IAPWS 2011 over 0–300 °C (±0.4 %) [W/(m·K)]. */
    public double liquidConductivity(double t) {
        double c = Math.max(0, Math.min(clampT(t) - 273.15, 330));
        return 0.562319 + c * (2.02295e-3 + c * (-9.14396e-6 + c * 6.02797e-9));
    }

    // ------------------------------------------------------------------ single-phase states (Fluid interface)

    /** True if (p, T) is vapour (above the saturation temperature for p). */
    public boolean isVapour(double p, double t) {
        return t > tSat(p);
    }

    @Override
    public double density(double p, double t) {
        double ts = tSat(p);
        if (t <= ts) return rhoLiquidSat(t) * (1 + (p - pSat(t)) / BULK);
        return rhoVapourSat(ts) * ts / t;
    }

    @Override
    public double dDensityDp(double p, double t) {
        double h = Math.max(1, 1e-6 * p);
        return (density(p + h, t) - density(p - h, t)) / (2 * h);
    }

    @Override
    public double dDensityDt(double p, double t) {
        double h = 1e-3;
        return (density(p, t + h) - density(p, t - h)) / (2 * h);
    }

    @Override
    public double internalEnergy(double p, double t) {
        double ts = tSat(p);
        if (t <= ts) return uLiquidSat(t);
        return uVapourSat(ts) + CV_VAPOUR * (t - ts);
    }

    @Override
    public double enthalpy(double p, double t) {
        return internalEnergy(p, t) + p / density(p, t);
    }

    /** Liquid heat capacity, the slope of the saturated-liquid enthalpy dh'/dT (±1 % to 250 °C). */
    @Override
    public double cv(double t) {
        double h = 0.05;
        return (hLiquidSat(t + h) - hLiquidSat(t - h)) / (2 * h);
    }

    @Override
    public double cp(double t) {
        return cv(t);
    }

    /** Vapour-phase isobaric heat capacity [J/(kg·K)]. */
    public double cpVapour() {
        return CV_VAPOUR + R;
    }

    @Override
    public double viscosity(double t) {
        return liquid.viscosity(t);
    }

    /** Steam viscosity (Sutherland fit, 373–600 K, ±5 %). */
    public double vapourViscosity(double t) {
        return 1.12e-5 * Math.pow(t / 350, 1.5) * (350 + 1064) / (t + 1064);
    }

    @Override
    public double conductivity(double t) {
        return liquidConductivity(t);
    }
}
