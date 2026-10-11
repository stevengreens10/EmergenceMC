package dev.emergence.core.chem;

import dev.emergence.core.units.PhysicalConstants;

import java.util.Map;

/**
 * Vapour–liquid equilibrium of condensable species: each one's saturation pressure (Antoine), its heat of vaporisation
 * (Clausius–Clapeyron from that same curve, so equilibrium and energy agree exactly), its liquid's molar volume, and
 * activity coefficients for non-ideal liquid mixtures (van Laar, for pairs with data; Raoult's ideal solution
 * otherwise). The equilibrium partial pressure of species i over a liquid of mole fractions x is x_i·γ_i·p_sat,i(T)
 * (modified Raoult's law, ideal vapour).
 * <p>
 * Data: Antoine constants (log₁₀ p[mmHg] = A − B/(C + t[°C])) from the classic compilations (water 1–100 °C,
 * ethanol −57–80 °C; outside, extrapolated); van Laar ethanol(1)–water(2) A₁₂ = 1.6798, A₂₁ = 0.9227 (at 1 atm,
 * fitted to the Txy data; azeotrope near x = 0.89, 351 K). Liquid molar volumes at 25 °C.
 */
public final class VaporLiquid {
    private VaporLiquid() {
    }

    /** A condensable species: Antoine constants (mmHg, °C) and liquid molar volume [m³/mol]. */
    public record Condensable(String species, double a, double b, double c, double liquidVolume) {
        /** Saturation pressure [Pa] at temperature T [K]. */
        public double psat(double t) {
            return 133.322_368 * Math.pow(10, a - b / (c + tc(t)));
        }

        /** d ln p_sat / dT [1/K]. */
        public double dlnPsat(double t) {
            double d = c + tc(t);
            return Math.log(10) * b / (d * d);
        }

        /** Heat of vaporisation [J/mol]: R·T²·d ln p_sat/dT (Clausius–Clapeyron, ideal vapour, liquid volume neglected). */
        public double latentHeat(double t) {
            return R * t * t * dlnPsat(t);
        }

        /** d(latent heat)/dT [J/(mol·K)]. */
        public double dLatentHeat(double t) {
            double d = c + tc(t), ln10b = Math.log(10) * b;
            return R * ln10b * (2 * t / (d * d) - 2 * t * t / (d * d * d));
        }

        /** The Antoine fit's own temperature, kept where its denominator stays well away from zero. */
        private double tc(double t) {
            return Math.max(t - 273.15, -c + 40);
        }
    }

    private static final double R = PhysicalConstants.GAS_CONSTANT;

    private static final Map<String, Condensable> TABLE = Map.of(
            "H2O", new Condensable("H2O", 8.07131, 1730.63, 233.426, 18.07e-6),
            "C2H5OH", new Condensable("C2H5OH", 8.20417, 1642.89, 230.300, 58.4e-6));

    /** van Laar constants for ordered pairs: {A_ij, A_ji}. */
    private static final Map<String, double[]> VAN_LAAR = Map.of("C2H5OH|H2O", new double[]{1.6798, 0.9227});

    /** The condensable data of a species, or null. */
    public static Condensable of(String species) {
        return TABLE.get(species);
    }

    /**
     * Activity coefficients γ_i of a liquid of mole fractions x (ids in the same order): van Laar for a pair with data,
     * 1 otherwise (and for any species in a mixture of more than two).
     */
    public static double[] activity(String[] ids, double[] x) {
        double[] g = new double[ids.length];
        java.util.Arrays.fill(g, 1);
        if (ids.length != 2) return g;
        double[] p = VAN_LAAR.get(ids[0] + "|" + ids[1]);
        boolean swap = false;
        if (p == null) {
            p = VAN_LAAR.get(ids[1] + "|" + ids[0]);
            swap = true;
        }
        if (p == null) return g;
        double x1 = swap ? x[1] : x[0], x2 = swap ? x[0] : x[1], a12 = p[0], a21 = p[1];
        double den = a12 * x1 + a21 * x2;
        if (den <= 0) return g;
        double g1 = Math.exp(a12 * Math.pow(a21 * x2 / den, 2)), g2 = Math.exp(a21 * Math.pow(a12 * x1 / den, 2));
        g[0] = swap ? g2 : g1;
        g[1] = swap ? g1 : g2;
        return g;
    }

    /**
     * Bubble point of a liquid at pressure p: the temperature [K] where Σ x_i·γ_i·p_sat,i(T) = p, and the vapour's
     * composition there (y_i = x_i·γ_i·p_sat,i/p). Returns {T, y_0, y_1, …}.
     */
    public static double[] bubblePoint(String[] ids, double[] x, double p) {
        double[] g = activity(ids, x);
        double lo = 200, hi = 700;
        for (int it = 0; it < 100; it++) {
            double mid = 0.5 * (lo + hi), s = 0;
            for (int i = 0; i < ids.length; i++) s += x[i] * g[i] * of(ids[i]).psat(mid);
            if (s > p) hi = mid;
            else lo = mid;
        }
        double t = 0.5 * (lo + hi);
        double[] out = new double[ids.length + 1];
        out[0] = t;
        for (int i = 0; i < ids.length; i++) out[i + 1] = x[i] * g[i] * of(ids[i]).psat(t) / p;
        return out;
    }
}
