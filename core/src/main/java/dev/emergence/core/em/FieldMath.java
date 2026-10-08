package dev.emergence.core.em;

import dev.emergence.core.math.Elliptic;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Closed-form magnetostatic field kernels.
 *
 * <h2>Uniformly charged rectangle (magnetic surface-charge model)</h2>
 * A uniformly magnetised body is exactly equivalent to magnetic surface charge σ = M·n̂ on its faces
 * (no volume charge since ∇·M = 0). For a rectangle u ∈ [u1,u2], v ∈ [v1,v2] in the plane w = w0 the
 * field H = −∇φ, φ = σ/(4π)∬dA/R integrates in closed form (cf. Furlani, <i>Permanent Magnet and
 * Electromechanical Devices</i>, 2001, §4.3; Akoun &amp; Yonnet 1984):
 * <pre>
 *   H_w = σ/4π Σ± atan(Δu·Δv / (Δw·R))
 *   H_u = σ/4π Σ± ln(Δv + R)       (with sign pattern ++ −− for corners)
 *   H_v = σ/4π Σ± ln(Δu + R)
 * </pre>
 * where Δu = u_i − u, Δv = v_j − v (corner relative to field point) and Δw = w − w0.
 *
 * <h2>Circular current filament</h2>
 * Exact field of a circular loop in terms of complete elliptic integrals (Smythe, <i>Static and
 * Dynamic Electricity</i>, §7.10).
 */
public final class FieldMath {
    private FieldMath() {}

    private static final double INV4PI = 1.0 / (4.0 * Math.PI);

    /**
     * Adds to {@code out[0..2]} the field H (A/m) in the sheet's local (u, v, w) frame at local point
     * (pu, pv, pw) due to a rectangle [u1,u2]×[v1,v2] at w = w0 with surface charge density σ (A/m).
     */
    public static void sheetH(double sigma, double u1, double u2, double v1, double v2, double w0,
                              double pu, double pv, double pw, double[] out) {
        double dw = pw - w0;
        if (Math.abs(dw) < 1e-12) dw = dw >= 0 ? 1e-12 : -1e-12;
        double hu = 0, hv = 0, hw = 0;
        for (int i = 0; i < 2; i++) {
            double du = (i == 0 ? u1 : u2) - pu;
            for (int j = 0; j < 2; j++) {
                double dv = (j == 0 ? v1 : v2) - pv;
                double sgn = (i == j) ? 1.0 : -1.0;
                double r = Math.sqrt(du * du + dv * dv + dw * dw);
                hw += sgn * Math.atan(du * dv / (dw * r));
                hu += sgn * logPlusR(dv, du * du + dw * dw, r);
                hv += sgn * logPlusR(du, dv * dv + dw * dw, r);
            }
        }
        double k = sigma * INV4PI;
        // H_u = −∂φ/∂u etc.; with Δu = u_i − u the antiderivatives above already carry the sign.
        out[0] += k * hu;
        out[1] += k * hv;
        out[2] += k * hw;
    }

    /** ln(a + R) where R = √(a² + b2), evaluated without cancellation when a &lt; 0. */
    private static double logPlusR(double a, double b2, double r) {
        if (a >= 0) return Math.log(a + r);
        if (b2 < 1e-30) b2 = 1e-30;
        return Math.log(b2) - Math.log(r - a);
    }

    /**
     * Field of a circular current filament. Writes (B_ρ, B_z) in tesla for a loop of radius {@code a}
     * carrying current {@code i}, at cylindrical coordinates (ρ, z) relative to the loop centre/axis.
     */
    public static void loopB(double a, double i, double rho, double z, double[] out) {
        double mu = PhysicalConstants.MU0 * i / (2 * Math.PI);
        double apr = a + rho;
        double q = apr * apr + z * z;
        double m = 4 * a * rho / q;
        if (m > 1 - 1e-12) m = 1 - 1e-12;
        double[] ke = Elliptic.ke(m);
        double k = ke[0], e = ke[1];
        double amr = a - rho;
        double d = amr * amr + z * z;
        double sq = Math.sqrt(q);
        double bz = mu / sq * (k + (a * a - rho * rho - z * z) / d * e);
        double br;
        if (rho < 1e-9 * a) {
            br = 0.0;
        } else {
            br = mu * z / (rho * sq) * (-k + (a * a + rho * rho + z * z) / d * e);
        }
        out[0] = br;
        out[1] = bz;
    }

    /**
     * Mutual inductance between two coaxial circular filaments of radii a, b separated axially by d
     * (Maxwell's formula): M = μ0·√(ab)·[(2/k − k)K(k²) − (2/k)E(k²)], k² = 4ab/((a+b)² + d²).
     */
    public static double coaxialLoopMutual(double a, double b, double d) {
        double k2 = 4 * a * b / ((a + b) * (a + b) + d * d);
        if (k2 > 1 - 1e-14) k2 = 1 - 1e-14;
        double k = Math.sqrt(k2);
        double[] ke = Elliptic.ke(k2);
        return PhysicalConstants.MU0 * Math.sqrt(a * b) * ((2 / k - k) * ke[0] - (2 / k) * ke[1]);
    }

    /**
     * Self-inductance of a circular loop of radius a whose current is distributed over a cross-section
     * with geometric mean distance g from itself: L = μ0·a·(ln(8a/g) − 2) (Rosa 1908; valid g ≪ a).
     */
    public static double loopSelfInductance(double a, double gmd) {
        return PhysicalConstants.MU0 * a * (Math.log(8 * a / gmd) - 2.0);
    }
}
