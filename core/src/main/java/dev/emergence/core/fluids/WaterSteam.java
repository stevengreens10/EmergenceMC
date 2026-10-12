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

    /** Indices into the saturation property set filled by {@link #saturation}. */
    private static final int S_P = 0, S_DP = 1, S_RL = 2, S_RV = 3, S_UL = 4, S_UV = 5, S_HL = 6, S_HV = 7;

    /**
     * All saturation properties at T in one pass: [p_s, dp_s/dT (analytic), ρ', ρ'', u', u'', h', h''], with the
     * fractional powers of the IAPWS correlations built from integer powers of τ^{1/6} and θ^{1/2}.
     */
    private static double[] saturation(double t) {
        t = clampT(t);
        double[] c = new double[9];
        double tau = 1 - t / TC, st = Math.sqrt(tau), t2 = tau * tau, t3 = t2 * tau;
        double sum = -7.85951783 * tau + 1.84408259 * tau * st - 11.7866497 * t3 + 22.6807411 * t3 * st
                - 15.9618719 * t3 * tau + 1.80122502 * t3 * t3 * tau * st;
        double dsum = -7.85951783 + 1.5 * 1.84408259 * st - 3 * 11.7866497 * t2 + 3.5 * 22.6807411 * t2 * st
                - 4 * 15.9618719 * t3 + 7.5 * 1.80122502 * t3 * t3 * st;
        double ps = PC * Math.exp(TC / t * sum);
        double dps = ps * (-TC * sum / (t * t) - dsum / t);
        // ρ': powers of τ^{1/3}
        double k1 = Math.cbrt(tau), k2 = k1 * k1, k4 = k2 * k2, k5 = k4 * k1, k8 = k4 * k4, k16 = k8 * k8, k32 = k16 * k16;
        double k43 = k32 * k8 * k2 * k1, k110 = k32 * k32 * k32 * k8 * k4 * k2;
        double rl = RHOC * (1 + 1.99274064 * k1 + 1.09965342 * k2 - 0.510839303 * k5 - 1.75493479 * k16 - 45.5170352 * k43
                - 6.74694450e5 * k110);
        // ρ'': powers of τ^{1/6}
        double r1 = Math.sqrt(k1), r2 = k1, r4 = k2, r8 = k4, r16 = k8, r18 = r16 * r2, r32 = k16, r37 = r32 * r4 * r1;
        double r71 = r32 * r32 * r4 * r2 * r1;
        double rv = RHOC * Math.exp(-2.03150240 * r2 - 2.68302940 * r4 - 5.38626492 * r8 - 17.2991605 * r18
                - 44.7586581 * r37 - 63.9201063 * r71);
        // α(θ)
        double th = t / TC, sth = Math.sqrt(th), th2 = th * th, th4 = th2 * th2, th5 = th4 * th, th8 = th4 * th4, th16 = th8 * th8;
        double th19 = th16 * th2 * th, th54 = th16 * th16 * th16 * th4 * th2;
        double alpha = 1000 * (-1135.905627715 - 5.65134998e-8 / th19 + 2690.66631 * th + 127.287297 * th4 * sth
                - 135.003439 * th5 + 0.981825814 * th54 * sth);
        double hl = alpha + t / rl * dps, hv = alpha + t / rv * dps;
        c[0] = t;
        c[1 + S_P] = ps;
        c[1 + S_DP] = dps;
        c[1 + S_RL] = rl;
        c[1 + S_RV] = rv;
        c[1 + S_UL] = hl - ps / rl;
        c[1 + S_UV] = hv - ps / rv;
        c[1 + S_HL] = hl;
        c[1 + S_HV] = hv;
        return c;
    }

    private static double sat(double t, int k) {
        return saturation(t)[1 + k];
    }

    /** Saturation pressure p_s(T) [Pa] (Wagner &amp; Pruß). */
    public static double pSat(double t) {
        return sat(t, S_P);
    }

    /** dp_s/dT [Pa/K] (analytic derivative of the correlation). */
    public static double dpSatDt(double t) {
        return sat(t, S_DP);
    }

    /** Saturation temperature T_s(p) [K] (Newton on ln p_s, quadratically convergent). */
    public static double tSat(double p) {
        p = Math.max(Math.min(p, 0.99 * PC), 612);
        double lp = Math.log(p / PC);
        // start: Clausius–Clapeyron through the normal boiling point, 1/T = 1/373.12 − (R/h_fg)·ln(p/p0)
        double t = clampT(1 / (1 / 373.124 - 461.5 / 2.26e6 * Math.log(p / 101_325)));
        for (int i = 0; i < 20; i++) {
            double tau = 1 - t / TC, st = Math.sqrt(tau), t2 = tau * tau, t3 = t2 * tau;
            double sum = -7.85951783 * tau + 1.84408259 * tau * st - 11.7866497 * t3 + 22.6807411 * t3 * st
                    - 15.9618719 * t3 * tau + 1.80122502 * t3 * t3 * tau * st;
            double dsum = -7.85951783 + 1.5 * 1.84408259 * st - 3 * 11.7866497 * t2 + 3.5 * 22.6807411 * t2 * st
                    - 4 * 15.9618719 * t3 + 7.5 * 1.80122502 * t3 * t3 * st;
            // ln(p_s/p_c) = (T_c/T)·Σ and its T-derivative
            double dt = (TC / t * sum - lp) / (-TC * sum / (t * t) - dsum / t);
            double tn = clampT(t - dt);
            if (Math.abs(tn - t) < 1e-11 * t) return tn;
            t = tn;
        }
        return t;
    }

    /** Saturated liquid density ρ'(T). */
    public static double rhoLiquidSat(double t) {
        return sat(t, S_RL);
    }

    /** Saturated vapour density ρ''(T). */
    public static double rhoVapourSat(double t) {
        return sat(t, S_RV);
    }

    /** Saturated liquid enthalpy h'(T) [J/kg]: α + (T/ρ')·dp_s/dT. */
    public static double hLiquidSat(double t) {
        return sat(t, S_HL);
    }

    /** Saturated vapour enthalpy h''(T) [J/kg]: α + (T/ρ'')·dp_s/dT. */
    public static double hVapourSat(double t) {
        return sat(t, S_HV);
    }

    public static double uLiquidSat(double t) {
        return sat(t, S_UL);
    }

    public static double uVapourSat(double t) {
        return sat(t, S_UV);
    }

    /** Latent heat of vaporisation h'' − h' [J/kg]. */
    public static double latentHeat(double t) {
        return hVapourSat(t) - hLiquidSat(t);
    }

    // ------------------------------------------------------------------ control-volume states (T, s)

    /** L(T) = ln(ρ'/ρ''): the density ratio across the dome on the logarithmic phase coordinate. */
    public static double logDensityRatio(double t) {
        return Math.log(rhoLiquidSat(t) / rhoVapourSat(t));
    }

    /** Indices into the array filled by {@link #state}. */
    public static final int P = 0, RHO = 1, RHO_U = 2, RHO_L = 3, H_L = 4, RHO_V = 5, H_V = 6, ALPHA_V = 7, U_V = 8;

    /**
     * State of a control volume from its temperature T and a phase coordinate s that runs smoothly through
     * all three regions, so that one set of unknowns covers boiling and condensation:
     * <ul>
     *   <li>s &lt; 0 — compressed liquid at p = p_s(T) + |s|·K·L;</li>
     *   <li>0 ≤ s ≤ 1 — liquid and vapour in equilibrium at p = p_s(T), with mixture density
     *       ρ = ρ'^{1−s}·ρ''^{s} (logarithmic in density, so a given change of s is the same relative change of
     *       the mass held anywhere in the dome — well conditioned at both the nearly-full and nearly-dry ends);</li>
     *   <li>s &gt; 1 — superheated vapour at p = p_s(T)·e^{−L(s−1)} (below the saturation pressure for T),</li>
     * </ul>
     * where L = ln(ρ'/ρ'') and K is the liquid bulk modulus; these scales make dρ/ds continuous at both
     * boundaries.
     * Fills {@code out} with [p, ρ (mixture), ρu (mixture, per volume), ρ_liquid, h_liquid, ρ_vapour, h_vapour,
     * vapour volume fraction, u_vapour]. The pressure, density and energy are continuous across the region
     * boundaries, and continuously differentiable: about the dry edge (0.97 &lt; s &lt; 1.03) the state is a cubic Hermite
     * join from the dome's to the superheated region's, and just outside the full edge (−2·10⁻⁵ &lt; s &lt; 0) one to the
     * compressed liquid's, so Newton's method does not cycle on the slope jump of the real saturation boundary when a
     * volume sits on it (a dry pipe of saturated steam). The dry join straddles the edge and interpolates the specific
     * energy u (not ρu): on the dome u climbs steeply with s, beyond it u is all but flat, and a join starting at the
     * edge with the dome's slope overshot — u rose ~110 kJ/kg and fell back (at 150 °C), so a volume's mass and energy
     * had two states, and the low-pressure steam of a condensing engine's cylinder two-cycled Newton by the ten thousand.
     * Straddling, the secant slope is about half the steep one, inside the cubic's monotone range (α² + β² ≤ 9).
     */
    /** Half-widths of the Hermite joins at the dry (s = 1) and full (s = 0) ends; the latter is narrow because
     *  the compressed-liquid pressure rises by K·L ≈ 13 GPa per unit s. */
    private static final double BAND = 3e-2, BAND_LIQUID = 1e-5;

    public static void state(double t, double s, double[] out) {
        state(t, s, out, null);
    }

    /**
     * Per-caller memo of the Hermite join's end states (they depend on T only). Not thread-safe: each element
     * keeps its own.
     */
    public static final class JoinCache {
        double t = Double.NaN, c = Double.NaN;
        final double[] ya = new double[9], ya2 = new double[9], yb = new double[9], yb2 = new double[9];
        /** A second slot: finite differences alternate between T and T + δT. */
        JoinCache other;
    }

    /** As {@link #state(double, double, double[])}, reusing the join ends in {@code cache} when T is unchanged. */
    public static void state(double t, double s, double[] out, JoinCache cache) {
        if (s > 1 - BAND && s < 1 + BAND) {
            blend(t, s, 1, out, cache);
        } else if (s < 0 && s > -2 * BAND_LIQUID) {
            blend(t, s, 0, out, cache);
        } else {
            raw(t, s, out);
        }
    }

    private static void blend(double t, double s, double c, double[] out, JoinCache cache) {
        // the join lies wholly in the single-phase region, so the two-phase states are exact
        double band = c == 1 ? BAND : BAND_LIQUID;
        double a = c == 1 ? 1 - band : -2 * band, b = c == 1 ? 1 + band : 0, d = 1e-3 * band, h = b - a;
        JoinCache j = cache != null ? cache : new JoinCache();
        if (cache != null && (j.t != t || j.c != c)) {
            if (cache.other == null) cache.other = new JoinCache();
            JoinCache o = cache.other;
            if (o.t == t && o.c == c) {
                j = o;
            } else {
                // evict the first slot into the second, refill the first
                cache.other = new JoinCache();
                copy(cache, cache.other);
            }
        }
        if (j.t != t || j.c != c) {
            raw(t, a, j.ya);
            raw(t, a - d, j.ya2);
            raw(t, b, j.yb);
            raw(t, b + d, j.yb2);
            j.t = t;
            j.c = c;
        }
        double u = (s - a) / h, u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        for (int k = 0; k < 9; k++) {
            double ma = (j.ya[k] - j.ya2[k]) / d, mb = (j.yb2[k] - j.yb[k]) / d;
            out[k] = h00 * j.ya[k] + h10 * h * ma + h01 * j.yb[k] + h11 * h * mb;
        }
        if (c == 1) {
            // the energy per mass, u = ρu/ρ, joined monotonically, then ρu = ρ·u (see state)
            double ua = j.ya[RHO_U] / j.ya[RHO], ua2 = j.ya2[RHO_U] / j.ya2[RHO], ub = j.yb[RHO_U] / j.yb[RHO], ub2 = j.yb2[RHO_U] / j.yb2[RHO];
            double ma = (ua - ua2) / d, mb = (ub2 - ub) / d;
            out[RHO_U] = out[RHO] * (h00 * ua + h10 * h * ma + h01 * ub + h11 * h * mb);
        }
    }

    private static void copy(JoinCache from, JoinCache to) {
        to.t = from.t;
        to.c = from.c;
        System.arraycopy(from.ya, 0, to.ya, 0, 9);
        System.arraycopy(from.ya2, 0, to.ya2, 0, 9);
        System.arraycopy(from.yb, 0, to.yb, 0, 9);
        System.arraycopy(from.yb2, 0, to.yb2, 0, 9);
    }

    /** Saturation property set at T (a fresh array): see {@link #SAT_P} etc. */
    public static double[] saturationSet(double t) {
        double[] c = saturation(t);
        return java.util.Arrays.copyOfRange(c, 1, 9);
    }

    /** Indices into {@link #saturationSet}: p_s, dp_s/dT, ρ', ρ'', u', u'', h', h''. */
    public static final int SAT_P = S_P, SAT_DP = S_DP, SAT_RHO_L = S_RL, SAT_RHO_V = S_RV, SAT_U_L = S_UL, SAT_U_V = S_UV,
            SAT_H_L = S_HL, SAT_H_V = S_HV;

    private static void raw(double t, double s, double[] out) {
        t = clampT(t);
        double[] c = saturation(t);
        double ps = c[1 + S_P], rl = c[1 + S_RL], ul = c[1 + S_UL], rvs = c[1 + S_RV], uvs = c[1 + S_UV], hvs = c[1 + S_HV];
        double lr = Math.log(rl / rvs);
        if (s < 0) {
            double p = ps - s * BULK * lr, rho = rl * (1 + (p - ps) / BULK);
            set(out, p, rho, rho * ul, rho, ul + p / rho, rvs, hvs, 0, uvs);
        } else if (s <= 1) {
            double uv = uvs;
            double rho = rl * Math.exp(-lr * s), av = (rl - rho) / (rl - rvs);
            set(out, ps, rho, (1 - av) * rl * ul + av * rvs * uv, rl, ul + ps / rl, rvs, uv + ps / rvs, av, uv);
        } else {
            double p = ps * Math.exp(-lr * (s - 1));
            double ts = Math.min(tSat(p), t);
            double[] cs = saturation(ts);
            double rv = cs[1 + S_RV] * (p / cs[1 + S_P]) * ts / t, uv = cs[1 + S_UV] + CV_VAPOUR * (t - ts);
            set(out, p, rv, rv * uv, rl, ul + p / rl, rv, uv + p / rv, 1, uv);
        }
    }

    private static void set(double[] o, double... v) {
        System.arraycopy(v, 0, o, 0, v.length);
    }

    /** Phase coordinate s of the state with this temperature and pressure (single phase; saturated → s = 0.5). */
    public static double phaseOf(double p, double t) {
        double ps = pSat(t), lr = logDensityRatio(t);
        if (Math.abs(p - ps) < 1e-6 * ps) return 0.5;
        if (p > ps) return -(p - ps) / (BULK * lr);
        return 1 + Math.log(ps / Math.max(p, 1e-3)) / lr;
    }

    /** Phase coordinate of a saturated mixture at T whose liquid fills the given fraction of the volume. */
    public static double phaseForLiquidFraction(double t, double fill) {
        double av = 1 - Math.max(0, Math.min(1, fill)), rl = rhoLiquidSat(t), rv = rhoVapourSat(t);
        double rho = (1 - av) * rl + av * rv;
        return Math.log(rl / rho) / Math.log(rl / rv);
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
