package dev.emergence.core.optics;

import dev.emergence.core.math.Vec3;

/** Geometric optics at a surface: the law of reflection, Snell's law, Fresnel's equations and dispersion. */
public final class Optics {
    private Optics() {
    }

    /** Wavelengths of the Fraunhofer d, F and C lines [nm], which define a glass's index n_d and Abbe number V_d. */
    public static final double LAMBDA_D = 587.56, LAMBDA_F = 486.13, LAMBDA_C = 656.27;

    /**
     * Refractive index at a wavelength [nm] from n_d and the Abbe number V_d = (n_d − 1)/(n_F − n_C), by Cauchy's
     * two-term formula n(λ) = A + B/λ² (good to ~10⁻³ across the visible for optical glasses). V_d ≤ 0: no dispersion.
     */
    public static double cauchy(double nd, double vd, double nm) {
        if (!(vd > 0)) return nd;
        double b = (nd - 1) / vd / (1 / (LAMBDA_F * LAMBDA_F) - 1 / (LAMBDA_C * LAMBDA_C));
        double a = nd - b / (LAMBDA_D * LAMBDA_D);
        return a + b / (nm * nm);
    }

    /** The direction d reflected at a surface of unit normal n (either side): d − 2(d·n)n. */
    public static Vec3 reflect(Vec3 d, Vec3 n) {
        return d.sub(n.mul(2 * d.dot(n)));
    }

    /**
     * The unit direction d refracted at a surface of unit normal n (either side) passing from index n1 into n2
     * (Snell: n1 sin θ1 = n2 sin θ2), or null past the critical angle (total internal reflection).
     */
    public static Vec3 refract(Vec3 d, Vec3 n, double n1, double n2) {
        double c = -d.dot(n);
        Vec3 nn = n;
        if (c < 0) {
            c = -c;
            nn = n.mul(-1);
        }
        double eta = n1 / n2, k = 1 - eta * eta * (1 - c * c);
        if (k < 0) return null;
        return d.mul(eta).add(nn.mul(eta * c - Math.sqrt(k))).normalize();
    }

    /**
     * Fresnel reflectance of unpolarised light, (R_s + R_p)/2, meeting a smooth interface from index n1 into n2 at
     * incidence cosine cosI; 1 past the critical angle. At normal incidence ((n1 − n2)/(n1 + n2))²: 4 % for glass.
     */
    public static double fresnel(double cosI, double n1, double n2) {
        cosI = Math.min(1, Math.abs(cosI));
        double s2 = (n1 / n2) * (n1 / n2) * (1 - cosI * cosI);
        if (s2 >= 1) return 1;
        double cosT = Math.sqrt(1 - s2);
        double rs = (n1 * cosI - n2 * cosT) / (n1 * cosI + n2 * cosT);
        double rp = (n1 * cosT - n2 * cosI) / (n1 * cosT + n2 * cosI);
        return 0.5 * (rs * rs + rp * rp);
    }

    /**
     * Focal length of a lens in air (lensmaker's equation for a thick lens):
     * 1/f = (n − 1)[1/R₁ − 1/R₂ + (n − 1)t/(n R₁ R₂)], radii signed (positive when the centre of curvature lies
     * beyond the surface in the direction light travels), 0 for a flat face; t its thickness on the axis.
     */
    public static double focalLength(double n, double r1, double r2, double t) {
        double c1 = r1 == 0 ? 0 : 1 / r1, c2 = r2 == 0 ? 0 : 1 / r2;
        double p = (n - 1) * (c1 - c2 + (n - 1) * t * c1 * c2 / n);
        return p == 0 ? Double.POSITIVE_INFINITY : 1 / p;
    }

    /** Depth of a spherical surface of signed radius r at distance h from its axis (its sag); 0 for a flat face. */
    public static double sag(double r, double h) {
        if (r == 0) return 0;
        double q = r * r - h * h;
        if (q < 0) return Double.NaN;
        return r - Math.signum(r) * Math.sqrt(q);
    }
}
