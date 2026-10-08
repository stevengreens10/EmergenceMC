package dev.emergence.core.math;

/**
 * Complete elliptic integrals of the first and second kind, K(m) and E(m) with parameter m = k²,
 * computed by the arithmetic–geometric mean (quadratically convergent, accurate to machine precision).
 */
public final class Elliptic {
    private Elliptic() {}

    /** Returns {K(m), E(m)} for 0 ≤ m &lt; 1. */
    public static double[] ke(double m) {
        if (m < 0 || m >= 1) throw new IllegalArgumentException("m out of range: " + m);
        double a = 1.0, b = Math.sqrt(1.0 - m), c = Math.sqrt(m);
        double sum = 0.5 * c * c;
        double pow2 = 0.5;
        for (int i = 0; i < 40 && Math.abs(c) > 1e-16; i++) {
            double an = 0.5 * (a + b);
            double bn = Math.sqrt(a * b);
            c = 0.5 * (a - b);
            a = an;
            b = bn;
            pow2 *= 2;
            sum += pow2 * c * c;
        }
        double k = Math.PI / (2 * a);
        return new double[]{k, k * (1 - sum)};
    }
}
