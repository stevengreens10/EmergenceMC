package dev.emergence.core.math;

/**
 * A natural cubic spline through equally spaced samples on [a, b], continued linearly beyond the ends (C¹). Used for
 * couplings tabulated over a finite travel (a slide) where {@link PeriodicFourier} serves a full revolution; it
 * implements the same evaluation interface (value, first and second derivative) though it is not periodic.
 */
public final class UniformSpline implements PeriodicFunction {
    private final double a, h;
    private final double[] y, m; // values and second derivatives at the knots
    private final double range;

    public UniformSpline(double a, double b, double[] samples) {
        int n = samples.length;
        if (n < 3) throw new IllegalArgumentException("need at least 3 samples");
        this.a = a;
        this.h = (b - a) / (n - 1);
        this.y = samples.clone();
        // natural spline: tridiagonal system for interior second derivatives (Thomas algorithm)
        m = new double[n];
        double[] c = new double[n], d = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double rhs = 6 * (y[i + 1] - 2 * y[i] + y[i - 1]) / (h * h);
            double denom = 4 - (i > 1 ? c[i - 1] : 0);
            c[i] = 1 / denom;
            d[i] = (rhs - (i > 1 ? d[i - 1] : 0)) / denom;
        }
        for (int i = n - 2; i >= 1; i--) m[i] = d[i] - c[i] * m[i + 1];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : y) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        range = hi - lo;
    }

    @Override
    public void eval(double x, double[] out) {
        int n = y.length;
        double b = a + h * (n - 1);
        if (x < a || x > b) {
            // linear continuation with the end slope
            boolean left = x < a;
            double[] e = new double[3];
            eval(left ? a : b, e);
            double dx = x - (left ? a : b);
            out[0] = e[0] + e[1] * dx;
            out[1] = e[1];
            out[2] = 0;
            return;
        }
        int i = Math.min(n - 2, (int) ((x - a) / h));
        double t0 = a + i * h, A = (t0 + h - x) / h, B = (x - t0) / h;
        out[0] = A * y[i] + B * y[i + 1] + ((A * A * A - A) * m[i] + (B * B * B - B) * m[i + 1]) * h * h / 6;
        out[1] = (y[i + 1] - y[i]) / h - (3 * A * A - 1) / 6 * h * m[i] + (3 * B * B - 1) / 6 * h * m[i + 1];
        out[2] = A * m[i] + B * m[i + 1];
    }

    @Override
    public double range() {
        return range;
    }
}
