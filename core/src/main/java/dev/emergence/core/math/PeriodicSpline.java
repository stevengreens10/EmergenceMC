package dev.emergence.core.math;

/**
 * Periodic cubic spline on uniformly spaced samples over [0, 2π). Gives C² interpolation with exact
 * first and second derivatives of the interpolant — needed because coupling torques are derivatives of
 * tabulated flux linkages and the Newton Jacobian needs their derivatives in turn.
 */
public final class PeriodicSpline implements PeriodicFunction {
    private final int n;
    private final double h;
    private final double[] y, m;

    public PeriodicSpline(double[] samples) {
        this.n = samples.length;
        this.h = 2 * Math.PI / n;
        this.y = samples.clone();
        this.m = new double[n];
        if (n >= 3) solveSecondDerivatives();
    }

    /** Solves the cyclic tridiagonal system (h/6)(M[k−1] + 4M[k] + M[k+1]) = (y[k+1] − 2y[k] + y[k−1])/h. */
    private void solveSecondDerivatives() {
        double[] rhs = new double[n];
        for (int k = 0; k < n; k++) {
            rhs[k] = 6.0 * (y[(k + 1) % n] - 2 * y[k] + y[(k - 1 + n) % n]) / (h * h);
        }
        // cyclic system with diag 4, off-diag 1 (corner entries 1) — Sherman–Morrison on Thomas algorithm
        double[] a = new double[n], b = new double[n], c = new double[n];
        for (int k = 0; k < n; k++) {
            a[k] = 1;
            b[k] = 4;
            c[k] = 1;
        }
        double gamma = -b[0];
        b[0] -= gamma;
        b[n - 1] -= a[0] * c[n - 1] / gamma;
        double[] x = thomas(a, b, c, rhs);
        double[] u = new double[n];
        u[0] = gamma;
        u[n - 1] = c[n - 1];
        double[] z = thomas(a, b, c, u);
        double fact = (x[0] + a[0] * x[n - 1] / gamma) / (1.0 + z[0] + a[0] * z[n - 1] / gamma);
        for (int k = 0; k < n; k++) m[k] = x[k] - fact * z[k];
    }

    private static double[] thomas(double[] a, double[] b, double[] c, double[] d) {
        int n = d.length;
        double[] cp = new double[n], dp = new double[n], x = new double[n];
        cp[0] = c[0] / b[0];
        dp[0] = d[0] / b[0];
        for (int i = 1; i < n; i++) {
            double den = b[i] - a[i] * cp[i - 1];
            cp[i] = c[i] / den;
            dp[i] = (d[i] - a[i] * dp[i - 1]) / den;
        }
        x[n - 1] = dp[n - 1];
        for (int i = n - 2; i >= 0; i--) x[i] = dp[i] - cp[i] * x[i + 1];
        return x;
    }

    /** Writes {f(φ), f'(φ), f''(φ)} into out. */
    @Override
    public void eval(double phi, double[] out) {
        double p = phi % (2 * Math.PI);
        if (p < 0) p += 2 * Math.PI;
        int k = (int) (p / h);
        if (k >= n) k = n - 1;
        double t = (p - k * h) / h;
        int k1 = (k + 1) % n;
        double aa = 1 - t, bb = t;
        double yk = y[k], yk1 = y[k1], mk = m[k], mk1 = m[k1];
        out[0] = aa * yk + bb * yk1 + ((aa * aa * aa - aa) * mk + (bb * bb * bb - bb) * mk1) * h * h / 6.0;
        out[1] = (yk1 - yk) / h - (3 * aa * aa - 1) / 6.0 * h * mk + (3 * bb * bb - 1) / 6.0 * h * mk1;
        out[2] = aa * mk + bb * mk1;
    }

    public double value(double phi) {
        double[] o = new double[3];
        eval(phi, o);
        return o[0];
    }

    /** Peak-to-peak amplitude of the samples (useful for diagnostics). */
    @Override
    public double range() {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : y) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo;
    }
}
